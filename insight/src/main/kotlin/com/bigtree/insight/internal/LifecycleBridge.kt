package com.bigtree.insight.internal

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import android.view.ViewTreeObserver
import java.util.Collections
import java.util.WeakHashMap

internal object LifecycleBridge : Application.ActivityLifecycleCallbacks {
    private val hookedFragments = Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())

    override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
        val now = SystemClock.elapsedRealtime()
        ScreenMonitor.onPreCreate(activity, now)
        StartupMonitor.onPreCreate(activity, now)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        val now = SystemClock.elapsedRealtime()
        ScreenMonitor.onCreated(activity, now)
        StartupMonitor.onCreated(activity, now)
        hookFragments(activity)
        watchDraw(activity)
    }

    override fun onActivityPostCreated(activity: Activity, savedInstanceState: Bundle?) {
        ScreenMonitor.onPostCreate(activity, SystemClock.elapsedRealtime())
    }

    override fun onActivityPreStarted(activity: Activity) {
        val now = SystemClock.elapsedRealtime()
        ScreenMonitor.onPreStart(activity, now)
        StartupMonitor.onPreStart(activity, now)
    }

    override fun onActivityStarted(activity: Activity) {
        val now = SystemClock.elapsedRealtime()
        ScreenMonitor.onStarted(activity, now)
        StartupMonitor.onStarted(activity, now)
    }

    override fun onActivityPostStarted(activity: Activity) {
        ScreenMonitor.onPostStart(activity, SystemClock.elapsedRealtime())
    }

    override fun onActivityPreResumed(activity: Activity) {
        ScreenMonitor.onPreResume(activity, SystemClock.elapsedRealtime())
    }

    override fun onActivityResumed(activity: Activity) {
        ScreenMonitor.onResumed(activity)
        FrameMonitor.onResume(activity)
    }

    override fun onActivityPostResumed(activity: Activity) {
        ScreenMonitor.onPostResume(activity, SystemClock.elapsedRealtime())
    }

    override fun onActivityPaused(activity: Activity) {
        FrameMonitor.onPause(activity)
    }

    override fun onActivityStopped(activity: Activity) {
        StartupMonitor.onStopped()
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) {
        FrameMonitor.onDestroy(activity)
        ScreenMonitor.onDestroy(activity)
        hookedFragments.remove(activity)
    }

    private fun hookFragments(activity: Activity) {
        val fragmentActivity = activity as? androidx.fragment.app.FragmentActivity ?: return
        if (!hookedFragments.add(activity)) {
            return
        }
        fragmentActivity.supportFragmentManager.registerFragmentLifecycleCallbacks(
            FragmentBridge,
            true,
        )
    }

    private fun watchDraw(activity: Activity) {
        val decor = activity.window?.decorView ?: return
        val observer = decor.viewTreeObserver
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                val current = if (observer.isAlive) observer else decor.viewTreeObserver
                current.removeOnPreDrawListener(this)
                val now = SystemClock.elapsedRealtime()
                ScreenMonitor.onDraw(activity, now)
                StartupMonitor.onDraw(activity, now)
                return true
            }
        }
        observer.addOnPreDrawListener(listener)
    }
}
