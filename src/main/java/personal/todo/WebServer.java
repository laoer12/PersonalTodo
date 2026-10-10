package personal.todo;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

final class WebServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService requests = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "todo-reminders"));
    final TodoService service;
    final String token, origin, url;
    private final DesktopBridge desktop;
    private final Runnable stop;
    private final Map<String, Long> windows = new ConcurrentHashMap<>();
    private final Path infoFile;
    private volatile boolean closed;

    WebServer(Store store, Model.Data data, Path infoFile, boolean headless, Runnable stop) throws IOException {
        this.service = new TodoService(store, data); this.infoFile = AppPaths.storagePath(infoFile); this.stop = stop;
        byte[] key = new byte[32]; new SecureRandom().nextBytes(key); token = HexFormat.of().formatHex(key);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        origin = "http://127.0.0.1:" + server.getAddress().getPort(); url = origin + "/#token=" + token;
        desktop = new DesktopBridge(service, this::openWindow, stop, headless);
        server.setExecutor(requests); server.createContext("/", this::handle);
    }
    void start() throws IOException {
        Files.createDirectories(infoFile.getParent());
        Store.atomicWrite(infoFile, Json.write(Map.of("origin", origin, "url", url, "token", token, "pid", ProcessHandle.current().pid(), "data", service.store.directory.toString())));
        server.start();
        worker.scheduleWithFixedDelay(() -> {
            try { List<String> due = service.reminders(LocalDateTime.now()); if (!due.isEmpty()) desktop.reminder(due); }
            catch (Exception e) { log(e); }
        }, 0, 20, TimeUnit.SECONDS);
    }
    void openWindow() {
        try { BrowserLauncher.open(url); } catch (IOException e) { javax.swing.SwingUtilities.invokeLater(() -> javax.swing.JOptionPane.showMessageDialog(null, e.getMessage(), "无法打开页面", javax.swing.JOptionPane.ERROR_MESSAGE)); }
    }
    private void handle(HttpExchange exchange) throws IOException {
        try {
            Headers headers = exchange.getResponseHeaders(); headers.set("X-Content-Type-Options", "nosniff"); headers.set("Referrer-Policy", "no-referrer");
            headers.set("Cache-Control", "no-store"); headers.set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'");
            String host = exchange.getRequestHeaders().getFirst("Host");
            if (!("127.0.0.1:" + server.getAddress().getPort()).equals(host)) { json(exchange, 403, Map.of("error", "无效主机")); return; }
            String path = exchange.getRequestURI().getPath();
            if (path.startsWith("/api/")) api(exchange, path); else asset(exchange, path);
        } catch (TodoService.Conflict e) { json(exchange, 409, Map.of("error", e.getMessage())); }
        catch (IllegalArgumentException | NoSuchElementException | ClassCastException e) { json(exchange, 400, Map.of("error", Objects.toString(e.getMessage(), "请求无效。"))); }
        catch (Exception e) { log(e); json(exchange, 500, Map.of("error", "操作失败，原数据已保留。请查看 D 盘日志。")); }
        finally { exchange.close(); }
    }
    private void api(HttpExchange exchange, String path) throws IOException {
        String requestOrigin = exchange.getRequestHeaders().getFirst("Origin");
        if (requestOrigin != null && !origin.equals(requestOrigin)) { json(exchange, 403, Map.of("error", "拒绝其他页面访问本机数据")); return; }
        Map<String, Object> body = null;
        // pagehide uses sendBeacon, which cannot attach Authorization headers.
        if (path.equals("/api/window") && exchange.getRequestMethod().equals("POST")) body = readBody(exchange);
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        String supplied = auth != null && auth.startsWith("Bearer ") ? auth.substring(7) : body != null ? Objects.toString(body.get("token"), "") : "";
        if (!MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) { json(exchange, 401, Map.of("error", "页面连接已过期，请从托盘重新打开。")); return; }
        if (path.equals("/api/state") && exchange.getRequestMethod().equals("GET")) {
            String query = exchange.getRequestURI().getRawQuery(); Map<String, String> parameters = new HashMap<>();
            if (query != null) for (String part : query.split("&")) {
                String[] pair = part.split("=", 2);
                String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
                if (parameters.putIfAbsent(key, pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "") != null) throw new IllegalArgumentException("查询参数重复。");
            }
            LocalDate date = parameters.containsKey("date") ? LocalDate.parse(parameters.get("date")) : LocalDate.now();
            LocalDate from = parameters.containsKey("from") ? LocalDate.parse(parameters.get("from")) : null;
            LocalDate to = parameters.containsKey("to") ? LocalDate.parse(parameters.get("to")) : null;
            Map<String, Object> state = service.state(date, from, to); state.put("startup", desktop.startup()); state.put("startupAvailable", desktop.startupAvailable());
            json(exchange, 200, state); return;
        }
        if (path.equals("/api/memos") && exchange.getRequestMethod().equals("GET")) { json(exchange, 200, service.memoState()); return; }
        if (!exchange.getRequestMethod().equals("POST")) { json(exchange, 405, Map.of("error", "不支持的请求方式")); return; }
        if (body == null) body = readBody(exchange);
        switch (path) {
            case "/api/action" -> json(exchange, 200, service.action(body));
            case "/api/memos" -> json(exchange, 200, service.memoAction(body));
            case "/api/native" -> {
                String command = TodoService.text(body, "command", 50);
                switch (command) {
                    case "folder" -> desktop.openFolder(TodoService.text(body, "name", 30));
                    case "startup" -> desktop.startup(TodoService.flag(body, "enabled", false));
                    case "reminder" -> desktop.reminder(List.of("这是一条测试提醒。关闭 Vue 窗口后，Java 后台仍可提醒。"));
                    default -> throw new IllegalArgumentException("未知桌面操作。");
                }
                json(exchange, 200, Map.of("ok", true));
            }
            case "/api/window" -> {
                long now = System.currentTimeMillis(); String windowId = TodoService.text(body, "windowId", 100);
                windows.entrySet().removeIf(entry -> now - entry.getValue() > 180000);
                if ("closed".equals(body.get("event"))) windows.remove(windowId); else windows.put(windowId, now);
                if (body.containsKey("text")) service.closingNote(body);
                if (body.containsKey("memo")) service.closingMemo(TodoService.object(body.get("memo")));
                if ("closed".equals(body.get("event")) && !service.copy().closeToTray) {
                    worker.schedule(() -> { if (!closed && windows.isEmpty() && !service.copy().closeToTray) stop.run(); }, 4, TimeUnit.SECONDS);
                }
                json(exchange, 200, Map.of("ok", true));
            }
            case "/api/exit" -> { json(exchange, 200, Map.of("ok", true)); worker.schedule(stop, 300, TimeUnit.MILLISECONDS); }
            default -> json(exchange, 404, Map.of("error", "接口不存在"));
        }
    }
    private static Map<String, Object> readBody(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readNBytes(16 * 1024 * 1024 + 1);
        if (bytes.length > 16 * 1024 * 1024) throw new IllegalArgumentException("请求超过 16 MB。");
        return TodoService.object(Json.parse(new String(bytes, StandardCharsets.UTF_8)));
    }
    private static void asset(HttpExchange exchange, String path) throws IOException {
        if (!exchange.getRequestMethod().equals("GET") && !exchange.getRequestMethod().equals("HEAD")) { json(exchange, 405, Map.of("error", "请求方式不支持")); return; }
        if (path.equals("/")) path = "/index.html";
        if (path.contains("..") || path.contains("\\") || path.contains("\0")) { json(exchange, 404, Map.of("error", "文件不存在")); return; }
        try (InputStream resource = WebServer.class.getResourceAsStream("/web" + path)) {
            if (resource == null) { json(exchange, 404, Map.of("error", "文件不存在")); return; }
            byte[] bytes = resource.readAllBytes(); String type = path.endsWith(".html") ? "text/html; charset=utf-8" : path.endsWith(".js") ? "text/javascript; charset=utf-8" : path.endsWith(".css") ? "text/css; charset=utf-8" : path.endsWith(".svg") ? "image/svg+xml" : "application/octet-stream";
            exchange.getResponseHeaders().set("Content-Type", type);
            exchange.sendResponseHeaders(200, exchange.getRequestMethod().equals("HEAD") ? -1 : bytes.length);
            if (!exchange.getRequestMethod().equals("HEAD")) exchange.getResponseBody().write(bytes);
        }
    }
    private static void json(HttpExchange exchange, int status, Object result) throws IOException {
        byte[] bytes = Json.write(result).getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes);
    }
    private static void log(Exception e) { try { Files.writeString(AppPaths.LOGS.resolve("error.log"), LocalDateTime.now() + " " + e + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND); } catch (IOException ignored) { } }
    @Override public void close() {
        if (closed) return; closed = true; server.stop(0); worker.shutdownNow(); requests.close(); desktop.close();
        try { if (Files.exists(infoFile)) { Map<String, Object> info = TodoService.object(Json.parse(Files.readString(infoFile))); if (((Number)info.get("pid")).longValue() == ProcessHandle.current().pid()) Files.delete(infoFile); } }
        catch (Exception ignored) { }
    }
}
