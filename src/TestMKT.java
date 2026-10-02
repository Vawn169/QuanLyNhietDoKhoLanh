import coldstorage.util.DatabaseUtil;
import java.util.List;
import java.sql.Connection;
import java.sql.Statement;

public class TestMKT {
    public static void main(String[] args) throws Exception {
        // Test manual calculation for 4C, 6C, 8C
        double[] tempsC = {4.0, 6.0, 8.0};
        double sumExp = 0;
        for (double tC : tempsC) {
            double tK = tC + 273.15;
            sumExp += Math.exp(-10000.0 / tK);
        }
        double manualMktK = 10000.0 / -Math.log(sumExp / tempsC.length);
        double manualMktC = manualMktK - 273.15;
        System.out.printf("Manual MKT for [4, 6, 8]: %.4f C\n", manualMktC);
        
        // Expected MKT for 4, 6, 8 is around 6.03 - 6.15 depending on rounding. Let's see.

        // Test identical temps
        double[] tempsIdentical = {5.5, 5.5, 5.5};
        double sumExp2 = 0;
        for (double tC : tempsIdentical) {
            double tK = tC + 273.15;
            sumExp2 += Math.exp(-10000.0 / tK);
        }
        double manualMktK2 = 10000.0 / -Math.log(sumExp2 / tempsIdentical.length);
        double manualMktC2 = manualMktK2 - 273.15;
        System.out.printf("Manual MKT for [5.5, 5.5, 5.5]: %.4f C\n", manualMktC2);
        if (Math.abs(manualMktC2 - 5.5) > 0.01) {
            System.out.println("ERROR: Identical temps should yield exactly that temp.");
            return;
        }

        // Test in DB
        System.out.println("\nPreparing DB for TestMKT...");
        DatabaseUtil.initDatabase();
        String testWh = "TestWH_MAT";
        DatabaseUtil.deleteWarehouseLogs(testWh); // Clean up
        
        try (Connection c = java.sql.DriverManager.getConnection("jdbc:sqlite:coldstorage.db");
             Statement stmt = c.createStatement()) {
            
            // Setup active MAT warehouse with Vac-xin product
            stmt.executeUpdate("INSERT OR IGNORE INTO product(name, min_temp, max_temp) VALUES('Vắc-xin COVID', 2.0, 8.0)");
            stmt.executeUpdate("INSERT OR REPLACE INTO warehouse(name, type, active) VALUES('" + testWh + "', 'MAT', 1)");
            stmt.executeUpdate("INSERT OR IGNORE INTO warehouse_product(warehouse_id, product_id) " +
                               "SELECT w.id, p.id FROM warehouse w, product p WHERE w.name='" + testWh + "' AND p.name='Vắc-xin COVID'");
            
            // Insert into hourly_stats for 3 hours
            long t1 = 1700000000000L;
            long t2 = t1 + 3600000L;
            long t3 = t2 + 3600000L;
            stmt.executeUpdate(String.format("INSERT INTO hourly_stats(warehouse_name, hour_bucket, count_total, sum_temp, min_temp, max_temp) VALUES('%s', %d, 1, 4.0, 4.0, 4.0)", testWh, t1));
            stmt.executeUpdate(String.format("INSERT INTO hourly_stats(warehouse_name, hour_bucket, count_total, sum_temp, min_temp, max_temp) VALUES('%s', %d, 1, 6.0, 6.0, 6.0)", testWh, t2));
            stmt.executeUpdate(String.format("INSERT INTO hourly_stats(warehouse_name, hour_bucket, count_total, sum_temp, min_temp, max_temp) VALUES('%s', %d, 1, 8.0, 8.0, 8.0)", testWh, t3));
        }

        List<DatabaseUtil.WarehouseReportRow> report = DatabaseUtil.getWarehouseReport(null, null);
        DatabaseUtil.WarehouseReportRow ourRow = null;
        for (DatabaseUtil.WarehouseReportRow r : report) {
            if (testWh.equals(r.name)) ourRow = r;
        }

        if (ourRow == null) {
            System.out.println("Test failed: Warehouse not found in report");
            return;
        }

        System.out.printf("DB calculated MKT: %.4f C\n", ourRow.mkt);
        
        double expectedMKT = 6.16; // Giá trị tham chiếu cố định (đã được làm tròn)
        System.out.printf("Expected MKT (Hardcoded): %.4f C\n", expectedMKT);
        System.out.printf("Actual MKT (From DB): %.4f C\n", ourRow.mkt);
        
        if (Math.abs(ourRow.mkt - expectedMKT) <= 0.01) {
            System.out.println("=> MKT TEST PASS! Giá trị tính toán khớp với tham chiếu.");
        } else {
            System.out.println("=> MKT TEST FAIL! Giá trị tính toán bị lệch so với tham chiếu (> 0.01).");
        }
    }
}
