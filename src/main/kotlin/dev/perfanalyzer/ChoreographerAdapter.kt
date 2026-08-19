package dev.perfanalyzer

import android.view.Choreographer
import org.json.JSONObject

class ChoreographerAdapter(private val onEvent: (JSONObject) -> Unit) : Choreographer.FrameCallback {
    private var lastFrameTimeNanos: Long = 0
    private var running = false

    fun start() {
        running = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun stop() {
        running = false
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return

        if (lastFrameTimeNanos != 0L) {
            val durationMs = (frameTimeNanos - lastFrameTimeNanos) / 1_000_000.0
            val severity = when {
                durationMs > 33 -> "critical"
                durationMs > 16.6 -> "high"
                else -> "low"
            }
            val event = JSONObject().apply {
                put("platform", "android")
                put("event", "frame")
                put("timestamp", System.currentTimeMillis())
                put("severity", severity)
                put("type", "frame")
                put("durationMs", durationMs)
            }
            onEvent(event)
        }

        lastFrameTimeNanos = frameTimeNanos
        Choreographer.getInstance().postFrameCallback(this)
    }
}
