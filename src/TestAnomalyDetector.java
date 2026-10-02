import coldstorage.model.TemperatureData;
import coldstorage.util.SensorAnomalyDetector;
import coldstorage.util.ConfigUtil;

public class TestAnomalyDetector {
    public static void main(String[] args) throws Exception {
        System.out.println("=== TEST 1: Kẹt cảm biến (Stuck) - >10 phút ===");
        testStuckDetector(true);

        System.out.println("\n=== TEST 2: Kẹt cảm biến (Stuck) - <10 phút ===");
        testStuckDetector(false);

        System.out.println("\n=== TEST 3: Nhảy vọt (Jump) ===");
        testJumpDetector(true);

        System.out.println("\n=== TEST 4: Nhiễu tự nhiên (Natural variance) ===");
        testJumpDetector(false);
    }

    private static void testStuckDetector(boolean waitLongEnough) {
        String wh = "WH_StuckTest_" + waitLongEnough;
        long startTime = 1700000000000L;
        
        long interval = waitLongEnough ? (2 * 60 * 1000L + 1000) : (15 * 1000L); // 2min1s vs 15s

        for (int i = 0; i < 6; i++) {
            long t = startTime + i * interval;
            TemperatureData data = new TemperatureData("SENS-1", wh, "DONG", -18.5, t, null);
            SensorAnomalyDetector.update(data);
            System.out.println("Gói " + (i+1) + " lúc " + (t - startTime)/1000 + "s -> Anomaly = " + SensorAnomalyDetector.hasAnomaly(wh));
        }

        boolean hasAnomaly = SensorAnomalyDetector.hasAnomaly(wh);
        if (waitLongEnough && hasAnomaly) {
            System.out.println("=> PASS (Phát hiện thành công)");
        } else if (!waitLongEnough && !hasAnomaly) {
            System.out.println("=> PASS (Chưa đủ thời gian, không báo sai)");
        } else {
            System.out.println("=> FAIL");
        }
    }

    private static void testJumpDetector(boolean hasJump) {
        String wh = "WH_JumpTest_" + hasJump;
        long t = 1700000000000L;
        
        // Chuỗi ổn định ban đầu (5 gói cách nhau 2 phút)
        double[] stableTemps = {-18.0, -18.1, -17.9, -18.0, -18.2};
        for (int i = 0; i < stableTemps.length; i++) {
            TemperatureData data = new TemperatureData("SENS-1", wh, "DONG", stableTemps[i], t, null);
            SensorAnomalyDetector.update(data);
            t += 120000L; // 2 phút
        }
        
        System.out.println("Sau chuỗi ổn định -> Anomaly = " + SensorAnomalyDetector.hasAnomaly(wh));

        // Gói tiếp theo
        double nextTemp = hasJump ? -5.0 : -17.8; 
        // -5.0 -> jump of 13 degrees in 2 mins = 6.5 C/min
        // -17.8 -> jump of 0.4 degrees in 2 mins = 0.2 C/min
        TemperatureData nextData = new TemperatureData("SENS-1", wh, "DONG", nextTemp, t, null);
        SensorAnomalyDetector.update(nextData);
        
        boolean hasAnomaly = SensorAnomalyDetector.hasAnomaly(wh);
        System.out.println("Gói cuối cùng (" + nextTemp + ") -> Anomaly = " + hasAnomaly);

        if (hasJump && hasAnomaly) {
            System.out.println("=> PASS (Phát hiện nhảy vọt thành công)");
        } else if (!hasJump && !hasAnomaly) {
            System.out.println("=> PASS (Dao động tự nhiên, không báo sai)");
        } else {
            System.out.println("=> FAIL");
        }
    }
}
