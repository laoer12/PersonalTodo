package personal.todo;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;

final class StartupReviewSelfTest {
    private static int count;
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        count++; System.out.println("PASS " + message);
    }
    static int run() throws Exception {
        count = 0;
        Path folder = AppPaths.testDirectory("personal-todo-startup-review-");
        Store store = new Store(folder);
        LocalDate today = LocalDate.of(2026, 10, 14), yesterday = today.minusDays(1);
        try {
            store.acquire(); Model.Data data = new Model.Data();
            Model.Task first = new Model.Task(); first.title = "学习 Vue 组件通信"; first.category = "学习";
            first.start = today.plusDays(5); first.done.add(yesterday); first.completionReports.put(yesterday, "完成了 props 与 emit 的练习。\n下一步练习状态共享。");
            Model.Task second = new Model.Task(); second.title = "完成项目日报"; second.done.add(yesterday);
            Model.Task open = new Model.Task(); open.title = "未完成"; open.start = yesterday; open.completionReports.put(yesterday, "曾经填写的内容");
            Model.Task deferred = new Model.Task(); deferred.title = "已顺延"; deferred.start = yesterday; deferred.deferred.add(yesterday);
            Model.Task older = new Model.Task(); older.title = "前天完成"; older.done.add(yesterday.minusDays(1));
            Model.Task current = new Model.Task(); current.title = "今天完成"; current.done.add(today);
            data.tasks.addAll(List.of(first, second, open, deferred, older, current));
            store.save(data); byte[] before = Files.readAllBytes(store.file);
            TodoService service = new TodoService(store, store.load()); Object revision = service.state(today).get("revision");
            String owner = UUID.randomUUID().toString(); var review = service.startupReview(today, owner);
            check(Boolean.TRUE.equals(review.get("show")) && review.get("date").equals(yesterday.toString()), "first opening returns yesterday review using calendar date");
            var items = (List<?>)review.get("items");
            check(items.size() == 2 && TodoService.object(items.getFirst()).get("id").equals(first.id), "review includes only yesterday completed tasks even outside edited recurrence");
            check(TodoService.object(items.getFirst()).get("report").equals(first.completionReports.get(yesterday)), "review preserves full multiline completion report");
            check(TodoService.object(items.get(1)).get("report").equals(""), "completed task without report remains in review");
            check(Arrays.equals(before, Files.readAllBytes(store.file)) && revision.equals(service.state(today).get("revision")), "opening review changes neither personal data nor task revision");
            check(service.startupReview(today, owner).equals(review), "same window can recover review after a lost response");
            check(Boolean.FALSE.equals(service.startupReview(today, UUID.randomUUID().toString()).get("show")), "second window cannot repeat the daily review");
            TodoService restarted = new TodoService(store, store.load());
            check(Boolean.FALSE.equals(restarted.startupReview(today, UUID.randomUUID().toString()).get("show")), "daily receipt survives backend restart");
            check(Boolean.TRUE.equals(restarted.startupReview(today.plusDays(1), UUID.randomUUID().toString()).get("show")), "next day opens a new review for its own yesterday");
            byte[] receipt = Files.readAllBytes(folder.resolve("startup-review.json")); boolean invalid = false;
            try { restarted.startupReview(today.plusDays(2), "invalid-window"); } catch (IllegalArgumentException expected) { invalid = true; }
            check(invalid && Arrays.equals(receipt, Files.readAllBytes(folder.resolve("startup-review.json"))), "invalid owner cannot consume daily review slot");
            Model.Data empty = new Model.Data(); TodoService emptyService = new TodoService(store, empty);
            check(Boolean.FALSE.equals(emptyService.startupReview(today.plusDays(2), UUID.randomUUID().toString()).get("show")), "day without completed tasks skips the popup");
            Model.Data lateData = Store.copy(data); lateData.tasks.getFirst().done.add(today.plusDays(1));
            TodoService lateService = new TodoService(store, lateData);
            check(Boolean.FALSE.equals(lateService.startupReview(today.plusDays(2), UUID.randomUUID().toString()).get("show")), "first opening without items still consumes the daily slot");
            LocalDate concurrentDay = today.plusDays(3); Model.Data concurrentData = Store.copy(data); concurrentData.tasks.getFirst().done.add(concurrentDay.minusDays(1));
            TodoService concurrent = new TodoService(store, concurrentData);
            try (var workers = Executors.newFixedThreadPool(2)) {
                CountDownLatch ready = new CountDownLatch(2), release = new CountDownLatch(1);
                Callable<Boolean> claim = () -> { ready.countDown(); release.await(); return Boolean.TRUE.equals(concurrent.startupReview(concurrentDay, UUID.randomUUID().toString()).get("show")); };
                Future<Boolean> a = workers.submit(claim), b = workers.submit(claim); ready.await(); release.countDown();
                check(a.get() != b.get(), "simultaneous windows have exactly one daily winner");
            }
            String invalidReceipt = "{broken"; Files.writeString(folder.resolve("startup-review.json"), invalidReceipt); boolean failed = false;
            try { service.startupReview(today.plusDays(4), UUID.randomUUID().toString()); } catch (IOException expected) { failed = true; }
            check(failed && Files.readString(folder.resolve("startup-review.json")).equals(invalidReceipt) && Arrays.equals(before, Files.readAllBytes(store.file)), "invalid receipt reports failure without overwriting receipt or personal data");
            Files.delete(folder.resolve("startup-review.json")); Files.createDirectory(folder.resolve("startup-review.json")); failed = false;
            try { service.startupReview(today.plusDays(4), UUID.randomUUID().toString()); } catch (IOException expected) { failed = true; }
            check(failed && Arrays.equals(before, Files.readAllBytes(store.file)), "receipt storage failure preserves personal data");
            check(!Store.encode(data).contains("startup-review") && !Store.encode(data).contains(owner), "backup data excludes local popup receipt");
        } finally {
            store.release();
            if (!folder.toRealPath().startsWith(AppPaths.TEMP.toRealPath())) throw new IllegalStateException("Unexpected test directory");
            try (var files = Files.walk(folder)) { for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file); }
        }
        return count;
    }
}
