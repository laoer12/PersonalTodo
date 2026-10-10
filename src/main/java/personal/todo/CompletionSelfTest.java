package personal.todo;

import java.nio.file.*;
import java.time.*;
import java.util.*;

final class CompletionSelfTest {
    private static int count;
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); count++; System.out.println("PASS " + message); }
    private static Map<String, Object> complete(TodoService service, Model.Task task, LocalDate date, boolean value, String report) {
        Map<String, Object> fields = new HashMap<>(); fields.put("revision", service.state(date).get("revision")); fields.put("type", "task.complete"); fields.put("id", task.id); fields.put("date", date.toString()); fields.put("value", value);
        if (report != null) fields.put("completionReport", report); return fields;
    }
    static int run() throws Exception {
        count = 0; Path folder = AppPaths.testDirectory("completion-core-"); Store store = new Store(folder);
        try {
            store.acquire(); Model.Data data = new Model.Data(); Model.Task task = new Model.Task(); task.title = "学习 Vue"; task.repeat = "daily"; Model.Step step = new Model.Step(); step.title = "练习组件"; task.steps.add(step); data.tasks.add(task);
            Map<String, Object> legacy = TodoService.object(Json.parse(Store.encode(data)));
            var oldTask = TodoService.object(((List<?>)legacy.get("tasks")).getFirst()); oldTask.remove("completionReports");
            check(Store.decode(Json.write(legacy)).tasks.getFirst().completionReports.isEmpty(), "legacy tasks load without completion reports");
            TodoService service = new TodoService(store, data); LocalDate today = task.start;
            service.action(complete(service, task, today, true, "完成组件练习\n下一步练习事件。"));
            Model.Task saved = store.load().tasks.getFirst();
            check(saved.done.contains(today) && saved.completionReports.get(today).contains("事件"), "completion status and report commit together");
            check(saved.steps.getFirst().done.contains(today), "completion report preserves parent-child completion behavior");
            service.action(complete(service, task, today.plusDays(1), true, "第二天的完成情况"));
            saved = store.load().tasks.getFirst(); check(saved.completionReports.size() == 2 && !saved.completionReports.get(today).equals(saved.completionReports.get(today.plusDays(1))), "recurring task reports are independent by date");
            service.action(complete(service, task, today, false, null));
            saved = service.copy().tasks.getFirst(); check(!saved.done.contains(today) && saved.completionReports.containsKey(today), "unchecking preserves previous report for re-completion");
            Map<String, Object> stale = complete(service, task, today, true, "stale report"); stale.put("expectedCompletion", Map.of("done", false, "report", "outdated"));
            byte[] before = Files.readAllBytes(store.file);
            try { service.action(stale); throw new AssertionError("stale completion accepted"); }
            catch (TodoService.Conflict expected) { check(Arrays.equals(before, Files.readAllBytes(store.file)), "stale completion report cannot overwrite another window"); }
            try { service.action(complete(service, task, today, true, "x".repeat(10001))); throw new AssertionError("oversized report accepted"); }
            catch (IllegalArgumentException expected) { check(!service.copy().tasks.getFirst().done.contains(today) && Arrays.equals(before, Files.readAllBytes(store.file)), "invalid report cannot mark task complete or modify disk"); }
            Files.createDirectory(folder.resolve("data.json.tmp"));
            try { service.action(complete(service, task, today, true, "disk failure")); throw new AssertionError("disk failure expected"); }
            catch (java.io.IOException expected) { check(!service.copy().tasks.getFirst().done.contains(today) && Arrays.equals(before, Files.readAllBytes(store.file)), "failed save leaves completion and report uncommitted"); }
            Files.delete(folder.resolve("data.json.tmp"));
            service.action(complete(service, task, today, true, "   "));
            check(service.copy().tasks.getFirst().done.contains(today) && !service.copy().tasks.getFirst().completionReports.containsKey(today), "optional blank report can complete task without saved text");
            saved = store.load().tasks.getFirst(); check(Model.copyPlan(saved).completionReports.isEmpty(), "new templates and deferred copies do not inherit old completion reports");
            Path backup = folder.resolve("export.json"); store.export(service.copy(), backup);
            check(store.read(backup).tasks.getFirst().completionReports.equals(saved.completionReports), "completion reports included in backup round trip");
        } finally {
            store.release(); try (var paths = Files.walk(folder)) { for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p); }
        }
        return count;
    }
}
