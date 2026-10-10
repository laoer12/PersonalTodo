package personal.todo;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.List;

final class DesktopBridge {
    private final TodoService service;
    private final Runnable open, exit;
    private TrayIcon tray;
    private JDialog popup;
    private final boolean headless;
    private volatile boolean startup;
    DesktopBridge(TodoService service, Runnable open, Runnable exit, boolean headless) {
        this.service = service; this.open = open; this.exit = exit; this.headless = headless;
        startup = !headless && Startup.enabled();
        if (!headless) SwingUtilities.invokeLater(this::installTray);
    }
    boolean startupAvailable() { return !headless && Startup.available(); }
    boolean startup() { return startup; }
    void startup(boolean enabled) throws IOException { if (!startupAvailable()) throw new IOException("开机启动请在打包后的桌面程序中设置。"); Startup.set(enabled); startup = enabled; }
    private void installTray() {
        if (!SystemTray.isSupported()) return;
        PopupMenu menu = new PopupMenu(); MenuItem launch = new MenuItem("打开每日计划"), stop = new MenuItem("完全退出");
        launch.addActionListener(e -> open.run()); stop.addActionListener(e -> exit.run()); menu.add(launch); menu.addSeparator(); menu.add(stop);
        tray = new TrayIcon(Theme.icon(32), "个人每日计划 · Vue 版", menu); tray.setImageAutoSize(true); tray.addActionListener(e -> open.run());
        try { SystemTray.getSystemTray().add(tray); } catch (AWTException e) { tray = null; }
    }
    void reminder(List<String> titles) {
        if (headless) return;
        SwingUtilities.invokeLater(() -> {
            if (service.copy().sound) Toolkit.getDefaultToolkit().beep();
            if (tray != null) tray.displayMessage("该做这件事了", String.join("\n", titles), TrayIcon.MessageType.INFO);
            if (popup != null) popup.dispose();
            popup = new JDialog((Frame)null, "每日计划 · 提醒", false); popup.setIconImage(Theme.icon(32)); popup.setAlwaysOnTop(true); popup.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            JPanel root = new JPanel(new BorderLayout(0, 16)); root.setBorder(new EmptyBorder(23, 24, 20, 24)); root.add(Theme.heading("该做这件事了", 21), BorderLayout.NORTH);
            JTextArea text = Theme.area(4); text.setText(String.join("\n\n", titles)); text.setEditable(false); root.add(Theme.scroll(text));
            JPanel actions = Theme.panel(new FlowLayout(FlowLayout.RIGHT, 8, 0)); actions.add(Theme.button("关闭提醒", false, () -> popup.dispose()));
            actions.add(Theme.button("查看待办", true, () -> { popup.dispose(); open.run(); })); root.add(actions, BorderLayout.SOUTH);
            popup.setContentPane(root); popup.setSize(420, 270); Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
            popup.setLocation(screen.x + screen.width - 440, screen.y + screen.height - 290); popup.setVisible(true);
        });
    }
    void openFolder(String name) throws IOException {
        if (headless) return;
        Path folder = switch (name) { case "root" -> AppPaths.ROOT; case "data" -> service.store.directory;
            case "backups" -> service.store.directory.resolve("backups"); case "exports" -> service.store.directory.resolve("exports"); case "logs" -> AppPaths.LOGS; default -> throw new IOException("未知目录。"); };
        Files.createDirectories(folder); Desktop.getDesktop().open(folder.toFile());
    }
    void close() { SwingUtilities.invokeLater(() -> { if (popup != null) popup.dispose(); if (tray != null) SystemTray.getSystemTray().remove(tray); }); }
}
