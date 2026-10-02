package coldstorage.server;

import coldstorage.util.DatabaseUtil;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * Tab "Lịch sử sự cố": liệt kê từng lần cảnh báo của mỗi kho (đã có AlertStateTracker ghi START/RECOVERED,
 * lưu qua DatabaseUtil.startAlertIncident/closeAlertIncident vào bảng alert_history), kèm thời điểm bắt đầu,
 * kết thúc và thời lượng - giúp trả lời "kho này cảnh báo bao lâu, bao nhiêu lần" mà không cần đọc log thô.
 */
public class AlertHistoryPanel extends JPanel {

    private static final int LIMIT = 500;
    private static final SimpleDateFormat SDF = new SimpleDateFormat("dd/MM HH:mm:ss");

    private final DefaultTableModel tableModel;
    private final JLabel summaryLabel = new JLabel(" ");
    private List<DatabaseUtil.AlertIncident> currentIncidents;

    public AlertHistoryPanel() {
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        tableModel = new DefaultTableModel(
                new Object[]{"ID", "Kho", "Bắt đầu", "Kết thúc", "Thời lượng", "Nhiệt độ đỉnh điểm", "Đã xác nhận"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        JTable table = new JTable(tableModel);
        table.setRowHeight(24);
        table.setDefaultRenderer(Object.class, new OngoingRowRenderer());

        table.getColumnModel().getColumn(0).setMinWidth(0);
        table.getColumnModel().getColumn(0).setMaxWidth(0);
        table.getColumnModel().getColumn(0).setWidth(0);

        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                int row = table.rowAtPoint(e.getPoint());
                int col = table.columnAtPoint(e.getPoint());
                if (row >= 0 && col == 6) { // Cột Xác nhận
                    int id = (int) tableModel.getValueAt(row, 0);
                    Object val = tableModel.getValueAt(row, 6);
                    if ("Chưa xác nhận".equals(val)) {
                        DatabaseUtil.acknowledgeAlert(id);
                        refresh();
                    }
                }
            }
        });

        JPanel top = new JPanel(new BorderLayout());
        top.add(summaryLabel, BorderLayout.WEST);
        JButton refreshBtn = new JButton("↻ Làm mới");
        refreshBtn.addActionListener(e -> refresh());
        top.add(refreshBtn, BorderLayout.EAST);

        add(top, BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);

        refresh();
        javax.swing.Timer timer = new javax.swing.Timer(15_000, e -> refresh()); // làm mới mỗi 15 giây
        timer.start();
    }

    /** Truy vấn lại (luồng nền) rồi nạp vào bảng (luồng Swing) */
    public void refresh() {
        new SwingWorker<List<DatabaseUtil.AlertIncident>, Void>() {
            @Override
            protected List<DatabaseUtil.AlertIncident> doInBackground() {
                return DatabaseUtil.getAlertHistory(LIMIT);
            }

            @Override
            protected void done() {
                try {
                    List<DatabaseUtil.AlertIncident> list = get();
                    tableModel.setRowCount(0);
                    currentIncidents = list;
                    int ongoing = 0;
                    for (DatabaseUtil.AlertIncident inc : list) {
                        boolean isOngoing = inc.isOngoing();
                        if (isOngoing) ongoing++;
                        String start = SDF.format(new Date(inc.startedAt));
                        String end = isOngoing ? "Đang diễn ra" : SDF.format(new Date(inc.endedAt));
                        long durationMs = (isOngoing ? System.currentTimeMillis() : inc.endedAt) - inc.startedAt;
                        String duration = formatDuration(durationMs);
                        String ack = inc.acknowledgedAt == null ? "Chưa xác nhận" : SDF.format(new Date(inc.acknowledgedAt));
                        tableModel.addRow(new Object[]{
                                inc.id, inc.warehouseName, start, end, duration, String.format("%.1f°C", inc.peakTemp), ack
                        });
                    }
                    summaryLabel.setText(String.format("Tổng %d sự cố đã ghi nhận (%d đang diễn ra)",
                            list.size(), ongoing));
                } catch (Exception e) {
                    System.err.println("[AlertHistoryPanel] Lỗi làm mới: " + e.getMessage());
                }
            }
        }.execute();
    }

    private static String formatDuration(long ms) {
        long totalSec = Math.max(0, ms / 1000);
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        if (h > 0) return String.format("%dh%02dp%02dg", h, m, s);
        if (m > 0) return String.format("%dp%02dg", m, s);
        return s + "g";
    }

    /** Tô nền vàng nhạt cho sự cố đang diễn ra (cột "Kết thúc" = "Đang diễn ra"), để dễ nhận ra ngay */
    private static class OngoingRowRenderer extends javax.swing.table.DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                boolean isSelected, boolean hasFocus, int row, int column) {
            JLabel c = (JLabel) super.getTableCellRendererComponent(table, value, isSelected, false, row, column);
            boolean ongoing = "Đang diễn ra".equals(table.getValueAt(row, 2));
            c.setOpaque(true);
            if (isSelected) {
                c.setBackground(table.getSelectionBackground());
                c.setForeground(table.getSelectionForeground());
            } else if (ongoing) {
                c.setBackground(UiTheme.BACKUP_BG);
                c.setForeground(UiTheme.BACKUP_FG);
            } else {
                c.setBackground(row % 2 == 0 ? Color.WHITE : new Color(247, 249, 251));
                c.setForeground(UiTheme.TEXT_DARK);
            }
            if (column == 6 && "Chưa xác nhận".equals(value)) {
                c.setForeground(Color.RED); // Highlight pending acknowledge
                c.setFont(c.getFont().deriveFont(java.awt.Font.BOLD));
            }
            return c;
        }
    }
}
