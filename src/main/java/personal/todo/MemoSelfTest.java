package personal.todo;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Persistence and conflict checks use isolated D-drive data, never personal notes. */
final class MemoSelfTest {
    private static int count;
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message); count++; System.out.println("PASS " + message);
    }
    private static Map<String, Object> save(String id, long version, String title, String body) {
        return Map.of("type", "save", "id", id, "expectedVersion", version, "title", title, "body", body);
    }
    private static Map<String, Object> edit(String type, Model.Memo memo) {
        return Map.of("type", type, "id", memo.id, "expectedVersion", memo.version);
    }
    private static void conflict(TodoService service, Map<String, Object> request, String message) throws IOException {
        try { service.memoAction(request); throw new AssertionError(message); }
        catch (TodoService.Conflict expected) { check(true, message); }
    }
    static int run() throws Exception {
        count = 0; Path folder = AppPaths.testDirectory("memo-core-"); Store store = new Store(folder);
        try {
            check(store.acquire(), "memos isolated data lock acquired");
            Model.Data legacy = new Model.Data(); legacy.notes.put(java.time.LocalDate.now(), "daily review stays separate");
            Map<String, Object> oldBackup = TodoService.object(Json.parse(Store.encode(legacy))); oldBackup.remove("memos");
            check(Store.decode(Json.write(oldBackup)).memos.isEmpty(), "legacy backup without memos remains readable");
            TodoService service = new TodoService(store, legacy); String id = UUID.randomUUID().toString();
            service.memoAction(save(id, 0, "Vue 学习笔记", "## 组件\n\n- 属性\n- 事件\n\n```js\nconst value = 1\n```"));
            Model.Memo first = service.copy().memos.getFirst();
            check(first.version == 1 && first.title.equals("Vue 学习笔记"), "memo creation has independent version");
            check(store.load().memos.getFirst().body.contains("```js"), "Markdown body persists without transformation");
            check(service.copy().notes.equals(legacy.notes), "memos do not modify daily review");
            service.memoAction(save(id, 0, first.title, first.body));
            check(service.copy().memos.size() == 1 && service.copy().memos.getFirst().version == 1, "lost-response retry cannot duplicate memo");
            String longBody = "中文\n".repeat(20000);
            service.memoAction(save(id, 1, first.title, longBody));
            check(store.load().memos.getFirst().body.equals(longBody), "long Chinese memo survives atomic save and reload");
            conflict(service, save(id, 1, "old window", "stale body"), "stale memo edit cannot overwrite newer content");
            Map<String, Object> state = service.state(java.time.LocalDate.now());
            service.action(Map.of("type", "settings.save", "revision", state.get("revision"), "sound", false));
            first = service.copy().memos.getFirst(); service.memoAction(save(id, first.version, first.title, "updated after settings"));
            check(service.copy().memos.getFirst().body.equals("updated after settings"), "task/settings revision does not invalidate memo writes");
            first = service.copy().memos.getFirst(); Map<String, Object> pin = new HashMap<>(edit("pin", first)); pin.put("value", true); service.memoAction(pin);
            check(store.load().memos.getFirst().pinned, "pin state persisted");
            first = service.copy().memos.getFirst(); service.memoAction(edit("delete", first));
            check(store.load().memos.getFirst().deletedAt != null, "delete moves memo to persistent recycle bin");
            conflict(service, save(id, first.version, "stale", "body"), "stale editor cannot revive deleted memo");
            first = service.copy().memos.getFirst(); service.memoAction(edit("restore", first));
            check(store.load().memos.getFirst().deletedAt == null, "restore recovers original memo and body");
            first = service.copy().memos.getFirst(); Map<String, Object> export = new HashMap<>(edit("export", first)); export.put("format", "md");
            Path exported = Path.of((String)service.memoAction(export).get("path"));
            check(exported.startsWith(folder) && Files.readString(exported).startsWith("# Vue 学习笔记"), "Markdown export stays on D and includes title");
            state = service.state(java.time.LocalDate.now());
            service.action(Map.of("type", "backup.import", "revision", state.get("revision"), "data", oldBackup));
            check(service.copy().memos.size() == 1, "importing legacy backup preserves existing memos");
            Model.Data complete = service.copy(); complete.memos.getFirst().body = "restored from complete backup";
            long preImportVersion = complete.memos.getFirst().version;
            state = service.state(java.time.LocalDate.now());
            service.action(Map.of("type", "backup.import", "revision", state.get("revision"), "data", Json.parse(Store.encode(complete))));
            check(service.copy().memos.getFirst().body.equals("restored from complete backup"), "full backup import restores memos with todo data");
            conflict(service, save(id, preImportVersion, "old draft", "must not overwrite restore"), "backup restore invalidates open memo drafts");
            service.closingMemo(save(id, preImportVersion, "closing stale draft", "preserve this text"));
            check(service.copy().memos.size() == 2 && service.copy().memos.stream().anyMatch(m -> m.title.contains("冲突草稿") && m.body.equals("preserve this text")), "conflicting close preserves both documents");
            first = service.copy().memos.stream().filter(m -> m.id.equals(id)).findFirst().orElseThrow();
            service.closingMemo(save(id, first.version, first.title, "final close text"));
            check(store.load().memos.stream().anyMatch(m -> m.id.equals(id) && m.body.equals("final close text")), "closing saves latest draft before shutdown");
            byte[] bytes = Files.readAllBytes(store.file); int beforeCount = service.copy().memos.size();
            Files.createDirectory(folder.resolve("data.json.tmp"));
            try { service.memoAction(save(UUID.randomUUID().toString(), 0, "write fail", "body")); throw new AssertionError("write failure expected"); }
            catch (IOException expected) { check(Arrays.equals(bytes, Files.readAllBytes(store.file)) && service.copy().memos.size() == beforeCount, "failed disk write leaves in-memory and persisted notes intact"); }
            Files.delete(folder.resolve("data.json.tmp"));
            first = service.copy().memos.stream().filter(m -> m.id.equals(id)).findFirst().orElseThrow(); service.memoAction(edit("delete", first));
            first = service.copy().memos.stream().filter(m -> m.id.equals(id)).findFirst().orElseThrow();
            try { service.memoAction(edit("purge", first)); throw new AssertionError("confirmation expected"); }
            catch (IllegalArgumentException expected) { check(service.copy().memos.size() == 2, "permanent delete requires explicit confirmation"); }
            Map<String, Object> purge = new HashMap<>(edit("purge", first)); purge.put("confirmed", true); service.memoAction(purge);
            check(store.load().memos.stream().noneMatch(m -> m.id.equals(id)), "confirmed purge removes only selected recycled memo");
            try (var backups = Files.list(folder.resolve("backups"))) { check(backups.anyMatch(p -> p.getFileName().toString().startsWith("before-memo-purge")), "permanent delete retains operation-before backup"); }
            Model.Data bad = service.copy(); bad.memos.add(bad.memos.getFirst());
            try { Store.decode(Store.encode(bad)); throw new AssertionError("duplicate IDs accepted"); }
            catch (IllegalArgumentException expected) { check(true, "duplicate memo IDs rejected before saving"); }
            check(service.copy().notes.equals(legacy.notes), "existing daily review remains intact after all memo operations");
        } finally {
            store.release();
            try (var paths = Files.walk(folder)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
        }
        return count;
    }
}
