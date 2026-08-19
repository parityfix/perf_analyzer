package dev.perfanalyzer

import android.os.Debug
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class MemoryObserver(private val onEvent: (JSONObject) -> Unit) {
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var future: ScheduledFuture<*>? = null

    fun start() {
        future = scheduler.scheduleAtFixedRate({
            val memInfo = Debug.MemoryInfo()
            Debug.getMemoryInfo(memInfo)

            val totalPss = memInfo.totalPss
            val dalvikHeap = memInfo.dalvikPss
            val nativeHeap = memInfo.nativePss

            val severity = when {
                totalPss > 300_000 -> "critical"
                totalPss > 150_000 -> "high"
                else -> "low"
            }

            val event = JSONObject().apply {
                put("type", "memory")
                put("platform", "android")
                put("timestamp", System.currentTimeMillis())
                put("severity", severity)
                put("totalPss", totalPss)
                put("dalvikHeap", dalvikHeap)
                put("nativeHeap", nativeHeap)
            }

            onEvent(event)
        }, 0, 2, TimeUnit.SECONDS)
    }

    fun stop() {
        future?.cancel(false)
        scheduler.shutdown()
    }
}
