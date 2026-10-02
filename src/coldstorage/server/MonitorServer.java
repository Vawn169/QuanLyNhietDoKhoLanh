package coldstorage.server;

import coldstorage.model.TemperatureData;
import coldstorage.sensor.SensorClient;
import coldstorage.util.AlertNotifier;
import coldstorage.util.AlertStateTracker;
import coldstorage.util.AlertUtil;
import coldstorage.util.BackupUtil;
import coldstorage.util.DatabaseUtil;
import coldstorage.util.ProductUtil;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Giao diện chính của Server giám sát nhiệt độ kho lạnh.
 * - Lắng nghe UDP trên 1 luồng riêng (không chặn giao diện).
 * - Mỗi gói tin nhận được xử lý trên 1 luồng riêng qua ThreadPoolExecutor (số luồng VÀ hàng đợi đều có giới hạn).
 * - Tab "Giám sát": bảng trạng thái từng kho + 2 biểu đồ nhiệt độ riêng (kho đông / kho mát).
 * - Tab "Thống kê": biểu đồ tròn An toàn/Cảnh báo + biểu đồ cột cảnh báo theo giờ.
 * - Phát hiện MẤT TÍN HIỆU: kho không gửi dữ liệu quá lâu được đánh dấu riêng và có cảnh báo.
 * - Nút "Quản trị": mở AdminPanel để đổi ngưỡng an toàn + thêm/xóa cảm biến giả lập.
 */
public class MonitorServer extends JFrame {

    private static final int UDP_PORT = 9876;
    private static final int MAX_WORKER_THREADS = 8;
    private static final int QUEUE_CAPACITY = 1000;
    private static final int HISTORY_LOAD_LIMIT = 100;

    /** Chu kỳ kiểm tra mất tín hiệu (quy tắc phát hiện nằm trong OfflineTracker) */
    private static final int OFFLINE_CHECK_MS = 2_000;

    private DefaultTableModel tableModel;
    private JTable statusTable;
    private StatCard cardTotal, cardWarning, cardOffline, cardWarnCount, cardRejected;
    private StatsPanel statsPanel;
    private TemperatureChartPanel dongChartPanel;
    private TemperatureChartPanel matChartPanel;

    // ---- Bộ lọc bảng trạng thái ----
    private TableRowSorter<DefaultTableModel> tableSorter;
    private JComboBox<String> filterTypeCombo;
    private JComboBox<String> filterStatusCombo;
    private JTextField filterNameField;

    // ---- Các cấu trúc dưới đây CHỈ được truy cập trên luồng Swing (EDT) nên không cần đồng bộ ----
    private final Map<String, Integer> rowIndexByWarehouse = new HashMap<>();
    private final Map<String, Long> lastAppliedTimestamp = new HashMap<>();
    // Bản ghi mới nhất của từng kho - để vẽ lại bảng khi đổi sản phẩm/ngưỡng
    private final Map<String, TemperatureData> lastDataByWarehouse = new HashMap<>();
    // Phát hiện kho mất tín hiệu (kho im lặng quá lâu)
    private final OfflineTracker offlineTracker = new OfflineTracker();
    // Số lần đo vượt ngưỡng: đọc từ DB 1 lần lúc khởi động rồi tự cộng dồn, KHÔNG truy vấn DB ở mỗi gói tin
    private int warningCount;

    // Registry quản lý cảm biến giả lập được thêm động qua AdminPanel
    private final Map<String, SensorClient> activeSensors = new HashMap<>();
    private final Map<String, Thread> activeSensorThreads = new HashMap<>();

    private ThreadPoolExecutor threadPool;
    private DatagramSocket serverSocket;
    private volatile boolean running = true;
    private int droppedPackets = 0;
    private WarehouseCardPanel warehouseCardPanel;

    public MonitorServer() {
        setTitle("Hệ thống giám sát nhiệt độ kho lạnh - Server");
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setSize(1100, 700);
        setLocationRelativeTo(null);

        // Phải khởi tạo database/sản phẩm TRƯỚC initUI(): initUI() dựng StatsPanel, mà constructor của
        // StatsPanel gọi refreshData() ngay lập tức -> tạo 1 luồng nền (SwingWorker) truy vấn database.
        // Nếu initUI() chạy trước, luồng nền đó có thể truy vấn database TRƯỚC KHI initDatabase() kịp
        // tạo bảng (đặc biệt ở lần chạy đầu tiên trên 1 file coldstorage.db hoàn toàn mới) -> race condition.
        DatabaseUtil.initDatabase();
        ProductUtil.init();
        // Nối tiếp các sự cố còn mở từ lần chạy trước (tránh sự cố "mồ côi" + sự cố trùng khi kho vẫn đang vượt ngưỡng)
        for (Map.Entry<String, Long> open : DatabaseUtil.reconcileOpenIncidents().entrySet()) {
            AlertStateTracker.resume(open.getKey(), open.getValue());
        }

        initUI();
        setupShutdownHandler();

        warningCount = DatabaseUtil.countWarnings();
        loadHistoryFromDatabase();
        threadPool = new ThreadPoolExecutor(MAX_WORKER_THREADS, MAX_WORKER_THREADS, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(QUEUE_CAPACITY));
        startUdpListener();
        startOfflineWatcher();
        updateSummaryLabel();
    }

    private void initUI() {
        setLayout(new BorderLayout(10, 10));

        // UI chính sẽ chia thành 2 phần: Sidebar trái (menu) và Content phải (CardLayout)
        JPanel mainPanel = new JPanel(new BorderLayout(0, 0));
        add(mainPanel, BorderLayout.CENTER);

        JPanel sidebar = new JPanel();
        sidebar.setLayout(new BoxLayout(sidebar, BoxLayout.Y_AXIS));
        sidebar.setPreferredSize(new Dimension(200, 0));
        sidebar.setBackground(new Color(40, 44, 52));
        sidebar.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel contentCards = new JPanel(new CardLayout());
        
        mainPanel.add(sidebar, BorderLayout.WEST);
        mainPanel.add(contentCards, BorderLayout.CENTER);

        // ----- Tab "Giám sát thực tế" -----
        JPanel monitorTab = new JPanel(new BorderLayout(10, 10));
        monitorTab.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        // Hàng thẻ tóm tắt ở đầu tab
        cardTotal = new StatCard("Tổng số kho", UiTheme.ACCENT_BLUE);
        cardWarning = new StatCard("Kho đang cảnh báo", UiTheme.ACCENT_GREEN);
        cardOffline = new StatCard("Kho mất tín hiệu", UiTheme.ACCENT_GRAY);
        cardWarnCount = new StatCard("Lần đo vượt ngưỡng (tổng)", UiTheme.ACCENT_ORANGE);
        cardRejected = new StatCard("Gói tin bị từ chối", UiTheme.ACCENT_GRAY);
        JPanel cardsRow = new JPanel(new GridLayout(1, 5, 10, 0));
        cardsRow.setOpaque(false);
        for (StatCard c : new StatCard[]{cardTotal, cardWarning, cardOffline, cardWarnCount, cardRejected}) cardsRow.add(c);
        monitorTab.add(cardsRow, BorderLayout.NORTH);

        String[] columns = {"Kho", "Loại kho", "Sản phẩm", "Nhiệt độ (°C)", "Ngưỡng an toàn", "Trạng thái", "Cập nhật lúc"};
        tableModel = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int col) { return false; }
        };
        statusTable = new JTable(tableModel);
        statusTable.setRowHeight(32);
        statusTable.setShowVerticalLines(false);
        statusTable.setGridColor(new Color(232, 235, 239));
        statusTable.setIntercellSpacing(new Dimension(0, 1));
        statusTable.setFillsViewportHeight(true);
        statusTable.getTableHeader().setPreferredSize(new Dimension(0, 34));
        statusTable.setDefaultRenderer(Object.class, new StatusRowRenderer());
        int[] widths = {110, 80, 250, 100, 140, 175, 110};
        for (int i = 0; i < widths.length; i++) statusTable.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);

        // RowSorter cho phép sắp xếp + lọc
        tableSorter = new TableRowSorter<>(tableModel);
        statusTable.setRowSorter(tableSorter);

        // --- Thanh lọc ---
        JPanel filterBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        filterBar.add(new JLabel("Loại kho:"));
        filterTypeCombo = new JComboBox<>(new String[]{"Tất cả", "Kho đông", "Kho mát"});
        filterTypeCombo.addActionListener(e -> applyTableFilter());
        filterBar.add(filterTypeCombo);

        filterBar.add(new JLabel("Trạng thái:"));
        filterStatusCombo = new JComboBox<>(new String[]{"Tất cả", "An toàn", "Cảnh báo", "Mất tín hiệu", "Dự phòng", "Bảo trì", "Sắp vượt ngưỡng", "Nghi cảm biến lỗi"});
        filterStatusCombo.addActionListener(e -> applyTableFilter());
        filterBar.add(filterStatusCombo);

        filterBar.add(new JLabel("Tên kho:"));
        filterNameField = new JTextField(12);
        filterNameField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { applyTableFilter(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { applyTableFilter(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { applyTableFilter(); }
        });
        filterBar.add(filterNameField);

        JButton clearFilterBtn = new JButton("Xóa lọc");
        clearFilterBtn.addActionListener(e -> {
            filterTypeCombo.setSelectedIndex(0);
            filterStatusCombo.setSelectedIndex(0);
            filterNameField.setText("");
        });
        filterBar.add(clearFilterBtn);

        JButton toggleViewBtn = new JButton("Chuyển Bảng/Thẻ");
        filterBar.add(toggleViewBtn);

        JScrollPane tableScroll = new JScrollPane(statusTable);
        tableScroll.setBorder(BorderFactory.createTitledBorder("Trạng thái kho lạnh theo thời gian thực"));

        warehouseCardPanel = new WarehouseCardPanel();
        warehouseCardPanel.setBorder(BorderFactory.createTitledBorder("Trạng thái kho lạnh theo thời gian thực"));

        JPanel viewPanel = new JPanel(new CardLayout());
        viewPanel.add(tableScroll, "TABLE");
        viewPanel.add(warehouseCardPanel, "CARD");

        toggleViewBtn.addActionListener(e -> {
            CardLayout cl = (CardLayout) viewPanel.getLayout();
            cl.next(viewPanel);
        });

        // Gộp thanh lọc + bảng vào 1 panel
        JPanel tableWithFilter = new JPanel(new BorderLayout());
        tableWithFilter.add(filterBar, BorderLayout.NORTH);
        tableWithFilter.add(viewPanel, BorderLayout.CENTER);

        // 2 biểu đồ đặt cạnh nhau: kho đông (bên trái) và kho mát (bên phải)
        dongChartPanel = new TemperatureChartPanel("Kho đông - nhiệt độ theo thời gian");
        matChartPanel = new TemperatureChartPanel("Kho mát - nhiệt độ theo thời gian");
        JPanel chartsPanel = new JPanel(new GridLayout(1, 2, 10, 0));
        chartsPanel.add(dongChartPanel);
        chartsPanel.add(matChartPanel);

        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tableWithFilter, chartsPanel);
        splitPane.setResizeWeight(0.35);
        monitorTab.add(splitPane, BorderLayout.CENTER);


        // ----- Tab "Thống kê" -----
        statsPanel = new StatsPanel();
        
        // Khởi tạo các panel mới
        ProductPanel productPanel = new ProductPanel(this);
        AdminPanel adminPanel = new AdminPanel(this);
        SecuritySettingsDialog securityPanel = new SecuritySettingsDialog();
        AdvancedConfigDialog advConfigPanel = new AdvancedConfigDialog();
        AlertHistoryPanel alertHistoryPanel = new AlertHistoryPanel();
        RejectedLogPanel rejectedLogPanel = new RejectedLogPanel();

        contentCards.add(monitorTab, "Giám sát");
        contentCards.add(statsPanel, "Thống kê");
        contentCards.add(alertHistoryPanel, "Lịch sử sự cố");
        contentCards.add(rejectedLogPanel, "Nhật ký từ chối");
        contentCards.add(productPanel, "Sản phẩm");
        contentCards.add(adminPanel, "Quản trị");
        contentCards.add(securityPanel, "Bảo mật");
        contentCards.add(advConfigPanel, "Nâng cao");

        // Các nút trong Sidebar
        String[] menuItems = {"Giám sát", "Thống kê", "Lịch sử sự cố", "Nhật ký từ chối", "Sản phẩm", "Quản trị", "Bảo mật", "Nâng cao"};
        java.util.List<JButton> menuBtns = new ArrayList<>();
        
        JButton loginBtn = new JButton("Đăng nhập");
        loginBtn.setAlignmentX(Component.CENTER_ALIGNMENT);
        loginBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));

        for (String item : menuItems) {
            JButton btn = new JButton(item);
            btn.setAlignmentX(Component.CENTER_ALIGNMENT);
            btn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
            btn.setFocusPainted(false);
            
            // Ban đầu khóa Quản trị, Bảo mật, Nâng cao
            if (item.equals("Quản trị") || item.equals("Bảo mật") || item.equals("Nâng cao")) {
                btn.setEnabled(false);
            }
            
            btn.addActionListener(e -> {
                CardLayout cl = (CardLayout) contentCards.getLayout();
                cl.show(contentCards, item);
            });
            sidebar.add(btn);
            sidebar.add(Box.createVerticalStrut(10));
            menuBtns.add(btn);
        }
        
        sidebar.add(Box.createVerticalGlue());
        sidebar.add(loginBtn);
        
        loginBtn.addActionListener(e -> {
            if (LoginDialog.authenticate(this)) {
                for (JButton btn : menuBtns) {
                    btn.setEnabled(true);
                }
                loginBtn.setText("Đã đăng nhập");
                loginBtn.setEnabled(false);
            }
        });
    }

    /**
     * Khôi phục bảng + biểu đồ từ DB khi khởi động. Dữ liệu khôi phục là dữ liệu CŨ nên được đánh dấu
     * MẤT TÍN HIỆU (im lặng, không gửi cảnh báo) cho tới khi kho thực sự gửi gói mới - tránh hiển thị số cũ
     * như thể đang đo trực tiếp.
     */
    private void loadHistoryFromDatabase() {
    List<String> warehouses = DatabaseUtil.getKnownWarehouses();
    Set<String> deleted = DatabaseUtil.getInactiveWarehouseNames();   // MỚI
    warehouses.removeAll(deleted);                                    // MỚI: bỏ các kho đã xóa
        for (String warehouseName : warehouses) {
            List<TemperatureData> history = DatabaseUtil.getRecentLogs(warehouseName, HISTORY_LOAD_LIMIT);
            if (history.isEmpty()) continue;
            offlineTracker.restoreAsOffline(warehouseName, history.get(history.size() - 1).getTimestamp());
            for (TemperatureData data : history) {
                AlertUtil.Status status = AlertUtil.checkStatus(data);
                updateTableRow(data, status);
                updateChart(data);
                lastAppliedTimestamp.put(data.getWarehouseName(), data.getTimestamp());
            }
        }
        if (!warehouses.isEmpty()) {
            System.out.println("[Server] Đã khôi phục lịch sử cho " + warehouses.size() + " kho từ database.");
        }
    }

    private void startUdpListener() {
        Thread listenerThread = new Thread(() -> {
            try {
                serverSocket = new DatagramSocket(UDP_PORT);
                System.out.println("[Server] Đang lắng nghe UDP tại cổng " + UDP_PORT + "...");
                byte[] buffer = new byte[1024];

                while (running) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    serverSocket.receive(packet);
                    String received = new String(packet.getData(), 0, packet.getLength(), "UTF-8");
                    String sourceIp = packet.getAddress().getHostAddress();
                    long receivedAt = System.currentTimeMillis();

                    try {
                        threadPool.execute(new SensorHandler(received, sourceIp, receivedAt, this::onDataProcessed));
                    } catch (RejectedExecutionException full) {
                        // Hàng đợi đầy (bị dồn gói): bỏ gói thay vì để bộ nhớ phình vô hạn. UDP vốn cho phép mất gói.
                        SecurityUtil.countRejected();
                        droppedPackets++;
                        if (droppedPackets % 100 == 1) {
                            System.err.println("[Server] Hàng đợi xử lý đầy, đã bỏ " + droppedPackets + " gói tin.");
                        }
                    }
                }
            } catch (Exception e) {
                if (running) System.err.println("[Server] Lỗi UDP listener: " + e.getMessage());
            }
        }, "Thread-UDP-Listener");
        listenerThread.setDaemon(true);
        listenerThread.start();
    }

    private void onDataProcessed(TemperatureData data, AlertUtil.Status status, AlertStateTracker.Event event) {
        SwingUtilities.invokeLater(() -> {
            String warehouse = data.getWarehouseName();
            if (status == AlertUtil.Status.WARNING) {
                warningCount++; // gói đã được ghi DB nên luôn cộng dồn, kể cả khi bị bỏ qua ở giao diện bên dưới
            }

            Long lastTs = lastAppliedTimestamp.get(warehouse);
            if (lastTs != null && data.getTimestamp() < lastTs) {
                System.out.println("[Server] Bỏ qua gói tin đến trễ/sai thứ tự từ " + warehouse);
                updateSummaryLabel();
                return;
            }
            lastAppliedTimestamp.put(warehouse, data.getTimestamp());

            // Gói tin do bộ dự phòng (BackupSensorClient) gửi KHÔNG được coi là "cảm biến chính đã
            // có tín hiệu trở lại" - nếu không, bảng sẽ hiện nhầm "AN TOÀN" trong khi cảm biến thật
            // vẫn đang chết, chỉ có phần mềm đang ước tính bù tạm (xem updateTableRow bên dưới).
            boolean backupSource = BackupUtil.isBackupSource(data);
            if (!backupSource) {
                long receivedAt = data.getServerReceivedAt() > 0 ? data.getServerReceivedAt() : System.currentTimeMillis();
                if (offlineTracker.onData(warehouse, receivedAt)) {
                    System.out.println("[Server] " + warehouse + ": đã có tín hiệu trở lại.");
                }
            }

            updateTableRow(data, status);
            updateChart(data);
            if (event == AlertStateTracker.Event.START) {
                Toolkit.getDefaultToolkit().beep(); // chỉ bíp khi BẮT ĐẦU sự cố, không bíp ở mọi gói
            }
            updateSummaryLabel();
        });
    }

    // ===================== PHÁT HIỆN MẤT TÍN HIỆU =====================

    private void startOfflineWatcher() {
        javax.swing.Timer timer = new javax.swing.Timer(OFFLINE_CHECK_MS, e -> {
            checkOffline();
            cleanExpiredMaintenance();
        });
        timer.setInitialDelay(OFFLINE_CHECK_MS);
        timer.start();
    }

    /** Chạy trên luồng Swing: đánh dấu kho im lặng quá lâu và gửi cảnh báo mất tín hiệu (1 lần / lần mất) */
    private void checkOffline() {
        for (OfflineTracker.Lost lost : offlineTracker.check(System.currentTimeMillis())) {
            TemperatureData last = lastDataByWarehouse.get(lost.warehouse);
            if (last != null) {
                updateTableRow(last, AlertUtil.checkStatus(last)); // đổi hàng sang "MẤT TÍN HIỆU"
            }
            // Kho đã mất tín hiệu: gỡ đường của nó khỏi biểu đồ ngay, thay vì để chú giải (legend)
            // hiển thị 1 tên kho mà không có đường nào vẽ ra (vì mọi điểm cũ đã trôi ra ngoài
            // cửa sổ WINDOW_MS của TemperatureChartPanel). Khi kho gửi dữ liệu trở lại, addPoint()
            // sẽ tự tạo lại đường mới bình thường.
            dongChartPanel.removeSeries(lost.warehouse);
            matChartPanel.removeSeries(lost.warehouse);
            if (!lost.silent) {
                // Phòng trường hợp bảo trì được bật nhưng mute() chưa kịp áp dụng (kho xuất
                // hiện sau khi bảo trì đã được đặt): kiểm tra DB 1 lần nữa trước khi gửi cảnh báo
                Long maint = DatabaseUtil.getWarehouseMaintenance(lost.warehouse);
                if (maint != null && System.currentTimeMillis() < maint) {
                    continue; // kho đang bảo trì -> bỏ qua cảnh báo mất tín hiệu
                }
                try {
                    threadPool.execute(() -> AlertNotifier.sendOffline(lost.warehouse, lost.lastSeenAt));
                } catch (RejectedExecutionException ignored) {
                    // đang tắt server
                }
            }
        }
        updateSummaryLabel();
    }

    /** Quét bảo trì quá hạn và tự dọn, unmute các kho đã hết bảo trì */
    private void cleanExpiredMaintenance() {
        java.util.List<String> expired = DatabaseUtil.clearExpiredMaintenance();
        for (String wh : expired) {
            offlineTracker.unmute(wh);
            System.out.println("[Server] Bảo trì kho '" + wh + "' đã hết hạn, tự động dọn.");
        }
        if (!expired.isEmpty()) refreshAllRows();
    }

    /** Cập nhật hàng thẻ tóm tắt (giữ tên cũ để không phải sửa các chỗ gọi) */
    private void updateSummaryLabel() {
        int warningRows = 0;
        for (int r = 0; r < tableModel.getRowCount(); r++) {
            Object st = tableModel.getValueAt(r, 5);
            if (st != null && st.toString().contains("CẢNH BÁO")) warningRows++;
        }
        int offline = offlineTracker.offlineCount();
        int rejected = SecurityUtil.getRejectedCount();
        cardTotal.set(tableModel.getRowCount(), UiTheme.ACCENT_BLUE);
        cardWarning.set(warningRows, warningRows > 0 ? UiTheme.ACCENT_RED : UiTheme.ACCENT_GREEN);
        cardOffline.set(offline, offline > 0 ? UiTheme.ACCENT_ORANGE : UiTheme.ACCENT_GRAY);
        cardWarnCount.set(warningCount, UiTheme.ACCENT_ORANGE);
        cardRejected.set(rejected, rejected > 0 ? UiTheme.ACCENT_RED : UiTheme.ACCENT_GRAY);
    }

    // ===================== BẢNG TRẠNG THÁI =====================

    private static String formatTime(long timestamp) {
        String today = new SimpleDateFormat("yyyyMMdd").format(new Date());
        boolean isToday = today.equals(new SimpleDateFormat("yyyyMMdd").format(new Date(timestamp)));
        // Dữ liệu của ngày khác phải hiện cả NGÀY để không bị hiểu nhầm là vừa đo
        return new SimpleDateFormat(isToday ? "HH:mm:ss" : "dd/MM HH:mm:ss").format(new Date(timestamp));
    }

    private void updateTableRow(TemperatureData data, AlertUtil.Status status) {
        String time = formatTime(data.getTimestamp());
        boolean offline = offlineTracker.isOffline(data.getWarehouseName());
        String statusText;
        Long maintenance = DatabaseUtil.getWarehouseMaintenance(data.getWarehouseName());
        boolean isMaintenance = maintenance != null && data.getTimestamp() < maintenance;

        if (isMaintenance) {
            statusText = "🔧 BẢO TRÌ";
        } else if (coldstorage.util.SensorAnomalyDetector.hasAnomaly(data.getWarehouseName())) {
            statusText = "🔧 NGHI CẢM BIẾN LỖI";
        } else if (BackupUtil.isBackupSource(data)) {
            // Cảm biến chính đang mất tín hiệu và bộ dự phòng đang tự gửi số liệu ƯỚC TÍNH thay thế
            statusText = "🔧 DỰ PHÒNG (ước tính)";
        } else if (offline) {
            statusText = "MẤT TÍN HIỆU";
        } else if (status == AlertUtil.Status.WARNING) {
            statusText = "⚠ CẢNH BÁO";
        } else if (coldstorage.util.TrendTracker.isTrendingNow(data.getWarehouseName())) {
            // Chưa thực sự vượt ngưỡng (status vẫn SAFE), nhưng đang tăng/giảm nhanh và dự kiến sắp chạm ngưỡng.
            // Xem TrendTracker + SensorHandler: chỉ được tính cho gói tin THẬT, không phải số liệu dự phòng.
            statusText = "◤ SẮP VƯỢT NGƯỠNG";
        } else {
            statusText = "AN TOÀN";
        }
        
        warehouseCardPanel.updateCard(data, offline, isMaintenance, coldstorage.util.SensorAnomalyDetector.hasAnomaly(data.getWarehouseName()));

        String threshold = AlertUtil.getThresholdText(data);
        String products = ProductUtil.getProductNames(data.getWarehouseName());
        if (products.isEmpty()) products = "—";
        lastDataByWarehouse.put(data.getWarehouseName(), data);
        String typeText = "DONG".equalsIgnoreCase(data.getWarehouseType()) ? "Kho đông" : "Kho mát";

        Object[] row = {
                data.getWarehouseName(), typeText, products, data.getTemperature(), threshold, statusText, time
        };

        Integer rowIdx = rowIndexByWarehouse.get(data.getWarehouseName());
        if (rowIdx == null) {
            tableModel.addRow(row);
            rowIndexByWarehouse.put(data.getWarehouseName(), tableModel.getRowCount() - 1);
        } else {
            for (int c = 0; c < row.length; c++) {
                tableModel.setValueAt(row[c], rowIdx, c);
            }
        }
    }

    /**
     * Vẽ lại toàn bộ bảng trạng thái theo ngưỡng/sản phẩm hiện hành, dùng ngay sau khi người dùng đổi sản phẩm
     * hoặc đổi ngưỡng (không phải chờ gói tin kế tiếp). Chạy trên luồng Swing.
     */
    public void refreshAllRows() {
        for (TemperatureData data : new ArrayList<>(lastDataByWarehouse.values())) {
            updateTableRow(data, AlertUtil.checkStatus(data));
        }
    }

    private void applyTableFilter() {
        if (tableSorter == null) return;
        java.util.List<RowFilter<DefaultTableModel, Object>> filters = new java.util.ArrayList<>();

        String type = (String) filterTypeCombo.getSelectedItem();
        if (!"Tất cả".equals(type)) {
            filters.add(RowFilter.regexFilter("^" + type + "$", 1));
        }

        String status = (String) filterStatusCombo.getSelectedItem();
        if (!"Tất cả".equals(status)) {
            filters.add(RowFilter.regexFilter("(?i)" + status, 5));
        }

        String name = filterNameField.getText().trim();
        if (!name.isEmpty()) {
            filters.add(RowFilter.regexFilter("(?i)" + name, 0));
        }

        if (filters.isEmpty()) {
            tableSorter.setRowFilter(null);
        } else {
            tableSorter.setRowFilter(RowFilter.andFilter(filters));
        }
    }

    /** Bật bảo trì cho kho: ghi DB + mute cảnh báo mất tín hiệu */
    public void startMaintenance(String warehouseName, long untilMillis) {
        DatabaseUtil.setWarehouseMaintenance(warehouseName, untilMillis);
        offlineTracker.mute(warehouseName);
    }

    /** Tắt bảo trì cho kho: xóa DB + unmute cảnh báo mất tín hiệu */
    public void stopMaintenance(String warehouseName) {
        DatabaseUtil.setWarehouseMaintenance(warehouseName, null);
        offlineTracker.unmute(warehouseName);
    }

    /** Danh sách kho đã có dữ liệu trong phiên này (dùng cho ProductPanel). Gọi trên luồng Swing. */
    public Set<String> getKnownWarehouseNames() {
        return new TreeSet<>(rowIndexByWarehouse.keySet());
    }

    /** Loại kho (DONG/MAT) của kho đã có dữ liệu; null nếu chưa biết. Gọi trên luồng Swing. */
    public String getWarehouseType(String warehouseName) {
        TemperatureData d = lastDataByWarehouse.get(warehouseName);
        return d == null ? null : d.getWarehouseType();
    }

    /** Mọi tên kho đang có trong hệ thống: đã từng gửi dữ liệu, đang chạy cảm biến giả lập, hoặc đã gán sản phẩm. */
    public Set<String> getAllWarehouseNames() {
        Set<String> all = new TreeSet<>(rowIndexByWarehouse.keySet());
        for (SensorClient s : activeSensors.values()) all.add(s.getWarehouseName());
        all.addAll(ProductUtil.getWarehousesWithProducts());
        return all;
    }

    /** Nếu tên gõ vào trùng (không phân biệt hoa/thường) với 1 kho đã biết thì trả về tên chuẩn của kho đó. */
    public String resolveWarehouseName(String typedName) {
        if (typedName == null) return "";
        String typed = typedName.trim();
        Set<String> all = new TreeSet<>(rowIndexByWarehouse.keySet());
        all.addAll(ProductUtil.getWarehousesWithProducts());
        for (String known : all) {
            if (known.equalsIgnoreCase(typed)) return known;
        }
        return typed;
    }

    /** Đưa điểm đo vào đúng biểu đồ theo loại kho */
    private void updateChart(TemperatureData data) {
        TemperatureChartPanel target = "DONG".equalsIgnoreCase(data.getWarehouseType())
                ? dongChartPanel : matChartPanel;
        target.addPoint(data.getWarehouseName(), data.getTimestamp(), data.getTemperature());
    }

    // ===================== QUẢN LÝ CẢM BIẾN GIẢ LẬP (gọi từ AdminPanel, trên luồng Swing) =====================

    /**
     * Thêm 1 cảm biến giả lập mới ngay trong tiến trình Server,
     * mô phỏng việc "thêm 1 kho lạnh mới" mà không cần sửa code/biên dịch lại.
     * Nhiệt độ khởi đầu chính là nhiệt độ vận hành (setpoint) của hệ thống làm lạnh mô phỏng.
     * @return null nếu thành công, ngược lại là thông báo lỗi để hiển thị cho người dùng
     */
    public synchronized String addSimulatedSensor(String sensorId, String warehouseName,
                                                   String warehouseType, double startTemp, int intervalMs) {
        // Ký tự '|' là dấu phân cách trường trong gói tin UDP nên không được có trong mã cảm biến / tên kho
        if (!sensorId.matches("[A-Za-z0-9_.\\-]{1,20}")) {
            return "Mã cảm biến chỉ gồm chữ/số/'_'/'-'/'.' và tối đa 20 ký tự.";
        }
        if (!warehouseName.matches("[^|\\p{Cntrl}]{1,40}")) {
            return "Tên kho tối đa 40 ký tự và không được chứa ký tự '|' hoặc ký tự điều khiển.";
        }
        if (!"DONG".equals(warehouseType) && !"MAT".equals(warehouseType)) {
            return "Loại kho phải là DONG hoặc MAT.";
        }
        if (Double.isNaN(startTemp) || Double.isInfinite(startTemp) || startTemp < -60 || startTemp > 60) {
            return "Nhiệt độ khởi đầu không hợp lệ (cho phép -60°C ~ 60°C).";
        }
        if (intervalMs < 500 || intervalMs > 600_000) {
            return "Chu kỳ gửi phải từ 500 ms đến 600000 ms.";
        }
        if (activeSensors.containsKey(sensorId)) {
            return "Mã cảm biến '" + sensorId + "' đã tồn tại.";
        }

        // Không cho 1 tên kho vừa là kho đông vừa là kho mát, hoặc trùng tên chỉ khác hoa/thường
        String knownType = getWarehouseType(warehouseName);
        for (SensorClient s : activeSensors.values()) {
            if (s.getWarehouseName().equals(warehouseName)) knownType = s.getWarehouseType();
        }
        if (knownType != null && !knownType.equalsIgnoreCase(warehouseType)) {
            return "Kho '" + warehouseName + "' đã tồn tại với loại " + knownType
                    + ". Một tên kho không thể vừa là kho đông vừa là kho mát.";
        }
        Set<String> knownNames = new TreeSet<>(rowIndexByWarehouse.keySet());
        for (SensorClient s : activeSensors.values()) knownNames.add(s.getWarehouseName());
        for (String n : knownNames) {
            if (n.equalsIgnoreCase(warehouseName) && !n.equals(warehouseName)) {
                return "Đã có kho tên '" + n + "' (chỉ khác hoa/thường). Hãy dùng đúng tên đó.";
            }
        }

        SensorClient sensor = new SensorClient(sensorId, warehouseName, warehouseType,
                startTemp, "localhost", UDP_PORT, intervalMs);
        Thread t = new Thread(sensor, "Thread-Sensor-" + sensorId);
        t.setDaemon(true);
        activeSensors.put(sensorId, sensor);
        activeSensorThreads.put(sensorId, t);
        offlineTracker.unmute(warehouseName);
        t.start();
        System.out.println("[Server] Đã thêm cảm biến giả lập mới: " + sensorId + " (" + warehouseName + ")");
        return null;
    }

    /** Dừng và gỡ bỏ 1 cảm biến giả lập đang chạy (kho của nó sẽ hiện MẤT TÍN HIỆU nhưng không gửi cảnh báo) */
    public synchronized void removeSimulatedSensor(String sensorId) {
        SensorClient sensor = activeSensors.remove(sensorId);
        activeSensorThreads.remove(sensorId);
        if (sensor != null) {
            sensor.stop();
            offlineTracker.mute(sensor.getWarehouseName());
            System.out.println("[Server] Đã dừng cảm biến giả lập: " + sensorId);
        }
    }

    public synchronized Set<String> getActiveSimulatedSensorIds() {
        return new LinkedHashSet<>(activeSensors.keySet());
    }

    /**
     * Xóa hẳn 1 kho khỏi hệ thống: dừng cảm biến giả lập đang gửi cho kho này (nếu có), gỡ khỏi
     * bảng/biểu đồ/theo dõi mất tín hiệu, gỡ khỏi mọi sản phẩm đang gán, và tùy chọn xóa luôn
     * lịch sử đo trong database. Gọi từ AdminPanel, trên luồng Swing.
     */
    public synchronized void deleteWarehouse(String warehouseName, boolean deleteHistory) {
        List<String> sensorIdsToStop = new ArrayList<>();
        for (SensorClient s : activeSensors.values()) {
            if (s.getWarehouseName().equals(warehouseName)) sensorIdsToStop.add(s.getSensorId());
        }
        for (String id : sensorIdsToStop) {
            removeSimulatedSensor(id);
        }

        Integer rowIdx = rowIndexByWarehouse.remove(warehouseName);
        if (rowIdx != null) {
            tableModel.removeRow(rowIdx);
            // Các dòng phía sau vừa dịch lên 1 bậc trong bảng -> đánh số lại chỉ mục tương ứng
            for (Map.Entry<String, Integer> e : rowIndexByWarehouse.entrySet()) {
                if (e.getValue() > rowIdx) e.setValue(e.getValue() - 1);
            }
        }
        if (warehouseCardPanel != null) warehouseCardPanel.removeCard(warehouseName);

        lastAppliedTimestamp.remove(warehouseName);
        lastDataByWarehouse.remove(warehouseName);
        offlineTracker.forget(warehouseName);
        dongChartPanel.removeSeries(warehouseName);
        matChartPanel.removeSeries(warehouseName);
        ProductUtil.forgetWarehouse(warehouseName);
        // Đánh dấu NGỪNG hoạt động trong sổ đăng ký (bất kể có tick "xóa luôn lịch sử" hay không),
        // để chương trình dự phòng (BackupLauncher, chạy ở tiến trình khác) biết dừng theo dõi kho
        // này thay vì tưởng nhầm là "cảm biến chính mất tín hiệu" rồi tự gửi dữ liệu hồi sinh nó.
        DatabaseUtil.markWarehouseInactive(warehouseName);
        // Kho đã xóa thì sự cố đang mở (nếu có) không thể "hồi phục" nữa -> đóng lại; xóa trạng thái cảnh báo/xu hướng
        // để nếu tạo lại kho trùng tên thì bắt đầu sạch (nếu không sẽ không bao giờ báo START vì tưởng vẫn đang báo động)
        DatabaseUtil.closeOpenIncidents(warehouseName, System.currentTimeMillis());
        AlertStateTracker.forget(warehouseName);
        coldstorage.util.TrendTracker.clear(warehouseName);

        if (deleteHistory) {
            DatabaseUtil.deleteWarehouseLogs(warehouseName);
            warningCount = DatabaseUtil.countWarnings(); // đếm lại vì 1 phần lịch sử vừa bị xóa
        }

        updateSummaryLabel();
        System.out.println("[Server] Đã xóa kho '" + warehouseName + "' khỏi hệ thống"
                + (deleteHistory ? " (kèm lịch sử database)." : " (giữ lại lịch sử database)."));
    }

    // ==========================================================================================

    private void setupShutdownHandler() {
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                System.out.println("[Server] Đang tắt hệ thống...");
                running = false;
                for (SensorClient sensor : activeSensors.values()) {
                    sensor.stop();
                }
                if (serverSocket != null && !serverSocket.isClosed()) {
                    serverSocket.close();
                }
                if (threadPool != null) {
                    // Chờ các gói đang xử lý ghi xong DB rồi mới đóng kết nối, tránh mất dữ liệu / mở lại kết nối vừa đóng
                    threadPool.shutdown();
                    try {
                        threadPool.awaitTermination(3, TimeUnit.SECONDS);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                }
                DatabaseUtil.closeConnection();
                dispose();
                System.exit(0);
            }
        });
    }

    /**
     * Chỉ tô màu ô "Trạng thái" (dạng huy hiệu), các ô còn lại kẻ sọc nhẹ; số căn giữa; dòng mất tín hiệu chữ xám.
     */
    private class StatusRowRenderer extends javax.swing.table.DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                boolean isSelected, boolean hasFocus, int row, int column) {
            if (value instanceof Double) value = String.format("%.1f", (Double) value);
            JLabel c = (JLabel) super.getTableCellRendererComponent(table, value, isSelected, false, row, column);
            String status = String.valueOf(table.getValueAt(row, 5));
            boolean offline = status.contains("MẤT TÍN HIỆU");

            c.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
            c.setHorizontalAlignment(column == 2 ? SwingConstants.LEFT : SwingConstants.CENTER);
            c.setFont(UiTheme.font(column == 0 ? Font.BOLD : Font.PLAIN, 13f));

            if (column == 5) { // ô trạng thái: huy hiệu màu
                Color bg, fg;
                if (status.contains("DỰ PHÒNG")) { bg = UiTheme.BACKUP_BG; fg = UiTheme.BACKUP_FG; }
                else if (status.contains("BẢO TRÌ")) { bg = UiTheme.MAINTENANCE_BG; fg = UiTheme.MAINTENANCE_FG; }
                else if (status.contains("CẢM BIẾN LỖI")) { bg = UiTheme.ANOMALY_BG; fg = UiTheme.ANOMALY_FG; }
                else if (offline) { bg = UiTheme.OFFLINE_BG; fg = UiTheme.OFFLINE_FG; }
                else if (status.contains("CẢNH BÁO")) { bg = UiTheme.WARN_BG; fg = UiTheme.WARN_FG; }
                else if (status.contains("SẮP VƯỢT")) { bg = new Color(255, 224, 178); fg = UiTheme.ACCENT_ORANGE; }
                else { bg = UiTheme.SAFE_BG; fg = UiTheme.SAFE_FG; }
                c.setOpaque(true);
                c.setBackground(bg);
                c.setForeground(fg);
                c.setFont(UiTheme.font(Font.BOLD, 13f));
            } else {
                c.setOpaque(true);
                c.setBackground(isSelected ? table.getSelectionBackground()
                        : (row % 2 == 0 ? Color.WHITE : new Color(247, 249, 251)));
                c.setForeground(isSelected ? table.getSelectionForeground()
                        : (offline ? UiTheme.OFFLINE_FG : UiTheme.TEXT_DARK));
            }
            return c;
        }
    }

    public static void main(String[] args) {
        UiTheme.install(); // look-and-feel + font + kiểu biểu đồ, phải gọi trước khi tạo cửa sổ
        SwingUtilities.invokeLater(() -> {
            MonitorServer server = new MonitorServer();
            server.setVisible(true);
        });
    }
}
