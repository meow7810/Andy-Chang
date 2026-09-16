package com.andychang.clauderi.capabilities

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.andychang.clauderi.data.AppSettings
import com.andychang.clauderi.data.CapabilityId
import com.andychang.clauderi.llm.ToolCall
import com.andychang.clauderi.llm.ToolParam
import com.andychang.clauderi.llm.ToolResult
import com.andychang.clauderi.llm.ToolSpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File

/** A pending "please open the camera" request; the chat screen fulfils it and completes [done]. */
data class CameraRequest(val target: Uri, val hint: String, internal val done: CompletableDeferred<Boolean> = CompletableDeferred())

/**
 * Camera as a capability. take_photo suspends the tool loop, the UI opens the system camera
 * (ACTION_IMAGE_CAPTURE, so no CAMERA permission is needed), the JPEG comes back downscaled
 * inside the tool result and the model answers from what it sees. share_last_photo hands that
 * file to any messaging app via the system share sheet: the user picks the app and the contact.
 */
class CameraCapability(private val context: Context) : Capability {

    override val id = CapabilityId.CAMERA

    private val _pending = MutableStateFlow<CameraRequest?>(null)
    val pending: StateFlow<CameraRequest?> = _pending.asStateFlow()

    private val dir = File(context.cacheDir, "photos").apply { mkdirs() }
    @Volatile private var lastPhoto: File? = null

    override fun promptSection(settings: AppSettings) =
        "你可以用 take_photo 開相機拍一張照片來看（使用者按下快門後你會收到照片），再用 share_last_photo 把剛拍的照片連同一句話分享出去。" +
            "描述照片時講重點，使用者問「這是什麼」就直接回答是什麼。"

    override fun tools(settings: AppSettings) = listOf(
        ToolSpec(
            "take_photo", "開啟相機讓使用者拍一張照片，拍完照片會回傳給你看。",
            listOf(ToolParam("hint", "string", "給使用者的一句提示，例如「請對準商品標籤」", required = false)),
        ),
        ToolSpec(
            "share_last_photo", "把剛拍的照片和一段文字透過系統分享面板送到使用者選的 App（LINE、訊息等），由使用者選收件人並按送出。",
            listOf(ToolParam("message", "string", "附帶的文字", required = false)),
        ),
    )

    override suspend fun execute(call: ToolCall, settings: AppSettings): ToolResult? = when (call.name) {
        "take_photo" -> takePhoto(call.str("hint").orEmpty())
        "share_last_photo" -> shareLast(call.str("message").orEmpty())
        else -> null
    }

    private suspend fun takePhoto(hint: String): ToolResult {
        val file = File(dir, "photo_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val req = CameraRequest(uri, hint)
        _pending.value = req
        val ok = try {
            withTimeoutOrNull(180_000) { req.done.await() } ?: false
        } finally {
            if (_pending.value === req) _pending.value = null
        }
        if (!ok || !file.exists() || file.length() == 0L) return err("使用者沒有拍照（取消或超時）。")
        lastPhoto = file
        val jpeg = withContext(Dispatchers.IO) { downscale(file) }
        return ToolResult("照片已拍攝（${jpeg.size / 1024} KB）。", imageJpeg = jpeg)
    }

    /** Called by the UI when the camera returns. */
    fun onPhotoResult(success: Boolean) { _pending.value?.done?.complete(success) }

    private fun shareLast(message: String): ToolResult {
        val file = lastPhoto?.takeIf { it.exists() } ?: return err("目前沒有剛拍的照片，先用 take_photo。")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            if (message.isNotBlank()) putExtra(Intent.EXTRA_TEXT, message)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "分享照片").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(chooser)
            ok("已開啟分享面板，等使用者選 App 和收件人。" + if (message.isNotBlank()) "附帶文字：$message" else "")
        } catch (e: Exception) {
            err("無法開啟分享面板：${e.message}")
        }
    }

    /** Longest side <= 1280 px, JPEG q80, EXIF rotation applied. Keeps token cost low. */
    private fun downscale(file: File): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1280 * 2) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return file.readBytes()
        val scale = 1280f / maxOf(bmp.width, bmp.height)
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        val rotation = when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        val upright = if (rotation != 0f) Bitmap.createBitmap(scaled, 0, 0, scaled.width, scaled.height, Matrix().apply { postRotate(rotation) }, true) else scaled
        val out = ByteArrayOutputStream()
        upright.compress(Bitmap.CompressFormat.JPEG, 80, out)
        return out.toByteArray()
    }

    override fun status() = "使用系統相機 App，不需要相機權限；照片只存在 App 快取，分享時才離開手機"
}
