package com.htmlnotes.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * 手机相册图片读取与优化助手：
 * - 单次读取 ContentResolver 数据流（杜绝 content:// 二次读取权限被吊销/流关闭问题）
 * - 针对移动端大图做安全采样解码，杜绝 OOM 内存溢出
 * - 自动检测并校正相机的 EXIF 旋转角度（竖拍不倒置）
 * - 按比例精确等比缩放至适配移动端视口（默认最大边 1080px）
 * - 高质量压缩输出为 self-contained 的 JPEG Base64 Data URI，离线、跨平台无缝兼容
 */
object ImageHelper {

    private const val TAG = "ImageHelper"

    fun processPickedImage(
        context: Context,
        uri: Uri,
        maxDimension: Int = 1080,
        quality: Int = 75
    ): String? {
        val cr = context.contentResolver
        return try {
            // 核心修复：只读取一次输入流，完整读入字节数组。
            // 解决 Android 11~15 上 content:// URI 第二次 openInputStream 会抛异常或返回 null 的致命 Bug！
            val bytes = cr.openInputStream(uri)?.use { stream ->
                stream.readBytes()
            }
            if (bytes == null || bytes.isEmpty()) {
                Log.e(TAG, "Failed to read bytes from uri: $uri")
                return null
            }

            // 1. 获取图片原始宽高（仅读取元数据，不分配像素内存）
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOptions)

            val origW = boundsOptions.outWidth
            val origH = boundsOptions.outHeight
            if (origW <= 0 || origH <= 0) {
                Log.e(TAG, "Invalid image bounds: ${origW}x${origH}")
                return null
            }

            // 2. 计算安全采样比例 inSampleSize
            var inSampleSize = 1
            while (origW / inSampleSize > maxDimension * 2 || origH / inSampleSize > maxDimension * 2) {
                inSampleSize *= 2
            }

            // 3. 从内存字节数组读取采样后的位图
            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val sampled = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
            if (sampled == null) {
                Log.e(TAG, "BitmapFactory failed to decode sampled bitmap")
                return null
            }

            // 4. 读取 EXIF 旋转属性
            val orientation = try {
                ByteArrayInputStream(bytes).use { stream ->
                    ExifInterface(stream).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse EXIF orientation: ${e.message}")
                ExifInterface.ORIENTATION_NORMAL
            }

            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            }

            // 5. 按比例精确缩放到目标最大尺寸
            val curW = sampled.width
            val curH = sampled.height
            val scale = minOf(1.0f, maxDimension.toFloat() / maxOf(curW, curH))
            if (scale < 1.0f) {
                matrix.postScale(scale, scale)
            }

            val finalBitmap = if (!matrix.isIdentity) {
                Bitmap.createBitmap(sampled, 0, 0, curW, curH, matrix, true).also {
                    if (it != sampled) sampled.recycle()
                }
            } else {
                sampled
            }

            // 6. 压缩为 JPEG 字节并转 Base64 Data URI
            val baos = ByteArrayOutputStream()
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)
            finalBitmap.recycle()
            val outBytes = baos.toByteArray()
            val base64 = Base64.encodeToString(outBytes, Base64.NO_WRAP)
            "data:image/jpeg;base64,$base64"
        } catch (e: Throwable) {
            Log.e(TAG, "processPickedImage exception: ${e.message}", e)
            null
        }
    }

    /**
     * 将 Data URI (Base64) 或本地文件路径解码为 Bitmap，供缩略图与大图全屏查看器使用。
     */
    fun decodeDataUriOrUrl(src: String): Bitmap? {
        return try {
            if (src.startsWith("data:image/")) {
                val base64Index = src.indexOf("base64,")
                if (base64Index != -1) {
                    val base64Str = src.substring(base64Index + 7)
                    val bytes = Base64.decode(base64Str, Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } else null
            } else if (src.startsWith("file://") || src.startsWith("/")) {
                val path = src.removePrefix("file://")
                BitmapFactory.decodeFile(path)
            } else null
        } catch (e: Throwable) {
            Log.e(TAG, "decodeDataUriOrUrl failed: ${e.message}", e)
            null
        }
    }
}

