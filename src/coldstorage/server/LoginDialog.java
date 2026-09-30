package coldstorage.server;

import coldstorage.util.ConfigUtil;

import javax.swing.*;
import java.awt.*;
import java.util.Arrays;

/**
 * Hộp thoại đăng nhập trước khi mở "Quản trị". Chỉ hỏi mật khẩu nếu đã từng đặt (ConfigUtil.isAdminPasswordSet()) -
 * nếu chưa đặt, cho qua luôn để tương thích ngược với hành vi cũ (ai mở app cũng vào được Quản trị).
 */
public class LoginDialog extends JDialog {

    private boolean authenticated = false;
    private final JPasswordField passwordField = new JPasswordField(18);

    private LoginDialog(JFrame owner) {
        super(owner, "Đăng nhập Quản trị", true);
        setLayout(new BorderLayout(10, 10));
        setLocationRelativeTo(owner);

        JPanel form = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 20));
        form.add(new JLabel("Mật khẩu Quản trị:"));
        form.add(passwordField);
        add(form, BorderLayout.CENTER);

        JButton okBtn = new JButton("Đăng nhập");
        JButton cancelBtn = new JButton("Hủy");
        okBtn.addActionListener(e -> tryLogin());
        cancelBtn.addActionListener(e -> dispose());
        passwordField.addActionListener(e -> tryLogin()); // Enter cũng đăng nhập

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        buttons.add(cancelBtn);
        buttons.add(okBtn);
        add(buttons, BorderLayout.SOUTH);

        setSize(360, 150);
        setResizable(false);
    }

    private void tryLogin() {
        char[] pass = passwordField.getPassword();
        String plain = new String(pass);
        Arrays.fill(pass, '\0'); // xóa mật khẩu khỏi bộ nhớ ngay sau khi dùng, không giữ lại dạng chuỗi lâu hơn cần thiết
        if (ConfigUtil.checkAdminPassword(plain)) {
            authenticated = true;
            dispose();
        } else {
            JOptionPane.showMessageDialog(this, "Sai mật khẩu.", "Không thể đăng nhập", JOptionPane.ERROR_MESSAGE);
            passwordField.setText("");
            passwordField.requestFocusInWindow();
        }
    }

    /**
     * @return true nếu được phép vào Quản trị: đăng nhập đúng mật khẩu, HOẶC chưa từng đặt mật khẩu nào
     *         (tương thích ngược với hành vi cũ khi tính năng này chưa tồn tại).
     */
    public static boolean authenticate(JFrame owner) {
        if (!ConfigUtil.isAdminPasswordSet()) return true;
        LoginDialog dlg = new LoginDialog(owner);
        dlg.setVisible(true); // modal, chặn tới khi đóng
        return dlg.authenticated;
    }
}
