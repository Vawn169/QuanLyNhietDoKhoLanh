package coldstorage.server;

import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.DateAxis;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.chart.ui.RectangleInsets;
import org.jfree.data.time.Millisecond;
import org.jfree.data.time.TimeSeries;
import org.jfree.data.time.TimeSeriesCollection;

import javax.swing.*;
import java.awt.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * Biểu đồ nhiệt độ theo thời gian cho 1 nhóm kho (kho đông hoặc kho mát).
 * - Mỗi kho là 1 đường riêng.
 * - Trục thời gian chỉ hiển thị khoảng WINDOW_MS gần nhất và tự trượt theo dữ liệu mới,
 *   nên dữ liệu cũ trong database không làm biểu đồ bị "nén" lại.
 * - Trục nhiệt độ tự co giãn theo dữ liệu (không ép phải chứa số 0).
 * - Khi 2 điểm liên tiếp cách nhau quá GAP_MS (Server vừa khởi động lại, cảm biến mất tín hiệu...), đường
 *   bị NGẮT ở chỗ đó thay vì nối thẳng qua khoảng trống (bản cũ vẽ 1 đường thẳng "dữ liệu giả" từ lần chạy
 *   trước sang lần chạy này).
 */
public class TemperatureChartPanel extends JPanel {

    private static final long WINDOW_MS = 2 * 60 * 1000; // hiển thị 2 phút gần nhất
    private static final long GAP_MS = 30 * 1000;        // cách nhau hơn 30 giây -> ngắt đường
    private static final int MAX_POINTS_PER_SERIES = 200;

    private final TimeSeriesCollection dataset = new TimeSeriesCollection();
    private final Map<String, TimeSeries> seriesByWarehouse = new HashMap<>();
    private final Map<String, Long> lastTimestampByWarehouse = new HashMap<>();

    public TemperatureChartPanel(String title) {
        setLayout(new BorderLayout());

        JFreeChart chart = ChartFactory.createTimeSeriesChart(
                title, "Thời gian", "Nhiệt độ (°C)", dataset, true, true, false);

        chart.setPadding(new RectangleInsets(4, 6, 4, 18)); // chừa chỗ bên phải để nhãn giờ cuối không bị cắt
        XYPlot plot = chart.getXYPlot();

        // Mỗi kho 1 màu cố định (cùng màu với các biểu đồ ở tab Thống kê), nét dày hơn, không vẽ chấm
        XYLineAndShapeRenderer renderer = new XYLineAndShapeRenderer(true, false) {
            @Override
            public Paint lookupSeriesPaint(int series) {
                return UiTheme.colorFor(String.valueOf(dataset.getSeriesKey(series)));
            }
        };
        renderer.setAutoPopulateSeriesStroke(false); // nếu không, nét mặc định của theme sẽ đè lên nét dày
        renderer.setDefaultStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        plot.setRenderer(renderer);

        DateAxis timeAxis = (DateAxis) plot.getDomainAxis();
        timeAxis.setAutoRange(true);
        timeAxis.setFixedAutoRange(WINDOW_MS);
        timeAxis.setDateFormatOverride(new SimpleDateFormat("HH:mm:ss"));

        NumberAxis tempAxis = (NumberAxis) plot.getRangeAxis();
        tempAxis.setAutoRangeIncludesZero(false);

        ChartPanel chartPanel = new ChartPanel(chart);
        chartPanel.setPreferredSize(new Dimension(450, 280));
        add(chartPanel, BorderLayout.CENTER);
    }

    /** Thêm 1 điểm đo cho 1 kho (tự tạo đường mới nếu kho chưa có). Gọi trên luồng Swing (EDT). */
    public void addPoint(String warehouseName, long timestamp, double temperature) {
        TimeSeries series = seriesByWarehouse.get(warehouseName);
        if (series == null) {
            series = new TimeSeries(warehouseName);
            series.setMaximumItemCount(MAX_POINTS_PER_SERIES);
            seriesByWarehouse.put(warehouseName, series);
            dataset.addSeries(series);
        }
        Long last = lastTimestampByWarehouse.get(warehouseName);
        if (last != null && timestamp - last > GAP_MS) {
            // Điểm "rỗng" ngay sau điểm cuối cũ: JFreeChart không nối đường qua giá trị rỗng
            series.addOrUpdate(new Millisecond(new Date(last + 1)), (Number) null);
        }
        series.addOrUpdate(new Millisecond(new Date(timestamp)), temperature);
        if (last == null || timestamp > last) {
            lastTimestampByWarehouse.put(warehouseName, timestamp);
        }
    }

    /** Gỡ hẳn đường của 1 kho khỏi biểu đồ (dùng khi xóa kho khỏi hệ thống); không có tác dụng nếu kho không thuộc biểu đồ này */
    public void removeSeries(String warehouseName) {
        TimeSeries series = seriesByWarehouse.remove(warehouseName);
        if (series != null) {
            dataset.removeSeries(series);
        }
        lastTimestampByWarehouse.remove(warehouseName);
    }
}
