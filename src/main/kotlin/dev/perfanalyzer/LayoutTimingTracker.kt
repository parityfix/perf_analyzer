package dev.perfanalyzer

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import org.json.JSONObject

// Answers "how long did this screen's XML tree take to build" — nothing else here
// times inflate+measure+layout as a whole. Stamps t0 in onActivityCreated, then a
// one-shot OnGlobalLayoutListener on the decor view fires at the first completed
// layout pass (inflate + measure + layout all done) and removes itself, giving a
// real wall-clock build time with zero per-screen app code.
//
// Most real apps are single-Activity + Jetpack Navigation (Fragments), where this
// alone would only ever report once per app launch. FragmentLayoutTimingTracker.kt
// covers every in-app screen change too, attached below when androidx.fragment is
// on the classpath — it's compileOnly here (like Compose already is), so apps that
// don't depend on it at all still get the Activity-level timing safely.
//
// See ComposeTracker's Modifier.perfTrackBuild() for the Compose-side equivalent.
class LayoutTimingTracker(private val onEvent: (JSONObject) -> Unit) : Application.ActivityLifecycleCallbacks {

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        val start = SystemClock.elapsedRealtime()
        val decorView = activity.window?.decorView ?: return
        onFirstLayout(decorView) {
            emitLayoutEvent(onEvent, activity.javaClass.simpleName, (SystemClock.elapsedRealtime() - start).toDouble(), countViews(decorView))
        }

        if (fragmentApiAvailable) {
            // Belt-and-suspenders on top of the Class.forName check: a version
            // mismatch inside androidx.fragment itself would still no-op here
            // rather than take down the host app's activity creation.
            try { FragmentLayoutTimingTracker.attach(activity, onEvent) } catch (_: Throwable) {}
        }
    }

    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}

    companion object {
        // Plain Class.forName by string — never mentions the optional type
        // directly, so this check itself is safe even without androidx.fragment
        // on the classpath. Only once this returns true do we ever touch
        // FragmentLayoutTimingTracker, which is the file that actually
        // references FragmentActivity/FragmentManager.
        private val fragmentApiAvailable: Boolean by lazy {
            try {
                Class.forName("androidx.fragment.app.FragmentActivity")
                Class.forName("androidx.fragment.app.FragmentManager\$FragmentLifecycleCallbacks")
                true
            } catch (_: Throwable) {
                false
            }
        }
    }
}

// Runs `block` once, at the first completed layout pass of `view`. The observer a
// view hands out before it's attached is merged into the window's and then dies,
// so removing through that captured reference silently failed and the listener
// fired on every later layout — re-reporting "time since creation" (minutes).
// Always remove through the view's *current* observer, and guard with a flag.
internal fun onFirstLayout(view: View, block: () -> Unit) {
    view.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
        private var done = false
        override fun onGlobalLayout() {
            view.viewTreeObserver.removeOnGlobalLayoutListener(this)
            if (done) return
            done = true
            block()
        }
    })
}

// Shared by LayoutTimingTracker (Activity decor view) and FragmentLayoutTimingTracker
// (per-fragment root view) — same "screen took Xms to build" shape either way.
internal fun countViews(view: View): Int {
    var count = 1
    if (view is ViewGroup) {
        for (i in 0 until view.childCount) count += countViews(view.getChildAt(i))
    }
    return count
}

internal fun emitLayoutEvent(onEvent: (JSONObject) -> Unit, screen: String, buildMs: Double, viewCount: Int) {
    val severity = when {
        buildMs > 500 -> "high"
        buildMs > 250 -> "medium"
        else -> "low"
    }
    onEvent(JSONObject().apply {
        put("platform", "android")
        put("event", "layout")
        put("type", "layout")
        put("timestamp", System.currentTimeMillis())
        put("severity", severity)
        put("screen", screen)
        put("context", "view")
        put("buildMs", buildMs)
        put("viewCount", viewCount)
    })
}
