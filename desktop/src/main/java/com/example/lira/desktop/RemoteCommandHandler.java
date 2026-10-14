package com.example.lira.desktop;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

final class RemoteCommandHandler {
    private static final int MAX_QUERY_LENGTH = 200;
    private static final int MAX_VISITED_ENTRIES = 100_000;
    private static final Duration SEARCH_TIMEOUT = Duration.ofSeconds(20);
    private static final List<String> WINDOWS_SYSTEM_DIRECTORIES = List.of(
            "windows", "program files", "program files (x86)", "programdata",
            "$recycle.bin", "system volume information", "windowsapps");
    private static final List<String> OPENABLE_EXTENSIONS = List.of(
            ".txt", ".rtf", ".pdf", ".doc", ".docx", ".odt",
            ".xls", ".xlsx", ".ods", ".csv", ".ppt", ".pptx", ".odp",
            ".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp",
            ".mp3", ".wav", ".m4a", ".ogg", ".mp4", ".mov", ".avi", ".mkv");

    private final ApplicationLauncher applicationLauncher;

    RemoteCommandHandler(ApplicationLauncher applicationLauncher) {
        this.applicationLauncher = applicationLauncher;
    }

    Result execute(String action, String requested) {
        String query = requested == null ? "" : requested.trim();
        if (query.isEmpty() || query.length() > MAX_QUERY_LENGTH
                || query.chars().anyMatch(Character::isISOControl)) {
            return new Result(false, "Команда пустая или слишком длинная.");
        }

        try {
            return switch (action) {
                case "launch_app" -> launchApplication(query);
                case "open" -> openApplicationOrFile(query);
                case "search" -> searchWeb(query);
                default -> new Result(false, "Неизвестный тип команды.");
            };
        } catch (IOException exception) {
            return new Result(false, "Не удалось открыть запрошенный элемент: "
                    + exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new Result(false, "Операция прервана.");
        }
    }

    private Result launchApplication(String name) throws IOException, InterruptedException {
        return applicationLauncher.open(name)
                ? new Result(true, "Запускаю на ПК «" + name + "».")
                : new Result(false, "Установленное приложение «" + name + "» не найдено.");
    }

    private Result openApplicationOrFile(String name) throws IOException, InterruptedException {
        if (applicationLauncher.open(name)) {
            return new Result(true, "Открываю на ПК «" + name + "».");
        }
        Path match = findFile(name);
        if (match == null) {
            return new Result(false, "Не нашла приложение, документ или папку «" + name
                    + "» на ПК.");
        }
        if (!Desktop.isDesktopSupported()
                || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            return new Result(false, "Открытие файлов этой системой не поддерживается.");
        }
        Desktop.getDesktop().open(match.toFile());
        return new Result(true, "Открываю на ПК: " + match.getFileName() + ".");
    }

    private Result searchWeb(String query) throws IOException {
        if (!Desktop.isDesktopSupported()
                || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            return new Result(false, "Системный браузер на ПК недоступен.");
        }
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        Desktop.getDesktop().browse(URI.create("https://www.google.com/search?q=" + encoded));
        return new Result(true, "Открыла поиск в браузере на ПК: " + query);
    }

    private Path findFile(String query) throws IOException {
        String normalized = query.toLowerCase(Locale.ROOT);
        List<Path> exactMatches = new ArrayList<>();
        List<Path> partialMatches = new ArrayList<>();
        SearchState state = new SearchState(System.nanoTime()
                + SEARCH_TIMEOUT.toNanos(), normalized, exactMatches, partialMatches);

        for (Path root : Path.of("").getFileSystem().getRootDirectories()) {
            if (state.limitReached) {
                break;
            }
            try {
                Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), 20, state);
            } catch (AmbiguousMatch exception) {
                throw exception;
            } catch (SearchLimitReached ignored) {
                state.limitReached = true;
            } catch (IOException | SecurityException ignored) {
                // Unreadable folders and removable drives must not stop the search elsewhere.
            }
        }

        List<Path> matches = exactMatches.isEmpty() ? partialMatches : exactMatches;
        if (matches.size() == 1) {
            return matches.get(0);
        }
        if (matches.size() > 1) {
            throw new IOException("Найдено несколько совпадений. Уточни название или расширение.");
        }
        if (state.limitReached) {
            throw new IOException("Поиск по дискам занял слишком много времени. Уточни название.");
        }
        return null;
    }

    static boolean canOpen(Path path) {
        if (Files.isDirectory(path)) {
            return true;
        }
        if (!Files.isRegularFile(path)) {
            return false;
        }
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return OPENABLE_EXTENSIONS.stream().anyMatch(name::endsWith);
    }

    private static boolean isWindowsSystemDirectory(Path directory) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("win") || directory.getParent() == null
                || directory.getParent().getParent() != null) {
            return false;
        }
        String name = directory.getFileName().toString().toLowerCase(Locale.ROOT);
        return WINDOWS_SYSTEM_DIRECTORIES.contains(name);
    }

    record Result(boolean success, String message) {
    }

    private static final class SearchState implements FileVisitor<Path> {
        private final long deadline;
        private final String query;
        private final List<Path> exactMatches;
        private final List<Path> partialMatches;
        private int visitedEntries;
        private boolean limitReached;

        private SearchState(long deadline, String query, List<Path> exactMatches,
                            List<Path> partialMatches) {
            this.deadline = deadline;
            this.query = query;
            this.exactMatches = exactMatches;
            this.partialMatches = partialMatches;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                throws IOException {
            if (attributes.isSymbolicLink() || isWindowsSystemDirectory(directory)) {
                return FileVisitResult.SKIP_SUBTREE;
            }
            inspect(directory);
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                throws IOException {
            if (!attributes.isSymbolicLink()) {
                inspect(file);
            }
            return FileVisitResult.CONTINUE;
        }

        private void inspect(Path path) throws IOException {
            visitedEntries++;
            if (visitedEntries > MAX_VISITED_ENTRIES || System.nanoTime() > deadline) {
                limitReached = true;
                throw new SearchLimitReached();
            }
            Path fileName = path.getFileName();
            if (fileName == null || !canOpen(path)) {
                return;
            }
            String name = fileName.toString().toLowerCase(Locale.ROOT);
            String withoutExtension = name.replaceFirst("\\.[^.]+$", "");
            if (name.equals(query) || withoutExtension.equals(query)) {
                exactMatches.add(path);
            } else if (query.length() >= 3 && name.contains(query)
                    && partialMatches.size() < 20) {
                partialMatches.add(path);
            }
            if (exactMatches.size() > 1) {
                throw new AmbiguousMatch();
            }
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exception) {
            return FileVisitResult.SKIP_SUBTREE;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path directory, IOException exception) {
            return FileVisitResult.CONTINUE;
        }
    }

    private static final class SearchLimitReached extends IOException {
    }

    private static final class AmbiguousMatch extends IOException {
        private AmbiguousMatch() {
            super("Найдено несколько совпадений. Уточни название или расширение.");
        }
    }
}
