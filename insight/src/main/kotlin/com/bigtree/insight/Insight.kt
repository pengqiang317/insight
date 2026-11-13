package com.bigtree.insight

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import com.bigtree.insight.internal.Engine

object Insight {
    const val VERSION = "0.1.1"

    /**
     * 依赖进来就会自动安装。其它进程、或者想在其它 SDK 之后确认安装时，可以再调一次。
     * 重复调用是安全的。
     */
    @JvmStatic
    fun install(context: Context) {
        Engine.install(context)
    }

    @JvmStatic
    fun configure(config: InsightConfig) {
        Engine.configure(config)
    }

    @JvmStatic
    fun setListener(listener: InsightListener?) {
        Engine.listener = listener
    }

    @JvmStatic
    fun trace(name: String, action: Runnable) {
        val span = begin(name)
        try {
            action.run()
        } finally {
            span.end()
        }
    }

    @JvmStatic
    fun begin(name: String): InsightSpan {
        Engine.breadcrumb("task", name)
        return InsightSpan(name, SystemClock.elapsedRealtime())
    }

    @JvmStatic
    fun log(message: String) {
        Engine.breadcrumb("log", message)
    }

    @JvmStatic
    fun reportFullyDrawn(activity: Activity) {
        Engine.reportFullyDrawn(activity)
    }

    /** 本地还没发出去的记录，调试时看上一轮崩溃和启动。读文件，别在启动关键路径上频繁调用。 */
    @JvmStatic
    fun pendingSummaries(): List<String> = Engine.pendingSummaries()

    internal fun finishSpan(name: String, beganElapsedMs: Long, extras: Map<String, String>) {
        Engine.finishSpan(name, beganElapsedMs, extras)
    }
}
