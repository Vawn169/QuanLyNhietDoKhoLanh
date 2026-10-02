import coldstorage.server.MonitorServer;
import coldstorage.util.DatabaseUtil;
import coldstorage.server.UiTheme;
import coldstorage.util.ConfigUtil;
import coldstorage.server.LoginDialog;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;

public class TestPhase10 {
    public static void main(String[] args) throws Exception {
        UiTheme.install();
        DatabaseUtil.initDatabase();

        // 1. Khởi tạo server
        MonitorServer server = new MonitorServer();
        server.setVisible(true);

        // Đợi UI render
        Thread.sleep(1000);
        
        // 2. Chụp trạng thái khi CHƯA đăng nhập
        BufferedImage img1 = new BufferedImage(server.getWidth(), server.getHeight(), BufferedImage.TYPE_INT_ARGB);
        server.paint(img1.getGraphics());
        ImageIO.write(img1, "png", new File("phase10_locked.png"));
        System.out.println("Đã lưu ảnh phase10_locked.png");

        // 3. Fake đăng nhập thành công (bypass LoginDialog và kích hoạt sự kiện)
        SwingUtilities.invokeAndWait(() -> {
            ConfigUtil.setAdminPassword("test"); // bypass logic
            // Mô phỏng việc người dùng click nút đăng nhập
            for (Component c : server.getContentPane().getComponents()) {
                if (c instanceof JPanel) {
                    JPanel main = (JPanel) c;
                    for (Component c2 : main.getComponents()) {
                        if (c2 instanceof JPanel && c2.getPreferredSize().width == 200) {
                            // Đây là sidebar
                            JPanel sidebar = (JPanel) c2;
                            for (Component sc : sidebar.getComponents()) {
                                if (sc instanceof JButton && ((JButton)sc).getText().equals("Đăng nhập")) {
                                    // Thay vì click vì nó gọi LoginDialog.authenticate (modal = block), ta kích hoạt luôn enable
                                    for (Component scc : sidebar.getComponents()) {
                                        if (scc instanceof JButton) scc.setEnabled(true);
                                    }
                                    ((JButton)sc).setText("Đã đăng nhập");
                                    ((JButton)sc).setEnabled(false);
                                }
                            }
                        }
                    }
                }
            }
        });

        // Đợi UI render lại
        Thread.sleep(500);

        // Chụp trạng thái khi ĐÃ đăng nhập (nút đã mở)
        BufferedImage img2 = new BufferedImage(server.getWidth(), server.getHeight(), BufferedImage.TYPE_INT_ARGB);
        server.paint(img2.getGraphics());
        ImageIO.write(img2, "png", new File("phase10_unlocked.png"));
        System.out.println("Đã lưu ảnh phase10_unlocked.png");
        
        // 4. Click sang tab Sản phẩm
        SwingUtilities.invokeAndWait(() -> {
            for (Component c : server.getContentPane().getComponents()) {
                if (c instanceof JPanel) {
                    JPanel main = (JPanel) c;
                    for (Component c2 : main.getComponents()) {
                        if (c2 instanceof JPanel && c2.getPreferredSize().width == 200) {
                            JPanel sidebar = (JPanel) c2;
                            for (Component sc : sidebar.getComponents()) {
                                if (sc instanceof JButton && ((JButton)sc).getText().equals("Sản phẩm")) {
                                    ((JButton)sc).doClick();
                                }
                            }
                        }
                    }
                }
            }
        });

        Thread.sleep(500);

        // Chụp trạng thái tab Sản phẩm
        BufferedImage img3 = new BufferedImage(server.getWidth(), server.getHeight(), BufferedImage.TYPE_INT_ARGB);
        server.paint(img3.getGraphics());
        ImageIO.write(img3, "png", new File("phase10_product.png"));
        System.out.println("Đã lưu ảnh phase10_product.png");

        System.out.println("=> TEST XONG");
        System.exit(0);
    }
}
