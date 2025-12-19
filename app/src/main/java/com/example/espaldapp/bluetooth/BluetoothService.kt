package com.example.espaldapp.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import com.example.espaldapp.model.PostureData
import com.example.espaldapp.security.SecurityManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.*

/**
 * Servicio para manejar la comunicación Bluetooth con el HC-06
 * Implementa la conexión, lectura de datos y envío de comandos
 * Con seguridad: CRC16 para integridad y opción de cifrado XOR
 */
class BluetoothService {
    
    companion object {
        private const val TAG = "BluetoothService"
        private val HC06_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB") // UUID estándar SPP
        
        // Configuración de seguridad (desactivado por defecto para compatibilidad con Arduino actual)
        const val USE_ENCRYPTION = false  // Cambiar a true si Arduino implementa cifrado
        const val VERIFY_CRC = false      // Cambiar a true si Arduino envía CRC
    }
    
    private var bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var bluetoothSocket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var readJob: Job? = null

    private var lastConnectedDeviceAddress: String? = null
    
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    
    private val _postureData = MutableStateFlow<PostureData?>(null)
    val postureData: StateFlow<PostureData?> = _postureData.asStateFlow()

    private val _lastDataAtMs = MutableStateFlow<Long?>(null)
    val lastDataAtMs: StateFlow<Long?> = _lastDataAtMs.asStateFlow()
    
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private fun closeResources() {
        try {
            readJob?.cancel()
        } catch (_: Exception) {
        }
        try {
            inputStream?.close()
        } catch (_: Exception) {
        }
        try {
            outputStream?.close()
        } catch (_: Exception) {
        }
        try {
            bluetoothSocket?.close()
        } catch (_: Exception) {
        }

        readJob = null
        inputStream = null
        outputStream = null
        bluetoothSocket = null
    }

    private fun markDisconnected(message: String? = null) {
        closeResources()
        _connectionState.value = ConnectionState.DISCONNECTED
        _postureData.value = null
        if (message != null) {
            _errorMessage.value = message
        }
        Log.d(TAG, "Desconectado")
    }
    
    /**
     * Verifica si el Bluetooth está disponible y habilitado
     */
    fun isBluetoothAvailable(): Boolean {
        return bluetoothAdapter != null && bluetoothAdapter?.isEnabled == true
    }
    
    /**
     * Obtiene la lista de dispositivos emparejados
     */
    @SuppressLint("MissingPermission")
    fun getPairedDevices(): List<BluetoothDevice> {
        return try {
            bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
        } catch (e: SecurityException) {
            Log.e(TAG, "Permisos de Bluetooth no otorgados", e)
            emptyList()
        }
    }
    
    /**
     * Busca el dispositivo HC-06 entre los emparejados
     */
    @SuppressLint("MissingPermission")
    fun findHC06Device(): BluetoothDevice? {
        return try {
            getPairedDevices().find { device ->
                device.name?.contains("HC-06", ignoreCase = true) == true ||
                device.name?.contains("HC-05", ignoreCase = true) == true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error buscando HC-06", e)
            null
        }
    }
    
    /**
     * Conecta al dispositivo Bluetooth especificado
     */
    @SuppressLint("MissingPermission")
    suspend fun connect(device: BluetoothDevice): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Asegura limpieza de cualquier socket anterior (incluso si quedó en estado DISCONNECTED)
            closeResources()
            
            _connectionState.value = ConnectionState.CONNECTING
            
            // Crear socket
            bluetoothSocket = device.createRfcommSocketToServiceRecord(HC06_UUID)
            bluetoothAdapter?.cancelDiscovery()
            
            // Conectar
            bluetoothSocket?.connect()
            
            inputStream = bluetoothSocket?.inputStream
            outputStream = bluetoothSocket?.outputStream

            lastConnectedDeviceAddress = device.address
            
            _connectionState.value = ConnectionState.CONNECTED
            
            // Iniciar lectura continua
            startReading()
            
            // Enviar PING para verificar conexión
            sendCommand("PING")
            
            Log.d(TAG, "Conectado a ${device.name}")
            Result.success(Unit)
            
        } catch (e: IOException) {
            markDisconnected("Error de conexión: ${e.message}")
            Log.e(TAG, "Error conectando", e)
            Result.failure(e)
        } catch (e: SecurityException) {
            markDisconnected("Permisos de Bluetooth no otorgados")
            Log.e(TAG, "Permisos denegados", e)
            Result.failure(e)
        }
    }
    
    /**
     * Desconecta del dispositivo Bluetooth
     */
    fun disconnect() {
        markDisconnected(null)
    }

    /**
     * Reintenta la conexión usando el último dispositivo conectado o buscando HC-06 emparejado.
     */
    @SuppressLint("MissingPermission")
    suspend fun reconnect(): Result<Unit> {
        val device = try {
            val address = lastConnectedDeviceAddress
            if (address != null) {
                getPairedDevices().firstOrNull { it.address == address }
            } else {
                null
            } ?: findHC06Device()
        } catch (e: Exception) {
            return Result.failure(e)
        }

        return if (device != null) {
            connect(device)
        } else {
            Result.failure(IllegalStateException("HC-06 no encontrado (no emparejado)"))
        }
    }
    
    /**
     * Inicia la lectura continua de datos del Arduino
     */
    private fun startReading() {
        readJob?.cancel()
        readJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val reader = inputStream?.bufferedReader()
                    ?: throw IOException("InputStream no disponible")

                while (isActive && _connectionState.value == ConnectionState.CONNECTED) {
                    val line = reader.readLine() ?: throw IOException("Stream cerrado")
                    processLine(line.trim())
                }
            } catch (e: IOException) {
                Log.e(TAG, "Error leyendo datos", e)
                markDisconnected("Conexión perdida")
            } catch (e: CancellationException) {
                // Normal al cancelar
            } catch (e: Exception) {
                Log.e(TAG, "Error inesperado leyendo datos", e)
                markDisconnected("Conexión perdida")
            }
        }
    }
    
    /**
     * Procesa una línea de datos recibida del Arduino
     * Soporta verificación de integridad con CRC16 si está habilitado
     */
    private fun processLine(line: String) {
        Log.d(TAG, "📥 Recibido: $line")
        
        var processedLine = line
        
        // Si la verificación CRC está habilitada, validar integridad
        if (VERIFY_CRC && line.contains(",CRC:")) {
            val validated = SecurityManager.processIncomingMessage(line, USE_ENCRYPTION)
            if (validated == null) {
                Log.e(TAG, "❌ Mensaje rechazado - CRC inválido o error de descifrado")
                return
            }
            processedLine = validated
            Log.d(TAG, "✅ CRC verificado correctamente")
        }
        
        // Parsear datos de postura
        if (processedLine.startsWith("DIST:")) {
            PostureData.fromBluetoothString(processedLine)?.let { data ->
                val estado = data.estadoPostura()
                Log.d(TAG, "✅ Parseado OK → dist:${data.distancia}mm, sentado:${data.sentado}, BAD:${data.malaPostura}, ALR:${data.alertaActiva}, estado:$estado")
                _postureData.value = data
                _lastDataAtMs.value = System.currentTimeMillis()
            } ?: run {
                Log.e(TAG, "❌ Error parseando datos: $processedLine")
            }
        }
        // Respuestas a comandos
        else if (processedLine.startsWith("PONG")) {
            Log.d(TAG, "🏓 PONG recibido - Conexión verificada")
        }
        else if (processedLine.startsWith("OK") || processedLine.startsWith("ERR")) {
            Log.d(TAG, "📨 Respuesta Arduino: $processedLine")
        }
    }
    
    /**
     * Envía un comando al Arduino
     * Agrega CRC16 para verificación de integridad si está habilitado
     */
    suspend fun sendCommand(command: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (_connectionState.value != ConnectionState.CONNECTED) {
                return@withContext Result.failure(Exception("No conectado"))
            }
            
            // Preparar comando con seguridad si está habilitado
            val secureCommand = if (VERIFY_CRC) {
                SecurityManager.prepareOutgoingMessage(command, USE_ENCRYPTION)
            } else {
                command
            }
            
            val data = "$secureCommand\n".toByteArray()
            outputStream?.write(data)
            outputStream?.flush()
            
            if (VERIFY_CRC) {
                Log.d(TAG, "📤 Comando enviado (con CRC): $command")
            } else {
                Log.d(TAG, "📤 Comando enviado: $command")
            }
            Result.success(Unit)
            
        } catch (e: IOException) {
            Log.e(TAG, "Error enviando comando", e)
            markDisconnected("Conexión perdida")
            Result.failure(e)
        }
    }
    
    /**
     * Configura el umbral verde (postura correcta)
     */
    suspend fun setGreenThreshold(mm: Int): Result<Unit> {
        return sendCommand("SET GREEN $mm")
    }
    
    /**
     * Configura el umbral rojo (postura incorrecta)
     */
    suspend fun setRedThreshold(mm: Int): Result<Unit> {
        return sendCommand("SET RED $mm")
    }
    
    /**
     * Configura el tiempo de umbral para alerta
     */
    suspend fun setTimeThreshold(ms: Long): Result<Unit> {
        return sendCommand("SET TIME $ms")
    }
    
    /**
     * Activa o desactiva la alarma
     */
    suspend fun setAlarm(enabled: Boolean): Result<Unit> {
        return sendCommand(if (enabled) "ALARM ON" else "ALARM OFF")
    }
}

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED
}
