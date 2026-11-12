package com.bigtree.insight.internal

import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal object BlockWatch {
    private val lastAck = AtomicLong(0)
    private val reporting = AtomicBoolean(false)
    private val started = AtomicBoolean(false)

    fun start() {
        val initial = Engine.config.blockThresholdMs
        if (initial <= 0 || !started.compareAndSet(false, true)) {
            return
        }
        val ping = object : Runnable {
            override fun run() {
                lastAck.set(SystemClock.elapsedRealtime())
                reporting.set(false)
                if (Engine.config.blockThresholdMs > 0) {
                    Engine.main.postDelayed(this, sampleMs())
                }
            }
        }
        lastAck.set(SystemClock.elapsedRealtime())
        Engine.main.post(ping)
        Thread({
            while (!Thread.currentThread().isInterrupted) {
                val threshold = Engine.config.blockThresholdMs
                SystemClock.sleep(sampleMs())
                if (threshold <= 0) {
                    continue
                }
                val stuck = SystemClock.elapsedRealtime() - lastAck.get()
                if (stuck >= threshold && reporting.compareAndSet(false, true)) {
                    val stack = Looper.getMainLooper().thread.stackTrace
                        .take(24)
                        .joinToString("\n") { element ->
                            "at ${element.className}.${element.methodName}(${element.fileName}:${element.lineNumber})"
                        }
                    Engine.emit(
                        "block",
                        "Insight block ${stuck}ms",
                        JSONObject()
                            .put("blockedMs", stuck)
                            .put("stack", stack),
                    )
                }
            }
        }, "insight-block").apply { isDaemon = true }.start()
    }

    private fun sampleMs(): Long {
        val threshold = Engine.config.blockThresholdMs
        if (threshold <= 0) {
            return 200
        }
        return (threshold / 4).coerceIn(50, 100)
    }
}
