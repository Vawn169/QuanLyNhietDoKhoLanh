package coldstorage.util;

import coldstorage.model.TemperatureData;

import java.util.HashMap;
import java.util.Map;

/**
 * Theo dõi trạng thái cảnh báo THEO TỪNG KHO để chỉ phát cảnh báo khi có thay đổi ý nghĩa,
 * thay vì báo lỗi ở MỌI gói tin vượt ngưỡng.
 * Áp dụng độ trễ xác nhận (dwell time) và biên hồi phục (recovery margin).
 */
public class AlertStateTracker {

    public enum Event { NONE, START, REMIND, RECOVERED }

    public static class UpdateResult {
        public final Event event;
        public final long breachSince;
        public UpdateResult(Event event, long breachSince) {
            this.event = event;
            this.breachSince = breachSince;
        }
    }

    public static long getRemindIntervalMs() {
        return (long)(ConfigUtil.getAlertRemindMinutes() * 60 * 1000);
    }

    private static class State {
        long breachSince = -1; // -1 = đang an toàn
        boolean confirmed = false;
        long lastTimestamp;
        long lastAlertAt;
    }

    private static final Map<String, State> STATES = new HashMap<>();

    /**
     * Khôi phục 1 sự cố đang mở sau khi Server khởi động lại.
     */
    public static synchronized void resume(String warehouseName, long startedAt) {
        State st = new State();
        st.breachSince = startedAt;
        st.confirmed = true;
        st.lastAlertAt = startedAt;
        st.lastTimestamp = 0;
        STATES.put(warehouseName, st);
    }

    /** Xóa trạng thái của 1 kho */
    public static synchronized void forget(String warehouseName) {
        STATES.remove(warehouseName);
    }

    /**
     * Cập nhật trạng thái và trả về kết quả đánh giá cảnh báo.
     * Lưu ý: Phương thức này đã được đổi chữ ký để trả về đối tượng UpdateResult thay vì enum Event như yêu cầu gốc.
     * Lý do: Tránh tình trạng race condition (cạnh tranh luồng) khi 1 luồng gọi update() để thay đổi trạng thái,
     * trong khi 1 luồng khác (ví dụ: hàm consume) đang chờ đọc trạng thái đó. Việc gộp trạng thái và thời điểm (breachSince)
     * vào một object duy nhất giúp đảm bảo tính toàn vẹn của dữ liệu trong môi trường đa luồng.
     */
    public static synchronized UpdateResult update(TemperatureData data, AlertUtil.Status status) {
        String name = data.getWarehouseName();
        long ts = data.getTimestamp();
        
        Long maintenance = DatabaseUtil.getWarehouseMaintenance(name);
        if (maintenance != null && data.getServerReceivedAt() < maintenance) {
            return new UpdateResult(Event.NONE, -1);
        }

        State st = STATES.get(name);
        if (st == null) {
            st = new State();
            STATES.put(name, st);
        }
        if (ts <= st.lastTimestamp) return new UpdateResult(Event.NONE, st.breachSince);
        st.lastTimestamp = ts;

        DatabaseUtil.WarehouseOverrides overrides = DatabaseUtil.getWarehouseOverrides(name);
        double dwellMins = (overrides.dwellMinutes != null) ? overrides.dwellMinutes : ConfigUtil.getAlertDwellMinutesDefault();
        long dwellMs = (long)(dwellMins * 60 * 1000);
        double margin = (overrides.recoveryMargin != null) ? overrides.recoveryMargin : ConfigUtil.getAlertRecoveryMarginDefault();

        if (status == AlertUtil.Status.WARNING) {
            if (st.breachSince == -1) {
                st.breachSince = ts;
                return new UpdateResult(Event.NONE, st.breachSince);
            }
            if (!st.confirmed) {
                if (ts - st.breachSince >= dwellMs) {
                    st.confirmed = true;
                    st.lastAlertAt = ts; // Xác nhận lúc ts, nhưng bắt đầu từ breachSince
                    return new UpdateResult(Event.START, st.breachSince);
                }
            } else {
                if (ts - st.lastAlertAt >= getRemindIntervalMs()) {
                    st.lastAlertAt = ts;
                    return new UpdateResult(Event.REMIND, st.breachSince);
                }
            }
            return new UpdateResult(Event.NONE, st.breachSince);
        }

        // SAFE (status == AlertUtil.Status.SAFE)
        if (st.breachSince != -1) {
            if (!st.confirmed) {
                // Báo động hụt (chưa đủ dwellMs)
                st.breachSince = -1;
                return new UpdateResult(Event.NONE, -1);
            } else {
                // Đã confirmed, kiểm tra biên hồi phục
                double currentTemp = data.getTemperature();
                double[] safeRange = AlertUtil.getSafeRange(data);
                double minTemp = safeRange[0];
                double maxTemp = safeRange[1];

                if (maxTemp - minTemp < 2 * margin) {
                    // Dải an toàn quá hẹp, bỏ qua margin
                    if (currentTemp >= minTemp && currentTemp <= maxTemp) {
                        st.breachSince = -1;
                        st.confirmed = false;
                        return new UpdateResult(Event.RECOVERED, -1);
                    }
                } else {
                    if (currentTemp >= minTemp + margin && currentTemp <= maxTemp - margin) {
                        st.breachSince = -1;
                        st.confirmed = false;
                        return new UpdateResult(Event.RECOVERED, -1);
                    }
                }
            }
        }
        return new UpdateResult(Event.NONE, -1);
    }
}
