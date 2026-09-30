package coldstorage.sensor;

import java.util.Random;
import java.util.function.Supplier;

/**
 * Hành vi dạng "Decorator": BỌC quanh 1 TemperatureBehavior khác (ví dụ RandomWalkBehavior).
 * Khi nhiệt độ vẫn trong ngưỡng an toàn, giao toàn quyền tính toán cho hành vi bên trong.
 * Khi nhiệt độ vượt ngưỡng LIÊN TỤC quá recoveryDelayMs, hành vi này "chen vào" kéo nhiệt độ
 * dần về giữa dải an toàn - mô phỏng việc kỹ thuật viên/hệ thống làm lạnh đã can thiệp xử lý.
 *
 * LƯU Ý PHẠM VI: đây thuần túy là hành vi tạo DỮ LIỆU GIẢ LẬP phía cảm biến để demo thực tế hơn,
 * không phải cơ chế điều khiển thiết bị thật. Server vẫn chỉ đóng vai trò giám sát + cảnh báo.
 */
public class AutoRecoveryBehavior implements TemperatureBehavior {

    private final TemperatureBehavior innerBehavior;
    /** Cung cấp dải an toàn {min, max} MỖI LẦN đo, để bám theo ngưỡng thay đổi động (đổi sản phẩm/ngưỡng) */
    private final Supplier<double[]> safeRangeSupplier;
    private final long recoveryDelayMs;
    private final double recoveryStep;
    private final Random random = new Random();

    private long warningSinceMs = -1; // -1 nghĩa là hiện đang an toàn (chưa bắt đầu đếm)
    private boolean lastCallWasRecovering = false;

    /** Dải an toàn CỐ ĐỊNH (giữ tương thích với code cũ) */
    public AutoRecoveryBehavior(TemperatureBehavior innerBehavior, double safeMin, double safeMax,
                                 long recoveryDelayMs, double recoveryStep) {
        this(innerBehavior, () -> new double[]{safeMin, safeMax}, recoveryDelayMs, recoveryStep);
    }

    /** Dải an toàn ĐỘNG: được hỏi lại ở mỗi lần đo (dùng cho kho có sản phẩm với ngưỡng riêng) */
    public AutoRecoveryBehavior(TemperatureBehavior innerBehavior, Supplier<double[]> safeRangeSupplier,
                                 long recoveryDelayMs, double recoveryStep) {
        this.innerBehavior = innerBehavior;
        this.safeRangeSupplier = safeRangeSupplier;
        this.recoveryDelayMs = recoveryDelayMs;
        this.recoveryStep = recoveryStep;
    }

    @Override
    public double nextTemperature(double currentTemp, long now) {
        double[] range = safeRangeSupplier.get();
        double safeMin = range[0];
        double safeMax = range[1];
        boolean isOutOfRange = currentTemp < safeMin || currentTemp > safeMax;

        if (isOutOfRange) {
            if (warningSinceMs == -1) {
                warningSinceMs = now;
            }
        } else {
            warningSinceMs = -1;
        }

        boolean isBeingRecovered = isOutOfRange && (now - warningSinceMs >= recoveryDelayMs);
        lastCallWasRecovering = isBeingRecovered;

        if (isBeingRecovered) {
            double mid = (safeMin + safeMax) / 2.0;
            double t = currentTemp;
            if (t < mid) {
                t += recoveryStep;
            } else {
                t -= recoveryStep;
            }
            t += (random.nextDouble() - 0.5) * 0.2; // dao động nhỏ cho tự nhiên
            return t;
        } else {
            // Chưa cần can thiệp (hoặc đã hồi phục xong) -> để hành vi bên trong tự quyết định
            return innerBehavior.nextTemperature(currentTemp, now);
        }
    }

    @Override
    public String getStatusNote() {
        return lastCallWasRecovering ? "⚙ Đang hồi phục về ngưỡng an toàn (mô phỏng đã được xử lý)..." : null;
    }
}