package com.example.wadl

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.wadl.ui.AmberWarn
import com.example.wadl.ui.AppCard
import com.example.wadl.ui.AppTheme
import com.example.wadl.ui.GreenOk
import com.example.wadl.ui.LoginScreen
import com.example.wadl.ui.Pill
import com.example.wadl.ui.RedErr
import com.example.wadl.ui.SubText
import com.example.wadl.ui.connText
import com.example.wadl.watch.WatchActivity

/**
 * نقطة الدخول:
 *  ① تسجيل الرقم مرة واحدة (شاشة الربط الموجودة كما هي)
 *  ② اختيار الوضع: مشاهدة أو تحميل (واتساب واحد مشترك بينهما)
 * الواجهتان (المشاهدة والتحميل) كما هما، ويمكن التبديل من «🔀 تغيير الوضع».
 */
class LauncherActivity : ComponentActivity() {

    private val hub by lazy { getSharedPreferences("hub", Context.MODE_PRIVATE) }
    private val askPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Repo.init(applicationContext)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (Build.VERSION.SDK_INT <= 28 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            askPermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        // اتصال واتساب يبدأ دائماً (تسجيل الدخول ثم الوضعان يعتمدان عليه)
        ContextCompat.startForegroundService(this, Intent(this, DownloadService::class.java))

        // TV Hub: لا شاشة اختيار — إن كان الحساب مربوطاً ندخل مباشرة
        if (Repo.isRegistered()) {
            open("tv")
            return
        }

        setContent {
            val th by Prefs.theme.collectAsState()
            val ac by Prefs.accent.collectAsState()
            AppTheme(th, ac) {
                val conn by Repo.conn.collectAsState()
                val pairing by Repo.pairingActive.collectAsState()
                val showLogin = pairing || conn == "needpair" || conn == "loggedout" || conn == "pairing" ||
                    (conn == "idle" && !Repo.isRegistered())

                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    if (showLogin) LoginScreen() else {
                        // بعد نجاح الربط (conn=open) ننتقل إلى التطبيق
                        androidx.compose.runtime.LaunchedEffect(conn) { if (conn == "open") open("tv") }
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("TV Hub", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onBackground)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Chooser(conn: String) {
        var rem by remember { mutableStateOf(hub.getBoolean("remember", false)) }
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                Modifier.size(84.dp).clip(RoundedCornerShape(26.dp)).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) { Text("⬡", fontSize = 40.sp, color = Color.White) }
            Spacer(Modifier.height(16.dp))
            Text("TV Hub", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            SubText("اختر ما تريد فعله")
            Spacer(Modifier.height(28.dp))

            Choice("▶", "مشاهدة", "استقبال الأجزاء من الشات وتشغيلها كبث مباشر تلقائياً") { pick("watch", rem) }
            Spacer(Modifier.height(12.dp))
            Choice("⬇", "تحميل", "تحميل الملفات التي تصل إلى الشات ودمجها") { pick("download", rem) }
            Spacer(Modifier.height(16.dp))

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "تذكّر اختياري ولا تسأل مجدداً", Modifier.weight(1f), fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Switch(checked = rem, onCheckedChange = { rem = it })
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pill(connText(conn), if (conn == "open") GreenOk else AmberWarn)
                TextButton(onClick = { Repo.logout() }) { Text("تسجيل الخروج", color = RedErr) }
            }
        }
    }

    private fun pick(mode: String, remember: Boolean) {
        hub.edit().putBoolean("remember", remember).putString("mode", mode).apply()
        open(mode)
    }

    private fun open(mode: String) {
        Repo.setMode(mode)
        val target = when (mode) {
            "tv" -> com.example.wadl.tv.TvActivity::class.java
            "watch" -> WatchActivity::class.java
            else -> MainActivity::class.java
        }
        startActivity(Intent(this, target))
        finish()
    }
}

@Composable
private fun Choice(icon: String, title: String, sub: String, onClick: () -> Unit) {
    AppCard(Modifier.clickable { onClick() }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) { Text(icon, fontSize = 26.sp, color = Color.White) }
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                SubText(sub)
            }
        }
    }
}
