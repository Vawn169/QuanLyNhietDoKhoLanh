package coldstorage.util;

import coldstorage.model.TemperatureData;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Giả lập gửi cảnh báo thực tế (email/SMS). Đây là đồ án học tập nên KHÔNG gửi email/SMS thật; thay vào đó:
 * - In log ra console mô phỏng đúng định dạng 1 hệ thống thật sẽ hiển thị.
 * - Ghi log bền vững ra file riêng cho từng kho: logs/{TenKho}_{maBam}.log và file tổng hợp logs/alerts.log
 * - File luôn ghi bằng UTF-8 tường minh để không bị lỗi font tiếng Việt.
 *
 * Được gọi theo SỰ KIỆN (xem AlertStateTracker): bắt đầu cảnh báo, nhắc lại, đã hồi phục, mất tín hiệu -
 * không còn gọi ở mọi gói tin vượt ngưỡng.
 */
public class AlertNotifier {

    private static final String LOG_DIR = "logs";
    private static final SimpleDateFormat SDF = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    static {
        new File(LOG_DIR).mkdirs();
    }

    /** Cảnh báo vượt ngưỡng (reminder = true nếu là lần nhắc lại khi sự cố vẫn kéo dài) */
    public static synchronized void sendAlert(TemperatureData data, boolean reminder) {
        String time = SDF.format(new Date(data.getTimestamp()));
        String warehouseTypeText = "DONG".equalsIgnoreCase(data.getWarehouseType()) ? "Kho đông" : "Kho mát";
        String title = reminder ? "NHẮC LẠI CẢNH BÁO" : "CẢNH BÁO";
        String message = String.format("[%s] %s: %s (%s) đo được %.1f°C - VƯỢT NGƯỠNG AN TOÀN (%s)!",
                time, title, data.getWarehouseName(), warehouseTypeText, data.getTemperature(),
                AlertUtil.getThresholdText(data));
        // Nếu kho đang chứa sản phẩm cụ thể thì ghi rõ để biết hàng nào đang bị ảnh hưởng
        String products = ProductUtil.getProductNames(data.getWarehouseName());
        if (!products.isEmpty()) {
            message += " [Sản phẩm: " + products + "]";
        }
        dispatch(data.getWarehouseName(), message);
    }

    /** Thông báo kho đã trở về ngưỡng an toàn */
    public static synchronized void sendRecovered(TemperatureData data) {
        String time = SDF.format(new Date(data.getTimestamp()));
        String message = String.format("[%s] ĐÃ HỒI PHỤC: %s đo được %.1f°C - đã trở về ngưỡng an toàn (%s).",
                time, data.getWarehouseName(), data.getTemperature(), AlertUtil.getThresholdText(data));
        dispatch(data.getWarehouseName(), message);
    }

    /** Thông báo kho ngừng gửi dữ liệu (cảm biến hỏng, mất điện, đứt mạng...) - hiểm họa không kém vượt ngưỡng */
    public static synchronized void sendOffline(String warehouseName, long lastSeenAt) {
        long now = System.currentTimeMillis();
        String message = String.format("[%s] MẤT TÍN HIỆU: %s không gửi dữ liệu từ %s (%d giây trước) - KHÔNG THỂ GIÁM SÁT NHIỆT ĐỘ!",
                SDF.format(new Date(now)), warehouseName, SDF.format(new Date(lastSeenAt)),
                Math.max(0, (now - lastSeenAt) / 1000));
        dispatch(warehouseName, message);
    }

    /** Cảnh báo SỚM theo xu hướng: kho đang tăng/giảm nhanh và dự kiến sắp chạm ngưỡng, dù CHƯA thực sự vượt */
    public static synchronized void sendTrendWarning(TemperatureData data, TrendTracker.Projection proj) {
        String time = SDF.format(new Date(data.getTimestamp()));
        String direction = proj.rising ? "đang TĂNG dần" : "đang GIẢM dần";
        String message = String.format(
                "[%s] SẮP VƯỢT NGƯỠNG: %s đo được %.1f°C, %s - dự kiến chạm ngưỡng %.1f°C trong khoảng %.0f phút nữa nếu không can thiệp.",
                time, data.getWarehouseName(), data.getTemperature(), direction, proj.boundary,
                Math.max(0, proj.minutesToBreach));
        dispatch(data.getWarehouseName(), message);
    }

    private static void dispatch(String warehouseName, String message) {
        // ---- Giả lập gửi email/SMS (chỉ in ra console, không gửi thật) ----
        System.out.println("[ALERT-EMAIL] Đã gửi email cảnh báo tới quanly@coldstorage.vn » " + message);
        System.out.println("[ALERT-SMS]   Đã gửi SMS tới +84 90 xxx xxxx » " + message);

        // ---- Thông báo đẩy THẬT qua Telegram (bất đồng bộ, không chặn luồng xử lý gói tin) ----
        TelegramNotifier.send(message);

        // ---- Ghi log bền vững ----
        appendToFile(LOG_DIR + "/alerts.log", message);
        appendToFile(LOG_DIR + "/" + toLogFileName(warehouseName) + ".log", message);
    }

    /**
     * Tên file log an toàn cho hệ điều hành nhưng KHÔNG trùng nhau: bỏ dấu tiếng Việt (Đông -> Dong) thay vì
     * đổi thành "_" (bản cũ làm "Kho Đông A" và "Kho Đồng A" trùng file), và thêm mã băm của tên gốc phía sau.
     * Nhờ có hậu tố nên cũng không thể trùng với alerts.log.
     */
    static String toLogFileName(String warehouseName) {
        String n = Normalizer.normalize(warehouseName, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        n = n.replace('Đ', 'D').replace('đ', 'd').replaceAll("[^a-zA-Z0-9_\\-]", "_");
        if (n.length() > 40) n = n.substring(0, 40);
        return n + "_" + Integer.toHexString(warehouseName.hashCode());
    }

    private static synchronized void appendToFile(String path, String message) {
        try (OutputStreamWriter osw = new OutputStreamWriter(new FileOutputStream(path, true), StandardCharsets.UTF_8);
             PrintWriter pw = new PrintWriter(osw)) {
            pw.println(message);
        } catch (IOException e) {
            System.err.println("[AlertNotifier] Lỗi ghi log cảnh báo: " + e.getMessage());
        }
    }
}
