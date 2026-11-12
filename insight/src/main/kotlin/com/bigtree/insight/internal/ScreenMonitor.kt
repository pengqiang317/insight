package com.bigtree.insight.internal

import android.app.Activity
import androidx.fragment.app.Fragment
import org.json.JSONObject
import java.util.WeakHashMap

internal object ScreenMonitor {
    private class Page(
        val name: String,
        val kind: String,
        val began: Long,
        var precise: Boolean,
    ) {
        var createMs: Long = -1
        var startMs: Long = -1
        var resumeMs: Long = -1
        var phaseMark: Long = 0
        var reported: Boolean = false
    }

    private val pages = WeakHashMap<Activity, Page>()
    private val fragments = WeakHashMap<Fragment, Page>()

    fun onPreCreate(activity: Activity, now: Long) {
        pages[activity] = Page(displayName(activity), "activity", now, precise = true)
    }

    fun onCreated(activity: Activity, now: Long) {
        if (pages[activity] == null) {
            pages[activity] = Page(displayName(activity), "activity", now, precise = false)
        }
    }

    fun onPostCreate(activity: Activity, now: Long) {
        val page = pages[activity] ?: return
        if (page.precise) {
            page.createMs = now - page.began
        }
    }

    fun onPreStart(activity: Activity, now: Long) {
        pages[activity]?.phaseMark = now
    }

    fun onStarted(activity: Activity, now: Long) {
        val page = pages[activity] ?: return
        if (!page.precise && page.phaseMark == 0L) {
            page.phaseMark = now
        }
    }

    fun onPostStart(activity: Activity, now: Long) {
        val page = pages[activity] ?: return
        if (page.phaseMark > 0) {
            page.startMs = now - page.phaseMark
        }
    }

    fun onPreResume(activity: Activity, now: Long) {
        pages[activity]?.phaseMark = now
    }

    fun onResumed(activity: Activity) {
        Engine.breadcrumb("screen", displayName(activity))
    }

    fun onPostResume(activity: Activity, now: Long) {
        val page = pages[activity] ?: return
        if (page.precise && page.phaseMark > 0) {
            page.resumeMs = now - page.phaseMark
        }
    }

    fun onDraw(activity: Activity, now: Long) {
        val page = pages[activity] ?: return
        if (page.reported) {
            return
        }
        page.reported = true
        val drawMs = now - page.began
        val timing = if (page.precise) "api29" else "afterCreate"
        val summary = buildString {
            append("Insight screen ")
            append(page.name)
            append(' ')
            append(drawMs)
            append("ms")
            if (page.createMs >= 0) {
                append(" create=")
                append(page.createMs)
            }
            if (page.startMs >= 0) {
                append(" start=")
                append(page.startMs)
            }
            if (page.resumeMs >= 0) {
                append(" resume=")
                append(page.resumeMs)
            }
        }
        Engine.emit(
            "screen",
            summary,
            JSONObject()
                .put("name", page.name)
                .put("kind", page.kind)
                .put("drawMs", drawMs)
                .put("createMs", page.createMs)
                .put("startMs", page.startMs)
                .put("resumeMs", page.resumeMs)
                .put("timing", timing),
        )
    }

    fun onDestroy(activity: Activity) {
        pages.remove(activity)
    }

    fun onFragmentPreCreate(fragment: Fragment, now: Long) {
        fragments[fragment] = Page(displayName(fragment), "fragment", now, precise = true)
    }

    fun onFragmentCreated(fragment: Fragment, now: Long) {
        val page = fragments[fragment]
        if (page == null) {
            fragments[fragment] = Page(displayName(fragment), "fragment", now, precise = false)
            return
        }
        if (page.precise) {
            page.createMs = now - page.began
        }
    }

    fun fragmentBegan(fragment: Fragment): Long {
        return fragments[fragment]?.began ?: android.os.SystemClock.elapsedRealtime()
    }

    fun onFragmentDraw(fragment: Fragment, began: Long, now: Long) {
        val page = fragments[fragment] ?: return
        if (page.reported) {
            return
        }
        page.reported = true
        val drawMs = now - began
        val parent = fragment.activity?.let { displayName(it) } ?: ""
        Engine.breadcrumb("fragment", page.name)
        Engine.emit(
            "screen",
            "Insight screen ${page.name} ${drawMs}ms fragment",
            JSONObject()
                .put("name", page.name)
                .put("kind", "fragment")
                .put("parent", parent)
                .put("drawMs", drawMs)
                .put("createMs", page.createMs)
                .put("timing", if (page.precise) "api29" else "afterCreate"),
        )
    }

    fun phases(activity: Activity): JSONObject {
        val page = pages[activity] ?: return JSONObject()
        return JSONObject()
            .put("createMs", page.createMs)
            .put("startMs", page.startMs)
            .put("resumeMs", page.resumeMs)
    }

    private fun displayName(instance: Any): String {
        val simple = instance.javaClass.simpleName
        if (simple.isNotEmpty()) {
            return simple
        }
        return instance.javaClass.name.substringAfterLast('.')
    }
}
