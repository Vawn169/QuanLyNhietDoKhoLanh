import coldstorage.model.TemperatureData;
import coldstorage.server.MonitorServer;
import coldstorage.server.WarehouseCard;
import coldstorage.server.WarehouseCardPanel;
import coldstorage.server.RejectedLogPanel;
import coldstorage.util.DatabaseUtil;
import coldstorage.server.UiTheme;
import coldstorage.server.SensorHandler;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;

public class TestPhase9 {
    public static void main(String[] args) throws Exception {
        UiTheme.install();
        DatabaseUtil.initDatabase();

        // 1. Test thẻ kho
        System.out.println("=== TEST 1: WAREHOUSE CARDS ===");
        WarehouseCardPanel cardPanel = new WarehouseCardPanel();
        cardPanel.setSize(900, 300); // Đủ chỗ cho 3 thẻ
        
        // Thẻ 1: An toàn
        TemperatureData d1 = new TemperatureData("S1", "Kho_AnToan", "DONG", -18.0, System.currentTimeMillis(), null);
        for(int i=0; i<30; i++) cardPanel.updateCard(new TemperatureData("S1", "Kho_AnToan", "DONG", -18.0 + Math.random(), System.currentTimeMillis(), null), false, false, false);
        cardPanel.updateCard(d1, false, false, false); // SAFE
        
        // Thẻ 2: Cảnh báo
        TemperatureData d2 = new TemperatureData("S2", "Kho_CanhBao", "DONG", -5.0, System.currentTimeMillis(), null);
        for(int i=0; i<30; i++) cardPanel.updateCard(new TemperatureData("S2", "Kho_CanhBao", "DONG", -15.0 + i*0.3, System.currentTimeMillis(), null), false, false, false);
        cardPanel.updateCard(d2, false, false, false); // Cảnh báo
        
        // Thẻ 3: Mất tín hiệu
        TemperatureData d3 = new TemperatureData("S3", "Kho_MatTinHieu", "DONG", -19.0, System.currentTimeMillis(), null);
        for(int i=0; i<30; i++) cardPanel.updateCard(new TemperatureData("S3", "Kho_MatTinHieu", "DONG", -19.0, System.currentTimeMillis(), null), true, false, false);
        cardPanel.updateCard(d3, true, false, false); // Offline

        // Cho Swing kịp layout
        SwingUtilities.invokeAndWait(() -> {
            cardPanel.doLayout();
            for(Component c : ((Container)cardPanel.getComponent(0)).getComponents()) {
                c.doLayout();
            }
        });
        
        Thread.sleep(1000);
        
        BufferedImage img = new BufferedImage(cardPanel.getWidth(), cardPanel.getHeight(), BufferedImage.TYPE_INT_ARGB);
        cardPanel.paint(img.getGraphics());
        File f = new File("cards_test.png");
        ImageIO.write(img, "png", f);
        System.out.println("Đã lưu ảnh " + f.getAbsolutePath());


        // 2. Test Rejected Log
        System.out.println("\n=== TEST 2: REJECTED LOGS ===");
        
        // Gói sai IP (từ 192.168.1.100 - mặc định chỉ cho 127.0.0.1)
        System.out.println("Gửi gói từ IP lạ...");
        SensorHandler h1 = new SensorHandler("S1,WH,DONG,-18,0,bad", "192.168.1.100", System.currentTimeMillis(), null);
        h1.run();

        // Gói sai chữ ký
        System.out.println("Gửi gói sai chữ ký...");
        String packetStr = "S2,WH,DONG,-18.0," + System.currentTimeMillis() + ",bad_hmac";
        SensorHandler h2 = new SensorHandler(packetStr, "127.0.0.1", System.currentTimeMillis(), null);
        h2.run();
        
        Thread.sleep(500);

        // Đọc lại từ bảng
        RejectedLogPanel rejPanel = new RejectedLogPanel();
        rejPanel.setSize(800, 300);
        SwingUtilities.invokeAndWait(() -> {
            rejPanel.doLayout();
        });
        
        Thread.sleep(500);
        
        BufferedImage img2 = new BufferedImage(rejPanel.getWidth(), rejPanel.getHeight(), BufferedImage.TYPE_INT_ARGB);
        rejPanel.paint(img2.getGraphics());
        File f2 = new File("rejected_logs_test.png");
        ImageIO.write(img2, "png", f2);
        System.out.println("Đã lưu ảnh " + f2.getAbsolutePath());
        
        System.out.println("=> TEST XONG");
        System.exit(0);
    }
}
