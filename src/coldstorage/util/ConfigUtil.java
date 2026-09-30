package coldstorage.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Lưu và cung cấp cấu hình của hệ thống, có thể chỉnh động qua AdminPanel:
 *  - Ngưỡng nhiệt độ an toàn theo loại kho (dong.min/max, mat.min/max).
 *  - Khóa bí mật HMAC dùng chung giữa Sensor và Server (shared.secret) - không còn viết cứng trong mã cảm biến.
 *  - Danh sách IP được phép gửi dữ liệu (allowed.ips, cách nhau bằng dấu phẩy) - mặc định chỉ máy local.
 * Giá trị được ghi ra file config.properties nên vẫn còn sau khi khởi động lại Server.
 */
public class ConfigUtil {

    private static final String CONFIG_FILE = "config.properties";

    /** Khóa mặc định khi config.properties không khai báo shared.secret (nên đổi khi triển khai thật) */
    public static final String DEFAULT_SECRET = "COLDSTORAGE-2026-SECRET";
    private static final String DEFAULT_ALLOWED_IPS = "127.0.0.1,0:0:0:0:0:0:0:1";

    private static volatile double dongMin = -18.0;
    private static volatile double dongMax = -15.0;
    private static volatile double matMin = 2.0;
    private static volatile double matMax = 8.0;
    private static volatile String sharedSecret = DEFAULT_SECRET;
    private static volatile Set<String> allowedIps = parseIps(DEFAULT_ALLOWED_IPS);

    // ----- Telegram (thông báo đẩy thật) -----
    private static volatile boolean telegramEnabled = false;
    private static volatile String telegramBotToken = "";
    private static volatile String telegramChatId = "";

    // ----- Mật khẩu Quản trị (SHA-256 + salt ngẫu nhiên, KHÔNG lưu plaintext) -----
    private static volatile String adminPasswordHash = ""; // rỗng = chưa đặt mật khẩu, ai cũng vào được (như trước)
    private static volatile String adminPasswordSalt = "";

    static {
        load();
    }

    public static double getDongMin() { return dongMin; }
    public static double getDongMax() { return dongMax; }
    public static double getMatMin()  { return matMin; }
    public static double getMatMax()  { return matMax; }
    public static String getSharedSecret() { return sharedSecret; }
    public static Set<String> getAllowedIps() { return allowedIps; }

    public static boolean isTelegramEnabled() { return telegramEnabled; }
    public static String getTelegramBotToken() { return telegramBotToken; }
    public static String getTelegramChatId() { return telegramChatId; }

    /** Lưu cấu hình Telegram. enabled=false thì AlertNotifier sẽ không gọi Telegram API nữa. */
    public static synchronized void setTelegramConfig(boolean enabled, String botToken, String chatId) {
        telegramEnabled = enabled;
        telegramBotToken = botToken == null ? "" : botToken.trim();
        telegramChatId = chatId == null ? "" : chatId.trim();
        Map<String, String> m = new LinkedHashMap<>();
        m.put("telegram.enabled", String.valueOf(telegramEnabled));
        m.put("telegram.bot.token", telegramBotToken);
        m.put("telegram.chat.id", telegramChatId);
        saveProperties(m);
    }

    public static boolean isAdminPasswordSet() { return !adminPasswordHash.isEmpty(); }

    /** true nếu mật khẩu khớp (hoặc chưa từng đặt mật khẩu - coi như luôn cho qua để tương thích ngược) */
    public static boolean checkAdminPassword(String plainPassword) {
        if (!isAdminPasswordSet()) return true;
        String hash = hashWithSalt(plainPassword == null ? "" : plainPassword, adminPasswordSalt);
        return hash.equals(adminPasswordHash);
    }

    /** Đặt (hoặc đổi) mật khẩu Quản trị. salt mới sinh ngẫu nhiên mỗi lần đổi. */
    public static synchronized void setAdminPassword(String plainPassword) {
        adminPasswordSalt = randomSalt();
        adminPasswordHash = hashWithSalt(plainPassword, adminPasswordSalt);
        Map<String, String> m = new LinkedHashMap<>();
        m.put("admin.password.hash", adminPasswordHash);
        m.put("admin.password.salt", adminPasswordSalt);
        saveProperties(m);
    }

    /** 16 byte ngẫu nhiên (mã hoá Base64) dùng làm salt cho mỗi lần đặt mật khẩu */
    private static String randomSalt() {
        byte[] salt = new byte[16];
        new java.security.SecureRandom().nextBytes(salt);
        return java.util.Base64.getEncoder().encodeToString(salt);
    }

    /** SHA-256(salt + mật khẩu), KHÔNG bao giờ lưu mật khẩu gốc (plaintext) ra file */
    private static String hashWithSalt(String plainPassword, String salt) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((salt + plainPassword).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.Base64.getEncoder().encodeToString(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 không khả dụng trên máy này", e);
        }
    }

    /**
     * Đặt ngưỡng mới và ghi ra file.
     * @throws IllegalArgumentException nếu giá trị không hợp lệ (NaN/vô cực, hoặc min >= max)
     */
    public static synchronized void setThresholds(double dMin, double dMax, double mMin, double mMax) {
        validate(dMin, dMax, mMin, mMax);
        dongMin = dMin;
        dongMax = dMax;
        matMin = mMin;
        matMax = mMax;
        save();
    }

    private static void validate(double dMin, double dMax, double mMin, double mMax) {
        for (double v : new double[]{dMin, dMax, mMin, mMax}) {
            if (Double.isNaN(v) || Double.isInfinite(v)) {
                throw new IllegalArgumentException("Nhiệt độ không hợp lệ.");
            }
        }
        if (dMin >= dMax || mMin >= mMax) {
            throw new IllegalArgumentException("Giá trị tối thiểu phải nhỏ hơn tối đa.");
        }
    }

    private static Set<String> parseIps(String csv) {
        Set<String> result = new LinkedHashSet<>();
        for (String s : Arrays.asList(csv.split(","))) {
            String ip = s.trim();
            if (!ip.isEmpty()) result.add(ip);
        }
        return Collections.unmodifiableSet(result);
    }

    /**
     * Đọc lại config.properties (BackupLauncher gọi định kỳ: Server đổi ngưỡng ở tiến trình khác thì tiến trình này mới biết).
     * Nếu không đọc được file hoặc giá trị hỏng thì GIỮ giá trị đang dùng.
     */
    public static synchronized void reload() {
        load();
    }

    private static void load() {
        File f = new File(CONFIG_FILE);
        if (!f.exists()) return;
        Properties p = new Properties();
        try (FileInputStream in = new FileInputStream(f)) {
            p.load(in);
        } catch (IOException e) {
            System.err.println("[Config] Không đọc được " + CONFIG_FILE + ", dùng cấu hình mặc định: " + e.getMessage());
            return;
        }

        String secret = p.getProperty("shared.secret");
        if (secret != null && !secret.trim().isEmpty()) sharedSecret = secret.trim();
        String ips = p.getProperty("allowed.ips");
        if (ips != null && !ips.trim().isEmpty()) allowedIps = parseIps(ips);

        telegramEnabled = Boolean.parseBoolean(p.getProperty("telegram.enabled", "false").trim());
        telegramBotToken = p.getProperty("telegram.bot.token", "").trim();
        telegramChatId = p.getProperty("telegram.chat.id", "").trim();
        adminPasswordHash = p.getProperty("admin.password.hash", "").trim();
        adminPasswordSalt = p.getProperty("admin.password.salt", "").trim();

        // Đọc + kiểm tra ĐỦ 4 giá trị trước rồi mới gán, tránh trạng thái nửa vời nếu 1 giá trị hỏng
        try {
            double dMin = Double.parseDouble(p.getProperty("dong.min", String.valueOf(dongMin)).trim());
            double dMax = Double.parseDouble(p.getProperty("dong.max", String.valueOf(dongMax)).trim());
            double mMin = Double.parseDouble(p.getProperty("mat.min", String.valueOf(matMin)).trim());
            double mMax = Double.parseDouble(p.getProperty("mat.max", String.valueOf(matMax)).trim());
            validate(dMin, dMax, mMin, mMax);
            dongMin = dMin;
            dongMax = dMax;
            matMin = mMin;
            matMax = mMax;
        } catch (IllegalArgumentException e) { // gồm cả NumberFormatException
            System.err.println("[Config] Ngưỡng trong " + CONFIG_FILE + " không hợp lệ, dùng ngưỡng mặc định: " + e.getMessage());
        }
    }

    private static void save() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("dong.min", String.valueOf(dongMin));
        m.put("dong.max", String.valueOf(dongMax));
        m.put("mat.min", String.valueOf(matMin));
        m.put("mat.max", String.valueOf(matMax));
        saveProperties(m);
    }

    /** Đọc file hiện có, ghi đè/thêm các khóa trong {@code updates}, rồi lưu lại - KHÔNG làm mất các khóa khác */
    private static synchronized void saveProperties(Map<String, String> updates) {
        Properties p = new Properties();
        File f = new File(CONFIG_FILE);
        if (f.exists()) {
            try (FileInputStream in = new FileInputStream(f)) {
                p.load(in);
            } catch (IOException ignored) {
                // ghi đè bằng các khóa trong updates bên dưới
            }
        }
        for (Map.Entry<String, String> e : updates.entrySet()) {
            p.setProperty(e.getKey(), e.getValue());
        }
        try (FileOutputStream out = new FileOutputStream(CONFIG_FILE)) {
            p.store(out, "Cau hinh he thong giam sat kho lanh");
        } catch (IOException e) {
            System.err.println("[Config] Lỗi ghi " + CONFIG_FILE + ": " + e.getMessage());
        }
    }
}
