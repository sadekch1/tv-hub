package com.example.wadl.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.wadl.Job
import com.example.wadl.JobStatus
import com.example.wadl.Rec
import com.example.wadl.Repo
import com.example.wadl.Storage

@Composable
fun DownloadsTab() {
    val jobs by Repo.jobs.collectAsState()
    val history by Repo.history.collectAsState()
    val conn by Repo.conn.collectAsState()
    val paused by Repo.userPaused.collectAsState()

    val sorted = remember(jobs) {
        jobs.sortedBy {
            when (it.status) {
                JobStatus.ACTIVE -> 0
                JobStatus.QUEUED -> 1
                JobStatus.FAILED, JobStatus.EXPIRED -> 2
                JobStatus.SKIPPED -> 3
            }
        }
    }
    val active = jobs.count { it.status == JobStatus.ACTIVE }
    val queued = jobs.count { it.status == JobStatus.QUEUED }
    val failed = jobs.count { it.status == JobStatus.FAILED || it.status == JobStatus.EXPIRED }
    val speed = jobs.filter { it.status == JobStatus.ACTIVE }.sumOf { it.speed }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("التحميلات", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                    SubText(fmtSpeed(speed).ifEmpty { "—" })
                }
                Pill(connText(conn), if (conn == "open") GreenOk else AmberWarn)
            }
        }
        item {
            AppCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Stat("جاري", active.toString(), Modifier.weight(1f))
                    Stat("منتظر", queued.toString(), Modifier.weight(1f))
                    Stat("فاشل", failed.toString(), Modifier.weight(1f))
                    Stat("مكتمل", history.count { !it.merged }.toString(), Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { Repo.setUserPause(!paused) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) { Text(if (paused) "▶  استئناف التحميل" else "⏸  إيقاف مؤقت (بعد الملف الحالي)") }
            }
        }

        if (sorted.isEmpty() && history.isEmpty()) {
            item {
                AppCard {
                    Text("لا توجد ملفات بعد", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(4.dp))
                    SubText("أي ملف يصل إلى الشات يبدأ تحميله هنا تلقائياً.")
                }
            }
        }

        items(sorted, key = { it.id }) { JobCard(it) }

        if (history.isNotEmpty()) {
            item { SectionTitle("المكتملة") }
            items(history.take(60), key = { it.id }) { RecCard(it) }
        }
    }
}

@Composable
fun JobCard(j: Job) {
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                j.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface
            )
            when (j.status) {
                JobStatus.ACTIVE -> Pill("جاري", MaterialTheme.colorScheme.primary)
                JobStatus.QUEUED -> Pill("منتظر", AmberWarn)
                JobStatus.FAILED -> Pill("فشل", RedErr)
                JobStatus.EXPIRED -> Pill("منتهي", RedErr)
                JobStatus.SKIPPED -> Pill("متخطّى", MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(8.dp))

        if (j.status == JobStatus.ACTIVE) {
            val p = if (j.size > 0) (j.done.toFloat() / j.size).coerceIn(0f, 1f) else 0f
            if (j.size > 0) {
                LinearProgressIndicator(
                    progress = { p },
                    modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(8.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(8.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
            Spacer(Modifier.height(8.dp))
            val eta = if (j.speed > 0 && j.size > j.done) "  •  " + fmtEta((j.size - j.done) / j.speed) else ""
            val pct = if (j.size > 0) "${(p * 100).toInt()}%  •  " else ""
            SubText("$pct${fmtSize(j.done)} / ${if (j.size > 0) fmtSize(j.size) else "؟"}  •  ${fmtSpeed(j.speed)}$eta")
            if (j.attempt > 1) SubText("المحاولة ${j.attempt}", AmberWarn)
            if (j.error.isNotEmpty()) SubText(j.error, AmberWarn)
        } else {
            SubText((if (j.size > 0) fmtSize(j.size) else "حجم غير معروف") + (if (j.from.isNotEmpty()) "  •  ${j.from}" else ""))
            if (j.error.isNotEmpty()) SubText(j.error, RedErr)
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            when (j.status) {
                JobStatus.ACTIVE, JobStatus.QUEUED ->
                    TextButton(onClick = { Repo.cancelJob(j.id) }) { Text("إلغاء", color = RedErr) }
                JobStatus.FAILED, JobStatus.EXPIRED -> {
                    if (j.status == JobStatus.FAILED && !j.error.startsWith("تعذّر الحفظ"))
                        TextButton(onClick = { Repo.retryJob(j.id) }) { Text("إعادة المحاولة") }
                    TextButton(onClick = { Repo.removeJob(j.id) }) { Text("إزالة") }
                }
                JobStatus.SKIPPED -> TextButton(onClick = { Repo.removeJob(j.id) }) { Text("إزالة") }
            }
        }
    }
}

@Composable
fun RecCard(r: Rec) {
    val ctx = LocalContext.current
    AppCard(Modifier.clickable { openRec(ctx, r) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(r.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface)
                SubText("${fmtSize(r.size)}  •  ${ago(r.at)}")
            }
            if (r.merged) Pill("مدموج", MaterialTheme.colorScheme.primary)
            else if (r.used) Pill("دُمج", MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { Repo.deleteRec(r) }) { Text("حذف", color = RedErr) }
        }
    }
}

fun openRec(ctx: Context, r: Rec) {
    try {
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse(r.uri), Storage.mimeFor(r.name))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    } catch (_: Exception) {
        Toast.makeText(ctx, "لا يوجد تطبيق لفتح هذا الملف", Toast.LENGTH_SHORT).show()
    }
}
