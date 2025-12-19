package com.example.espaldapp.viewmodel

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.media.RingtoneManager
import android.media.Ringtone
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.espaldapp.bluetooth.BluetoothService
import com.example.espaldapp.bluetooth.ConnectionState
import com.example.espaldapp.data.PostureRepository
import com.example.espaldapp.data.SessionRepository
import com.example.espaldapp.data.offline.OfflinePostureBuffer
import com.example.espaldapp.model.PostureData
import com.example.espaldapp.model.EstadoPostura
import com.example.espaldapp.model.SessionRecord
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

/**
 * ViewModel para la pantalla Home
 * Gestiona la comunicación Bluetooth, Firebase y el estado de la UI
 */
class HomeViewModel(application: Application) : AndroidViewModel(application) {
    
    companion object {
        private const val TAG = "HomeViewModel"
    }
    
    private val bluetoothService = BluetoothService()
    private val repository = PostureRepository()
    private val sessionRepository = SessionRepository()
    
    // Estados observables
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
    
    private val _toastMessage = MutableSharedFlow<String>()
    val toastMessage: SharedFlow<String> = _toastMessage.asSharedFlow()
    
    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    
    // Variables para control de tiempo de sesión
    private var sessionStartTime: Long = 0
    private var totalSessionTime: Long = 0 // en milisegundos (tiempo efectivo, sin pausas)
    private var isSessionActive = false
    private var hasShownBreakAlert = false // Para mostrar alerta de 1 hora solo una vez

    // Pausa/Reanudación de sesión
    private var isSessionPaused = false
    private var accumulatedSessionTime: Long = 0
    private var currentSegmentStartTime: Long = 0

    // Contingencia: pausa por pérdida de conexión + reconexión
    private var pauseDueToConnectionLoss = false
    private var manualDisconnect = false
    private var reconnectJob: Job? = null
    private var lastOfflineToastAtMs: Long = 0L
    private var lastWatchdogToastAtMs: Long = 0L

    private val staleDataTimeoutMs: Long = 6_000L
    private val watchdogIntervalMs: Long = 1_000L
    
    // Contador de alertas de mala postura en la sesión actual
    private var badPostureAlertCount = 0
    
    // Variables para alerta de mala postura
    private var badPostureStartTime: Long = 0
    private var isBadPosture = false
    private var alertJob: Job? = null
    
    // Ringtone para reproducir sonido de alerta
    private var alertRingtone: Ringtone? = null
    
    init {
        // Inicializar ringtone de manera segura
        try {
            val notificationUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            alertRingtone = RingtoneManager.getRingtone(getApplication(), notificationUri)
        } catch (e: Exception) {
            Log.e(TAG, "Error al inicializar ringtone de alerta", e)
        }
        
        // Observar estado de conexión Bluetooth
        viewModelScope.launch {
            bluetoothService.connectionState.collect { state ->
                _uiState.update { it.copy(connectionState = state) }

                when (state) {
                    ConnectionState.CONNECTED -> {
                        // Conexión establecida
                        manualDisconnect = false
                        _uiState.update { it.copy(isReconnecting = false) }
                        reconnectJob?.cancel()
                        reconnectJob = null

                        // Iniciar sesión cuando se conecta por primera vez
                        if (!isSessionActive) {
                            startSession()
                        }

                        // Si la pausa fue por pérdida de conexión, mantener pausado hasta acción del usuario
                        if (pauseDueToConnectionLoss) {
                            setPausedInternal(true)
                            viewModelScope.launch {
                                _toastMessage.emit("Reconectado. Sesión sigue en pausa")
                            }
                        }
                    }

                    ConnectionState.DISCONNECTED -> {
                        // Si el usuario desconectó manualmente, mantener el comportamiento anterior
                        if (manualDisconnect) {
                            if (isSessionActive) stopSession()
                            _uiState.update { it.copy(isReconnecting = false) }
                            pauseDueToConnectionLoss = false
                        } else {
                            // Contingencia: pausar y reintentar reconectar
                            if (isSessionActive) {
                                if (!isSessionPaused) {
                                    pauseDueToConnectionLoss = true
                                    setPausedInternal(true)
                                }
                                startAutoReconnect()
                            }
                        }
                    }

                    ConnectionState.CONNECTING -> {
                        // Nada especial
                    }
                }
            }
        }
        
        // Observar datos de postura desde Bluetooth
        viewModelScope.launch {
            bluetoothService.postureData.collect { data ->
                data?.let {
                    Log.d(TAG, "Datos recibidos - sentado: ${it.sentado}, distancia: ${it.distancia}mm")

                    // Sincronizar pausa desde Arduino si viene en el payload
                    syncPauseFromDevice(it.pausado)
                    
                    // Monitorear mala postura para alerta
                    checkBadPostureAlert(it)
                    
                    // Actualizar solo los datos de postura, el tiempo se actualiza en otro coroutine
                    _uiState.update { state ->
                        state.copy(postureData = it)
                    }
                    // Guardar en Firebase
                    saveToFirebase(it)
                }
            }
        }
        
        // Observar errores de Bluetooth
        viewModelScope.launch {
            bluetoothService.errorMessage.collect { error ->
                error?.let { _toastMessage.emit(it) }
            }
        }
        
        // Observar datos desde Firebase (para sincronización entre dispositivos)
        viewModelScope.launch {
            try {
                repository.observePostureData().collect { data ->
                    // Solo actualizar si no hay datos de Bluetooth recientes
                    if (_uiState.value.connectionState != ConnectionState.CONNECTED) {
                        data?.let {
                            _uiState.update { state ->
                                state.copy(postureData = it)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error observando datos de Firebase", e)
            }
        }
        
        // Actualizar tiempo de sesión cada segundo
        viewModelScope.launch {
            while (true) {
                if (isSessionActive) {
                    val currentTime = System.currentTimeMillis()
                    totalSessionTime = computeEffectiveSessionDuration(currentTime)
                    _uiState.update { it.copy(seatedTime = formatSeatedTime(totalSessionTime)) }
                    
                    // Verificar si ha pasado 1 hora (3600000 ms)
                    if (totalSessionTime >= 3600000 && !hasShownBreakAlert) {
                        hasShownBreakAlert = true
                        Log.d(TAG, "⏰ 1 hora de sesión completada - mostrando alerta de descanso")
                        showBreakAlert()
                    }
                }
                kotlinx.coroutines.delay(1000)
            }
        }

        // Watchdog: si está CONNECTED pero no llegan lecturas, forzar reconexión.
        viewModelScope.launch {
            while (true) {
                try {
                    val state = _uiState.value.connectionState
                    if (!manualDisconnect && isSessionActive && state == ConnectionState.CONNECTED) {
                        val lastDataAt = bluetoothService.lastDataAtMs.value
                        if (lastDataAt != null) {
                            val now = System.currentTimeMillis()
                            val gap = now - lastDataAt
                            if (gap >= staleDataTimeoutMs) {
                                if (!isSessionPaused) {
                                    pauseDueToConnectionLoss = true
                                    setPausedInternal(true)
                                }

                                if (now - lastWatchdogToastAtMs > 10_000L) {
                                    lastWatchdogToastAtMs = now
                                    _toastMessage.emit("No llegan datos del sensor. Reintentando conexión...")
                                }

                                // Fuerza un reinicio completo del socket/lector
                                bluetoothService.disconnect()
                                startAutoReconnect()
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Ignorar y reintentar
                }

                delay(watchdogIntervalMs)
            }
        }
        
        // Verificar disponibilidad de Bluetooth
        checkBluetoothAvailability()
    }
    
    /**
     * Inicia la sesión de medición
     */
    private fun startSession() {
        val now = System.currentTimeMillis()
        sessionStartTime = now
        totalSessionTime = 0
        isSessionActive = true
        hasShownBreakAlert = false
        badPostureAlertCount = 0 // Resetear contador de alertas

        isSessionPaused = false
        accumulatedSessionTime = 0
        currentSegmentStartTime = now

        _uiState.update { it.copy(isPaused = false) }
        Log.d(TAG, "✓ Sesión iniciada - comenzando a contar tiempo")
    }
    
    /**
     * Detiene la sesión de medición (automática por desconexión)
     */
    private fun stopSession() {
        // Cerrar el segmento activo si estaba corriendo
        val now = System.currentTimeMillis()
        totalSessionTime = computeEffectiveSessionDuration(now)
        isSessionActive = false
        Log.d(TAG, "✗ Sesión detenida - tiempo total: ${formatSeatedTime(totalSessionTime)}")
    }

    private fun startAutoReconnect() {
        if (reconnectJob?.isActive == true) return
        _uiState.update { it.copy(isReconnecting = true) }

        reconnectJob = viewModelScope.launch {
            var backoffMs = 3_000L
            val maxBackoffMs = 30_000L

            while (true) {
                // Si el usuario ya desconectó manualmente o ya reconectó, cortar
                if (manualDisconnect) {
                    _uiState.update { it.copy(isReconnecting = false) }
                    return@launch
                }
                if (_uiState.value.connectionState == ConnectionState.CONNECTED) {
                    _uiState.update { it.copy(isReconnecting = false) }
                    return@launch
                }

                try {
                    _uiState.update { it.copy(connectionState = ConnectionState.CONNECTING, isReconnecting = true) }
                    val result = bluetoothService.reconnect()
                    if (result.isSuccess) {
                        // El collector de connectionState terminará de actualizar
                        backoffMs = 3_000L
                    } else {
                        backoffMs = (backoffMs * 2).coerceAtMost(maxBackoffMs)
                    }
                } catch (e: Exception) {
                    backoffMs = (backoffMs * 2).coerceAtMost(maxBackoffMs)
                }

                delay(backoffMs)
            }
        }
    }
    
    /**
     * Muestra alerta de descanso después de 1 hora
     */
    private fun showBreakAlert() {
        viewModelScope.launch {
            if (_uiState.value.alarmEnabled) {
                playAlertSound()
            }
            _toastMessage.emit("⏰ ¡Es hora de tomar un descanso! Has completado 1 hora de sesión")
        }
    }
    
    /**
     * Monitorea mala postura y activa alerta después de 5 segundos
     */
    private fun checkBadPostureAlert(data: PostureData) {
        if (isSessionPaused || data.pausado) {
            // Si está pausado, no contar ni disparar alertas
            isBadPosture = false
            alertJob?.cancel()
            return
        }

        val estado = data.estadoPostura()
        val esMalaPostura = estado == EstadoPostura.MALA || estado == EstadoPostura.ALERTA
        
        if (esMalaPostura && !isBadPosture) {
            // Inicio de mala postura - iniciar temporizador solo si alarma está habilitada
            isBadPosture = true
            badPostureStartTime = System.currentTimeMillis()
            
            if (_uiState.value.alarmEnabled) {
                Log.d(TAG, "⚠️ Mala postura detectada - iniciando temporizador de 5 segundos")
                
                // Cancelar alerta anterior si existe
                alertJob?.cancel()
                
                // Programar alerta para 5 segundos
                alertJob = viewModelScope.launch {
                    delay(5000) // 5 segundos
                    if (isBadPosture) {
                        Log.d(TAG, "🔔 Activando alerta sonora - 5 segundos en mala postura")
                        badPostureAlertCount++ // Incrementar contador
                        playAlertSound()
                        _toastMessage.emit("¡Corrige tu postura!")
                    }
                }
            } else {
                Log.d(TAG, "⚠️ Mala postura detectada (alarma desactivada)")
            }
        } else if (!esMalaPostura && isBadPosture) {
            // Postura corregida - cancelar temporizador
            isBadPosture = false
            alertJob?.cancel()
            val tiempoMalaPostura = System.currentTimeMillis() - badPostureStartTime
            Log.d(TAG, "✓ Postura corregida después de ${tiempoMalaPostura}ms")
        }
    }
    
    /**
     * Reproduce el sonido de alerta
     */
    private fun playAlertSound() {
        try {
            alertRingtone?.play()
        } catch (e: Exception) {
            Log.e(TAG, "Error reproduciendo sonido de alerta", e)
        }
    }
    
    /**
     * Formatea el tiempo en milisegundos a formato HH:mm:ss
     */
    private fun formatSeatedTime(timeMs: Long): String {
        val seconds = (timeMs / 1000) % 60
        val minutes = (timeMs / (1000 * 60)) % 60
        val hours = (timeMs / (1000 * 60 * 60))
        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }
    
    /**
     * Reinicia el contador de tiempo de sesión manualmente
     */
    fun resetSessionTimer() {
        if (isSessionActive) {
            val now = System.currentTimeMillis()
            sessionStartTime = now
            totalSessionTime = 0
            hasShownBreakAlert = false
            isSessionPaused = false
            accumulatedSessionTime = 0
            currentSegmentStartTime = now
            _uiState.update { it.copy(isPaused = false) }
            Log.d(TAG, "🔄 Cronómetro de sesión reiniciado")
        }
        _uiState.update { it.copy(seatedTime = "00:00:00") }
        viewModelScope.launch {
            _toastMessage.emit("Cronómetro reiniciado")
        }
    }

    /**
     * Pausa o reanuda el contador de tiempo de sesión.
     * Si hay conexión Bluetooth, intenta sincronizar con Arduino (PAUSE ON/OFF).
     */
    fun togglePauseResume() {
        if (!isSessionActive) return

        val targetPaused = !isSessionPaused
        setPausedInternal(targetPaused)

        // Si el usuario reanuda manualmente, ya no es una pausa por pérdida de conexión
        if (!targetPaused) {
            pauseDueToConnectionLoss = false
        }

        // Intentar sincronizar con Arduino (opcional)
        viewModelScope.launch {
            if (_uiState.value.connectionState == ConnectionState.CONNECTED) {
                val cmd = if (targetPaused) "PAUSE ON" else "PAUSE OFF"
                val result = bluetoothService.sendCommand(cmd)
                if (result.isFailure) {
                    _toastMessage.emit("No se pudo enviar $cmd al Arduino")
                }
            }
        }
    }

    private fun syncPauseFromDevice(devicePaused: Boolean) {
        // Si Arduino no envía PAUS, esto queda en false y no hace nada.
        if (!isSessionActive) return

        // Si pausamos por pérdida de conexión, no permitir que el payload lo des-paue automáticamente.
        if (pauseDueToConnectionLoss && !devicePaused) return

        if (devicePaused == isSessionPaused) return
        setPausedInternal(devicePaused)
    }

    private fun setPausedInternal(paused: Boolean) {
        if (!isSessionActive) return
        val now = System.currentTimeMillis()

        if (paused) {
            if (!isSessionPaused) {
                accumulatedSessionTime += (now - currentSegmentStartTime)
            }
            isSessionPaused = true
        } else {
            if (isSessionPaused) {
                currentSegmentStartTime = now
            }
            isSessionPaused = false
        }

        totalSessionTime = computeEffectiveSessionDuration(now)
        _uiState.update {
            it.copy(
                seatedTime = formatSeatedTime(totalSessionTime),
                isPaused = isSessionPaused
            )
        }
    }

    private fun computeEffectiveSessionDuration(now: Long): Long {
        if (!isSessionActive) return 0
        return accumulatedSessionTime + if (isSessionPaused) 0 else (now - currentSegmentStartTime)
    }
    
    /**
     * Verifica si Bluetooth está disponible
     */
    private fun checkBluetoothAvailability() {
        val isAvailable = bluetoothService.isBluetoothAvailable()
        _uiState.update { it.copy(bluetoothAvailable = isAvailable) }
        
        Log.d(TAG, "Bluetooth disponible: $isAvailable")
        
        if (!isAvailable) {
            viewModelScope.launch {
                _toastMessage.emit("Bluetooth no disponible o deshabilitado")
            }
        }
    }
    
    /**
     * Obtiene la lista de dispositivos Bluetooth emparejados
     */
    @SuppressLint("MissingPermission")
    fun getPairedDevices(): List<BluetoothDevice> {
        return bluetoothService.getPairedDevices()
    }
    
    /**
     * Conecta automáticamente al HC-06 si está emparejado
     */
    fun connectToHC06() {
        Log.d(TAG, "Intentando conectar a HC-06...")
        viewModelScope.launch {
            try {
                // Verificar Bluetooth disponible
                if (!bluetoothService.isBluetoothAvailable()) {
                    Log.e(TAG, "Bluetooth no disponible")
                    _toastMessage.emit("Bluetooth no está habilitado. Por favor, actívalo en configuración.")
                    return@launch
                }
                
                // Buscar dispositivo HC-06
                val device = bluetoothService.findHC06Device()
                Log.d(TAG, "Dispositivo encontrado: ${device?.name ?: "ninguno"}")
                
                if (device != null) {
                    _toastMessage.emit("Conectando a ${device.name}...")
                    connectToDevice(device)
                } else {
                    // Mostrar dispositivos emparejados para debug
                    val pairedDevices = bluetoothService.getPairedDevices()
                    Log.d(TAG, "Dispositivos emparejados: ${pairedDevices.map { it.name }}")
                    _toastMessage.emit("HC-06 no encontrado. Emparéjalo primero en Configuración → Bluetooth")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error al conectar", e)
                _toastMessage.emit("Error: ${e.message}")
            }
        }
    }
    
    /**
     * Conecta a un dispositivo Bluetooth específico
     */
    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice) {
        viewModelScope.launch {
            Log.d(TAG, "Conectando a dispositivo: ${device.name} (${device.address})")
            val result = bluetoothService.connect(device)
            if (result.isSuccess) {
                Log.d(TAG, "Conexión exitosa")
                _toastMessage.emit("✓ Conectado a ${device.name}")
            } else {
                val error = result.exceptionOrNull()
                Log.e(TAG, "Error de conexión", error)
                _toastMessage.emit("✗ Error: ${error?.message ?: "Desconocido"}")
            }
        }
    }
    
    /**
     * Desconecta del dispositivo Bluetooth actual
     */
    fun disconnect() {
        manualDisconnect = true
        pauseDueToConnectionLoss = false
        reconnectJob?.cancel()
        reconnectJob = null
        bluetoothService.disconnect()
        viewModelScope.launch {
            _toastMessage.emit("Desconectado")
        }
    }
    
    /**
     * Guarda los datos de postura en Firebase
     */
    private fun saveToFirebase(data: PostureData) {
        viewModelScope.launch {
            val result = repository.savePostureData(data)
            if (result.isFailure) {
                // Contingencia: guardar localmente para no perder datos
                OfflinePostureBuffer.enqueue(getApplication(), data)

                val now = System.currentTimeMillis()
                if (now - lastOfflineToastAtMs > 10_000L) {
                    lastOfflineToastAtMs = now
                    _toastMessage.emit("Sin conexión a Firebase: guardando datos localmente")
                }
            } else {
                // Si volvió la conexión, intentar vaciar buffer en pequeños lotes
                flushOfflineBuffer()
            }
        }
    }

    private fun flushOfflineBuffer() {
        viewModelScope.launch {
            // Evitar drenar agresivamente (energía + cuota). Lotes pequeños.
            val batch = OfflinePostureBuffer.peek(getApplication(), maxItems = 20)
            if (batch.isEmpty()) return@launch

            var sent = 0
            for (item in batch) {
                val r = repository.saveToHistory(item)
                if (r.isSuccess) {
                    sent++
                } else {
                    break
                }
            }
            if (sent > 0) {
                OfflinePostureBuffer.dropFirst(getApplication(), sent)
            }
        }
    }
    
    /**
     * Guarda la sesión actual en el historial
     */
    fun saveSessionToHistory() {
        viewModelScope.launch {
            _uiState.value.postureData?.let { data ->
                val result = repository.saveToHistory(data)
                if (result.isSuccess) {
                    _toastMessage.emit("Sesión guardada en historial")
                } else {
                    _toastMessage.emit("Error guardando sesión: ${result.exceptionOrNull()?.message}")
                }
            }
        }
    }
    
    /**
     * Configura el umbral verde (postura correcta)
     */
    fun setGreenThreshold(mm: Int) {
        viewModelScope.launch {
            val result = bluetoothService.setGreenThreshold(mm)
            if (result.isSuccess) {
                repository.updateThresholds(mm, _uiState.value.postureData?.umbralRojo ?: 120)
                _toastMessage.emit("Umbral verde configurado: $mm mm")
            } else {
                _toastMessage.emit("Error configurando umbral verde")
            }
        }
    }
    
    /**
     * Configura el umbral rojo (postura incorrecta)
     */
    fun setRedThreshold(mm: Int) {
        viewModelScope.launch {
            val result = bluetoothService.setRedThreshold(mm)
            if (result.isSuccess) {
                repository.updateThresholds(_uiState.value.postureData?.umbralVerde ?: 80, mm)
                _toastMessage.emit("Umbral rojo configurado: $mm mm")
            } else {
                _toastMessage.emit("Error configurando umbral rojo")
            }
        }
    }
    
    /**
     * Configura el tiempo de umbral para alerta
     */
    fun setTimeThreshold(seconds: Int) {
        viewModelScope.launch {
            val ms = seconds * 1000L
            val result = bluetoothService.setTimeThreshold(ms)
            if (result.isSuccess) {
                _toastMessage.emit("Tiempo de alerta configurado: $seconds segundos")
            } else {
                _toastMessage.emit("Error configurando tiempo de alerta")
            }
        }
    }
    
    /**
     * Activa o desactiva la alarma
     */
    fun toggleAlarm(enabled: Boolean) {
        viewModelScope.launch {
            val result = bluetoothService.setAlarm(enabled)
            if (result.isSuccess) {
                _uiState.update { it.copy(alarmEnabled = enabled) }
                _toastMessage.emit(if (enabled) "Alarma activada" else "Alarma desactivada")
            } else {
                _toastMessage.emit("Error al configurar alarma")
            }
        }
    }
    
    /**
     * Finaliza la sesión actual manualmente y la guarda en Firebase
     */
    fun finalizarSesion() {
        if (!isSessionActive) {
            viewModelScope.launch {
                _toastMessage.emit("No hay una sesión activa")
            }
            return
        }
        
        viewModelScope.launch {
            try {
                val endTime = System.currentTimeMillis()
                val duration = computeEffectiveSessionDuration(endTime)
                
                // Crear registro de sesión
                val session = SessionRecord(
                    sessionId = "",  // Se genera automáticamente
                    startTimestamp = sessionStartTime,
                    endTimestamp = endTime,
                    durationMs = duration,
                    badPostureAlerts = badPostureAlertCount,
                    breakAlertShown = hasShownBreakAlert,
                    umbralVerde = _uiState.value.postureData?.umbralVerde ?: 80,
                    umbralRojo = _uiState.value.postureData?.umbralRojo ?: 120
                )
                
                Log.d(TAG, "💾 Guardando sesión:")
                Log.d(TAG, "  Start: $sessionStartTime (${java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(sessionStartTime))})")
                Log.d(TAG, "  End: $endTime (${java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(endTime))})")
                Log.d(TAG, "  Duration: ${session.formatDuration()}")
                Log.d(TAG, "  Alerts: $badPostureAlertCount")
                
                // Guardar en Firebase
                val result = sessionRepository.saveSession(session)
                
                if (result.isSuccess) {
                    Log.d(TAG, "✅ Sesión guardada exitosamente")
                    _toastMessage.emit("Sesión finalizada: ${session.formatDuration()} | ${badPostureAlertCount} alertas")
                    
                    // Resetear valores de sesión
                    isSessionActive = false
                    sessionStartTime = 0
                    totalSessionTime = 0
                    badPostureAlertCount = 0
                    hasShownBreakAlert = false
                    isSessionPaused = false
                    accumulatedSessionTime = 0
                    currentSegmentStartTime = 0
                    _uiState.update { it.copy(isPaused = false) }
                    _uiState.update { it.copy(seatedTime = "00:00:00") }
                    
                } else {
                    Log.e(TAG, "❌ Error guardando sesión", result.exceptionOrNull())
                    _toastMessage.emit("Error al guardar la sesión")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error finalizando sesión", e)
                _toastMessage.emit("Error al finalizar la sesión")
            }
        }
    }
    
    /**
     * Reinicia la sesión actual: guarda la actual y comienza una nueva
     */
    fun reiniciarSesion() {
        if (!isSessionActive) {
            viewModelScope.launch {
                _toastMessage.emit("No hay una sesión activa")
            }
            return
        }
        
        viewModelScope.launch {
            try {
                val endTime = System.currentTimeMillis()
                val duration = computeEffectiveSessionDuration(endTime)
                
                // Solo guardar si hay duración significativa (más de 5 segundos)
                if (duration > 5000) {
                    // Crear registro de sesión
                    val session = SessionRecord(
                        sessionId = "",
                        startTimestamp = sessionStartTime,
                        endTimestamp = endTime,
                        durationMs = duration,
                        badPostureAlerts = badPostureAlertCount,
                        breakAlertShown = hasShownBreakAlert,
                        umbralVerde = _uiState.value.postureData?.umbralVerde ?: 80,
                        umbralRojo = _uiState.value.postureData?.umbralRojo ?: 120
                    )
                    
                    Log.d(TAG, "💾 Guardando sesión antes de reiniciar: ${session.formatDuration()} | $badPostureAlertCount alertas")
                    
                    // Guardar en Firebase
                    sessionRepository.saveSession(session)
                }
                
                // Reiniciar valores de sesión
                val now = System.currentTimeMillis()
                sessionStartTime = now
                totalSessionTime = 0
                badPostureAlertCount = 0
                hasShownBreakAlert = false
                isSessionPaused = false
                accumulatedSessionTime = 0
                currentSegmentStartTime = now
                _uiState.update { it.copy(isPaused = false) }
                _uiState.update { it.copy(seatedTime = "00:00:00") }
                
                _toastMessage.emit("🔄 Sesión guardada y reiniciada")
                Log.d(TAG, "🔄 Sesión reiniciada")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error reiniciando sesión", e)
                _toastMessage.emit("Error al reiniciar la sesión")
            }
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        reconnectJob?.cancel()
        bluetoothService.disconnect()
        alertJob?.cancel()
        alertRingtone?.stop()
    }
}

/**
 * Estado de la UI
 */
data class HomeUiState(
    val postureData: PostureData? = null,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val bluetoothAvailable: Boolean = false,
    val alarmEnabled: Boolean = true,
    val seatedTime: String = "00:00:00",
    val isPaused: Boolean = false,
    val isReconnecting: Boolean = false
)
