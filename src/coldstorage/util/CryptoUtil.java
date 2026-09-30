package coldstorage.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * NÂNG CẤP BẢO MẬT: tính chữ ký HMAC-SHA256 cho gói tin, thay vì so sánh
 * token dạng chuỗi thuần (plain text) như bản trước. HMAC không thể bị giả mạo
 * nếu không biết khóa bí mật (secret key) dùng chung giữa Sensor và Server,
 * và không lộ khóa bí mật ra trên đường truyền (khác với gửi token trực tiếp).
 */
public class CryptoUtil {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /** Tính chữ ký HMAC-SHA256 của 1 chuỗi dữ liệu với khóa bí mật cho trước, trả về dạng hex */
    public static String computeHmac(String data, String secretKey) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
            mac.init(keySpec);
            byte[] rawHmac = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));

            StringBuilder hex = new StringBuilder();
            for (byte b : rawHmac) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException("Lỗi khi tính HMAC: " + e.getMessage(), e);
        }
    }

    /** So sánh 2 chữ ký theo kiểu an toàn thời gian (constant-time), chống tấn công timing attack */
    public static boolean isEqual(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}