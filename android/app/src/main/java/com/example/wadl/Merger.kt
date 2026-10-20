package com.example.wadl

import android.content.Context
import android.net.Uri
import java.io.IOException

data class Part(val rec: Rec, val num: Int)

data class Group(
    val key: String,
    val mergedName: String,
    val parts: List<Part>,
    val missing: List<Int>,
    val total: Long,
    val lastAt: Long
) {
    val complete: Boolean get() = parts.size >= 2 && missing.isEmpty()
    val firstNum: Int get() = parts.first().num
    val lastNum: Int get() = parts.last().num
}

object Merger {
    private val dotNum = Regex("^(.+)\\.(\\d{2,4})$")
    private val partRe = Regex("^(.*?)[\\s._-]*(?:part|pt)?[\\s._-]*(\\d+)$", RegexOption.IGNORE_CASE)

    /** (مفتاح المجموعة، رقم الجزء، اسم الملف المدموج) */
    fun parse(name: String): Triple<String, Int, String>? {
        val m1 = dotNum.matchEntire(name)
        if (m1 != null) {
            val b = m1.groupValues[1]
            val n = m1.groupValues[2].toIntOrNull() ?: return null
            val dot = b.lastIndexOf('.')
            val merged = if (dot > 0) b.substring(0, dot) + "-merged" + b.substring(dot) else "$b-merged"
            return Triple(b.lowercase(), n, merged)
        }
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        val m = partRe.matchEntire(stem) ?: return null
        val base = m.groupValues[1].trimEnd(' ', '.', '_', '-')
        if (base.isEmpty()) return null
        val n = m.groupValues[2].toIntOrNull() ?: return null
        return Triple((base + ext).lowercase(), n, "$base-merged$ext")
    }

    fun groups(history: List<Rec>): List<Group> {
        val map = LinkedHashMap<String, MutableList<Triple<Rec, Int, String>>>()
        for (r in history) {
            if (r.merged || r.used) continue
            val p = parse(r.name) ?: continue
            map.getOrPut(p.first) { ArrayList() }.add(Triple(r, p.second, p.third))
        }
        val out = ArrayList<Group>()
        for ((mapKey, list) in map) {
            // لو تكرر نفس الرقم نأخذ الأحدث
            val byNum = HashMap<Int, Triple<Rec, Int, String>>()
            for (t in list) {
                val old = byNum[t.second]
                if (old == null || t.first.at > old.first.at) byNum[t.second] = t
            }
            if (byNum.size < 2) continue
            val nums = byNum.keys.sorted()
            val missing = (nums.first()..nums.last()).filter { it !in byNum }
            val parts = nums.map { Part(byNum.getValue(it).first, it) }
            out.add(
                Group(
                    key = mapKey,
                    mergedName = byNum.getValue(nums.first()).third,
                    parts = parts,
                    missing = missing,
                    total = parts.sumOf { it.rec.size },
                    lastAt = parts.maxOf { it.rec.at }
                )
            )
        }
        return out.sortedByDescending { it.lastAt }
    }

    /** يوصل الأجزاء بالترتيب في ملف واحد، مع التحقق من الأحجام */
    fun merge(ctx: Context, g: Group, onProgress: (Long, Long) -> Unit): Rec {
        if (!g.complete) throw IOException("ينقص الجزء #${g.missing.firstOrNull() ?: "?"}")
        Storage.ensureFree(ctx, g.total)

        val sink = Storage.openOut(ctx, g.mergedName, Storage.mimeFor(g.mergedName), Prefs.folder.value)
        var written = 0L
        try {
            val buf = ByteArray(256 * 1024)
            for (p in g.parts) {
                var copied = 0L
                val input = ctx.contentResolver.openInputStream(Uri.parse(p.rec.uri))
                    ?: throw IOException("تعذّر فتح ${p.rec.name}")
                input.use { inp ->
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        sink.stream.write(buf, 0, n)
                        copied += n
                        written += n
                        onProgress(written, g.total)
                    }
                }
                if (p.rec.size > 0 && copied != p.rec.size) {
                    throw IOException("حجم ${p.rec.name} غير مطابق ($copied من ${p.rec.size})")
                }
            }
            if (g.total > 0 && written != g.total) throw IOException("الحجم النهائي غير مطابق")
        } catch (e: Exception) {
            sink.abort()
            throw e
        }
        val (uri, finalName) = sink.finish()
        return Rec(
            id = "m" + System.currentTimeMillis().toString(36),
            name = finalName,
            size = written,
            uri = uri,
            at = System.currentTimeMillis(),
            merged = true
        )
    }
}
