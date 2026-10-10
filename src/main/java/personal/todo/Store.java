package personal.todo;

import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.*;

final class Store {
    final Path directory, file;
    private FileChannel lockChannel;
    private FileLock lock;
    private long lastBackupPrune;
    Store(Path directory) { this.directory = AppPaths.storagePath(directory); file = this.directory.resolve("data.json"); }
    boolean acquire() throws IOException {
        Files.createDirectories(directory);
        lockChannel = FileChannel.open(directory.resolve("app.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try { lock = lockChannel.tryLock(); } catch (OverlappingFileLockException e) { return false; }
        return lock != null;
    }
    void release() { try { if (lock != null) lock.release(); if (lockChannel != null) lockChannel.close(); } catch (IOException ignored) { } }
    Model.Data load() throws IOException { return Files.exists(file) ? read(file) : new Model.Data(); }
    Model.Data read(Path path) throws IOException {
        if (Files.size(path) > 16 * 1024 * 1024) throw new IOException("备份超过 16 MB，暂不支持导入。");
        try { return decode(Files.readString(path, StandardCharsets.UTF_8)); }
        catch (RuntimeException e) { throw new IOException("无法读取数据：" + e.getMessage(), e); }
    }
    void save(Model.Data data) throws IOException {
        String json = encode(data); decode(json); // Validate before touching the current file.
        if (json.getBytes(StandardCharsets.UTF_8).length > 16 * 1024 * 1024) throw new IOException("数据超过 16 MB，请先导出并整理笔记。");
        Files.createDirectories(directory);
        if (Files.exists(file)) {
            Path backups = directory.resolve("backups"); Files.createDirectories(backups);
            Path daily = backups.resolve(LocalDate.now() + ".json");
            if (!Files.exists(daily)) Files.copy(file, daily);
            atomicWrite(directory.resolve("data.json.bak"), Files.readString(file, StandardCharsets.UTF_8));
        }
        atomicWrite(file, json);
        // Retention is best effort; a cleanup error must not turn a committed save into a failure.
        if (System.currentTimeMillis() - lastBackupPrune > 3600000) {
            lastBackupPrune = System.currentTimeMillis();
            Path backups = directory.resolve("backups");
            if (Files.isDirectory(backups)) try (Stream<Path> paths = Files.list(backups)) {
                for (Path old : paths.filter(p -> p.getFileName().toString().matches("\\d{4}-\\d{2}-\\d{2}\\.json"))
                        .sorted(Comparator.reverseOrder()).skip(30).toList()) {
                    try { Files.deleteIfExists(old); } catch (IOException ignored) { }
                }
            } catch (IOException ignored) { }
        }
    }
    static void atomicWrite(Path destination, String text) throws IOException {
        destination = AppPaths.storagePath(destination);
        Path temp = destination.resolveSibling(destination.getFileName() + ".tmp");
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
        }
        try { Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException e) { Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING); }
    }
    Path snapshot(Model.Data data, String prefix) throws IOException {
        Path folder = directory.resolve("backups"); Files.createDirectories(folder);
        Path path = folder.resolve(prefix + "-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")) + ".json");
        atomicWrite(path, encode(data)); return path;
    }
    void export(Model.Data data, Path path) throws IOException {
        try { path = AppPaths.storagePath(path); } catch (IllegalArgumentException e) { throw new IOException(e.getMessage(), e); }
        if (path.toAbsolutePath().normalize().equals(file.toAbsolutePath().normalize())) throw new IOException("请选择数据目录之外的位置导出。");
        atomicWrite(path, encode(data));
    }
    static Model.Data copy(Model.Data data) { return decode(encode(data)); }
    static String encode(Model.Data data) {
        Map<String, Object> root = new LinkedHashMap<>(); root.put("version", 1);
        root.put("settings", Map.of("closeToTray", data.closeToTray, "sound", data.sound));
        root.put("tasks", data.tasks.stream().map(Store::taskMap).toList());
        root.put("templates", data.templates.stream().map(Store::taskMap).toList());
        root.put("memos", data.memos.stream().map(Store::memoMap).toList());
        Map<String, Object> notes = new TreeMap<>(); data.notes.forEach((d, text) -> notes.put(d.toString(), text)); root.put("notes", notes);
        return Json.write(root);
    }
    static Map<String, Object> memoMap(Model.Memo memo) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", memo.id); m.put("title", memo.title); m.put("body", memo.body); m.put("pinned", memo.pinned);
        m.put("createdAt", memo.createdAt); m.put("updatedAt", memo.updatedAt); m.put("deletedAt", memo.deletedAt); m.put("version", memo.version);
        return m;
    }
    private static Map<String, Object> taskMap(Model.Task t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.id); m.put("title", t.title); m.put("notes", t.notes); m.put("category", t.category);
        m.put("repeat", t.repeat); m.put("start", t.start.toString()); m.put("end", t.end == null ? null : t.end.toString());
        m.put("time", t.time == null ? null : t.time.toString()); m.put("priority", t.priority);
        m.put("done", strings(t.done)); m.put("deferred", strings(t.deferred)); m.put("notified", strings(t.notified));
        Map<String, Object> reports = new TreeMap<>(); t.completionReports.forEach((date, text) -> reports.put(date.toString(), text)); m.put("completionReports", reports);
        m.put("steps", t.steps.stream().map(s -> Map.of("title", s.title, "done", strings(s.done))).toList()); return m;
    }
    private static List<String> strings(Set<LocalDate> dates) { return dates.stream().map(Object::toString).sorted().toList(); }
    static Model.Data decode(String json) {
        Map<String, Object> root = object(Json.parse(json));
        if (!(root.get("version") instanceof Number n) || n.doubleValue() != 1) throw new IllegalArgumentException("不支持的数据版本");
        Model.Data data = new Model.Data(); Map<String, Object> settings = object(root.get("settings"));
        data.closeToTray = bool(settings.get("closeToTray")); data.sound = bool(settings.get("sound"));
        Set<String> ids = new HashSet<>();
        for (Object item : array(root.get("tasks"))) data.tasks.add(task(object(item), ids));
        for (Object item : array(root.get("templates"))) data.templates.add(task(object(item), ids));
        object(root.get("notes")).forEach((date, text) -> data.notes.put(LocalDate.parse(date), string(text, 10000)));
        Set<String> memoIds = new HashSet<>();
        if (root.containsKey("memos")) for (Object item : array(root.get("memos"))) {
            Map<String, Object> m = object(item); Model.Memo memo = new Model.Memo();
            memo.id = string(m.get("id"), 36); UUID.fromString(memo.id);
            if (!memoIds.add(memo.id)) throw new IllegalArgumentException("备忘录 ID 重复");
            memo.title = string(m.get("title"), 200); memo.body = string(m.get("body"), 200000); memo.pinned = bool(m.get("pinned"));
            memo.createdAt = string(m.get("createdAt"), 40); memo.updatedAt = string(m.get("updatedAt"), 40);
            Instant created = Instant.parse(memo.createdAt), updated = Instant.parse(memo.updatedAt);
            if (updated.isBefore(created)) throw new IllegalArgumentException("备忘录时间无效");
            if (m.get("deletedAt") != null) { memo.deletedAt = string(m.get("deletedAt"), 40); Instant.parse(memo.deletedAt); }
            if (!(m.get("version") instanceof Number v) || v.doubleValue() != v.longValue() || v.longValue() < 1 || v.longValue() >= 9007199254740991L)
                throw new IllegalArgumentException("备忘录版本无效");
            memo.version = v.longValue(); data.memos.add(memo);
        }
        return data;
    }
    private static Model.Task task(Map<String, Object> m, Set<String> ids) {
        Model.Task t = new Model.Task();
        t.id = string(m.get("id"), 100); if (t.id.isBlank() || !ids.add(t.id)) throw new IllegalArgumentException("任务 ID 无效或重复");
        t.title = string(m.get("title"), 160); if (t.title.isBlank()) throw new IllegalArgumentException("任务标题为空");
        t.notes = string(m.get("notes"), 10000); t.category = string(m.get("category"), 40);
        t.repeat = string(m.get("repeat"), 20); if (!Arrays.asList(Model.REPEATS).contains(t.repeat)) throw new IllegalArgumentException("重复规则无效");
        t.start = LocalDate.parse(string(m.get("start"), 10));
        if (m.get("end") != null) { t.end = LocalDate.parse(string(m.get("end"), 10)); if (t.end.isBefore(t.start)) throw new IllegalArgumentException("结束日期早于开始日期"); }
        if (m.get("time") != null) { t.time = LocalTime.parse(string(m.get("time"), 5)); if (t.time.getSecond() != 0) throw new IllegalArgumentException("提醒时间无效"); }
        if (!(m.get("priority") instanceof Number p) || p.doubleValue() != p.intValue() || p.intValue() < 0 || p.intValue() > 3) throw new IllegalArgumentException("优先级无效");
        t.priority = p.intValue(); t.done = dates(m.get("done")); t.deferred = dates(m.get("deferred")); t.notified = dates(m.get("notified"));
        if (m.containsKey("completionReports")) object(m.get("completionReports")).forEach((date, text) -> t.completionReports.put(LocalDate.parse(date), string(text, 10000)));
        for (Object item : array(m.get("steps"))) {
            Map<String, Object> step = object(item); Model.Step s = new Model.Step(); s.title = string(step.get("title"), 200);
            if (s.title.isBlank()) throw new IllegalArgumentException("子任务标题为空"); s.done = dates(step.get("done")); t.steps.add(s);
        }
        if (t.steps.size() > 50) throw new IllegalArgumentException("单个任务最多 50 个子任务");
        return t;
    }
    private static Set<LocalDate> dates(Object value) {
        Set<LocalDate> dates = new LinkedHashSet<>(); for (Object item : array(value)) dates.add(LocalDate.parse(string(item, 10))); return dates;
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?>)) throw new IllegalArgumentException("缺少对象字段"); return (Map<String, Object>) value;
    }
    @SuppressWarnings("unchecked") private static List<Object> array(Object value) {
        if (!(value instanceof List<?>)) throw new IllegalArgumentException("缺少列表字段"); return (List<Object>) value;
    }
    private static String string(Object value, int limit) {
        if (!(value instanceof String s) || s.length() > limit) throw new IllegalArgumentException("文本字段无效或过长"); return s;
    }
    private static boolean bool(Object value) {
        if (!(value instanceof Boolean b)) throw new IllegalArgumentException("设置字段无效"); return b;
    }
}
