@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.wadl.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.wadl.DownloadService
import com.example.wadl.Prefs
import com.example.wadl.Repo

@Composable
fun SettingsTab() {
    val ctx = LocalContext.current
    var showLogout by remember { mutableStateOf(false) }
    var showLogs by remember { mutableStateOf(false) }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("الإعدادات", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)

        // ── المجلد ──
        val folder by Prefs.folder.collectAsState()
        var folderText by remember(folder) { mutableStateOf(folder) }
        AppCard {
            SectionTitle("مجلد الحفظ")
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = folderText, onValueChange = { folderText = it },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("اسم المجلد") }, shape = RoundedCornerShape(14.dp)
            )
            Spacer(Modifier.height(4.dp))
            SubText("المسار: Download/${Prefs.cleanFolderName(folderText)}")
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val n = Prefs.cleanFolderName(folderText)
                        Prefs.set(Prefs.folder, "folder", n); folderText = n
                    },
                    enabled = Prefs.cleanFolderName(folderText) != folder,
                    shape = RoundedCornerShape(12.dp)
                ) { Text("حفظ") }
                OutlinedButton(
                    onClick = { Prefs.set(Prefs.folder, "folder", Prefs.DEFAULT_FOLDER); folderText = Prefs.DEFAULT_FOLDER },
                    shape = RoundedCornerShape(12.dp)
                ) { Text("الافتراضي") }
            }
            SubText("الملفات الجديدة فقط تُحفظ في المجلد الجديد.")
        }

        // ── المصدر والفلترة ──
        val target by Prefs.target.collectAsState()
        val maxSize by Prefs.maxSizeMb.collectAsState()
        val exts by Prefs.exts.collectAsState()
        var targetText by remember(target) { mutableStateOf(target) }
        var sizeText by remember(maxSize) { mutableStateOf(if (maxSize > 0) maxSize.toString() else "") }
        var extText by remember(exts) { mutableStateOf(exts) }
        AppCard {
            SectionTitle("المصدر والفلترة")
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = targetText,
                onValueChange = { targetText = it.filter { c -> c.isDigit() }; Prefs.set(Prefs.target, "target", targetText) },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("رقم المحادثة (فارغ = الكل)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                shape = RoundedCornerShape(14.dp)
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = sizeText,
                onValueChange = {
                    sizeText = it.filter { c -> c.isDigit() }.take(6)
                    Prefs.set(Prefs.maxSizeMb, "maxSizeMb", sizeText.toIntOrNull() ?: 0)
                },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("أقصى حجم بالميغابايت (فارغ = بلا حد)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = RoundedCornerShape(14.dp)
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = extText,
                onValueChange = { extText = it; Prefs.set(Prefs.exts, "exts", it) },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("الأنواع المسموحة مثل: zip,rar,001") },
                shape = RoundedCornerShape(14.dp)
            )
        }

        // ── التحميل ──
        val gMin by Prefs.gapMin.collectAsState()
        val gMax by Prefs.gapMax.collectAsState()
        var range by remember(gMin, gMax) { mutableStateOf(gMin.toFloat()..gMax.toFloat()) }
        val wifiOnly by Prefs.wifiOnly.collectAsState()
        AppCard {
            SectionTitle("التحميل")
            Spacer(Modifier.height(8.dp))
            Text("الفاصل العشوائي بين الملفات: ${range.start.toInt()} – ${range.endInclusive.toInt()} ثانية",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
            RangeSlider(
                value = range,
                onValueChange = { range = it },
                onValueChangeFinished = {
                    Prefs.set(Prefs.gapMin, "gapMin", range.start.toInt())
                    Prefs.set(Prefs.gapMax, "gapMax", range.endInclusive.toInt())
                },
                valueRange = 1f..30f,
                steps = 28
            )
            ToggleRow("واي فاي فقط", wifiOnly) { Prefs.set(Prefs.wifiOnly, "wifiOnly", it) }
        }

        // ── الدمج ──
        val mode by Prefs.mergeMode.collectAsState()
        val count by Prefs.expectedCount.collectAsState()
        val idle by Prefs.idleSec.collectAsState()
        val delAfter by Prefs.deleteAfterMerge.collectAsState()
        AppCard {
            SectionTitle("الدمج التلقائي")
            Spacer(Modifier.height(8.dp))
            Chips(listOf("يدوي فقط", "عند اكتمال العدد", "عند توقف الوصول"), mode) { Prefs.set(Prefs.mergeMode, "mergeMode", it) }
            if (mode == 1) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("عدد الأجزاء: $count", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
                    OutlinedButton(onClick = { if (count > 2) Prefs.set(Prefs.expectedCount, "expectedCount", count - 1) }) { Text("−") }
                    Spacer(Modifier.size(8.dp))
                    OutlinedButton(onClick = { if (count < 99) Prefs.set(Prefs.expectedCount, "expectedCount", count + 1) }) { Text("+") }
                }
            }
            if (mode == 2) {
                Spacer(Modifier.height(10.dp))
                SubText("يدمج عندما لا يصل جزء جديد خلال:")
                Spacer(Modifier.height(6.dp))
                val opts = listOf(30, 60, 120, 300)
                Chips(opts.map { "${it}ث" }, opts.indexOf(idle).coerceAtLeast(0)) { Prefs.set(Prefs.idleSec, "idleSec", opts[it]) }
            }
            Spacer(Modifier.height(6.dp))
            SubText("الدمج التلقائي يتطلب أجزاء متتالية تبدأ من 1 بلا فجوات.")
            ToggleRow("حذف الأجزاء بعد نجاح الدمج", delAfter) { Prefs.set(Prefs.deleteAfterMerge, "deleteAfterMerge", it) }
        }

        // ── المظهر ──
        val th by Prefs.theme.collectAsState()
        val ac by Prefs.accent.collectAsState()
        AppCard {
            SectionTitle("المظهر")
            Spacer(Modifier.height(8.dp))
            Chips(themeDefs.map { it.name }, th) { Prefs.set(Prefs.theme, "theme", it) }
            Spacer(Modifier.height(12.dp))
            SubText("لون التمييز")
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                accentColors.forEachIndexed { i, c ->
                    Box(
                        Modifier.size(38.dp).clip(CircleShape).background(c).clickable { Prefs.set(Prefs.accent, "accent", i) },
                        contentAlignment = Alignment.Center
                    ) { if (i == ac) Text("✓", color = Color.White, fontWeight = FontWeight.Bold) }
                }
            }
        }

        // ── النظام والحساب ──
        AppCard {
            SectionTitle("النظام والحساب")
            Spacer(Modifier.height(8.dp))
            val pm = ctx.getSystemService(PowerManager::class.java)
            val ignoring = Build.VERSION.SDK_INT < 23 || pm.isIgnoringBatteryOptimizations(ctx.packageName)
            if (!ignoring) {
                OutlinedButton(
                    onClick = {
                        try {
                            ctx.startActivity(
                                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                            )
                        } catch (_: Exception) {}
                    },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)
                ) { Text("🔋 استثناء من توفير البطارية (موصى به)") }
                Spacer(Modifier.height(8.dp))
            }
            OutlinedButton(onClick = { showLogs = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                Text("📄 سجل الأحداث")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { showLogout = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                Text("🚪 تسجيل الخروج", color = RedErr)
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    Repo.setMode(null)
                    ctx.startActivity(
                        Intent(ctx, com.example.wadl.LauncherActivity::class.java).putExtra("choose", true)
                    )
                    (ctx as? android.app.Activity)?.finish()
                },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)
            ) { Text("🔀 تغيير الوضع") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    ctx.stopService(Intent(ctx, DownloadService::class.java))
                    ctx.stopService(Intent(ctx, com.example.wadl.NodeService::class.java))
                    android.os.Process.killProcess(android.os.Process.myPid())
                },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)
            ) { Text("⏻ إيقاف التطبيق بالكامل") }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (showLogout) {
        AlertDialog(
            onDismissRequest = { showLogout = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("تسجيل الخروج؟") },
            text = { Text("سيُحذف الربط مع واتساب وستحتاج لإدخال رمز جديد.") },
            confirmButton = { TextButton(onClick = { showLogout = false; Repo.logout() }) { Text("خروج", color = RedErr) } },
            dismissButton = { TextButton(onClick = { showLogout = false }) { Text("إلغاء") } }
        )
    }
    if (showLogs) {
        val logs by Repo.logs.collectAsState()
        AlertDialog(
            onDismissRequest = { showLogs = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("سجل الأحداث") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (logs.isEmpty()) SubText("لا توجد أحداث")
                    logs.reversed().forEach { SubText(it); Spacer(Modifier.height(4.dp)) }
                }
            },
            confirmButton = { TextButton(onClick = { showLogs = false }) { Text("إغلاق") } }
        )
    }
}

@Composable
fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun Chips(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, label ->
            FilterChip(selected = selected == i, onClick = { onSelect(i) }, label = { Text(label) })
        }
    }
}
