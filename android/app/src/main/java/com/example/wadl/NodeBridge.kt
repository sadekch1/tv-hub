package com.example.wadl

import android.content.Context
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** تشغيل محرك Node (nodejs-mobile) داخل العملية */
object NodeBridge {
    init {
        System.loadLibrary("node")
        System.loadLibrary("native-lib")
    }

    external fun startNodeWithArguments(args: Array<String>): Int

    private val started = AtomicBoolean(false)

    fun start(ctx: Context, port: Int, token: String) {
        if (!started.compareAndSet(false, true)) return
        val dataDir = File(ctx.filesDir, "wa").apply { mkdirs() }
        val projectDir = File(ctx.filesDir, "nodejs").apply { mkdirs() }
        val main = File(projectDir, "main.js")
        ctx.assets.open("nodejs/main.js").use { i -> main.outputStream().use { o -> i.copyTo(o) } }

        // Node يحتاج مكدساً كبيراً (2MB على الأقل)
        Thread(null, {
            startNodeWithArguments(
                arrayOf("node", main.absolutePath, dataDir.absolutePath, port.toString(), token)
            )
        }, "node-main", 2L * 1024 * 1024).start()
    }
}
