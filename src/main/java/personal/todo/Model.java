package personal.todo;

import java.time.*;
import java.util.*;

final class Model {
    static final String[] REPEATS = {"once", "daily", "weekdays", "weekly", "monthly"};
    static final String[] REPEAT_NAMES = {"仅此一天", "每天", "工作日（周一至周五）", "每周", "每月"};
    static final String[] PRIORITIES = {"普通", "低优先级", "中优先级", "高优先级"};

    static final class Step {
        String title = "";
        Set<LocalDate> done = new LinkedHashSet<>();
    }
    static final class Task {
        String id = UUID.randomUUID().toString();
        String title = "", notes = "", category = "生活", repeat = "once";
        LocalDate start = LocalDate.now(), end;
        LocalTime time;
        int priority;
        Set<Integer> weekDays = new TreeSet<>();
        Set<LocalDate> done = new LinkedHashSet<>(), deferred = new LinkedHashSet<>(), notified = new LinkedHashSet<>();
        Map<LocalDate, String> completionReports = new TreeMap<>();
        List<Step> steps = new ArrayList<>();
    }
    static final class Data {
        List<Task> tasks = new ArrayList<>(), templates = new ArrayList<>();
        List<Memo> memos = new ArrayList<>();
        Map<LocalDate, String> notes = new TreeMap<>();
        boolean closeToTray = true, sound = true;
    }
    static final class Memo {
        String id = UUID.randomUUID().toString(), title = "", body = "";
        boolean pinned;
        String createdAt = Instant.now().toString(), updatedAt = createdAt, deletedAt;
        long version = 1;
    }
    static Task find(Data data, String id) {
        return data.tasks.stream().filter(t -> t.id.equals(id)).findFirst().orElseThrow();
    }
    static boolean occurs(Task t, LocalDate date) {
        if (date.isBefore(t.start) || (t.end != null && date.isAfter(t.end))) return false;
        return switch (t.repeat) {
            case "daily" -> true;
            case "weekdays" -> date.getDayOfWeek().getValue() <= 5;
            case "weekly" -> effectiveWeekDays(t).contains(date.getDayOfWeek().getValue());
            case "monthly" -> date.getDayOfMonth() == Math.min(t.start.getDayOfMonth(), date.lengthOfMonth());
            default -> date.equals(t.start);
        };
    }
    static boolean open(Task t, LocalDate date) { return !t.done.contains(date) && !t.deferred.contains(date); }
    static Set<Integer> effectiveWeekDays(Task task) {
        return task.weekDays.isEmpty() ? Set.of(task.start.getDayOfWeek().getValue()) : task.weekDays;
    }
    static Set<Integer> parseWeekDays(Object value) {
        if (!(value instanceof List<?> days) || days.size() > 7) throw new IllegalArgumentException("每周重复日期应为周一至周日的列表。");
        Set<Integer> result = new TreeSet<>();
        for (Object day : days) {
            if (!(day instanceof Number n) || n.doubleValue() != n.intValue() || n.intValue() < 1 || n.intValue() > 7)
                throw new IllegalArgumentException("每周重复日期应为周一至周日。");
            result.add(n.intValue());
        }
        return result;
    }
    static void mark(Set<LocalDate> dates, LocalDate date, boolean value) {
        if (value) dates.add(date); else dates.remove(date);
    }
    static String repeatName(String value) {
        int index = Arrays.asList(REPEATS).indexOf(value);
        return REPEAT_NAMES[Math.max(0, index)];
    }
    static Task copyPlan(Task source) {
        Task t = new Task();
        t.title = source.title; t.notes = source.notes; t.category = source.category;
        t.repeat = source.repeat; t.priority = source.priority; t.start = source.start; t.end = source.end; t.time = source.time;
        t.weekDays.addAll(source.weekDays);
        for (Step step : source.steps) { Step s = new Step(); s.title = step.title; t.steps.add(s); }
        return t;
    }
    static Task defer(Data data, String id, LocalDate source) {
        Task task = find(data, id);
        if (!occurs(task, source) || !open(task, source)) return null;
        LocalDate next = source.plusDays(1);
        Task copy = null;
        if (!occurs(task, next)) {
            copy = copyPlan(task); copy.start = next; copy.end = null; copy.repeat = "once";
            copy.weekDays.clear();
            data.tasks.add(copy);
        }
        task.deferred.add(source);
        return copy;
    }
    static List<Task> forDay(Data data, LocalDate date) {
        return data.tasks.stream().filter(t -> occurs(t, date)).sorted(
            Comparator.<Task>comparingInt(t -> open(t, date) ? 0 : 1)
                .thenComparing(Comparator.comparingInt((Task t) -> t.priority).reversed())
                .thenComparing(t -> t.time, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(t -> t.title)).toList();
    }
    static List<Task> due(Data data, LocalDateTime now) {
        LocalDate day = now.toLocalDate();
        return data.tasks.stream().filter(t -> occurs(t, day) && open(t, day) && t.time != null && !t.notified.contains(day))
            .filter(t -> { long seconds = Duration.between(day.atTime(t.time), now).getSeconds(); return seconds >= 0 && seconds <= 1800; }).toList();
    }
}
