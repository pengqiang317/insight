package com.bigtree.insight.internal

import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

internal object ExitCollector {
    private const val MERGE_WINDOW_MS = 20_000L
    private const val TRACE_CAP = 96 * 1024

    fun collect() {
        val natives = readNativeFiles()
        val used = HashSet<File>()
        if (Build.VERSION.SDK_INT >= 30) {
            for (exit in ExitReasons.read()) {
                val match = natives.firstOrNull { abs(it.timeMs - exit.timestamp) <= MERGE_WINDOW_MS }
                if (match != null) {
                    used.add(match.file)
                }
                if (exit.reason == 4 && match == null && CrashLog.containsJavaCrashNear(exit.timestamp)) {
                    continue
                }
                writeExit(exit, match)
            }
        }
        for (native in natives) {
            if (native.file in used) {
                continue
            }
            Engine.emit(
                "native_crash",
                nativeSummary(native.signal, native.text),
                JSONObject()
                    .put("signal", native.signal)
                    .put("signalReport", native.text)
                    .put("breadcrumbs", readCrumbs()),
                native.timeMs,
            )
        }
        natives.forEach { runCatching { it.file.delete() } }
    }

    private fun writeExit(exit: ProcessExit, native: NativeFile?) {
        val type = when (exit.reason) {
            6 -> "anr"
            4 -> "java_crash_exit"
            5, 2 -> "native_crash"
            else -> "exit"
        }
        val data = JSONObject()
            .put("reason", exit.reasonName)
            .put("status", exit.status)
            .put("description", exit.description)
            .put("pssKb", exit.pssKb)
            .put("timestamp", exit.timestamp)
            .put("breadcrumbs", readCrumbs())
        if (!exit.trace.isNullOrEmpty()) {
            data.put("tombstone", exit.trace)
        }
        if (native != null) {
            data.put("signal", native.signal)
            data.put("signalReport", native.text)
        }
        val summary = when (type) {
            "anr" -> "Insight anr ${exit.reasonName}"
            "native_crash" -> nativeSummary(native?.signal ?: exit.reasonName, native?.text.orEmpty())
            else -> "Insight exit ${exit.reasonName}"
        }
        Engine.emit(type, summary, data, exit.timestamp)
    }

    private fun readNativeFiles(): List<NativeFile> {
        val dir = Engine.nativeDir
        val files = dir.listFiles()?.filter { it.isFile && it.name.startsWith("n-") } ?: return emptyList()
        return files.mapNotNull { file ->
            val text = runCatching { file.readText() }.getOrNull() ?: return@mapNotNull null
            val seconds = text.lineSequence()
                .firstOrNull { it.startsWith("time:") }
                ?.substringAfter("time:")
                ?.trim()
                ?.toLongOrNull()
            val signal = text.lineSequence()
                .firstOrNull { it.startsWith("signal ") }
                ?.substringAfter("(")
                ?.substringBefore(")")
                ?: "SIG"
            NativeFile(
                file = file,
                text = text.take(TRACE_CAP),
                timeMs = (seconds ?: file.lastModified() / 1000L) * 1000L,
                signal = signal,
            )
        }
    }

    private fun readCrumbs(): JSONArray {
        val array = JSONArray()
        val file = File(Engine.root, "crumbs.txt")
        if (!file.exists()) {
            return array
        }
        file.readLines().takeLast(20).forEach { array.put(it) }
        return array
    }

    private fun nativeSummary(signal: String, report: String): String {
        val frames = report.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("#") }
            .take(8)
            .joinToString("\n")
        return if (frames.isEmpty()) {
            "Insight native crash $signal"
        } else {
            "Insight native crash $signal\n$frames"
        }
    }

    private class NativeFile(
        val file: File,
        val text: String,
        val timeMs: Long,
        val signal: String,
    )
}
