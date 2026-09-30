package coldstorage.sensor;

import java.util.Random;

/**
 * Hành vi mặc định: nhiệt độ "đi bộ ngẫu nhiên" (random walk) quanh giá trị hiện tại,
 * và thỉnh thoảng tự tạo sự cố (nhảy vọt lên/xuống) để kiểm thử cảnh báo.
 * Không tự biết ngưỡng an toàn là gì - chỉ đơn thuần mô phỏng nhiễu đo lường tự nhiên.
 */
public class RandomWalkBehavior implements TemperatureBehavior {

    private final Random random = new Random();
    private final double stepMagnitude;      // độ dao động ngẫu nhiên mỗi lần đo
    private final int faultChancePercent;    // % khả năng xảy ra sự cố mỗi lần đo
    private final double faultMagnitude;     // biên độ nhảy vọt khi có sự cố

    public RandomWalkBehavior() {
        this(0.8, 8, 3.0);
    }

    public RandomWalkBehavior(double stepMagnitude, int faultChancePercent, double faultMagnitude) {
        this.stepMagnitude = stepMagnitude;
        this.faultChancePercent = faultChancePercent;
        this.faultMagnitude = faultMagnitude;
    }

    @Override
    public double nextTemperature(double currentTemp, long now) {
        double t = currentTemp + (random.nextDouble() - 0.5) * stepMagnitude;
        if (random.nextInt(100) < faultChancePercent) {
            t += (random.nextBoolean() ? faultMagnitude : -faultMagnitude);
        }
        return t;
    }
}