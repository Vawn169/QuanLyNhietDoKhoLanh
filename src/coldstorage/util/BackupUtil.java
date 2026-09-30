package coldstorage.util;

import coldstorage.model.TemperatureData;

/**
 * Quy ước dùng chung giữa Server (coldstorage.server) và chương trình cảm biến dự phòng
 * (coldstorage.backup) để nhận ra đâu là gói tin ƯỚC TÍNH (do BackupSensorClient tự sinh khi
 * cảm biến thật mất tín hiệu) và đâu là gói tin từ cảm biến THẬT.
 *
 * Cách nhận diện: gói dự phòng dùng sensorId có tiền tố SENSOR_PREFIX. Gói dự phòng vẫn được
 * ký HMAC như cảm biến thật (đi qua đúng luồng bảo mật hiện có ở SensorHandler/SecurityUtil),
 * nên đây chỉ là 1 quy ước hiển thị/lọc dữ liệu, KHÔNG phải 1 cơ chế bảo mật riêng.
 */
public final class BackupUtil {

    public static final String SENSOR_PREFIX = "BACKUP-";

    private BackupUtil() {
    }

    /** true nếu bản ghi này do bộ dự phòng gửi (ước tính), không phải cảm biến thật */
    public static boolean isBackupSource(TemperatureData data) {
        return data != null && data.getSensorId() != null && data.getSensorId().startsWith(SENSOR_PREFIX);
    }

    /**
     * sensorId dùng cho gói dự phòng của 1 kho: BACKUP-&lt;phần đọc được&gt;-&lt;băm của TÊN ĐẦY ĐỦ&gt;.
     * Phần đọc được chỉ để người xem log nhận ra kho (bỏ dấu tiếng Việt, ký tự lạ -> '_', tối đa 12 ký tự);
     * phần băm SHA-256 của tên đầy đủ đảm bảo 2 kho tên gần giống nhau (cắt ngắn/đổi dấu ra cùng chuỗi) KHÔNG trùng mã -
     * trùng mã sẽ làm chống replay (theo sensorId) loại nhầm gói của kho này vì timestamp của kho kia.
     * Kết quả không chứa '|' và ổn định giữa các lần chạy.
     */
    public static String backupSensorId(String warehouseName) {
        String name = warehouseName == null ? "" : warehouseName;
        String readable = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replace('đ', 'd').replace('Đ', 'D')
                .replaceAll("[^A-Za-z0-9_.\\-]", "_");
        if (readable.length() > 12) readable = readable.substring(0, 12);
        return SENSOR_PREFIX + readable + "-" + shortHash(name);
    }

    private static String shortHash(String s) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 5; i++) sb.append(String.format("%02x", d[i] & 0xff)); // 40 bit
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 không khả dụng", e);
        }
    }
}
