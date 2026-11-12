package com.bigtree.insight.internal

import android.util.Log
import com.bigtree.insight.NativeBridge
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

internal object CrashReporter {
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is Bridge) {
            return
        }
        Thread.setDefaultUncaughtExceptionHandler(Bridge(previous))
    }

    private class Bridge(
        private val previous: Thread.UncaughtExceptionHandler?,
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, throwable: Throwable) {
            try {
                NativeBridge.markJavaCrash()
                val data = JSONObject()
                    .put("thread", thread.name)
                    .put("exception", throwable.javaClass.name)
                    .put("message", throwable.message ?: "")
                    .put("stack", stackOf(throwable))
                    .put("threads", otherThreads(thread))
                    .put("breadcrumbs", Engine.snapshotCrumbs())
                val record = Engine.envelope("java_crash", data)
                val file = File(Engine.outbox, "java_crash-${System.currentTimeMillis()}.json")
                FileOutputStream(file).use { stream ->
                    stream.write(record.toString().toByteArray(Charsets.UTF_8))
                    stream.fd.sync()
                }
                Log.e(
                    Engine.TAG,
                    Engine.stamp(
                        record.getString("time"),
                        "Insight java crash ${throwable.javaClass.simpleName}: ${throwable.message}",
                    ),
                )
            } catch (error: Throwable) {
                Log.e(Engine.TAG, "failed to write java crash", error)
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }

    private fun stackOf(throwable: Throwable): String {
        val parts = ArrayList<String>()
        var current: Throwable? = throwable
        var depth = 0
        while (current != null && depth < 6) {
            if (depth > 0) {
                parts.add("Caused by: ${current.javaClass.name}: ${current.message ?: ""}")
            }
            current.stackTrace.take(80).forEach { element ->
                parts.add("at ${element.className}.${element.methodName}(${element.fileName}:${element.lineNumber})")
            }
            current = current.cause
            depth++
        }
        return parts.joinToString("\n")
    }

    private fun otherThreads(crashing: Thread): JSONArray {
        val array = JSONArray()
        var count = 0
        for ((thread, stack) in Thread.getAllStackTraces()) {
            if (thread == crashing || count >= 6) {
                continue
            }
            val name = thread.name
            if (name.startsWith("insight")) {
                continue
            }
            val text = stack.take(12).joinToString("\n") { element ->
                "at ${element.className}.${element.methodName}(${element.fileName}:${element.lineNumber})"
            }
            array.put(JSONObject().put("name", name).put("stack", text))
            count++
        }
        return array
    }
}
