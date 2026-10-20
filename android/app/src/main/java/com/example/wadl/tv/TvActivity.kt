package com.example.wadl.tv

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color as AColor
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.wadl.DownloadService
import com.example.wadl.LauncherActivity
import com.example.wadl.Prefs
import com.example.wadl.Repo
import com.example.wadl.ui.AmberWarn
import com.example.wadl.ui.AppCard
import com.example.wadl.ui.AppTheme
import com.example.wadl.ui.GreenOk
import com.example.wadl.ui.Pill
import com.example.wadl.ui.RedErr
import com.example.wadl.ui.SectionTitle
import com.example.wadl.ui.SubText
import com.example.wadl.ui.connText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** TV Hub — الواجهة الرئيسية: قنوات + مشغّل + إعدادات */
class TvActivity : ComponentActivity() {

    private val askPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Repo.init(applicationContext)
        TvStore.init(applicationContext)
        LogoCache.init(applicationContext)
        Repo.setMode("tv")

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) askPermission.launch(Manifest.permission.POST_NOTIFICATIONS)

        // الخدمة تُبقي محرك واتساب حياً (مع الشاشة المغلقة)
        ContextCompat.startForegroundService(this, Intent(this, DownloadService::class.java))

        setContent {
            val th by Prefs.theme.collectAsState()
            val ac by Prefs.accent.collectAsState()
            AppTheme(th, ac) { TvRoot() }
        }
    }
}

private const val ALL = "__all"
private const val FAV = "__fav"
private const val REC = "__rec"

fun fmtTime(sec: Int): String = "%d:%02d".format(sec / 60, sec % 60)

private fun hostOf(url: String): String =
    try { java.net.URL(url).host } catch (_: Exception) { url.take(30) }

// ═════════════════════════════ الجذر والتنقل ═════════════════════════════
@Composable
private fun TvRoot() {
    val ctx = LocalContext.current
    val conn by Repo.conn.collectAsState()
    var screen by rememberSaveable { mutableStateOf("channels") }

    // خرج الحساب → شاشة الربط
    LaunchedEffect(conn) {
        if (conn == "needpair" || conn == "loggedout") {
            ctx.startActivity(Intent(ctx, LauncherActivity::class.java))
            (ctx as? Activity)?.finish()
        }
    }
    BackHandler(screen != "channels") { screen = "channels" }

    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.20f), MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.background)
            )
        )
    ) {
        when (screen) {
            "player" -> PlayerScreen(onBack = { screen = "channels" })
            "settings" -> SettingsScreen(onBack = { screen = "channels" })
            else -> ChannelsScreen(
                onPlay = { ch -> TvController.play(ch); screen = "player" },
                onOpenPlayer = { screen = "player" },
                onSettings = { screen = "settings" }
            )
        }
    }
}

// ═════════════════════════════ شاشة القنوات ═════════════════════════════
@Composable
private fun ChannelsScreen(onPlay: (TvChannel) -> Unit, onOpenPlayer: () -> Unit, onSettings: () -> Unit) {
    val channels by TvStore.channels.collectAsState()
    val favs by TvStore.favorites.collectAsState()
    val recents by TvStore.recents.collectAsState()
    val conn by Repo.conn.collectAsState()
    val st by TvController.state.collectAsState()
    val logoProg by LogoCache.progress.collectAsState()

    var query by rememberSaveable { mutableStateOf("") }
    var group by rememberSaveable { mutableStateOf(ALL) }
    var showImport by rememberSaveable { mutableStateOf(false) }

    val groups = remember(channels) { M3uParser.groups(channels) }
    val byId = remember(channels) { channels.associateBy { it.id } }
    val shown = remember(channels, query, group, favs, recents) {
        val base = when (group) {
            ALL -> channels
            FAV -> channels.filter { it.id in favs }
            REC -> recents.mapNotNull { byId[it] }
            else -> channels.filter { it.group == group }
        }
        val q = query.trim()
        if (q.isEmpty()) base else base.filter { it.name.contains(q, ignoreCase = true) }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            // ── الترويسة ──
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("TV Hub", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onBackground)
                    SubText(if (channels.isEmpty()) "أضف قائمة m3u لتبدأ" else "${channels.size} قناة • ${groups.size} مجموعة")
                }
                Pill(connText(conn), if (conn == "open") GreenOk else AmberWarn)
                Spacer(Modifier.width(10.dp))
                RoundBtn("⚙", onClick = onSettings)
            }

            if (channels.isEmpty()) {
                EmptyChannels(onImport = { showImport = true })
            } else {
                // ── البحث ──
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("ابحث عن قناة…") },
                    shape = RoundedCornerShape(28.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = Color.Transparent
                    ),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                )
                Spacer(Modifier.height(10.dp))

                // ── المجموعات ──
                val chips = remember(groups) { listOf(ALL to "الكل", FAV to "⭐ المفضلة", REC to "🕘 الأخيرة") + groups.map { it.first to it.first } }
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(chips, key = { it.first }) { (key, label) ->
                        Chip(label, selected = group == key) { group = key }
                    }
                }

                logoProg?.let { (d, t) ->
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                        SubText("جارٍ حفظ الأيقونات في الهاتف  $d / $t")
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(progress = { if (t > 0) d.toFloat() / t else 0f }, modifier = Modifier.fillMaxWidth())
                    }
                }
                if (st.stopping && st.ui == TvUi.IDLE) {
                    Text(
                        "⏹ جارٍ تأكيد الإيقاف على الخادم…", color = AmberWarn, fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                    )
                }

                // ── الشبكة ──
                if (shown.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(40.dp), contentAlignment = Alignment.TopCenter) {
                        SubText(if (group == FAV) "لم تضف قنوات مفضلة بعد — اضغط ☆ على أي قناة" else "لا توجد نتائج")
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(108.dp),
                        contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 130.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(shown, key = { it.id }) { ch ->
                            ChannelCard(
                                ch, fav = ch.id in favs, playing = st.channel?.id == ch.id && st.ui != TvUi.IDLE,
                                onPlay = { onPlay(ch) }, onFav = { TvStore.toggleFav(ch.id) }
                            )
                        }
                    }
                }
            }
        }

        // ── شريط المشغّل المصغّر ──
        if (st.ui != TvUi.IDLE) {
            MiniPlayer(st, onOpen = onOpenPlayer, modifier = Modifier.align(Alignment.BottomCenter))
        }
    }

    if (showImport) ImportDialog(onDismiss = { showImport = false })
}

@Composable
private fun EmptyChannels(onImport: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier.size(110.dp).clip(RoundedCornerShape(34.dp))
                .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)))),
            contentAlignment = Alignment.Center
        ) { Text("📺", fontSize = 52.sp) }
        Spacer(Modifier.height(22.dp))
        Text("لا توجد قنوات بعد", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(8.dp))
        Text(
            "أضف رابط قائمة m3u أو ملفاً من هاتفك مرة واحدة (بإنترنت عادي).\nبعدها تُحفظ القنوات والأيقونات ويعمل التطبيق عبر واتساب فقط.",
            textAlign = TextAlign.Center, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onImport, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("إضافة قائمة قنوات", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ChannelCard(ch: TvChannel, fav: Boolean, playing: Boolean, onPlay: () -> Unit, onFav: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        Modifier.clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .then(if (playing) Modifier.border(BorderStroke(2.dp, MaterialTheme.colorScheme.primary), shape) else Modifier)
            .clickable(onClick = onPlay)
            .padding(10.dp)
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) { ChannelLogo(ch.logo, ch.name, 64.dp) }
            Spacer(Modifier.height(8.dp))
            Text(
                ch.name, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface
            )
        }
        Text(
            if (fav) "★" else "☆", fontSize = 18.sp,
            color = if (fav) Color(0xFFFFC53D) else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.TopStart).clip(CircleShape).clickable(onClick = onFav).padding(4.dp)
        )
    }
}

/** أيقونة محفوظة في الهاتف؛ إن لم توجد تظهر أول حرف من الاسم */
@Composable
fun ChannelLogo(url: String, name: String, size: Dp) {
    val ver by LogoCache.version.collectAsState()
    val bmp by produceState<android.graphics.Bitmap?>(initialValue = null, url, ver) {
        if (value == null && url.isNotBlank()) value = withContext(Dispatchers.IO) { LogoCache.load(url) }
    }
    val b = bmp
    if (b != null) {
        Image(
            bitmap = remember(b) { b.asImageBitmap() }, contentDescription = null, contentScale = ContentScale.Fit,
            modifier = Modifier.size(size).padding(6.dp)
        )
    } else {
        Text(
            name.trim().take(1).uppercase(), fontSize = (size.value * 0.4f).sp, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun MiniPlayer(st: TvState, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val ch = st.channel
    Row(
        modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onOpen)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
            if (ch != null) ChannelLogo(ch.logo, ch.name, 46.dp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(ch?.name ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(statusLine(st), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = statusColor(st))
        }
        RoundBtn("⏹", danger = true) { TvController.stop() }
    }
}

private fun statusLine(st: TvState): String = when (st.ui) {
    TvUi.STARTING -> "جارٍ الاتصال بالبوت…"
    TvUi.PREBUFFER -> "تجهيز الوسادة ${fmtTime(st.aheadSec)} / ${fmtTime(st.barTargetSec)}"
    TvUi.PLAYING -> if (st.userPaused) "متوقف مؤقتاً • الوسادة ${fmtTime(st.aheadSec)}" else "يعمل • الوسادة ${fmtTime(st.aheadSec)}"
    TvUi.REBUFFER -> "نفدت الوسادة — إعادة تجهيز ${fmtTime(st.aheadSec)} / ${fmtTime(st.barTargetSec)}"
    TvUi.ERROR -> st.error
    TvUi.IDLE -> ""
}

@Composable
private fun statusColor(st: TvState): Color = when (st.ui) {
    TvUi.PLAYING -> GreenOk
    TvUi.REBUFFER, TvUi.ERROR -> if (st.ui == TvUi.ERROR) RedErr else AmberWarn
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

// ═════════════════════════════ شاشة المشغّل ═════════════════════════════
@Composable
private fun PlayerScreen(onBack: () -> Unit) {
    val st by TvController.state.collectAsState()
    val bufMin by TvStore.bufferMin.collectAsState()
    val act = LocalContext.current as Activity
    var full by rememberSaveable { mutableStateOf(false) }

    DisposableEffect(full) {
        val c = WindowCompat.getInsetsController(act.window, act.window.decorView)
        if (full) {
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            c.hide(WindowInsetsCompat.Type.systemBars())
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            c.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            c.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    BackHandler(full) { full = false }

    if (full) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            VideoSurface(Modifier.fillMaxSize())
            BufferOverlay(st)
            Box(Modifier.align(Alignment.TopStart).padding(16.dp)) { RoundBtn("✕") { full = false } }
        }
        return
    }

    Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundBtn("›", onClick = onBack)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(st.channel?.name ?: "—", fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onBackground)
                SubText(st.channel?.group ?: "")
            }
        }

        Box(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(24.dp)).background(Color.Black)
        ) {
            VideoSurface(Modifier.fillMaxSize())
            BufferOverlay(st)
        }
        Spacer(Modifier.height(16.dp))

        // ── الحالة ──
        Column(Modifier.padding(horizontal = 16.dp)) {
            AppCard {
                Text(statusLine(st).ifBlank { "—" }, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = statusColor(st))
                if (st.ui == TvUi.PREBUFFER || st.ui == TvUi.REBUFFER) {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { if (st.barTargetSec > 0) (st.aheadSec.toFloat() / st.barTargetSec).coerceIn(0f, 1f) else 0f },
                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape)
                    )
                    Spacer(Modifier.height(8.dp))
                    SubText(
                        if (st.ui == TvUi.PREBUFFER) "يبدأ التشغيل تلقائياً عند اكتمال الوسادة — الوسادة الكبيرة تعني بثاً بلا تقطيع."
                        else "لا نستأنف قبل عودة وسادة كافية حتى لا يتكرر التوقف."
                    )
                }
                if (st.ui == TvUi.PLAYING && st.speed < 1f) { Spacer(Modifier.height(6.dp)); SubText("تعويض خفيف للسرعة لحماية الوسادة") }
                if (st.note.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(st.note, fontSize = 12.sp, color = AmberWarn) }
                Spacer(Modifier.height(8.dp))
                SubText("الأجزاء: ${st.segs}   •   مرات نفاد الوسادة: ${st.stalls}")
            }

            if (st.suggestMin > 0) {
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = { TvController.setBufferMin(st.suggestMin) }, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                    Text("تكرر التوقف — ارفع الوسادة إلى ${st.suggestMin} دقائق للمشاهدة القادمة")
                }
            }

            Spacer(Modifier.height(14.dp))
            // ── التحكم ──
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                LabeledBtn(if (st.userPaused) "▶" else "⏸", if (st.userPaused) "استئناف" else "إيقاف مؤقت", enabled = st.ui == TvUi.PLAYING) { TvController.togglePause() }
                LabeledBtn("⏹", "إيقاف", danger = true, big = true) { TvController.stop(); onBack() }
                LabeledBtn("⛶", "ملء الشاشة", enabled = st.ui == TvUi.PLAYING || st.ui == TvUi.REBUFFER) { full = true }
            }

            Spacer(Modifier.height(18.dp))
            SectionTitle("حجم الوسادة")
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(3, 5, 10).forEach { m -> Chip("$m دقائق", selected = bufMin == m) { TvController.setBufferMin(m) } }
            }
            Spacer(Modifier.height(6.dp))
            SubText(if (st.ui == TvUi.PREBUFFER) "يُطبَّق فوراً أثناء التجهيز." else "يُطبَّق عند فتح القناة التالية.")

            if (st.stopping) { Spacer(Modifier.height(12.dp)); Text("⏹ جارٍ تأكيد الإيقاف على الخادم…", color = AmberWarn, fontSize = 12.sp) }
            Spacer(Modifier.height(30.dp))
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoSurface(modifier: Modifier) {
    var view by remember { mutableStateOf<PlayerView?>(null) }
    DisposableEffect(Unit) { onDispose { view?.player = null } }   // لا نُبقي مرجعاً للواجهة داخل المشغّل
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                setBackgroundColor(AColor.BLACK)
                player = TvController.playerForView()
                view = this
            }
        },
        update = { it.player = TvController.playerForView() }
    )
}

/** طبقة فوق الفيديو: دائرة تقدّم أثناء التجهيز/إعادة التجهيز، أو رسالة الخطأ */
@Composable
private fun BufferOverlay(st: TvState) {
    val show = st.ui == TvUi.STARTING || st.ui == TvUi.PREBUFFER || st.ui == TvUi.REBUFFER || st.ui == TvUi.ERROR || st.ui == TvUi.IDLE
    if (!show) return
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
        when (st.ui) {
            TvUi.ERROR -> Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("⚠", fontSize = 34.sp, color = RedErr)
                Spacer(Modifier.height(8.dp))
                Text(st.error, color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                val ch = st.channel
                if (ch != null) Button(onClick = { TvController.play(ch) }, shape = RoundedCornerShape(14.dp)) { Text("إعادة المحاولة") }
            }
            TvUi.PREBUFFER, TvUi.REBUFFER -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { if (st.barTargetSec > 0) (st.aheadSec.toFloat() / st.barTargetSec).coerceIn(0f, 1f) else 0f },
                        modifier = Modifier.size(96.dp), strokeWidth = 7.dp, color = MaterialTheme.colorScheme.primary, trackColor = Color(0x33FFFFFF)
                    )
                    Text(fmtTime(st.aheadSec), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(10.dp))
                Text(if (st.ui == TvUi.PREBUFFER) "جارٍ تجهيز الوسادة" else "إعادة تجهيز الوسادة", color = Color.White, fontSize = 13.sp)
                Text("من ${fmtTime(st.barTargetSec)}", color = Color(0xAAFFFFFF), fontSize = 12.sp)
            }
            TvUi.STARTING -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(modifier = Modifier.size(56.dp), color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(10.dp))
                Text("جارٍ الاتصال بالبوت…", color = Color.White, fontSize = 13.sp)
            }
            else -> Text("📺", fontSize = 44.sp)
        }
    }
}

// ═════════════════════════════ الإعدادات ═════════════════════════════
@Composable
private fun SettingsScreen(onBack: () -> Unit) {
    val target by Prefs.target.collectAsState()
    val bufMin by TvStore.bufferMin.collectAsState()
    val channels by TvStore.channels.collectAsState()
    val src by TvStore.sourceUrl.collectAsState()
    val upd by TvStore.updatedAt.collectAsState()
    val busy by TvStore.importBusy.collectAsState()
    val msg by TvStore.importMsg.collectAsState()
    val conn by Repo.conn.collectAsState()
    val up by Repo.engineUp.collectAsState()
    val logs by Repo.logs.collectAsState()
    var showImport by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundBtn("›", onClick = onBack)
            Spacer(Modifier.width(12.dp))
            Text("الإعدادات", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onBackground)
        }
        Spacer(Modifier.height(16.dp))

        AppCard {
            SectionTitle("رقم البوت")
            Spacer(Modifier.height(4.dp))
            SubText("الرقم الذي يعمل عليه البوت (مع رمز الدولة، أرقام فقط). منه تصل الأجزاء وإليه تُرسَل الأوامر.")
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = target, onValueChange = { v -> Prefs.set(Prefs.target, "target", v.filter { it.isDigit() }) },
                singleLine = true, placeholder = { Text("مثال: 213555000111") },
                textStyle = TextStyle(textDirection = TextDirection.Ltr),
                shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            SubText("مهم: رقمك الذي ربطتَه بالتطبيق يجب أن يكون ضمن TV_ALLOWED في البوت.")
        }
        Spacer(Modifier.height(12.dp))

        AppCard {
            SectionTitle("حجم الوسادة الافتراضي")
            Spacer(Modifier.height(4.dp))
            SubText("كلما كبرت قلّ التقطيع، لكن يطول الانتظار قبل الصورة الأولى ويكبر التأخر عن البث الحقيقي.")
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(3, 5, 10).forEach { m -> Chip("$m دقائق", selected = bufMin == m) { TvController.setBufferMin(m) } }
            }
        }
        Spacer(Modifier.height(12.dp))

        AppCard {
            SectionTitle("قائمة القنوات")
            Spacer(Modifier.height(4.dp))
            SubText(
                if (channels.isEmpty()) "لا توجد قائمة بعد."
                else "${channels.size} قناة • المصدر: ${if (src.startsWith("http")) hostOf(src) else src}" +
                    if (upd > 0) " • آخر تحديث: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(upd))}" else ""
            )
            if (msg.isNotBlank()) { Spacer(Modifier.height(6.dp)); SubText(msg) }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { showImport = true }, enabled = !busy, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)) { Text("قائمة جديدة") }
                OutlinedButton(
                    onClick = { TvStore.refreshSource() }, enabled = !busy && src.startsWith("http"),
                    shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)
                ) { Text("تحديث") }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { LogoCache.prefetch(Repo.scope, channels.map { it.logo }) },
                enabled = channels.isNotEmpty(), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()
            ) { Text("إعادة تنزيل الأيقونات الناقصة") }
        }
        Spacer(Modifier.height(12.dp))

        AppCard {
            SectionTitle("الاتصال")
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pill(connText(conn), if (conn == "open") GreenOk else AmberWarn)
                Spacer(Modifier.width(8.dp))
                Pill(if (up) "المحرك يعمل" else "المحرك متوقف", if (up) GreenOk else RedErr)
            }
            Spacer(Modifier.height(8.dp))
            logs.takeLast(6).forEach { SubText(it) }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { Repo.diag() }, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)) { Text("فحص الاتصال") }
                TextButton(onClick = { Repo.logout() }, modifier = Modifier.weight(1f)) { Text("تسجيل الخروج", color = RedErr) }
            }
        }
        Spacer(Modifier.height(30.dp))
    }
    if (showImport) ImportDialog(onDismiss = { showImport = false })
}

// ═════════════════════════════ مكوّنات مشتركة ═════════════════════════════
@Composable
private fun ImportDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val busy by TvStore.importBusy.collectAsState()
    val msg by TvStore.importMsg.collectAsState()
    var url by rememberSaveable { mutableStateOf(TvStore.sourceUrl.value.takeIf { it.startsWith("http") } ?: "") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) TvStore.startImportUri(ctx, uri)
    }
    LaunchedEffect(msg) { if (msg.startsWith("تم استيراد")) { delay(1200); onDismiss() } }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        shape = RoundedCornerShape(28.dp),
        title = { Text("إضافة قائمة قنوات", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                SubText("يحتاج إنترنت عادياً هذه المرة فقط. بعدها تُحفظ القنوات والأيقونات في هاتفك.")
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = url, onValueChange = { url = it.trim() }, singleLine = true,
                    placeholder = { Text("https://…/get.php?username=…&type=m3u") },
                    textStyle = TextStyle(textDirection = TextDirection.Ltr),
                    shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(), enabled = !busy
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !busy, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Text("أو اختر ملف m3u من الهاتف")
                }
                if (busy) { Spacer(Modifier.height(12.dp)); LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
                if (msg.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(msg, fontSize = 13.sp, color = if (msg.startsWith("فشل")) RedErr else MaterialTheme.colorScheme.onSurface)
                }
            }
        },
        confirmButton = {
            Button(onClick = { TvStore.startImportUrl(url) }, enabled = !busy && url.startsWith("http"), shape = RoundedCornerShape(14.dp)) { Text("استيراد") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("إغلاق") } }
    )
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    val fg = if (selected) Color.White else MaterialTheme.colorScheme.onSurface
    Text(
        text, color = fg, fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(bg).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 9.dp)
    )
}

@Composable
private fun RoundBtn(label: String, danger: Boolean = false, onClick: () -> Unit) {
    val bg = if (danger) RedErr.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surface
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text(label, fontSize = 18.sp, color = if (danger) RedErr else MaterialTheme.colorScheme.onSurface) }
}

@Composable
private fun LabeledBtn(icon: String, label: String, danger: Boolean = false, big: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val s = if (big) 72.dp else 58.dp
    val bg = when {
        danger -> RedErr
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(s).clip(CircleShape).background(if (enabled) bg else bg.copy(alpha = 0.4f))
                .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
            contentAlignment = Alignment.Center
        ) { Text(icon, fontSize = if (big) 28.sp else 22.sp, color = if (danger) Color.White else MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.4f)) }
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
