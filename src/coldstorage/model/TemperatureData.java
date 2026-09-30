package coldstorage.model;

import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Đối tượng dữ liệu nhiệt độ gửi từ cảm biến (Sensor) tới Server qua UDP.
 * Định dạng chuỗi truyền đi: sensorId|warehouseName|warehouseType|temperature|timestamp|hmac
 * (5 trường đầu là phần dữ liệu "có thể ký" - xem getSignablePart(); trường cuối là chữ ký HMAC-SHA256)
 */
public class TemperatureData implements Serializable {

    private String sensorId;
    private String warehouseName;
    private String warehouseType;   // "DONG" (kho đông) hoặc "MAT" (kho mát)
    private double temperature;
    private long timestamp;         // thời gian đo, lấy từ đồng hồ cảm biến
    private String hmac;            // chữ ký HMAC-SHA256 để Server xác thực gói tin hợp lệ
    private long serverReceivedAt;  // thời gian Server THỰC SỰ nhận gói tin (gán ở phía Server, không gửi qua mạng)

    public TemperatureData(String sensorId, String warehouseName, String warehouseType,
                            double temperature, long timestamp, String hmac) {
        this.sensorId = sensorId;
        this.warehouseName = warehouseName;
        this.warehouseType = warehouseType;
        this.temperature = temperature;
        this.timestamp = timestamp;
        this.hmac = hmac;
    }

    /**
     * Phần dữ liệu dùng để TÍNH và XÁC THỰC chữ ký HMAC (không bao gồm chính chữ ký).
     * Sensor và Server đều phải tạo ra chuỗi này giống hệt nhau từ cùng 1 bộ giá trị
     * thì việc so khớp HMAC mới đúng.
     */
    public String getSignablePart() {
        return sensorId + "|" + warehouseName + "|" + warehouseType + "|" + temperature + "|" + timestamp;
    }

    /** Chuyển đối tượng thành chuỗi để gửi qua UDP */
    public String toPacketString() {
        return getSignablePart() + "|" + hmac;
    }

    /** Parse chuỗi nhận được từ UDP thành đối tượng TemperatureData */
    public static TemperatureData fromPacketString(String data) {
        String[] parts = data.split("\\|");
        if (parts.length != 6) {
            throw new IllegalArgumentException("Dữ liệu gói tin không hợp lệ (thiếu trường, có thể bị giả mạo hoặc hỏng): " + data);
        }
        String sensorId = parts[0];
        String warehouseName = parts[1];
        String warehouseType = parts[2];
        double temperature = Double.parseDouble(parts[3]);
        if (Double.isNaN(temperature) || Double.isInfinite(temperature)) {
            throw new IllegalArgumentException("Nhiệt độ không hợp lệ (NaN/vô cực): " + data);
        }
        long timestamp = Long.parseLong(parts[4]);
        String hmac = parts[5];
        return new TemperatureData(sensorId, warehouseName, warehouseType, temperature, timestamp, hmac);
    }

    public String getSensorId() { return sensorId; }
    public String getWarehouseName() { return warehouseName; }
    public String getWarehouseType() { return warehouseType; }
    public double getTemperature() { return temperature; }
    public long getTimestamp() { return timestamp; }
    public String getHmac() { return hmac; }
    public void setHmac(String hmac) { this.hmac = hmac; }
    public long getServerReceivedAt() { return serverReceivedAt; }
    public void setServerReceivedAt(long serverReceivedAt) { this.serverReceivedAt = serverReceivedAt; }

    @Override
    public String toString() {
        return "[" + warehouseName + "] " + temperature + "°C @ " + new SimpleDateFormat("HH:mm:ss").format(new Date(timestamp));
    }
}
