package com.example.wadl

enum class JobStatus { QUEUED, ACTIVE, FAILED, EXPIRED, SKIPPED }

data class Job(
    val id: String,
    val name: String,
    val size: Long,
    val from: String,
    val status: JobStatus,
    val done: Long = 0L,
    val speed: Long = 0L,
    val attempt: Int = 0,
    val error: String = ""
)

/** ملف مكتمل محفوظ في التنزيلات */
data class Rec(
    val id: String,
    val name: String,
    val size: Long,
    val uri: String,
    val at: Long,
    val merged: Boolean = false,   // ناتج دمج
    val used: Boolean = false      // جزء استُخدم في دمج
)
