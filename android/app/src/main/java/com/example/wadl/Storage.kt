package com.example.wadl

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/** الكتابة في Download/<اسم المجلد> عبر MediaStore (بدون صلاحية الوصول لكل الملفات) */
object Storage {

    class Sink(
        val stream: OutputStream,
        val finish: () -> Pair<String, String>,   // (uri, الاسم النهائي)
        val abort: () -> Unit
    )

    fun mimeFor(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    private fun legacyDir(folder: String): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), folder)
            .also { it.mkdirs() }

    private fun unique(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val ext = name.substringAfterLast('.', "")
        val base = if (ext.isEmpty()) name else name.dropLast(ext.length + 1)
        var i = 1
        while (f.exists()) {
            f = File(dir, if (ext.isEmpty()) "$base ($i)" else "$base ($i).$ext")
            i++
        }
        return f
    }

    private fun displayName(ctx: Context, uri: Uri): String? =
        ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }

    fun openOut(ctx: Context, name: String, mime: String, folder: String): Sink {
        val cr = ctx.contentResolver
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$folder")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
                ?: throw IOException("تعذّر إنشاء الملف في التنزيلات")
            val raw = cr.openOutputStream(uri) ?: throw IOException("تعذّر فتح الملف للكتابة")
            val out = BufferedOutputStream(raw, 256 * 1024)
            return Sink(
                out,
                finish = {
                    out.flush(); out.close()
                    val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                    cr.update(uri, done, null, null)
                    Pair(uri.toString(), displayName(ctx, uri) ?: name)
                },
                abort = {
                    try { out.close() } catch (_: Exception) {}
                    try { cr.delete(uri, null, null) } catch (_: Exception) {}
                }
            )
        } else {
            val f = unique(legacyDir(folder), name)
            val out = BufferedOutputStream(FileOutputStream(f), 256 * 1024)
            return Sink(
                out,
                finish = {
                    out.flush(); out.close()
                    MediaScannerConnection.scanFile(ctx, arrayOf(f.path), null, null)
                    Pair(Uri.fromFile(f).toString(), f.name)
                },
                abort = {
                    try { out.close() } catch (_: Exception) {}
                    f.delete()
                }
            )
        }
    }

    /** ينقل الملف المؤقت إلى مجلد التنزيلات ويحذف المؤقت */
    fun save(ctx: Context, src: File, name: String, nodeMime: String, folder: String): Pair<String, String> {
        val byExt = mimeFor(name)
        val mime = if (byExt != "application/octet-stream") byExt
        else nodeMime.ifEmpty { "application/octet-stream" }
        val sink = openOut(ctx, name, mime, folder)
        try {
            src.inputStream().use { it.copyTo(sink.stream, 256 * 1024) }
        } catch (e: Exception) {
            sink.abort(); throw e
        }
        val r = sink.finish()
        src.delete()
        return r
    }

    fun delete(ctx: Context, uriStr: String): Boolean {
        val u = Uri.parse(uriStr)
        return try {
            if (u.scheme == "file") File(u.path ?: return false).delete()
            else ctx.contentResolver.delete(u, null, null) > 0
        } catch (_: Exception) {
            false
        }
    }

    fun ensureFree(ctx: Context, needed: Long) {
        val path = ctx.getExternalFilesDir(null)?.path ?: ctx.filesDir.path
        val free = StatFs(path).availableBytes
        if (free < needed + 50L * 1048576) throw IOException("مساحة غير كافية للدمج")
    }
}
