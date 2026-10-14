package personal.todo;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

/** Local UI receipt, kept separate from user plans and exported backups. */
final class StartupReview {
    private final Path receipt;
    StartupReview(Store store) { receipt = store.directory.resolve("startup-review.json"); }

    Map<String, Object> open(Model.Data data, LocalDate today, String windowId) throws IOException {
        UUID.fromString(windowId);
        LocalDate yesterday = today.minusDays(1);
        if (Files.exists(receipt)) {
            if (Files.size(receipt) > 4096) throw new IOException("Startup review receipt is too large.");
            try {
                Map<String, Object> saved = TodoService.object(Json.parse(Files.readString(receipt)));
                LocalDate seen = LocalDate.parse(TodoService.text(saved, "date", 10));
                String owner = TodoService.text(saved, "windowId", 36); UUID.fromString(owner);
                // The same window may retry after a lost response; other windows cannot claim twice.
                if (seen.equals(today) && !owner.equals(windowId)) return response(today, yesterday, List.of());
            } catch (IllegalArgumentException e) { throw new IOException("Invalid startup review receipt.", e); }
        }
        List<Map<String, Object>> items = data.tasks.stream().filter(task -> task.done.contains(yesterday))
            .map(task -> Map.<String, Object>of("id", task.id, "title", task.title, "category", task.category,
                "report", task.completionReports.getOrDefault(yesterday, ""))).toList();
        // Record the first successful opening even when there are no completed items to show.
        Files.createDirectories(receipt.getParent());
        Store.atomicWrite(receipt, Json.write(Map.of("date", today.toString(), "windowId", windowId)));
        return response(today, yesterday, items);
    }
    private static Map<String, Object> response(LocalDate today, LocalDate yesterday, List<Map<String, Object>> items) {
        return Map.of("today", today.toString(), "date", yesterday.toString(), "show", !items.isEmpty(), "items", items);
    }
}
