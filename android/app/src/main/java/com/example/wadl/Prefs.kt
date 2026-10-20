package com.example.wadl

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow

object Prefs {
    const val DEFAULT_FOLDER = "my downloading file"
    private lateinit var sp: SharedPreferences

    val folder = MutableStateFlow(DEFAULT_FOLDER)
    val target = MutableStateFlow("")
    val gapMin = MutableStateFlow(3)
    val gapMax = MutableStateFlow(10)
    val maxSizeMb = MutableStateFlow(0)
    val exts = MutableStateFlow("")
    val wifiOnly = MutableStateFlow(false)
    val mergeMode = MutableStateFlow(0)          // 0 يدوي، 1 عند عدد، 2 عند توقف الوصول
    val expectedCount = MutableStateFlow(3)
    val idleSec = MutableStateFlow(60)
    val deleteAfterMerge = MutableStateFlow(false)
    val theme = MutableStateFlow(0)
    val accent = MutableStateFlow(0)

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("wadl", Context.MODE_PRIVATE)
        folder.value = sp.getString("folder", DEFAULT_FOLDER) ?: DEFAULT_FOLDER
        target.value = sp.getString("target", "") ?: ""
        gapMin.value = sp.getInt("gapMin", 3)
        gapMax.value = sp.getInt("gapMax", 10)
        maxSizeMb.value = sp.getInt("maxSizeMb", 0)
        exts.value = sp.getString("exts", "") ?: ""
        wifiOnly.value = sp.getBoolean("wifiOnly", false)
        mergeMode.value = sp.getInt("mergeMode", 0)
        expectedCount.value = sp.getInt("expectedCount", 3)
        idleSec.value = sp.getInt("idleSec", 60)
        deleteAfterMerge.value = sp.getBoolean("deleteAfterMerge", false)
        theme.value = sp.getInt("theme", 0)
        accent.value = sp.getInt("accent", 0)
    }

    fun <T : Any> set(flow: MutableStateFlow<T>, key: String, v: T) {
        flow.value = v
        val e = sp.edit()
        when (v) {
            is String -> e.putString(key, v)
            is Int -> e.putInt(key, v)
            is Boolean -> e.putBoolean(key, v)
        }
        e.apply()
        Repo.pushConfig()
    }

    fun cleanFolderName(raw: String): String {
        val n = raw.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().trim('.')
        return if (n.isEmpty()) DEFAULT_FOLDER else n.take(80)
    }
}
