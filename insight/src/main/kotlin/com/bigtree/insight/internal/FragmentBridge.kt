package com.bigtree.insight.internal

import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager

internal object FragmentBridge : FragmentManager.FragmentLifecycleCallbacks() {
    override fun onFragmentPreCreated(fm: FragmentManager, f: Fragment, savedInstanceState: Bundle?) {
        ScreenMonitor.onFragmentPreCreate(f, SystemClock.elapsedRealtime())
    }

    override fun onFragmentCreated(fm: FragmentManager, f: Fragment, savedInstanceState: Bundle?) {
        ScreenMonitor.onFragmentCreated(f, SystemClock.elapsedRealtime())
    }

    override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?) {
        val began = ScreenMonitor.fragmentBegan(f)
        val observer = v.viewTreeObserver
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                val current = if (observer.isAlive) observer else v.viewTreeObserver
                current.removeOnPreDrawListener(this)
                ScreenMonitor.onFragmentDraw(f, began, SystemClock.elapsedRealtime())
                return true
            }
        }
        observer.addOnPreDrawListener(listener)
    }
}
