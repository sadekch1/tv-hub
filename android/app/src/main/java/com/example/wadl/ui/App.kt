@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.wadl.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.wadl.Prefs
import com.example.wadl.Repo

@Composable
fun AppRoot() {
    val th by Prefs.theme.collectAsState()
    val ac by Prefs.accent.collectAsState()
    AppTheme(th, ac) {
        val conn by Repo.conn.collectAsState()
        val pairing by Repo.pairingActive.collectAsState()
        val showLogin = pairing || conn == "needpair" || conn == "loggedout" || conn == "pairing" ||
            (conn == "idle" && !Repo.isRegistered())

        val fatal by Repo.fatal.collectAsState()
        val crash by Repo.lastCrash.collectAsState()
        val clip = LocalClipboardManager.current

        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            if (showLogin) LoginScreen() else MainScreen()
            if (fatal.isNotEmpty()) {
                Text(
                    fatal + "\n(اضغط للنسخ)", color = androidx.compose.ui.graphics.Color.White, fontSize = 12.sp,
                    maxLines = 16,
                    modifier = Modifier.align(Alignment.TopCenter).systemBarsPadding().padding(12.dp)
                        .clip(RoundedCornerShape(12.dp)).background(RedErr)
                        .clickable { clip.setText(AnnotatedString(fatal)) }.padding(12.dp)
                )
            }
        }
        if (crash.isNotEmpty()) {
            AlertDialog(
                onDismissRequest = { Repo.lastCrash.value = "" },
                containerColor = MaterialTheme.colorScheme.surface,
                title = { Text("سبب إغلاق سابق") },
                text = {
                    Text(crash, fontSize = 11.sp, modifier = Modifier.verticalScroll(rememberScrollState()),
                        color = MaterialTheme.colorScheme.onSurface)
                },
                confirmButton = { TextButton(onClick = { clip.setText(AnnotatedString(crash)) }) { Text("نسخ") } },
                dismissButton = { TextButton(onClick = { Repo.lastCrash.value = "" }) { Text("إغلاق") } }
            )
        }
    }
}

// ───────────────────────── تسجيل الدخول ─────────────────────────
@Composable
fun LoginScreen() {
    val code by Repo.code.collectAsState()
    val err by Repo.errorMsg.collectAsState()
    val conn by Repo.conn.collectAsState()
    val pairing by Repo.pairingActive.collectAsState()
    val clipboard = LocalClipboardManager.current
    var phone by rememberSaveable { mutableStateOf("") }
    val accent = MaterialTheme.colorScheme.primary

    Column(
        Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier.size(84.dp).clip(RoundedCornerShape(26.dp)).background(accent),
            contentAlignment = Alignment.Center
        ) { Text("⬇", fontSize = 40.sp, color = androidx.compose.ui.graphics.Color.White) }

        Spacer(Modifier.height(16.dp))
        Text("WA Downloader", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        SubText("سجّل الدخول مرة واحدة وتُحفظ الجلسة")
        Spacer(Modifier.height(28.dp))

        AppCard {
            if (code.isEmpty()) {
                SectionTitle("ربط الواتساب")
                Spacer(Modifier.height(4.dp))
                SubText("أدخل رقمك مع رمز الدولة بدون +")
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it.filter { c -> c.isDigit() } },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("مثال: 213562396663") },
                    singleLine = true,
                    enabled = !pairing,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    shape = RoundedCornerShape(14.dp)
                )
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = { Repo.pair(phone) },
                    enabled = phone.length >= 8 && !pairing,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp)
                ) { Text(if (pairing) "جارٍ طلب الرمز…" else "طلب رمز الربط", fontWeight = FontWeight.Bold) }

                if (pairing) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { Repo.cancelPairing() }, modifier = Modifier.fillMaxWidth()) { Text("إلغاء") }
                }
            } else {
                SectionTitle("رمز الربط")
                Spacer(Modifier.height(12.dp))
                Text(
                    code, fontSize = 36.sp, fontWeight = FontWeight.Bold, color = accent,
                    letterSpacing = 4.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant).padding(vertical = 18.dp)
                )
                Spacer(Modifier.height(14.dp))
                SubText("1) افتح واتساب ← الأجهزة المرتبطة")
                SubText("2) اختر «ربط جهاز»")
                SubText("3) اختر «الربط برقم الهاتف بدلاً من ذلك»")
                SubText("4) أدخل الرمز أعلاه")
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { clipboard.setText(AnnotatedString(code.replace("-", ""))) },
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)
                    ) { Text("نسخ الرمز") }
                    OutlinedButton(
                        onClick = { Repo.cancelPairing() },
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)
                    ) { Text("إلغاء") }
                }
                Spacer(Modifier.height(8.dp))
                SubText("الحالة: " + connText(conn))
            }
            if (err.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(err, color = RedErr, fontSize = 13.sp)
            }
        }

        Spacer(Modifier.height(16.dp))
        val logs by Repo.logs.collectAsState()
        val up by Repo.engineUp.collectAsState()
        AppCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("التشخيص", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f))
                Pill(if (up) "المحرك يعمل" else "المحرك لم يبدأ", if (up) GreenOk else RedErr)
            }
            Spacer(Modifier.height(6.dp))
            SubText("الحالة: " + connText(conn))
            logs.takeLast(10).forEach { SubText(it) }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { Repo.diag() }, modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) { Text("🔍 فحص الاتصال بواتساب") }
        }
    }
}

fun connText(c: String) = when (c) {
    "open" -> "متصل"
    "connecting" -> "جارٍ الاتصال…"
    "reconnecting" -> "إعادة الاتصال…"
    "pairing" -> "بانتظار إدخال الرمز"
    "replaced" -> "فُتحت جلسة أخرى بنفس الحساب"
    "needpair", "loggedout" -> "غير مسجّل"
    else -> "تشغيل المحرك…"
}

// ───────────────────────── الشاشة الرئيسية ─────────────────────────
@Composable
fun MainScreen() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val items = listOf("التحميلات" to "⬇", "الدمج" to "🧩", "الإعدادات" to "⚙")
    val surface = MaterialTheme.colorScheme.surface

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = surface) {
                items.forEachIndexed { i, (label, icon) ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Text(icon, fontSize = 20.sp) },
                        label = { Text(label, fontSize = 12.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    )
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> DownloadsTab()
                1 -> MergeTab()
                else -> SettingsTab()
            }
        }
    }
}
