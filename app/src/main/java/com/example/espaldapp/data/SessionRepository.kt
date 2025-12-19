package com.example.espaldapp.data

import android.util.Log
import com.example.espaldapp.model.SessionRecord
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/**
 * Repositorio para gestionar sesiones de postura en Firebase
 * Estructura: /users/{userId}/sessions/{sessionId}
 */
class SessionRepository {
    
    companion object {
        private const val TAG = "SessionRepository"
    }
    
    private val database: DatabaseReference = FirebaseDatabase.getInstance().reference
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
    
    /**
     * Obtiene el userId actual
     */
    private fun getCurrentUserId(): String? = auth.currentUser?.uid
    
    /**
     * Referencia al nodo de sesiones del usuario
     */
    private fun getUserSessionsRef(): DatabaseReference? {
        val userId = getCurrentUserId() ?: return null
        return database.child("users").child(userId).child("sessions")
    }
    
    /**
     * Guarda una sesión completa en Firebase
     */
    suspend fun saveSession(session: SessionRecord): Result<String> {
        return try {
            val ref = getUserSessionsRef() ?: return Result.failure(Exception("Usuario no autenticado"))
            
            // Generar ID único si no tiene
            val sessionId = if (session.sessionId.isEmpty()) {
                ref.push().key ?: return Result.failure(Exception("Error generando ID"))
            } else {
                session.sessionId
            }
            
            // Guardar con el ID generado
            val sessionWithId = session.copy(
                sessionId = sessionId,
                userId = getCurrentUserId() ?: ""
            )
            
            ref.child(sessionId).setValue(sessionWithId).await()
            
            Log.d(TAG, "✅ Sesión guardada: $sessionId - ${sessionWithId.formatDuration()}")
            Result.success(sessionId)
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error guardando sesión", e)
            Result.failure(e)
        }
    }
    
    /**
     * Obtiene el historial de sesiones (últimas N sesiones)
     */
    suspend fun getSessionHistory(limit: Int = 50): Result<List<SessionRecord>> {
        return try {
            val ref = getUserSessionsRef() ?: return Result.failure(Exception("Usuario no autenticado"))
            
            val snapshot = ref
                .orderByChild("startTimestamp")
                .limitToLast(limit)
                .get()
                .await()
            
            val sessions = mutableListOf<SessionRecord>()
            snapshot.children.forEach { child ->
                child.getValue(SessionRecord::class.java)?.let { session ->
                    sessions.add(session)
                }
            }
            
            // Ordenar por fecha descendente (más reciente primero)
            sessions.sortByDescending { it.startTimestamp }
            
            Log.d(TAG, "✅ Historial obtenido: ${sessions.size} sesiones")
            Result.success(sessions)
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error obteniendo historial", e)
            Result.failure(e)
        }
    }
    
    /**
     * Observa cambios en el historial de sesiones en tiempo real
     */
    fun observeSessionHistory(limit: Int = 50): Flow<List<SessionRecord>> = callbackFlow {
        val ref = getUserSessionsRef()
        
        if (ref == null) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }
        
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val sessions = mutableListOf<SessionRecord>()
                snapshot.children.forEach { child ->
                    child.getValue(SessionRecord::class.java)?.let { session ->
                        sessions.add(session)
                    }
                }
                
                // Ordenar por fecha descendente
                sessions.sortByDescending { it.startTimestamp }
                
                // Limitar resultados
                val limited = sessions.take(limit)
                
                trySend(limited)
            }
            
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Error observando sesiones", error.toException())
                close(error.toException())
            }
        }
        
        ref.orderByChild("startTimestamp")
            .limitToLast(limit)
            .addValueEventListener(listener)
        
        awaitClose {
            ref.removeEventListener(listener)
        }
    }
    
    /**
     * Obtiene estadísticas generales del usuario
     */
    suspend fun getStatistics(): Result<SessionStatistics> {
        return try {
            val sessionsResult = getSessionHistory(100)
            if (sessionsResult.isFailure) {
                return Result.failure(sessionsResult.exceptionOrNull() ?: Exception("Error"))
            }
            
            val sessions = sessionsResult.getOrNull() ?: emptyList()
            
            val stats = SessionStatistics(
                totalSessions = sessions.size,
                totalDurationMs = sessions.sumOf { it.durationMs },
                totalAlerts = sessions.sumOf { it.badPostureAlerts },
                averageDurationMs = if (sessions.isNotEmpty()) {
                    sessions.sumOf { it.durationMs } / sessions.size
                } else 0L,
                averageAlerts = if (sessions.isNotEmpty()) {
                    sessions.sumOf { it.badPostureAlerts }.toDouble() / sessions.size
                } else 0.0
            )
            
            Result.success(stats)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error calculando estadísticas", e)
            Result.failure(e)
        }
    }
    
    /**
     * Elimina una sesión específica
     */
    suspend fun deleteSession(sessionId: String): Result<Unit> {
        return try {
            val ref = getUserSessionsRef() ?: return Result.failure(Exception("Usuario no autenticado"))
            ref.child(sessionId).removeValue().await()
            
            Log.d(TAG, "✅ Sesión eliminada: $sessionId")
            Result.success(Unit)
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error eliminando sesión", e)
            Result.failure(e)
        }
    }
}

/**
 * Estadísticas generales de sesiones
 */
data class SessionStatistics(
    val totalSessions: Int = 0,
    val totalDurationMs: Long = 0L,
    val totalAlerts: Int = 0,
    val averageDurationMs: Long = 0L,
    val averageAlerts: Double = 0.0
) {
    fun formatTotalDuration(): String {
        val hours = totalDurationMs / (1000 * 60 * 60)
        val minutes = (totalDurationMs / (1000 * 60)) % 60
        return "${hours}h ${minutes}m"
    }
    
    fun formatAverageDuration(): String {
        val minutes = averageDurationMs / (1000 * 60)
        val seconds = (averageDurationMs / 1000) % 60
        return "${minutes}m ${seconds}s"
    }
}
