package dev.perfanalyzer

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

class LogcatReader(private val onEvent: (JSONObject) -> Unit) {
    private val tag = "PerfAnalyzer.LogcatReader"
    private var process: Process? = null
    private var thread: Thread? = null
    private var running = false

    fun start() {
        running = true
        thread = Thread {
            try {
                process = Runtime.getRuntime().exec(arrayOf("logcat", "-v", "time", "-s", "AndroidRuntime:E", "*:F"))
                val reader = BufferedReader(InputStreamReader(process!!.inputStream))
                var line: String? = null
                while (running && reader.readLine().also { line = it } != null) {
                    line?.let { parseLine(it) }
                }
            } catch (e: Exception) {
                Log.w(tag, "Logcat reader stopped: ${e.message}")
            }
        }
        thread!!.isDaemon = true
        thread!!.start()
    }

    fun stop() {
        running = false
        process?.destroy()
        thread?.interrupt()
    }

    private fun parseLine(line: String) {
        val isException = line.contains("Exception") || line.contains("FATAL EXCEPTION")
        val isError = line.contains("Error:") && !line.contains("No error")
        if (!isException && !isError) return

        val fatal = line.contains("FATAL")
        val className = extractClassName(line)
        val message = line.trim()

        val event = JSONObject().apply {
            put("type", "error")
            put("platform", "android")
            put("timestamp", System.currentTimeMillis())
            put("severity", if (fatal) "critical" else "high")
            put("fatal", fatal)
            put("className", className)
            put("message", message.take(200))
        }

        onEvent(event)
    }

    private fun extractClassName(line: String): String {
        val exceptionPattern = Regex("""([A-Za-z.]+Exception|[A-Za-z.]+Error)""")
        return exceptionPattern.find(line)?.value?.substringAfterLast('.') ?: "UnknownError"
    }
}
