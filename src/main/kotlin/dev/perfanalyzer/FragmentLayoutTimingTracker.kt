package dev.perfanalyzer

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import org.json.JSONObject

// Kept in its own file so these androidx.fragment references are only ever resolved
// for apps that actually depend on it: LayoutTimingTracker.kt calls attach() only
// after confirming (via a plain Class.forName string check, no direct type
// reference of its own) that the classes used here actually exist. Covers the
// single-Activity + Jetpack Navigation architecture most real apps use, where
// LayoutTimingTracker's own Activity-level hook alone would only ever fire once
// per app launch — recursive=true below also catches nested/child fragments.
internal object FragmentLayoutTimingTracker {
    fun attach(activity: Activity, onEvent: (JSONObject) -> Unit) {
        if (activity !is FragmentActivity) return
        activity.supportFragmentManager.registerFragmentLifecycleCallbacks(
            object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?) {
                    val start = SystemClock.elapsedRealtime()
                    val observer = v.viewTreeObserver
                    val listener = object : ViewTreeObserver.OnGlobalLayoutListener {
                        override fun onGlobalLayout() {
                            if (observer.isAlive) observer.removeOnGlobalLayoutListener(this)
                            emitLayoutEvent(onEvent, f.javaClass.simpleName, (SystemClock.elapsedRealtime() - start).toDouble(), countViews(v))
                        }
                    }
                    observer.addOnGlobalLayoutListener(listener)
                }
            },
            true,
        )
    }
}
