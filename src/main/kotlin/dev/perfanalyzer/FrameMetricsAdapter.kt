package dev.perfanalyzer

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import org.json.JSONObject

// Studio's own Profiler/JankStats are built on this — Window.OnFrameMetricsAvailableListener
// (API 24+) gives a real phase breakdown per frame instead of ChoreographerAdapter's single
// total duration. Only usable once an Activity's Window exists, so this attaches the same way
// ViewOverdrawTracker does: via ActivityLifecycleCallbacks, resume-to-pause.
//
// minSdk is 21 — PerfAnalyzer picks this on API 24+ and falls back to ChoreographerAdapter
// (total duration only) below that, so no @RequiresApi/androidx.annotation dependency needed;
// the guard lives entirely in PerfAnalyzer's SDK_INT check before this class is ever touched.
internal class FrameMetricsAdapter(private val onEvent: (JSONObject) -> Unit) : Application.ActivityLifecycleCallbacks {
    private val metricsThread = HandlerThread("PerfAnalyzer-FrameMetrics").apply { start() }
    private val metricsHandler = Handler(metricsThread.looper)
    private val listeners = mutableMapOf<Window, Window.OnFrameMetricsAvailableListener>()

    fun stop() {
        listeners.forEach { (window, listener) -> window.removeOnFrameMetricsAvailableListener(listener) }
        listeners.clear()
        metricsThread.quitSafely()
    }

    override fun onActivityResumed(activity: Activity) {
        val window = activity.window ?: return
        if (listeners.containsKey(window)) return
        val listener = Window.OnFrameMetricsAvailableListener { _, frameMetrics, _ -> report(frameMetrics) }
        window.addOnFrameMetricsAvailableListener(listener, metricsHandler)
        listeners[window] = listener
    }

    override fun onActivityPaused(activity: Activity) {
        val window = activity.window ?: return
        listeners.remove(window)?.let { window.removeOnFrameMetricsAvailableListener(it) }
    }

    // The FrameMetrics instance is reused by the platform between callbacks, so every
    // value needed has to be pulled out synchronously here, on the metrics thread.
    private fun report(frameMetrics: FrameMetrics) {
        fun ns(metric: Int) = frameMetrics.getMetric(metric)
        val totalNs = ns(FrameMetrics.TOTAL_DURATION)
        // Same thread split Studio's frame view / JankStats use. DRAW (View#draw
        // recording display lists) and SYNC run on the main thread — counting
        // them as GPU made slow main-thread frames look like GPU problems.
        val delayNs = ns(FrameMetrics.UNKNOWN_DELAY_DURATION)
        val inputNs = ns(FrameMetrics.INPUT_HANDLING_DURATION)
        val animNs = ns(FrameMetrics.ANIMATION_DURATION)
        val layoutNs = ns(FrameMetrics.LAYOUT_MEASURE_DURATION)
        val drawNs = ns(FrameMetrics.DRAW_DURATION)
        val syncNs = ns(FrameMetrics.SYNC_DURATION)
        val mainNs = delayNs + inputNs + animNs + layoutNs + drawNs + syncNs
        // RenderThread only. GPU work runs in parallel and is already inside
        // TOTAL_DURATION, so it's reported on its own instead of added here.
        val renderNs = ns(FrameMetrics.COMMAND_ISSUE_DURATION) + ns(FrameMetrics.SWAP_BUFFERS_DURATION)
        val totalMs = totalNs / 1_000_000.0
        fun ms(v: Long) = v / 1_000_000.0
        val severity = when {
            totalMs > 33 -> "critical"
            totalMs > 16.6 -> "high"
            else -> "low"
        }
        onEvent(JSONObject().apply {
            put("platform", "android")
            put("event", "frame")
            put("timestamp", System.currentTimeMillis())
            put("severity", severity)
            put("type", "frame")
            put("durationMs", totalMs)
            put("build", ms(mainNs))
            put("raster", ms(renderNs))
            put("gpu", ms(gpuNs(frameMetrics)))
            deadlineNs(frameMetrics)?.let { put("deadlineMs", ms(it)) }
            put("phases", JSONObject().apply {
                put("delay", ms(delayNs)); put("input", ms(inputNs)); put("animation", ms(animNs))
                put("measureLayout", ms(layoutNs)); put("draw", ms(drawNs)); put("sync", ms(syncNs))
            })
        })
    }

    // GPU_DURATION needs API 31 and isn't reported on every device even then.
    private fun gpuNs(frameMetrics: FrameMetrics): Long {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return 0L
        return try { frameMetrics.getMetric(FrameMetrics.GPU_DURATION) } catch (_: Exception) { 0L }
    }

    // The frame's real budget (8.3ms at 120Hz, 16.6ms at 60Hz) — API 31+.
    private fun deadlineNs(frameMetrics: FrameMetrics): Long? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return try { frameMetrics.getMetric(FrameMetrics.DEADLINE).takeIf { it > 0 } } catch (_: Exception) { null }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
