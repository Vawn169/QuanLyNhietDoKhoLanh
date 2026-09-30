package coldstorage.server;

import coldstorage.model.TemperatureData;
import coldstorage.util.DatabaseUtil;
import coldstorage.util.ProductUtil;

import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.CategoryLabelPositions;
import org.jfree.chart.axis.DateAxis;
import org.jfree.chart.axis.DateTickUnit;
import org.jfree.chart.axis.DateTickUnitType;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.labels.StandardPieSectionLabelGenerator;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.chart.plot.IntervalMarker;
import org.jfree.chart.plot.PiePlot;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.category.BarRenderer;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.chart.ui.Layer;
import org.jfree.chart.ui.RectangleInsets;
import org.jfree.data.category.DefaultCategoryDataset;
import org.jfree.data.general.DefaultPieDataset;
import org.jfree.data.time.Millisecond;
import org.jfree.data.time.TimeSeries;
import org.jfree.data.time.TimeSeriesCollection;

import javax.swing.*;
import java.awt.*;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Tab thống kê tổng quan, bổ sung cho bảng trạng thái + biểu đồ đường vốn có.
 * - Biểu đồ tròn: tỉ lệ bản ghi AN TOÀN / CẢNH BÁO trên toàn bộ lịch sử.
 * - Biểu đồ cột: số lần đo vượt ngưỡng phân theo từng khung giờ trong ngày.
 * - Biểu đồ đường RIÊNG cho TỪNG KHO đang hoạt động (mỗi kho 1 biểu đồ, xếp lưới 2 cột, cuộn dọc):
 *   vẽ lại từ database, chỉ lấy WINDOW_MS (5 phút) gần nhất của kho; kho mới thêm / kho bị xóa tự xuất hiện / biến mất.
 * Dữ liệu được làm mới định kỳ bằng javax.swing.Timer, còn TRUY VẤN DATABASE chạy trên luồng nền (SwingWorker)
 * để giao diện không bị đứng; chỉ việc cập nhật biểu đồ mới chạy trên luồng Swing.
 */
public class StatsPanel extends JPanel {

    private static final int MAX_POINTS = 300;          // số điểm đo gần nhất đọc từ database cho mỗi kho
    private static final long WINDOW_MS = 5 * 60 * 1000; // chỉ vẽ 5 phút gần nhất tính từ điểm đo mới nhất của kho
    private static final long GAP_MS = 30 * 1000;       // cách nhau hơn 30 giây -> ngắt đường (không nối qua khoảng trống)
    private static final int TOP_ROW_HEIGHT = 320;
    private static final int LINE_CHART_HEIGHT = 280;

    private final DefaultPieDataset<String> pieDataset;
    private final DefaultCategoryDataset barDataset;

    private final JPanel linesGrid = new JPanel(new GridLayout(0, 2, 10, 10));
    /** Đường nhiệt độ của từng kho (giữ thứ tự tên kho) - chỉ đụng tới trên luồng Swing */
    private final Map<String, TimeSeries> seriesByWarehouse = new LinkedHashMap<>();
    private final Map<String, XYPlot> plotByWarehouse = new LinkedHashMap<>();

    public StatsPanel() {
        setLayout(new BorderLayout());

        // ----- Hàng trên: biểu đồ tròn + biểu đồ cột (như cũ) -----
        JPanel topRow = new JPanel(new GridLayout(1, 2, 10, 10));
        topRow.setPreferredSize(new Dimension(0, TOP_ROW_HEIGHT));

        pieDataset = new DefaultPieDataset<>();
        JFreeChart pieChart = ChartFactory.createPieChart(
                "Tỉ lệ An toàn / Cảnh báo", pieDataset, true, true, false);
        stylePie(pieChart);
        topRow.add(new ChartPanel(pieChart));

        barDataset = new DefaultCategoryDataset();
        JFreeChart barChart = ChartFactory.createBarChart(
                "Số lần đo vượt ngưỡng theo giờ trong ngày", "Giờ trong ngày", "Số lần vượt ngưỡng",
                barDataset, PlotOrientation.VERTICAL, false, true, false);
        styleBar(barChart);
        topRow.add(new ChartPanel(barChart));

        // ----- Tiêu đề phần biểu đồ đường từng kho -----
        JLabel linesTitle = new JLabel("Nhiệt độ theo thời gian của từng kho  (dải xanh = ngưỡng an toàn)");
        linesTitle.setFont(UiTheme.font(Font.BOLD, 15f));
        linesTitle.setForeground(UiTheme.TEXT_DARK);
        linesTitle.setBorder(BorderFactory.createEmptyBorder(12, 4, 6, 4));

        // ----- Thanh công cụ: xuất báo cáo -----
        JButton exportBtn = new JButton("⭳ Xuất báo cáo (CSV)");
        exportBtn.setToolTipText("Xuất tổng hợp theo kho + lịch sử sự cố ra file CSV, mở trực tiếp bằng Excel");
        exportBtn.addActionListener(e -> exportReport());
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
        toolbar.add(exportBtn);

        JPanel header = new JPanel(new BorderLayout());
        header.add(toolbar, BorderLayout.NORTH);
        header.add(topRow, BorderLayout.CENTER);
        header.add(linesTitle, BorderLayout.SOUTH);

        // ----- Nội dung cuộn dọc: hàng trên + lưới biểu đồ đường -----
        ScrollableContent content = new ScrollableContent(new BorderLayout());
        content.setBackground(UiTheme.PAGE_BG);
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        topRow.setOpaque(false);
        header.setOpaque(false);
        linesGrid.setOpaque(false);
        content.add(header, BorderLayout.NORTH);
        content.add(linesGrid, BorderLayout.CENTER);

        JScrollPane scrollPane = new JScrollPane(content,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.getVerticalScrollBar().setUnitIncrement(24);
        scrollPane.setBorder(null);
        scrollPane.getViewport().setBackground(UiTheme.PAGE_BG);
        add(scrollPane, BorderLayout.CENTER);

        refreshData();
        Timer timer = new Timer(10_000, e -> refreshData()); // làm mới mỗi 10 giây
        timer.start();
    }

    /** Kết quả truy vấn thống kê, tính trên luồng nền rồi chuyển sang luồng Swing */
    private static class Stats {
        final Map<String, Integer> statusCounts = DatabaseUtil.countByStatus();
        final Map<Integer, Integer> hourlyWarnings = DatabaseUtil.countWarningsByHour();
        /** kho đang hoạt động (theo tên) -> các điểm đo gần nhất, thời gian tăng dần */
        final Map<String, List<TemperatureData>> perWarehouse = new TreeMap<>();
        final Map<String, String> warehouseTypes = DatabaseUtil.getActiveWarehouseTypes();

        Stats() {
            for (String name : warehouseTypes.keySet()) {
                perWarehouse.put(name, DatabaseUtil.getRecentLogs(name, MAX_POINTS));
            }
        }
    }

    /** Chọn nơi lưu rồi xuất báo cáo CSV (tổng hợp theo kho + lịch sử sự cố). Truy vấn DB chạy nền, không treo UI. */
    private void exportReport() {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new java.io.File(
                "bao_cao_kho_lanh_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmm").format(new Date()) + ".csv"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        java.io.File file = chooser.getSelectedFile();
        if (!file.getName().toLowerCase().endsWith(".csv")) {
            file = new java.io.File(file.getParentFile(), file.getName() + ".csv");
        }
        final java.io.File target = file;

        new SwingWorker<Void, Void>() {
            String error = null;

            @Override
            protected Void doInBackground() {
                try {
                    coldstorage.util.ReportExportUtil.export(target);
                } catch (Exception ex) {
                    error = ex.getMessage();
                }
                return null;
            }

            @Override
            protected void done() {
                if (error != null) {
                    JOptionPane.showMessageDialog(StatsPanel.this, "Xuất báo cáo thất bại: " + error,
                            "Lỗi", JOptionPane.ERROR_MESSAGE);
                } else {
                    JOptionPane.showMessageDialog(StatsPanel.this,
                            "Đã xuất báo cáo:\n" + target.getAbsolutePath() +
                            "\n\nCó thể mở trực tiếp bằng Excel (double-click), hoặc File > Open trong Excel.");
                }
            }
        }.execute();
    }

    /** Truy vấn lại dữ liệu thống kê (luồng nền) rồi cập nhật các biểu đồ (luồng Swing) */
    public void refreshData() {
        new SwingWorker<Stats, Void>() {
            @Override
            protected Stats doInBackground() {
                return new Stats();
            }

            @Override
            protected void done() {
                try {
                    Stats stats = get();
                    pieDataset.clear();
                    pieDataset.setValue("An toàn", stats.statusCounts.getOrDefault("SAFE", 0));
                    pieDataset.setValue("Cảnh báo", stats.statusCounts.getOrDefault("WARNING", 0));

                    barDataset.clear();
                    for (Map.Entry<Integer, Integer> entry : stats.hourlyWarnings.entrySet()) {
                        String hourLabel = String.valueOf(entry.getKey()); // 0..23 (ngắn để không bị cắt thành "...")
                        barDataset.addValue(entry.getValue(), "Cảnh báo", hourLabel);
                    }

                    updateWarehouseCharts(stats);
                } catch (Exception e) {
                    System.err.println("[StatsPanel] Lỗi làm mới thống kê: " + e.getMessage());
                }
            }
        }.execute();
    }

    /** Dựng lại lưới khi danh sách kho thay đổi, rồi nạp lại dữ liệu cho từng đường. Chạy trên luồng Swing. */
    private void updateWarehouseCharts(Stats stats) {
        List<String> names = new ArrayList<>(stats.perWarehouse.keySet());
        if (!names.equals(new ArrayList<>(seriesByWarehouse.keySet()))) {
            rebuildGrid(names, stats.warehouseTypes);
        }
        for (Map.Entry<String, List<TemperatureData>> entry : stats.perWarehouse.entrySet()) {
            TimeSeries series = seriesByWarehouse.get(entry.getKey());
            if (series == null) continue;
            series.setNotify(false);
            series.clear();
            long last = -1;
            List<TemperatureData> points = entry.getValue();
            long newest = points.isEmpty() ? 0 : points.get(points.size() - 1).getTimestamp();
            for (TemperatureData d : points) {
                long t = d.getTimestamp();
                if (newest - t > WINDOW_MS) continue; // bỏ dữ liệu cũ của các lần chạy trước để đường không bị nén lại
                if (last >= 0 && t - last > GAP_MS) {
                    // điểm rỗng ngay sau điểm cuối cũ: JFreeChart không nối đường qua giá trị rỗng
                    series.addOrUpdate(new Millisecond(new Date(last + 1)), (Number) null);
                }
                series.addOrUpdate(new Millisecond(new Date(t)), d.getTemperature());
                last = t;
            }
            series.setNotify(true);
            series.fireSeriesChanged();
            updateThresholdBand(entry.getKey(), stats.warehouseTypes.get(entry.getKey()), points, newest);
        }
    }

    /** Vẽ dải ngưỡng an toàn (xanh nhạt) của kho và co giãn trục nhiệt độ để chứa cả dải lẫn dữ liệu */
    private void updateThresholdBand(String name, String type, List<TemperatureData> points, long newest) {
        XYPlot plot = plotByWarehouse.get(name);
        if (plot == null) return;
        double[] range = ProductUtil.getSafeRange(name, type);
        plot.clearRangeMarkers();
        IntervalMarker band = new IntervalMarker(range[0], range[1]);
        band.setPaint(UiTheme.BAND_FILL);
        band.setOutlinePaint(new Color(22, 163, 74, 140));
        band.setOutlineStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{5f, 4f}, 0f));
        plot.addRangeMarker(band, Layer.BACKGROUND);

        double lo = range[0], hi = range[1];
        for (TemperatureData d : points) {
            if (newest - d.getTimestamp() > WINDOW_MS) continue;
            lo = Math.min(lo, d.getTemperature());
            hi = Math.max(hi, d.getTemperature());
        }
        double pad = Math.max((hi - lo) * 0.12, 0.5);
        plot.getRangeAxis().setRange(lo - pad, hi + pad);
    }

    private void stylePie(JFreeChart chart) {
        @SuppressWarnings("unchecked")
        PiePlot<String> plot = (PiePlot<String>) chart.getPlot();
        plot.setSectionPaint("An toàn", UiTheme.ACCENT_GREEN);
        plot.setSectionPaint("Cảnh báo", UiTheme.ACCENT_RED);
        plot.setShadowPaint(null);
        plot.setOutlineVisible(false);
        plot.setBackgroundPaint(Color.WHITE);
        plot.setSectionOutlinesVisible(true);
        plot.setDefaultSectionOutlinePaint(Color.WHITE);
        plot.setLabelGenerator(new StandardPieSectionLabelGenerator("{0}: {1} ({2})"));
        plot.setLabelBackgroundPaint(Color.WHITE);
        plot.setLabelOutlinePaint(new Color(210, 214, 220));
        plot.setLabelShadowPaint(null);
        plot.setSimpleLabels(false);
    }

    private void styleBar(JFreeChart chart) {
        CategoryPlot plot = chart.getCategoryPlot();
        BarRenderer renderer = (BarRenderer) plot.getRenderer();
        renderer.setSeriesPaint(0, UiTheme.ACCENT_ORANGE);
        renderer.setMaximumBarWidth(0.06);
        renderer.setShadowVisible(false);
        plot.getDomainAxis().setCategoryMargin(0.15);
        plot.getDomainAxis().setTickLabelFont(UiTheme.font(Font.PLAIN, 11f));
        plot.getDomainAxis().setCategoryLabelPositions(CategoryLabelPositions.STANDARD);
        ((NumberAxis) plot.getRangeAxis()).setStandardTickUnits(NumberAxis.createIntegerTickUnits());
        chart.setPadding(new RectangleInsets(4, 6, 4, 10));
    }

    private void rebuildGrid(List<String> names, Map<String, String> types) {
        linesGrid.removeAll();
        seriesByWarehouse.clear();
        plotByWarehouse.clear();
        if (names.isEmpty()) {
            linesGrid.setLayout(new BorderLayout());
            linesGrid.add(new JLabel("Chưa có kho nào đang hoạt động.", SwingConstants.CENTER), BorderLayout.CENTER);
        } else {
            linesGrid.setLayout(new GridLayout(0, 2, 10, 10));
            for (String name : names) {
                TimeSeries series = new TimeSeries(name);
                seriesByWarehouse.put(name, series);
                ChartPanel cp = createLineChart(name, types.get(name), series);
                plotByWarehouse.put(name, cp.getChart().getXYPlot());
                linesGrid.add(cp);
            }
        }
        linesGrid.revalidate();
        linesGrid.repaint();
    }

    private ChartPanel createLineChart(String name, String type, TimeSeries series) {
        String typeLabel = "DONG".equals(type) ? "kho đông" : "MAT".equals(type) ? "kho mát" : "";
        String title = typeLabel.isEmpty() ? name : name + " (" + typeLabel + ")";

        JFreeChart chart = ChartFactory.createTimeSeriesChart(
                title, "Thời gian", "Nhiệt độ (°C)", new TimeSeriesCollection(series), false, true, false);
        chart.setPadding(new RectangleInsets(4, 6, 4, 18)); // chừa chỗ để nhãn giờ cuối không bị cắt
        XYPlot plot = chart.getXYPlot();
        XYLineAndShapeRenderer renderer = new XYLineAndShapeRenderer(true, false);
        renderer.setSeriesPaint(0, UiTheme.colorFor(name)); // cùng màu với biểu đồ thời gian thực
        renderer.setAutoPopulateSeriesStroke(false); // nếu không, nét mặc định của theme sẽ đè lên nét dày
        renderer.setDefaultStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        plot.setRenderer(renderer);
        DateAxis timeAxis = (DateAxis) plot.getDomainAxis();
        timeAxis.setDateFormatOverride(new SimpleDateFormat("HH:mm"));
        timeAxis.setTickUnit(new DateTickUnit(DateTickUnitType.MINUTE, 1));
        ((NumberAxis) plot.getRangeAxis()).setAutoRangeIncludesZero(false);

        ChartPanel panel = new ChartPanel(chart);
        panel.setPreferredSize(new Dimension(420, LINE_CHART_HEIGHT));
        panel.setBorder(BorderFactory.createLineBorder(new Color(224, 228, 233)));
        return panel;
    }

    /** Panel nội dung cuộn: luôn co giãn theo chiều rộng khung nhìn, chỉ cuộn dọc */
    private static class ScrollableContent extends JPanel implements Scrollable {
        ScrollableContent(LayoutManager lm) { super(lm); }
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 24; }
        @Override public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return 120; }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }
}
