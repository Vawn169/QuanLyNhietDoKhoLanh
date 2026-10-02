package coldstorage.server;

import coldstorage.util.ConfigUtil;
import coldstorage.util.TelegramNotifier;

import javax.swing.*;
import java.awt.*;

/**
 * Cửa sổ "Bảo mật & Thông báo": gộp 2 việc không liên quan tới ngưỡng/cảm biến nên KHÔNG đặt vào AdminPanel
 * (vốn đã khá dài) mà tách riêng:
 * 1. Đổi mật khẩu Quản trị (SHA-256 + salt, xem ConfigUtil).
 * 2. Bật/tắt và cấu hình thông báo đẩy Telegram (xem TelegramNotifier để biết cách lấy bot token/chat id).
 */
public class SecuritySettingsDialog extends JPanel {

    public SecuritySettingsDialog() {
        setLayout(new BorderLayout(10, 10));

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        content.add(buildPasswordPanel());
        content.add(Box.createVerticalStrut(10));
        content.add(buildTelegramPanel());

        add(new JScrollPane(content), BorderLayout.CENTER);
    }

    private JPanel buildPasswordPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Mật khẩu Quản trị"));
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(4, 4, 4, 4);
        gc.fill = GridBagConstraints.HORIZONTAL;

        JLabel statusLabel = new JLabel(ConfigUtil.isAdminPasswordSet()
                ? "Đã đặt mật khẩu. Nhập mật khẩu mới bên dưới để đổi."
                : "Chưa đặt mật khẩu - hiện ai mở app cũng vào được Quản trị.");
        gc.gridx = 0; gc.gridy = 0; gc.gridwidth = 2;
        panel.add(statusLabel, gc);

        JPasswordField newPass = new JPasswordField();
        JPasswordField confirmPass = new JPasswordField();
        gc.gridwidth = 1;
        gc.gridy = 1; gc.gridx = 0; panel.add(new JLabel("Mật khẩu mới:"), gc);
        gc.gridx = 1; panel.add(newPass, gc);
        gc.gridy = 2; gc.gridx = 0; panel.add(new JLabel("Nhập lại:"), gc);
        gc.gridx = 1; panel.add(confirmPass, gc);

        JButton saveBtn = new JButton("Lưu mật khẩu");
        saveBtn.addActionListener(e -> {
            char[] p1 = newPass.getPassword();
            char[] p2 = confirmPass.getPassword();
            String s1 = new String(p1), s2 = new String(p2);
            java.util.Arrays.fill(p1, '\0');
            java.util.Arrays.fill(p2, '\0');
            if (s1.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Mật khẩu không được để trống.", "Lỗi", JOptionPane.ERROR_MESSAGE);
                return;
            }
            if (!s1.equals(s2)) {
                JOptionPane.showMessageDialog(this, "2 ô mật khẩu không khớp.", "Lỗi", JOptionPane.ERROR_MESSAGE);
                return;
            }
            ConfigUtil.setAdminPassword(s1);
            newPass.setText("");
            confirmPass.setText("");
            statusLabel.setText("Đã lưu mật khẩu mới. Lần mở Quản trị kế tiếp sẽ cần mật khẩu này.");
            JOptionPane.showMessageDialog(this, "Đã lưu mật khẩu Quản trị.");
        });
        gc.gridy = 3; gc.gridx = 0; gc.gridwidth = 2;
        panel.add(saveBtn, gc);
        return panel;
    }

    private JPanel buildTelegramPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Thông báo đẩy qua Telegram"));
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(4, 4, 4, 4);
        gc.fill = GridBagConstraints.HORIZONTAL;

        JCheckBox enabledCheck = new JCheckBox("Kích hoạt gửi thông báo qua Telegram", ConfigUtil.isTelegramEnabled());
        gc.gridx = 0; gc.gridy = 0; gc.gridwidth = 2;
        panel.add(enabledCheck, gc);

        JTextField tokenField = new JTextField(ConfigUtil.getTelegramBotToken());
        JTextField chatIdField = new JTextField(ConfigUtil.getTelegramChatId());
        gc.gridwidth = 1;
        gc.gridy = 1; gc.gridx = 0; panel.add(new JLabel("Bot Token:"), gc);
        gc.gridx = 1; panel.add(tokenField, gc);
        gc.gridy = 2; gc.gridx = 0; panel.add(new JLabel("Chat ID:"), gc);
        gc.gridx = 1; panel.add(chatIdField, gc);

        JTextArea help = new JTextArea(
                "Cách lấy Bot Token + Chat ID:\n" +
                "1. Chat với @BotFather trên Telegram, gõ /newbot, làm theo hướng dẫn -> nhận Token.\n" +
                "2. Bấm Start (hoặc thêm bot vào 1 nhóm) để bot nhận được tin nhắn từ bạn.\n" +
                "3. Mở trình duyệt: https://api.telegram.org/bot<TOKEN>/getUpdates -> tìm \"chat\":{\"id\": ...}.");
        help.setEditable(false);
        help.setLineWrap(true);
        help.setWrapStyleWord(true);
        help.setBackground(panel.getBackground());
        help.setFont(help.getFont().deriveFont(11f));
        gc.gridy = 3; gc.gridx = 0; gc.gridwidth = 2;
        panel.add(help, gc);

        JButton testBtn = new JButton("Gửi thử");
        JButton saveBtn = new JButton("Lưu cấu hình");
        testBtn.addActionListener(e -> {
            if (tokenField.getText().trim().isEmpty() || chatIdField.getText().trim().isEmpty()) {
                JOptionPane.showMessageDialog(this, "Điền đủ Bot Token và Chat ID trước khi gửi thử.",
                        "Thiếu thông tin", JOptionPane.WARNING_MESSAGE);
                return;
            }
            // Gửi thử trực tiếp bằng giá trị trên form, KHÔNG ghi config (không tự bật thông báo thật)
            TelegramNotifier.sendTest(tokenField.getText(), chatIdField.getText());
            JOptionPane.showMessageDialog(this, "Đã gửi yêu cầu (kiểm tra Telegram trong vài giây; " +
                    "nếu không nhận được, xem lỗi ở cửa sổ Output của NetBeans).");
        });
        saveBtn.addActionListener(e -> {
            ConfigUtil.setTelegramConfig(enabledCheck.isSelected(), tokenField.getText(), chatIdField.getText());
            JOptionPane.showMessageDialog(this, "Đã lưu cấu hình Telegram.");
        });
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        btnRow.add(testBtn);
        btnRow.add(saveBtn);
        gc.gridy = 4;
        panel.add(btnRow, gc);
        return panel;
    }
}
