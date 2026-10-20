package com.example.wadl.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/** أيقونات القنوات: تُنزَّل مرة واحدة وتُحفظ في الهاتف، فتظهر بعدها بلا إنترنت */
object LogoCache {
    private lateinit var dir: File
    private val mem = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** (المنجَز، الكل) أثناء التنزيل وإلا null */
    val progress = MutableStateFlow<Pair<Int, Int>?>(null)
    /** يرتفع كل دفعة ليُعيد العرض تحميل الأيقونات التي وصلت */
    val version = MutableStateFlow(0)
    @Volatile private var job: Job? = null

    fun init(ctx: Context) {
        dir = File(ctx.filesDir, "tv/logos").apply { mkdirs() }
    }

    private fun fileFor(url: String) = File(dir, M3uParser.idFor(url))

    fun load(url: String): Bitmap? {
        if (url.isBlank()) return null
        val k = M3uParser.idFor(url)
        mem.get(k)?.let { return it }
        val f = File(dir, k)
        if (!f.exists() || f.length() == 0L) return null
        val bmp = decode(f)
        if (bmp == null) { f.delete(); return null }
        mem.put(k, bmp)
        return bmp
    }

    private fun decode(f: File): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        var sample = 1
        val m = maxOf(bounds.outWidth, bounds.outHeight)
        while (m / sample > 192) sample *= 2
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (_: Throwable) { null }

    fun prefetch(scope: CoroutineScope, urls: List<String>) {
        job?.cancel()
        val todo = urls.filter { it.isNotBlank() }.distinct().filter { !fileFor(it).exists() }
        if (todo.isEmpty()) { progress.value = null; return }
        progress.value = 0 to todo.size
        job = scope.launch(Dispatchers.IO) {
            val sem = Semaphore(6)
            val done = AtomicInteger(0)
            coroutineScope {
                for (u in todo) {
                    launch {
                        sem.withPermit { download(u) }
                        val d = done.incrementAndGet()
                        if (d % 20 == 0 || d == todo.size) {
                            progress.value = d to todo.size
                            version.value = version.value + 1
                        }
                    }
                }
            }
            progress.value = null
            version.value = version.value + 1
        }
    }

    private fun download(url: String) {
        val out = fileFor(url)
        val tmp = File(dir, out.name + ".tmp")
        try {
            var u = url
            repeat(4) {
                val c = URL(u).openConnection() as HttpURLConnection
                c.instanceFollowRedirects = false
                c.connectTimeout = 10_000
                c.readTimeout = 15_000
                c.setRequestProperty("User-Agent", TvStore.UA)
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location") ?: return
                    u = URL(URL(u), loc).toString()
                    c.disconnect()
                    return@repeat
                }
                if (code != 200) { c.disconnect(); return }
                c.inputStream.use { ins ->
                    tmp.outputStream().use { os ->
                        val buf = ByteArray(16 * 1024)
                        var total = 0
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > 2 * 1024 * 1024) { tmp.delete(); return }     // أيقونة أكبر من 2MB: نتجاهلها
                            os.write(buf, 0, n)
                        }
                    }
                }
                c.disconnect()
                if (tmp.length() > 0 && tmp.renameTo(out)) return
                tmp.delete()
                return
            }
        } catch (_: Throwable) {
            tmp.delete()
        }
    }
}
