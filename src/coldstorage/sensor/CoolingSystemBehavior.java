package coldstorage.sensor;

import java.util.Random;

/**
 * Mô phỏng 1 HỆ THỐNG LÀM LẠNH có nhiệt độ vận hành (setpoint) riêng:
 *  - Bình thường: nhiệt độ dao động nhẹ QUANH setpoint (máy nén luôn kéo nhiệt độ về setpoint).
 *  - Thỉnh thoảng xảy ra SỰ CỐ (mở cửa kho, máy nén yếu...): nhiệt độ trôi dạt 1 chiều trong 12-30 giây.
 *  - Hết sự cố (mô phỏng kỹ thuật viên đã xử lý): nhiệt độ được kéo dần trở lại setpoint.
 *
 * Khác bản cũ (RandomWalk + AutoRecovery): cảm biến KHÔNG biết và KHÔNG cần biết ngưỡng cảnh báo của Server -
 * đúng như 1 cảm biến thật, chỉ đo và gửi. Việc nhiệt độ có vượt ngưỡng hay không là do Server đánh giá.
 * Nhờ vậy khi quản trị viên đổi ngưỡng hoặc gán sản phẩm, cảm biến không còn "tự hồi phục" về 1 dải cứng
 * khác với dải mà Server đang dùng (nguyên nhân khiến kho cảnh báo liên tục ở bản cũ).
 *
 * Các tham số tốc độ được quy đổi theo khoảng thời gian giữa 2 lần đo (chuẩn 3 giây) nên đổi chu kỳ gửi
 * không làm sự cố dài/ngắn bất thường.
 */
public class CoolingSystemBehavior implements TemperatureBehavior {

    private static final double BASE_STEP_MS = 3000.0;

    private final double setpoint;
    private final double noise;              // biên độ nhiễu đo mỗi lần (dao động ±noise/2)
    private final double pullback;           // tỉ lệ kéo về setpoint mỗi bước chuẩn 3s (0..1)
    private final double faultChancePercent; // % khả năng bắt đầu sự cố mỗi bước chuẩn 3s
    private final double faultDrift;         // độ trôi (°C) mỗi bước chuẩn 3s khi có sự cố
    private final long faultMinMs;
    private final long faultMaxMs;
    private final Random random = new Random();

    private boolean inFault = false;
    private long faultEndsAt;
    private double faultDirection;
    private long lastNow = 0;
    private String note;

    /** Cấu hình mặc định: dao động ±0.3°C, sự cố ~mỗi 2.5 phút, kéo dài 12-30 giây */
    public CoolingSystemBehavior(double setpoint) {
        this(setpoint, 0.6, 0.25, 2.0, 0.9, 12_000, 30_000);
    }

    public CoolingSystemBehavior(double setpoint, double noise, double pullback, double faultChancePercent,
                                 double faultDrift, long faultMinMs, long faultMaxMs) {
        this.setpoint = setpoint;
        this.noise = noise;
        this.pullback = pullback;
        this.faultChancePercent = faultChancePercent;
        this.faultDrift = faultDrift;
        this.faultMinMs = faultMinMs;
        this.faultMaxMs = Math.max(faultMinMs, faultMaxMs);
    }

    @Override
    public double nextTemperature(double currentTemp, long now) {
        note = null;
        double k = (lastNow == 0) ? 1.0 : Math.max(0.1, Math.min(10.0, (now - lastNow) / BASE_STEP_MS));
        lastNow = now;

        if (!inFault && random.nextDouble() * 100.0 < faultChancePercent * k) {
            inFault = true;
            faultDirection = random.nextBoolean() ? 1.0 : -1.0;
            faultEndsAt = now + faultMinMs + (long) (random.nextDouble() * (faultMaxMs - faultMinMs));
        }
        if (inFault) {
            if (now >= faultEndsAt) {
                inFault = false; // sự cố đã được xử lý, chuyển sang hồi phục bên dưới
            } else {
                note = "⚠ Mô phỏng sự cố thiết bị làm lạnh (nhiệt độ đang trôi dạt)";
                return currentTemp + faultDirection * faultDrift * k * (0.7 + 0.6 * random.nextDouble())
                        + (random.nextDouble() - 0.5) * noise * 0.5;
            }
        }

        double effectivePullback = 1.0 - Math.pow(1.0 - pullback, k);
        double t = currentTemp + (setpoint - currentTemp) * effectivePullback + (random.nextDouble() - 0.5) * noise;
        if (Math.abs(t - setpoint) > 1.5) {
            note = "⚙ Đang hồi phục về nhiệt độ vận hành (mô phỏng đã được xử lý)...";
        }
        return t;
    }

    @Override
    public String getStatusNote() {
        return note;
    }
}
