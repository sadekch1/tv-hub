package com.example.wadl.tv

import java.io.BufferedReader
import java.io.StringReader
import java.security.MessageDigest

/** قناة واحدة من قائمة m3u */
data class TvChannel(
    val id: String,
    val name: String,
    val group: String,
    val logo: String,
    val url: String,
    val tvgId: String = ""
)

/** قارئ m3u/m3u8: يقرأ سطراً بسطر (قوائم Xtream قد تبلغ عشرات الميغابايت) */
object M3uParser {
    const val NO_GROUP = "غير مصنّف"
    private val attrRe = Regex("""([A-Za-z0-9_\-]+)="([^"]*)"""")
    private val urlRe = Regex("""^https?://\S+""", RegexOption.IGNORE_CASE)

    /** معرّف ثابت من الرابط (تبقى المفضلة صالحة بعد التحديث) */
    fun idFor(url: String): String {
        val md = MessageDigest.getInstance("MD5").digest(url.toByteArray(Charsets.UTF_8))
        return md.take(6).joinToString("") { "%02x".format(it) }
    }

    fun parse(text: String): List<TvChannel> = parse(BufferedReader(StringReader(text)))

    fun parse(reader: BufferedReader): List<TvChannel> {
        val out = ArrayList<TvChannel>()
        val seen = HashSet<String>()
        var have = false
        var name = ""
        var group = ""
        var logo = ""
        var tvgId = ""
        var extGrp = ""

        // #EXTGRP يستمر للقنوات التالية (كما في VLC) ولا يُصفَّر مع كل قناة
        fun reset() { have = false; name = ""; group = ""; logo = ""; tvgId = "" }

        reader.forEachLine { raw ->
            val line = raw.trim().trimStart('\uFEFF')
            if (line.isEmpty()) return@forEachLine
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    reset()
                    have = true
                    val attrs = HashMap<String, String>()
                    attrRe.findAll(line).forEach { attrs[it.groupValues[1].lowercase()] = it.groupValues[2] }
                    group = attrs["group-title"].orEmpty().trim()
                    logo = attrs["tvg-logo"].orEmpty().trim()
                    tvgId = attrs["tvg-id"].orEmpty().trim()
                    name = displayName(line).ifBlank { attrs["tvg-name"].orEmpty() }.trim()
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> extGrp = line.substringAfter(':').trim()
                line.startsWith("#") -> Unit
                have && urlRe.containsMatchIn(line) -> {
                    val u = line.trim()
                    if (seen.add(u)) {
                        out.add(
                            TvChannel(
                                id = idFor(u),
                                name = name.ifBlank { "قناة ${out.size + 1}" },
                                group = group.ifBlank { extGrp }.ifBlank { NO_GROUP },
                                logo = if (urlRe.containsMatchIn(logo)) logo else "",
                                url = u,
                                tvgId = tvgId
                            )
                        )
                    }
                    reset()
                }
                else -> Unit
            }
        }
        return out
    }

    /** الاسم = ما بعد آخر فاصلة خارج علامات الاقتباس (الفواصل داخل السمات لا تُحسب) */
    private fun displayName(extinf: String): String {
        var inQuote = false
        var last = -1
        for (i in extinf.indices) {
            val c = extinf[i]
            if (c == '"') inQuote = !inQuote
            else if (c == ',' && !inQuote) last = i
        }
        return if (last >= 0) extinf.substring(last + 1) else ""
    }

    fun groups(channels: List<TvChannel>): List<Pair<String, Int>> =
        channels.groupingBy { it.group }.eachCount().toList().sortedBy { it.first }
}
