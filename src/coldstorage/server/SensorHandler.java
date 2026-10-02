package coldstorage.server;

import coldstorage.model.TemperatureData;
import coldstorage.util.AlertNotifier;
import coldstorage.util.AlertStateTracker;
import coldstorage.util.AlertUtil;
import coldstorage.util.BackupUtil;
import coldstorage.util.DatabaseUtil;
import coldstorage.util.TrendTracker;

/**
 * Xử lý 1 gói tin nhận được từ cảm biến, chạy trên 1 luồng riêng (được đưa vào ThreadPoolExecutor).
 * Việc tách xử lý ra Thread/luồng riêng giúp Server không bị chặn (block) khi ghi DB hoặc cập nhật GUI,
 * cho phép nhận đồng thời gói tin từ nhiều cảm biến khác nhau.
 *
 * Bảo mật (kiểm tra rẻ trước, đắt sau): IP nguồn nằm trong whitelist -> timestamp không lệch quá xa ->
 * chữ ký HMAC hợp lệ -> không phải gói phát lại. Gói bị từ chối được đếm để hiển thị trên giao diện.
 */
public class SensorHandler implements Runnable {

    public interface ResultCallback {
        void onProcessed(TemperatureData data, AlertUtil.Status status, AlertStateTracker.Event event);
    }

    private final String rawPacket;
    private final String sourceIp;
    private final long serverReceivedAt;
    private final ResultCallback callback;

    public SensorHandler(String rawPacket, String sourceIp, long serverReceivedAt, ResultCallback callback) {
        this.rawPacket = rawPacket;
        this.sourceIp = sourceIp;
        this.serverReceivedAt = serverReceivedAt;
        this.callback = callback;
    }

    private void reject(String reason, String sensorId) {
        SecurityUtil.countRejected();
        System.err.println("[SensorHandler] TỪ CHỐI gói tin: " + reason + ", IP=" + sourceIp + (sensorId != null ? ", Sensor=" + sensorId : ""));
        DatabaseUtil.insertRejectedLog(serverReceivedAt, sourceIp, reason, sensorId);
    }

    @Override
    public void run() {
        try {
            if (!SecurityUtil.isIpAllowed(sourceIp)) {
                reject("IP không nằm trong whitelist", null);
                return;
            }

            TemperatureData data;
            try {
                data = TemperatureData.fromPacketString(rawPacket);
                data.setServerReceivedAt(serverReceivedAt);
            } catch (Exception e) {
                reject("Sai định dạng gói tin", null);
                return;
            }

            if (!SecurityUtil.isFresh(data.getTimestamp(), serverReceivedAt)) {
                reject("Timestamp lệch quá ngưỡng (đồng hồ sai hoặc replay)", data.getSensorId());
                return;
            }
            if (!SecurityUtil.isSignatureValid(data)) {
                reject("Chữ ký HMAC không hợp lệ", data.getSensorId());
                return;
            }
            if (SecurityUtil.isReplay(data)) {
                reject("Gói tin cũ/trùng (nghi ngờ replay attack)", data.getSensorId());
                return;
            }

            AlertUtil.Status status = AlertUtil.checkStatus(data);

            DatabaseUtil.insertLog(data, status.name());

            // Chỉ gói tin THẬT (không phải do bộ dự phòng tự gửi) mới được coi là bằng chứng "kho này
            // đang có cảm biến chính hoạt động" - nếu không, BackupLauncher (đọc bảng này ở tiến trình
            // khác) sẽ tưởng nhầm chính gói dự phòng của nó là dấu hiệu kho còn tồn tại, tự nuôi sống
            // 1 vòng lặp vô hạn.
            if (!BackupUtil.isBackupSource(data)) {
                DatabaseUtil.markWarehouseActive(data.getWarehouseName(), data.getWarehouseType());
            }

            // Chỉ cảnh báo khi có thay đổi trạng thái (bắt đầu / nhắc lại / hồi phục), không báo ở mọi gói.
            // CHỈ áp dụng cho gói tin THẬT: gói dự phòng (ước tính) không được đưa vào AlertStateTracker,
            // vì nếu số liệu ước tính trôi về trong ngưỡng an toàn, hệ thống sẽ gửi "ĐÃ HỒI PHỤC" trong khi
            // cảm biến thật vẫn đang MẤT TÍN HIỆU - mâu thuẫn trực tiếp với cảnh báo sendOffline() và gây
            // hiểu lầm nghiêm trọng cho người vận hành (tưởng sự cố đã qua). Gói dự phòng vẫn được ghi log
            // (DatabaseUtil.insertLog ở trên) và vẫn cập nhật giao diện qua callback.onProcessed() bên dưới,
            // chỉ không kích hoạt START/REMIND/RECOVERED.
            AlertStateTracker.Event event = AlertStateTracker.Event.NONE;
            if (!BackupUtil.isBackupSource(data)) {
                AlertStateTracker.UpdateResult updateResult = AlertStateTracker.update(data, status);
                event = updateResult.event;
                switch (event) {
                    case START:
                        // Mở sự cố trong DB TRƯỚC khi gửi thông báo (gửi có thể chậm: log/email/Telegram). Nếu để sau,
                        // 1 gói WARNING tệ hơn tới trong lúc đó (luồng khác) sẽ không thấy sự cố đang mở và bị mất đỉnh.
                        DatabaseUtil.startAlertIncident(data.getWarehouseName(), updateResult.breachSince, data.getTemperature());
                        AlertNotifier.sendAlert(data, false);
                        break;
                    case REMIND:
                        AlertNotifier.sendAlert(data, true);
                        break;
                    case RECOVERED:
                        DatabaseUtil.closeAlertIncident(data.getWarehouseName(), data.getTimestamp());
                        AlertNotifier.sendRecovered(data);
                        break;
                    default:
                        break;
                }
                // Cập nhật nhiệt độ đỉnh điểm ở MỌI gói WARNING (không chỉ lúc REMIND sau 5 phút)
                if (status == AlertUtil.Status.WARNING) {
                    double[] range = AlertUtil.getSafeRange(data);
                    DatabaseUtil.updateAlertPeak(data.getWarehouseName(), data.getTemperature(), range[0], range[1]);
                }
                // Cảnh báo SỚM theo xu hướng (chỉ áp dụng cho gói THẬT, số liệu dự phòng nhiễu hơn không phù hợp
                // để tính tốc độ tăng/giảm). Không lồng vào AlertStateTracker vì đây là 1 loại tín hiệu khác hẳn
                // (dự báo, không phải đã-xảy-ra), không muốn ảnh hưởng logic START/REMIND/RECOVERED đã ổn định.
                if (status == AlertUtil.Status.SAFE) {
                    TrendTracker.Projection proj = TrendTracker.update(data);
                    if (proj != null && TrendTracker.shouldWarn(data.getWarehouseName())) {
                        AlertNotifier.sendTrendWarning(data, proj);
                    }
                } else {
                    TrendTracker.clear(data.getWarehouseName()); // đã vượt ngưỡng thật thì không cần dự báo nữa
                }
                
                coldstorage.util.SensorAnomalyDetector.update(data);
            }

            if (callback != null) {
                callback.onProcessed(data, status, event);
            }
        } catch (Exception e) {
            SecurityUtil.countRejected();
            System.err.println("[SensorHandler] Lỗi xử lý gói tin (có thể là gói dữ liệu rác/giả mạo sai định dạng): " + e.getMessage());
        }
    }
}
