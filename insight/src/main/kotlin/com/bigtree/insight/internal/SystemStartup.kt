package com.bigtree.insight.internal

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationStartInfo
import android.os.Process
import org.json.JSONObject
import java.util.function.Consumer

/**
 * 只在 Android 15 及以上加载。更早的系统没有 ApplicationStartInfo。
 */
internal object SystemStartup {
    private var cached: ApplicationStartInfo? = null
    private var listener: Consumer<ApplicationStartInfo>? = null
    private var onReady: (() -> Unit)? = null

    fun arm(app: Application, ready: () -> Unit) {
        onReady = ready
        val manager = app.getSystemService(ActivityManager::class.java) ?: return
        val consumer = Consumer<ApplicationStartInfo> { info ->
            if (info.pid != Process.myPid()) {
                return@Consumer
            }
            cached = info
            if (info.startupState == ApplicationStartInfo.STARTUP_STATE_FIRST_FRAME_DRAWN ||
                info.startupState == ApplicationStartInfo.STARTUP_STATE_ERROR
            ) {
                onReady?.invoke()
            }
        }
        listener = consumer
        manager.addApplicationStartInfoCompletionListener(Engine.io, consumer)
    }

    fun fill(data: JSONObject) {
        if (data.has("system")) {
            return
        }
        val info = cached ?: readHistorical() ?: return
        if (info.pid != Process.myPid()) {
            return
        }
        cached = info
        data.put("system", systemJson(info))
    }

    fun detach() {
        val consumer = listener ?: return
        listener = null
        onReady = null
        runCatching {
            Engine.app.getSystemService(ActivityManager::class.java)
                ?.removeApplicationStartInfoCompletionListener(consumer)
        }
    }

    private fun readHistorical(): ApplicationStartInfo? {
        val manager = Engine.app.getSystemService(ActivityManager::class.java) ?: return null
        return runCatching {
            manager.getHistoricalProcessStartReasons(8).firstOrNull { it.pid == Process.myPid() }
        }.getOrNull()
    }

    private fun systemJson(info: ApplicationStartInfo): JSONObject {
        val timestamps = info.startupTimestamps
        return JSONObject()
            .put("startType", startTypeName(info.startType))
            .put("reason", reasonName(info.reason))
            .put("launchToFirstFrameMs", between(timestamps, ApplicationStartInfo.START_TIMESTAMP_LAUNCH, ApplicationStartInfo.START_TIMESTAMP_FIRST_FRAME))
            .put("launchToForkMs", between(timestamps, ApplicationStartInfo.START_TIMESTAMP_LAUNCH, ApplicationStartInfo.START_TIMESTAMP_FORK))
            .put("forkToBindMs", between(timestamps, ApplicationStartInfo.START_TIMESTAMP_FORK, ApplicationStartInfo.START_TIMESTAMP_BIND_APPLICATION))
            .put("bindToOnCreateMs", between(timestamps, ApplicationStartInfo.START_TIMESTAMP_BIND_APPLICATION, ApplicationStartInfo.START_TIMESTAMP_APPLICATION_ONCREATE))
            .put("onCreateToFirstFrameMs", between(timestamps, ApplicationStartInfo.START_TIMESTAMP_APPLICATION_ONCREATE, ApplicationStartInfo.START_TIMESTAMP_FIRST_FRAME))
    }

    private fun between(timestamps: Map<Int, Long>, from: Int, to: Int): Long {
        val start = timestamps[from] ?: return -1
        val end = timestamps[to] ?: return -1
        return ((end - start) / 1_000_000L).coerceAtLeast(0)
    }

    private fun startTypeName(type: Int): String = when (type) {
        ApplicationStartInfo.START_TYPE_COLD -> "cold"
        ApplicationStartInfo.START_TYPE_WARM -> "warm"
        ApplicationStartInfo.START_TYPE_HOT -> "hot"
        else -> "unset"
    }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationStartInfo.START_REASON_LAUNCHER -> "launcher"
        ApplicationStartInfo.START_REASON_LAUNCHER_RECENTS -> "recents"
        ApplicationStartInfo.START_REASON_START_ACTIVITY -> "activity"
        ApplicationStartInfo.START_REASON_SERVICE -> "service"
        ApplicationStartInfo.START_REASON_BROADCAST -> "broadcast"
        ApplicationStartInfo.START_REASON_PUSH -> "push"
        ApplicationStartInfo.START_REASON_BOOT_COMPLETE -> "boot"
        ApplicationStartInfo.START_REASON_ALARM -> "alarm"
        ApplicationStartInfo.START_REASON_JOB -> "job"
        ApplicationStartInfo.START_REASON_CONTENT_PROVIDER -> "provider"
        else -> reason.toString()
    }
}
