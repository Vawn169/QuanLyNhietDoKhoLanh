package coldstorage.server;

import javax.swing.*;
import java.awt.*;

/** Thẻ tóm tắt: số lớn + chú thích nhỏ, có vạch màu bên trái (đổi màu theo trạng thái). */
public class StatCard extends JPanel {

    private final JLabel valueLabel = new JLabel("0");
    private final JLabel captionLabel;
    private Color accent;

    public StatCard(String caption, Color accent) {
        this.accent = accent;
        setOpaque(false);
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(8, 18, 8, 12));

        valueLabel.setFont(UiTheme.font(Font.BOLD, 26f));
        valueLabel.setForeground(accent);
        captionLabel = new JLabel(caption);
        captionLabel.setFont(UiTheme.font(Font.PLAIN, 12f));
        captionLabel.setForeground(UiTheme.ACCENT_GRAY);

        add(valueLabel, BorderLayout.CENTER);
        add(captionLabel, BorderLayout.SOUTH);
        setPreferredSize(new Dimension(150, 70));
    }

    /** Cập nhật số hiển thị và màu nhấn */
    public void set(int value, Color accent) {
        this.accent = accent;
        valueLabel.setText(String.valueOf(value));
        valueLabel.setForeground(accent);
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int w = getWidth(), h = getHeight();
        g2.setColor(Color.WHITE);
        g2.fillRoundRect(0, 0, w - 1, h - 1, 12, 12);
        g2.setColor(new Color(224, 228, 233));
        g2.drawRoundRect(0, 0, w - 1, h - 1, 12, 12);
        g2.setColor(accent);
        g2.fillRoundRect(0, 8, 5, h - 16, 5, 5); // vạch màu bên trái
        g2.dispose();
        super.paintComponent(g);
    }
}
