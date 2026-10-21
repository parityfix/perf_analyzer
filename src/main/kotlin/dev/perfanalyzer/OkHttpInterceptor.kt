package dev.perfanalyzer

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.Source
import okio.buffer
import org.json.JSONObject

// Added by hand (`.addInterceptor(PerfInterceptor())`) or to every client by the
// `dev.perfanalyzer` Gradle plugin — so it must be safe on ANY call: image and
// file downloads, streams, one-shot uploads. Nothing is buffered: the response
// is counted as the app reads it, and the event is sent once the body is fully
// read or closed. durationMs = request start → body done (the full round trip).
class PerfInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val start = System.currentTimeMillis()
        val response = chain.proceed(request)

        val body = response.body
        // WebSocket upgrades hand the connection over — leave them alone.
        if (body == null || response.code == 101) {
            send(request, response, 0L, System.currentTimeMillis() - start)
            return response
        }
        val counting = CountingSource(body.source()) { bytes -> send(request, response, bytes, System.currentTimeMillis() - start) }
        return response.newBuilder()
            .body(counting.buffer().asResponseBody(body.contentType(), body.contentLength()))
            .build()
    }

    private fun send(request: Request, response: Response, responseBodySize: Long, durationMs: Long) {
        val severity = when {
            responseBodySize > 512000 -> "high"
            durationMs > 500 -> "medium"
            else -> "low"
        }
        PerfAnalyzer.sendEvent(JSONObject().apply {
            put("type", "api")
            put("platform", "android")
            put("timestamp", System.currentTimeMillis())
            put("severity", severity)
            put("url", request.url.toString())
            put("method", request.method)
            // Declared length only — writing the body out to measure it would
            // consume one-shot/streamed uploads. -1 (unknown) is reported as 0.
            put("requestBodySize", (request.body?.contentLength() ?: 0L).coerceAtLeast(0L))
            put("responseBodySize", responseBodySize)
            put("durationMs", durationMs)
            put("statusCode", response.code)
            response.body?.contentType()?.let { put("contentType", it.toString()) }
        })
    }

    private class CountingSource(delegate: Source, private val onDone: (Long) -> Unit) : ForwardingSource(delegate) {
        private var bytes = 0L
        private var done = false

        override fun read(sink: Buffer, byteCount: Long): Long {
            val n = super.read(sink, byteCount)
            if (n == -1L) finish() else bytes += n
            return n
        }

        override fun close() {
            finish()
            super.close()
        }

        private fun finish() {
            if (done) return
            done = true
            onDone(bytes)
        }
    }
}
