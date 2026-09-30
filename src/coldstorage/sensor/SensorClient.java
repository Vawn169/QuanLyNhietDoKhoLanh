package coldstorage.sensor;

import coldstorage.model.TemperatureData;
import coldstorage.util.ConfigUtil;
import coldstorage.util.CryptoUtil;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

/**
 * Mô phỏng 1 cảm biến nhiệt độ trong 1 kho lạnh.
 * Chạy trên luồng riêng (implements Runnable) để có thể mô phỏng NHIỀU cảm biến song song.
 *
 * KIẾN TRÚC: SensorClient CHỈ chịu trách nhiệm đóng gói dữ liệu (ký HMAC) và gửi UDP định kỳ.
 * Việc "nhiệt độ tiếp theo là bao nhiêu" được giao hoàn toàn cho 1 TemperatureBehavior
 * (Strategy pattern) - cho phép thay đổi cách mô phỏng (hệ thống làm lạnh có sự cố ngẫu nhiên,
 * dao động ngẫu nhiên, luôn lỗi...) mà không cần sửa logic mạng ở đây.
 *
 * Khóa HMAC lấy từ ConfigUtil (config.properties, khóa shared.secret) - không viết cứng trong mã cảm biến.
 */
public class SensorClient implements Runnable {

    private final String sensorId;
    private final String warehouseName;
    private final String warehouseType; // "DONG" hoặc "MAT"
    private final String serverHost;
    private final int serverPort;
    private final int intervalMs;
    private final TemperatureBehavior behavior;

    private double currentTemp;
    private volatile boolean running = true;

    /**
     * Khởi tạo cảm biến với hành vi TỰ CHỌN (linh hoạt nhất - ví dụ dùng AlwaysFaultyBehavior
     * để mô phỏng thiết bị hỏng, hoặc tự lắp ghép 1 tổ hợp behavior khác).
     */
    public SensorClient(String sensorId, String warehouseName, String warehouseType,
                         double startTemp, String serverHost, int serverPort, int intervalMs,
                         TemperatureBehavior behavior) {
        this.sensorId = sensorId;
        this.warehouseName = warehouseName;
        this.warehouseType = warehouseType;
        this.currentTemp = startTemp;
        this.serverHost = serverHost;
        this.serverPort = serverPort;
        this.intervalMs = intervalMs;
        this.behavior = behavior;
    }

    /**
     * Khởi tạo với hành vi MẶC ĐỊNH: hệ thống làm lạnh vận hành quanh nhiệt độ khởi đầu (startTemp chính là
     * nhiệt độ vận hành/setpoint), thỉnh thoảng có sự cố trôi dạt rồi được xử lý (xem CoolingSystemBehavior).
     */
    public SensorClient(String sensorId, String warehouseName, String warehouseType,
                         double startTemp, String serverHost, int serverPort, int intervalMs) {
        this(sensorId, warehouseName, warehouseType, startTemp, serverHost, serverPort, intervalMs,
                new CoolingSystemBehavior(startTemp));
    }

    public String getSensorId() { return sensorId; }
    public String getWarehouseName() { return warehouseName; }
    public String getWarehouseType() { return warehouseType; }

    @Override
    public void run() {
        try (DatagramSocket socket = new DatagramSocket()) {
            InetAddress address = null;
            System.out.println("[" + sensorId + "] Bắt đầu gửi dữ liệu tới " + serverHost + ":" + serverPort);

            while (running) {
                long now = System.currentTimeMillis();
                currentTemp = behavior.nextTemperature(currentTemp, now);

                String note = behavior.getStatusNote();
                if (note != null) {
                    System.out.println("[" + sensorId + "] " + note);
                }

                // Lỗi mạng thoáng qua (không phân giải được host, gửi thất bại...) KHÔNG được làm chết luồng cảm biến:
                // ghi nhận rồi thử lại ở lần đo kế tiếp.
                try {
                    if (address == null) {
                        address = InetAddress.getByName(serverHost);
                    }
                    TemperatureData data = new TemperatureData(
                            sensorId, warehouseName, warehouseType, round1(currentTemp), now, null);

                    // Ký chữ ký HMAC-SHA256 trên phần dữ liệu, rồi gắn vào gói tin
                    String signature = CryptoUtil.computeHmac(data.getSignablePart(), ConfigUtil.getSharedSecret());
                    data.setHmac(signature);

                    byte[] buffer = data.toPacketString().getBytes(StandardCharsets.UTF_8);
                    socket.send(new DatagramPacket(buffer, buffer.length, address, serverPort));
                    System.out.println("[" + sensorId + "] Đã gửi: " + data);
                } catch (IOException e) {
                    address = null; // phân giải lại ở lần sau
                    System.err.println("[" + sensorId + "] Lỗi gửi gói tin, sẽ thử lại ở lần đo sau: " + e.getMessage());
                }
                Thread.sleep(intervalMs);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            System.err.println("[" + sensorId + "] Lỗi: " + e.getMessage());
        }
    }

    private double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    public void stop() {
        running = false;
    }
}
