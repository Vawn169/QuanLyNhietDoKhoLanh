package coldstorage.server;

import coldstorage.model.TemperatureData;
import coldstorage.util.DatabaseUtil;
import coldstorage.util.AlertUtil;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.DateAxis;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.labels.StandardXYToolTipGenerator;
import org.jfree.chart.plot.IntervalMarker;
import org.jfree.chart.plot.Marker;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.XYAreaRenderer;
import org.jfree.chart.ui.Layer;
import org.jfree.chart.ui.RectangleInsets;
import org.jfree.data.time.Millisecond;
import org.jfree.data.time.TimeSeries;
import org.jfree.data.time.TimeSeriesCollection;

import javax.swing.*;
import java.awt.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.ExecutionException;

/**
 * Biểu đồ nhiệt độ theo thời gian cho 1 nhóm kho.
 */
public class TemperatureChartPanel extends JPanel {

    private static final long GAP_MS = 30 * 1000;
    private static final int MAX_POINTS_PER_SERIES = 5000;

    private final TimeSeriesCollection dataset = new TimeSeriesCollection();
    private final Map<String, TimeSeries> seriesByWarehouse = new HashMap<>();
    private final Map<String, Long> lastTimestampByWarehouse = new HashMap<>();
    private final XYPlot plot;
    
    private int currentMode = 0; // 0 = 5 min, 1 = 1 hour, 2 = 24 hours
    private long currentWindowMs = 5 * 60 * 1000L;
    private final DateAxis timeAxis;
    private final String chartType; // "DONG" hoặc "MAT", suy ra từ title
    private final List<Marker> alertMarkers = new ArrayList<>();

    public TemperatureChartPanel(String title) {
        setLayout(new BorderLayout());
        chartType = title.toLowerCase().contains("đông") ? "DONG" : "MAT";

        JFreeChart chart = ChartFactory.createTimeSeriesChart(
                title, "Thời gian", "Nhiệt độ (°C)", dataset, true, true, false);

        chart.setPadding(new RectangleInsets(4, 6, 4, 18));
        plot = chart.getXYPlot();

        // 1. Đổi sang vùng tô gradient / bán trong suốt và bật Tooltip
        XYAreaRenderer renderer = new XYAreaRenderer(XYAreaRenderer.AREA) {
            @Override
            public Paint lookupSeriesPaint(int series) {
                Color c = UiTheme.colorFor(String.valueOf(dataset.getSeriesKey(series)));
                // Trả về màu bán trong suốt để tạo hiệu ứng vùng
                return new Color(c.getRed(), c.getGreen(), c.getBlue(), 80);
            }
            @Override
            public Paint lookupSeriesOutlinePaint(int series) {
                return UiTheme.colorFor(String.valueOf(dataset.getSeriesKey(series))); // Viền đậm
            }
        };
        renderer.setOutline(true);
        
        // 2. Bật tooltip
        renderer.setDefaultToolTipGenerator(new StandardXYToolTipGenerator(
            "{0}: {2}°C lúc {1}", 
            new SimpleDateFormat("HH:mm:ss"), 
            new java.text.DecimalFormat("0.0")
        ));
        plot.setRenderer(renderer);

        // Bật crosshair
        plot.setDomainCrosshairVisible(true);
        plot.setRangeCrosshairVisible(true);

        timeAxis = (DateAxis) plot.getDomainAxis();
        timeAxis.setAutoRange(true);
        timeAxis.setFixedAutoRange(currentWindowMs);
        timeAxis.setDateFormatOverride(new SimpleDateFormat("HH:mm:ss"));

        NumberAxis tempAxis = (NumberAxis) plot.getRangeAxis();
        tempAxis.setAutoRangeIncludesZero(false);

        ChartPanel chartPanel = new ChartPanel(chart);
        chartPanel.setPreferredSize(new Dimension(450, 280));
        add(chartPanel, BorderLayout.CENTER);

        // 3. Thanh công cụ chọn khoảng thời gian
        JPanel toolBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 2));
        toolBar.setOpaque(false);
        String[] modes = {"5 phút", "1 giờ", "24 giờ"};
        JComboBox<String> modeCombo = new JComboBox<>(modes);
        modeCombo.setSelectedIndex(0);
        modeCombo.addActionListener(e -> {
            int sel = modeCombo.getSelectedIndex();
            if (sel != currentMode) {
                currentMode = sel;
                changeTimeRange();
            }
        });
        toolBar.add(new JLabel("Khoảng t.gian:"));
        toolBar.add(modeCombo);
        add(toolBar, BorderLayout.NORTH);
    }

    private void changeTimeRange() {
        if (currentMode == 0) {
            currentWindowMs = 5 * 60 * 1000L;
            timeAxis.setFixedAutoRange(currentWindowMs);
            timeAxis.setDateFormatOverride(new SimpleDateFormat("HH:mm:ss"));
            // Xóa sạch để MonitorServer tự nạp lại điểm mới vào (logic addPoint bình thường)
            clearAll();
        } else if (currentMode == 1) {
            currentWindowMs = 60 * 60 * 1000L;
            timeAxis.setFixedAutoRange(currentWindowMs);
            timeAxis.setDateFormatOverride(new SimpleDateFormat("HH:mm"));
            loadHistoricalData(1);
        } else {
            currentWindowMs = 24 * 60 * 60 * 1000L;
            timeAxis.setFixedAutoRange(currentWindowMs);
            timeAxis.setDateFormatOverride(new SimpleDateFormat("HH:mm"));
            loadHistoricalData(24);
        }
    }

    private void clearAll() {
        dataset.removeAllSeries();
        seriesByWarehouse.clear();
        lastTimestampByWarehouse.clear();
        for (Marker m : alertMarkers) plot.removeDomainMarker(m);
        alertMarkers.clear();
    }

    private void loadHistoricalData(int hours) {
        clearAll();
        long now = System.currentTimeMillis();
        long fromTime = now - hours * 3600000L;

        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                // Background thread: load data from DB and add to series
                List<String> warehouses = DatabaseUtil.getKnownWarehouses();
                List<DatabaseUtil.AlertIncident> incidents = DatabaseUtil.getAlertHistory(1000);
                
                for (String wh : warehouses) {
                    if (!DatabaseUtil.getWarehouseType(wh).equalsIgnoreCase(chartType)) continue;
                    
                    final TimeSeries series = new TimeSeries(wh);
                    series.setMaximumItemCount(MAX_POINTS_PER_SERIES);
                    
                    if (hours == 1) {
                        // 1 hour: use temperature_log
                        List<TemperatureData> history = DatabaseUtil.getLogsInRange(wh, fromTime, now);
                        Long last = null;
                        for (TemperatureData d : history) {
                            if (last != null && d.getTimestamp() - last > GAP_MS) {
                                series.addOrUpdate(new Millisecond(new Date(last + 1)), (Number) null);
                            }
                            series.addOrUpdate(new Millisecond(new Date(d.getTimestamp())), d.getTemperature());
                            last = d.getTimestamp();
                        }
                    } else {
                        // 24 hours: use hourly_stats, but wait, hourly stats are 1 bucket per hour.
                        // We will have just 24 points.
                        List<double[]> stats = DatabaseUtil.getHourlyStatsInRange(wh, fromTime, now);
                        Long last = null;
                        for (double[] row : stats) {
                            long ts = (long) row[0];
                            double avgTemp = row[1];
                            if (last != null && ts - last > 3600000L * 2) { // hole > 2 hours
                                series.addOrUpdate(new Millisecond(new Date(last + 1000)), (Number) null);
                            }
                            series.addOrUpdate(new Millisecond(new Date(ts)), avgTemp);
                            last = ts;
                        }
                    }
                    
                    SwingUtilities.invokeLater(() -> {
                        seriesByWarehouse.put(wh, series);
                        dataset.addSeries(series);
                    });
                }
                
                // Add markers for incidents in this range
                SwingUtilities.invokeLater(() -> {
                    for (DatabaseUtil.AlertIncident inc : incidents) {
                        if (inc.startedAt > now || (inc.endedAt > 0 && inc.endedAt < fromTime)) continue;
                        // check if warehouse is in this chart
                        if (!seriesByWarehouse.containsKey(inc.warehouseName)) continue;
                        
                        long start = Math.max(fromTime, inc.startedAt);
                        long end = inc.endedAt == 0 ? now : Math.min(now, inc.endedAt);
                        if (end > start) {
                            IntervalMarker marker = new IntervalMarker(start, end);
                            marker.setPaint(new Color(255, 0, 0, 40));
                            plot.addDomainMarker(marker, Layer.BACKGROUND);
                            alertMarkers.add(marker);
                        }
                    }
                });
                
                return null;
            }
        }.execute();
    }

    /** Thêm 1 điểm đo cho 1 kho. Gọi trên luồng Swing (EDT). */
    public void addPoint(String warehouseName, long timestamp, double temperature) {
        // Chỉ thêm data live nếu đang ở chế độ 5 phút
        if (currentMode != 0) return;

        TimeSeries series = seriesByWarehouse.get(warehouseName);
        if (series == null) {
            series = new TimeSeries(warehouseName);
            series.setMaximumItemCount(MAX_POINTS_PER_SERIES);
            seriesByWarehouse.put(warehouseName, series);
            dataset.addSeries(series);
        }
        Long last = lastTimestampByWarehouse.get(warehouseName);
        if (last != null && timestamp - last > GAP_MS) {
            series.addOrUpdate(new Millisecond(new Date(last + 1)), (Number) null);
        }
        series.addOrUpdate(new Millisecond(new Date(timestamp)), temperature);
        if (last == null || timestamp > last) {
            lastTimestampByWarehouse.put(warehouseName, timestamp);
        }
    }

    public void removeSeries(String warehouseName) {
        TimeSeries series = seriesByWarehouse.remove(warehouseName);
        if (series != null) {
            dataset.removeSeries(series);
        }
        lastTimestampByWarehouse.remove(warehouseName);
    }
}
