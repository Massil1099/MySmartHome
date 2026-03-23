package fr.mastersid.massil_andrianina.mysmarthome.network

import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

object Esp32HttpClient {

    // Vérifie si l'ESP32 répond
    fun checkConnection(ip: String, timeoutMs: Int = 2000): Result<String> {
        val safeIp = ip.trim()
        if (safeIp.isEmpty()) {
            return Result.failure(IllegalArgumentException("IP vide"))
        }

        return try {
            val conn = (URL("http://$safeIp/").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
            }

            try {
                val code = conn.responseCode
                if (code in 200..499) {
                    Result.success("ESP32 joignable (HTTP $code)")
                } else {
                    Result.failure(RuntimeException("HTTP $code"))
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Envoie la commande complète à l'ESP32
    fun sendCommand(
        ip: String,
        room: String,
        obj: String,
        action: String,
        timeoutMs: Int = 2500
    ): Result<String> {
        val safeIp = ip.trim()
        val safeRoom = room.trim()
        val safeObject = obj.trim()
        val safeAction = action.trim()

        if (safeIp.isEmpty()) {
            return Result.failure(IllegalArgumentException("IP vide"))
        }
        if (safeRoom.isEmpty()) {
            return Result.failure(IllegalArgumentException("Pièce vide"))
        }
        if (safeObject.isEmpty()) {
            return Result.failure(IllegalArgumentException("Objet vide"))
        }
        if (safeAction.isEmpty()) {
            return Result.failure(IllegalArgumentException("Action vide"))
        }

        val encodedRoom = URLEncoder.encode(safeRoom, "UTF-8")
        val encodedObject = URLEncoder.encode(safeObject, "UTF-8")
        val encodedAction = URLEncoder.encode(safeAction, "UTF-8")

        val url = URL(
            "http://$safeIp/cmd?room=$encodedRoom&object=$encodedObject&action=$encodedAction"
        )

        return try {
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
            }

            try {
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

                if (code in 200..299) {
                    Result.success(body.ifBlank { "OK ($code)" })
                } else {
                    Result.failure(RuntimeException("HTTP $code: $body"))
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}