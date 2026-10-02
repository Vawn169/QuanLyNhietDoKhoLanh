package coldstorage.util;

import coldstorage.model.Product;
import coldstorage.model.TemperatureData;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.HashSet;
import java.util.Set;

/**
 * Quản lý kết nối và thao tác với SQLite database.
 * Cần thêm thư viện sqlite-jdbc vào project NetBeans (xem
 * HUONG_DAN_NETBEANS.txt).
 *
 * FIX #6: SQLite chỉ cho phép 1 tiến trình ghi tại 1 thời điểm; việc mở 1
 * Connection mới
 * cho MỖI lần insert/query (như bản cũ) vừa tốn tài nguyên vừa dễ gây lỗi
 * "database is locked"
 * khi nhiều luồng ghi cùng lúc. Bản sửa này dùng LẠI một Connection dùng chung
 * (singleton),
 * mọi thao tác ghi được đồng bộ hoá (synchronized) để đảm bảo an toàn khi nhiều
 * luồng gọi.
 */
public class DatabaseUtil {

    private static final String DB_URL = "jdbc:sqlite:coldstorage.db";
    private static Connection sharedConnection;

    /** Lấy connection dùng chung, tự mở lại nếu đã bị đóng */
    private static synchronized Connection getConnection() throws SQLException {
        if (sharedConnection == null || sharedConnection.isClosed()) {
            sharedConnection = DriverManager.getConnection(DB_URL);
            // SQLite MẶC ĐỊNH TẮT khóa ngoại - phải bật cho từng kết nối thì FOREIGN KEY
            // mới có tác dụng
            try (Statement st = sharedConnection.createStatement()) {
                st.execute("PRAGMA foreign_keys = ON");
                // Server và BackupLauncher là 2 tiến trình cùng mở 1 file .db: chờ tối đa 8s
                // khi file đang bị khóa ghi
                // thay vì báo lỗi ngay (lỗi thoáng qua không được coi là "kho đã xóa"/"cảm biến
                // chính im lặng").
                st.execute("PRAGMA busy_timeout = 8000");
            }
        }
        return sharedConnection;
    }

    /**
     * Gọi 1 lần khi Server khởi động để tạo bảng nếu chưa có (và tự nâng cấp cấu
     * trúc cũ nếu cần)
     */
    public static synchronized void initDatabase() {
        String sql = "CREATE TABLE IF NOT EXISTS temperature_log (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "sensor_id TEXT," +
                "warehouse_name TEXT," +
                "warehouse_type TEXT," +
                "temperature REAL," +
                "status TEXT," +
                "log_time TEXT," +
                "log_time_millis INTEGER)";
        // Index: tránh quét toàn bảng khi lọc theo kho / đếm theo trạng thái (bảng tăng
        // hàng chục nghìn dòng mỗi ngày)
        String idxWarehouse = "CREATE INDEX IF NOT EXISTS idx_log_warehouse ON temperature_log(warehouse_name, id)";
        String idxStatus = "CREATE INDEX IF NOT EXISTS idx_log_status ON temperature_log(status)";
        // Bảng danh mục sản phẩm (mỗi sản phẩm có ngưỡng nhiệt độ an toàn riêng)
        String productSql = "CREATE TABLE IF NOT EXISTS product (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "name TEXT NOT NULL UNIQUE COLLATE NOCASE," +
                "min_temp REAL NOT NULL," +
                "max_temp REAL NOT NULL)";
        // Bảng KHO (thực thể trung tâm, thay cho warehouse_registry cũ).
        // active = 1: kho đã được cảm biến THẬT gửi dữ liệu và chưa bị xóa.
        // active = 0: kho đã bị xóa khỏi hệ thống (hoặc mới được gán sản phẩm, chưa có
        // cảm biến nào gửi).
        // Kho bị xóa vẫn GIỮ dòng này (xóa mềm) để BackupLauncher và bước nạp lịch sử
        // biết mà không hồi sinh nó.
        String warehouseSql = "CREATE TABLE IF NOT EXISTS warehouse (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "name TEXT NOT NULL UNIQUE," +
                "type TEXT," +
                "active INTEGER NOT NULL DEFAULT 0," +
                "updated_at INTEGER)";
        // Bảng nối nhiều-nhiều: kho nào đang chứa những sản phẩm nào (khóa ngoại thật
        // tới warehouse và product)
        String warehouseProductSql = "CREATE TABLE IF NOT EXISTS warehouse_product (" +
                "warehouse_id INTEGER NOT NULL," +
                "product_id INTEGER NOT NULL," +
                "PRIMARY KEY (warehouse_id, product_id)," +
                "FOREIGN KEY (warehouse_id) REFERENCES warehouse(id) ON DELETE CASCADE," +
                "FOREIGN KEY (product_id) REFERENCES product(id) ON DELETE CASCADE)";
        // Lịch sử sự cố: mỗi dòng là 1 lần cảnh báo của 1 kho, từ lúc BẮT ĐẦU
        // (started_at) tới lúc
        // HỒI PHỤC (ended_at). ended_at = NULL nghĩa là sự cố đang diễn ra (chưa hồi
        // phục).
        // Ghi trực tiếp theo AlertStateTracker.Event.START / RECOVERED, không suy luận
        // lại từ temperature_log.
        String alertHistorySql = "CREATE TABLE IF NOT EXISTS alert_history (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "warehouse_name TEXT NOT NULL," +
                "started_at INTEGER NOT NULL," +
                "ended_at INTEGER," +
                "peak_temp REAL)";
        // Bảng lưu thống kê tổng hợp theo giờ, giảm thiểu quét bảng log
        String hourlyStatsSql = "CREATE TABLE IF NOT EXISTS hourly_stats (" +
                "warehouse_name TEXT NOT NULL," +
                "hour_bucket INTEGER NOT NULL," +
                "count_total INTEGER NOT NULL DEFAULT 0," +
                "count_warning INTEGER NOT NULL DEFAULT 0," +
                "sum_temp REAL NOT NULL DEFAULT 0," +
                "min_temp REAL," +
                "max_temp REAL," +
                "PRIMARY KEY (warehouse_name, hour_bucket))";
        String idxAlertOpen = "CREATE INDEX IF NOT EXISTS idx_alert_open ON alert_history(warehouse_name, ended_at)";
        String rejectedLogSql = "CREATE TABLE IF NOT EXISTS rejected_log (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "timestamp INTEGER NOT NULL, " +
                "source_ip TEXT, " +
                "reason TEXT NOT NULL, " +
                "sensor_id TEXT)";

        try (Statement stmt = getConnection().createStatement()) {
            stmt.execute(sql);
            stmt.execute(idxWarehouse);
            stmt.execute(idxStatus);
            stmt.execute(productSql);
            stmt.execute(warehouseSql);

            // QUAN TRỌNG: mọi CREATE TABLE phải chạy xong hết TRƯỚC khi đụng tới ALTER
            // TABLE/columnExists
            // của bất kỳ bảng nào trong số đó. Trên database HOÀN TOÀN MỚI (chưa từng có
            // alert_history),
            // nếu ALTER chạy trước CREATE, SQLite ném "no such table", catch bên dưới sẽ
            // bắt lỗi đó và
            // BỎ QUA toàn bộ phần code còn lại trong khối try này - nghĩa là
            // warehouse_product và
            // alert_history sẽ không bao giờ được tạo. Lỗi này không lộ ra trên database cũ
            // đã có sẵn
            // 2 bảng đó từ trước, nên rất dễ lọt qua kiểm thử nếu không thử trên 1 database
            // rỗng.
            migrateToWarehouseTable(warehouseProductSql);
            stmt.execute(warehouseProductSql);
            stmt.execute(alertHistorySql);
            stmt.execute(hourlyStatsSql);
            stmt.execute(rejectedLogSql);
            stmt.execute(idxAlertOpen);

            Connection c = getConnection();

            // Backfill hourly_stats
            try (Statement checkStmt1 = c.createStatement();
                    Statement checkStmt2 = c.createStatement();
                    ResultSet rsCheck = checkStmt1.executeQuery("SELECT COUNT(*) FROM hourly_stats");
                    ResultSet rsLog = checkStmt2.executeQuery("SELECT COUNT(*) FROM temperature_log")) {
                boolean statsEmpty = rsCheck.next() && rsCheck.getInt(1) == 0;
                boolean logHasData = rsLog.next() && rsLog.getInt(1) > 0;

                if (statsEmpty && logHasData) {
                    System.out.println("[DB] Đang backfill dữ liệu cho bảng hourly_stats...");
                    c.setAutoCommit(false);
                    try {
                        String backfill = "INSERT INTO hourly_stats (warehouse_name, hour_bucket, count_total, count_warning, sum_temp, min_temp, max_temp) "
                                +
                                "SELECT warehouse_name, (log_time_millis / 3600000) * 3600000 AS hour_bucket, " +
                                "COUNT(*), SUM(CASE WHEN status = 'WARNING' THEN 1 ELSE 0 END), SUM(temperature), MIN(temperature), MAX(temperature) "
                                +
                                "FROM temperature_log " +
                                "WHERE SUBSTR(sensor_id, 1, 7) != 'BACKUP-' " +
                                "GROUP BY warehouse_name, hour_bucket";
                        int created = stmt.executeUpdate(backfill);
                        c.commit();
                        System.out.println("[DB] Backfill hoàn tất, đã tạo " + created + " dòng trong hourly_stats.");
                    } catch (SQLException ex) {
                        c.rollback();
                        throw ex;
                    } finally {
                        c.setAutoCommit(true);
                    }
                }
            }

            if (!columnExists(c, "warehouse", "dwell_minutes")) {
                stmt.execute("ALTER TABLE warehouse ADD COLUMN dwell_minutes REAL");
            }
            if (!columnExists(c, "warehouse", "recovery_margin")) {
                stmt.execute("ALTER TABLE warehouse ADD COLUMN recovery_margin REAL");
            }
            if (!columnExists(c, "warehouse", "maintenance_until")) {
                stmt.execute("ALTER TABLE warehouse ADD COLUMN maintenance_until INTEGER");
            }
            if (!columnExists(c, "alert_history", "acknowledged_at")) {
                stmt.execute("ALTER TABLE alert_history ADD COLUMN acknowledged_at INTEGER");
            }
            System.out.println("[DB] Đã khởi tạo database coldstorage.db thành công.");
        } catch (SQLException e) {
            System.err.println("[DB] Lỗi khởi tạo database: " + e.getMessage());
        }
    }

    /**
     * Nâng cấp cấu trúc cũ (warehouse_registry + warehouse_product theo TÊN kho)
     * sang cấu trúc mới
     * (bảng warehouse có id, warehouse_product dùng warehouse_id). Chạy trong 1
     * transaction:
     * lỗi giữa chừng thì hoàn tác toàn bộ; đã nâng cấp rồi thì các lần sau tự bỏ
     * qua.
     */
    private static void migrateToWarehouseTable(String warehouseProductSql) throws SQLException {
        Connection c = getConnection();
        boolean hasRegistry = tableExists(c, "warehouse_registry");
        boolean oldAssign = columnExists(c, "warehouse_product", "warehouse_name");
        if (!hasRegistry && !oldAssign)
            return; // không có gì để chuyển

        c.setAutoCommit(false);
        try (Statement st = c.createStatement()) {
            if (hasRegistry) {
                st.execute("INSERT OR IGNORE INTO warehouse(name, type, active, updated_at) " +
                        "SELECT warehouse_name, warehouse_type, active, updated_at FROM warehouse_registry");
            }
            if (oldAssign) {
                // Kho có gán sản phẩm nhưng chưa có trong sổ đăng ký: thêm vào (active=0, loại
                // lấy theo lịch sử nếu có)
                st.execute("INSERT OR IGNORE INTO warehouse(name, type, active, updated_at) " +
                        "SELECT DISTINCT wp.warehouse_name, " +
                        "(SELECT t.warehouse_type FROM temperature_log t WHERE t.warehouse_name = wp.warehouse_name " +
                        "ORDER BY t.id DESC LIMIT 1), 0, 0 FROM warehouse_product wp");
                st.execute("ALTER TABLE warehouse_product RENAME TO warehouse_product_old");
                st.execute(warehouseProductSql);
                st.execute("INSERT OR IGNORE INTO warehouse_product(warehouse_id, product_id) " +
                        "SELECT w.id, o.product_id FROM warehouse_product_old o " +
                        "JOIN warehouse w ON w.name = o.warehouse_name " +
                        "JOIN product p ON p.id = o.product_id");
                st.execute("DROP TABLE warehouse_product_old");
            }
            if (hasRegistry) {
                st.execute("DROP TABLE warehouse_registry");
            }
            c.commit();
            System.out.println(
                    "[DB] Đã nâng cấp cấu trúc: bảng warehouse (khóa chính id) + warehouse_product (khóa ngoại).");
        } catch (SQLException e) {
            c.rollback();
            System.err.println("[DB] Nâng cấp cấu trúc thất bại, đã hoàn tác: " + e.getMessage());
            throw e;
        } finally {
            c.setAutoCommit(true);
        }
    }

    private static boolean tableExists(Connection c, String table) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static boolean columnExists(Connection c, String table, String column) throws SQLException {
        try (Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name")))
                    return true;
            }
        }
        return false;
    }

    /**
     * Đánh dấu 1 kho là ĐANG hoạt động (vừa nhận được gói tin THẬT từ cảm biến
     * chính của nó)
     */
    public static synchronized void markWarehouseActive(String warehouseName, String warehouseType) {
        // Dùng UPSERT (KHÔNG dùng INSERT OR REPLACE) để giữ nguyên id của kho, tránh
        // gãy khóa ngoại
        String sql = "INSERT INTO warehouse(name, type, active, updated_at) " +
                "VALUES(?,?,1,?) ON CONFLICT(name) DO UPDATE SET " +
                "type = excluded.type, active = 1, updated_at = excluded.updated_at";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, warehouseName);
            ps.setString(2, warehouseType);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[DB] Lỗi khi đánh dấu kho đang hoạt động: " + e.getMessage());
        }
    }

    /**
     * Đánh dấu 1 kho đã bị xóa khỏi hệ thống (gọi từ MonitorServer.deleteWarehouse,
     * BẤT KỂ có xóa lịch sử hay không)
     */
    public static synchronized void markWarehouseInactive(String warehouseName) {
        String sql = "UPDATE warehouse SET active = 0, updated_at = ? WHERE name = ?";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, warehouseName);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[DB] Lỗi khi đánh dấu kho đã xóa: " + e.getMessage());
        }
    }

    /**
     * Trạng thái đăng ký của 1 kho. UNKNOWN = KHÔNG đọc được database (khóa/lỗi) -
     * hoàn toàn khác INACTIVE (đã xóa thật).
     */
    public enum WarehouseState {
        ACTIVE, INACTIVE, UNKNOWN
    }

    public static synchronized WarehouseState getWarehouseState(String warehouseName) {
        String sql = "SELECT active FROM warehouse WHERE name = ?";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, warehouseName);
            try (ResultSet rs = ps.executeQuery()) {
                // Không có dòng nào = kho không còn/không có trong sổ đăng ký -> coi như đã xóa
                return (rs.next() && rs.getInt("active") != 0) ? WarehouseState.ACTIVE : WarehouseState.INACTIVE;
            }
        } catch (SQLException e) {
            System.err.println("[DB] Lỗi khi kiểm tra trạng thái kho (KHÔNG kết luận là đã xóa): " + e.getMessage());
            return WarehouseState.UNKNOWN;
        }
    }

    public static class WarehouseOverrides {
        public Double dwellMinutes;
        public Double recoveryMargin;
    }

    public static synchronized WarehouseOverrides getWarehouseOverrides(String warehouseName) {
        String sql = "SELECT dwell_minutes, recovery_margin FROM warehouse WHERE name = ?";
        WarehouseOverrides overrides = new WarehouseOverrides();
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, warehouseName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    double d = rs.getDouble("dwell_minutes");
                    if (!rs.wasNull())
                        overrides.dwellMinutes = d;
                    double r = rs.getDouble("recovery_margin");
                    if (!rs.wasNull())
                        overrides.recoveryMargin = r;
                }
            }
        } catch (SQLException e) {
            System.err.println("[DB] Lỗi khi lấy overrides kho: " + e.getMessage());
        }
        return overrides;
    }

    public static synchronized void updateWarehouseOverrides(String warehouseName, Double dwellMins,
            Double recoveryMargin) {
        String sql = "UPDATE warehouse SET dwell_minutes = ?, recovery_margin = ? WHERE name = ?";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            if (dwellMins == null)
                ps.setNull(1, java.sql.Types.REAL);
            else
                ps.setDouble(1, dwellMins);
            if (recoveryMargin == null)
                ps.setNull(2, java.sql.Types.REAL);
            else
                ps.setDouble(2, recoveryMargin);
            ps.setString(3, warehouseName);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[DB] Lỗi khi cập nhật overrides kho: " + e.getMessage());
        }
    }

    /**
     * Tên + loại của MỌI kho đang active=1. Ném SQLException khi lỗi để người gọi
     * PHÂN BIỆT "lỗi đọc" với "không có kho nào".
     */
    public static synchronized Map<String, String> queryActiveWarehouseTypes() throws SQLException {
        Map<String, String> result = new LinkedHashMap<>();
        String sql = "SELECT name, type FROM warehouse WHERE active = 1 AND type IS NOT NULL";
        try (Statement stmt = getConnection().createStatement();
                ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                result.put(rs.getString("name"), rs.getString("type"));
            }
        }
        return result;
    }

    /**
     * Bản không ném lỗi (giao diện Thống kê): lỗi đọc -> danh sách rỗng.
     * BackupLauncher KHÔNG dùng bản này.
     */
    public static synchronized Map<String, String> getActiveWarehouseTypes() {
        try {
            return queryActiveWarehouseTypes();
        } catch (SQLException e) {
            System.err.println("[DB] Lỗi khi lấy danh sách kho đang hoạt động: " + e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    /**
     * Tên các kho đã bị xóa (active = 0) - dùng để loại khỏi bảng giám sát khi khởi
     * động
     */
    public static synchronized Set<String> getInactiveWarehouseNames() {
        Set<String> result = new HashSet<>();
        String sql = "SELECT name FROM warehouse WHERE active = 0";
        try (Statement stmt = getConnection().createStatement();
                ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                result.add(rs.getString("name"));
            }
        } catch (SQLException e) {
            System.err.println("[DB] Lỗi khi lấy danh sách kho đã xóa: " + e.getMessage());
        }
        return result;
    }

    /** Ghi 1 bản ghi nhiệt độ vào database (an toàn khi gọi từ nhiều luồng) */
    public static synchronized void insertLog(TemperatureData data, String status) {
        String sql = "INSERT INTO temperature_log(sensor_id, warehouse_name, warehouse_type, " +
                "temperature, status, log_time, log_time_millis) VALUES(?,?,?,?,?,?,?)";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, data.getSensorId());
            ps.setString(2, data.getWarehouseName());
            ps.setString(3, data.getWarehouseType());
            ps.setDouble(4, data.getTemperature());
            ps.setString(5, status);
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            ps.setString(6, sdf.format(new Date(data.getTimestamp())));
            ps.setLong(7, data.getTimestamp());
            ps.executeUpdate();

            // Cập nhật thống kê theo giờ (loại trừ gói dự phòng)
            if (data.getSensorId() == null || !data.getSensorId().startsWith(BackupUtil.SENSOR_PREFIX)) {
                String upsert = "INSERT INTO hourly_stats (warehouse_name, hour_bucket, count_total, count_warning, sum_temp, min_temp, max_temp) "
                        +
                        "VALUES (?, ?, 1, ?, ?, ?, ?) " +
                        "ON CONFLICT(warehouse_name, hour_bucket) DO UPDATE SET " +
                        "count_total = count_total + 1, " +
                        "count_warning = count_warning + excluded.count_warning, " +
                        "sum_temp = sum_temp + excluded.sum_temp, " +
                        "min_temp = MIN(hourly_stats.min_temp, excluded.min_temp), " +
                        "max_temp = MAX(hourly_stats.max_temp, excluded.max_temp)";
                try (PreparedStatement psUpsert = getConnection().prepareStatement(upsert)) {
                    long hourBucket = (data.getTimestamp() / 3600000L) * 3600000L;
                    int isWarning = "WARNING".equals(status) ? 1 : 0;
                    psUpsert.setString(1, data.getWarehouseName());
                    psUpsert.setLong(2, hourBucket);
                    psUpsert.setInt(3, isWarning);
                    psUpsert.setDouble(4, data.getTemperature());
                    psUpsert.setDouble(5, data.getTemperature());
                    psUpsert.setDouble(6, data.getTemperature());
                    psUpsert.executeUpdate();
                }
            }
        } catch (SQLException e) {
            System.err.println("[DB] Lỗi khi ghi log: " + e.getMessage());
        }
    }

    /**
     * FIX #3: Lấy danh sách tên kho đã từng xuất hiện trong lịch sử,
     * dùng để Server biết cần nạp lại (khôi phục) dữ liệu cho những kho nào khi
     * khởi động.
     */
    public static synchronized List<String> getKnownWarehouses() {
        List<String> result = new ArrayList<>();
        String sql = "SELECT DISTINCT warehouse_name FROM temperature_log";
        try (Statement stmt = getConnection().createStatement();
                ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                result.add(rs.getString("warehouse_name"));
            }
        } catch (SQLException e) {
            System.err.println("[DB] Lỗi khi lấy danh sách kho: " + e.getMessage());
        }
        return result;
    }

    /**
     * FIX #3: Lấy N bản ghi gần nhất của 1 kho (theo thứ tự thời gian tăng dần),
     * dùng để khôi phục lại bảng trạng thái + biểu đồ khi Server vừa khởi động lại,
     * thay vì bắt đầu từ màn hình trống như bản cũ.
     */
    public static synchronized List<TemperatureData> getRecentLogs(String warehouseName, int limit) {
        List<TemperatureData> result = new ArrayList<>();
        String sql = "SELECT * FROM (SELECT sensor_id, warehouse_name, warehouse_type, temperature, " +
                "log_time_millis FROM temperature_log WHERE warehouse_name = ? " +
                "ORDER BY id DESC LIMIT ?) ORDER BY log_time_millis ASC";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, warehouseName);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    TemperatureData d = new TemperatureData(
                            rs.getString("sensor_id"),
                            rs.getString("warehouse_name"),
                            rs.getString("warehouse_type"),
                            rs.getDouble("temperature"),
                            rs.getLong("log_time_millis"),
                            null // token không cần thiết khi đọc lại từ DB, dữ liệu đã được xác thực lúc ghi
                    );
                    result.add(d);
                }
            }
        } catch (SQLException e) {
            System.err.println("[DB] Lỗi khi đọc lịch sử: " + e.getMessage());
        }
        return result;
    }

    public static synchronized String getWarehouseType(String warehouseName) {
        String sql = "SELECT type FROM warehouse WHERE name = ?";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, warehouseName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next())
                    return rs.getString("type");
            }
        } catch (SQLException e) {
            System.err.println("[DB] Lỗi khi lấy loại kho: " + e.getMessage());
        }
        return "UNKNOWN";
    }

    public static synchronized List<TemperatureData> getLogsInRange(String warehouseName, long fromMillis,
            long toMillis) {
        List<TemperatureData> result = new ArrayList<>();
        String sql = "SELECT sensor_id, warehouse_name, warehouse_type, temperature, log_time_millis " +
                "FROM temperature_log WHERE warehouse_name = ? AND log_time_millis >= ? AND log_time_millis <= ? " +
                "ORDER BY log_time_millis ASC";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, warehouseName);
            ps.setLong(2, fromMillis);
            ps.setLong(3, toMillis);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new TemperatureData(
                            rs.getString("sensor_id"),
                            rs.getString("warehouse_name"),
                            rs.getString("warehouse_type"),
                            rs.getDouble("temperature"),
                            rs.getLong("log_time_millis"),
                            null));
                }
            }
        } catch (SQLException e) {
            System.err.println("[DB] Lỗi khi đọc lịch sử khoảng: " + e.getMessage());
        }
        return result;
    }

    public static synchronized void insertRejectedLog(long timestamp, String sourceIp, String reason, String sensorId) {
        String sql = "INSERT INTO rejected_log(timestamp, source_ip, reason, sensor_id) VALUES(?,?,?,?)";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setLong(1, timestamp);
            ps.setString(2, sourceIp);
            ps.setString(3, reason);
            ps.setString(4, sensorId);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[DB] Lỗi khi ghi rejected_log: " + e.getMessage());
        }
    }

    public static class RejectedLog {
        public long timestamp;
        public String sourceIp;
        public String reason;
        public String sensorId;
    }

    public static synchronized List<RejectedLog> getRecentRejectedLogs(int limit) {
        List<RejectedLog> result = new ArrayList<>();
        String sql = "SELECT * FROM rejected_log ORDER BY id DESC LIMIT ?";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    RejectedLog log = new RejectedLog();
                    log.timestamp = rs.getLong("timestamp");
                    log.sourceIp = rs.getString("source_ip");
                    log.reason = rs.getString("reason");
                    log.sensorId = rs.getString("sensor_id");
                    result.add(log);
                }
            }
        } catch (SQLException e) {
            System.err.println("[DB] Lỗi khi lấy rejected_log: " + e.getMessage());
        }
        return result;
    }

    public static synchronized List<double[]> getHourlyStatsInRange(String warehouseName, long fromMillis,
            long toMillis) {
        List<double[]> result = new ArrayList<>();
        String sql = "SELECT hour_bucket, sum_temp, count_total FROM hourly_stats " +
                "WHERE warehouse_name = ? AND hour_bucket >= ? AND hour_bucket <= ? AND count_total > 0 " +
                "ORDER BY hour_bucket ASC";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, warehouseName);
            ps.setLong(2, fromMillis);
            ps.setLong(3, toMillis);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long ts = rs.getLong("hour_bucket");
                    double sum = rs.getDouble("sum_temp");
                    int cnt = rs.getInt("count_total");
                    result.add(new double[] { ts, sum / cnt });
                }
            }
        } catch (SQLException e) {
            System.err.println("[DB] Lỗi khi đọc stats khoảng: " + e.getMessage());
        }
        return result;
    }

    /**
     * Các bản ghi GẦN NHẤT của 1 kho (mới -> cũ), LOẠI TRỪ sensor_id bắt đầu bằng
     * excludePrefix (gói dự phòng).
     * Dùng cho BackupSensorClient: phần tử đầu = lần cuối CẢM BIẾN THẬT gửi; khoảng
     * cách giữa các phần tử = chu kỳ gửi thật.
     * Ném SQLException khi lỗi (KHÔNG trả về rỗng) để không nhầm "đọc lỗi" thành
     * "cảm biến chính chưa từng gửi/đang im lặng".
     */
    public static synchronized List<TemperatureData> getRecentPrimaryLogs(String warehouseName, String excludePrefix,
            int limit)
            throws SQLException {
        List<TemperatureData> result = new ArrayList<>();
        String sql = "SELECT sensor_id, warehouse_name, warehouse_type, temperature, log_time_millis " +
                "FROM temperature_log WHERE warehouse_name = ? AND sensor_id NOT LIKE ? " +
                "ORDER BY id DESC LIMIT ?";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, warehouseName);
            ps.setString(2, excludePrefix + "%");
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new TemperatureData(
                            rs.getString("sensor_id"), rs.getString("warehouse_name"), rs.getString("warehouse_type"),
                            rs.getDouble("temperature"), rs.getLong("log_time_millis"), null));
                }
            }
        }
        return result;
    }

    /**
     * Tên + loại (DONG/MAT) của MỌI kho đã từng xuất hiện trong lịch sử, lấy theo
     * bản ghi gần nhất
     * của từng kho. Dùng cho chương trình dự phòng để tự biết cần theo dõi những
     * kho nào mà không
     * phải sửa code mỗi khi Admin thêm kho mới.
     */
    public static synchronized Map<String, String> getKnownWarehouseTypes() {
        Map<String, String> result = new LinkedHashMap<>();
        String sql = "SELECT t.warehouse_name, t.warehouse_type FROM temperature_log t " +
                "INNER JOIN (SELECT warehouse_name, MAX(id) AS max_id FROM temperature_log GROUP BY warehouse_name) latest "
                +
                "ON t.warehouse_name = latest.warehouse_name AND t.id = latest.max_id";
        try (Statement stmt = getConnection().createStatement();
                ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                result.put(rs.getString("warehouse_name"), rs.getString("warehouse_type"));
            }
        } catch (SQLException e) {
            System.err.println("[DB] Lỗi khi lấy danh sách kho kèm loại: " + e.getMessage());
        }
        return result;
    }

    /** Đếm tổng số lần cảnh báo (WARNING) đã ghi nhận */
    public static synchronized int countWarnings() {
        String sql = "SELECT COUNT(*) FROM temperature_log WHERE status = 'WARNING'";
        try (Statement stmt = getConnection().createStatement();
                ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next())
                return rs.getInt(1);
        } catch (SQLException e) {
            System.err.println("[DB] Lỗi khi đếm cảnh báo: " + e.getMessage());
        }
        return 0;
    }

    /**
     * NÂNG CẤP THỐNG KÊ: đếm số bản ghi theo từng trạng thái (SAFE / WARNING),
     * dùng để vẽ biểu đồ tròn tỉ lệ an toàn/cảnh báo trên StatsPanel.
     */
    public static synchronized Map<String, Integer> countByStatus() {
        Map<String, Integer> result = new LinkedHashMap<>();
        result.put("SAFE", 0);
        result.put("WARNING", 0);
        String sql = "SELECT SUM(count_total - count_warning) AS safe_cnt, SUM(count_warning) AS warn_cnt FROM hourly_stats";
        try (Statement stmt = getConnection().createStatement();
                ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                result.put("SAFE", rs.getInt("safe_cnt"));
                result.put("WARNING", rs.getInt("warn_cnt"));
            }
        } catch (SQLException e) {
            System.err.println("[DB] Lỗi khi thống kê theo trạng thái: " + e.getMessage());
        }
        return result;
    }

    /**
     * NÂNG CẤP THỐNG KÊ: đếm số lần cảnh báo theo từng khung giờ trong ngày
     * (0h-23h),
     * dùng để vẽ biểu đồ cột "giờ nào hay xảy ra sự cố nhất" trên StatsPanel.
     */
    public static synchronized Map<Integer, Integer> countWarningsByHour() {
        Map<Integer, Integer> result = new TreeMap<>();
        for (int h = 0; h < 24; h++)
            result.put(h, 0);
        String sql = "SELECT CAST(strftime('%H', hour_bucket / 1000, 'unixepoch', 'localtime') AS INTEGER) AS hour, SUM(count_warning) AS cnt "
                +
                "FROM hourly_stats WHERE count_warning > 0 GROUP BY hour";
        try (Statement stmt = getConnection().createStatement();
                ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                result.put(rs.getInt("hour"), rs.getInt("cnt"));
            }
        } catch (SQLException e) {
            System.err.println("[DB] Lỗi khi thống kê cảnh báo theo giờ: " + e.getMessage());
        }
        return result;
    }

    // ===================== SẢN PHẨM & GÁN SẢN PHẨM VÀO KHO =====================

    /**
     * true nếu bảng product CHƯA TỪNG có bản ghi nào (kể cả đã bị xóa hết) - dùng
     * để chỉ nạp sản phẩm mẫu
     * đúng 1 lần, không nạp lại sau khi người dùng chủ động xóa sạch danh mục.
     */
    public static synchronized boolean isProductTableNeverUsed() {
        String sql = "SELECT COUNT(*) FROM sqlite_sequence WHERE name = 'product'";
        try (Statement stmt = getConnection().createStatement();
                ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() && rs.getInt(1) == 0;
        } catch (SQLException e) {
            System.err.println("[DB] Lỗi khi kiểm tra bảng sản phẩm: " + e.getMessage());
            return false;
        }
    }

    /** @return false nếu tên đã tồn tại hoặc ghi lỗi */
    public static synchronized boolean insertProduct(String name, double minTemp, double maxTemp) {
        String sql = "INSERT INTO product(name, min_temp, max_temp) VALUES(?,?,?)";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setDouble(2, minTemp);
            ps.setDouble(3, maxTemp);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            System.err.println("[DB] Lỗi khi thêm sản phẩm: " + e.getMessage());
            return false;
        }
    }

    /** Xóa sản phẩm và mọi liên kết kho-sản phẩm của nó */
    public static synchronized void deleteProduct(int productId) {
        try (PreparedStatement ps1 = getConnection()
                .prepareStatement("DELETE FROM warehouse_product WHERE product_id = ?");
                PreparedStatement ps2 = getConnection().prepareStatement("DELETE FROM product WHERE id = ?")) {
            ps1.setInt(1, productId);
            ps1.executeUpdate();
            ps2.setInt(1, productId);
            ps2.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[DB] Lỗi khi xóa sản phẩm: " + e.getMessage());
        }
    }

    public static synchronized List<Product> getAllProducts() {
        try {
            return queryAllProducts();
        } catch (SQLException e) {
            System.err.println("[DB] Lỗi khi đọc danh sách sản phẩm: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Như getAllProducts nhưng ném lỗi - để ProductUtil giữ snapshot cũ thay vì
     * thay bằng danh sách rỗng
     */
    public static synchronized List<Product> queryAllProducts() throws SQLException {
        List<Product> result = new ArrayList<>();
        String sql = "SELECT id, name, min_temp, max_temp FROM product ORDER BY id";
        try (Statement stmt = getConnection().createStatement();
                ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                result.add(new Product(rs.getInt("id"), rs.getString("name"),
                        rs.getDouble("min_temp"), rs.getDouble("max_temp")));
            }
        }
        return result;
    }

    /**
     * Lấy id của kho theo tên; chưa có thì tạo dòng mới (active=0, chưa rõ loại) để
     * gán sản phẩm trước khi cảm biến xuất hiện
     */
    private static int getOrCreateWarehouseId(String name) throws SQLException {
        try (PreparedStatement ins = getConnection().prepareStatement(
                "INSERT OR IGNORE INTO warehouse(name, type, active, updated_at) VALUES(?,NULL,0,?)")) {
            ins.setString(1, name);
            ins.setLong(2, System.currentTimeMillis());
            ins.executeUpdate();
        }
        try (PreparedStatement sel = getConnection().prepareStatement("SELECT id FROM warehouse WHERE name = ?")) {
            sel.setString(1, name);
            try (ResultSet rs = sel.executeQuery()) {
                if (rs.next())
                    return rs.getInt(1);
            }
        }
        throw new SQLException("Không tìm thấy/tạo được kho '" + name + "'");
    }

    /**
     * @return false nếu ghi lỗi (gán trùng được coi là thành công vì kết quả cuối
     *         cùng như nhau)
     */
    public static synchronized boolean assignProduct(String warehouseName, int productId) {
        String sql = "INSERT OR IGNORE INTO warehouse_product(warehouse_id, product_id) VALUES(?,?)";
        try {
            int warehouseId = getOrCreateWarehouseId(warehouseName);
            try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
                ps.setInt(1, warehouseId);
                ps.setInt(2, productId);
                ps.executeUpdate();
            }
            return true;
        } catch (SQLException e) {
            System.err.println("[DB] Lỗi khi gán sản phẩm vào kho: " + e.getMessage());
            return false;
        }
    }

    public static synchronized void unassignProduct(String warehouseName, int productId) {
        String sql = "DELETE FROM warehouse_product WHERE product_id = ? " +
                "AND warehouse_id = (SELECT id FROM warehouse WHERE name = ?)";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setInt(1, productId);
            ps.setString(2, warehouseName);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[DB] Lỗi khi gỡ sản phẩm khỏi kho: " + e.getMessage());
        }
    }

    /** Toàn bộ liên kết: tên kho -> danh sách id sản phẩm */
    public static synchronized Map<String, List<Integer>> getAllAssignments() {
        try {
            return queryAllAssignments();
        } catch (SQLException e) {
            System.err.println("[DB] Lỗi khi đọc liên kết kho-sản phẩm: " + e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    /**
     * Như getAllAssignments nhưng ném lỗi - để ProductUtil giữ snapshot cũ thay vì
     * coi như "chưa gán sản phẩm nào"
     */
    public static synchronized Map<String, List<Integer>> queryAllAssignments() throws SQLException {
        Map<String, List<Integer>> result = new LinkedHashMap<>();
        String sql = "SELECT w.name, wp.product_id FROM warehouse_product wp " +
                "JOIN warehouse w ON w.id = wp.warehouse_id ORDER BY w.name, wp.product_id";
        try (Statement stmt = getConnection().createStatement();
                ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                result.computeIfAbsent(rs.getString("name"), k -> new ArrayList<>())
                        .add(rs.getInt("product_id"));
            }
        }
        return result;
    }

    /**
     * Xóa toàn bộ lịch sử đo của 1 kho khỏi database (dùng khi xóa hẳn kho khỏi hệ
     * thống). KHÔNG thể hoàn tác.
     */
    public static synchronized void deleteWarehouseLogs(String warehouseName) {
        String sql = "DELETE FROM temperature_log WHERE warehouse_name = ?";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, warehouseName);
            int n = ps.executeUpdate();
            System.out.println("[DB] Đã xóa " + n + " bản ghi lịch sử của kho '" + warehouseName + "'.");

            try (PreparedStatement ps2 = getConnection()
                    .prepareStatement("DELETE FROM hourly_stats WHERE warehouse_name = ?")) {
                ps2.setString(1, warehouseName);
                ps2.executeUpdate();
            }
        } catch (SQLException e) {
            System.err.println("[DB] Lỗi khi xóa lịch sử kho: " + e.getMessage());
        }
        // "Xóa luôn lịch sử" phải gồm cả lịch sử sự cố, nếu không tab "Lịch sử sự cố"
        // và báo cáo vẫn hiện kho đã xóa
        try (PreparedStatement ps = getConnection()
                .prepareStatement("DELETE FROM alert_history WHERE warehouse_name = ?")) {
            ps.setString(1, warehouseName);
            ps.executeUpdate();
            OPEN_PEAK.remove(warehouseName);
        } catch (SQLException e) {
            System.err.println("[DB] Lỗi khi xóa lịch sử sự cố của kho: " + e.getMessage());
        }
    }

    /**
     * Gỡ toàn bộ sản phẩm đang gán cho 1 kho (dùng khi xóa hẳn kho khỏi hệ thống)
     */
    public static synchronized void deleteAllAssignmentsForWarehouse(String warehouseName) {
        String sql = "DELETE FROM warehouse_product WHERE warehouse_id = (SELECT id FROM warehouse WHERE name = ?)";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, warehouseName);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[DB] Lỗi khi gỡ sản phẩm khỏi kho bị xóa: " + e.getMessage());
        }
    }

    /**
     * 1 dòng báo cáo tổng hợp của 1 kho: dùng cho nút "Xuất báo cáo" ở tab Thống kê
     */
    public static class WarehouseReportRow {
        public final String name;
        public final String type;
        public final String products; // rỗng nếu chưa gán sản phẩm nào
        public final int totalReadings;
        public final int warningReadings;
        public final double minTemp;
        public final double avgTemp;
        public final double maxTemp;
        public final long totalAlertMinutes;
        public final Double mkt;

        public WarehouseReportRow(String name, String type, String products, int totalReadings, int warningReadings,
                double minTemp, double avgTemp, double maxTemp, long totalAlertMinutes, Double mkt) {
            this.name = name;
            this.type = type;
            this.products = products;
            this.totalReadings = totalReadings;
            this.warningReadings = warningReadings;
            this.minTemp = minTemp;
            this.avgTemp = avgTemp;
            this.maxTemp = maxTemp;
            this.totalAlertMinutes = totalAlertMinutes;
            this.mkt = mkt;
        }

        public double warningPercent() {
            return totalReadings == 0 ? 0 : 100.0 * warningReadings / totalReadings;
        }

        public double safePercent() {
            return 100.0 - warningPercent();
        }
    }

    public static synchronized List<WarehouseReportRow> getWarehouseReport(Long fromMillis, Long toMillis) {
        List<WarehouseReportRow> result = new ArrayList<>();
        String wSql = "SELECT w.name, w.type, GROUP_CONCAT(DISTINCT p.name) AS products " +
                "FROM warehouse w " +
                "LEFT JOIN warehouse_product wp ON wp.warehouse_id = w.id " +
                "LEFT JOIN product p ON p.id = wp.product_id " +
                "WHERE w.active = 1 GROUP BY w.id, w.name ORDER BY w.name";
        try (Statement stmt = getConnection().createStatement();
                ResultSet wRs = stmt.executeQuery(wSql)) {
            while (wRs.next()) {
                String name = wRs.getString("name");
                String type = wRs.getString("type");
                String products = wRs.getString("products");
                if (products == null)
                    products = "";

                String statsSql = "SELECT SUM(count_total) as t_cnt, SUM(count_warning) as w_cnt, " +
                        "MIN(min_temp) as min_t, SUM(sum_temp) as sum_t, MAX(max_temp) as max_t " +
                        "FROM hourly_stats WHERE warehouse_name = ?";
                if (fromMillis != null)
                    statsSql += " AND hour_bucket >= ?";
                if (toMillis != null)
                    statsSql += " AND hour_bucket <= ?";

                int total = 0, warns = 0;
                double minT = 0, avgT = 0, maxT = 0;

                try (PreparedStatement ps = getConnection().prepareStatement(statsSql)) {
                    ps.setString(1, name);
                    int pidx = 2;
                    if (fromMillis != null)
                        ps.setLong(pidx++, fromMillis);
                    if (toMillis != null)
                        ps.setLong(pidx++, toMillis);

                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            total = rs.getInt("t_cnt");
                            warns = rs.getInt("w_cnt");
                            if (total > 0) {
                                minT = rs.getDouble("min_t");
                                maxT = rs.getDouble("max_t");
                                avgT = rs.getDouble("sum_t") / total;
                            }
                        }
                    }
                }

                Double mkt = null;
                String prodLower = products.toLowerCase();
                if ("MAT".equalsIgnoreCase(type) &&
                        (prodLower.contains("vắc-xin") || prodLower.contains("vac-xin") ||
                                prodLower.contains("dược phẩm") || prodLower.contains("duoc pham"))) {

                    String mktSql = "SELECT sum_temp, count_total FROM hourly_stats WHERE warehouse_name = ? AND count_total > 0";
                    if (fromMillis != null)
                        mktSql += " AND hour_bucket >= ?";
                    if (toMillis != null)
                        mktSql += " AND hour_bucket <= ?";

                    try (PreparedStatement ps = getConnection().prepareStatement(mktSql)) {
                        ps.setString(1, name);
                        int pidx = 2;
                        if (fromMillis != null)
                            ps.setLong(pidx++, fromMillis);
                        if (toMillis != null)
                            ps.setLong(pidx++, toMillis);

                        try (ResultSet rs = ps.executeQuery()) {
                            double sumExp = 0;
                            int n = 0;
                            while (rs.next()) {
                                double hSum = rs.getDouble("sum_temp");
                                int hCnt = rs.getInt("count_total");
                                double tHour = hSum / hCnt;
                                double tKelvin = tHour + 273.15;
                                sumExp += Math.exp(-10000.0 / tKelvin);
                                n++;
                            }
                            if (n > 0) {
                                double mktK = 10000.0 / -Math.log(sumExp / n);
                                mkt = mktK - 273.15;
                            }
                        }
                    }
                }

                String alertSql = "SELECT SUM(COALESCE(ended_at, ?) - started_at) as total_ms " +
                        "FROM alert_history WHERE warehouse_name = ?";
                if (fromMillis != null)
                    alertSql += " AND (ended_at IS NULL OR ended_at >= ?)";
                if (toMillis != null)
                    alertSql += " AND started_at <= ?";

                long totalAlertMs = 0;
                long now = System.currentTimeMillis();
                try (PreparedStatement ps = getConnection().prepareStatement(alertSql)) {
                    ps.setLong(1, now);
                    ps.setString(2, name);
                    int pidx = 3;
                    if (fromMillis != null)
                        ps.setLong(pidx++, fromMillis);
                    if (toMillis != null)
                        ps.setLong(pidx++, toMillis);

                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            totalAlertMs = rs.getLong("total_ms");
                        }
                    }
                }

                result.add(new WarehouseReportRow(name, type, products, total, warns, minT, avgT, maxT,
                        totalAlertMs / 60000, mkt));
            }
        } catch (SQLException e) {
            System.err.println("[DB] Lỗi khi lấy báo cáo tổng hợp: " + e.getMessage());
        }
        return result;
    }

    // ============================== LỊCH SỬ SỰ CỐ (alert_history)
    // ==============================

    /**
     * Mở 1 sự cố mới cho kho (gọi khi AlertStateTracker báo START). started_at tính
     * bằng millis.
     */
    public static synchronized void startAlertIncident(String warehouseName, long startedAt, double temperature) {
        String sql = "INSERT INTO alert_history(warehouse_name, started_at, ended_at, peak_temp) VALUES(?,?,NULL,?)";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, warehouseName);
            ps.setLong(2, startedAt);
            ps.setDouble(3, temperature);
            ps.executeUpdate();
            OPEN_PEAK.put(warehouseName, temperature);
        } catch (SQLException e) {
            System.err.println("[DB] Lỗi khi mở sự cố cảnh báo: " + e.getMessage());
        }
    }

    /**
     * Nhiệt độ đỉnh điểm đang ghi nhận của sự cố đang mở, theo kho (cache để không
     * query DB ở mỗi gói WARNING)
     */
    private static final java.util.Map<String, Double> OPEN_PEAK = new java.util.HashMap<>();

    /**
     * Độ lệch (>= 0) của nhiệt độ so với dải an toàn [min, max]; 0 nếu nằm trong
     * dải
     */
    private static double deviation(double t, double min, double max) {
        if (t > max)
            return t - max;
        if (t < min)
            return min - t;
        return 0;
    }

    /**
     * Cập nhật nhiệt độ đỉnh điểm của sự cố đang mở: đỉnh điểm = nhiệt độ LỆCH XA
     * dải an toàn nhất
     * (cao hơn max thì càng cao càng tệ, thấp hơn min thì càng thấp càng tệ). Gọi ở
     * MỌI gói WARNING,
     * không chỉ lúc nhắc lại, để sự cố ngắn (< 5 phút) vẫn ghi đúng mức nghiêm
     * trọng thật sự.
     */
    public static synchronized void updateAlertPeak(String warehouseName, double temperature, double safeMin,
            double safeMax) {
        try {
            Double cur = OPEN_PEAK.get(warehouseName);
            if (cur == null) { // chưa có trong cache (vd. khởi động lại khi sự cố còn mở) -> đọc từ DB
                String q = "SELECT peak_temp FROM alert_history WHERE warehouse_name = ? AND ended_at IS NULL ORDER BY id DESC LIMIT 1";
                try (PreparedStatement ps = getConnection().prepareStatement(q)) {
                    ps.setString(1, warehouseName);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next())
                            return; // không có sự cố đang mở
                        cur = rs.getDouble(1);
                    }
                }
            }
            if (deviation(temperature, safeMin, safeMax) <= deviation(cur, safeMin, safeMax)) {
                OPEN_PEAK.put(warehouseName, cur);
                return; // không tệ hơn đỉnh hiện tại
            }
            String sql = "UPDATE alert_history SET peak_temp = ? " +
                    "WHERE id = (SELECT id FROM alert_history WHERE warehouse_name = ? AND ended_at IS NULL " +
                    "ORDER BY id DESC LIMIT 1)";
            try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
                ps.setDouble(1, temperature);
                ps.setString(2, warehouseName);
                ps.executeUpdate();
                OPEN_PEAK.put(warehouseName, temperature);
            }
        } catch (SQLException e) {
            System.err.println("[DB] Lỗi khi cập nhật đỉnh điểm sự cố: " + e.getMessage());
        }
    }

    /**
     * Gọi 1 lần khi Server khởi động (sau initDatabase). Trạng thái cảnh báo nằm
     * trong bộ nhớ nên bị mất khi tắt máy,
     * để lại sự cố "đang diễn ra" (ended_at NULL) trong DB. Xử lý:
     * - kho CÒN hoạt động: giữ sự cố MỚI NHẤT mở và trả về {kho -> started_at} để
     * AlertStateTracker TIẾP TỤC sự cố đó
     * (kho vẫn vượt ngưỡng -> không mở sự cố trùng; kho đã ổn -> gói SAFE đầu tiên
     * sẽ đóng đúng sự cố này);
     * - sự cố CŨ hơn của cùng kho (mồ côi do lỗi trước đây): đóng, kết thúc = lúc
     * sự cố kế tiếp bắt đầu;
     * - kho ĐÃ BỊ XÓA (active=0): đóng, kết thúc = lần cuối cảm biến thật gửi dữ
     * liệu.
     */
    public static synchronized Map<String, Long> reconcileOpenIncidents() {
        Map<String, Long> resume = new LinkedHashMap<>();
        Map<String, List<long[]>> open = new LinkedHashMap<>();
        try {
            try (Statement st = getConnection().createStatement();
                    ResultSet rs = st.executeQuery("SELECT id, warehouse_name, started_at FROM alert_history " +
                            "WHERE ended_at IS NULL ORDER BY warehouse_name, id")) {
                while (rs.next()) {
                    open.computeIfAbsent(rs.getString("warehouse_name"), k -> new ArrayList<>())
                            .add(new long[] { rs.getLong("id"), rs.getLong("started_at") });
                }
            }
            for (Map.Entry<String, List<long[]>> e : open.entrySet()) {
                String name = e.getKey();
                List<long[]> rows = e.getValue();
                for (int i = 0; i < rows.size() - 1; i++) {
                    closeIncidentById(rows.get(i)[0], Math.max(rows.get(i)[1], rows.get(i + 1)[1]));
                }
                long[] last = rows.get(rows.size() - 1);
                if (isWarehouseDeleted(name)) {
                    closeIncidentById(last[0], Math.max(last[1], lastPrimaryLogTime(name, last[1])));
                } else {
                    resume.put(name, last[1]);
                }
            }
        } catch (SQLException e) {
            System.err.println("[DB] Lỗi khi đối soát sự cố còn mở: " + e.getMessage());
        }
        OPEN_PEAK.clear();
        return resume;
    }

    private static void closeIncidentById(long id, long endedAt) throws SQLException {
        try (PreparedStatement ps = getConnection()
                .prepareStatement("UPDATE alert_history SET ended_at = ? WHERE id = ?")) {
            ps.setLong(1, endedAt);
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    /**
     * true nếu kho có dòng trong sổ đăng ký với active=0 (đã bị xóa). Không có dòng
     * = chưa từng đăng ký -> KHÔNG coi là đã xóa.
     */
    private static boolean isWarehouseDeleted(String name) throws SQLException {
        try (PreparedStatement ps = getConnection().prepareStatement("SELECT active FROM warehouse WHERE name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt("active") == 0;
            }
        }
    }

    private static long lastPrimaryLogTime(String name, long fallback) throws SQLException {
        try (PreparedStatement ps = getConnection().prepareStatement(
                "SELECT MAX(log_time_millis) FROM temperature_log WHERE warehouse_name = ? AND sensor_id NOT LIKE ?")) {
            ps.setString(1, name);
            ps.setString(2, BackupUtil.SENSOR_PREFIX + "%");
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long v = rs.getLong(1);
                    if (!rs.wasNull())
                        return v;
                }
            }
        }
        return fallback;
    }

    /**
     * Đóng MỌI sự cố đang mở của kho (dùng khi xóa kho). Giờ kết thúc = endedAt,
     * nhưng không nhỏ hơn giờ bắt đầu.
     */
    public static synchronized void closeOpenIncidents(String warehouseName, long endedAt) {
        String sql = "UPDATE alert_history SET ended_at = MAX(started_at, ?) WHERE warehouse_name = ? AND ended_at IS NULL";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setLong(1, endedAt);
            ps.setString(2, warehouseName);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[DB] Lỗi khi đóng sự cố của kho bị xóa: " + e.getMessage());
        }
        OPEN_PEAK.remove(warehouseName);
    }

    /**
     * Đóng sự cố đang mở gần nhất của kho (gọi khi AlertStateTracker báo RECOVERED)
     */
    public static synchronized void closeAlertIncident(String warehouseName, long endedAt) {
        String sql = "UPDATE alert_history SET ended_at = ? " +
                "WHERE id = (SELECT id FROM alert_history WHERE warehouse_name = ? AND ended_at IS NULL " +
                "ORDER BY id DESC LIMIT 1)";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setLong(1, endedAt);
            ps.setString(2, warehouseName);
            ps.executeUpdate();
            OPEN_PEAK.remove(warehouseName);
        } catch (SQLException e) {
            System.err.println("[DB] Lỗi khi đóng sự cố cảnh báo: " + e.getMessage());
        }
    }

    /**
     * Toàn bộ lịch sử sự cố, mới nhất trước. Sự cố còn đang mở (ended_at = NULL)
     * hiện "Đang diễn ra" ở tầng hiển thị.
     */
    public static synchronized List<AlertIncident> getAlertHistory(int limit) {
        List<AlertIncident> result = new ArrayList<>();
        String sql = "SELECT id, warehouse_name, started_at, ended_at, peak_temp, acknowledged_at FROM alert_history " +
                "ORDER BY started_at DESC LIMIT ?";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long ended = rs.getObject("ended_at") == null ? -1L : rs.getLong("ended_at");
                    Long ack = rs.getObject("acknowledged_at") == null ? null : rs.getLong("acknowledged_at");
                    result.add(
                            new AlertIncident(rs.getInt("id"), rs.getString("warehouse_name"), rs.getLong("started_at"),
                                    ended, rs.getDouble("peak_temp"), ack));
                }
            }
        } catch (SQLException e) {
            System.err.println("[DB] Lỗi khi đọc lịch sử sự cố: " + e.getMessage());
        }
        return result;
    }

    /**
     * 1 dòng lịch sử sự cố: kho, bắt đầu, kết thúc (-1 nếu đang diễn ra), nhiệt độ
     * đỉnh điểm, đã xác nhận
     */
    public static class AlertIncident {
        public final int id;
        public final String warehouseName;
        public final long startedAt;
        public final long endedAt; // -1 nếu đang diễn ra
        public final double peakTemp;
        public final Long acknowledgedAt;

        public AlertIncident(int id, String warehouseName, long startedAt, long endedAt, double peakTemp,
                Long acknowledgedAt) {
            this.id = id;
            this.warehouseName = warehouseName;
            this.startedAt = startedAt;
            this.endedAt = endedAt;
            this.peakTemp = peakTemp;
            this.acknowledgedAt = acknowledgedAt;
        }

        public boolean isOngoing() {
            return endedAt < 0;
        }
    }

    public static void acknowledgeAlert(int alertId) {
        String sql = "UPDATE alert_history SET acknowledged_at = ? WHERE id = ? AND acknowledged_at IS NULL";
        try (PreparedStatement pstmt = getConnection().prepareStatement(sql)) {
            pstmt.setLong(1, System.currentTimeMillis());
            pstmt.setInt(2, alertId);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[DB] Lỗi khi xác nhận sự cố: " + e.getMessage());
        }
    }

    public static void setWarehouseMaintenance(String warehouseName, Long untilMillis) {
        String sql = "UPDATE warehouse SET maintenance_until = ? WHERE name = ?";
        try (PreparedStatement pstmt = getConnection().prepareStatement(sql)) {
            if (untilMillis == null) {
                pstmt.setNull(1, Types.INTEGER);
            } else {
                pstmt.setLong(1, untilMillis);
            }
            pstmt.setString(2, warehouseName);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[DB] Lỗi khi thiết lập bảo trì: " + e.getMessage());
        }
    }

    public static Long getWarehouseMaintenance(String warehouseName) {
        String sql = "SELECT maintenance_until FROM warehouse WHERE name = ?";
        try (PreparedStatement pstmt = getConnection().prepareStatement(sql)) {
            pstmt.setString(1, warehouseName);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    long val = rs.getLong("maintenance_until");
                    if (!rs.wasNull())
                        return val;
                }
            }
        } catch (SQLException e) {
            System.err.println("[DB] Lỗi khi lấy bảo trì: " + e.getMessage());
        }
        return null;
    }

    /**
     * Quét tất cả kho active mà maintenance_until đã quá hạn (now >=
     * maintenance_until),
     * tự động xóa maintenance_until về NULL và trả về danh sách tên kho đã dọn.
     */
    public static synchronized List<String> clearExpiredMaintenance() {
        List<String> cleared = new ArrayList<>();
        long now = System.currentTimeMillis();
        String selectSql = "SELECT name FROM warehouse WHERE active = 1 AND maintenance_until IS NOT NULL AND maintenance_until <= ?";
        try (PreparedStatement ps = getConnection().prepareStatement(selectSql)) {
            ps.setLong(1, now);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    cleared.add(rs.getString("name"));
                }
            }
        } catch (SQLException e) {
            System.err.println("[DB] Lỗi khi quét bảo trì quá hạn: " + e.getMessage());
            return cleared;
        }
        if (!cleared.isEmpty()) {
            String updateSql = "UPDATE warehouse SET maintenance_until = NULL WHERE active = 1 AND maintenance_until IS NOT NULL AND maintenance_until <= ?";
            try (PreparedStatement ps = getConnection().prepareStatement(updateSql)) {
                ps.setLong(1, now);
                ps.executeUpdate();
            } catch (SQLException e) {
                System.err.println("[DB] Lỗi khi xóa bảo trì quá hạn: " + e.getMessage());
            }
        }
        return cleared;
    }

    /**
     * Đóng connection dùng chung, gọi khi Server tắt để giải phóng tài nguyên sạch
     * sẽ
     */
    public static synchronized void closeConnection() {
        try {
            if (sharedConnection != null && !sharedConnection.isClosed()) {
                sharedConnection.close();
                System.out.println("[DB] Đã đóng kết nối database.");
            }
        } catch (SQLException e) {
            System.err.println("[DB] Lỗi khi đóng kết nối: " + e.getMessage());
        }
    }
}