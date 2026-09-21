package com.wjx.autofill.qr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer

/**
 * 二维码解码：相机实时帧与相册图片共用同一个 zxing 解码器。
 *
 * 无 GMS、无 native 代码，纯 Java 依赖（com.google.zxing:core）。
 */
object QrDecoder {

    /** 相册大图采样上限：解码不需要原图，1600px 足够且省内存（本机内存紧张）。 */
    const val MAX_SAMPLE_DIMENSION = 1600

    private fun hints(): Map<DecodeHintType, Any> = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE, BarcodeFormat.DATA_MATRIX),
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.CHARACTER_SET to "UTF-8",
    )

    /** 从 YUV_420_888 的 Y 平面解码（CameraX ImageAnalysis 路径）。 */
    fun decodeImageProxy(image: ImageProxy): String? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        buffer.rewind()
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) return null
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val luminance = ByteArray(width * height)
        if (pixelStride == 1 && rowStride == width) {
            val available = minOf(buffer.remaining(), luminance.size)
            buffer.get(luminance, 0, available)
        } else {
            val row = ByteArray(rowStride)
            var offset = 0
            for (y in 0 until height) {
                val remaining = buffer.remaining()
                if (remaining <= 0) break
                val length = minOf(rowStride, remaining)
                buffer.get(row, 0, length)
                var x = 0
                while (x < width && offset < luminance.size) {
                    val index = x * pixelStride
                    if (index >= length) break
                    luminance[offset++] = row[index]
                    x++
                }
            }
        }
        return decodeLuminance(luminance, width, height)
    }

    /** 从灰度数组解码。 */
    fun decodeLuminance(luminance: ByteArray, width: Int, height: Int): String? {
        if (width <= 0 || height <= 0 || luminance.size < width * height) return null
        val source = PlanarYUVLuminanceSource(
            luminance, width, height, 0, 0, width, height, false,
        )
        return tryDecode(BinaryBitmap(HybridBinarizer(source)))
            ?: tryDecode(BinaryBitmap(GlobalHistogramBinarizer(source)))
    }

    /** 从 Bitmap 解码（相册路径）。 */
    fun decodeBitmap(bitmap: Bitmap): String? {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return null
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val source = RGBLuminanceSource(width, height, pixels)
        return tryDecode(BinaryBitmap(HybridBinarizer(source)))
            ?: tryDecode(BinaryBitmap(GlobalHistogramBinarizer(source)))
    }

    /**
     * 相册图片解码：先读尺寸算采样率，再采样解码，避免整张大图进内存。
     * 不做 EXIF 旋转 —— 二维码检测本身与方向无关。
     */
    fun decodeImageUri(context: Context, uri: Uri): String? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        } catch (_: Throwable) {
            return null
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(
                bounds.outWidth, bounds.outHeight, MAX_SAMPLE_DIMENSION,
            )
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = try {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        } catch (_: Throwable) {
            null
        } ?: return null
        return try {
            decodeBitmap(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    /** 纯计算：返回 2 的幂采样率，使长边不超过 maxDimension。可单元测试。 */
    fun calculateInSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        if (width <= 0 || height <= 0 || maxDimension <= 0) return 1
        var sampleSize = 1
        while (maxOf(width, height) / sampleSize > maxDimension) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun tryDecode(bitmap: BinaryBitmap): String? {
        return try {
            val reader = MultiFormatReader().apply { setHints(hints()) }
            val result = reader.decodeWithState(bitmap)
            result.text?.takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            // NotFoundException / FormatException / ChecksumException 都属正常未命中。
            null
        }
    }
}
