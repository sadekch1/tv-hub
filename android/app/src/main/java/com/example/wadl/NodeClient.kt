package com.example.wadl

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** عميل HTTP محلي: قناة أحداث SSE + إرسال أوامر */
class NodeClient(
    private val port: Int,
    private val token: String,
    private val onDown: () -> Unit,
    private val onEvent: (JSONObject) -> Unit
) {
    @Volatile private var running = false
    private var reader: Thread? = null
    private val sender = Executors.newSingleThreadExecutor()

    fun start() {
        if (running) return
        running = true
        reader = Thread { loop() }.also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running = false
        reader?.interrupt()
        sender.shutdownNow()
    }

    private fun loop() {
        while (running) {
            try {
                val c = URL("http://127.0.0.1:$port/events").openConnection() as HttpURLConnection
                c.setRequestProperty("x-token", token)
                c.connectTimeout = 2000
                c.readTimeout = 0
                c.inputStream.bufferedReader().use { r ->
                    while (running) {
                        val line = r.readLine() ?: break
                        if (line.startsWith("data:")) {
                            try { onEvent(JSONObject(line.substring(5).trim())) } catch (_: Exception) {}
                        }
                    }
                }
            } catch (_: Exception) {
            }
            try { onDown() } catch (_: Exception) {}
            try { Thread.sleep(1000) } catch (_: InterruptedException) {}
        }
    }

    fun send(cmd: JSONObject) {
        try {
            sender.execute {
                try {
                    val c = URL("http://127.0.0.1:$port/cmd").openConnection() as HttpURLConnection
                    c.requestMethod = "POST"
                    c.doOutput = true
                    c.connectTimeout = 3000
                    c.readTimeout = 5000
                    c.setRequestProperty("x-token", token)
                    c.outputStream.use { it.write(cmd.toString().toByteArray()) }
                    c.inputStream.close()
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
    }
}
