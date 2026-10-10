package personal.todo;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.*;
import java.util.*;

public final class App {
    public static void main(String[] args) throws Exception {
        try { AppPaths.prepareRuntime(); }
        catch (Exception e) { JOptionPane.showMessageDialog(null, "无法准备应用工作目录。\n" + e.getMessage(), "个人每日计划", JOptionPane.ERROR_MESSAGE); return; }
        var arguments = Arrays.asList(args);
        if (arguments.contains("--self-test")) { SelfTest.run(); return; }
        Theme.install();
        if (arguments.contains("--ui-test")) { SelfTest.ui(); return; }
        if (arguments.contains("--integration-test")) { SelfTest.integration(); return; }
        if (arguments.contains("--web-integration-test")) { WebIntegration.run(); return; }
        if (arguments.contains("--preview")) {
            int index = arguments.indexOf("--preview");
            Path target = Path.of(args[index + 1]).toAbsolutePath();
            SwingUtilities.invokeAndWait(() -> preview(target)); return;
        }
        Path folder = AppPaths.DATA;
        int directoryArg = arguments.indexOf("--data-dir");
        if (directoryArg >= 0) {
            try { folder = AppPaths.storagePath(Path.of(args[directoryArg + 1])); }
            catch (Exception e) { JOptionPane.showMessageDialog(null, e.getMessage(), "存储路径无效", JOptionPane.ERROR_MESSAGE); return; }
        }
        Store store = new Store(folder);
        Path infoFile = AppPaths.ROOT.resolve("runtime/server.json");
        int infoArg = arguments.indexOf("--server-info");
        if (infoArg >= 0) infoFile = AppPaths.storagePath(Path.of(args[infoArg + 1]));
        try {
            if (!store.acquire()) {
                store.release();
                try {
                    var info = TodoService.object(Json.parse(Files.readString(infoFile)));
                    if (folder.toString().equals(info.get("data")) && ProcessHandle.of(((Number)info.get("pid")).longValue()).map(ProcessHandle::isAlive).orElse(false)) {
                        BrowserLauncher.open((String)info.get("url")); return;
                    }
                } catch (Exception ignored) { }
                JOptionPane.showMessageDialog(null, "个人每日计划已经在运行。\n请双击系统托盘中的勾选图标打开。", "个人每日计划", JOptionPane.INFORMATION_MESSAGE); return;
            }
        } catch (IOException e) {
            store.release(); JOptionPane.showMessageDialog(null, "无法打开数据目录，请检查权限。\n" + e.getMessage(), "个人每日计划", JOptionPane.ERROR_MESSAGE); return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(store::release, "release-data-lock"));
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                StringWriter output = new StringWriter(); error.printStackTrace(new PrintWriter(output));
                Files.writeString(AppPaths.LOGS.resolve("error.log"), LocalDateTime.now() + "\n" + output, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ignored) { }
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(null, "发生错误，详情已记录到：\n" + AppPaths.LOGS.resolve("error.log") + "\n" + error.getMessage(), "个人每日计划", JOptionPane.ERROR_MESSAGE));
        });
        Model.Data data;
        try { data = store.load(); }
        catch (IOException e) {
            if (arguments.contains("--no-browser")) { store.release(); throw e; }
            data = recover(store, e); if (data == null) { store.release(); return; }
        }
        if (arguments.contains("--swing")) {
            Model.Data loaded = data;
            SwingUtilities.invokeLater(() -> { MainFrame frame = new MainFrame(store, loaded, false); if (arguments.contains("--minimized")) frame.startMinimized(); else frame.showWindow(); });
            return;
        }
        WebServer[] running = new WebServer[1];
        Runnable stop = () -> { if (running[0] != null) running[0].close(); if (!arguments.contains("--no-browser")) BrowserLauncher.closeOwnedWindow(); store.release(); System.exit(0); };
        WebServer web = new WebServer(store, data, infoFile, arguments.contains("--no-browser"), stop); running[0] = web;
        Runtime.getRuntime().addShutdownHook(new Thread(web::close, "close-local-server"));
        web.start();
        if (!arguments.contains("--minimized") && !arguments.contains("--no-browser")) web.openWindow();
    }
    private static Model.Data recover(Store store, IOException failure) {
        Object[] options = {"恢复上次保存", "选择其他备份", "退出"};
        int answer = JOptionPane.showOptionDialog(null, "当前数据无法读取，原文件会保留。\n" + failure.getMessage() + "\n请选择备份恢复，或退出后检查文件。", "数据恢复", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[2]);
        if (answer < 0 || answer == 2) return null;
        Path source = store.directory.resolve("data.json.bak");
        if (answer == 1) {
            JFileChooser chooser = new JFileChooser(store.directory.resolve("backups").toFile());
            if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return null; source = chooser.getSelectedFile().toPath();
        }
        try {
            Model.Data restored = store.read(source); store.snapshot(restored, "recovered");
            if (Files.exists(store.file)) Files.copy(store.file, store.directory.resolve("data-corrupt-" + System.currentTimeMillis() + ".json"));
            store.save(restored); return restored;
        } catch (IOException e) { JOptionPane.showMessageDialog(null, "恢复失败，程序将退出，原数据不会清空。\n" + e.getMessage(), "数据恢复", JOptionPane.ERROR_MESSAGE); return null; }
    }
    static Model.Data demo() {
        Model.Data data = new Model.Data();
        Model.Task work = new Model.Task(); work.title = "完成今天最重要的一项工作"; work.category = "工作"; work.priority = 3; work.repeat = "weekdays"; work.time = LocalTime.of(9, 30);
        work.notes = "先列清目标，再留出一段不被打扰的时间。"; Model.Step s = new Model.Step(); s.title = "梳理要交付的结果"; s.done.add(LocalDate.now()); work.steps.add(s);
        Model.Step s2 = new Model.Step(); s2.title = "完成初稿并检查"; work.steps.add(s2); data.tasks.add(work);
        Model.Task read = new Model.Task(); read.title = "阅读 20 分钟，记下一点收获"; read.category = "学习"; read.priority = 2; read.repeat = "daily"; read.time = LocalTime.of(12, 30); data.tasks.add(read);
        Model.Task walk = new Model.Task(); walk.title = "傍晚散步，让身体动起来"; walk.category = "健康"; walk.repeat = "daily"; walk.time = LocalTime.of(18, 30); data.tasks.add(walk);
        Model.Task morning = new Model.Task(); morning.title = "整理桌面，写下今日目标"; morning.category = "生活"; morning.done.add(LocalDate.now()); data.tasks.add(morning);
        Model.Task template = Model.copyPlan(read); template.repeat = "once"; data.templates.add(template);
        data.notes.put(LocalDate.now(), "不急着做完所有事，先把重要的一件做好。"); return data;
    }
    private static void preview(Path target) {
        MainFrame frame = new MainFrame(new Store(target.resolveSibling("preview-data")), demo(), true);
        try {
            Files.createDirectories(target.getParent()); frame.setVisible(true); frame.validate();
            Container canvas = frame.getContentPane();
            BufferedImage image = new BufferedImage(canvas.getWidth(), canvas.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics(); canvas.paint(graphics); graphics.dispose(); ImageIO.write(image, "png", target.toFile());
            ImageIO.write(Theme.icon(256), "png", target.resolveSibling("icon.png").toFile());
            byte[] png = Files.readAllBytes(target.resolveSibling("icon.png"));
            ByteBuffer ico = ByteBuffer.allocate(22 + png.length).order(ByteOrder.LITTLE_ENDIAN);
            ico.putShort((short)0).putShort((short)1).putShort((short)1);
            ico.put((byte)0).put((byte)0).put((byte)0).put((byte)0).putShort((short)1).putShort((short)32).putInt(png.length).putInt(22).put(png);
            Files.write(target.resolveSibling("icon.ico"), ico.array());
        } catch (IOException e) { throw new UncheckedIOException(e); } finally { frame.exit(); }
    }
}
