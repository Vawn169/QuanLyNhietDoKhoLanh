package coldstorage.server;

import coldstorage.model.TemperatureData;
import coldstorage.util.AlertUtil;
import coldstorage.util.BackupUtil;
import coldstorage.util.DatabaseUtil;
import coldstorage.util.SensorAnomalyDetector;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

public class WarehouseCard extends JPanel {
    private String warehouseName;
    private JLabel nameLabel;
    private JLabel tempLabel;
    private JLabel statusLabel;
    private SparklinePanel sparkline;
    private SafeRangeBar safeRangeBar;

    private LinkedList<Double> history = new LinkedList<>();

    public WarehouseCard(String name) {
        this.warehouseName = name;
        setLayout(new BorderLayout(10, 10));
        setBackground(Color.WHITE);
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(230, 230, 230), 1, true),
                BorderFactory.createEmptyBorder(15, 15, 15, 15)
        ));
        
        nameLabel = new JLabel(name, SwingConstants.LEFT);
        nameLabel.setFont(UiTheme.font(Font.BOLD, 16f));
        
        statusLabel = new JLabel("CHỜ DỮ LIỆU", SwingConstants.RIGHT);
        statusLabel.setOpaque(true);
        statusLabel.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        statusLabel.setFont(UiTheme.font(Font.BOLD, 12f));

        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.setOpaque(false);
        topPanel.add(nameLabel, BorderLayout.WEST);
        topPanel.add(statusLabel, BorderLayout.EAST);
        
        tempLabel = new JLabel("-- °C", SwingConstants.CENTER);
        tempLabel.setFont(UiTheme.font(Font.BOLD, 32f));
        
        sparkline = new SparklinePanel();
        sparkline.setPreferredSize(new Dimension(150, 40));
        
        safeRangeBar = new SafeRangeBar();
        safeRangeBar.setPreferredSize(new Dimension(200, 10));

        JPanel centerPanel = new JPanel(new BorderLayout(0, 10));
        centerPanel.setOpaque(false);
        centerPanel.add(tempLabel, BorderLayout.NORTH);
        centerPanel.add(safeRangeBar, BorderLayout.CENTER);
        centerPanel.add(sparkline, BorderLayout.SOUTH);

        add(topPanel, BorderLayout.NORTH);
        add(centerPanel, BorderLayout.CENTER);
        
        setPreferredSize(new Dimension(260, 180));
    }

    public void update(TemperatureData data, boolean isOffline, boolean isMaintenance, boolean hasAnomaly) {
        if (data == null) return;
        
        double t = data.getTemperature();
        tempLabel.setText(String.format("%.1f °C", t));
        
        history.addLast(t);
        if (history.size() > 50) history.removeFirst();
        sparkline.setData(history, UiTheme.colorFor(warehouseName));
        
        AlertUtil.Status status = AlertUtil.checkStatus(data);
        double[] range = AlertUtil.getSafeRange(data);
        safeRangeBar.update(t, range[0], range[1]);

        String statusText;
        Color bg = UiTheme.SAFE_BG;
        Color fg = UiTheme.SAFE_FG;

        if (isMaintenance) {
            statusText = "🔧 BẢO TRÌ";
            bg = UiTheme.MAINTENANCE_BG; fg = UiTheme.MAINTENANCE_FG;
        } else if (hasAnomaly) {
            statusText = "🔧 NGHI CẢM BIẾN LỖI";
            bg = UiTheme.ANOMALY_BG; fg = UiTheme.ANOMALY_FG;
        } else if (BackupUtil.isBackupSource(data)) {
            statusText = "🔧 DỰ PHÒNG";
            bg = UiTheme.BACKUP_BG; fg = UiTheme.BACKUP_FG;
        } else if (isOffline) {
            statusText = "MẤT TÍN HIỆU";
            bg = UiTheme.OFFLINE_BG; fg = UiTheme.OFFLINE_FG;
        } else if (status == AlertUtil.Status.WARNING) {
            statusText = "⚠ CẢNH BÁO";
            bg = UiTheme.WARN_BG; fg = UiTheme.WARN_FG;
        } else if (coldstorage.util.TrendTracker.isTrendingNow(warehouseName)) {
            statusText = "◤ SẮP VƯỢT NGƯỠNG";
            bg = new Color(255, 224, 178); fg = UiTheme.ACCENT_ORANGE;
        } else {
            statusText = "AN TOÀN";
        }
        
        statusLabel.setText(statusText);
        statusLabel.setBackground(bg);
        statusLabel.setForeground(fg);
    }

    private static class SparklinePanel extends JPanel {
        private List<Double> data = new ArrayList<>();
        private Color lineColor = Color.BLUE;

        public SparklinePanel() {
            setOpaque(false);
        }

        public void setData(List<Double> data, Color lineColor) {
            this.data = new ArrayList<>(data);
            this.lineColor = lineColor;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (data.size() < 2) return;
            
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            
            double min = data.stream().min(Double::compareTo).orElse(0.0);
            double max = data.stream().max(Double::compareTo).orElse(0.0);
            if (max - min < 1.0) { min -= 0.5; max += 0.5; }
            
            int w = getWidth();
            int h = getHeight();
            
            double xStep = (double) w / (data.size() - 1);
            
            g2.setColor(lineColor);
            g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            
            int[] xPoints = new int[data.size()];
            int[] yPoints = new int[data.size()];
            
            for (int i = 0; i < data.size(); i++) {
                xPoints[i] = (int) (i * xStep);
                double norm = (data.get(i) - min) / (max - min);
                yPoints[i] = (int) (h - 2 - (norm * (h - 4)));
            }
            g2.drawPolyline(xPoints, yPoints, data.size());
        }
    }

    private static class SafeRangeBar extends JPanel {
        private double temp, min, max;
        
        public SafeRangeBar() {
            setOpaque(false);
        }
        
        public void update(double temp, double min, double max) {
            this.temp = temp;
            this.min = min;
            this.max = max;
            repaint();
        }
        
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            
            int w = getWidth();
            int h = getHeight();
            
            // Vẽ dải nền (xám)
            g2.setColor(new Color(230, 230, 230));
            g2.fillRoundRect(0, 0, w, h, h, h);
            
            // Tính toán khoảng hiển thị (rộng hơn dải an toàn một chút)
            double span = max - min;
            double displayMin = min - span * 0.2;
            double displayMax = max + span * 0.2;
            
            // Vẽ dải xanh (an toàn)
            int safeX = (int) ((min - displayMin) / (displayMax - displayMin) * w);
            int safeW = (int) ((max - min) / (displayMax - displayMin) * w);
            
            safeX = Math.max(0, safeX);
            safeW = Math.min(w - safeX, safeW);
            
            g2.setColor(new Color(198, 239, 206)); // SAFE_BG
            g2.fillRect(safeX, 0, safeW, h);
            
            // Vẽ vạch nhiệt độ hiện tại
            double t = Math.max(displayMin, Math.min(temp, displayMax));
            int markerX = (int) ((t - displayMin) / (displayMax - displayMin) * w);
            
            g2.setColor(t < min || t > max ? UiTheme.ACCENT_RED : UiTheme.ACCENT_GREEN);
            g2.fillRoundRect(Math.max(0, markerX - 3), 0, 6, h, 6, 6);
        }
    }
}
