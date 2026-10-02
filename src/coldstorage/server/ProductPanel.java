package coldstorage.server;

import coldstorage.model.Product;
import coldstorage.util.ConfigUtil;
import coldstorage.util.ProductUtil;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Cửa sổ quản lý SẢN PHẨM, mở từ nút "Sản phẩm" trên thanh công cụ của Server.
 * Tab 1 "Danh mục sản phẩm": thêm/xóa sản phẩm, mỗi sản phẩm có dải nhiệt độ an toàn riêng.
 * Tab 2 "Sản phẩm trong kho": gán/gỡ sản phẩm cho từng kho.
 */
public class ProductPanel extends JPanel {

    private final MonitorServer server;

    // ----- Tab 1: danh mục -----
    private DefaultTableModel productTableModel;
    private JTable productTable;
    private JTextField nameField, minField, maxField;
    private List<Product> shownProducts = new ArrayList<>(); // dòng bảng -> sản phẩm

    // ----- Tab 2: sản phẩm trong kho -----
    private JComboBox<String> warehouseCombo;
    private DefaultListModel<Product> assignedModel;
    private JList<Product> assignedList;
    private JComboBox<Product> productCombo;
    private JLabel effectiveLabel;
    private JTextField dwellField;
    private JTextField marginField;
    private JTextField maintenanceField;
    private JLabel maintenanceStatusLabel;

    public ProductPanel(MonitorServer server) {
        this.server = server;
        setLayout(new BorderLayout(10, 10));

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Danh mục sản phẩm", buildCatalogTab());
        tabs.addTab("Sản phẩm trong kho", buildWarehouseTab());
        add(tabs, BorderLayout.CENTER);

        refreshCatalog();
        refreshWarehouseNames();
        refreshAssigned();

        // Gắn listener SAU khi dựng xong để tránh gọi khi các thành phần còn null
        warehouseCombo.addActionListener(e -> refreshAssigned());
    }

    // ============================== TAB 1: DANH MỤC ==============================

    private JPanel buildCatalogTab() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        productTableModel = new DefaultTableModel(
                new String[]{"Sản phẩm", "Tối thiểu (°C)", "Tối đa (°C)", "Đang dùng ở kho"}, 0) {
            @Override
            public boolean isCellEditable(int row, int col) { return false; }
        };
        productTable = new JTable(productTableModel);
        productTable.setRowHeight(24);
        productTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane scroll = new JScrollPane(productTable);
        scroll.setBorder(BorderFactory.createTitledBorder("Danh mục sản phẩm và ngưỡng nhiệt độ an toàn"));
        panel.add(scroll, BorderLayout.CENTER);

        JButton deleteBtn = new JButton("Xóa sản phẩm đã chọn");
        deleteBtn.addActionListener(e -> deleteSelectedProduct());

        JPanel form = new JPanel(new GridLayout(0, 2, 6, 6));
        nameField = new JTextField();
        minField = new JTextField("-18.0");
        maxField = new JTextField("-15.0");
        form.add(new JLabel("Tên sản phẩm:"));
        form.add(nameField);
        form.add(new JLabel("Nhiệt độ tối thiểu (°C):"));
        form.add(minField);
        form.add(new JLabel("Nhiệt độ tối đa (°C):"));
        form.add(maxField);

        JButton addBtn = new JButton("+ Thêm sản phẩm");
        addBtn.addActionListener(e -> addProduct());

        JPanel formWrapper = new JPanel(new BorderLayout(6, 6));
        formWrapper.setBorder(BorderFactory.createTitledBorder("Thêm sản phẩm mới"));
        formWrapper.add(form, BorderLayout.CENTER);
        formWrapper.add(addBtn, BorderLayout.SOUTH);

        JPanel south = new JPanel(new BorderLayout(6, 6));
        south.add(deleteBtn, BorderLayout.NORTH);
        south.add(formWrapper, BorderLayout.CENTER);
        panel.add(south, BorderLayout.SOUTH);
        return panel;
    }

    private void addProduct() {
        try {
            String name = nameField.getText().trim();
            double min = Double.parseDouble(minField.getText().trim());
            double max = Double.parseDouble(maxField.getText().trim());
            String error = ProductUtil.addProduct(name, min, max);
            if (error != null) {
                JOptionPane.showMessageDialog(this, error, "Không thể thêm sản phẩm", JOptionPane.WARNING_MESSAGE);
                return;
            }
            nameField.setText("");
            refreshCatalog();
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this, "Vui lòng nhập nhiệt độ là số hợp lệ (ví dụ: -18.0).",
                    "Dữ liệu không hợp lệ", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void deleteSelectedProduct() {
        int row = productTable.getSelectedRow();
        if (row < 0 || row >= shownProducts.size()) {
            JOptionPane.showMessageDialog(this, "Hãy chọn 1 sản phẩm trong bảng để xóa.",
                    "Chưa chọn sản phẩm", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        Product p = shownProducts.get(row);
        List<String> used = ProductUtil.getWarehousesUsing(p.getId());
        String msg = "Xóa sản phẩm '" + p.getName() + "'?";
        if (!used.isEmpty()) {
            msg += "\nSản phẩm đang được gán cho: " + String.join(", ", used)
                    + ".\nSau khi xóa, các kho này sẽ đổi sang ngưỡng của sản phẩm còn lại (hoặc ngưỡng theo loại kho nếu không còn sản phẩm nào).";
        }
        int choice = JOptionPane.showConfirmDialog(this, msg, "Xác nhận xóa",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice != JOptionPane.YES_OPTION) return;

        ProductUtil.deleteProduct(p.getId());
        refreshCatalog();
        refreshAssigned();
        server.refreshAllRows();
    }

    private void refreshCatalog() {
        shownProducts = ProductUtil.getAllProducts();
        productTableModel.setRowCount(0);
        for (Product p : shownProducts) {
            List<String> used = ProductUtil.getWarehousesUsing(p.getId());
            productTableModel.addRow(new Object[]{
                    p.getName(), p.getMinTemp(), p.getMaxTemp(), used.isEmpty() ? "—" : String.join(", ", used)
            });
        }
        if (productCombo != null) {
            productCombo.removeAllItems();
            for (Product p : shownProducts) productCombo.addItem(p);
        }
    }

    // ============================== TAB 2: SẢN PHẨM TRONG KHO ==============================

    private JPanel buildWarehouseTab() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        // --- chọn kho ---
        warehouseCombo = new JComboBox<>();
        warehouseCombo.setEditable(true); // cho phép gõ tên kho chưa có dữ liệu (nhập đúng tên kho)
        JButton reloadBtn = new JButton("Làm mới");
        reloadBtn.addActionListener(e -> {
            refreshWarehouseNames();
            refreshAssigned();
        });
        JPanel top = new JPanel(new BorderLayout(6, 6));
        top.setBorder(BorderFactory.createTitledBorder("Chọn kho (hoặc gõ tên kho)"));
        top.add(warehouseCombo, BorderLayout.CENTER);
        top.add(reloadBtn, BorderLayout.EAST);

        // --- danh sách sản phẩm đang trong kho ---
        assignedModel = new DefaultListModel<>();
        assignedList = new JList<>(assignedModel);
        JScrollPane listScroll = new JScrollPane(assignedList);
        listScroll.setBorder(BorderFactory.createTitledBorder("Sản phẩm đang được bảo quản trong kho này"));

        JButton removeBtn = new JButton("Gỡ sản phẩm đã chọn khỏi kho");
        removeBtn.addActionListener(e -> unassignSelected());

        effectiveLabel = new JLabel(" ");
        effectiveLabel.setFont(effectiveLabel.getFont().deriveFont(Font.BOLD));

        JPanel center = new JPanel(new BorderLayout(6, 6));
        center.add(effectiveLabel, BorderLayout.NORTH);
        center.add(listScroll, BorderLayout.CENTER);
        center.add(removeBtn, BorderLayout.SOUTH);

        // --- gán thêm sản phẩm ---
        productCombo = new JComboBox<>();
        JButton assignBtn = new JButton("Gán vào kho");
        assignBtn.addActionListener(e -> assignSelected());
        JPanel assign = new JPanel(new BorderLayout(6, 6));
        assign.setBorder(BorderFactory.createTitledBorder("Thêm sản phẩm vào kho"));
        assign.add(productCombo, BorderLayout.CENTER);
        assign.add(assignBtn, BorderLayout.EAST);

        // --- cấu hình cảnh báo riêng ---
        JPanel overridePanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        overridePanel.setBorder(BorderFactory.createTitledBorder("Cấu hình cảnh báo riêng (trống = mặc định)"));
        overridePanel.add(new JLabel("Độ trễ (phút):"));
        dwellField = new JTextField(5);
        overridePanel.add(dwellField);
        overridePanel.add(new JLabel("Biên hồi phục (°C):"));
        marginField = new JTextField(5);
        overridePanel.add(marginField);
        JButton saveOverrideBtn = new JButton("Lưu");
        saveOverrideBtn.addActionListener(e -> saveOverrides());
        overridePanel.add(saveOverrideBtn);

        // --- bảo trì kho ---
        JPanel maintenancePanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        maintenancePanel.setBorder(BorderFactory.createTitledBorder("Bảo trì kho (Tắt cảnh báo)"));
        maintenancePanel.add(new JLabel("Bảo trì trong (phút):"));
        maintenanceField = new JTextField(5);
        maintenancePanel.add(maintenanceField);
        JButton btnMaintenance = new JButton("Kích hoạt");
        btnMaintenance.addActionListener(e -> setMaintenance());
        maintenancePanel.add(btnMaintenance);
        JButton btnStopMaintenance = new JButton("Dừng");
        btnStopMaintenance.addActionListener(e -> stopMaintenance());
        maintenancePanel.add(btnStopMaintenance);
        maintenanceStatusLabel = new JLabel("");
        maintenancePanel.add(maintenanceStatusLabel);

        JPanel bottomPanel = new JPanel(new GridLayout(3, 1, 6, 6));
        bottomPanel.add(assign);
        bottomPanel.add(overridePanel);
        bottomPanel.add(maintenancePanel);

        panel.add(top, BorderLayout.NORTH);
        panel.add(center, BorderLayout.CENTER);
        panel.add(bottomPanel, BorderLayout.SOUTH);
        return panel;
    }

    private String currentWarehouse() {
        Object item = warehouseCombo.getEditor().getItem();
        return item == null ? "" : item.toString().trim();
    }

    private void refreshWarehouseNames() {
        Object keep = warehouseCombo.getEditor().getItem();
        Set<String> names = server.getKnownWarehouseNames();
        // kho đã có sản phẩm nhưng chưa có dữ liệu trong phiên này vẫn được liệt kê
        names.addAll(ProductUtil.getWarehousesWithProducts());
        DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>();
        for (String n : names) model.addElement(n);
        warehouseCombo.setModel(model);
        if (keep != null && !keep.toString().trim().isEmpty()) {
            warehouseCombo.setSelectedItem(keep.toString());
        } else if (model.getSize() > 0) {
            warehouseCombo.setSelectedIndex(0);
        }
    }

    private void refreshAssigned() {
        assignedModel.clear();
        String wh = currentWarehouse();
        if (wh.isEmpty()) {
            effectiveLabel.setText("Chưa chọn kho.");
            dwellField.setText("");
            marginField.setText("");
            return;
        }
        
        coldstorage.util.DatabaseUtil.WarehouseOverrides overrides = coldstorage.util.DatabaseUtil.getWarehouseOverrides(wh);
        dwellField.setText(overrides.dwellMinutes != null ? String.valueOf(overrides.dwellMinutes) : "");
        marginField.setText(overrides.recoveryMargin != null ? String.valueOf(overrides.recoveryMargin) : "");

        Long maintenance = coldstorage.util.DatabaseUtil.getWarehouseMaintenance(wh);
        if (maintenance != null && maintenance > System.currentTimeMillis()) {
            maintenanceStatusLabel.setText("Đang bảo trì tới " + new java.text.SimpleDateFormat("HH:mm:ss dd/MM").format(new java.util.Date(maintenance)));
            maintenanceStatusLabel.setForeground(Color.RED);
        } else {
            maintenanceStatusLabel.setText("Đang hoạt động");
            maintenanceStatusLabel.setForeground(Color.BLACK);
        }

        List<Product> products = ProductUtil.getProductsOf(wh);
        for (Product p : products) assignedModel.addElement(p);

        String type = server.getWarehouseType(wh); // null nếu kho chưa từng gửi dữ liệu
        if (products.isEmpty()) {
            effectiveLabel.setText(type == null
                    ? "Kho chưa gán sản phẩm nào → dùng ngưỡng theo loại kho."
                    : "Kho chưa gán sản phẩm → dùng ngưỡng theo loại kho: "
                        + coldstorage.util.AlertUtil.getThresholdText(wh, type));
        } else {
            double[] r = ProductUtil.getSafeRange(wh, type == null ? "MAT" : type);
            effectiveLabel.setText("Ngưỡng áp dụng cho kho: " + r[0] + "°C ~ " + r[1] + "°C"
                    + (products.size() > 1 ? "  (dải chung của " + products.size() + " sản phẩm)" : ""));
        }
    }

    private void assignSelected() {
        Product p = (Product) productCombo.getSelectedItem();
        if (p == null) {
            JOptionPane.showMessageDialog(this, "Danh mục chưa có sản phẩm. Hãy thêm ở tab \"Danh mục sản phẩm\".",
                    "Chưa có sản phẩm", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String wh = server.resolveWarehouseName(currentWarehouse()); // khớp đúng tên kho đã biết, không phân biệt hoa/thường
        String type = server.getWarehouseType(wh);
        boolean known = type != null || ProductUtil.hasProducts(wh);
        if (!known) {
            int c = JOptionPane.showConfirmDialog(this,
                    "Kho '" + wh + "' chưa từng gửi dữ liệu và chưa có sản phẩm nào.\nCó thể bạn gõ sai tên kho. Vẫn gán?",
                    "Kho chưa có dữ liệu", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (c != JOptionPane.YES_OPTION) return;
        }
        if (type != null) {
            boolean dong = "DONG".equalsIgnoreCase(type);
            double lo = dong ? ConfigUtil.getDongMin() : ConfigUtil.getMatMin();
            double hi = dong ? ConfigUtil.getDongMax() : ConfigUtil.getMatMax();
            if (p.getMaxTemp() < lo || p.getMinTemp() > hi) { // dải của sản phẩm không giao với dải chuẩn của loại kho
                int c = JOptionPane.showConfirmDialog(this,
                        "Dải nhiệt độ của '" + p.getName() + "' (" + p.getRangeText() + ") không giao với dải chuẩn của "
                        + (dong ? "kho đông" : "kho mát") + " (" + lo + "°C ~ " + hi + "°C).\nCó thể bạn đang gán nhầm sản phẩm vào kho. Vẫn gán?",
                        "Sản phẩm không hợp loại kho", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (c != JOptionPane.YES_OPTION) return;
            }
        }
        String error = ProductUtil.assignProduct(wh, p.getId());
        if (error != null) {
            JOptionPane.showMessageDialog(this, error, "Không thể gán sản phẩm", JOptionPane.WARNING_MESSAGE);
            return;
        }
        refreshCatalog();
        refreshWarehouseNames();
        refreshAssigned();
        server.refreshAllRows();
    }

    private void unassignSelected() {
        Product p = assignedList.getSelectedValue();
        if (p == null) return;
        ProductUtil.unassignProduct(currentWarehouse(), p.getId());
        refreshCatalog();
        refreshAssigned();
        server.refreshAllRows();
    }

    private void saveOverrides() {
        String wh = currentWarehouse();
        if (wh.isEmpty()) return;
        Double dwell = null;
        Double margin = null;
        try {
            String d = dwellField.getText().trim();
            if (!d.isEmpty()) dwell = Double.parseDouble(d);
            String m = marginField.getText().trim();
            if (!m.isEmpty()) margin = Double.parseDouble(m);
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, "Vui lòng nhập số hợp lệ.", "Lỗi nhập liệu", JOptionPane.ERROR_MESSAGE);
            return;
        }
        coldstorage.util.DatabaseUtil.updateWarehouseOverrides(wh, dwell, margin);
        JOptionPane.showMessageDialog(this, "Đã lưu cấu hình riêng cho kho " + wh, "Thành công", JOptionPane.INFORMATION_MESSAGE);
    }

    private void setMaintenance() {
        String wh = currentWarehouse();
        if (wh.isEmpty()) return;
        try {
            int mins = Integer.parseInt(maintenanceField.getText().trim());
            long until = System.currentTimeMillis() + mins * 60_000L;
            server.startMaintenance(wh, until);
            refreshAssigned();
            server.refreshAllRows();
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, "Nhập số phút hợp lệ.");
        }
    }

    private void stopMaintenance() {
        String wh = currentWarehouse();
        if (wh.isEmpty()) return;
        server.stopMaintenance(wh);
        refreshAssigned();
        server.refreshAllRows();
    }
}
