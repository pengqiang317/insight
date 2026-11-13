package com.bigtree.insight.internal

import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

internal object Uploader {
    fun upload(endpoint: String, outbox: File, queue: File) {
        if (!uploadOutbox(endpoint, outbox)) {
            return
        }
        val queued = queue.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
        for (file in queued) {
            val body = runCatching { file.readText() }.getOrNull() ?: continue
            if (!post(endpoint, body)) {
                Log.w(Engine.TAG, "upload stopped at ${file.name}")
                return
            }
            file.delete()
        }
    }

    private fun uploadOutbox(endpoint: String, outbox: File): Boolean {
        val marker = File(outbox, ".uploaded")
        val sent = marker.takeIf { it.isFile }?.readLines()?.toMutableSet() ?: mutableSetOf()
        val files = outbox.listFiles()
            ?.filter { it.isFile && !it.name.startsWith(".") }
            ?.sortedBy { it.lastModified() }
            ?: return true
        for (file in files) {
            if (file.name in sent) {
                continue
            }
            val body = runCatching { file.readText() }.getOrNull() ?: continue
            if (!post(endpoint, body)) {
                Log.w(Engine.TAG, "upload stopped at ${file.name}")
                return false
            }
            if (CrashLog.isStoredException(file.name)) {
                sent.add(file.name)
                marker.writeText(sent.joinToString("\n"))
            } else {
                file.delete()
            }
        }
        return true
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
