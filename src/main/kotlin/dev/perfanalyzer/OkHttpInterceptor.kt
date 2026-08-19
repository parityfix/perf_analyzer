package dev.perfanalyzer

import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONObject

class PerfInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val start = System.currentTimeMillis()

        val requestBodySize = try {
            val copy = request.newBuilder().build()
            val buffer = okio.Buffer()
            copy.body?.writeTo(buffer)
            buffer.size
        } catch (_: Exception) {
            0L
        }

        val response = chain.proceed(request)
        val durationMs = System.currentTimeMillis() - start

        val responseBody = response.body
        val responseBytes = responseBody?.bytes() ?: ByteArray(0)
        val responseBodySize = responseBytes.size.toLong()

        val severity = when {
            responseBodySize > 512000 -> "high"
            durationMs > 500 -> "medium"
            else -> "low"
        }

        val event = JSONObject().apply {
            put("type", "api")
            put("platform", "android")
            put("timestamp", System.currentTimeMillis())
            put("severity", severity)
            put("url", request.url.toString())
            put("method", request.method)
            put("requestBodySize", requestBodySize)
            put("responseBodySize", responseBodySize)
            put("durationMs", durationMs)
            put("statusCode", response.code)
        }

        PerfAnalyzer.sendEvent(event)

        return response.newBuilder()
            .body(okhttp3.ResponseBody.create(responseBody?.contentType(), responseBytes))
            .build()
    }
}
