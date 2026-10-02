package coldstorage.util;

import coldstorage.model.TemperatureData;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phát hiện bất thường từ cảm biến THẬT:
 * 1. Kẹt cảm biến (Stuck): giá trị giống hệt liên tục >= N gói, khoảng thời gian >= M phút.
 * 2. Nhảy vọt (Jump): Tốc độ thay đổi nhiệt độ |delta T| / delta t lớn đột biến (>= K * stddev, và >= min_rate).
 */
public class SensorAnomalyDetector {

    private static class AnomalyState {
        // ---- Cho kẹt cảm biến ----
        double lastValue = Double.NaN;
        int stuckCount = 0;
        long stuckStartTime = 0;

        // ---- Cho nhảy vọt (cửa sổ tốc độ thay đổi) ----
        TemperatureData lastData = null;
        LinkedList<Double> recentRates = new LinkedList<>();
        static final int RATE_WINDOW = 10;
        
        boolean hasAnomaly = false;
    }

    private static final Map<String, AnomalyState> states = new ConcurrentHashMap<>();

    public static void update(TemperatureData data) {
        if (data == null || data.getSensorId() == null || data.getSensorId().startsWith(BackupUtil.SENSOR_PREFIX)) {
            return;
        }
        
        String wh = data.getWarehouseName();
        AnomalyState state = states.computeIfAbsent(wh, k -> new AnomalyState());
        
        boolean isStuck = checkStuck(state, data);
        boolean isJump = checkJump(state, data);

        if (isStuck || isJump) {
            state.hasAnomaly = true;
            TrendTracker.clear(wh); // Xóa trend để không nhiễu
            System.out.println("[AnomalyDetector] Cảnh báo bất thường thiết bị tại kho '" + wh + "': " + (isStuck ? "KẸT CẢM BIẾN" : "NHẢY VỌT VÔ LÝ"));
        } else {
            state.hasAnomaly = false;
        }
    }

    private static boolean checkStuck(AnomalyState state, TemperatureData data) {
        if (Double.isNaN(state.lastValue) || state.lastValue != data.getTemperature()) {
            state.lastValue = data.getTemperature();
            state.stuckCount = 1;
            state.stuckStartTime = data.getTimestamp();
            return false;
        }
        
        state.stuckCount++;
        long durationMs = data.getTimestamp() - state.stuckStartTime;
        double durationMinutes = durationMs / 60000.0;
        
        return state.stuckCount >= ConfigUtil.getAnomalyStuckMinCount() && 
               durationMinutes >= ConfigUtil.getAnomalyStuckMinutes();
    }

    private static boolean checkJump(AnomalyState state, TemperatureData data) {
        if (state.lastData == null) {
            state.lastData = data;
            return false;
        }

        long dtMs = data.getTimestamp() - state.lastData.getTimestamp();
        double dtMins = dtMs / 60000.0;
        double tempDiff = Math.abs(data.getTemperature() - state.lastData.getTemperature());
        
        state.lastData = data;

        if (dtMins <= 0.001) return false;
        
        double currentRate = tempDiff / dtMins;
        boolean isJump = false;
        
        if (state.recentRates.size() >= 3) {
            double sum = 0;
            for (Double r : state.recentRates) sum += r;
            double mean = sum / state.recentRates.size();
            
            double varSum = 0;
            for (Double r : state.recentRates) varSum += Math.pow(r - mean, 2);
            double stddev = Math.sqrt(varSum / state.recentRates.size());
            
            double k = ConfigUtil.getAnomalyJumpStddevMultiple();
            double minRate = ConfigUtil.getAnomalyJumpMinRate();
            
            if (currentRate >= minRate && currentRate >= mean + k * stddev) {
                isJump = true;
            }
        }
        
        state.recentRates.addLast(currentRate);
        if (state.recentRates.size() > AnomalyState.RATE_WINDOW) {
            state.recentRates.removeFirst();
        }
        
        return isJump;
    }

    public static boolean hasAnomaly(String warehouseName) {
        AnomalyState state = states.get(warehouseName);
        return state != null && state.hasAnomaly;
    }
}
