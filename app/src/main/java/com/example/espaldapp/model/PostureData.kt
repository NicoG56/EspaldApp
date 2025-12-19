package com.example.espaldapp.model

import com.google.firebase.database.IgnoreExtraProperties

/**
 * Modelo de datos que representa la información de postura del Arduino
 * Compatible con el formato enviado por HC-06:
 * DIST:<mm>,SENT:<0/1>,BAD:<0/1>,ALR:<0/1>,GREEN:<mm>,RED:<mm>[,PAUS:<0/1>]
 */
@IgnoreExtraProperties
data class PostureData(
    val distancia: Int = 0,              // Distancia en milímetros (mm)
    val sentado: Boolean = false,         // Usuario está sentado
    val malaPostura: Boolean = false,     // Postura incorrecta detectada
    val alertaActiva: Boolean = false,    // Alerta por mala postura sostenida
    val umbralVerde: Int = 80,           // Umbral para postura correcta (mm)
    val umbralRojo: Int = 120,           // Umbral para postura incorrecta (mm)
    val pausado: Boolean = false,         // Sesión pausada (botón físico / app)
    val timestamp: Long = System.currentTimeMillis()  // Timestamp de la lectura
) {
    // Calcula la distancia en centímetros para la UI
    fun distanciaCm(): Int = distancia / 10
    
    // Determina el estado de la postura
    fun estadoPostura(): EstadoPostura {
        // IMPORTANTE: Confiar en los flags calculados por el Arduino
        // para mantener sincronización exacta con LEDs y lógica del sensor

        // Si está pausado, se considera estado neutro (no evaluar mala postura)
        if (pausado) return EstadoPostura.CORRECTA
        
        // Si Arduino activa alerta (tiempo sostenido en mala postura)
        if (alertaActiva) return EstadoPostura.ALERTA
        
        // Si Arduino detecta mala postura (BAD:1)
        if (malaPostura) return EstadoPostura.MALA
        
        // Si no está sentado o sin lectura válida, no hay advertencia
        if (!sentado || distancia == 0) return EstadoPostura.CORRECTA
        
        // Si la distancia está en zona amarilla (entre umbrales)
        if (distancia > umbralVerde && distancia <= umbralRojo) {
            return EstadoPostura.ADVERTENCIA
        }
        
        // Postura correcta
        return EstadoPostura.CORRECTA
    }
    
    companion object {
        /**
         * Parsea el string recibido del Arduino vía Bluetooth
         * Formato: DIST:123,SENT:1,BAD:0,ALR:0,GREEN:80,RED:120[,PAUS:0/1]
         */
        fun fromBluetoothString(data: String): PostureData? {
            return try {
                val map = data.split(",").associate { part ->
                    val (key, value) = part.split(":")
                    key.trim() to value.trim()
                }
                
                android.util.Log.d("PostureData", "Parse map: DIST=${map["DIST"]}, SENT=${map["SENT"]}, BAD=${map["BAD"]}, ALR=${map["ALR"]}")
                
                PostureData(
                    distancia = map["DIST"]?.toIntOrNull() ?: 0,
                    sentado = map["SENT"] == "1",
                    malaPostura = map["BAD"] == "1",
                    alertaActiva = map["ALR"] == "1",
                    umbralVerde = map["GREEN"]?.toIntOrNull() ?: 80,
                    umbralRojo = map["RED"]?.toIntOrNull() ?: 120,
                    pausado = map["PAUS"] == "1"
                )
            } catch (e: Exception) {
                android.util.Log.e("PostureData", "Error parseando: $data", e)
                null
            }
        }
    }
}

enum class EstadoPostura {
    CORRECTA,      // Postura correcta (verde) - distancia <= umbralVerde
    ADVERTENCIA,   // Postura en zona amarilla - umbralVerde < distancia <= umbralRojo
    MALA,          // Postura incorrecta (rojo) - distancia > umbralRojo
    ALERTA         // Alerta activa por mala postura sostenida
}
