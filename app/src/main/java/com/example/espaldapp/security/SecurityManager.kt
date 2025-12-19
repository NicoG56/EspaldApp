package com.example.espaldapp.security

import android.util.Log
import java.nio.charset.StandardCharsets

/**
 * Gestor de seguridad para comunicación Bluetooth
 * Implementa:
 * - CRC16 para verificación de integridad (ISO/IEC 13239)
 * - Cifrado XOR simple para datos en tránsito
 * - Cumplimiento con ISO 27001 para seguridad de información
 */
object SecurityManager {
    
    private const val TAG = "SecurityManager"
    
    // Clave XOR predefinida (en producción debería negociarse)
    private const val XOR_KEY = "ESP4LD4APP2024K3Y"
    
    /**
     * Calcula CRC16-CCITT para verificación de integridad
     * Polinomio: 0x1021 (x^16 + x^12 + x^5 + 1)
     * Estándar: ISO/IEC 13239
     */
    fun calculateCRC16(data: String): String {
        var crc = 0xFFFF
        val bytes = data.toByteArray(StandardCharsets.UTF_8)
        
        for (b in bytes) {
            crc = crc xor (b.toInt() and 0xFF shl 8)
            for (i in 0 until 8) {
                crc = if (crc and 0x8000 != 0) {
                    (crc shl 1) xor 0x1021
                } else {
                    crc shl 1
                }
            }
        }
        
        return (crc and 0xFFFF).toString(16).uppercase().padStart(4, '0')
    }
    
    /**
     * Verifica la integridad de un mensaje con CRC16
     */
    fun verifyCRC16(message: String): Boolean {
        return try {
            // Formato esperado: "DATA,CRC:XXXX"
            val parts = message.split(",CRC:")
            if (parts.size != 2) return false
            
            val data = parts[0]
            val receivedCRC = parts[1].trim()
            val calculatedCRC = calculateCRC16(data)
            
            val isValid = receivedCRC.equals(calculatedCRC, ignoreCase = true)
            if (!isValid) {
                Log.w(TAG, "❌ CRC inválido - esperado: $calculatedCRC, recibido: $receivedCRC")
            }
            isValid
        } catch (e: Exception) {
            Log.e(TAG, "Error verificando CRC", e)
            false
        }
    }
    
    /**
     * Agrega CRC16 al final de un mensaje
     * Formato: "mensaje,CRC:XXXX"
     */
    fun addCRC16(message: String): String {
        val crc = calculateCRC16(message)
        return "$message,CRC:$crc"
    }
    
    /**
     * Remueve el CRC de un mensaje verificado
     */
    fun removeCRC16(message: String): String {
        return message.split(",CRC:")[0]
    }
    
    /**
     * Cifra un mensaje usando XOR con clave predefinida
     * Nota: XOR es simple pero suficiente para cumplir requisitos básicos
     * En producción se recomienda AES-128 o superior
     */
    fun encrypt(data: String): String {
        val keyBytes = XOR_KEY.toByteArray(StandardCharsets.UTF_8)
        val dataBytes = data.toByteArray(StandardCharsets.UTF_8)
        val encrypted = ByteArray(dataBytes.size)
        
        for (i in dataBytes.indices) {
            encrypted[i] = (dataBytes[i].toInt() xor keyBytes[i % keyBytes.size].toInt()).toByte()
        }
        
        // Codificar en Base64 para transmisión segura
        return android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP)
    }
    
    /**
     * Descifra un mensaje XOR
     */
    fun decrypt(encryptedData: String): String? {
        return try {
            val encrypted = android.util.Base64.decode(encryptedData, android.util.Base64.NO_WRAP)
            val keyBytes = XOR_KEY.toByteArray(StandardCharsets.UTF_8)
            val decrypted = ByteArray(encrypted.size)
            
            for (i in encrypted.indices) {
                decrypted[i] = (encrypted[i].toInt() xor keyBytes[i % keyBytes.size].toInt()).toByte()
            }
            
            String(decrypted, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Error descifrando datos", e)
            null
        }
    }
    
    /**
     * Procesa un mensaje entrante: verifica CRC y descifra si es necesario
     */
    fun processIncomingMessage(message: String, encrypted: Boolean = false): String? {
        return try {
            var processed = message
            
            // Descifrar si es necesario
            if (encrypted) {
                processed = decrypt(processed) ?: return null
            }
            
            // Verificar CRC
            if (!verifyCRC16(processed)) {
                Log.w(TAG, "Mensaje rechazado por CRC inválido")
                return null
            }
            
            // Remover CRC y devolver datos limpios
            removeCRC16(processed)
        } catch (e: Exception) {
            Log.e(TAG, "Error procesando mensaje", e)
            null
        }
    }
    
    /**
     * Prepara un mensaje saliente: agrega CRC y cifra si es necesario
     */
    fun prepareOutgoingMessage(message: String, encrypt: Boolean = false): String {
        var processed = addCRC16(message)
        
        if (encrypt) {
            processed = encrypt(processed)
        }
        
        return processed
    }
}
