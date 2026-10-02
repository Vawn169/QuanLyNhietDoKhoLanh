import coldstorage.model.TemperatureData;
import coldstorage.util.AlertStateTracker;
import coldstorage.util.AlertUtil;
import coldstorage.util.DatabaseUtil;
import java.io.File;

public class TestAlertMaintenanceTime {
    public static void main(String[] args) {
        String testWH = "TestWH_MAINT";
        
        // Ensure database is initialized
        DatabaseUtil.initDatabase();
        
        long now = System.currentTimeMillis();
        long maintenanceUntil = now - 60000; // Bảo trì đã hết hạn 1 phút trước
        
        // Ensure warehouse is active first so it exists in DB
        DatabaseUtil.markWarehouseActive(testWH, "DONG");
        DatabaseUtil.setWarehouseMaintenance(testWH, maintenanceUntil);
        
        // Giả lập gói tin
        // Nhiệt độ rất cao để gây cảnh báo, Giờ cảm biến tự đóng dấu (10 phút trước)
        TemperatureData data = new TemperatureData("SENSOR-TEST", testWH, "DONG", 50.0, now - 600000, "hmac_mock"); 
        // Giờ Server nhận (Hiện tại - Lớn hơn maintenance_until)
        data.setServerReceivedAt(now); 
        
        // Ghi đè thời gian trễ cảnh báo (dwell) về 0 để báo ngay lập tức thay vì đợi
        DatabaseUtil.updateWarehouseOverrides(testWH, 0.0, 0.5);
        AlertStateTracker.forget(testWH);
        
        System.out.println("--- Bắt đầu test ---");
        System.out.println("Hiện tại (now): " + now);
        System.out.println("Bảo trì tới (maintenanceUntil): " + maintenanceUntil);
        System.out.println("Gói tin đo lúc (data.Timestamp): " + data.getTimestamp());
        System.out.println("Server nhận lúc (data.ReceivedAt): " + data.getServerReceivedAt());
        
        // AlertStateTracker sẽ gọi Update với status WARNING vì nhiệt độ 50 là rất cao
        AlertStateTracker.UpdateResult res1 = AlertStateTracker.update(data, AlertUtil.Status.WARNING);
        System.out.println("Kết quả gói 1 event: " + res1.event);
        
        // Gói thứ 2 (cùng thời điểm nhận) để kích hoạt START (vì lần 1 chỉ gán breachSince)
        TemperatureData data2 = new TemperatureData("SENSOR-TEST", testWH, "DONG", 50.0, now - 599000, "hmac_mock");
        data2.setServerReceivedAt(now + 1);
        AlertStateTracker.UpdateResult res = AlertStateTracker.update(data2, AlertUtil.Status.WARNING);
        System.out.println("Kết quả gói 2 event: " + res.event);
        
        if (res.event != AlertStateTracker.Event.NONE) {
            System.out.println("TEST PASSED: event đã phát ra bình thường (KHÔNG bị im lặng do bảo trì chặn sai).");
        } else {
            System.out.println("TEST FAILED: event là NONE (Vẫn bị im lặng do bảo trì).");
        }
    }
}
