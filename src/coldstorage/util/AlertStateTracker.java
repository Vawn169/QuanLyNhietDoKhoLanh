package coldstorage.util;

import coldstorage.model.TemperatureData;

import java.util.HashMap;
import java.util.Map;

/**
 * Theo dõi trạng thái cảnh báo THEO TỪNG KHO để chỉ phát cảnh báo khi có thay đổi ý nghĩa,
 * thay vì báo lại ở MỌI gói tin vượt ngưỡng (gây "bão" email/SMS/log và bíp liên tục):
 *  - START     : kho vừa chuyển từ an toàn sang vượt ngưỡng  -> gửi cảnh báo + bíp.
 *  - REMIND    : kho vẫn vượt ngưỡng sau REMIND_INTERVAL_MS  -> nhắc lại (không bíp).
 *  - RECOVERED : kho vừa trở về vùng an toàn                 -> báo đã hồi phục.
 *  - NONE      : không có gì cần thông báo.
 * Gói đến trễ/trùng thứ tự (timestamp không lớn hơn gói đã xử lý) bị bỏ qua để trạng thái không nhảy qua lại.
 */
public class AlertStateTracker {

    public enum Event { NONE, START, REMIND, RECOVERED }

    /** Khoảng nhắc lại khi sự cố kéo dài */
    public static final long REMIND_INTERVAL_MS = 5 * 60 * 1000;

    private static class State {
        boolean warning;
        long lastTimestamp;
        long lastAlertAt;
    }

    private static final Map<String, State> STATES = new HashMap<>();

    /**
     * Khôi phục 1 sự cố đang mở sau khi Server khởi động lại (xem DatabaseUtil.reconcileOpenIncidents): coi kho là ĐANG
     * vượt ngưỡng từ startedAt, để gói WARNING kế tiếp KHÔNG mở sự cố trùng, và gói SAFE đầu tiên đóng đúng sự cố này.
     */
    public static synchronized void resume(String warehouseName, long startedAt) {
        State st = new State();
        st.warning = true;
        st.lastAlertAt = startedAt;
        st.lastTimestamp = 0;
        STATES.put(warehouseName, st);
    }

    /** Xóa trạng thái của 1 kho (khi kho bị xóa khỏi hệ thống, để kho trùng tên tạo lại sau này bắt đầu sạch) */
    public static synchronized void forget(String warehouseName) {
        STATES.remove(warehouseName);
    }

    public static synchronized Event update(TemperatureData data, AlertUtil.Status status) {
        State st = STATES.get(data.getWarehouseName());
        if (st == null) {
            st = new State();
            STATES.put(data.getWarehouseName(), st);
        }
        long ts = data.getTimestamp();
        if (ts <= st.lastTimestamp) return Event.NONE;
        st.lastTimestamp = ts;

        if (status == AlertUtil.Status.WARNING) {
            if (!st.warning) {
                st.warning = true;
                st.lastAlertAt = ts;
                return Event.START;
            }
            if (ts - st.lastAlertAt >= REMIND_INTERVAL_MS) {
                st.lastAlertAt = ts;
                return Event.REMIND;
            }
            return Event.NONE;
        }
        if (st.warning) {
            st.warning = false;
            return Event.RECOVERED;
        }
        return Event.NONE;
    }
}
