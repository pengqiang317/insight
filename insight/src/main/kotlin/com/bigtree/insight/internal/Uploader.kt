package com.bigtree.insight.internal

import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

internal object Uploader {
    fun upload(endpoint: String, outbox: File) {
        val files = outbox.listFiles()?.sortedBy { it.lastModified() } ?: return
        for (file in files) {
            val body = runCatching { file.readText() }.getOrNull() ?: continue
            if (!post(endpoint, body)) {
                Log.w(Engine.TAG, "upload stopped at ${file.name}")
                return
            }
            file.delete()
        }
    }

    private fun post(endpoint: String, body: String): Boolean {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { stream ->
                stream.write(body.toByteArray(Charsets.UTF_8))
            }
            connection.responseCode in 200..299
        } catch (error: Exception) {
            Log.w(Engine.TAG, "upload error", error)
            false
        } finally {
            connection.disconnect()
        }
    }
}
