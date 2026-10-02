import coldstorage.model.TemperatureData;
import coldstorage.util.AlertStateTracker;
import coldstorage.util.AlertUtil;
import coldstorage.util.ConfigUtil;

public class TestAlertTracker {
    public static void main(String[] args) {
        coldstorage.util.DatabaseUtil.initDatabase();
        System.out.println("Starting AlertStateTracker Tests...");
        // Set default configs manually just in case
        ConfigUtil.setAlertDefaults(2.0, 0.5); // 2 mins dwell, 0.5 margin
        ConfigUtil.setThresholds(-18.0, -15.0, 2.0, 8.0); // DONG: -18 to -15

        testScenarioA();
        testScenarioB();
        testScenarioC();
        testScenarioD();
        testScenarioE();
        testScenarioF();
        
        System.out.println("All tests finished.");
    }

    private static void testScenarioA() {
        System.out.println("\n--- Test A: Vượt ngưỡng 1 phút rồi về an toàn ngay (dwell 2 phút) -> KHÔNG START ---");
        AlertStateTracker.forget("Kho_A");
        long t0 = System.currentTimeMillis();
        
        // At t0: Warning
        TemperatureData d1 = new TemperatureData("sensor1", "Kho_A", "DONG", -10.0, t0, "hash");
        AlertStateTracker.UpdateResult r1 = AlertStateTracker.update(d1, AlertUtil.Status.WARNING);
        System.out.println("t0 (Warning): " + r1.event + " (breachSince: " + (r1.breachSince == t0) + ")");
        
        // At t0 + 1 min: Warning
        long t1 = t0 + 60000;
        TemperatureData d2 = new TemperatureData("sensor1", "Kho_A", "DONG", -10.0, t1, "hash");
        AlertStateTracker.UpdateResult r2 = AlertStateTracker.update(d2, AlertUtil.Status.WARNING);
        System.out.println("t0+1m (Warning): " + r2.event);
        
        // At t0 + 1.5 min: Safe
        long t2 = t0 + 90000;
        TemperatureData d3 = new TemperatureData("sensor1", "Kho_A", "DONG", -16.0, t2, "hash"); // Safe temp (-16 is within -18 to -15)
        AlertStateTracker.UpdateResult r3 = AlertStateTracker.update(d3, AlertUtil.Status.SAFE);
        System.out.println("t0+1.5m (Safe): " + r3.event);
        
        if (r1.event == AlertStateTracker.Event.NONE && r2.event == AlertStateTracker.Event.NONE && r3.event == AlertStateTracker.Event.NONE) {
            System.out.println("=> PASS");
        } else {
            System.out.println("=> FAIL");
        }
    }

    private static void testScenarioB() {
        System.out.println("\n--- Test B: Vượt ngưỡng liên tục đủ 2 phút -> đúng 1 START, đúng breachSince ---");
        AlertStateTracker.forget("Kho_B");
        long t0 = System.currentTimeMillis();
        
        // At t0: Warning
        TemperatureData d1 = new TemperatureData("sensor1", "Kho_B", "DONG", -10.0, t0, "hash");
        AlertStateTracker.UpdateResult r1 = AlertStateTracker.update(d1, AlertUtil.Status.WARNING);
        
        // At t0 + 2.5 min: Warning -> Should trigger START
        long t1 = t0 + 150000; // 2.5 mins
        TemperatureData d2 = new TemperatureData("sensor1", "Kho_B", "DONG", -10.0, t1, "hash");
        AlertStateTracker.UpdateResult r2 = AlertStateTracker.update(d2, AlertUtil.Status.WARNING);
        System.out.println("t0+2.5m (Warning): " + r2.event + ", breachSince is t0: " + (r2.breachSince == t0));
        
        if (r1.event == AlertStateTracker.Event.NONE && r2.event == AlertStateTracker.Event.START && r2.breachSince == t0) {
            System.out.println("=> PASS");
        } else {
            System.out.println("=> FAIL");
        }
    }

    private static void testScenarioC() {
        System.out.println("\n--- Test C: Dao động sát biên (trong dải nhưng trong khoảng margin) -> không RECOVERED ---");
        AlertStateTracker.forget("Kho_C");
        long t0 = System.currentTimeMillis();
        
        // Trigger alert first
        AlertStateTracker.update(new TemperatureData("sensor1", "Kho_C", "DONG", -10.0, t0, "hash"), AlertUtil.Status.WARNING);
        AlertStateTracker.UpdateResult rAlert = AlertStateTracker.update(
                new TemperatureData("sensor1", "Kho_C", "DONG", -10.0, t0 + 150000, "hash"), AlertUtil.Status.WARNING);
        System.out.println("Setup alert: " + rAlert.event);
        
        // Safe range is -18 to -15. Margin is 0.5. 
        // So required to recover is -17.5 to -15.5
        // Try -15.2 (Safe, but in margin)
        TemperatureData d1 = new TemperatureData("sensor1", "Kho_C", "DONG", -15.2, t0 + 160000, "hash");
        AlertStateTracker.UpdateResult r1 = AlertStateTracker.update(d1, AlertUtil.Status.SAFE);
        System.out.println("t+ (Safe but -15.2): " + r1.event);
        
        // Try -17.8 (Safe, but in margin)
        TemperatureData d2 = new TemperatureData("sensor1", "Kho_C", "DONG", -17.8, t0 + 170000, "hash");
        AlertStateTracker.UpdateResult r2 = AlertStateTracker.update(d2, AlertUtil.Status.SAFE);
        System.out.println("t+ (Safe but -17.8): " + r2.event);
        
        // Try -16.0 (Safe and outside margin)
        TemperatureData d3 = new TemperatureData("sensor1", "Kho_C", "DONG", -16.0, t0 + 180000, "hash");
        AlertStateTracker.UpdateResult r3 = AlertStateTracker.update(d3, AlertUtil.Status.SAFE);
        System.out.println("t+ (Safe -16.0): " + r3.event);
        
        if (r1.event == AlertStateTracker.Event.NONE && r2.event == AlertStateTracker.Event.NONE && r3.event == AlertStateTracker.Event.RECOVERED) {
            System.out.println("=> PASS");
        } else {
            System.out.println("=> FAIL");
        }
    }

    private static void testScenarioD() {
        System.out.println("\n--- Test D: Gói đến trễ (timestamp nhỏ hơn) không làm nhảy qua lại ---");
        AlertStateTracker.forget("Kho_D");
        long t0 = System.currentTimeMillis();
        
        // Valid packet
        TemperatureData d1 = new TemperatureData("sensor1", "Kho_D", "DONG", -16.0, t0 + 10000, "hash");
        AlertStateTracker.UpdateResult r1 = AlertStateTracker.update(d1, AlertUtil.Status.SAFE);
        System.out.println("t+10s (Safe): " + r1.event);
        
        // Late packet (timestamp t0)
        TemperatureData d2 = new TemperatureData("sensor1", "Kho_D", "DONG", -10.0, t0, "hash");
        AlertStateTracker.UpdateResult r2 = AlertStateTracker.update(d2, AlertUtil.Status.WARNING);
        System.out.println("t0 (Late packet Warning): " + r2.event);
        
        if (r1.event == AlertStateTracker.Event.NONE && r2.event == AlertStateTracker.Event.NONE) {
            System.out.println("=> PASS");
        } else {
            System.out.println("=> FAIL");
        }
    }

    private static void testScenarioE() {
        System.out.println("\n--- Test E: Dải an toàn hẹp hơn 2x margin -> đóng ngay ---");
        AlertStateTracker.forget("Kho_E");
        // Narrow range: e.g. -15.5 to -15.0. Diff = 0.5 < 2*margin (1.0)
        ConfigUtil.setThresholds(-15.5, -15.0, 2.0, 8.0);
        
        long t0 = System.currentTimeMillis();
        AlertStateTracker.update(new TemperatureData("sensor1", "Kho_E", "DONG", -10.0, t0, "hash"), AlertUtil.Status.WARNING);
        AlertStateTracker.UpdateResult rAlert = AlertStateTracker.update(
                new TemperatureData("sensor1", "Kho_E", "DONG", -10.0, t0 + 150000, "hash"), AlertUtil.Status.WARNING);
        System.out.println("Setup alert: " + rAlert.event);
        
        // Temperature comes back exactly to -15.2 (inside range, but margin says we need min+margin=-15.0)
        // Since range is too narrow, it should recover immediately
        TemperatureData d1 = new TemperatureData("sensor1", "Kho_E", "DONG", -15.2, t0 + 160000, "hash");
        AlertStateTracker.UpdateResult r1 = AlertStateTracker.update(d1, AlertUtil.Status.SAFE);
        System.out.println("t+ (-15.2, inside narrow range): " + r1.event);
        
        if (rAlert.event == AlertStateTracker.Event.START && r1.event == AlertStateTracker.Event.RECOVERED) {
            System.out.println("=> PASS");
        } else {
            System.out.println("=> FAIL");
        }
    }

    private static void testScenarioF() {
        System.out.println("\n--- Test F: Chế độ bảo trì -> KHÔNG sinh cảnh báo ---");
        AlertStateTracker.forget("Kho_F");
        long t0 = System.currentTimeMillis();
        
        // Đảm bảo kho tồn tại trong DB trước khi set maintenance
        coldstorage.util.DatabaseUtil.markWarehouseActive("Kho_F", "DONG");
        // Bật bảo trì cho Kho_F trong 2 phút
        coldstorage.util.DatabaseUtil.setWarehouseMaintenance("Kho_F", t0 + 120000);
        
        // Gửi nhiệt độ vượt ngưỡng khi đang bảo trì
        TemperatureData d1 = new TemperatureData("sensor1", "Kho_F", "DONG", -10.0, t0, "hash");
        AlertStateTracker.UpdateResult r1 = AlertStateTracker.update(d1, AlertUtil.Status.WARNING);
        System.out.println("t0 (Warning - Maintenance): " + r1.event);
        
        TemperatureData d2 = new TemperatureData("sensor1", "Kho_F", "DONG", -10.0, t0 + 130000, "hash");
        // Đã qua 2 phút (t0 + 130000 > t0 + 120000), bảo trì hết hạn, cảnh báo bắt đầu tính dwell time
        AlertStateTracker.UpdateResult r2 = AlertStateTracker.update(d2, AlertUtil.Status.WARNING);
        System.out.println("t0+2m10s (Warning - Expired): " + r2.event);
        
        if (r1.event == AlertStateTracker.Event.NONE && r2.event == AlertStateTracker.Event.NONE) {
            System.out.println("=> PASS");
        } else {
            System.out.println("=> FAIL");
        }
    }
}
