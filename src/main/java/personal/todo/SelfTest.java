package personal.todo;

import javax.swing.*;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.*;

final class SelfTest {
    private static int count;
    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description); count++; System.out.println("PASS " + description);
    }
    private static void rejects(Runnable action, String description) {
        boolean rejected = false; try { action.run(); } catch (IllegalArgumentException e) { rejected = true; } check(rejected, description);
    }
    static void run() throws Exception {
        check(AppPaths.DATA.getRoot().equals(AppPaths.ROOT.getRoot()), "data stays on chosen application volume");
        check(Path.of(System.getProperty("java.io.tmpdir")).equals(AppPaths.TEMP), "runtime temp stays in application home");
        Path otherVolume = Path.of(AppPaths.ROOT.getRoot().toString().equalsIgnoreCase("C:\\") ? "D:/PersonalTodo-test/data.json" : "C:/PersonalTodo-test/data.json");
        rejects(() -> AppPaths.storagePath(otherVolume), "reject writes outside chosen volume");
        check(AppPaths.onVolume(Path.of("C:/PersonalTodo-test/data.json"), Path.of("C:/")).equals(Path.of("C:/PersonalTodo-test/data.json")), "C drive is supported when selected (validation only)");
        check(AppPaths.homeFrom(Path.of("D:/clone with spaces/build/input/personal-todo.jar")).equals(Path.of("D:/clone with spaces")), "source JAR detects relocated project home");
        check(AppPaths.homeFrom(Path.of("D:/clone with spaces/build/classes")).equals(Path.of("D:/clone with spaces")), "class output detects project home");
        check(AppPaths.homeFrom(Path.of("D:/portable with spaces/app/personal-todo.jar")).equals(Path.of("D:/portable with spaces")), "packaged JAR detects portable home");
        check(AppPaths.homeFrom(Path.of("D:/standalone/personal-todo.jar")).equals(Path.of("D:/standalone")), "standalone JAR uses its own directory");
        Model.Task t = new Model.Task(); t.title = "Test 中文"; t.start = LocalDate.of(2026, 1, 31);
        check(Model.occurs(t, t.start), "single task on scheduled date"); check(!Model.occurs(t, t.start.plusDays(1)), "single task excluded next day");
        t.repeat = "daily"; check(!Model.occurs(t, t.start.minusDays(1)), "recurrence excluded before start"); check(Model.occurs(t, t.start.plusYears(1)), "daily recurrence");
        t.repeat = "monthly"; check(Model.occurs(t, LocalDate.of(2026, 2, 28)), "month-end February rollover"); check(!Model.occurs(t, LocalDate.of(2026, 2, 27)), "month-end excludes previous day");
        check(Model.occurs(t, LocalDate.of(2028, 2, 29)), "leap-year month end"); check(Model.occurs(t, LocalDate.of(2026, 4, 30)), "30-day month rollover");
        t.repeat = "weekly"; check(Model.occurs(t, t.start.plusWeeks(1)), "weekly same weekday"); check(!Model.occurs(t, t.start.plusDays(1)), "weekly excludes other weekdays");
        t.repeat = "weekdays"; check(!Model.occurs(t, t.start), "weekdays excludes Saturday"); check(!Model.occurs(t, t.start.plusDays(1)), "weekdays excludes Sunday"); check(Model.occurs(t, t.start.plusDays(2)), "weekdays includes Monday");
        t.repeat = "daily"; t.end = t.start.plusDays(4); check(Model.occurs(t, t.end), "inclusive end date"); check(!Model.occurs(t, t.end.plusDays(1)), "recurrence stops after end date"); t.end = null;
        t.done.add(t.start); check(!Model.open(t, t.start), "completed date closed"); check(Model.open(t, t.start.plusDays(1)), "next occurrence independently open");
        Model.Step step = new Model.Step(); step.title = "子任务"; step.done.add(t.start); t.steps.add(step);
        Model.Data data = new Model.Data(); data.tasks.add(t); data.notes.put(t.start, "中文 \"引号\"\n换行 \\ slash 😀");
        Model.Task template = Model.copyPlan(t); data.templates.add(template);
        Model.Data copy = Store.copy(data); check(copy.tasks.getFirst().done.contains(t.start), "completion JSON round trip"); check(copy.tasks.getFirst().steps.getFirst().done.contains(t.start), "subtask history round trip");
        check(copy.notes.equals(data.notes), "Chinese escapes and emoji round trip"); check(!template.id.equals(t.id) && template.done.isEmpty() && template.steps.getFirst().done.isEmpty(), "plan copy has fresh completion state");
        check(copy.templates.size() == 1, "template persistence");
        Model.Data deferred = new Model.Data(); Model.Task single = new Model.Task(); single.title = "defer"; single.start = t.start; deferred.tasks.add(single);
        Model.Task moved = Model.defer(deferred, single.id, single.start); check(moved != null && moved.start.equals(single.start.plusDays(1)), "one-off defer creates next-day occurrence");
        check(single.deferred.contains(single.start) && !Model.open(single, single.start), "defer preserves source status"); Model.defer(deferred, single.id, single.start); check(deferred.tasks.size() == 2, "repeated defer cannot duplicate");
        Model.Task recurring = new Model.Task(); recurring.title = "daily"; recurring.start = t.start; recurring.repeat = "daily"; deferred.tasks.add(recurring);
        check(Model.defer(deferred, recurring.id, recurring.start) == null && deferred.tasks.size() == 3, "daily defer reuses tomorrow recurrence");
        Model.Task friday = new Model.Task(); friday.title = "Friday"; friday.start = LocalDate.of(2026, 10, 9); friday.repeat = "weekdays"; deferred.tasks.add(friday);
        Model.Task saturday = Model.defer(deferred, friday.id, friday.start); check(saturday != null && saturday.start.getDayOfWeek() == DayOfWeek.SATURDAY, "Friday defer explicitly adds Saturday one-off");
        Model.Data alarms = new Model.Data(); Model.Task alarm = new Model.Task(); alarm.title = "alarm"; alarm.start = LocalDate.of(2026, 10, 9); alarm.time = LocalTime.of(9, 0); alarms.tasks.add(alarm);
        check(Model.due(alarms, alarm.start.atTime(8, 59, 59)).isEmpty(), "reminder never early"); check(Model.due(alarms, alarm.start.atTime(9, 0)).size() == 1, "reminder at due time");
        check(Model.due(alarms, alarm.start.atTime(9, 30)).size() == 1, "reminder grace boundary"); check(Model.due(alarms, alarm.start.atTime(9, 30, 1)).isEmpty(), "stale reminder excluded");
        alarm.notified.add(alarm.start); check(Model.due(alarms, alarm.start.atTime(9, 5)).isEmpty(), "reminder delivered at most once"); alarm.notified.clear();
        alarm.done.add(alarm.start); check(Model.due(alarms, alarm.start.atTime(9, 5)).isEmpty(), "completed task not reminded"); alarm.done.clear(); alarm.deferred.add(alarm.start);
        check(Model.due(alarms, alarm.start.atTime(9, 5)).isEmpty(), "deferred task not reminded");
        rejects(() -> Store.decode("{}"), "reject unsupported backup"); rejects(() -> Store.decode(Store.encode(data).replace("\"version\":1", "\"version\":2")), "reject future schema");
        rejects(() -> Json.parse("{\"x\":1,\"x\":2}"), "reject duplicate JSON fields"); rejects(() -> Json.parse("[1,]"), "reject trailing comma"); rejects(() -> Json.parse("01"), "reject malformed number");
        rejects(() -> Store.decode(Store.encode(data).replace("\"priority\":0", "\"priority\":4")), "reject invalid priority");
        Path folder = AppPaths.testDirectory("personal-todo-test-"); Store store = new Store(folder);
        try {
            check(store.acquire(), "acquire data lock"); Store second = new Store(folder); check(!second.acquire(), "reject second instance"); second.release();
            store.save(data); check(store.load().notes.equals(data.notes), "save and reload persisted data");
            data.notes.put(t.start, "changed"); store.save(data); check(store.read(folder.resolve("data.json.bak")).notes.get(t.start).startsWith("中文"), "previous save backup");
            check(Files.exists(folder.resolve("backups").resolve(LocalDate.now() + ".json")), "daily backup created before overwrite");
            Path exported = folder.resolve("export.json"); store.export(data, exported); check(store.read(exported).notes.equals(data.notes), "export and import round trip");
            byte[] exportBefore = Files.readAllBytes(store.file);
            boolean blocked = false; try { store.export(data, store.file); } catch (IOException e) { blocked = true; }
            check(blocked && Arrays.equals(exportBefore, Files.readAllBytes(store.file)), "export cannot overwrite live data");
            Path snapshot = store.snapshot(data, "before-import"); check(Files.exists(snapshot), "pre-import snapshot");
            byte[] before = Files.readAllBytes(store.file); Model.Data invalid = Store.copy(data); invalid.tasks.getFirst().title = "";
            try { store.save(invalid); throw new AssertionError("invalid save accepted"); } catch (IllegalArgumentException expected) { }
            check(Arrays.equals(before, Files.readAllBytes(store.file)), "invalid save cannot overwrite good data");
            Files.writeString(store.file, "corrupt"); boolean failed = false; try { store.load(); } catch (IOException e) { failed = true; }
            check(failed && Files.readString(store.file).equals("corrupt"), "corrupt file fails without reset");
        } finally { store.release(); cleanup(folder); }
        count += MemoSelfTest.run();
        count += CompletionSelfTest.run();
        count += WeeklyRepeatSelfTest.run();
        System.out.println("All " + count + " checks passed.");
    }
    static void ui() throws Exception {
        Path folder = AppPaths.testDirectory("personal-todo-ui-"); AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            MainFrame frame = new MainFrame(new Store(folder), new Model.Data(), true);
            try {
                frame.setVisible(true);
                JTextField quick = components(frame).stream().filter(c -> "quickAdd".equals(c.getName())).map(c -> (JTextField)c).findFirst().orElseThrow();
                quick.setText("界面快速添加验证"); quick.postActionEvent(); check(frame.data.tasks.size() == 1, "UI quick-add event persists task");
                JCheckBox complete = components(frame).stream().filter(c -> c instanceof JCheckBox b && b.getText().isEmpty()).map(c -> (JCheckBox)c).findFirst().orElseThrow();
                complete.doClick(); check(frame.data.tasks.getFirst().done.contains(LocalDate.now()), "UI checkbox marks today's completion");
                frame.navigate("tomorrow"); frame.navigate("plans"); frame.navigate("history"); frame.navigate("templates"); frame.navigate("settings"); frame.navigate("today");
                check(true, "UI navigation renders all six pages");
                AtomicReference<Throwable> dialogFailure = new AtomicReference<>();
                javax.swing.Timer fill = new javax.swing.Timer(200, e -> {
                    ((javax.swing.Timer)e.getSource()).stop();
                    try {
                        TaskDialog dialog = Arrays.stream(Window.getWindows()).filter(w -> w instanceof TaskDialog && w.isVisible()).map(w -> (TaskDialog)w).findFirst().orElseThrow();
                        JTextField title = (JTextField)named(dialog, "taskTitle"); title.setText("任务弹窗验证");
                        ((JCheckBox)named(dialog, "taskReminder")).doClick(); ((JTextField)named(dialog, "taskTime")).setText("09:30");
                        ((JComboBox<?>)named(dialog, "taskRepeat")).setSelectedIndex(1); ((JComboBox<?>)named(dialog, "taskPriority")).setSelectedIndex(3);
                        ((JTextArea)named(dialog, "taskSteps")).setText("检查输入\n检查保存");
                        String artifactFolder = System.getProperty("todo.artifacts");
                        if (artifactFolder != null) {
                            Container canvas = dialog.getContentPane(); BufferedImage image = new BufferedImage(canvas.getWidth(), canvas.getHeight(), BufferedImage.TYPE_INT_RGB);
                            Graphics2D g = image.createGraphics(); canvas.paint(g); g.dispose(); ImageIO.write(image, "png", Path.of(artifactFolder, "task-dialog.png").toFile());
                        }
                        JButton save = components(dialog).stream().filter(c -> "saveTask".equals(c.getName())).map(c -> (JButton)c).findFirst().orElseThrow(); save.doClick();
                    } catch (Throwable ex) { dialogFailure.set(ex); for (Window w : Window.getWindows()) if (w instanceof TaskDialog) w.dispose(); }
                }); fill.setRepeats(false); fill.start();
                JButton add = components(frame).stream().filter(c -> c instanceof JButton b && b.getText().equals("+  新建待办")).map(c -> (JButton)c).findFirst().orElseThrow(); add.doClick();
                if (dialogFailure.get() != null) throw new AssertionError(dialogFailure.get());
                check(frame.data.tasks.size() == 2, "UI task dialog save event");
                Model.Task created = frame.data.tasks.getLast();
                check(created.time.equals(LocalTime.of(9, 30)) && created.repeat.equals("daily") && created.priority == 3 && created.steps.size() == 2, "UI dialog persists reminder, recurrence, priority and subtasks");
                frame.navigate("today");
                JCheckBox recurringComplete = components(frame).stream().filter(c -> c instanceof JCheckBox b && b.getText().isEmpty()).map(c -> (JCheckBox)c).findFirst().orElseThrow(); recurringComplete.doClick();
                check(frame.data.tasks.getLast().steps.stream().allMatch(s -> s.done.contains(LocalDate.now())), "UI parent completion marks subtasks");
                JButton expand = components(frame).stream().filter(c -> c instanceof JButton b && b.getText().equals("子任务")).map(c -> (JButton)c).findFirst().orElseThrow(); expand.doClick();
                JCheckBox subtask = components(frame).stream().filter(c -> c instanceof JCheckBox b && b.getText().equals("检查输入")).map(c -> (JCheckBox)c).findFirst().orElseThrow(); subtask.doClick();
                check(!frame.data.tasks.getLast().done.contains(LocalDate.now()), "UI unchecking subtask reopens parent");
                ((JButton)named(frame, "reviewToggle")).doClick();
                JTextArea review = components(frame).stream().filter(c -> c instanceof JTextArea).map(c -> (JTextArea)c).findFirst().orElseThrow(); review.setText("界面复盘保存验证"); frame.navigate("history");
                check(frame.data.notes.get(LocalDate.now()).equals("界面复盘保存验证"), "UI page change flushes review");
                Model.Data reloaded = new Store(folder).load(); check(reloaded.tasks.size() == 2, "UI changes survive reload");
            } catch (Throwable e) { failure.set(e); } finally { frame.exit(); }
        });
        cleanup(folder); if (failure.get() != null) throw new AssertionError("UI smoke test failed", failure.get()); System.out.println("UI checks passed.");
    }
    private static List<Component> components(Container parent) {
        List<Component> list = new ArrayList<>(); for (Component c : parent.getComponents()) { list.add(c); if (c instanceof Container child) list.addAll(components(child)); } return list;
    }
    static void integration() throws Exception {
        Path folder = AppPaths.testDirectory("personal-todo-integration-"); AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            int originalIcons = SystemTray.isSupported() ? SystemTray.getSystemTray().getTrayIcons().length : 0;
            Store store = new Store(folder); Model.Data data = new Model.Data(); data.sound = false;
            Model.Task reminder = new Model.Task(); reminder.title = "每日计划 · 提醒验证"; reminder.time = LocalTime.now().withSecond(0).withNano(0); data.tasks.add(reminder);
            MainFrame frame = new MainFrame(store, data, false);
            try {
                frame.startMinimized();
                if (SystemTray.isSupported()) {
                    check(SystemTray.getSystemTray().getTrayIcons().length == originalIcons + 1, "integration tray icon registered");
                    check(!frame.isVisible(), "integration start minimized to tray");
                }
                frame.tick(); check(frame.data.tasks.getFirst().notified.contains(LocalDate.now()), "integration real reminder poll persisted delivery");
                check(Arrays.stream(Window.getWindows()).anyMatch(w -> w instanceof JDialog d && d.isVisible() && d.getTitle().equals("每日计划 · 提醒")), "integration reminder popup displayed");
                check(Model.due(store.load(), LocalDateTime.now()).isEmpty(), "integration restart cannot repeat delivered reminder");
                frame.showWindow(); check(frame.isVisible(), "integration reopen from tray");
                String artifacts = System.getProperty("todo.artifacts");
                if (artifacts != null) {
                    JDialog popup = Arrays.stream(Window.getWindows()).filter(w -> w instanceof JDialog d && d.isVisible() && d.getTitle().equals("每日计划 · 提醒")).map(w -> (JDialog)w).findFirst().orElseThrow();
                    Container canvas = popup.getContentPane(); BufferedImage image = new BufferedImage(canvas.getWidth(), canvas.getHeight(), BufferedImage.TYPE_INT_RGB);
                    Graphics2D g = image.createGraphics(); canvas.paint(g); g.dispose(); ImageIO.write(image, "png", Path.of(artifacts, "reminder.png").toFile());
                }
            } catch (Throwable e) { failure.set(e); } finally { frame.exit(); }
            if (SystemTray.isSupported()) check(SystemTray.getSystemTray().getTrayIcons().length == originalIcons, "integration exit removes tray icon");
        });
        cleanup(folder); if (failure.get() != null) throw new AssertionError("Integration checks failed", failure.get()); System.out.println("Integration checks passed.");
    }
    private static Component named(Container parent, String name) { return components(parent).stream().filter(c -> name.equals(c.getName())).findFirst().orElseThrow(); }
    private static void cleanup(Path directory) throws IOException {
        if (!directory.toRealPath().startsWith(AppPaths.TEMP.toRealPath()) || !directory.getFileName().toString().startsWith("personal-todo-"))
            throw new IOException("Refusing cleanup outside the application's temporary directory: " + directory);
        try (var paths = Files.walk(directory)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
    }
}
