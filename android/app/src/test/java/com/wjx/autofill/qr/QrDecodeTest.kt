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
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.util.zip.GZIPInputStream

/**
 * 二维码解码回归测试（A7）。
 *
 * 夹具：
 *   - testdata/qr-sample.jpg        用户提供的问卷星测试二维码截图（1200x2000 JPEG，原始证据）
 *   - testdata/qr-sample.lum.gz     由 scripts/gen-qr-fixture.sh 从上面那张 JPEG 逐像素转出的
 *                                   亮度矩阵（格式：'WJX1' + int32 宽 + int32 高 + w*h 灰度字节，gzip）
 *
 * 为什么要转亮度矩阵：Android 本地单元测试的编译类路径是 android.jar，
 * **没有 java.awt / javax.imageio**，所以单测里不能直接 ImageIO 解 JPEG。
 * 转成亮度矩阵后，单测仍然是在真实截图的像素上跑生产解码器 QrDecoder.decodeLuminance，
 * 而 JPEG→二维码文本 的那一段由 scripts/gen-qr-fixture.sh 在纯 JVM 里断言（build-apk.sh 会调用）。
 */
class QrDecodeTest {

    private val expectedUrl = "https://www.wjx.cn/vm/Q0DQewW.aspx"

    private class Luminance(val width: Int, val height: Int, val data: ByteArray)

    /** 夹具路径：从 user.dir 向上找 testdata/，可用 -Dwjx.qr.fixtureDir=... 覆盖。 */
    private fun fixture(name: String): File {
        val overrideDir = System.getProperty("wjx.qr.fixtureDir")
        if (overrideDir != null && overrideDir.isNotBlank()) {
            val direct = File(overrideDir, name)
            if (direct.isFile) return direct
        }
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "testdata/" + name)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        throw AssertionError(
            "找不到夹具 testdata/" + name + "（user.dir=" + System.getProperty("user.dir") + "）",
        )
    }

    private fun loadLuminance(): Luminance {
        val file = fixture("qr-sample.lum.gz")
        DataInputStream(BufferedInputStream(GZIPInputStream(FileInputStream(file)))).use { input ->
            val magic = ByteArray(4)
            input.readFully(magic)
            assertEquals("夹具魔数应为 WJX1", "WJX1", String(magic, Charsets.US_ASCII))
            val width = input.readInt()
            val height = input.readInt()
            assertTrue("夹具尺寸异常：" + width + "x" + height, width > 100 && height > 100)
            val data = ByteArray(width * height)
            input.readFully(data)
            return Luminance(width, height, data)
        }
    }

    /** 灰度矩阵 → ARGB 像素，走 zxing 原生 RGBLuminanceSource（与相册解码同一条链路）。 */
    private fun decodeWithZxing(luminance: Luminance): String? {
        val pixels = IntArray(luminance.data.size)
        for (i in luminance.data.indices) {
            val gray = luminance.data[i].toInt() and 0xFF
            pixels[i] = (0xFF shl 24) or (gray shl 16) or (gray shl 8) or gray
        }
        val hints = mapOf(
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        )
        val bitmap = BinaryBitmap(
            HybridBinarizer(RGBLuminanceSource(luminance.width, luminance.height, pixels)),
        )
        return MultiFormatReader().apply { setHints(hints) }.decode(bitmap).text
    }

    @Test
    fun jpegOriginalFixtureIsIntact() {
        val jpeg = fixture("qr-sample.jpg")
        val head = ByteArray(2)
        FileInputStream(jpeg).use { it.read(head) }
        assertEquals("夹具应是 JPEG（FF D8 开头）", 0xFF, head[0].toInt() and 0xFF)
        assertEquals(0xD8, head[1].toInt() and 0xFF)
        assertTrue("JPEG 夹具太小：" + jpeg.length(), jpeg.length() > 10_000)
    }

    @Test
    fun fixtureDecodesToSampleSurveyUrl() {
        val luminance = loadLuminance()
        assertEquals(expectedUrl, decodeWithZxing(luminance))
    }

    @Test
    fun productionDecoderDecodesSameFixture() {
        val luminance = loadLuminance()
        assertEquals(
            expectedUrl,
            QrDecoder.decodeLuminance(luminance.data, luminance.width, luminance.height),
        )
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
