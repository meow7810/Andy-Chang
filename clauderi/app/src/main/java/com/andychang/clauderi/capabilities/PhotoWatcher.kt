package com.andychang.clauderi.capabilities

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import java.io.File

/**
 * The wide door: every new picture in the phone's gallery is shown to the assistant, which may
 * say something or nothing. Registered once per process; only acts while [enabled] is on and
 * the images permission is granted. Pictures the assistant asked for itself (take_photo) live
 * in the app cache and never pass through here.
 */
object PhotoWatcher {
    @Volatile var enabled = false
    @Volatile var onPhoto: ((ByteArray, String) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private var resolver: ContentResolver? = null
    private var appContext: Context? = null
    private var lastId = -1L
    private var startedAt = 0L
    private var pending: Runnable? = null

    private val observer = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) {
            // Camera apps touch the row several times while writing; wait for the last change.
            pending?.let { main.removeCallbacks(it) }
            val r = Runnable { scan() }
            pending = r
            main.postDelayed(r, 1500)
        }
    }

    fun permission(): String = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

    fun granted(context: Context) = ContextCompat.checkSelfPermission(context, permission()) == PackageManager.PERMISSION_GRANTED

    fun start(context: Context) {
        if (resolver != null) return
        val ctx = context.applicationContext
        appContext = ctx
        resolver = ctx.contentResolver
        startedAt = System.currentTimeMillis() / 1000
        lastId = newestId() ?: -1L
        ctx.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
    }

    private fun newestId(): Long? {
        val cr = resolver ?: return null
        val ctx = appContext ?: return null
        if (!granted(ctx)) return null
        cr.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID), null, null, "${MediaStore.Images.Media._ID} DESC LIMIT 1")
            ?.use { c -> if (c.moveToFirst()) return c.getLong(0) }
        return null
    }

    private fun scan() {
        val ctx = appContext ?: return
        if (!enabled || !granted(ctx)) return
        val cr = resolver ?: return
        val cb = onPhoto ?: return
        val proj = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.DATA)
        cr.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, "${MediaStore.Images.Media._ID} > ?", arrayOf(lastId.toString()), "${MediaStore.Images.Media._ID} ASC")
            ?.use { c ->
                var newest: Long = lastId
                var picked: Pair<Long, String?>? = null
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val added = c.getLong(1)
                    newest = maxOf(newest, id)
                    // Only pictures taken after we started watching, not a backlog synced in from elsewhere.
                    if (added >= startedAt - 60) picked = id to c.getString(2)
                }
                lastId = newest
                val (id, path) = picked ?: return
                Thread {
                    val jpeg = runCatching {
                        val f = path?.let { File(it) }
                        if (f != null && f.exists()) CameraCapability.downscale(f)
                        else {
                            // No file path (scoped storage): copy the stream to cache first.
                            val tmp = File.createTempFile("gallery_", ".jpg", ctx.cacheDir)
                            cr.openInputStream(android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id))!!.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                            CameraCapability.downscale(tmp).also { tmp.delete() }
                        }
                    }.getOrNull() ?: return@Thread
                    cb(jpeg, "相簿新照片")
                }.start()
            }
    }
}
