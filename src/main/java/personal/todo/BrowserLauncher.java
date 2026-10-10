package personal.todo;

import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Browser app window with an independent profile inside the application home. */
final class BrowserLauncher {
    private static final Path PROFILE = AppPaths.ROOT.resolve("runtime/browser-profile");
    static void open(String url) throws IOException {
        Path executable = null;
        String configured = System.getenv("PERSONAL_TODO_BROWSER");
        if (configured != null && !configured.isBlank()) {
            executable = Path.of(configured);
            if (!Files.isRegularFile(executable)) throw new IOException("PERSONAL_TODO_BROWSER 指向的浏览器不存在：" + executable);
        } else for (String variable : List.of("ProgramFiles(x86)", "ProgramFiles", "LOCALAPPDATA")) {
            String base = System.getenv(variable); if (base == null || base.isBlank()) continue;
            for (String browser : List.of("Microsoft/Edge/Application/msedge.exe", "Google/Chrome/Application/chrome.exe")) {
                Path candidate = Path.of(base).resolve(browser);
                if (Files.isRegularFile(candidate)) { executable = candidate; break; }
            }
            if (executable != null) break;
        }
        if (executable == null) throw new IOException("未找到 Microsoft Edge 或 Chrome。请先安装其中一个浏览器。");
        Files.createDirectories(PROFILE); Path cache = AppPaths.ROOT.resolve("runtime/browser-cache"); Files.createDirectories(cache);
        ProcessBuilder process = new ProcessBuilder(executable.toString(), "--app=" + url, "--user-data-dir=" + PROFILE,
            "--disk-cache-dir=" + cache, "--no-first-run", "--no-default-browser-check", "--disable-background-mode", "--disable-sync",
            "--disable-extensions", "--disable-default-apps", "--disable-component-extensions-with-background-pages", "--disable-background-networking",
            "--disable-breakpad", "--disable-crash-reporter", "--disable-component-update", "--window-size=1160,820");
        process.environment().put("TEMP", AppPaths.TEMP.toString()); process.environment().put("TMP", AppPaths.TEMP.toString());
        process.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
    }
    static void closeOwnedWindow() {
        // Windows ProcessHandle does not expose other processes' arguments. Query CIM, match the
        // exact dedicated profile, and recheck creation time before stopping any remaining process.
        String script = """
            $ErrorActionPreference = 'Stop'
            $ProgressPreference = 'SilentlyContinue'
            $escaped = [regex]::Escape($env:PERSONAL_TODO_BROWSER_PROFILE)
            $pattern = '--user-data-dir=(?:"' + $escaped + '"|' + $escaped + '(?=\\s|$))'
            $owned = @(Get-CimInstance Win32_Process -Filter "Name = 'msedge.exe' OR Name = 'chrome.exe'" | Where-Object { $_.CommandLine -match $pattern })
            foreach ($entry in $owned) {
                try {
                    $item = Get-Process -Id $entry.ProcessId -ErrorAction SilentlyContinue
                    if ($item -and $item.MainWindowHandle -ne 0) { [void]$item.CloseMainWindow() }
                } catch { } # Another browser process may already have closed this window.
            }
            if ($owned.Count -gt 0) { Start-Sleep -Milliseconds 800 }
            foreach ($entry in $owned) {
                $current = Get-CimInstance Win32_Process -Filter ('ProcessId = ' + $entry.ProcessId)
                if ($current -and $current.CreationDate -eq $entry.CreationDate -and $current.CommandLine -match $pattern) {
                    Stop-Process -Id $entry.ProcessId -Force -ErrorAction SilentlyContinue
                }
            }
            """;
        try {
            String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
            ProcessBuilder builder = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-EncodedCommand", encoded);
            builder.environment().put("PERSONAL_TODO_BROWSER_PROFILE", PROFILE.toString());
            builder.environment().put("TEMP", AppPaths.TEMP.toString()); builder.environment().put("TMP", AppPaths.TEMP.toString());
            Path shellCache = AppPaths.ROOT.resolve("runtime/powershell"); Files.createDirectories(shellCache);
            builder.environment().put("PSModuleAnalysisCachePath", shellCache.resolve("ModuleAnalysisCache").toString());
            Process process = builder.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(AppPaths.LOGS.resolve("browser-exit.log").toFile()).start();
            if (!process.waitFor(8, TimeUnit.SECONDS)) process.destroyForcibly();
        } catch (IOException e) { System.err.println("Unable to close application browser: " + e); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
