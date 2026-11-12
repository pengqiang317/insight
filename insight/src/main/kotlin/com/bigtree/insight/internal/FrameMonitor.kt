package com.bigtree.insight.internal

import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import org.json.JSONObject
import java.util.WeakHashMap

internal object FrameMonitor {
    private class Stats {
        var frames: Int = 0
        var slow: Int = 0
        var frozen: Int = 0
        var maxMs: Long = 0
        var budgetMs: Long = 32
        var listener: Window.OnFrameMetricsAvailableListener? = null
    }

    private val stats = WeakHashMap<Activity, Stats>()
    private val thread = HandlerThread("insight-frame").apply { start() }
    private val handler = Handler(thread.looper)

    fun onResume(activity: Activity) {
        val window = activity.window ?: return
        val state = stats.getOrPut(activity) { Stats() }
        if (state.listener != null) {
            return
        }
        val refresh = refreshRate(activity)
        state.budgetMs = if (refresh > 1f) {
            (2000f / refresh).toLong().coerceAtLeast(16)
        } else {
            32
        }
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            val ms = metrics.getMetric(FrameMetrics.TOTAL_DURATION) / 1_000_000L
            synchronized(state) {
                state.frames++
                if (ms >= state.budgetMs) {
                    state.slow++
                }
                if (ms >= 700) {
                    state.frozen++
                }
                if (ms > state.maxMs) {
                    state.maxMs = ms
                }
            }
        }
        state.listener = listener
        window.addOnFrameMetricsAvailableListener(listener, handler)
    }

    fun onPause(activity: Activity) {
        emitAndClear(activity, removeListener = true)
    }

    fun onDestroy(activity: Activity) {
        emitAndClear(activity, removeListener = true)
        stats.remove(activity)
    }

    private fun emitAndClear(activity: Activity, removeListener: Boolean) {
        val state = stats[activity] ?: return
        if (removeListener) {
            val listener = state.listener
            state.listener = null
            if (listener != null) {
                runCatching { activity.window?.removeOnFrameMetricsAvailableListener(listener) }
            }
        }
        val frames: Int
        val slow: Int
        val frozen: Int
        val maxMs: Long
        synchronized(state) {
            frames = state.frames
            slow = state.slow
            frozen = state.frozen
            maxMs = state.maxMs
            state.frames = 0
            state.slow = 0
            state.frozen = 0
            state.maxMs = 0
        }
        if (frames == 0) {
            return
        }
        val name = activity.javaClass.simpleName.ifEmpty { activity.javaClass.name.substringAfterLast('.') }
        Engine.emit(
            "jank",
            "Insight jank $name slow=$slow frozen=$frozen max=${maxMs}ms",
            JSONObject()
                .put("name", name)
                .put("frames", frames)
                .put("slow", slow)
                .put("frozen", frozen)
                .put("maxMs", maxMs),
        )
    }

    private fun refreshRate(activity: Activity): Float {
        return if (Build.VERSION.SDK_INT >= 30) {
            activity.display?.refreshRate ?: 60f
        } else {
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay.refreshRate
        }
    }
}
