package com.example.wadl.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.wadl.Group
import com.example.wadl.Merger
import com.example.wadl.Repo

@Composable
fun MergeTab() {
    val history by Repo.history.collectAsState()
    val progress by Repo.mergeProgress.collectAsState()
    val errors by Repo.mergeErrors.collectAsState()
    val groups = remember(history) { Merger.groups(history) }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("دمج الأجزاء", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            SubText("الملفات المتشابهة مثل file-part1 و file-part2 تُجمَّع هنا وتُوصَل بالترتيب الرقمي.")
        }
        if (groups.isEmpty()) {
            item {
                AppCard {
                    Text("لا توجد مجموعات", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(4.dp))
                    SubText("تظهر المجموعة عندما يصل جزءان على الأقل بنفس الاسم الأساسي.")
                }
            }
        }
        items(groups, key = { it.key }) { g -> GroupCard(g, progress[g.key], errors[g.key]) }
    }
}

@Composable
fun GroupCard(g: Group, prog: Pair<Long, Long>?, error: String?) {
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                g.mergedName, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface
            )
            when {
                prog != null -> Pill("جارٍ الدمج", MaterialTheme.colorScheme.primary)
                g.missing.isNotEmpty() -> Pill("ناقص #${g.missing.first()}", AmberWarn)
                else -> Pill("جاهز", GreenOk)
            }
        }
        Spacer(Modifier.height(6.dp))
        SubText("${g.parts.size} أجزاء  •  #${g.firstNum} إلى #${g.lastNum}  •  ${fmtSize(g.total)}")
        if (g.firstNum > 1) SubText("تنبيه: يبدأ من الجزء #${g.firstNum} وليس 1", AmberWarn)
        if (g.missing.isNotEmpty()) {
            SubText("الأجزاء الناقصة: " + g.missing.take(8).joinToString("، ") { "#$it" } +
                if (g.missing.size > 8) " …" else "", AmberWarn)
        }
        if (error != null) {
            Spacer(Modifier.height(4.dp))
            Text(error, color = RedErr, fontSize = 12.sp)
        }

        if (prog != null) {
            Spacer(Modifier.height(10.dp))
            val p = if (prog.second > 0) (prog.first.toFloat() / prog.second).coerceIn(0f, 1f) else 0f
            LinearProgressIndicator(
                progress = { p },
                modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(8.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            SubText("${(p * 100).toInt()}%  •  ${fmtSize(prog.first)} / ${fmtSize(prog.second)}")
        } else {
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { Repo.mergeGroup(g) },
                enabled = g.complete,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) { Text("🧩  دمج الآن", fontWeight = FontWeight.Bold) }
        }
    }
}
