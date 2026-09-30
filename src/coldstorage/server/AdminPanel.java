package coldstorage.server;

import coldstorage.util.ConfigUtil;

import javax.swing.*;
import java.awt.*;
import java.util.Set;

/**
 * NÂNG CẤP QUẢN TRỊ: cửa sổ cho phép quản trị viên (giảng viên/người vận hành) thay đổi
 * ngưỡng an toàn và thêm/bớt kho giám sát NGAY TRÊN GIAO DIỆN, không cần sửa code + biên dịch lại.
 *
 * Gồm 2 phần:
 * 1. Chỉnh ngưỡng an toàn cho kho đông / kho mát (lưu qua ConfigUtil, có hiệu lực ngay lập tức).
 * 2. Thêm/xóa cảm biến giả lập (chạy thử nghiệm ngay trong tiến trình Server, tiện cho việc demo
 *    thêm 1 kho mới mà không cần mở thêm cửa sổ SensorLauncher riêng).
 */
public class AdminPanel extends JDialog {

    private final MonitorServer server;

    private JTextField dongMinField, dongMaxField, matMinField, matMaxField;
    private JTextField sensorIdField, warehouseNameField, startTempField, intervalField;
    private JComboBox<String> warehouseTypeCombo;
    private DefaultListModel<String> sensorListModel;
    private JList<String> sensorList;
    private JComboBox<String> deleteWarehouseCombo;
    private JCheckBox deleteHistoryCheck;

    public AdminPanel(JFrame owner, MonitorServer server) {
        super(owner, "Quản trị hệ thống", true);
        this.server = server;
        setSize(520, 660);
        setLocationRelativeTo(owner);
        setLayout(new BorderLayout(10, 10));

        add(buildThresholdPanel(), BorderLayout.NORTH);
        add(buildSensorPanel(), BorderLayout.CENTER);
        add(buildDeleteWarehousePanel(), BorderLayout.SOUTH);

        refreshSensorList();
        refreshDeleteWarehouseCombo();
    }

    private JPanel buildThresholdPanel() {
        JPanel panel = new JPanel(new GridLayout(0, 2, 8, 8));
        panel.setBorder(BorderFactory.createTitledBorder("Ngưỡng an toàn nhiệt độ"));

        panel.add(new JLabel("Kho đông - Tối thiểu (°C):"));
        dongMinField = new JTextField(String.valueOf(ConfigUtil.getDongMin()));
        panel.add(dongMinField);

        panel.add(new JLabel("Kho đông - Tối đa (°C):"));
        dongMaxField = new JTextField(String.valueOf(ConfigUtil.getDongMax()));
        panel.add(dongMaxField);

        panel.add(new JLabel("Kho mát - Tối thiểu (°C):"));
        matMinField = new JTextField(String.valueOf(ConfigUtil.getMatMin()));
        panel.add(matMinField);

        panel.add(new JLabel("Kho mát - Tối đa (°C):"));
        matMaxField = new JTextField(String.valueOf(ConfigUtil.getMatMax()));
        panel.add(matMaxField);

        JButton saveBtn = new JButton("Lưu ngưỡng an toàn");
        saveBtn.addActionListener(e -> saveThresholds());
        panel.add(new JLabel()); // ô trống để căn layout
        panel.add(saveBtn);

        return panel;
    }

    private void saveThresholds() {
        try {
            double dMin = Double.parseDouble(dongMinField.getText().trim());
            double dMax = Double.parseDouble(dongMaxField.getText().trim());
            double mMin = Double.parseDouble(matMinField.getText().trim());
            double mMax = Double.parseDouble(matMaxField.getText().trim());

            try {
                ConfigUtil.setThresholds(dMin, dMax, mMin, mMax);
            } catch (IllegalArgumentException invalid) {
                JOptionPane.showMessageDialog(this, invalid.getMessage(), "Dữ liệu không hợp lệ",
                        JOptionPane.WARNING_MESSAGE);
                return;
            }
            server.refreshAllRows(); // cập nhật ngay cột Ngưỡng/Trạng thái, không chờ gói tin kế tiếp
            JOptionPane.showMessageDialog(this,
                    "Đã lưu ngưỡng an toàn mới. Áp dụng ngay cho các gói tin tiếp theo.",
                    "Thành công", JOptionPane.INFORMATION_MESSAGE);
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this,
                    "Vui lòng nhập số hợp lệ (ví dụ: -18.0).", "Dữ liệu không hợp lệ",
                    JOptionPane.WARNING_MESSAGE);
        }
    }

    private JPanel buildSensorPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createTitledBorder("Quản lý cảm biến / kho giám sát"));

        JPanel formPanel = new JPanel(new GridLayout(0, 2, 6, 6));
        sensorIdField = new JTextField("S" + (int) (Math.random() * 900 + 100));
        warehouseNameField = new JTextField("Kho mới");
        warehouseTypeCombo = new JComboBox<>(new String[]{"DONG", "MAT"});
        startTempField = new JTextField("-16.5");
        intervalField = new JTextField("3000");

        formPanel.add(new JLabel("Mã cảm biến:"));
        formPanel.add(sensorIdField);
        formPanel.add(new JLabel("Tên kho:"));
        formPanel.add(warehouseNameField);
        formPanel.add(new JLabel("Loại kho:"));
        formPanel.add(warehouseTypeCombo);
        // Đổi loại kho thì gợi ý lại nhiệt độ vận hành cho hợp lý (bản cũ luôn để -16.0 nên kho mát cảnh báo ngay)
        warehouseTypeCombo.addActionListener(e ->
                startTempField.setText("DONG".equals(warehouseTypeCombo.getSelectedItem()) ? "-16.5" : "5.0"));
        formPanel.add(new JLabel("Nhiệt độ vận hành (°C):"));
        formPanel.add(startTempField);
        formPanel.add(new JLabel("Chu kỳ gửi (ms):"));
        formPanel.add(intervalField);

        JButton addBtn = new JButton("+ Thêm cảm biến giả lập");
        addBtn.addActionListener(e -> addSimulatedSensor());

        JPanel formWrapper = new JPanel(new BorderLayout(6, 6));
        formWrapper.add(formPanel, BorderLayout.CENTER);
        formWrapper.add(addBtn, BorderLayout.SOUTH);

        sensorListModel = new DefaultListModel<>();
        sensorList = new JList<>(sensorListModel);
        JScrollPane listScroll = new JScrollPane(sensorList);
        listScroll.setBorder(BorderFactory.createTitledBorder("Cảm biến giả lập đang chạy"));

        JButton removeBtn = new JButton("Dừng cảm biến đã chọn");
        removeBtn.addActionListener(e -> removeSelectedSensor());

        JPanel listWrapper = new JPanel(new BorderLayout(6, 6));
        listWrapper.add(listScroll, BorderLayout.CENTER);
        listWrapper.add(removeBtn, BorderLayout.SOUTH);

        panel.add(formWrapper, BorderLayout.NORTH);
        panel.add(listWrapper, BorderLayout.CENTER);
        return panel;
    }

    private void addSimulatedSensor() {
        try {
            String id = sensorIdField.getText().trim();
            String name = warehouseNameField.getText().trim();
            String type = (String) warehouseTypeCombo.getSelectedItem();
            double startTemp = Double.parseDouble(startTempField.getText().trim());
            int interval = Integer.parseInt(intervalField.getText().trim());

            if (id.isEmpty() || name.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Vui lòng nhập đủ mã cảm biến và tên kho.",
                        "Thiếu thông tin", JOptionPane.WARNING_MESSAGE);
                return;
            }
            // Việc kiểm tra chi tiết (ký tự '|', độ dài, trùng mã, xung đột loại kho...) do MonitorServer đảm nhiệm
            String error = server.addSimulatedSensor(id, name, type, startTemp, interval);
            if (error != null) {
                JOptionPane.showMessageDialog(this, error,
                        "Không thể thêm cảm biến", JOptionPane.WARNING_MESSAGE);
                return;
            }
            refreshSensorList();
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this, "Nhiệt độ khởi đầu và chu kỳ gửi phải là số hợp lệ.",
                    "Dữ liệu không hợp lệ", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void removeSelectedSensor() {
        String selected = sensorList.getSelectedValue();
        if (selected == null) return;
        String sensorId = selected.split(" ")[0]; // lấy mã cảm biến ở đầu chuỗi hiển thị
        server.removeSimulatedSensor(sensorId);
        refreshSensorList();
    }

    private void refreshSensorList() {
        sensorListModel.clear();
        Set<String> ids = server.getActiveSimulatedSensorIds();
        for (String id : ids) {
            sensorListModel.addElement(id + " (đang chạy)");
        }
    }

    // ===================== XÓA KHO KHỎI HỆ THỐNG =====================

    private JPanel buildDeleteWarehousePanel() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.setBorder(BorderFactory.createTitledBorder("Xóa kho khỏi hệ thống"));

        deleteWarehouseCombo = new JComboBox<>();
        JButton reloadBtn = new JButton("Làm mới");
        reloadBtn.addActionListener(e -> refreshDeleteWarehouseCombo());
        JPanel top = new JPanel(new BorderLayout(6, 6));
        top.add(deleteWarehouseCombo, BorderLayout.CENTER);
        top.add(reloadBtn, BorderLayout.EAST);

        deleteHistoryCheck = new JCheckBox("Xóa luôn lịch sử đo trong database (không thể hoàn tác)");

        JButton deleteBtn = new JButton("🗑 Xóa kho đã chọn");
        deleteBtn.addActionListener(e -> deleteSelectedWarehouse());

        panel.add(top, BorderLayout.NORTH);
        panel.add(deleteHistoryCheck, BorderLayout.CENTER);
        panel.add(deleteBtn, BorderLayout.SOUTH);
        return panel;
    }

    private void refreshDeleteWarehouseCombo() {
        Object keep = deleteWarehouseCombo.getSelectedItem();
        DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>();
        for (String n : server.getAllWarehouseNames()) model.addElement(n);
        deleteWarehouseCombo.setModel(model);
        if (keep != null) deleteWarehouseCombo.setSelectedItem(keep);
    }

    private void deleteSelectedWarehouse() {
        String name = (String) deleteWarehouseCombo.getSelectedItem();
        if (name == null) {
            JOptionPane.showMessageDialog(this, "Không có kho nào để xóa.",
                    "Không có dữ liệu", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        boolean alsoHistory = deleteHistoryCheck.isSelected();
        String msg = "Xóa kho '" + name + "' khỏi hệ thống?\n"
                + (alsoHistory
                    ? "Toàn bộ lịch sử đo trong database của kho này sẽ bị XÓA VĨNH VIỄN."
                    : "Kho sẽ biến mất khỏi bảng/biểu đồ, nhưng lịch sử trong database vẫn được giữ lại.")
                + "\nNếu đang có cảm biến giả lập gửi cho kho này, cảm biến đó cũng sẽ bị dừng.";
        int c = JOptionPane.showConfirmDialog(this, msg, "Xác nhận xóa kho",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (c != JOptionPane.YES_OPTION) return;

        server.deleteWarehouse(name, alsoHistory);
        refreshSensorList();
        refreshDeleteWarehouseCombo();
        deleteHistoryCheck.setSelected(false);
        JOptionPane.showMessageDialog(this, "Đã xóa kho '" + name + "'.",
                "Thành công", JOptionPane.INFORMATION_MESSAGE);
    }
}
