package com.bigtree.insight.internal

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Process
import android.os.SystemClock
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

internal object StartupMonitor {
    private val processStart = Process.getStartElapsedRealtime()
    private var startedCount = 0
    private var hadForeground = false
    private var backgroundPending = false
    private var session: Session? = null
    private var pending: JSONObject? = null
    private val flushed = AtomicBoolean(false)

    private class Session {
        var anchor: Long = 0
        var created: Boolean = false
        var activity: String = ""
        var reported: Boolean = false
    }

    fun arm(app: Application) {
        if (Build.VERSION.SDK_INT >= 35) {
            SystemStartup.arm(app) { onSystemReady() }
        }
    }

    fun onPreCreate(activity: Activity, now: Long) {
        openSession(activity, now, created = true)
    }

    fun onCreated(activity: Activity, now: Long) {
        openSession(activity, now, created = true)
    }

    fun onPreStart(activity: Activity, now: Long) {
        openSession(activity, now, created = false)
    }

    fun onStarted(activity: Activity, now: Long) {
        openSession(activity, now, created = false)
        startedCount++
        backgroundPending = false
    }

    fun onStopped() {
        startedCount = (startedCount - 1).coerceAtLeast(0)
        if (startedCount != 0) {
            return
        }
        backgroundPending = true
        Engine.main.postDelayed({
            if (backgroundPending && startedCount == 0) {
                Engine.memory("background")
                Engine.scheduleUpload()
            }
        }, 400)
    }

    fun onDraw(activity: Activity, now: Long) {
        val current = session ?: return
        if (current.reported || current.activity != displayName(activity)) {
            return
        }
        current.reported = true
        val kind = when {
            !hadForeground && current.created -> "cold"
            current.created -> "warm"
            else -> "hot"
        }
        hadForeground = true
        val duration = if (kind == "cold") now - processStart else now - current.anchor
        val data = JSONObject()
            .put("kind", kind)
            .put("activity", current.activity)
            .put("durationMs", duration)
            .put("processToFirstFrameMs", now - processStart)
            .put("phases", ScreenMonitor.phases(activity))
        if (kind == "cold") {
            Engine.memory("firstFrame")
        }
        synchronized(this) {
            pending = data
            flushed.set(false)
            attachSystem(data)
        }
        if (Build.VERSION.SDK_INT >= 35) {
            Engine.main.postDelayed({ flush() }, 1200)
        } else {
            flush()
        }
    }

    fun onFullyDrawn(activity: Activity, now: Long) {
        Engine.emit(
            "fully_drawn",
            "Insight fullyDrawn ${now - processStart}ms ${displayName(activity)}",
            JSONObject()
                .put("activity", displayName(activity))
                .put("processToFullyDrawnMs", now - processStart),
        )
    }

    private fun openSession(activity: Activity, now: Long, created: Boolean) {
        val current = session
        if (startedCount == 0 && (current == null || current.reported)) {
            session = Session().apply {
                anchor = now
                this.activity = displayName(activity)
                this.created = created
            }
            return
        }
        if (created && current != null && !current.created && !current.reported) {
            current.created = true
            current.activity = displayName(activity)
            current.anchor = now
        }
    }

    private fun onSystemReady() {
        synchronized(this) {
            val data = pending ?: return
            SystemStartup.fill(data)
            flushLocked()
        }
    }

    private fun flush() {
        synchronized(this) {
            attachSystem(pending ?: return)
            flushLocked()
        }
    }

    private fun flushLocked() {
        val data = pending ?: return
        if (!flushed.compareAndSet(false, true)) {
            return
        }
        val summary = summaryOf(data)
        Engine.emit("startup", summary, data)
        if (Build.VERSION.SDK_INT >= 35) {
            SystemStartup.detach()
        }
    }

    private fun attachSystem(data: JSONObject) {
        if (data.has("system") || Build.VERSION.SDK_INT < 35) {
            return
        }
        SystemStartup.fill(data)
    }

    private fun summaryOf(data: JSONObject): String {
        val kind = data.optString("kind")
        val activity = data.optString("activity")
        val system = data.optJSONObject("system")
        val reason = system?.optString("reason").orEmpty()
        val icon = system?.optLong("launchToFirstFrameMs", -1) ?: -1
        val fromIcon = icon >= 0 && (reason == "launcher" || reason == "recents")
        val duration = if (fromIcon) icon else data.optLong("durationMs")
        val label = if (fromIcon) "icon→firstFrame" else "process→firstFrame"
        return "Insight startup $kind ${duration}ms $label $activity"
    }

    private fun displayName(activity: Activity): String {
        val simple = activity.javaClass.simpleName
        return if (simple.isNotEmpty()) simple else activity.javaClass.name.substringAfterLast('.')
    }
}
