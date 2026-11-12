package com.bigtree.insight.internal

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log

/**
 * 只在 Android 11 及以上加载。tombstone 原文从 Android 12 开始能读到。
 */
internal object ExitReasons {
    private const val TRACE_CAP = 96 * 1024

    fun read(): List<ProcessExit> {
        val manager = Engine.app.getSystemService(ActivityManager::class.java) ?: return emptyList()
        val prefs = Engine.app.getSharedPreferences("insight", Context.MODE_PRIVATE)
        val last = prefs.getLong("last_exit_ms", 0L)
        val weekAgo = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        val infos = runCatching {
            manager.getHistoricalProcessExitReasons(Engine.app.packageName, 0, 8)
        }.getOrElse {
            Log.w(Engine.TAG, "exit reasons unavailable", it)
            return emptyList()
        }
        var maxTs = last
        val result = ArrayList<ProcessExit>()
        for (info in infos) {
            if (info.timestamp > maxTs) {
                maxTs = info.timestamp
            }
            if (info.timestamp <= last || info.timestamp < weekAgo || !interesting(info.reason)) {
                continue
            }
            val trace = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) readTrace(info) else null
            result.add(
                ProcessExit(
                    reason = info.reason,
                    reasonName = reasonName(info.reason),
                    status = info.status,
                    description = info.description ?: "",
                    timestamp = info.timestamp,
                    pssKb = info.pss,
                    trace = trace,
                ),
            )
        }
        if (maxTs > last) {
            prefs.edit().putLong("last_exit_ms", maxTs).apply()
        }
        return result
    }

    private fun interesting(reason: Int): Boolean {
        return reason == ApplicationExitInfo.REASON_CRASH ||
            reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
            reason == ApplicationExitInfo.REASON_ANR ||
            reason == ApplicationExitInfo.REASON_LOW_MEMORY ||
            reason == ApplicationExitInfo.REASON_SIGNALED
    }

    private fun readTrace(info: ApplicationExitInfo): String? {
        return runCatching {
            info.traceInputStream?.use { input ->
                val bytes = input.readBytes()
                val capped = if (bytes.size > TRACE_CAP) bytes.copyOf(TRACE_CAP) else bytes
                readableTrace(capped)
            }
        }.getOrNull()
    }

    private fun readableTrace(bytes: ByteArray): String {
        if (bytes.isEmpty()) {
            return ""
        }
        val textLike = bytes.count { byte ->
            val value = byte.toInt() and 0xff
            value == 0x0a || value == 0x09 || value in 32..126
        }
        if (textLike > bytes.size * 0.85) {
            return String(bytes, Charsets.UTF_8)
        }
        val lines = StringBuilder()
        val current = StringBuilder()
        fun flush() {
            if (current.length >= 6 && lines.length < 8000) {
                lines.append(current).append('\n')
            }
            current.setLength(0)
        }
        for (byte in bytes) {
            val value = byte.toInt() and 0xff
            if (value in 32..126) {
                current.append(value.toChar())
            } else {
                flush()
            }
        }
        flush()
        return lines.toString()
    }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash_native"
        ApplicationExitInfo.REASON_ANR -> "anr"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low_memory"
        ApplicationExitInfo.REASON_SIGNALED -> "signaled"
        else -> reason.toString()
    }
}

internal class ProcessExit(
    val reason: Int,
    val reasonName: String,
    val status: Int,
    val description: String,
    val timestamp: Long,
    val pssKb: Long,
    val trace: String?,
)
