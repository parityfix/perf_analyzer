package dev.perfanalyzer

import okhttp3.OkHttpClient

// Called from app bytecode rewritten by the `dev.perfanalyzer` Gradle plugin
// (perf-plugin/): in a debug build every OkHttpClient.Builder.build() and
// `OkHttpClient()` passes through here, so API calls are captured with no app
// code. Idempotent — a client that already has PerfInterceptor (added by hand,
// or a newBuilder() of an instrumented client) never gets a second one.
object PerfOkHttp {
    @JvmStatic
    fun instrument(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        if (builder.interceptors().none { it is PerfInterceptor }) builder.addInterceptor(PerfInterceptor())
        return builder
    }

    @JvmStatic
    fun instrument(client: OkHttpClient): OkHttpClient =
        if (client.interceptors.any { it is PerfInterceptor }) client else instrument(client.newBuilder()).build()
}
