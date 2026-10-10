package personal.todo;

import java.io.*;
import java.nio.charset.*;

final class Startup {
    private static final String KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    static boolean available() { return System.getProperty("jpackage.app-path") != null; }
    static boolean enabled() {
        if (!available()) return false;
        try { Process p = new ProcessBuilder("reg", "query", KEY, "/v", "PersonalTodo").redirectErrorStream(true).start(); p.getInputStream().readAllBytes(); return p.waitFor() == 0; }
        catch (Exception e) { return false; }
    }
    static void set(boolean enabled) throws IOException {
        String executable = System.getProperty("jpackage.app-path");
        if (executable == null) throw new IOException("请从便携版 PersonalTodo.exe 中设置开机启动。");
        ProcessBuilder builder = enabled ? new ProcessBuilder("reg", "add", KEY, "/v", "PersonalTodo", "/t", "REG_SZ", "/d", "\"" + executable + "\" --minimized", "/f")
            : new ProcessBuilder("reg", "delete", KEY, "/v", "PersonalTodo", "/f");
        try {
            Process p = builder.redirectErrorStream(true).start(); byte[] output = p.getInputStream().readAllBytes();
            if (p.waitFor() != 0) throw new IOException("无法修改开机启动：" + new String(output, Charset.defaultCharset()));
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
    }
}
