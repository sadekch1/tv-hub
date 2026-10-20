package com.example.wadl.tv

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.example.wadl.Prefs
import com.example.wadl.Repo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.File
import java.util.TreeMap
import java.util.UUID

enum class TvUi { IDLE, STARTING, PREBUFFER, PLAYING, REBUFFER, ERROR }

data class TvState(
    val ui: TvUi = TvUi.IDLE,
    val channel: TvChannel? = null,
    val aheadSec: Int = 0,
    val barTargetSec: Int = 0,
    val segs: Int = 0,
    val stalls: Int = 0,
    val speed: Float = 1f,
    val userPaused: Boolean = false,
    val note: String = "",
    val error: String = "",
    /** البوت لم يؤكد الإيقاف بعد */
    val stopping: Boolean = false,
    /** بالدقائق، 0 = لا اقتراح */
    val suggestMin: Int = 0
)

/**
 * قلب التلفاز: يستقبل الأجزاء من محرك Node، يرتّبها، يُبقيها خارج المشغّل حتى تكتمل الوسادة،
 * ثم يغذّي ExoPlayer بها. كل شيء هنا يعمل على الخيط الرئيسي.
 */
@OptIn(UnstableApi::class)
object TvController {
    val state = MutableStateFlow(TvState())

    private class Seg(val n: Int, val d: Double, val disc: Boolean, val path: String)

    private val main = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var sid = ""
    private var stoppingSid = ""
    private var policy: BufferPolicy? = null

    private val held = TreeMap<Int, Seg>()          // وصلت لكن تنتظر دورها
    private val skipped = HashSet<Int>()            // أجزاء ضاعت نهائياً
    private var nextAppend = 1
    private var waitingSince = 0L
    private val durs = ArrayDeque<Double>()         // مرتبطة بعناصر المشغّل بنفس الترتيب
    private val files = ArrayDeque<String>()
    private var appended = 0
    private var errorsInRow = 0
    private var tickerOn = false

    private const val MAX_WAIT_MISSING_MS = 90_000L
    private const val MAX_AHEAD_SEC = 3600

    /** للواجهة فقط (الخيط الرئيسي) */
    fun playerForView(): ExoPlayer = ensurePlayer()

    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val p = ExoPlayer.Builder(Repo.app).build()
        p.setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            true
        )
        p.setHandleAudioBecomingNoisy(true)
        p.setWakeMode(C.WAKE_MODE_LOCAL)
        p.playWhenReady = false
        p.repeatMode = Player.REPEAT_MODE_OFF
        p.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { main.post { trimPlayed() } }
            override fun onIsPlayingChanged(isPlaying: Boolean) { if (isPlaying) errorsInRow = 0 }
            override fun onPlayerError(error: PlaybackException) { main.post { onBrokenItem() } }
        })
        player = p
        return p
    }

    // ───────────── أوامر الواجهة ─────────────
    fun play(ch: TvChannel) {
        val err = when {
            Repo.conn.value != "open" -> "واتساب غير متصل بعد. انتظر حتى تظهر «متصل» ثم اضغط القناة مجدداً."
            Prefs.target.value.isBlank() -> "أدخل رقم البوت في الإعدادات أولاً."
            else -> ""
        }
        if (err.isNotEmpty()) {
            state.update { TvState(ui = TvUi.ERROR, channel = ch, error = err, stopping = it.stopping) }
            return
        }
        main.post {
            resetPlayback()                                    // تبديل القناة: البوت يستبدل الجلسة القديمة بنفسه
            sid = newSid()
            val pol = BufferPolicy(TvStore.bufferMin.value * 60)
            policy = pol
            val p = ensurePlayer()
            p.playWhenReady = false
            p.setPlaybackSpeed(1f)
            state.update { TvState(ui = TvUi.STARTING, channel = ch, barTargetSec = pol.targetSec, stopping = it.stopping) }
            Repo.setMode("tv")
            Repo.pushConfig()
            Repo.send(JSONObject().put("cmd", "tvStart").put("sid", sid).put("url", ch.url).put("seg", 30))
            TvStore.markRecent(ch.id)
            startTicker()
        }
    }

    /** إيقاف من المستخدم: المشغّل يتوقف فوراً، ونتتبع تأكيد البوت */
    fun stop() {
        main.post {
            val s = sid
            if (s.isNotEmpty()) {
                Repo.send(JSONObject().put("cmd", "tvStop").put("sid", s).put("reason", "user"))
                stoppingSid = s
                main.postDelayed({
                    if (stoppingSid == s) { stoppingSid = ""; state.update { it.copy(stopping = false) } }
                }, 30_000)
            }
            endSession()
            state.value = TvState(stopping = s.isNotEmpty())
        }
    }

    fun togglePause() {
        main.post {
            val p = player ?: return@post
            val pol = policy ?: return@post
            val paused = !state.value.userPaused
            state.update { it.copy(userPaused = paused) }
            p.playWhenReady = !paused && pol.phase == BufferPolicy.Phase.PLAYING
        }
    }

    /** يغيّر الوسادة للمشاهدة التالية، وفوراً إن كنا ما زلنا في مرحلة التجهيز */
    fun setBufferMin(min: Int) {
        TvStore.setBufferMin(min)
        main.post {
            val pol = policy ?: return@post
            if (pol.phase == BufferPolicy.Phase.PREBUFFER) pol.targetSec = min * 60
        }
    }

    fun dismissError() { state.update { if (it.ui == TvUi.ERROR) TvState(stopping = it.stopping) else it } }

    // ───────────── أحداث محرك Node ─────────────
    fun onNodeEvent(e: JSONObject) { main.post { handle(e) } }

    private fun handle(e: JSONObject) {
        val evSid = e.optString("sid")
        if (e.optString("t") == "tvstate" && e.optString("state") == "stopped" && evSid == stoppingSid) {
            stoppingSid = ""
            state.update { it.copy(stopping = false) }
        }
        if (evSid != sid || sid.isEmpty()) return

        when (e.optString("t")) {
            "tvstate" -> onNodeState(e.optString("state"), e.optString("reason"))
            "tv" -> onBotEvent(e.optString("ev"), e.optJSONObject("data") ?: JSONObject())
            "tvseg" -> onSeg(e)
            "tvgap" -> note("جارٍ طلب جزء ناقص من البوت…")
            "tvskip" -> { skipped.add(e.optInt("n")); drain() }
        }
    }

    private fun onNodeState(st: String, reason: String) {
        when (st) {
            "running" -> state.update { if (it.ui == TvUi.STARTING) it.copy(ui = TvUi.PREBUFFER) else it }
            "stopped" -> {
                val msg = when (reason) {
                    "src_fail" -> "القناة لا تستجيب (المصدر لا يعمل). جرّب قناة أخرى."
                    "no_bot_reply" -> "البوت لا يردّ. تأكد من رقم البوت، وأنه يعمل، وأن رقمك مسموح له (TV_ALLOWED)."
                    "source_dead" -> "انقطع مصدر القناة نهائياً بعد محاولات متكررة."
                    "watchdog", "max_duration", "replaced" -> "توقّف البث على الخادم ($reason)."
                    else -> "توقّف البث ($reason)."
                }
                fail(msg, notifyNode = false)
            }
        }
    }

    private fun onBotEvent(ev: String, data: JSONObject) {
        when (ev) {
            "warn" -> when (data.optString("reason")) {
                "slow_upload" -> note("رفع البوت أبطأ من البث — قد تحتاج وسادة أكبر أو خادماً أسرع.")
                "source_reconnect" -> note("انقطع مصدر القناة، جارٍ إعادة الاتصال…")
            }
            "src_ok", "start" -> note("")
            "err" -> if (data.optString("reason") != "source_dead") note("خطأ من البوت: ${data.optString("reason")}")
        }
    }

    private fun onSeg(e: JSONObject) {
        val s = Seg(e.optInt("n"), e.optDouble("d", 30.0), e.optInt("disc") == 1, e.optString("path"))
        if (s.n < nextAppend && !held.containsKey(s.n)) return        // وصل متأخراً بعد تخطّيه
        held[s.n] = s
        drain()
        state.update { it.copy(segs = appended) }
    }

    // ───────────── ترتيب الأجزاء وتغذية المشغّل ─────────────
    private fun drain() {
        var progressed = false
        var again = true
        while (again) {
            again = false
            if (skipped.remove(nextAppend)) { nextAppend++; progressed = true; again = true; continue }
            val s = held.remove(nextAppend)
            if (s != null) { append(s); nextAppend++; progressed = true; again = true }
        }
        if (progressed) waitingSince = 0L
        if (held.isNotEmpty()) {
            val now = SystemClock.elapsedRealtime()
            if (waitingSince == 0L) waitingSince = now
            else if (now - waitingSince > MAX_WAIT_MISSING_MS) {
                // جزء ناقص يعطّل ما بعده منذ أكثر من 90 ثانية: نتخطاه بدل أن نتجمّد
                nextAppend = held.firstKey()
                waitingSince = 0L
                note("تعذّر الحصول على جزء — تم تخطّيه.")
                drain()
            }
        } else waitingSince = 0L
    }

    private fun append(s: Seg) {
        val f = File(s.path)
        if (!f.exists()) return
        val p = ensurePlayer()
        p.addMediaItem(MediaItem.Builder().setUri(Uri.fromFile(f)).setMediaId("seg${s.n}").build())
        durs.addLast(s.d)
        files.addLast(s.path)
        appended++
        // نفس النمط المجرَّب في وضع المشاهدة: بعد الإضافة نوقظ المشغّل إن كان فارغاً/منتهياً
        when (p.playbackState) {
            Player.STATE_IDLE -> p.prepare()
            Player.STATE_ENDED -> p.seekToDefaultPosition(p.mediaItemCount - 1)
            else -> {}
        }
    }

    /** نبقي جزءاً واحداً مُشغَّلاً خلف الموضع الحالي ونحذف الباقي (قرص + ذاكرة) */
    private fun trimPlayed() {
        val p = player ?: return
        while (p.currentMediaItemIndex > 1 && p.mediaItemCount > 2 && durs.isNotEmpty()) {
            p.removeMediaItem(0)
            durs.removeFirst()
            files.removeFirstOrNull()?.let { try { File(it).delete() } catch (_: Throwable) {} }
        }
    }

    private fun onBrokenItem() {
        val p = player ?: return
        if (sid.isEmpty()) return
        errorsInRow++
        if (errorsInRow > 6) { fail("تعذّر تشغيل الأجزاء الواردة (تنسيق غير مدعوم من المصدر).", notifyNode = true); return }
        val i = p.currentMediaItemIndex
        if (i + 1 < p.mediaItemCount) p.seekTo(i + 1, 0L)       // نتخطى الجزء التالف
        p.prepare()
        note("جزء تالف — تم تخطّيه.")
    }

    // ───────────── مؤقّت كل ثانية ─────────────
    private val ticker = object : Runnable {
        override fun run() {
            try { tick() } catch (_: Throwable) {}
            if (tickerOn) main.postDelayed(this, 1000)
        }
    }

    private fun startTicker() {
        if (tickerOn) return
        tickerOn = true
        main.postDelayed(ticker, 1000)
    }

    private fun computeAhead(p: ExoPlayer): Double {
        if (p.mediaItemCount == 0 || durs.isEmpty()) return 0.0
        val idx = p.currentMediaItemIndex
        var sum = 0.0
        for (i in idx + 1 until durs.size) sum += durs[i]
        val cur = durs.getOrNull(idx) ?: 0.0
        return sum + maxOf(0.0, cur - p.currentPosition / 1000.0)
    }

    private fun tick() {
        val pol = policy ?: return
        val p = player ?: return
        val st = state.value
        if (st.ui == TvUi.IDLE || st.ui == TvUi.ERROR) return

        drain()                                    // يفعّل مهلة تخطّي الجزء الناقص حتى لو لم يصل جديد
        trimPlayed()
        val ahead = computeAhead(p)
        val starved = p.mediaItemCount == 0 ||
            p.playbackState == Player.STATE_BUFFERING || p.playbackState == Player.STATE_ENDED

        when (pol.onTick(ahead, starved)) {
            BufferPolicy.Action.PLAY -> if (!st.userPaused) p.playWhenReady = true
            BufferPolicy.Action.PAUSE -> p.playWhenReady = false
            BufferPolicy.Action.NONE -> {}
        }
        if (p.playbackParameters.speed != pol.speed) p.setPlaybackSpeed(pol.speed)

        if (ahead > MAX_AHEAD_SEC) {
            fail("توقّف البث تلقائياً: الوسادة تجاوزت ساعة (لحماية مساحة الهاتف).", notifyNode = true)
            return
        }

        val ui = if (st.ui == TvUi.STARTING && appended == 0 && held.isEmpty()) TvUi.STARTING else when (pol.phase) {
            BufferPolicy.Phase.PREBUFFER -> TvUi.PREBUFFER
            BufferPolicy.Phase.PLAYING -> TvUi.PLAYING
            BufferPolicy.Phase.REBUFFER -> TvUi.REBUFFER
        }
        state.update {
            it.copy(
                ui = ui, aheadSec = ahead.toInt(), barTargetSec = pol.barTargetSec, segs = appended,
                stalls = pol.stalls, speed = pol.speed, suggestMin = pol.suggestedNextMin() ?: 0
            )
        }
    }

    // ───────────── تنظيف ─────────────
    private fun note(s: String) = state.update { it.copy(note = s) }

    private fun fail(msg: String, notifyNode: Boolean) {
        val s = sid
        if (notifyNode && s.isNotEmpty()) {
            Repo.send(JSONObject().put("cmd", "tvStop").put("sid", s).put("reason", "app_error"))
            stoppingSid = s
        }
        val ch = state.value.channel
        endSession()
        state.update { TvState(ui = TvUi.ERROR, channel = ch, error = msg, stopping = stoppingSid.isNotEmpty()) }
    }

    private fun endSession() {
        tickerOn = false
        main.removeCallbacks(ticker)
        resetPlayback()
        sid = ""
        policy = null
    }

    private fun resetPlayback() {
        val old = sid
        player?.let { it.playWhenReady = false; it.stop(); it.clearMediaItems() }
        files.forEach { try { File(it).delete() } catch (_: Throwable) {} }
        held.values.forEach { try { File(it.path).delete() } catch (_: Throwable) {} }
        files.clear(); durs.clear(); held.clear(); skipped.clear()
        nextAppend = 1; appended = 0; errorsInRow = 0; waitingSince = 0L
        if (old.isNotEmpty()) {
            try { File(Repo.app.filesDir, "wa/tv/$old").deleteRecursively() } catch (_: Throwable) {}
        }
    }

    private fun newSid(): String = UUID.randomUUID().toString().replace("-", "").take(8)
}
