package com.bigtree.insight.internal

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * 异常按天追加到 files/insight/crashes/yyyy-MM-dd.txt。
 * 上传用的 JSON 放在 files/insight/upload/，成功后只删这份队列，不删按天文件。
 */
internal object CrashLog {
    private const val KEEP_MS = 14L * 24 * 60 * 60 * 1000
    private const val NEAR_MS = 20_000L
    private val seq = AtomicInteger()
    private val dayFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat {
            return SimpleDateFormat("yyyy-MM-dd", Locale.US)
        }
    }

    fun isException(type: String): Boolean {
        return type == "java_crash" ||
            type == "java_crash_exit" ||
            type == "native_crash" ||
            type == "anr" ||
            type == "exit"
    }

    fun isStoredException(name: String): Boolean {
        return name.startsWith("java_crash") ||
            name.startsWith("native_crash") ||
            name.startsWith("anr") ||
            name.startsWith("exit-")
    }

    fun append(record: JSONObject) {
        if (!Engine.hasRoot()) {
            return
        }
        synchronized(this) {
            val dir = crashDir().apply { mkdirs() }
            val file = File(dir, dayFormat.get()!!.format(Date(record.optLong("ts"))) + ".txt")
            val block = format(record)
            FileOutputStream(file, true).use { stream ->
                stream.write(block.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            enqueue(record)
            trimLocked()
        }
    }

    fun uploadDir(): File {
        return File(Engine.root, "upload").apply { mkdirs() }
    }

    fun trim() {
        if (!Engine.hasRoot()) {
            return
        }
        synchronized(this) { trimLocked() }
    }

    fun containsJavaCrashNear(timestamp: Long): Boolean {
        if (!Engine.hasRoot()) {
            return false
        }
        synchronized(this) {
            val files = crashDir().listFiles() ?: emptyArray()
            for (file in files) {
                if (!file.isFile || !file.name.endsWith(".txt")) {
                    continue
                }
                for (line in file.readLines()) {
                    if (!line.startsWith("----- ")) {
                        continue
                    }
                    val parts = line.split("  ")
                    if (parts.getOrNull(1) != "java_crash") {
                        continue
                    }
                    val ts = parts.getOrNull(2)
                        ?.substringAfter("ts=")
                        ?.substringBefore(" ")
                        ?.toLongOrNull()
                        ?: continue
                    if (abs(ts - timestamp) <= NEAR_MS) {
                        return true
                    }
                }
            }
        }
        val leftover = Engine.outbox.listFiles() ?: return false
        return leftover.any { file ->
            if (!file.name.startsWith("java_crash-")) {
                return@any false
            }
            val ts = file.name.removePrefix("java_crash-")
                .substringBefore('.')
                .substringBefore('-')
                .toLongOrNull()
                ?: return@any false
            abs(ts - timestamp) <= NEAR_MS
        }
    }

    fun summaries(): List<String> {
        if (!Engine.hasRoot()) {
            return emptyList()
        }
        val blocks = ArrayList<Block>()
        synchronized(this) {
            val files = crashDir().listFiles() ?: return emptyList()
            for (file in files) {
                if (!file.isFile || !file.name.endsWith(".txt")) {
                    continue
                }
                var lines = ArrayList<String>()
                var ts = 0L
                fun flush() {
                    if (lines.isEmpty()) {
                        return
                    }
                    blocks.add(Block(ts, lines))
                    lines = ArrayList()
                    ts = 0L
                }
                for (line in file.readLines()) {
                    if (line.startsWith("----- ")) {
                        flush()
                        val parts = line.split("  ")
                        ts = parts.getOrNull(2)
                            ?.substringAfter("ts=")
                            ?.substringBefore(" ")
                            ?.toLongOrNull()
                            ?: 0L
                        lines.add(line.replace(Regex("""\s+ts=\d+"""), ""))
                    } else {
                        lines.add(line)
                    }
                }
                flush()
            }
        }
        return blocks
            .sortedByDescending { it.ts }
            .take(40)
            .map { block ->
                val body = block.lines.drop(1)
                    .map { it.trimEnd() }
                    .filter { it.isNotEmpty() }
                    .take(6)
                if (body.isEmpty()) {
                    block.lines.first()
                } else {
                    block.lines.first() + "\n" + body.joinToString("\n")
                }
            }
    }

    private fun format(record: JSONObject): String {
        val data = record.optJSONObject("data") ?: JSONObject()
        val type = record.optString("type", "exit")
        val body = when (type) {
            "java_crash", "java_crash_exit" -> javaBody(data)
            "native_crash" -> nativeBody(data)
            "anr" -> anrBody(data)
            else -> exitBody(data)
        }
        return buildString {
            append("----- ")
            append(record.optString("time"))
            append("  ")
            append(type)
            append("  ts=")
            append(record.optLong("ts"))
            append(" -----\n")
            append(body)
            if (!body.endsWith("\n")) {
                append('\n')
            }
            append('\n')
        }
    }

    private fun javaBody(data: JSONObject): String {
        return buildString {
            append("thread: ")
            append(data.optString("thread"))
            append('\n')
            append(data.optString("exception"))
            val message = data.optString("message")
            if (message.isNotEmpty()) {
                append(": ")
                append(message)
            }
            append('\n')
            appendStack(data.optString("stack"))
            val threads = data.optJSONArray("threads")
            if (threads != null && threads.length() > 0) {
                append("other threads:\n")
                for (i in 0 until threads.length()) {
                    val thread = threads.optJSONObject(i) ?: continue
                    append("  ")
                    append(thread.optString("name"))
                    append(":\n")
                    thread.optString("stack").lineSequence().forEach { line ->
                        if (line.isBlank()) {
                            return@forEach
                        }
                        append("    ")
                        append(line.trim())
                        append('\n')
                    }
                }
            }
            appendRecent(data.optJSONArray("breadcrumbs"))
        }
    }

    private fun nativeBody(data: JSONObject): String {
        return buildString {
            val report = data.optString("signalReport")
            if (report.isEmpty()) {
                val signal = data.optString("signal").ifEmpty { data.optString("reason") }
                if (signal.isNotEmpty()) {
                    append("signal: ")
                    append(signal)
                    append('\n')
                }
            } else {
                append(report.trimEnd())
                append('\n')
            }
            appendTrace("tombstone", data.optString("tombstone"))
            appendRecent(data.optJSONArray("breadcrumbs"))
        }
    }

    private fun anrBody(data: JSONObject): String {
        return buildString {
            append("reason: ")
            append(data.optString("reason"))
            append('\n')
            val description = data.optString("description")
            if (description.isNotEmpty()) {
                append("description: ")
                append(description)
                append('\n')
            }
            appendTrace("trace", data.optString("tombstone"))
            appendRecent(data.optJSONArray("breadcrumbs"))
        }
    }

    private fun exitBody(data: JSONObject): String {
        return buildString {
            append("reason: ")
            append(data.optString("reason"))
            append('\n')
            val description = data.optString("description")
            if (description.isNotEmpty()) {
                append("description: ")
                append(description)
                append('\n')
            }
            if (data.has("pssKb")) {
                append("pss: ")
                append(data.optLong("pssKb"))
                append("KB\n")
            }
            appendTrace("tombstone", data.optString("tombstone"))
            appendRecent(data.optJSONArray("breadcrumbs"))
        }
    }

    private fun StringBuilder.appendStack(stack: String) {
        stack.lineSequence().forEach { line ->
            if (line.isBlank()) {
                return@forEach
            }
            val trimmed = line.trim()
            if (trimmed.startsWith("at ")) {
                append("    ")
            }
            append(trimmed)
            append('\n')
        }
    }

    private fun StringBuilder.appendTrace(title: String, text: String) {
        if (text.isEmpty()) {
            return
        }
        append(title)
        append(":\n")
        text.lineSequence().forEach { line ->
            append("  ")
            append(line.trimEnd())
            append('\n')
        }
    }

    private fun StringBuilder.appendRecent(crumbs: JSONArray?) {
        if (crumbs == null || crumbs.length() == 0) {
            return
        }
        append("recent:\n")
        for (i in 0 until crumbs.length()) {
            append("  ")
            append(crumbs.optString(i))
            append('\n')
        }
    }

    private fun enqueue(record: JSONObject) {
        val dir = uploadDir()
        val name = record.optString("type", "exit") +
            "-" + record.optLong("ts") +
            "-" + seq.incrementAndGet() + ".json"
        FileOutputStream(File(dir, name)).use { stream ->
            stream.write(record.toString().toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
    }

    private fun trimLocked() {
        val cutoff = System.currentTimeMillis() - KEEP_MS
        val files = crashDir().listFiles() ?: return
        for (file in files) {
            val day = file.name.removeSuffix(".txt")
            val start = runCatching { dayFormat.get()!!.parse(day)?.time }.getOrNull() ?: continue
            if (start < cutoff) {
                file.delete()
            }
        }
    }

    private fun crashDir(): File = File(Engine.root, "crashes")

    private class Block(val ts: Long, val lines: List<String>)
}
