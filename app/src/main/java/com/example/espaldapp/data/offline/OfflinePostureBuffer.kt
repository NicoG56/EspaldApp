package com.example.espaldapp.data.offline

import android.content.Context
import com.example.espaldapp.model.PostureData
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Buffer local simple (archivo JSON) para continuidad operativa.
 * Guarda lecturas cuando Firebase falla (sin red / errores temporales).
 */
object OfflinePostureBuffer {

    private const val FILE_NAME = "offline_posture_buffer.json"

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun size(context: Context): Int {
        return try {
            readArray(context).length()
        } catch (_: Exception) {
            0
        }
    }

    @Synchronized
    fun enqueue(context: Context, data: PostureData, maxItems: Int = 500): Int {
        val arr = readArray(context)
        arr.put(toJson(data))

        // Mantener tamaño acotado (descarta lo más antiguo)
        while (arr.length() > maxItems) {
            arr.remove(0)
        }

        writeArray(context, arr)
        return arr.length()
    }

    /**
     * Retorna hasta [maxItems] elementos (en orden FIFO).
     */
    @Synchronized
    fun peek(context: Context, maxItems: Int = 50): List<PostureData> {
        val arr = readArray(context)
        val n = minOf(arr.length(), maxItems)
        val out = ArrayList<PostureData>(n)
        for (i in 0 until n) {
            val obj = arr.optJSONObject(i) ?: continue
            fromJson(obj)?.let(out::add)
        }
        return out
    }

    /**
     * Elimina los primeros [count] elementos del buffer.
     */
    @Synchronized
    fun dropFirst(context: Context, count: Int) {
        if (count <= 0) return
        val arr = readArray(context)
        val n = minOf(arr.length(), count)
        repeat(n) { arr.remove(0) }
        writeArray(context, arr)
    }

    private fun readArray(context: Context): JSONArray {
        return try {
            val f = file(context)
            if (!f.exists()) return JSONArray()
            val text = f.readText()
            if (text.isBlank()) JSONArray() else JSONArray(text)
        } catch (_: Exception) {
            JSONArray()
        }
    }

    private fun writeArray(context: Context, array: JSONArray) {
        val f = file(context)
        f.writeText(array.toString())
    }

    private fun toJson(d: PostureData): JSONObject {
        return JSONObject()
            .put("distancia", d.distancia)
            .put("sentado", d.sentado)
            .put("malaPostura", d.malaPostura)
            .put("alertaActiva", d.alertaActiva)
            .put("umbralVerde", d.umbralVerde)
            .put("umbralRojo", d.umbralRojo)
            .put("pausado", d.pausado)
            .put("timestamp", d.timestamp)
    }

    private fun fromJson(o: JSONObject): PostureData? {
        return try {
            PostureData(
                distancia = o.optInt("distancia", 0),
                sentado = o.optBoolean("sentado", false),
                malaPostura = o.optBoolean("malaPostura", false),
                alertaActiva = o.optBoolean("alertaActiva", false),
                umbralVerde = o.optInt("umbralVerde", 80),
                umbralRojo = o.optInt("umbralRojo", 120),
                pausado = o.optBoolean("pausado", false),
                timestamp = o.optLong("timestamp", System.currentTimeMillis())
            )
        } catch (_: Exception) {
            null
        }
    }
}
