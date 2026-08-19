package dev.perfanalyzer

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

// Compose has no public API to walk its slot table or read recomposition counts
// from outside the app — that's Android Studio's own private App Inspection
// protocol. So tracking a composable needs one line at its call site:
//   Modifier.perfTrack("ProductCard")
// Recomposition counts are tallied here and flushed once/sec (mirroring the
// Flutter adapter's RebuildObserver), so an animation-driven composable
// recomposing every frame doesn't flood the socket with one event per frame.
internal object ComposeTracker {
    private val counts = ConcurrentHashMap<String, Int>()
    private val locations = ConcurrentHashMap<String, String>()
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var future: ScheduledFuture<*>? = null
    private var onEvent: ((JSONObject) -> Unit)? = null

    fun start(onEvent: (JSONObject) -> Unit) {
        this.onEvent = onEvent
        if (future != null) return
        future = scheduler.scheduleAtFixedRate({ flush() }, 1, 1, TimeUnit.SECONDS)
    }

    fun stop() {
        future?.cancel(false)
        future = null
        onEvent = null
        counts.clear()
        locations.clear()
    }

    fun record(name: String, location: String?) {
        counts.merge(name, 1, Int::plus)
        if (location != null) locations[name] = location
    }

    // First-composition build time — a one-shot event, unlike record() above,
    // so it's emitted immediately rather than tallied. Same "layout" event type
    // LayoutTimingTracker.kt uses for XML screens, tagged context:"compose" so
    // the dashboard's one build-time panel covers both.
    fun recordBuild(name: String, buildMs: Double) {
        val emit = onEvent ?: return
        val severity = when {
            buildMs > 500 -> "high"
            buildMs > 250 -> "medium"
            else -> "low"
        }
        emit(JSONObject().apply {
            put("type", "layout")
            put("event", "layout")
            put("platform", "android")
            put("timestamp", System.currentTimeMillis())
            put("severity", severity)
            put("screen", name)
            put("context", "compose")
            put("buildMs", buildMs)
        })
    }

    private fun flush() {
        if (counts.isEmpty()) return
        val snapshot = HashMap(counts)
        counts.clear()
        val emit = onEvent ?: return
        for ((name, count) in snapshot) {
            val severity = when {
                count > 30 -> "critical"
                count > 15 -> "high"
                count > 10 -> "medium"
                else -> "low"
            }
            emit(JSONObject().apply {
                put("type", "rebuild")
                put("platform", "android")
                put("timestamp", System.currentTimeMillis())
                put("view", name)
                put("count", count)
                put("severity", severity)
                locations[name]?.let { loc ->
                    val idx = loc.lastIndexOf(':')
                    if (idx > 0) {
                        put("location", JSONObject().apply {
                            put("file", loc.substring(0, idx))
                            put("line", loc.substring(idx + 1).toIntOrNull() ?: 0)
                        })
                    }
                }
            })
        }
    }
}

/**
 * Add to any composable you want recomposition counts + a live location for:
 *   Text("hi", modifier = Modifier.perfTrack("Greeting"))
 */
fun Modifier.perfTrack(name: String): Modifier = composed {
    val location = remember {
        Throwable().stackTrace
            .firstOrNull { !it.className.startsWith("dev.perfanalyzer") }
            ?.let { "${it.fileName}:${it.lineNumber}" }
    }
    SideEffect { ComposeTracker.record(name, location) }
    this
}

/**
 * Times first composition only (not every recomposition — use Modifier.perfTrack
 * for that):  Column(modifier = Modifier.perfTrackBuild("ProductScreen")) { ... }
 */
fun Modifier.perfTrackBuild(name: String): Modifier = composed {
    val start = remember { System.nanoTime() }
    // SideEffect reruns on every recomposition, not just the first — this guard
    // keeps recordBuild() a one-shot instead of reporting a growing "elapsed
    // since first composition" on every later rebuild.
    val reported = remember { BooleanArray(1) }
    SideEffect {
        if (!reported[0]) {
            reported[0] = true
            ComposeTracker.recordBuild(name, (System.nanoTime() - start) / 1_000_000.0)
        }
    }
    this
}
