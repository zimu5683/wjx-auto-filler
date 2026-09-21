package com.wjx.autofill.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * 二维码解码回归测试。
 *
 * 夹具 testdata/qr-sample.jpg 是用户提供的问卷星测试二维码截图（1200x2000 JPEG）。
 * 断言点：
 *   1. 夹具本身能被 zxing 解出 https://www.wjx.cn/vm/Q0DQewW.aspx（防止夹具被换错）
 *   2. 生产解码器 QrDecoder.decodeLuminance 对同一张图给出同样结果（相机/相册两条路径共用的核心）
 *   3. 纯计算函数 calculateInSampleSize 的采样率边界
 *
 * 注意：这里只用 javax.imageio 解 JPEG（本地单元测试跑在 JDK 上，java.desktop 可用），
 * 不引入 com.google.zxing:javase，也不引入任何 Android 依赖。
 */
class QrDecodeTest {

    private val expectedUrl = "https://www.wjx.cn/vm/Q0DQewW.aspx"

    /** 夹具路径：默认从 user.dir 向上找 testdata/qr-sample.jpg，可用 -Dwjx.qr.fixture=... 覆盖。 */
    private fun fixture(): File {
        val override = System.getProperty("wjx.qr.fixture")
        if (override != null && override.isNotBlank()) return File(override)
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "testdata/qr-sample.jpg")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        throw AssertionError(
            "找不到夹具 testdata/qr-sample.jpg（user.dir=" + System.getProperty("user.dir") + "）",
        )
    }

    private fun loadImage(): BufferedImage {
        System.setProperty("java.awt.headless", "true")
        val file = fixture()
        return ImageIO.read(file) ?: throw AssertionError("ImageIO 无法解码夹具：" + file.absolutePath)
    }

    private fun decodeWithZxing(image: BufferedImage): String? {
        val width = image.width
        val height = image.height
        val pixels = image.getRGB(0, 0, width, height, null, 0, width)
        val hints = mapOf(
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        )
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, height, pixels)))
        return MultiFormatReader().apply { setHints(hints) }.decode(bitmap).text
    }

    /** 与生产代码 decodeBitmap 相同的亮度换算：(r + 2g + b) / 4。 */
    private fun toLuminance(image: BufferedImage): ByteArray {
        val width = image.width
        val height = image.height
        val pixels = image.getRGB(0, 0, width, height, null, 0, width)
        val out = ByteArray(width * height)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            out[i] = ((r + 2 * g + b) / 4).toByte()
        }
        return out
    }

    @Test
    fun fixtureDecodesToSampleSurveyUrl() {
        val image = loadImage()
        assertTrue("夹具尺寸异常：" + image.width + "x" + image.height, image.width > 100 && image.height > 100)
        assertEquals(expectedUrl, decodeWithZxing(image))
    }

    @Test
    fun productionDecoderDecodesSameFixture() {
        val image = loadImage()
        val luminance = toLuminance(image)
        assertEquals(expectedUrl, QrDecoder.decodeLuminance(luminance, image.width, image.height))
    }

    @Test
    fun productionDecoderRejectsBlankInput() {
        assertNull(QrDecoder.decodeLuminance(ByteArray(64 * 64), 64, 64))
        assertNull(QrDecoder.decodeLuminance(ByteArray(0), 0, 0))
    }

    @Test
    fun sampleSizeKeepsLongEdgeWithinLimit() {
        // 夹具是 1200x2000：长边 2000 > 1600，采样 2 倍后 1000 <= 1600。
        assertEquals(2, QrDecoder.calculateInSampleSize(1200, 2000, 1600))
        assertEquals(2, QrDecoder.calculateInSampleSize(2400, 3200, 1600))
        assertEquals(4, QrDecoder.calculateInSampleSize(4000, 3000, 1600))
        assertEquals(1, QrDecoder.calculateInSampleSize(1600, 1600, 1600))
        assertEquals(1, QrDecoder.calculateInSampleSize(0, 100, 1600))
        assertEquals(1, QrDecoder.calculateInSampleSize(100, 100, 0))
    }
}
