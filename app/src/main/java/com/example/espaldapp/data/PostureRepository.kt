package com.example.espaldapp.data

import com.example.espaldapp.model.PostureData
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/**
 * Repositorio para manejar la comunicación con Firebase Realtime Database
 * Estructura en Firebase:
 * /users/{userId}/posture/current -> Datos actuales de postura
 * /users/{userId}/posture/history -> Historial de sesiones
 */
class PostureRepository {
    
    private val database: DatabaseReference = FirebaseDatabase.getInstance().reference
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
    
    /**
     * Obtiene el userId actual del usuario autenticado
     */
    private fun getCurrentUserId(): String? = auth.currentUser?.uid
    
    /**
     * Referencia al nodo de datos de postura del usuario actual
     */
    private fun getUserPostureRef(): DatabaseReference? {
        val userId = getCurrentUserId() ?: return null
        return database.child("users").child(userId).child("posture")
    }
    
    /**
     * Guarda o actualiza los datos de postura actuales en Firebase
     */
    suspend fun savePostureData(data: PostureData): Result<Unit> {
        return try {
            val ref = getUserPostureRef() ?: return Result.failure(Exception("Usuario no autenticado"))
            ref.child("current").setValue(data).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Observa los cambios en los datos de postura en tiempo real
     * Retorna un Flow que emite PostureData cada vez que hay cambios
     */
    fun observePostureData(): Flow<PostureData?> = callbackFlow {
        val ref = getUserPostureRef()
        
        if (ref == null) {
            trySend(null)
            close()
            return@callbackFlow
        }
        
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val data = snapshot.child("current").getValue(PostureData::class.java)
                trySend(data)
            }
            
            override fun onCancelled(error: DatabaseError) {
                close(error.toException())
            }
        }
        
        ref.addValueEventListener(listener)
        
        awaitClose {
            ref.removeEventListener(listener)
        }
    }
    
    /**
     * Guarda los datos de postura en el historial
     * Se puede usar al finalizar una sesión
     */
    suspend fun saveToHistory(data: PostureData): Result<Unit> {
        return try {
            val ref = getUserPostureRef() ?: return Result.failure(Exception("Usuario no autenticado"))
            val historyRef = ref.child("history").push()
            historyRef.setValue(data).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Obtiene el historial de sesiones
     */
    suspend fun getHistory(limit: Int = 50): Result<List<PostureData>> {
        return try {
            val ref = getUserPostureRef() ?: return Result.failure(Exception("Usuario no autenticado"))
            val snapshot = ref.child("history")
                .orderByChild("timestamp")
                .limitToLast(limit)
                .get()
                .await()
            
            val historyList = mutableListOf<PostureData>()
            snapshot.children.forEach { child ->
                child.getValue(PostureData::class.java)?.let { historyList.add(it) }
            }
            
            Result.success(historyList.reversed())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Actualiza los umbrales de configuración en Firebase
     */
    suspend fun updateThresholds(umbralVerde: Int, umbralRojo: Int): Result<Unit> {
        return try {
            val ref = getUserPostureRef() ?: return Result.failure(Exception("Usuario no autenticado"))
            val updates = mapOf(
                "current/umbralVerde" to umbralVerde,
                "current/umbralRojo" to umbralRojo
            )
            ref.updateChildren(updates).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Limpia los datos actuales (útil al desconectar el dispositivo)
     */
    suspend fun clearCurrentData(): Result<Unit> {
        return try {
            val ref = getUserPostureRef() ?: return Result.failure(Exception("Usuario no autenticado"))
            ref.child("current").removeValue().await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
