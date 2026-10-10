package personal.todo;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.nio.file.*;

/** Keep application files on the volume chosen for the application home. */
final class AppPaths {
    static final Path ROOT = resolveHome();
    static final Path DATA = storagePath(Path.of(setting("personal.todo.data", "PERSONAL_TODO_DATA", ROOT.resolve("data").toString())));
    static final Path TEMP = ROOT.resolve("runtime/tmp");
    static final Path LOGS = ROOT.resolve("runtime/logs");

    private static String setting(String property, String environment, String fallback) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) value = System.getenv(environment);
        return value == null || value.isBlank() ? fallback : value;
    }
    private static Path resolveHome() {
        Path location;
        try { location = Path.of(AppPaths.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().normalize(); }
        catch (Exception e) { throw new IllegalStateException("无法识别程序目录，请设置 PERSONAL_TODO_HOME。", e); }
        Path detected = homeFrom(location);
        Path selected = Path.of(setting("personal.todo.home", "PERSONAL_TODO_HOME", detected.toString())).toAbsolutePath().normalize();
        return onVolume(selected, selected.getRoot());
    }
    static Path homeFrom(Path location) {
        Path parent = location.getParent();
        if (location.getFileName().toString().equals("classes") && parent != null && parent.getFileName().toString().equals("build")) return parent.getParent();
        if (location.getFileName().toString().endsWith(".jar")) {
            if (parent.getFileName().toString().equals("app")) return parent.getParent();
            if (parent.getFileName().toString().equals("input") && parent.getParent().getFileName().toString().equals("build")) return parent.getParent().getParent();
            return parent;
        }
        return location;
    }
    static Path storagePath(Path input) { return onVolume(input, ROOT.getRoot()); }
    static Path onVolume(Path input, Path volume) {
        Path path = input.toAbsolutePath().normalize();
        if (!sameVolume(path.getRoot(), volume)) throw new IllegalArgumentException("请选择应用工作目录所在磁盘中的路径：" + path);
        // A directory junction must not silently redirect writes to another volume.
        Path ancestor = path;
        while (ancestor != null && !Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) ancestor = ancestor.getParent();
        if (ancestor != null) {
            try { if (!sameVolume(ancestor.toRealPath().getRoot(), volume)) throw new IllegalArgumentException("该路径链接到了应用工作磁盘之外：" + path); }
            catch (IOException e) { throw new IllegalArgumentException("无法确认存储路径：" + path, e); }
        }
        return path;
    }
    private static boolean sameVolume(Path left, Path right) { return left != null && right != null && left.toString().equalsIgnoreCase(right.toString()); }
    static void prepareRuntime() throws IOException {
        Files.createDirectories(TEMP); Files.createDirectories(LOGS);
        System.setProperty("java.io.tmpdir", TEMP.toString());
        ImageIO.setUseCache(false);
    }
    static Path testDirectory(String prefix) throws IOException {
        Files.createDirectories(TEMP); return Files.createTempDirectory(TEMP, prefix);
    }
}
