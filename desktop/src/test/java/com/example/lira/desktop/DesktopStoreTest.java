package com.example.lira.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.LocalDate;
import com.google.gson.JsonObject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void storesFinanceGoalsNotesAndChatInAndroidCompatibleSchema() throws Exception {
        Path database = temporaryDirectory.resolve("lira_offline.db");
        try (DesktopStore store = new DesktopStore(temporaryDirectory)) {
            store.addTransaction("Зарплата", 250_000, true, "Зарплата");
            store.addTransaction("Продукты", 7_500, false, "Покупки");
            store.addGoal("Отпуск", LocalDate.of(2026, 1, 1),
                    LocalDate.of(2026, 12, 31), "год");
            store.addNote("Идеи", "Проверка заметки");
            store.saveMessage("Привет", true);

            assertEquals(2, store.transactions().size());
            assertEquals(1, store.goals().size());
            assertEquals(1, store.notes().size());
            assertEquals(1, store.messages().size());
            assertEquals(250_000, store.monthlyTotals(
                    LocalDate.now().toString().substring(0, 7))[0]);
        }

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement();
             var result = statement.executeQuery("PRAGMA user_version")) {
            assertTrue(result.next());
            assertEquals(6, result.getInt(1));
        }

        assertTrue(database.toFile().isFile());
    }

    @Test
    void deletesGoalAndNote() throws Exception {
        try (DesktopStore store = new DesktopStore(temporaryDirectory)) {
            store.addGoal("Цель", LocalDate.now(), LocalDate.now().plusDays(1), "день");
            store.addNote("Черновик", "Удалить");

            store.deleteGoal(store.goals().get(0).id());
            store.deleteNote(store.notes().get(0).id());

            assertTrue(store.goals().isEmpty());
            assertTrue(store.notes().isEmpty());
        }
    }

    @Test
    void synchronizationMergesRowsUsingLatestRevisionAndTombstones() throws Exception {
        Path leftPath = temporaryDirectory.resolve("left");
        Path rightPath = temporaryDirectory.resolve("right");
        try (DesktopStore left = new DesktopStore(leftPath);
             DesktopStore right = new DesktopStore(rightPath)) {
            left.addTransaction("Обед", 1_500, false, "Кафе и еда");
            JsonObject firstSnapshot = left.syncSnapshot();
            right.mergeSyncSnapshot(firstSnapshot);
            assertEquals("Обед", right.transactions().get(0).title());

            long transactionId = right.transactions().get(0).id();
            right.updateTransaction(transactionId, "Ужин", 2_500, false, LocalDate.now(),
                    "Кафе и еда");
            left.mergeSyncSnapshot(right.syncSnapshot());
            assertEquals("Ужин", left.transactions().get(0).title());

            right.deleteTransaction(transactionId);
            left.mergeSyncSnapshot(right.syncSnapshot());
            assertTrue(left.transactions().isEmpty());
        }
    }

    @Test
    void migratesLegacyIdsAndKeepsGoalReferencesForFirstSync() throws Exception {
        Path database = temporaryDirectory.resolve("lira_offline.db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("CREATE TABLE chat_messages (id INTEGER PRIMARY KEY, "
                    + "message TEXT NOT NULL, is_user INTEGER NOT NULL, time TEXT NOT NULL)");
            statement.execute("CREATE TABLE transactions (id INTEGER PRIMARY KEY, "
                    + "title TEXT NOT NULL, amount_cents INTEGER NOT NULL, type INTEGER NOT NULL, "
                    + "date TEXT NOT NULL, category TEXT NOT NULL DEFAULT '')");
            statement.execute("CREATE TABLE goals (id INTEGER PRIMARY KEY, title TEXT NOT NULL, "
                    + "target_date TEXT NOT NULL, start_date TEXT NOT NULL DEFAULT '', "
                    + "term TEXT NOT NULL DEFAULT 'day')");
            statement.execute("CREATE TABLE planner_tasks (id INTEGER PRIMARY KEY, "
                    + "title TEXT NOT NULL, task_date TEXT NOT NULL, task_time TEXT NOT NULL, "
                    + "goal_id INTEGER REFERENCES goals(id), is_done INTEGER NOT NULL DEFAULT 0)");
            statement.execute("CREATE TABLE goal_progress (goal_id INTEGER NOT NULL, "
                    + "progress_date TEXT NOT NULL, note TEXT NOT NULL DEFAULT '', "
                    + "PRIMARY KEY(goal_id, progress_date), FOREIGN KEY(goal_id) "
                    + "REFERENCES goals(id) ON DELETE CASCADE)");
            statement.execute("INSERT INTO chat_messages VALUES (1,'Привет',1,'12:00')");
            statement.execute("INSERT INTO transactions VALUES "
                    + "(1,'Обед',500,-1,'2026-01-01','Еда')");
            statement.execute("INSERT INTO goals VALUES (1,'Цель','2026-12-31','2026-01-01','год')");
            statement.execute("INSERT INTO planner_tasks VALUES "
                    + "(1,'Шаг','2026-01-01','09:00',1,0)");
            statement.execute("INSERT INTO goal_progress VALUES (1,'2026-01-01','Готово')");
        }

        try (DesktopStore store = new DesktopStore(temporaryDirectory)) {
            long goalId = store.goals().get(0).id();
            long taskId = store.tasks(LocalDate.of(2026, 1, 1)).get(0).id();
            assertTrue(goalId > 1_000);
            assertTrue(taskId > 1_000);
            JsonObject snapshot = store.syncSnapshot();
            assertTrue(snapshot.getAsJsonArray("records").size() >= 5);
            assertTrue(snapshot.getAsJsonArray("records").asList().stream()
                    .allMatch(record -> record.getAsJsonObject().get("modifiedAt").getAsLong() > 0));
        }

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement();
             var rows = statement.executeQuery(
                     "SELECT g.id,t.goal_id,p.goal_id FROM goals g "
                             + "JOIN planner_tasks t ON t.goal_id=g.id "
                             + "JOIN goal_progress p ON p.goal_id=g.id")) {
            assertTrue(rows.next());
            assertEquals(rows.getLong(1), rows.getLong(2));
            assertEquals(rows.getLong(1), rows.getLong(3));
        }
    }
}
