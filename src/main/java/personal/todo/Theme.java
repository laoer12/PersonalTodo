package personal.todo;

import javax.swing.*;
import javax.swing.border.*;
import javax.swing.plaf.*;
import javax.swing.plaf.basic.*;
import javax.swing.plaf.metal.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;

final class Theme {
    static final Color PAGE = Color.WHITE, SIDEBAR = new Color(0xF7F7F8), INK = new Color(0x202123), MUTED = new Color(0x8B8D91);
    static final Color ACCENT = new Color(0x202123), PALE = new Color(0xE9E9EA), LINE = new Color(0xECECEE);
    static final Color ORANGE = new Color(0x76787C), RED = new Color(0xB86C6C), WHITE = Color.WHITE;
    static Font font(float size) { return new Font("Microsoft YaHei UI", Font.PLAIN, (int)size); }
    static Font bold(float size) { return font(size).deriveFont(Font.BOLD, size); }
    static void install() {
        MetalLookAndFeel.setCurrentTheme(new DefaultMetalTheme() {
            @Override protected ColorUIResource getPrimary1() { return new ColorUIResource(0x8B8D91); }
            @Override protected ColorUIResource getPrimary2() { return new ColorUIResource(0xC9C9CC); }
            @Override protected ColorUIResource getPrimary3() { return new ColorUIResource(0xECECEE); }
        });
        try { UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName()); } catch (Exception ignored) { }
        var defaults = UIManager.getDefaults();
        for (Object key : defaults.keySet().toArray()) if (defaults.get(key) instanceof Font) defaults.put(key, font(13));
        defaults.put("Panel.background", PAGE); defaults.put("Label.foreground", INK);
        defaults.put("TextField.caretForeground", ACCENT); defaults.put("TextArea.caretForeground", ACCENT);
        defaults.put("TextField.selectionBackground", PALE); defaults.put("TextArea.selectionBackground", PALE);
        defaults.put("TextField.inactiveForeground", MUTED); defaults.put("TextField.inactiveBackground", SIDEBAR);
        defaults.put("CheckBox.background", WHITE); defaults.put("CheckBox.foreground", INK);
        defaults.put("CheckBox.icon", new TickIcon(false)); defaults.put("CheckBox.select", PALE);
        defaults.put("ScrollBar.width", 8); defaults.put("ScrollBar.thumb", new Color(0xD7D7D9));
        defaults.put("ScrollBar.track", PAGE); defaults.put("ComboBox.background", WHITE);
        defaults.put("OptionPane.background", PAGE); defaults.put("Button.font", font(13));
        defaults.put("ComboBox.selectionBackground", PALE); defaults.put("ComboBox.selectionForeground", INK);
        defaults.put("ComboBox.foreground", INK); defaults.put("ComboBox.disabledBackground", SIDEBAR);
        defaults.put("ComboBox.border", new EmptyBorder(3, 5, 3, 5));
        defaults.put("ComboBoxUI", GrayComboBoxUI.class.getName());
        defaults.put("ProgressBar.foreground", ACCENT); defaults.put("ProgressBar.background", LINE);
        ToolTipManager.sharedInstance().setInitialDelay(450);
    }
    static JPanel panel(LayoutManager layout) { JPanel p = new JPanel(layout); p.setOpaque(false); return p; }
    static JPanel column() { JPanel p = panel(null); p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS)); return p; }
    static JLabel label(String text, int size, Color color) { JLabel l = new JLabel(text); l.setFont(font(size)); l.setForeground(color); return l; }
    static JLabel heading(String text, int size) { JLabel l = label(text, size, INK); l.setFont(bold(size)); return l; }
    static JTextField field() { JTextField f = new JTextField(); f.setFont(font(14)); f.setBorder(new CompoundBorder(new LineBorder(LINE), new EmptyBorder(9, 10, 9, 10))); return f; }
    static JTextArea area(int rows) { JTextArea a = new JTextArea(rows, 30); a.setFont(font(13)); a.setLineWrap(true); a.setWrapStyleWord(true); a.setBorder(new EmptyBorder(9, 10, 9, 10)); return a; }
    static JButton button(String text, boolean primary, Runnable action) {
        JButton button = new SoftButton(text, primary); button.addActionListener(e -> action.run()); return button;
    }
    static JButton small(String text, Runnable action) {
        JButton b = button(text, false, action); b.setBorder(new EmptyBorder(6, 10, 6, 10)); b.setFont(font(12)); return b;
    }
    static JButton link(String text, Runnable action) {
        JButton b = new JButton(text); b.setFont(font(12)); b.setForeground(MUTED); b.setContentAreaFilled(false);
        b.setBorder(new EmptyBorder(5, 7, 5, 7)); b.setBorderPainted(false); b.setFocusPainted(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)); b.addActionListener(e -> action.run());
        b.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { b.setForeground(INK); }
            @Override public void mouseExited(MouseEvent e) { b.setForeground(MUTED); }
        }); return b;
    }
    static JButton nav(String text, boolean active, Runnable action) {
        JButton b = button(text, false, action); b.putClientProperty("nav", true); b.putClientProperty("active", active);
        b.setHorizontalAlignment(SwingConstants.LEFT); b.setBorder(new EmptyBorder(9, 15, 9, 15)); b.setFont(font(13)); return b;
    }
    static final class PlaceholderField extends JTextField {
        private final String placeholder;
        PlaceholderField(String placeholder) { this.placeholder = placeholder; }
        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if (getText().isEmpty()) {
                Graphics2D g = (Graphics2D)graphics.create(); g.setFont(getFont()); g.setColor(MUTED);
                Insets inset = getInsets(); FontMetrics fm = g.getFontMetrics();
                g.drawString(placeholder, inset.left + 1, (getHeight() - fm.getHeight()) / 2 + fm.getAscent()); g.dispose();
            }
        }
    }
    static JScrollPane scroll(Component component) {
        JScrollPane pane = new JScrollPane(component); pane.setBorder(null); pane.getViewport().setOpaque(false); pane.setOpaque(false);
        pane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        pane.getVerticalScrollBar().setUnitIncrement(22);
        pane.getVerticalScrollBar().setUI(new BasicScrollBarUI() {
            @Override protected JButton createDecreaseButton(int orientation) { return invisibleArrow(); }
            @Override protected JButton createIncreaseButton(int orientation) { return invisibleArrow(); }
            private JButton invisibleArrow() { JButton b = new JButton(); b.setPreferredSize(new Dimension(0, 0)); return b; }
            @Override protected void paintTrack(Graphics g, JComponent c, Rectangle bounds) { }
            @Override protected void paintThumb(Graphics graphics, JComponent c, Rectangle r) {
                if (r.isEmpty() || !c.isEnabled()) return;
                Graphics2D g = (Graphics2D)graphics.create(); g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(new Color(0xD5D5D8)); g.fillRoundRect(r.x + 2, r.y, Math.max(3, r.width - 3), r.height, 6, 6); g.dispose();
            }
        }); return pane;
    }
    public static final class GrayComboBoxUI extends BasicComboBoxUI {
        public static ComponentUI createUI(JComponent c) { return new GrayComboBoxUI(); }
        @Override protected JButton createArrowButton() {
            JButton arrow = new BasicArrowButton(SwingConstants.SOUTH, SIDEBAR, SIDEBAR, MUTED, SIDEBAR);
            arrow.setBorder(new EmptyBorder(0, 4, 0, 4)); return arrow;
        }
    }
    static JCheckBox check(String title, boolean selected) {
        JCheckBox b = new JCheckBox(title, selected); b.setOpaque(false); b.setFocusPainted(false); b.setIcon(new TickIcon(false));
        b.setSelectedIcon(new TickIcon(true)); b.setIconTextGap(10); b.setFont(font(13)); return b;
    }
    static void gap(JPanel panel, int height) { panel.add(Box.createVerticalStrut(height)); }
    static void bounded(JComponent component, int height) {
        component.setAlignmentX(Component.LEFT_ALIGNMENT); component.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
    }
    static String html(String text) { return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }
    static BufferedImage icon(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB); Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(ACCENT); g.fillRoundRect(0, 0, size, size, size / 3, size / 3);
        g.setColor(WHITE); g.setStroke(new BasicStroke(size / 12f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine(size * 27 / 100, size / 2, size * 44 / 100, size * 67 / 100);
        g.drawLine(size * 44 / 100, size * 67 / 100, size * 75 / 100, size * 33 / 100); g.dispose(); return image;
    }
    static class Card extends JPanel {
        private final Color color;
        Card(LayoutManager layout) { this(layout, WHITE); }
        Card(LayoutManager layout, Color color) { super(layout); this.color = color; setOpaque(false); setBorder(new EmptyBorder(18, 20, 18, 20)); }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D)graphics.create(); g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(color); g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 18, 18);
            g.setColor(LINE); g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 18, 18); g.dispose(); super.paintComponent(graphics);
        }
    }
    static final class SoftButton extends JButton {
        final boolean primary;
        SoftButton(String text, boolean primary) {
            super(text); this.primary = primary; setContentAreaFilled(false); setOpaque(false); setFocusPainted(false); setBorderPainted(false);
            setBorder(new EmptyBorder(10, 16, 10, 16)); setForeground(primary ? WHITE : INK); setFont(font(13));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D)graphics.create(); g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            boolean nav = Boolean.TRUE.equals(getClientProperty("nav")), active = Boolean.TRUE.equals(getClientProperty("active"));
            g.setColor(!isEnabled() ? LINE : primary ? (getModel().isRollover() ? new Color(0x38393C) : ACCENT)
                : nav ? (active ? PALE : getModel().isRollover() ? new Color(0xEFEFF0) : SIDEBAR)
                : (getModel().isRollover() ? SIDEBAR : WHITE));
            g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
            if (!primary && !nav) { g.setColor(new Color(0xDFDFE2)); g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12); }
            g.dispose(); super.paintComponent(graphics);
        }
    }
    private record TickIcon(boolean selected) implements Icon {
        public int getIconWidth() { return 22; } public int getIconHeight() { return 22; }
        public void paintIcon(Component c, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D)graphics.create(); g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(selected ? ACCENT : WHITE); g.fillOval(x + 1, y + 1, 19, 19);
            g.setColor(selected ? ACCENT : new Color(0xC9C9CC)); g.drawOval(x + 1, y + 1, 19, 19);
            if (selected) { g.setColor(WHITE); g.setStroke(new BasicStroke(2f)); g.drawLine(x + 5, y + 10, x + 9, y + 14); g.drawLine(x + 9, y + 14, x + 16, y + 7); }
            g.dispose();
        }
    }
}
