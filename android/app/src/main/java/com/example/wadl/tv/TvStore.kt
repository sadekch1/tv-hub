package com.example.wadl.tv

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.example.wadl.Repo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/** تخزين محلي: القنوات + المفضلة + الأخيرة + إعدادات التلفاز. بعد أول استيراد يعمل كله بلا إنترنت. */
object TvStore {
    private lateinit var dir: File
    private lateinit var sp: SharedPreferences
    private var inited = false

    val channels = MutableStateFlow<List<TvChannel>>(emptyList())
    val favorites = MutableStateFlow<Set<String>>(emptySet())
    val recents = MutableStateFlow<List<String>>(emptyList())
    val sourceUrl = MutableStateFlow("")
    val updatedAt = MutableStateFlow(0L)
    val bufferMin = MutableStateFlow(10)
    val importMsg = MutableStateFlow("")
    val importBusy = MutableStateFlow(false)

    const val UA = "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"

    fun init(ctx: Context) {
        if (inited) return
        inited = true
        dir = File(ctx.filesDir, "tv").apply { mkdirs() }
        sp = ctx.getSharedPreferences("watv", Context.MODE_PRIVATE)
        favorites.value = sp.getStringSet("fav", emptySet())?.toSet() ?: emptySet()
        recents.value = (sp.getString("recent", "") ?: "").split(",").filter { it.isNotBlank() }
        sourceUrl.value = sp.getString("src", "") ?: ""
        updatedAt.value = sp.getLong("updated", 0L)
        bufferMin.value = sp.getInt("bufferMin", 10)
        channels.value = loadChannels()
    }

    // ───────────── القنوات ─────────────
    private fun channelsFile() = File(dir, "channels.json")

    private fun loadChannels(): List<TvChannel> = try {
        val f = channelsFile()
        if (!f.exists()) emptyList() else {
            val arr = JSONArray(f.readText())
            ArrayList<TvChannel>(arr.length()).also { out ->
                for (i in 0 until arr.length()) {
                    val a = arr.getJSONArray(i)
                    out.add(TvChannel(a.getString(0), a.getString(1), a.getString(2), a.getString(3), a.getString(4), a.optString(5)))
                }
            }
        }
    } catch (_: Throwable) { emptyList() }

    private fun saveChannels(list: List<TvChannel>) {
        val arr = JSONArray()
        list.forEach { c ->
            arr.put(JSONArray().put(c.id).put(c.name).put(c.group).put(c.logo).put(c.url).put(c.tvgId))
        }
        val tmp = File(dir, "channels.json.tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(channelsFile())) { channelsFile().delete(); tmp.renameTo(channelsFile()) }
    }

    private fun commit(list: List<TvChannel>, source: String): Int {
        if (list.isEmpty()) throw IllegalStateException("لا توجد قنوات صالحة (تأكد أن الملف m3u وروابطه http/https)")
        saveChannels(list)
        channels.value = list
        sourceUrl.value = source
        updatedAt.value = System.currentTimeMillis()
        sp.edit().putString("src", source).putLong("updated", updatedAt.value).apply()
        // أبقِ المفضلة والأخيرة الموجودة فقط
        val ids = list.mapTo(HashSet()) { it.id }
        setFavs(favorites.value.filter { it in ids }.toSet())
        recents.value = recents.value.filter { it in ids }
        return list.size
    }

    // ───────────── الاستيراد ─────────────
    private fun openUrl(url: String): HttpURLConnection {
        var u = url
        repeat(6) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 20_000
            c.readTimeout = 120_000          // get.php في Xtream قد يتأخر في التوليد
            c.setRequestProperty("User-Agent", UA)
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw IllegalStateException("إعادة توجيه بلا عنوان")
                u = URL(URL(u), loc).toString()      // يدعم المسارات النسبية وhttp→https
                c.disconnect()
            } else if (code == 200) {
                return c
            } else {
                c.disconnect()
                throw IllegalStateException("الخادم ردّ بالرمز HTTP $code")
            }
        }
        throw IllegalStateException("تحويلات كثيرة جداً")
    }

    suspend fun importUrl(url: String): Int = withContext(Dispatchers.IO) {
        val c = openUrl(url)
        try {
            BufferedReader(InputStreamReader(c.inputStream, Charsets.UTF_8), 64 * 1024).use { r ->
                commit(M3uParser.parse(r), url)
            }
        } finally { c.disconnect() }
    }

    suspend fun importUri(ctx: Context, uri: Uri): Int = withContext(Dispatchers.IO) {
        val ins = ctx.contentResolver.openInputStream(uri) ?: throw IllegalStateException("تعذّر فتح الملف")
        BufferedReader(InputStreamReader(ins, Charsets.UTF_8), 64 * 1024).use { r ->
            commit(M3uParser.parse(r), "ملف محلي")
        }
    }

    private fun runImport(block: suspend () -> Int) {
        if (importBusy.value) return
        importBusy.value = true
        importMsg.value = "جارٍ التحميل والقراءة…"
        Repo.scope.launch {
            try {
                val n = block()
                importMsg.value = "تم استيراد $n قناة ✓ — جارٍ حفظ الأيقونات في الهاتف"
                LogoCache.prefetch(Repo.scope, channels.value.map { it.logo })
            } catch (e: Throwable) {
                importMsg.value = "فشل الاستيراد: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                importBusy.value = false
            }
        }
    }

    fun startImportUrl(url: String) = runImport { importUrl(url.trim()) }
    fun startImportUri(ctx: Context, uri: Uri) = runImport { importUri(ctx.applicationContext, uri) }
    fun refreshSource() { val s = sourceUrl.value; if (s.startsWith("http")) startImportUrl(s) }

    // ───────────── مفضلة وأخيرة وإعدادات ─────────────
    private fun setFavs(s: Set<String>) {
        favorites.value = s
        sp.edit().putStringSet("fav", HashSet(s)).apply()
    }

    fun toggleFav(id: String) {
        val cur = favorites.value
        setFavs(if (id in cur) cur - id else cur + id)
    }

    fun markRecent(id: String) {
        val l = (listOf(id) + recents.value.filter { it != id }).take(30)
        recents.value = l
        sp.edit().putString("recent", l.joinToString(",")).apply()
    }

    fun setBufferMin(min: Int) {
        bufferMin.value = min
        sp.edit().putInt("bufferMin", min).apply()
    }
}
