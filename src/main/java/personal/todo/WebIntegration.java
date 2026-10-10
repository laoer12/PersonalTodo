package personal.todo;

import javax.swing.*;
import java.awt.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.List;

/** Tests the actual native tray and reminder worker used by the Vue version. */
final class WebIntegration {
    private static int checks;
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; System.out.println("PASS " + message); }
    static void run() throws Exception {
        Path folder = AppPaths.testDirectory("personal-todo-vue-native-"); Store store = new Store(folder);
        Model.Data data = new Model.Data(); data.sound = false;
        Model.Task task = new Model.Task(); task.title = "Vue 版 · 后台提醒验证"; task.time = LocalTime.now().withSecond(0).withNano(0); data.tasks.add(task);
        int icons = SystemTray.isSupported() ? SystemTray.getSystemTray().getTrayIcons().length : 0;
        WebServer web = new WebServer(store, data, folder.resolve("server.json"), false, () -> {});
        try {
            check(store.acquire(), "Vue native acquires isolated D-drive data lock");
            web.start(); SwingUtilities.invokeAndWait(() -> {});
            if (SystemTray.isSupported()) check(SystemTray.getSystemTray().getTrayIcons().length == icons + 1, "Vue native tray registered without opening browser");
            check(Arrays.stream(Window.getWindows()).noneMatch(w -> w instanceof MainFrame && w.isVisible()), "Vue default does not create legacy Swing main window");
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline && web.service.copy().tasks.getFirst().notified.isEmpty()) Thread.sleep(50);
            check(store.load().tasks.getFirst().notified.contains(LocalDate.now()), "Vue reminder scheduler persists delivery with no browser window");
            SwingUtilities.invokeAndWait(() -> {
                check(Arrays.stream(Window.getWindows()).anyMatch(w -> w instanceof JDialog d && d.isVisible() && d.getTitle().equals("每日计划 · 提醒")), "Vue native reminder popup visible");
            });
            check(web.service.reminders(LocalDateTime.now()).isEmpty(), "Vue reminder delivery does not repeat");
            check(Model.due(store.load(), LocalDateTime.now()).isEmpty(), "Vue reminder deduplication survives reload");
        } finally { web.close(); store.release(); SwingUtilities.invokeAndWait(() -> {}); }
        if (SystemTray.isSupported()) check(SystemTray.getSystemTray().getTrayIcons().length == icons, "Vue native exit removes tray icon");
        check(!Files.exists(folder.resolve("server.json")), "Vue native exit removes connection metadata");
        if (!folder.toRealPath().startsWith(AppPaths.TEMP.toRealPath()) || !folder.getFileName().toString().startsWith("personal-todo-vue-native-")) throw new IllegalStateException("Unexpected test path");
        try (var files = Files.walk(folder)) { for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file); }
        System.out.println("All " + checks + " Vue native checks passed.");
    }
}
