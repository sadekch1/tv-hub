package com.example.wadl

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import java.io.File

/**
 * يشغّل محرك Node في عملية مستقلة (:node).
 * لو انهار المحرك (حتى انهياراً أصلياً) يبقى التطبيق نفسه سليماً.
 */
class NodeService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifier.ensureChannels(this)
        val n = Notifier.ongoing(this, "TV Hub", "جارٍ تشغيل المحرك…")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val port = intent?.getIntExtra("port", 0) ?: 0
        val token = intent?.getStringExtra("token") ?: ""
        if (port == 0 || token.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            NodeBridge.start(this, port, token)
        } catch (t: Throwable) {
            try {
                val d = File(filesDir, "wa").apply { mkdirs() }
                File(d, "node-start-error.txt").writeText(t.javaClass.name + ": " + t.message)
            } catch (_: Throwable) {}
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        // Node لا يمكن إيقافه داخل العملية، فننهي العملية كاملة
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}
