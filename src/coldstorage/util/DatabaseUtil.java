package coldstorage.util;

import coldstorage.model.Product;
import coldstorage.model.TemperatureData;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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
 * Cần thêm thư viện sqlite-jdbc vào project NetBeans (xem HUONG_DAN_NETBEANS.txt).
 *
 * FIX #6: SQLite chỉ cho phép 1 tiến trình ghi tại 1 thời điểm; việc mở 1 Connection mới
 * cho MỖI lần insert/query (như bản cũ) vừa tốn tài nguyên vừa dễ gây lỗi "database is locked"
 * khi nhiều luồng ghi cùng lúc. Bản sửa này dùng LẠI một Connection dùng chung (singleton),
 * mọi thao tác ghi được đồng bộ hoá (synchronized) để đảm bảo an toàn khi nhiều luồng gọi.
 */
public class DatabaseUtil {

    private static final String DB_URL = "jdbc:sqlite:coldstorage.db";
    private static Connection sharedConnection;

    /** Lấy connection dùng chung, tự mở lại nếu đã bị đóng */
    private static synchronized Connection getConnection() throws SQLException {
        if (sharedConnection == null || sharedConnection.isClosed()) {
            sharedConnection = DriverManager.getConnection(DB_URL);
            // SQLite MẶC ĐỊNH TẮT khóa ngoại - phải bật cho từng kết nối thì FOREIGN KEY mới có tác dụng
            try (Statement st = sharedConnection.createStatement()) {
                st.execute("PRAGMA foreign_keys = ON");
                // Server và BackupLauncher là 2 tiến trình cùng mở 1 file .db: chờ tối đa 8s khi file đang bị khóa ghi
                // thay vì báo lỗi ngay (lỗi thoáng qua không được coi là "kho đã xóa"/"cảm biến chính im lặng").
                st.execute("PRAGMA busy_timeout = 8000");
            }
        }
        return sharedConnection;
    }

    /** Gọi 1 lần khi Server khởi động để tạo bảng nếu chưa có (và tự nâng cấp cấu trúc cũ nếu cần) */
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
        // Index: tránh quét toàn bảng khi lọc theo kho / đếm theo trạng thái (bảng tăng hàng chục nghìn dòng mỗi ngày)
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
        // active = 0: kho đã bị xóa khỏi hệ thống (hoặc mới được gán sản phẩm, chưa có cảm biến nào gửi).
        // Kho bị xóa vẫn GIỮ dòng này (xóa mềm) để BackupLauncher và bước nạp lịch sử biết mà không hồi sinh nó.
        String warehouseSql = "CREATE TABLE IF NOT EXISTS warehouse (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "name TEXT NOT NULL UNIQUE," +
                "type TEXT," +
                "active INTEGER NOT NULL DEFAULT 0," +
                "updated_at INTEGER)";
        // Bảng nối nhiều-nhiều: kho nào đang chứa những sản phẩm nào (khóa ngoại thật tới warehouse và product)
        String warehouseProductSql = "CREATE TABLE IF NOT EXISTS warehouse_product (" +
                "warehouse_id INTEGER NOT NULL," +
                "product_id INTEGER NOT NULL," +
                "PRIMARY KEY (warehouse_id, product_id)," +
                "FOREIGN KEY (warehouse_id) REFERENCES warehouse(id) ON DELETE CASCADE," +
                "FOREIGN KEY (product_id) REFERENCES product(id) ON DELETE CASCADE)";
        // Lịch sử sự cố: mỗi dòng là 1 lần cảnh báo của 1 kho, từ lúc BẮT ĐẦU (started_at) tới lúc
        // HỒI PHỤC (ended_at). ended_at = NULL nghĩa là sự cố đang diễn ra (chưa hồi phục).
        // Ghi trực tiếp theo AlertStateTracker.Event.START / RECOVERED, không suy luận lại từ temperature_log.
        String alertHistorySql = "CREATE TABLE IF NOT EXISTS alert_history (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "warehouse_name TEXT NOT NULL," +
                "started_at INTEGER NOT NULL," +
                "ended_at INTEGER," +
                "peak_temp REAL)";
        String idxAlertOpen = "CREATE INDEX IF NOT EXISTS idx_alert_open ON alert_history(warehouse_name, ended_at)";
        try (Statement stmt = getConnection().createStatement()) {
            stmt.execute(sql);
            stmt.execute(idxWarehouse);
            stmt.execute(idxStatus);
            stmt.execute(productSql);
            stmt.execute(warehouseSql);
            migrateToWarehouseTable(warehouseProductSql);
            stmt.execute(warehouseProductSql);
            stmt.execute(alertHistorySql);
            stmt.execute(idxAlertOpen);
            System.out.println("[DB] Đã khởi tạo database coldstorage.db thành công.");
        } catch (SQLException e) {
            System.err.println("[DB] Lỗi khởi tạo database: " + e.getMessage());
        }
    }

    /**
     * Nâng cấp cấu trúc cũ (warehouse_registry + warehouse_product theo TÊN kho) sang cấu trúc mới
     * (bảng warehouse có id, warehouse_product dùng warehouse_id). Chạy trong 1 transaction:
     * lỗi giữa chừng thì hoàn tác toàn bộ; đã nâng cấp rồi thì các lần sau tự bỏ qua.
     */
    private static void migrateToWarehouseTable(String warehouseProductSql) throws SQLException {
        Connection c = getConnection();
        boolean hasRegistry = tableExists(c, "warehouse_registry");
        boolean oldAssign = columnExists(c, "warehouse_product", "warehouse_name");
        if (!hasRegistry && !oldAssign) return; // không có gì để chuyển

        c.setAutoCommit(false);
        try (Statement st = c.createStatement()) {
            if (hasRegistry) {
                st.execute("INSERT OR IGNORE INTO warehouse(name, type, active, updated_at) " +
                        "SELECT warehouse_name, warehouse_type, active, updated_at FROM warehouse_registry");
            }
            if (oldAssign) {
                // Kho có gán sản phẩm nhưng chưa có trong sổ đăng ký: thêm vào (active=0, loại lấy theo lịch sử nếu có)
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
            System.out.println("[DB] Đã nâng cấp cấu trúc: bảng warehouse (khóa chính id) + warehouse_product (khóa ngoại).");
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
                if (column.equalsIgnoreCase(rs.getString("name"))) return true;
            }
        }
        return false;
    }

    /** Đánh dấu 1 kho là ĐANG hoạt động (vừa nhận được gói tin THẬT từ cảm biến chính của nó) */
    public static synchronized void markWarehouseActive(String warehouseName, String warehouseType) {
        // Dùng UPSERT (KHÔNG dùng INSERT OR REPLACE) để giữ nguyên id của kho, tránh gãy khóa ngoại
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

    /** Đánh dấu 1 kho đã bị xóa khỏi hệ thống (gọi từ MonitorServer.deleteWarehouse, BẤT KỂ có xóa lịch sử hay không) */
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

    /** Trạng thái đăng ký của 1 kho. UNKNOWN = KHÔNG đọc được database (khóa/lỗi) - hoàn toàn khác INACTIVE (đã xóa thật). */
    public enum WarehouseState { ACTIVE, INACTIVE, UNKNOWN }

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

    /** Tên + loại của MỌI kho đang active=1. Ném SQLException khi lỗi để người gọi PHÂN BIỆT "lỗi đọc" với "không có kho nào". */
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

    /** Bản không ném lỗi (giao diện Thống kê): lỗi đọc -> danh sách rỗng. BackupLauncher KHÔNG dùng bản này. */
    public static synchronized Map<String, String> getActiveWarehouseTypes() {
        try {
            return queryActiveWarehouseTypes();
        } catch (SQLException e) {
            System.err.println("[DB] Lỗi khi lấy danh sách kho đang hoạt động: " + e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    /** Tên các kho đã bị xóa (active = 0) - dùng để loại khỏi bảng giám sát khi khởi động */
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
        } catch (SQLException e) {
            System.err.println("[DB] Lỗi khi ghi log: " + e.getMessage());
        }
    }

    /**
     * FIX #3: Lấy danh sách tên kho đã từng xuất hiện trong lịch sử,
     * dùng để Server biết cần nạp lại (khôi phục) dữ liệu cho những kho nào khi khởi động.
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

    /**
     * Các bản ghi GẦN NHẤT của 1 kho (mới -> cũ), LOẠI TRỪ sensor_id bắt đầu bằng excludePrefix (gói dự phòng).
     * Dùng cho BackupSensorClient: phần tử đầu = lần cuối CẢM BIẾN THẬT gửi; khoảng cách giữa các phần tử = chu kỳ gửi thật.
     * Ném SQLException khi lỗi (KHÔNG trả về rỗng) để không nhầm "đọc lỗi" thành "cảm biến chính chưa từng gửi/đang im lặng".
     */
    public static synchronized List<TemperatureData> getRecentPrimaryLogs(String warehouseName, String excludePrefix, int limit)
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
     * Tên + loại (DONG/MAT) của MỌI kho đã từng xuất hiện trong lịch sử, lấy theo bản ghi gần nhất
     * của từng kho. Dùng cho chương trình dự phòng để tự biết cần theo dõi những kho nào mà không
     * phải sửa code mỗi khi Admin thêm kho mới.
     */
    public static synchronized Map<String, String> getKnownWarehouseTypes() {
        Map<String, String> result = new LinkedHashMap<>();
        String sql = "SELECT t.warehouse_name, t.warehouse_type FROM temperature_log t " +
                "INNER JOIN (SELECT warehouse_name, MAX(id) AS max_id FROM temperature_log GROUP BY warehouse_name) latest " +
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
            if (rs.next()) return rs.getInt(1);
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
        String sql = "SELECT status, COUNT(*) AS cnt FROM temperature_log GROUP BY status";
        try (Statement stmt = getConnection().createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                result.put(rs.getString("status"), rs.getInt("cnt"));
            }
        } catch (SQLException e) {
            System.err.println("[DB] Lỗi khi thống kê theo trạng thái: " + e.getMessage());
        }
        return result;
    }

    /**
     * NÂNG CẤP THỐNG KÊ: đếm số lần cảnh báo theo từng khung giờ trong ngày (0h-23h),
     * dùng để vẽ biểu đồ cột "giờ nào hay xảy ra sự cố nhất" trên StatsPanel.
     */
    public static synchronized Map<Integer, Integer> countWarningsByHour() {
        Map<Integer, Integer> result = new TreeMap<>();
        for (int h = 0; h < 24; h++) result.put(h, 0);
        String sql = "SELECT CAST(strftime('%H', log_time) AS INTEGER) AS hour, COUNT(*) AS cnt " +
                "FROM temperature_log WHERE status = 'WARNING' GROUP BY hour";
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
     * true nếu bảng product CHƯA TỪNG có bản ghi nào (kể cả đã bị xóa hết) - dùng để chỉ nạp sản phẩm mẫu
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
        try (PreparedStatement ps1 = getConnection().prepareStatement("DELETE FROM warehouse_product WHERE product_id = ?");
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

    /** Như getAllProducts nhưng ném lỗi - để ProductUtil giữ snapshot cũ thay vì thay bằng danh sách rỗng */
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

    /** Lấy id của kho theo tên; chưa có thì tạo dòng mới (active=0, chưa rõ loại) để gán sản phẩm trước khi cảm biến xuất hiện */
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
                if (rs.next()) return rs.getInt(1);
            }
        }
        throw new SQLException("Không tìm thấy/tạo được kho '" + name + "'");
    }

    /** @return false nếu ghi lỗi (gán trùng được coi là thành công vì kết quả cuối cùng như nhau) */
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

    /** Như getAllAssignments nhưng ném lỗi - để ProductUtil giữ snapshot cũ thay vì coi như "chưa gán sản phẩm nào" */
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

    /** Xóa toàn bộ lịch sử đo của 1 kho khỏi database (dùng khi xóa hẳn kho khỏi hệ thống). KHÔNG thể hoàn tác. */
    public static synchronized void deleteWarehouseLogs(String warehouseName) {
        String sql = "DELETE FROM temperature_log WHERE warehouse_name = ?";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, warehouseName);
            int n = ps.executeUpdate();
            System.out.println("[DB] Đã xóa " + n + " bản ghi lịch sử của kho '" + warehouseName + "'.");
        } catch (SQLException e) {
            System.err.println("[DB] Lỗi khi xóa lịch sử kho: " + e.getMessage());
        }
        // "Xóa luôn lịch sử" phải gồm cả lịch sử sự cố, nếu không tab "Lịch sử sự cố" và báo cáo vẫn hiện kho đã xóa
        try (PreparedStatement ps = getConnection().prepareStatement("DELETE FROM alert_history WHERE warehouse_name = ?")) {
            ps.setString(1, warehouseName);
            ps.executeUpdate();
            OPEN_PEAK.remove(warehouseName);
        } catch (SQLException e) {
            System.err.println("[DB] Lỗi khi xóa lịch sử sự cố của kho: " + e.getMessage());
        }
    }

    /** Gỡ toàn bộ sản phẩm đang gán cho 1 kho (dùng khi xóa hẳn kho khỏi hệ thống) */
    public static synchronized void deleteAllAssignmentsForWarehouse(String warehouseName) {
        String sql = "DELETE FROM warehouse_product WHERE warehouse_id = (SELECT id FROM warehouse WHERE name = ?)";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, warehouseName);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[DB] Lỗi khi gỡ sản phẩm khỏi kho bị xóa: " + e.getMessage());
        }
    }

    /** 1 dòng báo cáo tổng hợp của 1 kho: dùng cho nút "Xuất báo cáo" ở tab Thống kê */
    public static class WarehouseReportRow {
        public final String name;
        public final String type;
        public final String products; // rỗng nếu chưa gán sản phẩm nào
        public final int totalReadings;
        public final int warningReadings;

        public WarehouseReportRow(String name, String type, String products, int totalReadings, int warningReadings) {
            this.name = name;
            this.type = type;
            this.products = products;
            this.totalReadings = totalReadings;
            this.warningReadings = warningReadings;
        }

        public double warningPercent() {
            return totalReadings == 0 ? 0 : 100.0 * warningReadings / totalReadings;
        }
    }

    /** Báo cáo tổng hợp mọi kho ĐANG hoạt động: loại kho, sản phẩm, tổng lần đo, số lần cảnh báo */
    public static synchronized List<WarehouseReportRow> getWarehouseReport() {
        List<WarehouseReportRow> result = new ArrayList<>();
        String sql = "SELECT w.name, w.type, GROUP_CONCAT(DISTINCT p.name) AS products, " +
                "(SELECT COUNT(*) FROM temperature_log t WHERE t.warehouse_name = w.name) AS total, " +
                "(SELECT COUNT(*) FROM temperature_log t WHERE t.warehouse_name = w.name AND t.status = 'WARNING') AS warns " +
                "FROM warehouse w " +
                "LEFT JOIN warehouse_product wp ON wp.warehouse_id = w.id " +
                "LEFT JOIN product p ON p.id = wp.product_id " +
                "WHERE w.active = 1 GROUP BY w.id, w.name ORDER BY w.name";
        try (Statement stmt = getConnection().createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                String products = rs.getString("products");
                result.add(new WarehouseReportRow(rs.getString("name"), rs.getString("type"),
                        products == null ? "" : products, rs.getInt("total"), rs.getInt("warns")));
            }
        } catch (SQLException e) {
            System.err.println("[DB] Lỗi khi lấy báo cáo tổng hợp: " + e.getMessage());
        }
        return result;
    }

    // ============================== LỊCH SỬ SỰ CỐ (alert_history) ==============================

    /** Mở 1 sự cố mới cho kho (gọi khi AlertStateTracker báo START). started_at tính bằng millis. */
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

    /** Nhiệt độ đỉnh điểm đang ghi nhận của sự cố đang mở, theo kho (cache để không query DB ở mỗi gói WARNING) */
    private static final java.util.Map<String, Double> OPEN_PEAK = new java.util.HashMap<>();

    /** Độ lệch (>= 0) của nhiệt độ so với dải an toàn [min, max]; 0 nếu nằm trong dải */
    private static double deviation(double t, double min, double max) {
        if (t > max) return t - max;
        if (t < min) return min - t;
        return 0;
    }

    /**
     * Cập nhật nhiệt độ đỉnh điểm của sự cố đang mở: đỉnh điểm = nhiệt độ LỆCH XA dải an toàn nhất
     * (cao hơn max thì càng cao càng tệ, thấp hơn min thì càng thấp càng tệ). Gọi ở MỌI gói WARNING,
     * không chỉ lúc nhắc lại, để sự cố ngắn (< 5 phút) vẫn ghi đúng mức nghiêm trọng thật sự.
     */
    public static synchronized void updateAlertPeak(String warehouseName, double temperature, double safeMin, double safeMax) {
        try {
            Double cur = OPEN_PEAK.get(warehouseName);
            if (cur == null) { // chưa có trong cache (vd. khởi động lại khi sự cố còn mở) -> đọc từ DB
                String q = "SELECT peak_temp FROM alert_history WHERE warehouse_name = ? AND ended_at IS NULL ORDER BY id DESC LIMIT 1";
                try (PreparedStatement ps = getConnection().prepareStatement(q)) {
                    ps.setString(1, warehouseName);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) return; // không có sự cố đang mở
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
     * Gọi 1 lần khi Server khởi động (sau initDatabase). Trạng thái cảnh báo nằm trong bộ nhớ nên bị mất khi tắt máy,
     * để lại sự cố "đang diễn ra" (ended_at NULL) trong DB. Xử lý:
     *  - kho CÒN hoạt động: giữ sự cố MỚI NHẤT mở và trả về {kho -> started_at} để AlertStateTracker TIẾP TỤC sự cố đó
     *    (kho vẫn vượt ngưỡng -> không mở sự cố trùng; kho đã ổn -> gói SAFE đầu tiên sẽ đóng đúng sự cố này);
     *  - sự cố CŨ hơn của cùng kho (mồ côi do lỗi trước đây): đóng, kết thúc = lúc sự cố kế tiếp bắt đầu;
     *  - kho ĐÃ BỊ XÓA (active=0): đóng, kết thúc = lần cuối cảm biến thật gửi dữ liệu.
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
                            .add(new long[]{rs.getLong("id"), rs.getLong("started_at")});
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
        try (PreparedStatement ps = getConnection().prepareStatement("UPDATE alert_history SET ended_at = ? WHERE id = ?")) {
            ps.setLong(1, endedAt);
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    /** true nếu kho có dòng trong sổ đăng ký với active=0 (đã bị xóa). Không có dòng = chưa từng đăng ký -> KHÔNG coi là đã xóa. */
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
                    if (!rs.wasNull()) return v;
                }
            }
        }
        return fallback;
    }

    /** Đóng MỌI sự cố đang mở của kho (dùng khi xóa kho). Giờ kết thúc = endedAt, nhưng không nhỏ hơn giờ bắt đầu. */
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

    /** Đóng sự cố đang mở gần nhất của kho (gọi khi AlertStateTracker báo RECOVERED) */
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

    /** Toàn bộ lịch sử sự cố, mới nhất trước. Sự cố còn đang mở (ended_at = NULL) hiện "Đang diễn ra" ở tầng hiển thị. */
    public static synchronized List<AlertIncident> getAlertHistory(int limit) {
        List<AlertIncident> result = new ArrayList<>();
        String sql = "SELECT warehouse_name, started_at, ended_at, peak_temp FROM alert_history " +
                "ORDER BY started_at DESC LIMIT ?";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long ended = rs.getObject("ended_at") == null ? -1L : rs.getLong("ended_at");
                    result.add(new AlertIncident(rs.getString("warehouse_name"), rs.getLong("started_at"),
                            ended, rs.getDouble("peak_temp")));
                }
            }
        } catch (SQLException e) {
            System.err.println("[DB] Lỗi khi đọc lịch sử sự cố: " + e.getMessage());
        }
        return result;
    }

    /** 1 dòng lịch sử sự cố: kho, bắt đầu, kết thúc (-1 nếu đang diễn ra), nhiệt độ đỉnh điểm */
    public static class AlertIncident {
        public final String warehouseName;
        public final long startedAt;
        public final long endedAt; // -1 nếu đang diễn ra
        public final double peakTemp;

        public AlertIncident(String warehouseName, long startedAt, long endedAt, double peakTemp) {
            this.warehouseName = warehouseName;
            this.startedAt = startedAt;
            this.endedAt = endedAt;
            this.peakTemp = peakTemp;
        }

        public boolean isOngoing() {
            return endedAt < 0;
        }
    }

    /** Đóng connection dùng chung, gọi khi Server tắt để giải phóng tài nguyên sạch sẽ */
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