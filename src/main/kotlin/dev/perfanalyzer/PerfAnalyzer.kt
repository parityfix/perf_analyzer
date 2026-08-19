package dev.perfanalyzer

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

data class PerfConfig(
    val groqKey: String? = null,
    val geminiKey: String? = null,
    val claudeKey: String? = null,
    // "localhost" only resolves to your dev machine on an emulator. A physical
    // device needs either `adb reverse tcp:7001 tcp:7001` (then localhost
    // works exactly as on an emulator) or your machine's real LAN IP here.
    val host: String = "localhost",
    val port: Int = 7001, // must match server/android_bridge.js's ANDROID_PORT
    val enableLocalAI: Boolean = true,
    // null = auto (only runs on debuggable builds — see PerfAnalyzer.isDebuggable).
    // A shipped release build has no dev machine at localhost to ever reach, so
    // without this a production install would retry the websocket forever for
    // nothing. Pass true to force it on anyway (e.g. an internal QA build that
    // isn't flagged debuggable), or false to force it off in a debug build.
    val enabled: Boolean? = null
)

object PerfAnalyzer {
    private const val TAG = "PerfAnalyzer"
    private var client: OkHttpClient? = null
    private var ws: WebSocket? = null
    private var config: PerfConfig = PerfConfig()
    private var choreographerAdapter: ChoreographerAdapter? = null
    private var frameMetricsAdapter: FrameMetricsAdapter? = null
    private var memoryObserver: MemoryObserver? = null
    private var logcatReader: LogcatReader? = null
    private var viewOverdrawTracker: ViewOverdrawTracker? = null
    private var layoutTimingTracker: LayoutTimingTracker? = null
    private var app: Application? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var running = false
    private val eventQueue = mutableListOf<JSONObject>()
    private const val MAX_QUEUE_SIZE = 100

    /**
     * Everything: frame timing, memory polling, logcat crash parsing, view
     * overdraw, AND Compose recomposition tracking / API call visibility.
     * The first three duplicate what `android_live.js` already gets for free
     * over adb (zero code) — reach for connect() instead if you only want
     * Modifier.perfTrack()/PerfInterceptor() and nothing else.
     */
    fun start(context: Application, config: PerfConfig = PerfConfig()) {
        if (running) return
        if (!(config.enabled ?: isDebuggable(context))) {
            Log.i(TAG, "Not a debuggable build — PerfAnalyzer disabled (pass PerfConfig(enabled = true) to force it on)")
            return
        }
        running = true
        this.config = config
        app = context

        connectWebSocket()

        // FrameMetrics (API 24+) gives a real build/raster phase breakdown per frame —
        // Choreographer only ever sees a single total duration. Below API 24, fall back.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            frameMetricsAdapter = FrameMetricsAdapter { event -> sendEvent(event) }
            context.registerActivityLifecycleCallbacks(frameMetricsAdapter!!)
        } else {
            choreographerAdapter = ChoreographerAdapter { event -> sendEvent(event) }
            mainHandler.post { choreographerAdapter!!.start() }
        }

        memoryObserver = MemoryObserver { event -> sendEvent(event) }
        memoryObserver!!.start()

        logcatReader = LogcatReader { event -> sendEvent(event) }
        logcatReader!!.start()

        viewOverdrawTracker = ViewOverdrawTracker { event -> sendEvent(event) }
        context.registerActivityLifecycleCallbacks(viewOverdrawTracker)

        layoutTimingTracker = LayoutTimingTracker { event -> sendEvent(event) }
        context.registerActivityLifecycleCallbacks(layoutTimingTracker)

        ComposeTracker.start { event -> sendEvent(event) }

        Log.i(TAG, "🔍 Perf Analyzer connected to ws://${config.host}:${config.port}")
    }

    /**
     * Just the bridge connection + Compose recomposition tracking — for apps
     * that only want Modifier.perfTrack()/PerfInterceptor() and already get
     * frames/memory/CPU/crashes for free from the zero-code adb Live Monitor.
     *
     * No Context here, so there's no way to auto-detect a production build —
     * prefer the connect(context, config) overload below so this can disable
     * itself on release builds instead of retrying a dev machine forever.
     */
    fun connect(config: PerfConfig = PerfConfig()) {
        if (running) return
        running = true
        this.config = config
        connectWebSocket()
        ComposeTracker.start { event -> sendEvent(event) }
    }

    /** Same as above, but auto-disables on non-debuggable (production) builds — see PerfConfig.enabled. */
    fun connect(context: Application, config: PerfConfig = PerfConfig()) {
        if (running) return
        if (!(config.enabled ?: isDebuggable(context))) {
            Log.i(TAG, "Not a debuggable build — PerfAnalyzer disabled (pass PerfConfig(enabled = true) to force it on)")
            return
        }
        connect(config)
    }

    private fun isDebuggable(context: Application): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    fun stop() {
        running = false
        choreographerAdapter?.stop()
        choreographerAdapter = null
        frameMetricsAdapter?.let { app?.unregisterActivityLifecycleCallbacks(it); it.stop() }
        frameMetricsAdapter = null
        memoryObserver?.stop()
        logcatReader?.stop()
        viewOverdrawTracker?.let { app?.unregisterActivityLifecycleCallbacks(it) }
        viewOverdrawTracker?.stop()
        viewOverdrawTracker = null
        layoutTimingTracker?.let { app?.unregisterActivityLifecycleCallbacks(it) }
        layoutTimingTracker = null
        ComposeTracker.stop()
        ws?.close(1000, null)
        ws = null
        client?.dispatcher?.executorService?.shutdown()
        client = null
    }

    internal fun sendEvent(event: JSONObject) {
        try {
            synchronized(eventQueue) {
                val currentWs = ws
                if (currentWs != null && currentWs.send(event.toString())) {
                    // Sent successfully
                } else {
                    if (eventQueue.size < MAX_QUEUE_SIZE) {
                        eventQueue.add(event)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send event: ${e.message}")
        }
    }

    private fun connectWebSocket() {
        val httpClient = client ?: OkHttpClient.Builder().build().also { client = it }
        val request = Request.Builder().url("ws://${config.host}:${config.port}").build()
        ws = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connected to bridge")
                synchronized(eventQueue) {
                    eventQueue.forEach { webSocket.send(it.toString()) }
                    eventQueue.clear()
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "WebSocket closed: $reason")
                if (running) mainHandler.postDelayed({ connectWebSocket() }, 3000)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket error: ${t.message}")
                if (running) mainHandler.postDelayed({ connectWebSocket() }, 3000)
            }
        })
    }
}
