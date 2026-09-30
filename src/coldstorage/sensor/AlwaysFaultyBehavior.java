package coldstorage.sensor;

import java.util.Random;

/**
 * Hành vi mô phỏng SỰ CỐ KÉO DÀI KHÔNG ĐƯỢC XỬ LÝ (ví dụ: hỏng máy nén, mất điện lâu).
 * Nhiệt độ liên tục trôi dạt theo 1 hướng, không có cơ chế tự hồi phục - dùng để kiểm thử
 * kịch bản "cảnh báo dai dẳng" (ví dụ: kiểm tra tính năng leo thang cảnh báo, ghi log liên tục).
 * Khác với RandomWalkBehavior (dao động cân bằng quanh 1 điểm), hành vi này thiên hẳn về 1 hướng.
 */
public class AlwaysFaultyBehavior implements TemperatureBehavior {

    private final Random random = new Random();
    private final double driftPerStep; // tốc độ trôi dạt mỗi lần đo

    public AlwaysFaultyBehavior() {
        this(0.5);
    }

    public AlwaysFaultyBehavior(double driftPerStep) {
        this.driftPerStep = driftPerStep;
    }

    @Override
    public double nextTemperature(double currentTemp, long now) {
        // Thiên về tăng dần (hệ số 0.7 thay vì 0.5) để mô phỏng nhiệt độ trôi dạt 1 chiều,
        // không dao động cân bằng như hành vi bình thường.
        return currentTemp + (random.nextDouble() - 0.3) * driftPerStep * 2;
    }

    @Override
    public String getStatusNote() {
        return "☠ Thiết bị mô phỏng lỗi - không có cơ chế tự hồi phục";
    }
}