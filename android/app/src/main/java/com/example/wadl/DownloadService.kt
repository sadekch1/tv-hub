package com.example.wadl

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import java.net.ServerSocket
import java.util.UUID

/** خدمة في المقدمة: تُبقي محرك Node حياً حتى مع إغلاق الشاشة */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var cm: ConnectivityManager? = null

    private val netCb = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            Repo.wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            Repo.updatePause()
        }

        override fun onLost(network: Network) {
            Repo.wifi = false
            Repo.updatePause()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Repo.init(applicationContext)
        Notifier.ensureChannels(this)

        val n = Notifier.ongoing(this, "TV Hub", "جارٍ تشغيل المحرك…")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }

        if (Repo.port == 0) {
            try {
                val p = ServerSocket(0).use { it.localPort }
                Repo.startClient(p, UUID.randomUUID().toString())
                launchEngine()
            } catch (e: Throwable) {
                Repo.fatal.value = "تعذّر تشغيل المحرك: ${e.javaClass.simpleName}: ${e.message}"
            }
        }

        // مراقب: لو توقف المحرك نعرض السبب ونعيد تشغيله (بحد أقصى 3 مرات كل دقيقتين)
        scope.launch {
            var downSince = 0L
            val restarts = ArrayList<Long>()
            while (true) {
                delay(3000)
                if (Repo.engineUp.value) { downSince = 0L; continue }
                val now = System.currentTimeMillis()
                if (downSince == 0L) { downSince = now; continue }
                if (now - downSince < 12_000) continue
                Repo.fatal.value = "المحرك توقف — " + Repo.describeNodeExit()
                restarts.removeAll { now - it > 120_000 }
                if (restarts.size < 3) {
                    restarts.add(now)
                    launchEngine()
                    downSince = now
                } else {
                    Repo.fatal.value += "\nتوقفت المحاولات التلقائية."
                    downSince = Long.MAX_VALUE / 2
                }
            }
        }

        cm = getSystemService(ConnectivityManager::class.java)
        try { cm?.registerDefaultNetworkCallback(netCb) } catch (_: Exception) {}

        scope.launch {
            while (true) {
                delay(5000)
                try { Repo.autoMergeTick() } catch (_: Exception) {}
            }
        }

        scope.launch {
            combine(Repo.jobs, Repo.conn) { j, c -> j to c }.conflate().collect { (jobs, c) ->
                val active = jobs.firstOrNull { it.status == JobStatus.ACTIVE }
                val queued = jobs.count { it.status == JobStatus.QUEUED }
                val title = when {
                    active != null -> {
                        val pct = if (active.size > 0) " ${(active.done * 100 / active.size)}%" else ""
                        "⬇ ${active.name}$pct"
                    }
                    else -> "TV Hub"
                }
                val text = when {
                    queued > 0 -> "في الانتظار: $queued"
                    c == "open" -> "متصل — بانتظار الملفات"
                    c == "reconnecting" || c == "connecting" -> "جارٍ الاتصال…"
                    c == "needpair" || c == "loggedout" -> "سجّل الدخول من التطبيق"
                    else -> "جارٍ التشغيل…"
                }
                Notifier.updateOngoing(this@DownloadService, title, text)
                delay(1500)
            }
        }
    }

    private fun launchEngine() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, NodeService::class.java).putExtra("port", Repo.port).putExtra("token", Repo.token)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        try { cm?.unregisterNetworkCallback(netCb) } catch (_: Exception) {}
        try { stopService(Intent(this, NodeService::class.java)) } catch (_: Exception) {}
        Repo.stopClient()
        scope.cancel()
        super.onDestroy()
    }
}
