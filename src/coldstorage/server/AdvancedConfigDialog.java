package coldstorage.server;

import coldstorage.util.ConfigUtil;

import javax.swing.*;
import java.awt.*;
import java.util.HashMap;
import java.util.Map;

public class AdvancedConfigDialog extends JPanel {

    private JTextField alertRemindMinutes;
    private JTextField trendWindowSize;
    private JTextField trendMinSpanSec;
    private JTextField trendHorizonMinutes;
    private JTextField trendMinSlopePerMin;
    private JTextField trendCooldownMinutes;
    private JTextField offlineMinSec;
    private JTextField offlineFactor;
    private JTextField backupOfflineMinSec;
    private JTextField backupOfflineFactor;
    private JTextField backupPullback;
    private JTextField backupNoise;
    private JTextField backupPollSec;

    public AdvancedConfigDialog() {
        setLayout(new BorderLayout(10, 10));

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        
        content.add(buildFormPanel());
        content.add(Box.createVerticalStrut(10));
        content.add(buildButtonPanel());

        add(new JScrollPane(content), BorderLayout.CENTER);
        loadValues();
    }

    private JPanel buildFormPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Cấu hình Thuật toán"));
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(4, 4, 4, 4);
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.gridx = 0;
        gc.gridy = 0;

        alertRemindMinutes = addField(panel, gc, "alert.remind.minutes", "Nhắc lại cảnh báo (phút):");
        trendWindowSize = addField(panel, gc, "trend.window.size", "Kích thước cửa sổ xu hướng:");
        trendMinSpanSec = addField(panel, gc, "trend.min.span.sec", "Thời gian tối thiểu xu hướng (giây):");
        trendHorizonMinutes = addField(panel, gc, "trend.horizon.minutes", "Dự báo chạm ngưỡng (phút):");
        trendMinSlopePerMin = addField(panel, gc, "trend.min.slope.per.min", "Tốc độ xu hướng tối thiểu (°C/phút):");
        trendCooldownMinutes = addField(panel, gc, "trend.cooldown.minutes", "Giãn cách cảnh báo sớm (phút):");
        offlineMinSec = addField(panel, gc, "offline.min.sec", "Thời gian tối thiểu mất tín hiệu (giây):");
        offlineFactor = addField(panel, gc, "offline.factor", "Hệ số mất tín hiệu:");
        backupOfflineMinSec = addField(panel, gc, "backup.offline.min.sec", "Thời gian tối thiểu hỏng cảm biến (giây):");
        backupOfflineFactor = addField(panel, gc, "backup.offline.factor", "Hệ số hỏng cảm biến:");
        backupPullback = addField(panel, gc, "backup.pullback", "Tốc độ kéo nhiệt dự phòng:");
        backupNoise = addField(panel, gc, "backup.noise", "Độ nhiễu nhiệt dự phòng:");
        backupPollSec = addField(panel, gc, "backup.poll.sec", "Chu kỳ dự phòng (giây):");

        return panel;
    }
    
    private JTextField addField(JPanel panel, GridBagConstraints gc, String configKey, String label) {
        gc.gridwidth = 1;
        gc.gridx = 0;
        panel.add(new JLabel(label), gc);
        JTextField field = new JTextField(10);
        gc.gridx = 1;
        panel.add(field, gc);
        gc.gridy++;
        return field;
    }
    
    private void loadValues() {
        alertRemindMinutes.setText(String.valueOf(ConfigUtil.getAlertRemindMinutes()));
        trendWindowSize.setText(String.valueOf(ConfigUtil.getTrendWindowSize()));
        trendMinSpanSec.setText(String.valueOf(ConfigUtil.getTrendMinSpanSec()));
        trendHorizonMinutes.setText(String.valueOf(ConfigUtil.getTrendHorizonMinutes()));
        trendMinSlopePerMin.setText(String.valueOf(ConfigUtil.getTrendMinSlopePerMin()));
        trendCooldownMinutes.setText(String.valueOf(ConfigUtil.getTrendCooldownMinutes()));
        offlineMinSec.setText(String.valueOf(ConfigUtil.getOfflineMinSec()));
        offlineFactor.setText(String.valueOf(ConfigUtil.getOfflineFactor()));
        backupOfflineMinSec.setText(String.valueOf(ConfigUtil.getBackupOfflineMinSec()));
        backupOfflineFactor.setText(String.valueOf(ConfigUtil.getBackupOfflineFactor()));
        backupPullback.setText(String.valueOf(ConfigUtil.getBackupPullback()));
        backupNoise.setText(String.valueOf(ConfigUtil.getBackupNoise()));
        backupPollSec.setText(String.valueOf(ConfigUtil.getBackupPollSec()));
    }

    private JPanel buildButtonPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton btnSave = new JButton("Lưu");
        JButton btnDefault = new JButton("Khôi phục mặc định");

        btnSave.addActionListener(e -> save());
        btnDefault.addActionListener(e -> restoreDefaults());

        panel.add(btnDefault);
        panel.add(btnSave);
        return panel;
    }
    
    private void restoreDefaults() {
        alertRemindMinutes.setText("5.0");
        trendWindowSize.setText("12");
        trendMinSpanSec.setText("45");
        trendHorizonMinutes.setText("5.0");
        trendMinSlopePerMin.setText("0.03");
        trendCooldownMinutes.setText("10.0");
        offlineMinSec.setText("15");
        offlineFactor.setText("3.0");
        backupOfflineMinSec.setText("20");
        backupOfflineFactor.setText("3.0");
        backupPullback.setText("0.15");
        backupNoise.setText("0.15");
        backupPollSec.setText("5");
    }

    private void save() {
        Map<String, String> configs = new HashMap<>();
        configs.put("alert.remind.minutes", alertRemindMinutes.getText().trim());
        configs.put("trend.window.size", trendWindowSize.getText().trim());
        configs.put("trend.min.span.sec", trendMinSpanSec.getText().trim());
        configs.put("trend.horizon.minutes", trendHorizonMinutes.getText().trim());
        configs.put("trend.min.slope.per.min", trendMinSlopePerMin.getText().trim());
        configs.put("trend.cooldown.minutes", trendCooldownMinutes.getText().trim());
        configs.put("offline.min.sec", offlineMinSec.getText().trim());
        configs.put("offline.factor", offlineFactor.getText().trim());
        configs.put("backup.offline.min.sec", backupOfflineMinSec.getText().trim());
        configs.put("backup.offline.factor", backupOfflineFactor.getText().trim());
        configs.put("backup.pullback", backupPullback.getText().trim());
        configs.put("backup.noise", backupNoise.getText().trim());
        configs.put("backup.poll.sec", backupPollSec.getText().trim());

        ConfigUtil.setAdvancedConfigs(configs);
        JOptionPane.showMessageDialog(this, "Đã lưu cấu hình nâng cao thành công!", "Thành công", JOptionPane.INFORMATION_MESSAGE);
    }
}
