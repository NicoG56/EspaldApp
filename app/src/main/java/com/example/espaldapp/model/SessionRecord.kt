package com.example.espaldapp.model

import com.google.firebase.database.IgnoreExtraProperties
import java.text.SimpleDateFormat
import java.util.*

/**
 * Modelo para registrar una sesión completa de monitoreo de postura
 * Se guarda en Firebase: /users/{userId}/sessions/{sessionId}
 */
@IgnoreExtraProperties
data class SessionRecord(
    val sessionId: String = "",
    val startTimestamp: Long = 0L,           // Timestamp de inicio
    val endTimestamp: Long = 0L,             // Timestamp de fin
    val durationMs: Long = 0L,               // Duración total en milisegundos
    val badPostureAlerts: Int = 0,           // Cantidad de alertas de mala postura enviadas
    val breakAlertShown: Boolean = false,    // Si se mostró la alerta de 1 hora
    val umbralVerde: Int = 80,               // Umbral verde usado en la sesión
    val umbralRojo: Int = 120,               // Umbral rojo usado en la sesión
    val userId: String = ""                  // ID del usuario
) {
    // Constructor sin argumentos requerido por Firebase
    constructor() : this("", 0L, 0L, 0L, 0, false, 80, 120, "")
    
    /**
     * Formatea la duración en formato legible: "2h 35m 42s"
     */
    fun formatDuration(): String {
        val seconds = (durationMs / 1000) % 60
        val minutes = (durationMs / (1000 * 60)) % 60
        val hours = (durationMs / (1000 * 60 * 60))
        
        return when {
            hours > 0 -> "${hours}h ${minutes}m ${seconds}s"
            minutes > 0 -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }
    
    /**
     * Formatea la fecha de inicio
     */
    fun formatDate(): String {
        val date = Date(startTimestamp)
        val formatter = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        return formatter.format(date)
    }
    
    /**
     * Formatea solo la fecha (sin hora)
     */
    fun formatDateShort(): String {
        val date = Date(startTimestamp)
        val formatter = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        return formatter.format(date)
    }
    
    /**
     * Formatea solo la hora
     */
    fun formatTime(): String {
        val date = Date(startTimestamp)
        val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
        return formatter.format(date)
    }
    
    /**
     * Obtiene un resumen de la sesión
     */
    fun getSummary(): String {
        return "Duración: ${formatDuration()} | Alertas: $badPostureAlerts"
    }
}
