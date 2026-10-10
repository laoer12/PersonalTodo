package personal.todo;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Shared transactional service: both the Vue UI and reminder worker use these rules. */
final class TodoService {
    final Store store;
    private Model.Data data;
    private long revision = System.currentTimeMillis();

    TodoService(Store store, Model.Data data) { this.store = store; this.data = data; }
    synchronized Model.Data copy() { return Store.copy(data); }
    synchronized Map<String, Object> state(LocalDate date) {
        return state(date, null, null);
    }
    synchronized Map<String, Object> state(LocalDate date, LocalDate from, LocalDate to) {
        if ((from == null) != (to == null)) throw new IllegalArgumentException("请同时选择周期的开始和结束日期。");
        if (from != null && (to.isBefore(from) || java.time.temporal.ChronoUnit.DAYS.between(from, to) >= 366))
            throw new IllegalArgumentException("结束日期不能早于开始日期，一个周期最多显示 366 天。");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("data", Json.parse(Store.encode(data))); result.put("revision", revision);
        result.put("today", LocalDate.now().toString()); result.put("date", date.toString());
        result.put("dayTasks", Model.forDay(data, date).stream().map(t -> t.id).toList());
        // Recorded occurrences remain visible in history after a recurrence/start-date edit.
        result.put("historyDayTasks", data.tasks.stream().filter(t -> Model.occurs(t, date) || t.done.contains(date) || t.deferred.contains(date) || t.completionReports.containsKey(date)).map(t -> t.id).toList());
        if (from != null) {
            List<Object> days = new ArrayList<>();
            for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1))
                days.add(Map.of("date", day.toString(), "taskIds", Model.forDay(data, day).stream().map(t -> t.id).toList()));
            result.put("range", Map.of("from", from.toString(), "to", to.toString(), "days", days));
        }
        result.put("paths", Map.of("root", AppPaths.ROOT.toString(), "data", store.directory.toString(),
            "backups", store.directory.resolve("backups").toString(), "logs", AppPaths.LOGS.toString(), "temp", AppPaths.TEMP.toString()));
        return result;
    }
    synchronized Map<String, Object> action(Map<String, Object> request) throws IOException {
        if (!(request.get("revision") instanceof Number n) || n.longValue() != revision) throw new Conflict("数据已在其他窗口更新，请刷新后重试。你的输入仍保留在页面中。");
        Model.Data next = Store.copy(data); String type = text(request, "type", 60); String message = "已保存";
        String snapshot = null;
        switch (type) {
            case "task.save" -> {
                boolean template = flag(request, "template", false); List<Model.Task> list = template ? next.templates : next.tasks;
                Map<String, Object> fields = object(request.get("task")); String id = optionalText(fields, "id", 100);
                Model.Task old = id.isEmpty() ? null : list.stream().filter(t -> t.id.equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("任务已不存在。"));
                if (old != null && request.containsKey("expected") && !samePlan(old, object(request.get("expected"))))
                    throw new Conflict("此计划已在其他窗口编辑。当前草稿已保留，请先复制草稿，再重新打开编辑窗口核对。");
                Model.Task task = old == null ? new Model.Task() : Store.copy(single(old)).tasks.getFirst();
                task.title = text(fields, "title", 160).strip(); task.notes = optionalText(fields, "notes", 10000);
                task.category = optionalText(fields, "category", 40).strip(); if (task.category.isEmpty()) task.category = "生活";
                task.start = LocalDate.parse(text(fields, "start", 10)); task.repeat = template ? "once" : text(fields, "repeat", 20);
                Object alarm = fields.get("time"); LocalTime time = alarm == null || alarm.equals("") ? null : LocalTime.parse(text(fields, "time", 5));
                if (old != null && !Objects.equals(time, old.time)) task.notified.remove(LocalDate.now()); task.time = time;
                Object priority = fields.get("priority"); if (!(priority instanceof Number p) || p.doubleValue() != p.intValue()) throw new IllegalArgumentException("优先级无效。"); task.priority = p.intValue();
                List<Model.Step> remaining = old == null ? new ArrayList<>() : new ArrayList<>(old.steps); task.steps = new ArrayList<>();
                for (Object title : array(fields.get("steps"))) {
                    if (!(title instanceof String value) || value.isBlank() || value.length() > 200) throw new IllegalArgumentException("子任务名称应为 1～200 字。");
                    Model.Step step = new Model.Step(); step.title = value.strip();
                    for (var it = remaining.iterator(); it.hasNext();) { Model.Step previous = it.next(); if (previous.title.equals(step.title)) { step.done.addAll(previous.done); it.remove(); break; } }
                    task.steps.add(step);
                }
                if (old == null) list.add(task); else { list.set(list.indexOf(old), task); snapshot = "before-edit"; }
            }
            case "task.complete" -> {
                Model.Task task = Model.find(next, text(request, "id", 100)); LocalDate date = date(request);
                requireOccurrence(task, date); boolean value = flag(request, "value", false);
                if (request.containsKey("expectedCompletion")) {
                    Map<String, Object> expected = object(request.get("expectedCompletion"));
                    if (task.done.contains(date) != flag(expected, "done", false) || !task.completionReports.getOrDefault(date, "").equals(text(expected, "report", 10000)))
                        throw new Conflict("这项任务当天的完成记录已在其他窗口更新。输入内容仍保留，请核对后重新打开完成弹窗。");
                }
                if (value && request.containsKey("completionReport")) {
                    String report = text(request, "completionReport", 10000).strip();
                    if (report.isEmpty()) task.completionReports.remove(date); else task.completionReports.put(date, report);
                }
                Model.mark(task.done, date, value); if (value) task.steps.forEach(s -> s.done.add(date));
            }
            case "task.report" -> {
                Model.Task task = Model.find(next, text(request, "id", 100)); LocalDate date = date(request);
                Map<String, Object> expected = object(request.get("expectedCompletion"));
                if (!task.done.contains(date) || !flag(expected, "done", false) || !task.completionReports.getOrDefault(date, "").equals(text(expected, "report", 10000)))
                    throw new Conflict("这项任务当天的完成记录已在其他窗口更新。输入内容仍保留，请核对后重新打开完成弹窗。");
                String report = text(request, "completionReport", 10000).strip();
                if (report.isEmpty()) task.completionReports.remove(date); else task.completionReports.put(date, report);
                // Editing prose must not toggle completion, steps, deferrals or reminder state.
            }
            case "task.step" -> {
                Model.Task task = Model.find(next, text(request, "id", 100)); LocalDate date = date(request); requireOccurrence(task, date);
                int index = ((Number)request.get("index")).intValue(); if (index < 0 || index >= task.steps.size()) throw new IllegalArgumentException("子任务已不存在。");
                boolean value = flag(request, "value", false); Model.mark(task.steps.get(index).done, date, value); if (!value) task.done.remove(date);
            }
            case "task.defer" -> {
                Model.Task task = Model.find(next, text(request, "id", 100)); LocalDate date = date(request); requireOccurrence(task, date);
                if (!Model.open(task, date)) throw new IllegalArgumentException("仅未完成的任务可以顺延。");
                Model.defer(next, task.id, date); message = "已顺延到 " + date.plusDays(1);
            }
            case "task.delete" -> {
                String id = text(request, "id", 100); List<Model.Task> list = flag(request, "template", false) ? next.templates : next.tasks;
                if (!list.removeIf(t -> t.id.equals(id))) throw new IllegalArgumentException("任务已不存在。"); snapshot = "before-delete"; message = "已删除，并保存操作前备份";
            }
            case "template.create" -> {
                Model.Task copy = Model.copyPlan(Model.find(next, text(request, "id", 100))); copy.start = LocalDate.now(); copy.end = null; copy.repeat = "once";
                next.templates.add(copy); message = "已保存为每日模板";
            }
            case "template.apply" -> {
                LocalDate date = date(request); int added = 0, skipped = 0;
                for (Object id : array(request.get("ids"))) {
                    Model.Task template = next.templates.stream().filter(t -> t.id.equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("模板已不存在。"));
                    if (next.tasks.stream().anyMatch(t -> Model.occurs(t, date) && t.title.equals(template.title) && t.category.equals(template.category))) { skipped++; continue; }
                    Model.Task copy = Model.copyPlan(template); copy.start = date; copy.end = null; copy.repeat = "once"; next.tasks.add(copy); added++;
                }
                message = "已添加 " + added + " 项" + (skipped > 0 ? "，跳过 " + skipped + " 项当天已有任务" : "");
            }
            case "note.save" -> {
                LocalDate date = date(request); String value = text(request, "text", 10000);
                if (value.isBlank()) next.notes.remove(date); else next.notes.put(date, value);
            }
            case "settings.save" -> { next.closeToTray = flag(request, "closeToTray", next.closeToTray); next.sound = flag(request, "sound", next.sound); }
            case "backup.import" -> {
                Model.Data imported = Store.decode(Json.write(request.get("data")));
                next.tasks = imported.tasks; next.templates = imported.templates; next.notes = imported.notes; next.closeToTray = imported.closeToTray; next.sound = imported.sound;
                if (object(request.get("data")).containsKey("memos")) {
                    for (Model.Memo memo : imported.memos) {
                        long previous = data.memos.stream().filter(m -> m.id.equals(memo.id)).mapToLong(m -> m.version).findFirst().orElse(0);
                        memo.version = Math.max(previous, memo.version) + 1;
                    }
                    next.memos = imported.memos;
                }
                snapshot = "before-import"; message = "已恢复备份，原数据已另存";
            }
            case "backup.export" -> {
                Path exports = store.directory.resolve("exports"); Files.createDirectories(exports);
                Path target = exports.resolve("每日计划-" + LocalDateTime.now().toString().replace(':', '-') + ".json"); store.export(data, target);
                return Map.of("revision", revision, "message", "备份已保存", "path", target.toString());
            }
            default -> throw new IllegalArgumentException("未知操作。");
        }
        // Reject invalid input before taking a snapshot or touching the current data file.
        Store.decode(Store.encode(next)); if (snapshot != null) store.snapshot(data, snapshot);
        store.save(next); data = next; revision++;
        return Map.of("revision", revision, "message", message);
    }
    synchronized List<String> reminders(LocalDateTime now) throws IOException {
        List<Model.Task> due = Model.due(data, now); if (due.isEmpty()) return List.of();
        Model.Data next = Store.copy(data); for (Model.Task task : due) Model.find(next, task.id).notified.add(now.toLocalDate());
        store.save(next); data = next; revision++; return due.stream().map(t -> t.title).toList();
    }
    synchronized Map<String, Object> memoState() {
        return Map.of("memos", data.memos.stream().map(Store::memoMap).toList());
    }
    /** A memo's version is independent of reminder and task revisions. */
    synchronized Map<String, Object> memoAction(Map<String, Object> request) throws IOException {
        String type = text(request, "type", 20), id = text(request, "id", 36); UUID.fromString(id);
        Model.Data next = Store.copy(data);
        Model.Memo memo = next.memos.stream().filter(m -> m.id.equals(id)).findFirst().orElse(null);
        if (!(request.get("expectedVersion") instanceof Number expected) || expected.doubleValue() != expected.longValue() || expected.longValue() < 0)
            throw new IllegalArgumentException("备忘录版本无效。");
        if (type.equals("save") && memo != null && memo.deletedAt == null && memo.title.equals(text(request, "title", 200).strip()) && memo.body.equals(text(request, "body", 200000)))
            return Map.of("memo", Store.memoMap(memo), "message", "已保存"); // Safe retry after a lost response or pagehide.
        if (expected.longValue() != (memo == null ? 0 : memo.version)) throw new Conflict("这篇备忘录已在其他窗口更新。当前草稿已保留，可另存为新笔记或重新载入核对。");
        String now = Instant.now().toString(); String message = "已保存";
        switch (type) {
            case "save" -> {
                if (memo != null && memo.deletedAt != null) throw new Conflict("备忘录已移入回收站。当前草稿可以另存为新笔记。");
                String title = text(request, "title", 200).strip(), body = text(request, "body", 200000);
                if (memo == null) {
                    if (title.isBlank() && body.isBlank()) throw new IllegalArgumentException("空白草稿无需保存。");
                    memo = new Model.Memo(); memo.id = id; memo.createdAt = now; next.memos.add(memo);
                } else memo.version++;
                memo.title = title; memo.body = body; memo.updatedAt = now;
            }
            case "pin", "delete", "restore", "purge", "export" -> {
                if (memo == null) throw new IllegalArgumentException("备忘录已不存在。");
                if (type.equals("export")) {
                    Path folder = store.directory.resolve("exports"); Files.createDirectories(folder);
                    String format = optionalText(request, "format", 10);
                    if (!format.equals("md") && !format.equals("txt")) throw new IllegalArgumentException("导出格式无效。");
                    String name = (memo.title.isBlank() ? "未命名备忘录" : memo.title).replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
                    if (name.length() > 60) name = name.substring(0, 60);
                    Path target = folder.resolve("备忘录-" + name + "-" + System.currentTimeMillis() + "." + format);
                    Store.atomicWrite(target, (memo.title.isBlank() ? "" : (format.equals("md") ? "# " : "") + memo.title + "\n\n") + memo.body);
                    return Map.of("memo", Store.memoMap(memo), "message", "已导出", "path", target.toString());
                }
                if (type.equals("purge")) {
                    if (memo.deletedAt == null) throw new IllegalArgumentException("请先将备忘录移入回收站。");
                    if (!flag(request, "confirmed", false)) throw new IllegalArgumentException("永久删除需要确认。");
                    next.memos.remove(memo); message = "已永久删除，操作前备份仍保留";
                } else {
                    if (type.equals("restore")) {
                        if (memo.deletedAt == null) throw new IllegalArgumentException("这篇备忘录没有被删除。");
                        memo.deletedAt = null; message = "已恢复备忘录";
                    } else {
                        if (memo.deletedAt != null) throw new IllegalArgumentException("备忘录已在回收站中。");
                        if (type.equals("pin")) memo.pinned = flag(request, "value", false);
                        else { memo.deletedAt = now; message = "已移入回收站"; }
                    }
                    memo.version++; memo.updatedAt = now;
                }
            }
            default -> throw new IllegalArgumentException("未知备忘录操作。");
        }
        Store.decode(Store.encode(next));
        if (type.equals("delete") || type.equals("purge")) store.snapshot(data, "before-memo-" + type);
        store.save(next); data = next; revision++;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("memo", type.equals("purge") ? null : Store.memoMap(memo)); result.put("message", message); return result;
    }
    synchronized void closingMemo(Map<String, Object> draft) throws IOException {
        String title = text(draft, "title", 200), body = text(draft, "body", 200000);
        if (title.isBlank() && body.isBlank() && ((Number)draft.get("expectedVersion")).longValue() == 0) return;
        Map<String, Object> request = new LinkedHashMap<>(draft); request.put("type", "save");
        try { memoAction(request); }
        catch (Conflict e) {
            // Closing cannot show a conflict dialog: preserve both documents rather than lose the draft.
            request.put("id", UUID.randomUUID().toString()); request.put("expectedVersion", 0);
            request.put("title", (title.isBlank() ? "未命名备忘录" : title.substring(0, Math.min(180, title.length()))) + "（冲突草稿）");
            memoAction(request);
        }
    }
    synchronized void closingNote(Map<String, Object> request) throws IOException {
        if (!(request.get("text") instanceof String text) || text.length() > 10000) return;
        LocalDate date = date(request); String base = optionalText(request, "base", 10000);
        if (!data.notes.getOrDefault(date, "").equals(base)) return; // Never overwrite another window's edit on unload.
        if (text.equals(base)) return;
        Model.Data next = Store.copy(data); if (text.isBlank()) next.notes.remove(date); else next.notes.put(date, text);
        store.save(next); data = next; revision++;
    }
    private static Model.Data single(Model.Task task) { Model.Data data = new Model.Data(); data.tasks.add(task); return data; }
    private static boolean samePlan(Model.Task task, Map<String, Object> expected) {
        return Objects.equals(task.title, expected.get("title")) && Objects.equals(task.notes, expected.get("notes"))
            && Objects.equals(task.category, expected.get("category")) && Objects.equals(task.repeat, expected.get("repeat"))
            && Objects.equals(task.start.toString(), expected.get("start")) && Objects.equals(task.end == null ? null : task.end.toString(), expected.get("end"))
            && Objects.equals(task.time == null ? null : task.time.toString(), expected.get("time"))
            && expected.get("priority") instanceof Number priority && priority.intValue() == task.priority
            && task.steps.stream().map(s -> s.title).toList().equals(array(expected.get("steps")).stream().map(s -> object(s).get("title")).toList());
    }
    private static void requireOccurrence(Model.Task task, LocalDate date) {
        if (!Model.occurs(task, date) || task.deferred.contains(date)) throw new IllegalArgumentException("这一天没有可操作的任务。");
    }
    private static LocalDate date(Map<String, Object> request) { return LocalDate.parse(text(request, "date", 10)); }
    static String text(Map<String, Object> map, String key, int max) {
        if (!(map.get(key) instanceof String value) || value.length() > max) throw new IllegalArgumentException("字段无效：" + key); return value;
    }
    private static String optionalText(Map<String, Object> map, String key, int max) { return map.get(key) == null ? "" : text(map, key, max); }
    static boolean flag(Map<String, Object> map, String key, boolean fallback) {
        if (!map.containsKey(key)) return fallback; if (!(map.get(key) instanceof Boolean value)) throw new IllegalArgumentException("字段无效：" + key); return value;
    }
    @SuppressWarnings("unchecked") static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?>)) throw new IllegalArgumentException("请求应为 JSON 对象。"); return (Map<String, Object>)value;
    }
    private static List<?> array(Object value) { if (!(value instanceof List<?> list)) throw new IllegalArgumentException("列表字段无效。"); return list; }
    static final class Conflict extends IllegalArgumentException { Conflict(String message) { super(message); } }
}
