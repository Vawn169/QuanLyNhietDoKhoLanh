package coldstorage.server;

import coldstorage.util.DatabaseUtil;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

public class RejectedLogPanel extends JPanel {
    
    private final DefaultTableModel tableModel;
    private final JTable table;

    public RejectedLogPanel() {
        setLayout(new BorderLayout());

        tableModel = new DefaultTableModel(new String[]{"Thời gian", "IP Nguồn", "Lý do", "Sensor ID"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        table = new JTable(tableModel);
        table.setRowHeight(28);
        table.getTableHeader().setPreferredSize(new Dimension(0, 32));
        table.getTableHeader().setFont(UiTheme.font(Font.BOLD, 14f));
        table.setFont(UiTheme.font(Font.PLAIN, 13f));
        
        int[] widths = {150, 150, 350, 150};
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }

        JScrollPane scrollPane = new JScrollPane(table);
        add(scrollPane, BorderLayout.CENTER);

        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton refreshBtn = new JButton("Làm mới");
        refreshBtn.addActionListener(e -> loadData());
        topPanel.add(refreshBtn);
        add(topPanel, BorderLayout.NORTH);

        loadData();
    }

    public void loadData() {
        tableModel.setRowCount(0);
        List<DatabaseUtil.RejectedLog> logs = DatabaseUtil.getRecentRejectedLogs(50);
        SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss");
        for (DatabaseUtil.RejectedLog log : logs) {
            tableModel.addRow(new Object[]{
                sdf.format(new Date(log.timestamp)),
                log.sourceIp,
                log.reason,
                log.sensorId != null ? log.sensorId : "-"
            });
        }
    }
}
