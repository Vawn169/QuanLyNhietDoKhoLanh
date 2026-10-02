import coldstorage.model.TemperatureData;
import coldstorage.server.TemperatureChartPanel;
import coldstorage.util.DatabaseUtil;
import coldstorage.server.UiTheme;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.sql.Connection;
import java.sql.Statement;

public class TestChartVisuals {
    public static void main(String[] args) throws Exception {
        UiTheme.install();
        DatabaseUtil.initDatabase();
        String testWh = "TestWH_Dong";
        DatabaseUtil.deleteWarehouseLogs(testWh); // Clean up
        
        try (Connection c = java.sql.DriverManager.getConnection("jdbc:sqlite:coldstorage.db");
             Statement stmt = c.createStatement()) {
            
            stmt.executeUpdate("INSERT OR REPLACE INTO warehouse(name, type, active) VALUES('" + testWh + "', 'DONG', 1)");
            
            // Insert 24h data
            long now = System.currentTimeMillis();
            long t1 = now - 24 * 3600000L;
            
            // Generate some fake alerts
            // Alert 1: 5 hours ago to 4 hours ago
            DatabaseUtil.startAlertIncident(testWh, now - 5 * 3600000L, -10.0);
            DatabaseUtil.closeAlertIncident(testWh, now - 4 * 3600000L);
            
            // Alert 2: 30 minutes ago to 10 minutes ago
            DatabaseUtil.startAlertIncident(testWh, now - 30 * 60000L, -8.0);
            DatabaseUtil.closeAlertIncident(testWh, now - 10 * 60000L);
            
            System.out.println("Inserting fake data for 24h...");
            for (long t = t1; t <= now; t += 60000L) { // every minute
                double temp = -18.0 + Math.sin(t / 3600000.0) * 2; // oscillates between -20 and -16
                // Make the temp high during alerts
                if (t >= now - 5 * 3600000L && t <= now - 4 * 3600000L) temp = -10.0;
                if (t >= now - 30 * 60000L && t <= now - 10 * 60000L) temp = -8.0;
                
                TemperatureData data = new TemperatureData("SENS-1", testWh, "DONG", temp, t, null);
                DatabaseUtil.insertLog(data, temp > -15.0 ? "WARNING" : "SAFE");
            }
        }

        // Tạo UI off-screen
        TemperatureChartPanel chartPanel = new TemperatureChartPanel("Kho đông - nhiệt độ theo thời gian");
        chartPanel.setSize(800, 400); // Kích thước để render

        // Helper capture method
        Runnable capture = new Runnable() {
            int idx = 0;
            @Override
            public void run() {
                try {
                    Thread.sleep(1000); // Đợi render & load xong
                    BufferedImage img = new BufferedImage(chartPanel.getWidth(), chartPanel.getHeight(), BufferedImage.TYPE_INT_ARGB);
                    chartPanel.paint(img.getGraphics());
                    File f = new File("chart_mode_" + idx + ".png");
                    ImageIO.write(img, "png", f);
                    System.out.println("Đã lưu ảnh " + f.getAbsolutePath());
                    idx++;
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        };

        // Gửi data giả như MonitorServer đang chạy (5 min)
        long now = System.currentTimeMillis();
        for (int i = 0; i < 5; i++) {
            chartPanel.addPoint(testWh, now - (4 - i) * 60000L, -17.0 + i * 0.5);
        }
        capture.run(); // Capture 5m

        // Switch to 1h
        JComboBox<String> combo = findCombo(chartPanel);
        long tStart = System.currentTimeMillis();
        combo.setSelectedIndex(1); // 1 giờ
        System.out.println("Thời gian chuyển 1h (từ lúc click, luồng chính không block): " + (System.currentTimeMillis() - tStart) + "ms");
        capture.run();

        // Switch to 24h
        tStart = System.currentTimeMillis();
        combo.setSelectedIndex(2); // 24 giờ
        System.out.println("Thời gian chuyển 24h (từ lúc click, luồng chính không block): " + (System.currentTimeMillis() - tStart) + "ms");
        capture.run();
        
        System.out.println("=> TEST XONG. File ảnh đã được lưu ở thư mục gốc.");
        System.exit(0);
    }

    private static JComboBox<String> findCombo(Container parent) {
        for (Component c : parent.getComponents()) {
            if (c instanceof JComboBox) return (JComboBox<String>) c;
            if (c instanceof Container) {
                JComboBox<String> found = findCombo((Container) c);
                if (found != null) return found;
            }
        }
        return null;
    }
}
