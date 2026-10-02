import coldstorage.model.TemperatureData;
import coldstorage.util.BackupUtil;
import coldstorage.util.DatabaseUtil;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;

public class TestHourlyStats {

    public static void main(String[] args) throws Exception {
        DatabaseUtil.initDatabase();

        System.out.println("=== TEST 1: insertLog updates hourly_stats ===");
        testInsertLog();

        System.out.println("\n=== TEST 2: Backfill correctly reconstructs hourly_stats ===");
        testBackfill();

        System.out.println("\n=== TEST 3: countByStatus / countWarningsByHour with hourly_stats ===");
        testStatsRead();
    }

    private static void testInsertLog() throws Exception {
        String testWh = "TestWH_Hourly";
        DatabaseUtil.deleteWarehouseLogs(testWh); // Clean up if previous run failed

        long baseTime = 1700000000000L; // some static time
        
        // Insert 10 SAFE, 5 WARNING, 5 BACKUP
        for (int i = 0; i < 10; i++) {
            TemperatureData data = new TemperatureData("SENS-1", testWh, "DONG", -18.0, baseTime + i * 1000, null);
            DatabaseUtil.insertLog(data, "SAFE");
        }
        for (int i = 0; i < 5; i++) {
            TemperatureData data = new TemperatureData("SENS-1", testWh, "DONG", -10.0, baseTime + 20000 + i * 1000, null);
            DatabaseUtil.insertLog(data, "WARNING");
        }
        for (int i = 0; i < 5; i++) {
            TemperatureData data = new TemperatureData(BackupUtil.SENSOR_PREFIX + "1", testWh, "DONG", -15.0, baseTime + 40000 + i * 1000, null);
            DatabaseUtil.insertLog(data, "SAFE");
        }

        // Check hourly_stats directly
        // Base time hour bucket
        long hourBucket = (baseTime / 3600000L) * 3600000L;
        
        // We expect: count_total = 15, count_warning = 5, min_temp = -18.0, max_temp = -10.0
        // Reflection or just a quick query since we are in same package, wait we don't have direct DB access.
        // I will use a direct JDBC query for test.
        try (Connection c = java.sql.DriverManager.getConnection("jdbc:sqlite:coldstorage.db");
             Statement stmt = c.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM hourly_stats WHERE warehouse_name = '" + testWh + "'")) {
             
             if (rs.next()) {
                 int total = rs.getInt("count_total");
                 int warn = rs.getInt("count_warning");
                 double min = rs.getDouble("min_temp");
                 double max = rs.getDouble("max_temp");
                 
                 System.out.println("count_total: " + total + " (Expected 15)");
                 System.out.println("count_warning: " + warn + " (Expected 5)");
                 System.out.println("min_temp: " + min + " (Expected -18.0)");
                 System.out.println("max_temp: " + max + " (Expected -10.0)");
                 
                 if (total == 15 && warn == 5 && min == -18.0 && max == -10.0) {
                     System.out.println("=> PASS");
                 } else {
                     System.out.println("=> FAIL");
                 }
             } else {
                 System.out.println("=> FAIL (No row found)");
             }
        }
        DatabaseUtil.deleteWarehouseLogs(testWh);
    }

    private static void testBackfill() throws Exception {
        // We'll clear hourly_stats and let initDatabase run again
        try (Connection c = java.sql.DriverManager.getConnection("jdbc:sqlite:coldstorage.db");
             Statement stmt = c.createStatement()) {
            stmt.executeUpdate("DELETE FROM hourly_stats");
        }
        
        // Get expected counts from temperature_log
        int expectedTotal = 0;
        try (Connection c = java.sql.DriverManager.getConnection("jdbc:sqlite:coldstorage.db");
             Statement stmt = c.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM temperature_log WHERE SUBSTR(sensor_id, 1, 7) != 'BACKUP-'")) {
             if (rs.next()) expectedTotal = rs.getInt(1);
        }

        // Run initDatabase to trigger backfill
        DatabaseUtil.initDatabase();
        
        int actualTotal = 0;
        try (Connection c = java.sql.DriverManager.getConnection("jdbc:sqlite:coldstorage.db");
             Statement stmt = c.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT SUM(count_total) FROM hourly_stats")) {
             if (rs.next()) actualTotal = rs.getInt(1);
        }

        System.out.println("Expected backfill total: " + expectedTotal);
        System.out.println("Actual backfill total  : " + actualTotal);
        if (expectedTotal == actualTotal) {
            System.out.println("=> PASS");
        } else {
            System.out.println("=> FAIL");
        }
    }

    private static void testStatsRead() throws Exception {
        // Measure countByStatus
        long t1 = System.currentTimeMillis();
        Map<String, Integer> byStatus = DatabaseUtil.countByStatus();
        long t2 = System.currentTimeMillis();
        System.out.println("countByStatus(): " + byStatus + " (Time: " + (t2 - t1) + "ms)");

        // Measure countWarningsByHour
        long t3 = System.currentTimeMillis();
        Map<Integer, Integer> byHour = DatabaseUtil.countWarningsByHour();
        long t4 = System.currentTimeMillis();
        System.out.println("countWarningsByHour(): " + byHour + " (Time: " + (t4 - t3) + "ms)");
        
        System.out.println("=> DONE (Verify values manually if needed, or just ensure no exceptions)");
    }
}
