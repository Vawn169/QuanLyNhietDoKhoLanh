package coldstorage.util;

import coldstorage.model.TemperatureData;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Cảnh báo SỚM bằng xu hướng: khác với AlertStateTracker (chỉ phản ứng SAU KHI đã vượt ngưỡng),
 * lớp này ước lượng tốc độ tăng/giảm nhiệt độ trong vài phút gần nhất (hồi quy tuyến tính đơn giản
 * trên cửa sổ trượt) và dự báo xem có sắp chạm ngưỡng an toàn trong khoảng HORIZON_MINUTES tới hay không.
 *
 * Chỉ nên gọi cho gói tin THẬT (không phải BACKUP-...), vì số liệu ước tính của bộ dự phòng nhiễu hơn,
 * không phản ánh đúng tốc độ thay đổi thật của kho.
 *
 * Đây là dự báo, KHÔNG phải đã-xảy-ra, nên được tách hẳn khỏi AlertStateTracker/alert_history để
 * không ảnh hưởng tới logic START/REMIND/RECOVERED vốn đã ổn định.
 */
public final class TrendTracker {

    private TrendTracker() {
    }

    /** Cửa sổ dữ liệu tối đa dùng để tính xu hướng */
    private static final int WINDOW_SIZE = 12;
    /** Chỉ tính xu hướng khi cửa sổ trải dài ít nhất chừng này (tránh vài điểm gần nhau gây nhiễu) */
    private static final long MIN_SPAN_MS = 45 * 1000;
    /** Dự báo trước bao nhiêu phút */
    private static final double HORIZON_MIN = 5.0;
    /** Tốc độ thay đổi dưới ngưỡng này (°C/phút) coi là nhiễu, không phải xu hướng thật */
    private static final double MIN_SLOPE_PER_MIN = 0.03;
    /** Giãn cách tối thiểu giữa 2 lần cảnh báo sớm liên tiếp cho CÙNG 1 kho, tránh làm phiền liên tục */
    private static final long WARN_COOLDOWN_MS = 10 * 60 * 1000;

    private static final Map<String, Deque<double[]>> WINDOWS = new HashMap<>(); // [timestampMs, temperature]
    private static final Map<String, Long> LAST_WARN_AT = new HashMap<>();
    /** Kho nào đang trong trạng thái "sắp vượt ngưỡng" ngay tại thời điểm gói tin mới nhất - để MonitorServer
     *  hiển thị lên bảng trạng thái (màu vàng cam), tách biệt khỏi việc có gửi thông báo hay không (shouldWarn
     *  có giãn cách 10 phút, nhưng trạng thái hiển thị trên bảng thì nên cập nhật ngay mỗi gói tin). */
    private static final Map<String, Boolean> TRENDING_NOW = new HashMap<>();

    /** Kết quả dự báo: nhiệt độ ước tính sau HORIZON_MIN phút nữa, và số phút ước tính tới lúc CHẠM ngưỡng */
    public static class Projection {
        public final double projectedTemp;
        public final double minutesToBreach;
        public final double boundary; // ngưỡng sẽ bị chạm (min hoặc max)
        public final boolean rising;  // true = đang tăng dần (sắp vượt ngưỡng trên), false = đang giảm dần

        Projection(double projectedTemp, double minutesToBreach, double boundary, boolean rising) {
            this.projectedTemp = projectedTemp;
            this.minutesToBreach = minutesToBreach;
            this.boundary = boundary;
            this.rising = rising;
        }
    }

    /** Nạp 1 điểm đo mới và tính lại xu hướng cho kho đó. @return dự báo nếu sắp chạm ngưỡng, ngược lại null */
    public static synchronized Projection update(TemperatureData data) {
        String name = data.getWarehouseName();
        Projection result = compute(data, name);
        TRENDING_NOW.put(name, result != null); // luôn cập nhật cờ hiển thị, kể cả khi kết quả là "không trending"
        return result;
    }

    private static Projection compute(TemperatureData data, String name) {
        Deque<double[]> window = WINDOWS.computeIfAbsent(name, k -> new ArrayDeque<>());
        window.addLast(new double[]{data.getTimestamp(), data.getTemperature()});
        while (window.size() > WINDOW_SIZE) {
            window.removeFirst();
        }
        if (window.size() < 4) return null; // quá ít điểm để tính hồi quy đáng tin

        double[] first = window.peekFirst();
        double[] last = window.peekLast();
        if (last[0] - first[0] < MIN_SPAN_MS) return null; // cửa sổ quá ngắn, dễ nhiễu

        double slopePerMin = linearRegressionSlopePerMinute(window);
        if (Math.abs(slopePerMin) < MIN_SLOPE_PER_MIN) return null; // coi như đứng yên

        double currentTemp = last[1];
        double[] range = AlertUtil.getSafeRange(data); // {min, max}
        double projectedTemp = currentTemp + slopePerMin * HORIZON_MIN;

        if (slopePerMin > 0 && currentTemp <= range[1] && projectedTemp > range[1]) {
            double minutesToBreach = (range[1] - currentTemp) / slopePerMin;
            return new Projection(projectedTemp, minutesToBreach, range[1], true);
        }
        if (slopePerMin < 0 && currentTemp >= range[0] && projectedTemp < range[0]) {
            double minutesToBreach = (range[0] - currentTemp) / slopePerMin; // slope âm, hiệu âm -> kết quả dương
            return new Projection(projectedTemp, minutesToBreach, range[0], false);
        }
        return null;
    }

    /** true nếu kho này ĐANG được coi là "sắp vượt ngưỡng" tại lần cập nhật gần nhất - dùng để tô màu bảng trạng thái */
    public static synchronized boolean isTrendingNow(String warehouseName) {
        return Boolean.TRUE.equals(TRENDING_NOW.get(warehouseName));
    }

    /** Hồi quy tuyến tính đơn giản (least squares): trục x = phút kể từ điểm đầu cửa sổ, trục y = nhiệt độ */
    private static double linearRegressionSlopePerMinute(Deque<double[]> window) {
        double t0 = window.peekFirst()[0];
        int n = window.size();
        double sumX = 0, sumY = 0, sumXY = 0, sumXX = 0;
        for (double[] p : window) {
            double x = (p[0] - t0) / 60000.0; // phút
            double y = p[1];
            sumX += x;
            sumY += y;
            sumXY += x * y;
            sumXX += x * x;
        }
        double denom = n * sumXX - sumX * sumX;
        if (Math.abs(denom) < 1e-9) return 0; // các điểm trùng thời gian, tránh chia cho 0
        return (n * sumXY - sumX * sumY) / denom;
    }

    /** true nếu ĐÃ ĐỦ LÂU kể từ lần cảnh báo sớm gần nhất của kho này (và ghi nhận lần cảnh báo mới) */
    public static synchronized boolean shouldWarn(String warehouseName) {
        long now = System.currentTimeMillis();
        Long last = LAST_WARN_AT.get(warehouseName);
        if (last != null && now - last < WARN_COOLDOWN_MS) return false;
        LAST_WARN_AT.put(warehouseName, now);
        return true;
    }

    /** Xóa toàn bộ dữ liệu xu hướng của 1 kho (gọi khi kho đã thực sự vượt ngưỡng, hoặc bị xóa khỏi hệ thống) */
    public static synchronized void clear(String warehouseName) {
        WINDOWS.remove(warehouseName);
        LAST_WARN_AT.remove(warehouseName);
        TRENDING_NOW.remove(warehouseName);
    }
}
