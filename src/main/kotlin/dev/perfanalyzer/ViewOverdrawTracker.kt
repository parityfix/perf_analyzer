package dev.perfanalyzer

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import org.json.JSONObject

// Heuristic overdraw detector — no in-app code needed per screen, just registers
// itself as an ActivityLifecycleCallbacks in PerfAnalyzer.start().
//
// ponytail: this counts stacked opaque backgrounds down each view branch (the
// same proxy Android's own HWUI "debug GPU overdraw" overlay visualizes with
// blue/green/pink/red), not real per-pixel GPU overdraw — that needs the HWUI
// overlay + screenshot analysis. Reports only leaf views so each report reflects
// the actual stack at a rendered pixel, not an intermediate container's own count.
class ViewOverdrawTracker(private val onEvent: (JSONObject) -> Unit) : Application.ActivityLifecycleCallbacks {
    private val handler = Handler(Looper.getMainLooper())
    private var scanning = false
    private var currentActivity: Activity? = null

    private val scanRunnable = object : Runnable {
        override fun run() {
            currentActivity?.let { scan(it) }
            if (scanning) handler.postDelayed(this, SCAN_INTERVAL_MS)
        }
    }

    fun stop() {
        scanning = false
        currentActivity = null
        handler.removeCallbacks(scanRunnable)
    }

    // Runs on the main thread (Views aren't safe to read off it) every
    // SCAN_INTERVAL_MS for as long as the activity is resumed. visitedCount
    // bounds a single scan's worst case so a pathologically large/deep tree
    // (long non-recycled lists, heavy nesting) can't turn this heartbeat into
    // a main-thread stall long enough to trip Android's ANR watchdog.
    private var visitedCount = 0

    private fun scan(activity: Activity) {
        val root = activity.window?.decorView ?: return
        val found = ArrayList<Pair<View, Int>>()
        visitedCount = 0
        walk(root, 0, found)
        found.sortByDescending { it.second }
        for ((view, stack) in found.take(REPORT_LIMIT)) {
            report(view, stack, activity)
        }
    }

    private fun walk(view: View, backgroundStack: Int, out: MutableList<Pair<View, Int>>) {
        if (++visitedCount > MAX_NODES_PER_SCAN) return
        val stack = backgroundStack + if (view.background != null) 1 else 0

        if (view is ViewGroup && view.childCount > 0) {
            for (i in 0 until view.childCount) walk(view.getChildAt(i), stack, out)
        } else if (stack >= REPORT_THRESHOLD) {
            out.add(view to stack)
        }
    }

    private fun report(view: View, stack: Int, activity: Activity) {
        val severity = when {
            stack >= 4 -> "high"
            stack == 3 -> "medium"
            else -> "low"
        }
        onEvent(JSONObject().apply {
            put("type", "overdraw")
            put("platform", "android")
            put("timestamp", System.currentTimeMillis())
            put("view", resourceName(view, activity))
            put("viewClass", view.javaClass.simpleName)
            put("stack", stack)
            put("severity", severity)
        })
    }

    private fun resourceName(view: View, activity: Activity): String {
        if (view.id == View.NO_ID) return view.javaClass.simpleName
        return try { activity.resources.getResourceEntryName(view.id) }
        catch (_: Exception) { view.javaClass.simpleName }
    }

    // ── ActivityLifecycleCallbacks — only resume/pause matter here ─────────────
    override fun onActivityResumed(activity: Activity) {
        currentActivity = activity
        if (!scanning) {
            scanning = true
            handler.post(scanRunnable)
        }
    }

    override fun onActivityPaused(activity: Activity) {
        if (currentActivity === activity) currentActivity = null
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}

    companion object {
        private const val SCAN_INTERVAL_MS = 1500L
        private const val REPORT_THRESHOLD = 2 // 2+ stacked backgrounds is worth flagging
        private const val REPORT_LIMIT = 30     // matches the dashboard's OverdrawMap cap
        // ponytail: hard cap instead of moving the walk to a background thread
        // (View reads aren't thread-safe). Real screens run in the tens-to-low-
        // hundreds of views, so this never triggers in practice — raise it, or
        // switch to Choreographer idle-time dispatch, if a real screen needs more.
        private const val MAX_NODES_PER_SCAN = 4000
    }
}
