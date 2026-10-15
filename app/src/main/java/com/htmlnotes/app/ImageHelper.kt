package com.htmlnotes.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * 手机相册图片读取与优化助手：
 * - 针对移动端大图做安全采样解码，杜绝 OOM 内存溢出
 * - 自动检测并校正相机的 EXIF 旋转角度（竖拍不倒置）
 * - 按比例精确等比缩放至适配移动端视口（默认最大边 1440px）
 * - 高质量压缩输出为 self-contained 的 JPEG Base64 Data URI，离线、跨平台无缝兼容
 */
object ImageHelper {

    fun processPickedImage(
        context: Context,
        uri: Uri,
        maxDimension: Int = 1080,
        quality: Int = 75
    ): String? {
        val cr = context.contentResolver
        return runCatching {
            // 1. 获取图片原始宽高（仅读取元数据，不分配像素内存）
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cr.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, boundsOptions)
            } ?: return null

            val origW = boundsOptions.outWidth
            val origH = boundsOptions.outHeight
            if (origW <= 0 || origH <= 0) return null

            // 2. 计算安全采样比例 inSampleSize
            var inSampleSize = 1
            while (origW / inSampleSize > maxDimension * 2 || origH / inSampleSize > maxDimension * 2) {
                inSampleSize *= 2
            }

            // 3. 读取采样后的位图
            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val sampled = cr.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            } ?: return null

            // 4. 读取 EXIF 旋转属性
            val orientation = runCatching {
                cr.openInputStream(uri)?.use { stream ->
                    ExifInterface(stream).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                }
            }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

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
            val bytes = baos.toByteArray()
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            "data:image/jpeg;base64,$base64"
        }.getOrNull()
    }
}
