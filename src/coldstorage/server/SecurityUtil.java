package coldstorage.server;

import coldstorage.model.TemperatureData;
import coldstorage.util.ConfigUtil;
import coldstorage.util.CryptoUtil;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Xác thực nguồn gửi dữ liệu.
 * - isIpAllowed: IP gửi phải nằm trong whitelist (cấu hình allowed.ips, mặc định chỉ máy local).
 * - isFresh: từ chối gói tin có timestamp lệch quá xa lúc Server nhận.
 * - isSignatureValid: kiểm tra chữ ký HMAC-SHA256 (khóa đọc từ ConfigUtil, không import từ lớp cảm biến).
 * - isReplay: chống phát lại - mỗi cảm biến chỉ được nhận gói có timestamp LỚN HƠN gói đã nhận trước đó,
 *   nên gói bị bắt lại rồi gửi lại (kể cả trong cửa sổ 30 giây của isFresh) sẽ bị từ chối.
 * Mọi gói bị từ chối được đếm lại để hiển thị trên giao diện (không còn im lặng bỏ qua).
 */
public class SecurityUtil {

    /** Lệch thời gian tối đa cho phép giữa lúc đo và lúc Server nhận */
    private static final long MAX_CLOCK_SKEW_MS = 30_000;

    private static final Map<String, Long> LAST_TIMESTAMP_BY_SENSOR = new ConcurrentHashMap<>();
    private static final AtomicInteger REJECTED = new AtomicInteger();

    public static boolean isSignatureValid(TemperatureData data) {
        if (data.getHmac() == null) return false;
        String expected = CryptoUtil.computeHmac(data.getSignablePart(), ConfigUtil.getSharedSecret());
        return CryptoUtil.isEqual(expected, data.getHmac());
    }

    public static boolean isIpAllowed(String ip) {
        return ip != null && ConfigUtil.getAllowedIps().contains(ip);
    }

    public static boolean isFresh(long sensorTimestamp, long serverReceivedAt) {
        return Math.abs(serverReceivedAt - sensorTimestamp) <= MAX_CLOCK_SKEW_MS;
    }

    /** Gọi SAU khi chữ ký đã hợp lệ. true nếu gói này cũ hơn hoặc trùng gói đã nhận của cùng cảm biến. */
    public static boolean isReplay(TemperatureData data) {
        final boolean[] replay = {false};
        LAST_TIMESTAMP_BY_SENSOR.compute(data.getSensorId(), (id, last) -> {
            if (last != null && data.getTimestamp() <= last) {
                replay[0] = true;
                return last;
            }
            return data.getTimestamp();
        });
        return replay[0];
    }

    public static void countRejected() { REJECTED.incrementAndGet(); }

    public static int getRejectedCount() { return REJECTED.get(); }
}
