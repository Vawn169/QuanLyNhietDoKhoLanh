package coldstorage.backup;

import coldstorage.model.TemperatureData;
import coldstorage.util.BackupUtil;
import coldstorage.util.ConfigUtil;
import coldstorage.util.CryptoUtil;
import coldstorage.util.DatabaseUtil;
import coldstorage.util.ProductUtil;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * "Cảm biến dự phòng" cho ĐÚNG 1 kho, chạy trên 1 luồng riêng (Runnable, giống SensorClient).
 *
 * Bình thường (cảm biến chính vẫn gửi dữ liệu đều đặn) lớp này CHỈ QUAN SÁT, không gửi gì cả.
 * Khi cảm biến chính im lặng quá ngưỡng (xem MIN_OFFLINE_MS/OFFLINE_FACTOR - tính theo chu kỳ gửi thực tế), nó coi như cảm biến chính đã hỏng và
 * tự "tiếp quản": gửi các gói tin nhiệt độ ƯỚC TÍNH, kéo dần từ nhiệt độ đo được lần cuối về
 * TRUNG ĐIỂM của dải an toàn hiện hành (ProductUtil.getSafeRange - tự cập nhật nếu quản trị
 * viên đổi ngưỡng/sản phẩm sau đó), rồi giữ ổn định quanh đó cho tới khi cảm biến chính có tín
 * hiệu trở lại.
 *
 * Gói tin dự phòng dùng sensorId có tiền tố BackupUtil.SENSOR_PREFIX ("BACKUP-") và vẫn được
 * ký HMAC-SHA256 như cảm biến thật (đọc cùng shared.secret từ config.properties), nên đi qua
 * đúng luồng bảo mật / lưu database hiện có ở Server - Server chỉ cần nhận diện tiền tố này để
 * hiển thị rõ "🔧 DỰ PHÒNG" thay vì "AN TOÀN"/"MẤT TÍN HIỆU" (xem MonitorServer).
 *
 * LƯU Ý QUAN TRỌNG: đây là giải pháp PHẦN MỀM (ước tính để dashboard/log không bị "trống",
 * giúp người vận hành biết hệ thống đang bù tạm) - KHÔNG thay thế việc sửa/thay cảm biến vật
 * lý đã hỏng, và KHÔNG điều khiển được thiết bị làm lạnh thật (dự án này chỉ mô phỏng).
 */
public class BackupSensorClient implements Runnable {

    private static final int INTERVAL_SAMPLES = 8;               // số gói gần nhất dùng để ước lượng chu kỳ
    private static final long MAX_INTERVAL_SAMPLE_MS = 10 * 60_000; // khoảng cách lớn hơn = khoảng lặng, không phải chu kỳ

    private final String warehouseName;
    private final String warehouseType;
    private final String serverHost;
    private final int serverPort;
    private final Random random = new Random();

    private InetAddress address = null;
    private volatile boolean running = true;
    private boolean backupActive = false;
    private boolean maintenanceLogged = false;
    private Double backupTemp = null;

    public BackupSensorClient(String warehouseName, String warehouseType, String serverHost, int serverPort) {
        this.warehouseName = warehouseName;
        this.warehouseType = warehouseType;
        this.serverHost = serverHost;
        this.serverPort = serverPort;
    }

    public String getWarehouseName() {
        return warehouseName;
    }

    public void stop() {
        running = false;
    }

    private volatile Thread thread;

    /** Gắn luồng đang chạy client này để BackupLauncher biết luồng đã tự dừng (kho bị xóa) hay còn sống */
    public void attach(Thread t) {
        this.thread = t;
    }

    public boolean isAlive() {
        Thread t = thread;
        return t != null && t.isAlive();
    }

    @Override
    public void run() {
        String sensorId = BackupUtil.backupSensorId(warehouseName);

        while (running) {
            try {
                if (!pollOnce(sensorId)) {
                    return; // kho đã bị xóa thật
                }
            } catch (Exception inner) {
                // Lỗi ở 1 vòng (mạng, DB đang bị khóa...) không được làm chết hẳn luồng dự phòng, cũng không được
                // coi là "kho đã xóa" hay "cảm biến chính im lặng": bỏ qua vòng này, giữ nguyên trạng thái, thử lại
                System.err.println("[Dự phòng-" + warehouseName + "] Lỗi ở 1 vòng lặp, giữ nguyên trạng thái và thử lại: " + inner.getMessage());
            }

            try {
                Thread.sleep(coldstorage.util.ConfigUtil.getBackupPollSec() * 1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** @return false nếu phải DỪNG HẲN luồng (kho đã bị xóa thật); true nếu tiếp tục */
    private boolean pollOnce(String sensorId) throws Exception {
        // Chỉ dừng khi CHẮC CHẮN kho đã bị xóa. UNKNOWN (không đọc được DB) thì không kết luận gì, bỏ qua vòng này.
        DatabaseUtil.WarehouseState state = DatabaseUtil.getWarehouseState(warehouseName);
        if (state == DatabaseUtil.WarehouseState.INACTIVE) {
            System.out.println("[Dự phòng-" + warehouseName + "] Kho đã bị xóa khỏi hệ thống, dừng hẳn theo dõi dự phòng.");
            return false;
        }
        if (state == DatabaseUtil.WarehouseState.UNKNOWN) {
            return true;
        }

        // Kiểm tra bảo trì: nếu kho đang bảo trì, không kích hoạt dự phòng,
        // và nếu dự phòng ĐANG chạy thì dừng ngay.
        Long maint = DatabaseUtil.getWarehouseMaintenance(warehouseName);
        if (maint != null && System.currentTimeMillis() < maint) {
            if (backupActive) {
                System.out.println("[Dự phòng-" + warehouseName + "] Kho đang bảo trì, dừng chế độ dự phòng.");
                backupActive = false;
                backupTemp = null;
            } else if (!maintenanceLogged) {
                System.out.println("[Dự phòng-" + warehouseName + "] Kho đang bảo trì, bỏ qua dự phòng.");
                maintenanceLogged = true;
            }
            return true;
        }
        maintenanceLogged = false; // reset để log lại nếu bảo trì được bật lần tiếp theo

        // Ném SQLException khi lỗi -> run() bỏ qua vòng này (KHÔNG rơi vào nhánh "chưa từng có dữ liệu chính")
        List<TemperatureData> recent = DatabaseUtil.getRecentPrimaryLogs(
                warehouseName, BackupUtil.SENSOR_PREFIX, INTERVAL_SAMPLES + 1);
        TemperatureData lastPrimary = recent.isEmpty() ? null : recent.get(0);
        long now = System.currentTimeMillis();
        long threshold = offlineThresholdMs(recent);
        boolean primarySilent = lastPrimary == null || now - lastPrimary.getTimestamp() > threshold;

        if (!primarySilent) {
            if (backupActive) {
                System.out.println("[Dự phòng-" + warehouseName + "] Cảm biến chính đã có tín hiệu trở lại, dừng chế độ dự phòng.");
            }
            backupActive = false;
            backupTemp = null;
            return true;
        }

        if (!backupActive) {
            backupTemp = lastPrimary != null ? lastPrimary.getTemperature() : defaultStart();
            System.out.println("[Dự phòng-" + warehouseName + "] Cảm biến chính im lặng > "
                    + (threshold / 1000) + "s -> TIẾP QUẢN, bắt đầu từ " + round1(backupTemp) + "°C.");
        }
        backupActive = true;

        double[] range = coldstorage.util.ProductUtil.getSafeRange(warehouseName, warehouseType);
        double target = (range[0] + range[1]) / 2.0;
        backupTemp += (target - backupTemp) * coldstorage.util.ConfigUtil.getBackupPullback() + (random.nextDouble() - 0.5) * coldstorage.util.ConfigUtil.getBackupNoise();

        if (address == null) {
            address = InetAddress.getByName(serverHost);
        }
        try (DatagramSocket socket = new DatagramSocket()) {
            TemperatureData data = new TemperatureData(
                    sensorId, warehouseName, warehouseType, round1(backupTemp), now, null);
            String signature = CryptoUtil.computeHmac(data.getSignablePart(), ConfigUtil.getSharedSecret());
            data.setHmac(signature);

            byte[] buffer = data.toPacketString().getBytes(StandardCharsets.UTF_8);
            socket.send(new DatagramPacket(buffer, buffer.length, address, serverPort));
            System.out.println("[Dự phòng-" + warehouseName + "] Đã gửi ước tính: " + data);
        }
        return true;
    }

    /** Ngưỡng im lặng theo chu kỳ gửi thực tế (trung vị các khoảng cách giữa gói gần nhất, mới -> cũ); không đủ dữ liệu -> MIN */
    static long offlineThresholdMs(List<TemperatureData> recentNewestFirst) {
        List<Long> deltas = new ArrayList<>();
        for (int i = 0; i + 1 < recentNewestFirst.size(); i++) {
            long d = recentNewestFirst.get(i).getTimestamp() - recentNewestFirst.get(i + 1).getTimestamp();
            if (d > 0 && d <= MAX_INTERVAL_SAMPLE_MS) deltas.add(d);
        }
        if (deltas.isEmpty()) return coldstorage.util.ConfigUtil.getBackupOfflineMinSec() * 1000;
        Collections.sort(deltas);
        long median = deltas.get(deltas.size() / 2);
        return Math.max(coldstorage.util.ConfigUtil.getBackupOfflineMinSec() * 1000, 
            (long)(coldstorage.util.ConfigUtil.getBackupOfflineFactor() * median));
    }

    private double defaultStart() {
        return "DONG".equalsIgnoreCase(warehouseType) ? -17.0 : 5.0;
    }

    private double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
