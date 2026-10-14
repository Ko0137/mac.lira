package com.example.lira.desktop;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class ApplicationLauncher {
    boolean open(String requestedName) throws IOException, InterruptedException {
        String query = requestedName.trim();
        if (query.isEmpty()) {
            return false;
        }
        List<String> searchTerms = aliasesFor(query);
        String encodedTerms = Base64.getEncoder().encodeToString(
                String.join("|", searchTerms).getBytes(StandardCharsets.UTF_8));
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return openWindows(encodedTerms);
        }
        if (os.contains("mac")) {
            return openMac(searchTerms);
        }
        return openLinux(searchTerms);
    }

    private boolean openWindows(String encodedTerms)
            throws IOException, InterruptedException {
        String script = "$terms=[Text.Encoding]::UTF8.GetString("
                + "[Convert]::FromBase64String('" + encodedTerms + "')).Split('|');"
                + "$terms=@($terms | ForEach-Object {($_.Normalize("
                + "[Text.NormalizationForm]::FormD) -replace '\\p{Mn}','').ToLowerInvariant()});"
                + "$apps=Get-StartApps | Where-Object { "
                + "$a=($_.Name.Normalize([Text.NormalizationForm]::FormD) -replace "
                + "'\\p{Mn}','').ToLowerInvariant(); "
                + "$terms | Where-Object { $_ -and $a.Contains($_) } | Select-Object -First 1 };"
                + "if($apps){$app=$apps | Sort-Object { $_.Name.Length } | Select-Object -First 1;"
                + "Start-Process -FilePath explorer.exe -ArgumentList ('shell:AppsFolder\\'+$app.AppID);"
                + "Write-Output ('OPENED|'+$app.Name); exit 0};"
                + "$dirs=@($env:ProgramData+'\\Microsoft\\Windows\\Start Menu\\Programs',"
                + "$env:APPDATA+'\\Microsoft\\Windows\\Start Menu\\Programs');"
                + "$links=foreach($d in $dirs){if(Test-Path -LiteralPath $d){"
                + "Get-ChildItem -LiteralPath $d -Filter *.lnk -File -Recurse -ErrorAction SilentlyContinue}};"
                + "$link=$links | Where-Object { $name=$_.BaseName.ToLowerInvariant();"
                + "$terms | Where-Object { $_ -and $name.Contains($_) } "
                + "| Select-Object -First 1 } "
                + "| Sort-Object { $_.BaseName.Length } | Select-Object -First 1;"
                + "if($link){Start-Process -FilePath $link.FullName;"
                + "Write-Output ('OPENED|'+$link.BaseName); exit 0};"
                + "$paths=@('HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\App Paths\\*',"
                + "'HKLM:\\Software\\Microsoft\\Windows\\CurrentVersion\\App Paths\\*',"
                + "'HKLM:\\Software\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\App Paths\\*');"
                + "foreach($p in $paths){$hit=Get-ItemProperty $p -ErrorAction SilentlyContinue "
                + "| Where-Object { $name=$_.PSChildName.ToLowerInvariant();"
                + "$terms | Where-Object { $_ -and $name.Contains($_) } "
                + "| Select-Object -First 1 } "
                + "| Select-Object -First 1; if($hit){$key=Get-Item -LiteralPath $hit.PSPath;"
                + "$exe=$key.GetValue('');if(!$exe){$exe=$key.GetValue('Path')};"
                + "if($exe -and (Test-Path -LiteralPath $exe)){"
                + "Start-Process -FilePath $exe;Write-Output ('OPENED|'+$hit.PSChildName);exit 0}}};"
                + "Write-Output 'NOT_FOUND'; exit 3";
        Process process = new ProcessBuilder("powershell.exe", "-NoLogo", "-NoProfile",
                "-NonInteractive", "-WindowStyle", "Hidden", "-EncodedCommand",
                Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE)))
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        return exitCode == 0 && output.contains("OPENED|");
    }

    private List<String> aliasesFor(String query) {
        String normalized = query.toLowerCase(Locale.ROOT).trim()
                .replaceFirst("^(приложение|application)\\s+", "")
                .replaceFirst("\\s+(приложение|application|app)$", "");
        return switch (normalized) {
            case "телеграм", "телеграмм", "telegram" -> List.of("телеграм", "telegram");
            case "яндекс музыка", "яндекс музыку", "yandex музыка", "yandex музыку",
                    "yandex music", "музыка" ->
                    List.of("яндекс музыка", "yandex music", "spotify");
            case "гугл хром", "google chrome", "chrome", "хром" ->
                    List.of("google chrome", "chrome");
            case "калькулятор", "calculator" -> List.of("калькулятор", "calculator");
            case "блокнот", "notepad" -> List.of("блокнот", "notepad");
            case "проводник", "explorer", "file explorer" ->
                    List.of("проводник", "explorer", "file explorer");
            case "дискорд", "discord" -> List.of("дискорд", "discord");
            case "стим", "steam" -> List.of("стим", "steam");
            default -> List.of(normalized);
        };
    }

    private boolean openMac(List<String> searchTerms) throws IOException, InterruptedException {
        for (String term : searchTerms) {
            Process process = new ProcessBuilder("open", "-a", term)
                    .redirectErrorStream(true).start();
            if (process.waitFor() == 0) {
                return true;
            }
        }
        return false;
    }

    private boolean openLinux(List<String> searchTerms) throws IOException, InterruptedException {
        List<Path> applicationDirectories = new ArrayList<>();
        applicationDirectories.add(Path.of(System.getProperty("user.home"),
                ".local", "share", "applications"));
        applicationDirectories.add(Path.of("/usr/share/applications"));
        applicationDirectories.add(Path.of("/usr/local/share/applications"));
        for (Path directory : applicationDirectories) {
            if (!Files.isDirectory(directory)) {
                continue;
            }
            try (var desktopFiles = Files.walk(directory, 5)) {
                var match = desktopFiles.filter(path -> path.toString().endsWith(".desktop"))
                        .filter(path -> {
                            try {
                                return Files.readString(path).lines()
                                        .filter(line -> line.startsWith("Name=")
                                                || line.startsWith("Name["))
                                        .anyMatch(line -> searchTerms.stream().anyMatch(term ->
                                                line.substring(line.indexOf('=') + 1)
                                                        .toLowerCase(Locale.ROOT).contains(term)));
                            } catch (IOException exception) {
                                return false;
                            }
                        }).findFirst();
                if (match.isPresent()) {
                    String id = directory.relativize(match.get()).toString()
                            .replace('\\', '/').replaceAll("\\.desktop$", "");
                    return new ProcessBuilder("gtk-launch", id).start().waitFor() == 0;
                }
            }
        }
        for (String term : searchTerms) {
            Process probe = new ProcessBuilder("sh", "-lc", "command -v \"$1\"", "lira", term)
                    .redirectErrorStream(true).start();
            String executable = new String(probe.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8).trim();
            if (probe.waitFor() == 0 && !executable.isEmpty()) {
                return new ProcessBuilder(executable).start().isAlive();
            }
        }
        return false;
    }
}
