#!/usr/bin/env bash
# =============================================================================
# scripts/gen-qr-fixture.sh — 二维码夹具工具
#
# 为什么需要它：
#   Android 本地单元测试的编译类路径是 android.jar（没有 java.awt / javax.imageio），
#   所以 Gradle 单测里**不能**用 ImageIO 解 JPEG。本脚本在纯 JVM（JDK 17）里把
#   testdata/qr-sample.jpg 转成亮度矩阵夹具 testdata/qr-sample.lum.gz，
#   单测再把这个矩阵喂给生产解码器 QrDecoder.decodeLuminance。
#
# 同时它本身就是 A7（二维码解码正确）的证据：JPEG → zxing → 断言等于问卷链接。
#
# 用法：
#   scripts/gen-qr-fixture.sh            # 重新生成夹具 + 断言
#   scripts/gen-qr-fixture.sh --check    # 只解码 JPEG 并断言，不写夹具（build-apk.sh 用）
# =============================================================================
set -o pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
JPG="$ROOT_DIR/testdata/qr-sample.jpg"
LUM="$ROOT_DIR/testdata/qr-sample.lum.gz"
EXPECTED_URL="https://www.wjx.cn/vm/Q0DQewW.aspx"
CHECK_ONLY=0
[ "$1" = "--check" ] && CHECK_ONLY=1

ZXING_JAR="$(find "$HOME/.gradle/caches/modules-2/files-2.1/com.google.zxing/core" \
    -name 'core-*.jar' 2>/dev/null | sort | tail -1)"
if [ -z "$ZXING_JAR" ]; then
    echo "FAIL: Gradle 缓存里找不到 com.google.zxing:core" >&2
    exit 1
fi
[ -f "$JPG" ] || { echo "FAIL: 夹具不存在 $JPG" >&2; exit 1; }

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR" 2>/dev/null' EXIT

cat > "$WORK_DIR/QrFixtureTool.java" <<'JAVA'
import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.zip.GZIPOutputStream;
import javax.imageio.ImageIO;

/** 读 JPEG → zxing 解码 → 断言 URL → 写亮度夹具（WJX1 + width + height + 灰度字节）。 */
public class QrFixtureTool {
    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        File jpg = new File(args[0]);
        String expected = args[1];
        boolean writeFixture = args.length > 2 && args[2] != null && !args[2].isEmpty();
        File out = writeFixture ? new File(args[2]) : null;

        BufferedImage image = ImageIO.read(jpg);
        if (image == null) {
            System.out.println("FAIL: ImageIO 无法解码 " + jpg);
            System.exit(2);
        }
        int width = image.getWidth();
        int height = image.getHeight();
        int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);

        DecodeHintType hintFormat = DecodeHintType.POSSIBLE_FORMATS;
        java.util.Map<DecodeHintType, Object> hints = new java.util.EnumMap<DecodeHintType, Object>(DecodeHintType.class);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        hints.put(hintFormat, java.util.Collections.singletonList(BarcodeFormat.QR_CODE));
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(width, height, pixels)));
        String decoded;
        try {
            MultiFormatReader reader = new MultiFormatReader();
            reader.setHints(hints);
            decoded = reader.decode(bitmap).getText();
        } catch (Exception e) {
            System.out.println("FAIL: zxing 解码失败 " + e);
            System.exit(3);
            return;
        }
        System.out.println("image=" + width + "x" + height);
        System.out.println("decoded=" + decoded);
        if (!expected.equals(decoded)) {
            System.out.println("FAIL: 期望 " + expected + " 实际 " + decoded);
            System.exit(4);
        }

        byte[] luminance = new byte[width * height];
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            int r = (p >> 16) & 0xFF;
            int g = (p >> 8) & 0xFF;
            int b = p & 0xFF;
            luminance[i] = (byte) ((r + 2 * g + b) / 4);
        }

        if (out != null) {
            try (DataOutputStream os = new DataOutputStream(new BufferedOutputStream(
                    new GZIPOutputStream(new FileOutputStream(out))))) {
                os.write('W'); os.write('J'); os.write('X'); os.write('1');
                os.writeInt(width);
                os.writeInt(height);
                os.write(luminance);
            }
            System.out.println("fixture=" + out.getAbsolutePath() + " bytes=" + out.length());
        }
        System.out.println("OK");
    }
}
JAVA

if ! javac -cp "$ZXING_JAR" -d "$WORK_DIR" "$WORK_DIR/QrFixtureTool.java" > "$WORK_DIR/javac.log" 2>&1; then
    echo "FAIL: javac 编译夹具工具失败" >&2
    tail -5 "$WORK_DIR/javac.log" >&2
    exit 1
fi

if [ "$CHECK_ONLY" = 1 ]; then
    java -cp "$ZXING_JAR:$WORK_DIR" QrFixtureTool "$JPG" "$EXPECTED_URL"
    exit $?
fi

java -cp "$ZXING_JAR:$WORK_DIR" QrFixtureTool "$JPG" "$EXPECTED_URL" "$LUM"
exit $?
