package coldstorage.server;

import org.jfree.chart.ChartFactory;
import org.jfree.chart.StandardChartTheme;
import org.jfree.chart.renderer.category.StandardBarPainter;
import org.jfree.chart.renderer.xy.StandardXYBarPainter;

import javax.swing.*;
import javax.swing.plaf.FontUIResource;
import java.awt.*;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

/**
 * Giao diện chung của ứng dụng: look-and-feel, font, bảng màu và kiểu biểu đồ.
 * Gọi UiTheme.install() 1 lần trong main(), TRƯỚC khi tạo bất kỳ cửa sổ hoặc biểu đồ nào.
 */
public final class UiTheme {

    private UiTheme() {}

    // ---- Bảng màu trạng thái (nền / chữ) ----
    public static final Color SAFE_BG = new Color(198, 239, 206);
    public static final Color SAFE_FG = new Color(0, 97, 0);
    public static final Color WARN_BG = new Color(255, 199, 206);
    public static final Color WARN_FG = new Color(156, 0, 6);
    public static final Color OFFLINE_BG = new Color(222, 226, 230);
    public static final Color OFFLINE_FG = new Color(90, 98, 106);
    public static final Color BACKUP_BG = new Color(255, 235, 156);
    public static final Color BACKUP_FG = new Color(133, 82, 0);

    // ---- Màu nhấn dùng cho thẻ tóm tắt / biểu đồ ----
    public static final Color ACCENT_BLUE = new Color(37, 99, 235);
    public static final Color ACCENT_GREEN = new Color(22, 163, 74);
    public static final Color ACCENT_RED = new Color(220, 38, 38);
    public static final Color ACCENT_GRAY = new Color(107, 114, 128);
    public static final Color ACCENT_ORANGE = new Color(234, 88, 12);
    public static final Color TEXT_DARK = new Color(33, 37, 41);
    public static final Color PAGE_BG = new Color(245, 247, 250);
    public static final Color BAND_FILL = new Color(22, 163, 74, 38); // dải ngưỡng an toàn (xanh nhạt, trong suốt)

    /** Mỗi kho 1 màu cố định (dùng chung cho mọi biểu đồ) */
    private static final Color[] PALETTE = {
            new Color(37, 99, 235), new Color(220, 38, 38), new Color(22, 163, 74),
            new Color(234, 88, 12), new Color(124, 58, 237), new Color(8, 145, 178),
            new Color(219, 39, 119), new Color(101, 163, 13), new Color(180, 83, 9),
            new Color(71, 85, 105)
    };
    private static final Map<String, Color> colorByWarehouse = new HashMap<>();

    /** Màu riêng của 1 kho: kho nào xuất hiện trước lấy màu trước; cùng tên kho luôn cùng màu ở mọi biểu đồ. */
    public static synchronized Color colorFor(String warehouseName) {
        Color c = colorByWarehouse.get(warehouseName);
        if (c == null) {
            c = PALETTE[colorByWarehouse.size() % PALETTE.length];
            colorByWarehouse.put(warehouseName, c);
        }
        return c;
    }

    private static Font baseFont;

    /** Font giao diện chung (Segoe UI nếu máy có, ngược lại SansSerif) */
    public static Font font(int style, float size) {
        if (baseFont == null) baseFont = pickFont();
        return baseFont.deriveFont(style, size);
    }

    private static Font pickFont() {
        for (String family : GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()) {
            if ("Segoe UI".equalsIgnoreCase(family)) return new Font("Segoe UI", Font.PLAIN, 13);
        }
        return new Font(Font.SANS_SERIF, Font.PLAIN, 13);
    }

    /** Cài look-and-feel + font + kiểu biểu đồ. Nếu có FlatLaf trong classpath thì dùng, không thì dùng Nimbus có sẵn. */
    public static void install() {
        boolean ok = false;
        try {
            Class.forName("com.formdev.flatlaf.FlatLightLaf").getMethod("setup").invoke(null);
            ok = true;
        } catch (Throwable ignored) {
            // không có FlatLaf -> dùng Nimbus
        }
        if (!ok) {
            try {
                for (UIManager.LookAndFeelInfo info : UIManager.getInstalledLookAndFeels()) {
                    if ("Nimbus".equals(info.getName())) {
                        UIManager.setLookAndFeel(info.getClassName());
                        ok = true;
                        break;
                    }
                }
            } catch (Exception ignored) {
                // giữ giao diện mặc định
            }
        }

        FontUIResource uiFont = new FontUIResource(font(Font.PLAIN, 13f));
        UIManager.put("defaultFont", uiFont); // Nimbus
        for (Enumeration<Object> keys = UIManager.getDefaults().keys(); keys.hasMoreElements(); ) {
            Object key = keys.nextElement();
            if (key.toString().endsWith(".font")) UIManager.put(key, uiFont);
        }
        UIManager.put("TabbedPane.font", new FontUIResource(font(Font.BOLD, 13f)));
        UIManager.put("TableHeader.font", new FontUIResource(font(Font.BOLD, 13f)));

        installChartTheme();
    }

    private static void installChartTheme() {
        StandardChartTheme theme = new StandardChartTheme("ColdStorage");
        theme.setExtraLargeFont(font(Font.BOLD, 15f));   // tiêu đề biểu đồ
        theme.setLargeFont(font(Font.PLAIN, 12f));       // tiêu đề trục
        theme.setRegularFont(font(Font.PLAIN, 12f));     // chú giải, nhãn
        theme.setSmallFont(font(Font.PLAIN, 11f));       // số trên trục
        theme.setTitlePaint(TEXT_DARK);
        theme.setChartBackgroundPaint(Color.WHITE);
        theme.setPlotBackgroundPaint(Color.WHITE);
        theme.setPlotOutlinePaint(new Color(210, 214, 220));
        theme.setDomainGridlinePaint(new Color(228, 231, 235));
        theme.setRangeGridlinePaint(new Color(228, 231, 235));
        theme.setLegendBackgroundPaint(Color.WHITE);
        theme.setAxisLabelPaint(TEXT_DARK);
        theme.setTickLabelPaint(new Color(75, 85, 99));
        theme.setBarPainter(new StandardBarPainter());
        theme.setXYBarPainter(new StandardXYBarPainter());
        theme.setShadowVisible(false);
        ChartFactory.setChartTheme(theme);
    }
}
