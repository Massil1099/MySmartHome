package fr.mastersid.massil_andrianina.mysmarthome.network

import java.net.HttpURLConnection
import java.net.URL

object Esp32HttpClient {

    fun sendCommand(ip: String, command: String, timeoutMs: Int = 2500): Result<String> {
        val safeIp = ip.trim()
        val safeCmd = command.trim()
        if (safeIp.isEmpty()) return Result.failure(IllegalArgumentException("IP vide"))
        if (safeCmd.isEmpty()) return Result.failure(IllegalArgumentException("Commande vide"))

        val url = URL("http://$safeIp/cmd?value=$safeCmd")

        return try {
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

            if (code in 200..299) Result.success(body.ifBlank { "OK ($code)" })
            else Result.failure(RuntimeException("HTTP $code: $body"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}