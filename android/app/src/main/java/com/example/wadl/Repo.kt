package com.example.wadl

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** مصدر الحقيقة الوحيد: يستقبل أحداث Node ويحتفظ بحالة الواجهة */
object Repo {
    lateinit var app: Context
    private var inited = false
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var client: NodeClient? = null
    var port: Int = 0
    var token: String = ""
    @Volatile var wifi: Boolean = true

    // ── حالة الواجهة ──
    val conn = MutableStateFlow("idle")
    val code = MutableStateFlow("")
    val errorMsg = MutableStateFlow("")
    val engineUp = MutableStateFlow(false)

    /** الوضع الحالي: "watch" أو "download" أو null (شاشة الاختيار) */
    val mode = MutableStateFlow<String?>(null)
    /** وضع المشاهدة: يستقبل (مسار الملف المؤقت، اسمه) فور اكتمال تحميل كل جزء */
    @Volatile var watchListener: ((String, String) -> Unit)? = null
    /** أوامر الشات في وضع المشاهدة: !pause !play !next ... */
    @Volatile var chatCmdListener: ((String, Double?) -> Unit)? = null
    val fatal = MutableStateFlow("")        // خطأ في تشغيل المحرك
    val lastCrash = MutableStateFlow("")    // سبب إغلاق سابق
    val pairingActive = MutableStateFlow(false)
    val jobs = MutableStateFlow<List<Job>>(emptyList())
    val history = MutableStateFlow<List<Rec>>(emptyList())
    val logs = MutableStateFlow<List<String>>(emptyList())
    val userPaused = MutableStateFlow(false)
    val mergeProgress = MutableStateFlow<Map<String, Pair<Long, Long>>>(emptyMap())
    val mergeErrors = MutableStateFlow<Map<String, String>>(emptyMap())

    private val merging = HashSet<String>()
    private val mergeMutex = Mutex()
    private val historyLock = Any()

    fun init(ctx: Context) {
        if (inited) return
        inited = true
        app = ctx.applicationContext
        Prefs.init(app)
        history.value = loadHistory()
        checkLastCrash()
    }

    private fun reasonName(r: Int): String = when (r) {
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "انهيار أصلي (native crash)"
        ApplicationExitInfo.REASON_CRASH -> "انهيار"
        ApplicationExitInfo.REASON_EXIT_SELF -> "خروج ذاتي exit()"
        ApplicationExitInfo.REASON_SIGNALED -> "إشارة قتل"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "نفاد الذاكرة"
        ApplicationExitInfo.REASON_ANR -> "عدم استجابة"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "فشل التهيئة"
        else -> "reason=$r"
    }

    private fun traceStrings(e: ApplicationExitInfo): String = try {
        e.traceInputStream?.use { ins ->
            val buf = ByteArray(300_000)
            var n = 0
            while (n < buf.size) {
                val r = ins.read(buf, n, buf.size - n)
                if (r < 0) break
                n += r
            }
            val txt = String(buf, 0, n, Charsets.ISO_8859_1)
            Regex("[\\x20-\\x7E]{8,}").findAll(txt).map { it.value }
                .filter { it.contains("libnode") || it.contains("abort", true) || it.contains("Fatal", true) ||
                    it.contains("Check failed") || it.contains("v8::") || it.contains("node::") || it.contains("SIG") }
                .distinct().take(12).joinToString("\n")
        } ?: ""
    } catch (_: Throwable) { "" }

    /** سبب توقف محرك Node حسب ما سجّله النظام */
    fun describeNodeExit(): String {
        val sb = StringBuilder()
        try {
            val f = File(app.filesDir, "wa/node-start-error.txt")
            if (f.exists()) { sb.append(f.readText().take(1200)); f.delete() }
        } catch (_: Throwable) {}
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val am = app.getSystemService(ActivityManager::class.java)
                val e = am.getHistoricalProcessExitReasons(app.packageName, 0, 8)
                    .firstOrNull { it.processName?.endsWith(":node") == true }
                if (e != null) {
                    if (sb.isNotEmpty()) sb.append("\n")
                    sb.append("${reasonName(e.reason)} • status=${e.status} ${e.description ?: ""}")
                    if (e.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) {
                        val t = traceStrings(e)
                        if (t.isNotEmpty()) sb.append("\n").append(t)
                    }
                }
            } catch (_: Throwable) {}
        }
        if (sb.isEmpty()) sb.append("لا توجد معلومات من النظام")
        return sb.toString()
    }

    private fun checkLastCrash() {
        try {
            val sb = StringBuilder()
            val f = File(app.filesDir, "crash.txt")
            if (f.exists()) { sb.append(f.readText().take(3000)); f.delete() }
            if (Build.VERSION.SDK_INT >= 30) {
                val am = app.getSystemService(ActivityManager::class.java)
                val e = am.getHistoricalProcessExitReasons(app.packageName, 0, 1).firstOrNull()
                val sp = app.getSharedPreferences("wadl", Context.MODE_PRIVATE)
                if (e != null && e.timestamp > sp.getLong("exitSeen", 0L)) {
                    sp.edit().putLong("exitSeen", e.timestamp).apply()
                    val bad = setOf(
                        ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE,
                        ApplicationExitInfo.REASON_SIGNALED, ApplicationExitInfo.REASON_EXIT_SELF,
                        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE, ApplicationExitInfo.REASON_LOW_MEMORY,
                        ApplicationExitInfo.REASON_ANR
                    )
                    if (e.reason in bad) {
                        sb.append("\nسبب إنهاء العملية: reason=${e.reason} status=${e.status} ${e.description ?: ""}")
                    }
                }
            }
            lastCrash.value = sb.toString()
        } catch (_: Throwable) {}
    }

    // ───────────── الاتصال بالمحرك ─────────────
    fun startClient(p: Int, t: String) {
        if (client != null) return
        port = p; token = t
        client = NodeClient(p, t, { engineUp.value = false }) { onEvent(it) }.also { it.start() }
    }

    fun send(o: JSONObject) { client?.send(o) }

    /** عند مغادرة وضع التحميل: نغلق الاتصال بالمحرك لتعود الحالة نظيفة */
    fun stopClient() {
        try { client?.stop() } catch (_: Exception) {}
        client = null
        port = 0
        token = ""
        engineUp.value = false
        conn.value = "idle"
        code.value = ""
        jobs.value = emptyList()
    }

    fun isRegistered(): Boolean {
        return try {
            val f = File(app.filesDir, "wa/auth/creds.json")
            f.exists() && Regex("\"registered\"\\s*:\\s*true").containsMatchIn(f.readText())
        } catch (_: Exception) { false }
    }

    fun setMode(m: String?) {
        mode.value = m
        send(JSONObject().put("cmd", "setMode").put("mode", m ?: "download"))
    }

    fun pushConfig() {
        send(
            JSONObject().put("cmd", "config")
                .put("target", Prefs.target.value)
                .put("gapMin", Prefs.gapMin.value)
                .put("gapMax", Prefs.gapMax.value)
                .put("maxSizeMb", Prefs.maxSizeMb.value)
                .put("exts", Prefs.exts.value)
        )
        updatePause()
    }

    fun updatePause() {
        send(
            JSONObject().put("cmd", "setPause")
                .put("user", userPaused.value)
                .put("net", Prefs.wifiOnly.value && !wifi)
        )
    }

    fun setUserPause(b: Boolean) { userPaused.value = b; updatePause() }

    fun pair(phone: String) {
        errorMsg.value = ""
        code.value = ""
        pairingActive.value = true
        send(JSONObject().put("cmd", "pair").put("phone", phone))
    }

    fun cancelPairing() {
        pairingActive.value = false
        code.value = ""
        send(JSONObject().put("cmd", "logout"))
    }

    fun cancelJob(id: String) = send(JSONObject().put("cmd", "cancel").put("id", id))
    fun retryJob(id: String) = send(JSONObject().put("cmd", "retry").put("id", id))
    fun removeJob(id: String) {
        send(JSONObject().put("cmd", "clearJob").put("id", id))
        jobs.update { l -> l.filter { it.id != id } }
    }
    fun logout() = send(JSONObject().put("cmd", "logout"))
    fun diag() { addLog("…بدء الفحص"); send(JSONObject().put("cmd", "diag")) }

    // ───────────── معالجة الأحداث ─────────────
    private fun parseStatus(s: String) = when (s) {
        "active" -> JobStatus.ACTIVE
        "failed" -> JobStatus.FAILED
        "expired" -> JobStatus.EXPIRED
        "skipped" -> JobStatus.SKIPPED
        else -> JobStatus.QUEUED
    }

    private fun parseJob(o: JSONObject) = Job(
        id = o.optString("id"),
        name = o.optString("name"),
        size = o.optLong("size"),
        from = o.optString("from"),
        status = parseStatus(o.optString("status")),
        done = o.optLong("done"),
        attempt = o.optInt("attempt"),
        error = o.optString("error")
    )

    private fun updateJob(id: String, f: (Job) -> Job) =
        jobs.update { l -> l.map { if (it.id == id) f(it) else it } }

    private fun addLog(s: String) {
        logs.update { (it + s).takeLast(150) }
    }

    fun onEvent(e: JSONObject) {
        when (e.optString("t")) {
            "snapshot" -> {
                engineUp.value = true
                val f = e.optString("fatal")
                if (f.isNotEmpty()) fatal.value = f
                else if (fatal.value.startsWith("المحرك توقف")) fatal.value = ""
                conn.value = e.optString("conn")
                code.value = e.optString("code")
                val arr = e.optJSONArray("jobs") ?: JSONArray()
                jobs.value = (0 until arr.length()).map { parseJob(arr.getJSONObject(it)) }
                if (conn.value == "idle") send(JSONObject().put("cmd", "connect"))
                send(JSONObject().put("cmd", "setMode").put("mode", mode.value ?: "download"))
                pushConfig()
            }
            "state" -> {
                val c = e.optString("conn")
                conn.value = c
                if (c == "open") { pairingActive.value = false; code.value = ""; errorMsg.value = "" }
                if (c == "needpair" || c == "loggedout") { pairingActive.value = false; code.value = "" }
            }
            "code" -> code.value = e.optString("code")
            "error" -> { errorMsg.value = e.optString("msg"); pairingActive.value = false }
            "log" -> addLog(e.optString("msg"))
            "queued" -> {
                val j = parseJob(e)
                jobs.update { l -> l.filter { it.id != j.id } + j }
            }
            "start" -> updateJob(e.optString("id")) {
                it.copy(status = JobStatus.ACTIVE, attempt = e.optInt("attempt"), done = 0, speed = 0, error = "")
            }
            "progress" -> updateJob(e.optString("id")) {
                it.copy(done = e.optLong("done"), speed = e.optLong("speed"),
                    size = if (e.optLong("total") > 0) e.optLong("total") else it.size)
            }
            "fail" -> updateJob(e.optString("id")) {
                if (e.optBoolean("final")) it.copy(status = JobStatus.FAILED, error = e.optString("error"))
                else it.copy(error = "محاولة ${e.optInt("attempt")} فشلت: ${e.optString("error")}")
            }
            "expired" -> updateJob(e.optString("id")) {
                it.copy(status = JobStatus.EXPIRED, error = e.optString("error"))
            }
            "canceled" -> jobs.update { l -> l.filter { it.id != e.optString("id") } }
            "chatCmd" -> chatCmdListener?.invoke(
                e.optString("cmd"),
                if (e.isNull("arg")) null else e.optDouble("arg")
            )
            "done" -> onDone(e)
            "tvstate", "tv", "tvseg", "tvgap", "tvskip" -> com.example.wadl.tv.TvController.onNodeEvent(e)
        }
    }

    private fun onDone(e: JSONObject) {
        val id = e.optString("id")
        val path = e.optString("path")
        val name = e.optString("name")
        val mime = e.optString("mime")
        val size = e.optLong("size")
        // وضع المشاهدة: الجزء يذهب للمشغّل مباشرة (بدون حفظ في التنزيلات)
        if (mode.value == "watch") {
            val l = watchListener
            jobs.update { list -> list.filter { it.id != id } }
            if (l != null) l(path, name) else try { File(path).delete() } catch (_: Exception) {}
            return
        }
        scope.launch {
            try {
                val (uri, finalName) = Storage.save(app, File(path), name, mime, Prefs.folder.value)
                addRec(Rec(id, finalName, size, uri, System.currentTimeMillis()))
                jobs.update { l -> l.filter { it.id != id } }
                if (jobs.value.none { it.status == JobStatus.ACTIVE || it.status == JobStatus.QUEUED }) {
                    Notifier.event(app, 2, "اكتمل التحميل", "آخر ملف: $finalName")
                }
            } catch (ex: Exception) {
                addLog("فشل الحفظ: ${ex.message}")
                updateJob(id) { it.copy(status = JobStatus.FAILED, error = "تعذّر الحفظ في التنزيلات: ${ex.message}") }
            }
        }
    }

    // ───────────── السجل ─────────────
    private fun historyFile() = File(app.filesDir, "history.json")

    private fun loadHistory(): List<Rec> = try {
        val arr = JSONArray(historyFile().readText())
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Rec(o.getString("id"), o.getString("name"), o.optLong("size"), o.getString("uri"),
                o.optLong("at"), o.optBoolean("merged"), o.optBoolean("used"))
        }
    } catch (_: Exception) { emptyList() }

    private fun saveHistory(list: List<Rec>) = synchronized(historyLock) {
        try {
            val arr = JSONArray()
            list.forEach {
                arr.put(JSONObject().put("id", it.id).put("name", it.name).put("size", it.size)
                    .put("uri", it.uri).put("at", it.at).put("merged", it.merged).put("used", it.used))
            }
            historyFile().writeText(arr.toString())
        } catch (_: Exception) {}
    }

    private fun addRec(r: Rec) {
        history.update { listOf(r) + it }
        saveHistory(history.value)
    }

    fun deleteRec(r: Rec) {
        scope.launch {
            Storage.delete(app, r.uri)
            history.update { l -> l.filter { it.id != r.id } }
            saveHistory(history.value)
        }
    }

    // ───────────── الدمج ─────────────
    fun mergeGroup(g: Group, manual: Boolean = true) {
        synchronized(merging) { if (!merging.add(g.key)) return }
        if (manual) mergeErrors.update { it - g.key }
        scope.launch {
            mergeMutex.withLock {
                try {
                    mergeProgress.update { it + (g.key to (0L to g.total)) }
                    var last = 0L
                    val rec = Merger.merge(app, g) { d, t ->
                        val now = System.currentTimeMillis()
                        if (now - last > 250 || d == t) {
                            last = now
                            mergeProgress.update { it + (g.key to (d to t)) }
                        }
                    }
                    val ids = g.parts.map { it.rec.id }.toSet()
                    if (Prefs.deleteAfterMerge.value) {
                        g.parts.forEach { Storage.delete(app, it.rec.uri) }
                        history.update { l -> listOf(rec) + l.filter { it.id !in ids } }
                    } else {
                        history.update { l -> listOf(rec) + l.map { if (it.id in ids) it.copy(used = true) else it } }
                    }
                    saveHistory(history.value)
                    Notifier.event(app, 3, "اكتمل الدمج", rec.name)
                } catch (ex: Exception) {
                    mergeErrors.update { it + (g.key to (ex.message ?: "فشل الدمج")) }
                    Notifier.event(app, 4, "فشل الدمج", ex.message ?: g.mergedName)
                } finally {
                    mergeProgress.update { it - g.key }
                    synchronized(merging) { merging.remove(g.key) }
                }
            }
        }
    }

    /** يُستدعى كل بضع ثوانٍ من الخدمة */
    fun autoMergeTick() {
        val mode = Prefs.mergeMode.value
        if (mode == 0) return
        val busy = jobs.value.any { it.status == JobStatus.ACTIVE || it.status == JobStatus.QUEUED }
        val failed = mergeErrors.value.keys
        for (g in Merger.groups(history.value)) {
            if (!g.complete || g.firstNum > 1 || g.key in failed) continue
            val ok = when (mode) {
                1 -> g.parts.size >= Prefs.expectedCount.value
                2 -> !busy && System.currentTimeMillis() - g.lastAt > Prefs.idleSec.value * 1000L
                else -> false
            }
            if (ok) mergeGroup(g, manual = false)
        }
    }
}
