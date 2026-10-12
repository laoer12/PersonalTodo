package personal.todo;

import java.nio.file.*;
import java.time.*;
import java.util.*;

final class WeeklyRepeatSelfTest {
    private static int count;
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        count++; System.out.println("PASS " + message);
    }
    private static Map<String, Object> fields(Model.Task task) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("id", task.id); fields.put("title", task.title); fields.put("start", task.start.toString());
        fields.put("repeat", task.repeat); fields.put("weekDays", new ArrayList<>(task.weekDays));
        fields.put("category", task.category); fields.put("priority", task.priority); fields.put("notes", task.notes);
        fields.put("time", task.time == null ? null : task.time.toString()); fields.put("steps", List.of());
        return fields;
    }
    private static Map<String, Object> plan(TodoService service) {
        var data = TodoService.object(service.state(LocalDate.of(2026, 10, 12)).get("data"));
        return TodoService.object(((List<?>)data.get("tasks")).getFirst());
    }
    private static void action(TodoService service, Map<String, Object> request) throws Exception {
        request.put("revision", service.state(LocalDate.of(2026, 10, 12)).get("revision")); service.action(request);
    }
    static int run() throws Exception {
        count = 0;
        LocalDate monday = LocalDate.of(2026, 10, 12);
        Model.Task task = new Model.Task(); task.title = "每周学习"; task.repeat = "weekly"; task.start = monday;
        task.time = LocalTime.of(9, 0);
        Model.Data data = new Model.Data(); data.tasks.add(task);
        var legacy = TodoService.object(Json.parse(Store.encode(data)));
        TodoService.object(((List<?>)legacy.get("tasks")).getFirst()).remove("weekDays");
        Model.Task loaded = Store.decode(Json.write(legacy)).tasks.getFirst();
        check(loaded.weekDays.equals(Set.of(1)) && Model.occurs(loaded, monday.plusWeeks(1)) && !Model.occurs(loaded, monday.plusDays(1)), "legacy weekly tasks retain start weekday without migration writes");
        task.weekDays.addAll(List.of(3, 5, 7));
        check(!Model.occurs(task, monday) && Model.occurs(task, monday.plusDays(2)) && Model.occurs(task, monday.plusDays(4)) && Model.occurs(task, monday.plusDays(6)), "selected Wednesday Friday Sunday recur independently of start weekday");
        check(!Model.occurs(task, monday.minusDays(1)) && Model.occurs(task, monday.plusWeeks(12).plusDays(2)), "weekly selection respects start boundary and recurs across weeks");
        task.end = monday.plusDays(4);
        check(Model.occurs(task, task.end) && !Model.occurs(task, monday.plusDays(6)), "selected weekly dates respect inclusive end boundary"); task.end = null;
        check(Model.due(data, monday.plusDays(1).atTime(9, 0)).isEmpty() && Model.due(data, monday.plusDays(2).atTime(9, 0)).size() == 1, "reminder schedule uses selected weekdays");
        task.done.add(monday.plusDays(2)); task.notified.add(monday.plusDays(4));
        check(Model.due(data, monday.plusDays(2).atTime(9, 0)).isEmpty() && Model.due(data, monday.plusDays(4).atTime(9, 0)).isEmpty(), "selected weekly reminders honor completion and delivery deduplication");
        Model.Task cloned = Model.copyPlan(task); cloned.weekDays.remove(3);
        check(task.weekDays.contains(3) && cloned.weekDays.equals(Set.of(5, 7)) && cloned.done.isEmpty(), "plan copies retain independent weekday selection and reset completion");
        check(Store.copy(data).tasks.getFirst().weekDays.equals(task.weekDays), "weekday selections survive JSON copy");
        check(Model.parseWeekDays(List.of(7, 3, 3)).equals(new TreeSet<>(List.of(3, 7))), "weekday lists normalize order and duplicates");
        for (Object invalid : List.of(List.of(0), List.of(8), List.of(1.5), List.of("1"), "Monday")) {
            boolean rejected = false; try { Model.parseWeekDays(invalid); } catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "invalid weekday input rejected: " + invalid);
        }
        Path directory = Files.createTempDirectory(AppPaths.TEMP, "personal-todo-weekly-");
        Store store = new Store(directory);
        try {
            store.acquire(); store.save(data); TodoService service = new TodoService(store, store.load());
            byte[] before = Files.readAllBytes(store.file); Object revision = service.state(monday).get("revision");
            for (List<Integer> invalid : List.of(List.<Integer>of(), List.of(8))) {
                var bad = fields(task); bad.put("weekDays", invalid); boolean rejected = false;
                try { action(service, new HashMap<>(Map.of("type", "task.save", "task", bad))); } catch (IllegalArgumentException expected) { rejected = true; }
                check(rejected && Arrays.equals(before, Files.readAllBytes(store.file)) && revision.equals(service.state(monday).get("revision")), "invalid weekly save leaves file and revision unchanged: " + invalid);
            }
            var updated = fields(task); updated.put("weekDays", List.of(5, 1, 3));
            action(service, new HashMap<>(Map.of("type", "task.save", "task", updated)));
            check(Model.parseWeekDays(plan(service).get("weekDays")).equals(Set.of(1, 3, 5)), "API saves sorted multi-weekday selection");
            var range = TodoService.object(service.state(monday, monday, monday.plusDays(6)).get("range"));
            var days = (List<?>)range.get("days");
            boolean exact = true;
            for (int index = 0; index < 7; index++) exact &= !((List<?>)TodoService.object(days.get(index)).get("taskIds")).isEmpty() == (index == 0 || index == 2 || index == 4);
            check(exact, "period plan includes exactly Monday Wednesday Friday");
            action(service, new HashMap<>(Map.of("type", "task.complete", "id", task.id, "date", monday.toString(), "value", true, "completionReport", "完成周一学习")));
            check(service.copy().tasks.getFirst().done.contains(monday) && !service.copy().tasks.getFirst().done.contains(monday.plusDays(4)), "completion belongs to the chosen weekly occurrence");
            var expectedPlan = new LinkedHashMap<>(plan(service)); updated.put("weekDays", List.of(2));
            action(service, new HashMap<>(Map.of("type", "task.save", "task", updated, "expected", expectedPlan)));
            check(((List<?>)service.state(monday).get("dayTasks")).isEmpty() && ((List<?>)service.state(monday).get("historyDayTasks")).contains(task.id) && service.copy().tasks.getFirst().completionReports.get(monday).equals("完成周一学习"), "changing selected weekdays preserves historical completion and report");
            before = Files.readAllBytes(store.file); boolean conflicted = false;
            try { action(service, new HashMap<>(Map.of("type", "task.save", "task", updated, "expected", expectedPlan))); } catch (TodoService.Conflict expected) { conflicted = true; }
            check(conflicted && Arrays.equals(before, Files.readAllBytes(store.file)), "concurrent weekday edit rejected even with current global revision");
            updated.remove("weekDays"); updated.put("notes", "兼容旧客户端");
            action(service, new HashMap<>(Map.of("type", "task.save", "task", updated)));
            check(service.copy().tasks.getFirst().weekDays.equals(Set.of(2)), "older client edit without weekday field preserves existing selection");
            Path exported = directory.resolve("export.json"); store.export(service.copy(), exported);
            check(store.read(exported).tasks.getFirst().weekDays.equals(Set.of(2)) && store.read(exported).tasks.getFirst().done.contains(monday), "backup round trip preserves weekly selection and old completion");
            check(store.load().tasks.getFirst().weekDays.equals(Set.of(2)), "restart restores saved weekly selection");
            updated.put("repeat", "daily"); updated.put("weekDays", List.of(2));
            action(service, new HashMap<>(Map.of("type", "task.save", "task", updated)));
            check(service.copy().tasks.getFirst().weekDays.isEmpty() && Model.occurs(service.copy().tasks.getFirst(), monday.plusDays(3)), "switching repeat type clears weekday restriction");
            updated.put("repeat", "weekly"); updated.remove("weekDays");
            action(service, new HashMap<>(Map.of("type", "task.save", "task", updated)));
            check(service.copy().tasks.getFirst().weekDays.equals(Set.of(1)), "legacy weekly creation defaults to start weekday");
            check(service.reminders(monday.plusWeeks(1).atTime(9, 0)).size() == 1 && service.reminders(monday.plusWeeks(1).atTime(9, 1)).isEmpty() && service.reminders(monday.plusWeeks(1).plusDays(1).atTime(9, 0)).isEmpty(), "actual reminder worker persists selected-day delivery only once");
            action(service, new HashMap<>(Map.of("type", "template.create", "id", task.id)));
            Model.Task template = service.copy().templates.getFirst();
            check(template.repeat.equals("once") && template.weekDays.isEmpty(), "daily template does not inherit weekly restrictions");
            action(service, new HashMap<>(Map.of("type", "template.apply", "ids", List.of(template.id), "date", monday.plusDays(1).toString())));
            check(service.copy().tasks.stream().anyMatch(t -> !t.id.equals(task.id) && t.repeat.equals("once") && t.start.equals(monday.plusDays(1)) && t.weekDays.isEmpty()), "applying template creates unrestricted one-day occurrence");
        } finally {
            store.release();
            if (!directory.toRealPath().startsWith(AppPaths.TEMP.toRealPath())) throw new IllegalStateException("Unexpected test directory");
            try (var files = Files.walk(directory)) { for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file); }
        }
        Model.Data deferred = new Model.Data(); Model.Task scheduled = new Model.Task(); scheduled.title = "顺延"; scheduled.repeat = "weekly"; scheduled.start = monday; scheduled.weekDays.addAll(List.of(1, 3)); deferred.tasks.add(scheduled);
        Model.Task once = Model.defer(deferred, scheduled.id, monday);
        check(once != null && once.repeat.equals("once") && once.weekDays.isEmpty() && Model.occurs(once, monday.plusDays(1)) && Model.occurs(scheduled, monday.plusDays(2)), "defer to unselected weekday creates one-day task without changing series");
        scheduled.deferred.clear(); scheduled.weekDays.add(2); int size = deferred.tasks.size();
        check(Model.defer(deferred, scheduled.id, monday) == null && deferred.tasks.size() == size, "defer to already selected weekday reuses next occurrence");
        return count;
    }
}
