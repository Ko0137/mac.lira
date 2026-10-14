package com.example.lira.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteCommandHandlerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void remoteFileOpeningAllowsDocumentsAndFoldersButNotExecutables() throws IOException {
        Path document = Files.createFile(temporaryDirectory.resolve("notes.txt"));
        Path executable = Files.createFile(temporaryDirectory.resolve("payload.exe"));
        Path folder = Files.createDirectory(temporaryDirectory.resolve("documents"));

        assertTrue(RemoteCommandHandler.canOpen(document));
        assertTrue(RemoteCommandHandler.canOpen(folder));
        assertFalse(RemoteCommandHandler.canOpen(executable));
    }

    @Test
    void unknownRemoteActionsAreRejected() {
        RemoteCommandHandler handler = new RemoteCommandHandler(new ApplicationLauncher());

        RemoteCommandHandler.Result result = handler.execute("run_process", "notepad");

        assertFalse(result.success());
        assertTrue(result.message().contains("Неизвестный"));
    }

    @Test
    void emptyAndOversizedQueriesAreRejected() {
        RemoteCommandHandler handler = new RemoteCommandHandler(new ApplicationLauncher());

        assertFalse(handler.execute("search", "  ").success());
        assertFalse(handler.execute("search", "x".repeat(201)).success());
    }
}
