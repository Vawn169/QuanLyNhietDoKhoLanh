package coldstorage.backup;

import coldstorage.util.ConfigUtil;
import coldstorage.util.DatabaseUtil;
import coldstorage.util.ProductUtil;

import java.util.HashMap;
import java.util.Map;

/**
 * Chạy file này như 1 CHƯƠNG TRÌNH RIÊNG - độc lập với MonitorServer và với SensorLauncher
 * (right-click BackupLauncher.java > Run File trong NetBeans).
 *
 * Nhiệm vụ: định kỳ đọc danh sách các kho đang active=1 trong sổ đăng ký (warehouse_registry),
 * rồi tự "cắm" 1 BackupSensorClient (chạy trên 1 luồng riêng) cho từng kho - mỗi luồng tự quan
 * sát cảm biến chính của đúng kho đó và chỉ lên tiếng (gửi dữ liệu ước tính) khi cảm biến chính
 * mất tín hiệu quá lâu. Kho mới do Admin thêm sau cũng tự được nhận theo dõi, và kho vừa bị xóa
 * (kể cả khi KHÔNG tick "xóa luôn lịch sử") cũng tự được ngừng theo dõi ngay - không cần khởi
 * động lại chương trình này.
 *
 * YÊU CẦU CHẠY: chạy trong CÙNG thư mục làm việc với Server (để đọc chung config.properties
 * và coldstorage.db - shared.secret phải khớp, allowed.ips phải cho phép IP máy chạy chương
 * trình này). Nên khởi động SAU khi Server đã chạy ít nhất 1 lần (để database đã có bảng).
 * Có thể chạy trên cùng máy với Server (khuyên dùng) hoặc máy khác nếu IP nằm trong whitelist.
 */
public class BackupLauncher {

    private static final String SERVER_HOST = "localhost";
    private static final int SERVER_PORT = 9876;
    private static final long DISCOVER_INTERVAL_MS = 10_000; // dò kho mới/kho vừa xuất hiện mỗi 10s

    public static void main(String[] args) throws InterruptedException {
        DatabaseUtil.initDatabase();
        ProductUtil.init();

        Map<String, BackupSensorClient> activeByWarehouse = new HashMap<>();
        System.out.println("[Dự phòng] Đã khởi động chương trình cảm biến dự phòng.");
        System.out.println("[Dự phòng] Server: " + SERVER_HOST + ":" + SERVER_PORT + " | Nhấn Ctrl+C để dừng.");

        while (true) {
            // Đây là TIẾN TRÌNH RIÊNG: ngưỡng chung (config.properties) và sản phẩm/gán sản phẩm do Server sửa ở tiến trình
            // khác nên phải nạp lại MỖI vòng, nếu không dự phòng cứ kéo nhiệt độ về trung điểm của ngưỡng CŨ.
            // (Nạp lỗi thì cả hai đều tự giữ giá trị đang dùng.)
            ConfigUtil.reload();
            ProductUtil.refresh();

            try {
                // CHỈ theo dõi kho đang active=1 trong sổ đăng ký - KHÔNG dùng lịch sử temperature_log,
                // vì lịch sử vẫn còn ngay cả sau khi kho đã bị xóa (trừ khi người dùng chủ
                // động tick "xóa luôn lịch sử" trong Quản trị), nên dựa vào lịch sử sẽ vô tình "hồi sinh"
                // những kho đã bị xóa.
                // queryActiveWarehouseTypes ném lỗi khi đọc DB thất bại: KHÔNG được coi "đọc lỗi" là "không còn kho nào"
                // (nếu không, mọi luồng dự phòng sẽ bị dừng hàng loạt chỉ vì DB bị khóa thoáng qua).
                Map<String, String> warehouses = DatabaseUtil.queryActiveWarehouseTypes();

                for (Map.Entry<String, String> entry : warehouses.entrySet()) {
                    String name = entry.getKey();
                    String type = entry.getValue();
                    BackupSensorClient existing = activeByWarehouse.get(name);
                    // Luồng có thể đã tự dừng (kho từng bị xóa rồi được đăng ký lại) -> dựng lại luồng mới
                    if (existing == null || !existing.isAlive()) {
                        BackupSensorClient client = new BackupSensorClient(name, type, SERVER_HOST, SERVER_PORT);
                        Thread t = new Thread(client, "Thread-Backup-" + name);
                        t.setDaemon(true);
                        client.attach(t);
                        t.start();
                        activeByWarehouse.put(name, client);
                        System.out.println("[Dự phòng] Bắt đầu theo dõi kho '" + name + "' (" + type + ").");
                    }
                }

                // Kho đang được theo dõi nhưng vừa bị xóa (không còn active=1) -> dừng luồng ngay,
                // không đợi bản thân luồng đó tự phát hiện ở vòng lặp tiếp theo.
                java.util.Iterator<Map.Entry<String, BackupSensorClient>> it = activeByWarehouse.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<String, BackupSensorClient> e = it.next();
                    if (!warehouses.containsKey(e.getKey())) {
                        e.getValue().stop();
                        System.out.println("[Dự phòng] Kho '" + e.getKey() + "' đã bị xóa khỏi hệ thống, dừng theo dõi.");
                        it.remove();
                    }
                }
            } catch (java.sql.SQLException ex) {
                System.err.println("[Dự phòng] Không đọc được danh sách kho (giữ nguyên các luồng đang chạy, thử lại vòng sau): "
                        + ex.getMessage());
            }

            Thread.sleep(DISCOVER_INTERVAL_MS);
        }
    }
}
