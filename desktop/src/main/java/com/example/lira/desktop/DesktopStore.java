package com.example.lira.desktop;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

final class DesktopStore implements AutoCloseable {
    private final Connection connection;

    DesktopStore() throws SQLException {
        this(Path.of(System.getProperty("user.home"), ".lira"));
    }

    private String revisionOrigin(String table, String key) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT origin_id FROM sync_revisions WHERE table_name=? AND record_key=?")) {
            query.setString(1, table);
            query.setString(2, key);
            try (ResultSet row = query.executeQuery()) {
                return row.next() ? row.getString(1) : "";
            }
        }
    }

    DesktopStore(Path directory) throws SQLException {
        try {
            java.nio.file.Files.createDirectories(directory);
        } catch (java.io.IOException exception) {
            throw new SQLException("Не удалось создать папку данных L.I.R.A.", exception);
        }
        connection = DriverManager.getConnection(
                "jdbc:sqlite:" + directory.resolve("lira_offline.db"));
        initialize();
    }

    private void initialize() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("CREATE TABLE IF NOT EXISTS chat_messages ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, message TEXT NOT NULL, "
                    + "is_user INTEGER NOT NULL, time TEXT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS transactions ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, "
                    + "amount_cents INTEGER NOT NULL, type INTEGER NOT NULL, "
                    + "date TEXT NOT NULL, category TEXT NOT NULL DEFAULT '')");
            statement.execute("CREATE TABLE IF NOT EXISTS goals ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, "
                    + "target_date TEXT NOT NULL, start_date TEXT NOT NULL DEFAULT '', "
                    + "term TEXT NOT NULL DEFAULT 'day')");
            statement.execute("CREATE TABLE IF NOT EXISTS planner_tasks ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, "
                    + "task_date TEXT NOT NULL, task_time TEXT NOT NULL, goal_id INTEGER, "
                    + "is_done INTEGER NOT NULL DEFAULT 0, "
                    + "FOREIGN KEY(goal_id) REFERENCES goals(id) ON DELETE CASCADE)");
            statement.execute("CREATE TABLE IF NOT EXISTS voice_notes ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, "
                    + "content TEXT NOT NULL, created_at TEXT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS finance_budgets ("
                    + "category TEXT PRIMARY KEY, monthly_limit_cents INTEGER NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS quick_income_categories ("
                    + "name TEXT PRIMARY KEY, is_deleted INTEGER NOT NULL DEFAULT 0)");
            statement.execute("CREATE TABLE IF NOT EXISTS daily_records ("
                    + "entry_date TEXT PRIMARY KEY, effort TEXT NOT NULL DEFAULT 'NORMAL', "
                    + "energy TEXT NOT NULL DEFAULT 'NORMAL', "
                    + "development_note TEXT NOT NULL DEFAULT '', diary TEXT NOT NULL DEFAULT '', "
                    + "rest_activity TEXT NOT NULL DEFAULT '')");
            statement.execute("CREATE TABLE IF NOT EXISTS daily_entries ("
                    + "entry_date TEXT NOT NULL, block_id TEXT NOT NULL, "
                    + "is_done INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(entry_date, block_id))");
            statement.execute("CREATE TABLE IF NOT EXISTS goal_progress ("
                    + "goal_id INTEGER NOT NULL, progress_date TEXT NOT NULL, "
                    + "note TEXT NOT NULL DEFAULT '', PRIMARY KEY(goal_id, progress_date), "
                    + "FOREIGN KEY(goal_id) REFERENCES goals(id) ON DELETE CASCADE)");
            statement.execute("CREATE TABLE IF NOT EXISTS planner_settings ("
                    + "setting_key TEXT PRIMARY KEY, setting_value TEXT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS planner_blocks ("
                    + "block_id TEXT PRIMARY KEY, group_id TEXT NOT NULL, "
                    + "group_title TEXT NOT NULL, group_caption TEXT NOT NULL, "
                    + "title TEXT NOT NULL, subtitle TEXT NOT NULL DEFAULT '', "
                    + "group_position INTEGER NOT NULL, position INTEGER NOT NULL, "
                    + "is_deleted INTEGER NOT NULL DEFAULT 0)");
            statement.execute("CREATE TABLE IF NOT EXISTS sync_revisions ("
                    + "table_name TEXT NOT NULL, record_key TEXT NOT NULL, "
                    + "modified_at INTEGER NOT NULL, origin_id TEXT NOT NULL DEFAULT '', "
                    + "is_deleted INTEGER NOT NULL DEFAULT 0, "
                    + "PRIMARY KEY(table_name,record_key))");
            statement.execute("CREATE TABLE IF NOT EXISTS sync_device (device_id TEXT NOT NULL)");
            statement.execute("PRAGMA user_version = 6");
        }
        try (PreparedStatement device = connection.prepareStatement(
                "INSERT INTO sync_device(device_id) SELECT ? WHERE NOT EXISTS "
                        + "(SELECT 1 FROM sync_device)")) {
            device.setString(1, java.util.UUID.randomUUID().toString());
            device.executeUpdate();
        }
        if (revisionCount() == 0) {
            remapLegacyIds();
        }
        createSyncTriggers();
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT OR IGNORE INTO quick_income_categories(name) VALUES (?)")) {
            for (String name : List.of("Зарплата", "Инвестиции")) {
                insert.setString(1, name);
                insert.addBatch();
            }
            insert.executeBatch();
        }
        ensurePlannerDefaults();
    }

    private long revisionCount() throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM sync_revisions")) {
            return result.next() ? result.getLong(1) : 0;
        }
    }

    private void remapLegacyIds() throws SQLException {
        for (String table : List.of("chat_messages", "transactions", "planner_tasks",
                "voice_notes")) {
            remapIds(table);
        }
        List<Long> goalIds = new ArrayList<>();
        try (Statement query = connection.createStatement();
             ResultSet rows = query.executeQuery("SELECT id FROM goals ORDER BY id")) {
            while (rows.next()) {
                goalIds.add(rows.getLong(1));
            }
        }
        for (long oldId : goalIds) {
            long newId = uniqueId();
            try (PreparedStatement copy = connection.prepareStatement(
                    "INSERT INTO goals(id,title,target_date,start_date,term) "
                            + "SELECT ?,title,target_date,start_date,term FROM goals WHERE id=?")) {
                copy.setLong(1, newId);
                copy.setLong(2, oldId);
                copy.executeUpdate();
            }
            for (String table : List.of("planner_tasks", "goal_progress")) {
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE " + table + " SET goal_id=? WHERE goal_id=?")) {
                    update.setLong(1, newId);
                    update.setLong(2, oldId);
                    update.executeUpdate();
                }
            }
            try (PreparedStatement remove = connection.prepareStatement(
                    "DELETE FROM goals WHERE id=?")) {
                remove.setLong(1, oldId);
                remove.executeUpdate();
            }
        }
    }

    private void remapIds(String table) throws SQLException {
        List<Long> ids = new ArrayList<>();
        try (Statement query = connection.createStatement();
             ResultSet rows = query.executeQuery("SELECT id FROM \"" + table + "\" ORDER BY id")) {
            while (rows.next()) {
                ids.add(rows.getLong(1));
            }
        }
        for (long id : ids) {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE \"" + table + "\" SET id=? WHERE id=?")) {
                update.setLong(1, uniqueId());
                update.setLong(2, id);
                update.executeUpdate();
            }
        }
    }

    private void createSyncTriggers() throws SQLException {
        Map<String, String> keys = Map.ofEntries(
                Map.entry("chat_messages", "id"),
                Map.entry("transactions", "id"),
                Map.entry("goals", "id"),
                Map.entry("planner_tasks", "id"),
                Map.entry("finance_budgets", "category"),
                Map.entry("quick_income_categories", "name"),
                Map.entry("daily_records", "entry_date"),
                Map.entry("daily_entries", "entry_date || char(31) || block_id"),
                Map.entry("goal_progress", "goal_id || char(31) || progress_date"),
                Map.entry("planner_settings", "setting_key"),
                Map.entry("planner_blocks", "block_id"),
                Map.entry("voice_notes", "id"));
        try (Statement statement = connection.createStatement()) {
            for (Map.Entry<String, String> entry : keys.entrySet()) {
                String table = entry.getKey();
                String key = entry.getValue();
                for (String action : List.of("INSERT", "UPDATE", "DELETE")) {
                    String alias = action.equals("DELETE") ? "OLD" : "NEW";
                    String trigger = "sync_" + table + "_" + action.toLowerCase(Locale.ROOT);
                    String recordKey = "CAST(" + key.replaceAll("\\b(id|name|entry_date|block_id|goal_id|"
                            + "progress_date|setting_key)\\b", alias + ".$1") + " AS TEXT)";
                    statement.execute("CREATE TRIGGER IF NOT EXISTS \"" + trigger + "\" "
                            + "AFTER " + action + " ON \"" + table + "\" BEGIN "
                            + "DELETE FROM sync_revisions WHERE table_name='" + table
                            + "' AND record_key=" + recordKey + "; "
                            + "INSERT INTO sync_revisions(table_name,record_key,"
                            + "modified_at,origin_id,is_deleted) VALUES('" + table + "',CAST("
                            + key.replaceAll("\\b(id|name|entry_date|block_id|goal_id|"
                                    + "progress_date|setting_key)\\b", alias + ".$1")
                            + " AS TEXT),MAX(CAST((julianday('now')-2440587.5)*86400000 AS INTEGER),"
                            + "(SELECT COALESCE(MAX(modified_at),0)+1 FROM sync_revisions)),"
                            + "(SELECT device_id FROM sync_device LIMIT 1),"
                            + (action.equals("DELETE") ? "1" : "0") + "); END");
                }
                String sourceKey = key.replaceAll(
                        "\\b(id|name|entry_date|block_id|goal_id|progress_date|setting_key)\\b",
                        "source_row.$1");
                try (PreparedStatement seed = connection.prepareStatement(
                        "INSERT OR IGNORE INTO sync_revisions "
                                + "(table_name,record_key,modified_at,origin_id,is_deleted) "
                                + "SELECT ?,CAST(" + sourceKey + " AS TEXT),"
                                + "CAST((julianday('now')-2440587.5)*86400000 AS INTEGER),"
                                + "(SELECT device_id FROM sync_device LIMIT 1),0 "
                                + "FROM \"" + table + "\" source_row")) {
                    seed.setString(1, table);
                    seed.executeUpdate();
                }
            }
        }
    }

    JsonObject syncSnapshot() throws SQLException {
        JsonObject snapshot = new JsonObject();
        com.google.gson.JsonArray records = new com.google.gson.JsonArray();
        Map<String, String> keys = Map.ofEntries(
                Map.entry("chat_messages", "id"), Map.entry("transactions", "id"),
                Map.entry("goals", "id"), Map.entry("planner_tasks", "id"),
                Map.entry("finance_budgets", "category"),
                Map.entry("quick_income_categories", "name"),
                Map.entry("daily_records", "entry_date"),
                Map.entry("daily_entries", "entry_date || char(31) || block_id"),
                Map.entry("goal_progress", "goal_id || char(31) || progress_date"),
                Map.entry("planner_settings", "setting_key"),
                Map.entry("planner_blocks", "block_id"), Map.entry("voice_notes", "id"));
        for (Map.Entry<String, String> entry : keys.entrySet()) {
            String table = entry.getKey();
            try (Statement query = connection.createStatement();
                 ResultSet rows = query.executeQuery("SELECT * FROM \"" + table + "\"")) {
                java.sql.ResultSetMetaData metadata = rows.getMetaData();
                while (rows.next()) {
                    JsonObject data = new JsonObject();
                    for (int index = 1; index <= metadata.getColumnCount(); index++) {
                        Object value = rows.getObject(index);
                        if (value == null) {
                            data.add(metadata.getColumnName(index), com.google.gson.JsonNull.INSTANCE);
                        } else if (value instanceof Number number) {
                            data.addProperty(metadata.getColumnName(index), number);
                        } else {
                            data.addProperty(metadata.getColumnName(index), value.toString());
                        }
                    }
                    String recordKey = recordKey(table, data);
                    JsonObject record = new JsonObject();
                    record.addProperty("table", table);
                    record.addProperty("key", recordKey);
                    record.add("data", data);
                    record.addProperty("modifiedAt", revisionTime(table, recordKey));
                    record.addProperty("origin", revisionOrigin(table, recordKey));
                    record.addProperty("deleted", false);
                    records.add(record);
                }
            }
        }
        try (Statement query = connection.createStatement();
             ResultSet rows = query.executeQuery(
                      "SELECT table_name,record_key,modified_at,origin_id,is_deleted FROM sync_revisions")) {
            while (rows.next()) {
                String table = rows.getString(1);
                boolean present = recordExists(table, rows.getString(2));
                 if (!present || rows.getInt(5) == 1) {
                    JsonObject tombstone = new JsonObject();
                    tombstone.addProperty("table", table);
                    tombstone.addProperty("key", rows.getString(2));
                    tombstone.addProperty("modifiedAt", rows.getLong(3));
                    tombstone.addProperty("origin", rows.getString(4));
                    tombstone.addProperty("deleted", true);
                    tombstone.add("data", com.google.gson.JsonNull.INSTANCE);
                    records.add(tombstone);
                }
            }
        }
        snapshot.add("records", records);
        return snapshot;
    }

    private long revisionTime(String table, String key) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT modified_at FROM sync_revisions "
                        + "WHERE table_name=? AND record_key=?")) {
            query.setString(1, table);
            query.setString(2, key);
            try (ResultSet row = query.executeQuery()) {
                return row.next() ? row.getLong(1) : 0;
            }
        }
    }

    private boolean recordExists(String table, String key) throws SQLException {
        String where = switch (table) {
            case "daily_entries" -> "entry_date=? AND block_id=?";
            case "goal_progress" -> "goal_id=? AND progress_date=?";
            default -> switch (table) {
                case "chat_messages", "transactions", "goals", "planner_tasks",
                        "voice_notes" -> "id=?";
                case "finance_budgets" -> "category=?";
                case "quick_income_categories" -> "name=?";
                case "daily_records" -> "entry_date=?";
                case "planner_settings" -> "setting_key=?";
                case "planner_blocks" -> "block_id=?";
                default -> throw new SQLException("Unknown sync table.");
            };
        };
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT 1 FROM \"" + table + "\" WHERE " + where)) {
            bindRecordKey(query, table, key);
            try (ResultSet result = query.executeQuery()) {
                return result.next();
            }
        }
    }

    void mergeSyncSnapshot(JsonObject snapshot) throws SQLException {
        if (snapshot == null || !snapshot.has("records") || !snapshot.get("records").isJsonArray()) {
            throw new SQLException("Неверный формат синхронизации.");
        }
        connection.setAutoCommit(false);
        try {
            for (JsonElement item : snapshot.getAsJsonArray("records")) {
                JsonObject record = item.getAsJsonObject();
                String table = record.get("table").getAsString();
                String key = record.get("key").getAsString();
                long modifiedAt = record.get("modifiedAt").getAsLong();
                String origin = record.has("origin") ? record.get("origin").getAsString() : "";
                long localTime = revisionTime(table, key);
                String localOrigin = revisionOrigin(table, key);
                if (modifiedAt < localTime || (modifiedAt == localTime
                        && origin.compareTo(localOrigin) <= 0)) {
                    continue;
                }
                if (record.get("deleted").getAsBoolean()) {
                    deleteSyncedRecord(table, key);
                } else {
                    upsertSyncedRecord(table, record.getAsJsonObject("data"));
                }
                try (PreparedStatement saveRevision = connection.prepareStatement(
                        "INSERT OR REPLACE INTO sync_revisions"
                                + "(table_name,record_key,modified_at,origin_id,is_deleted) "
                                + "VALUES(?,?,?,?,?)")) {
                    saveRevision.setString(1, table);
                    saveRevision.setString(2, key);
                    saveRevision.setLong(3, modifiedAt);
                    saveRevision.setString(4, origin);
                    saveRevision.setInt(5, record.get("deleted").getAsBoolean() ? 1 : 0);
                    saveRevision.executeUpdate();
                }
            }
            connection.commit();
        } catch (RuntimeException | SQLException exception) {
            connection.rollback();
            if (exception instanceof SQLException sqlException) {
                throw sqlException;
            }
            throw new SQLException("Не удалось объединить данные синхронизации.", exception);
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private void deleteSyncedRecord(String table, String key) throws SQLException {
        if (!table.equals("chat_messages") && !table.equals("transactions")
                && !table.equals("goals") && !table.equals("planner_tasks")
                && !table.equals("finance_budgets") && !table.equals("quick_income_categories")
                && !table.equals("daily_records") && !table.equals("daily_entries")
                && !table.equals("goal_progress") && !table.equals("planner_settings")
                && !table.equals("planner_blocks") && !table.equals("voice_notes")) {
            throw new SQLException("Unknown sync table.");
        }
        String where = switch (table) {
            case "daily_entries" -> "entry_date=? AND block_id=?";
            case "goal_progress" -> "goal_id=? AND progress_date=?";
            case "chat_messages", "transactions", "goals", "planner_tasks", "voice_notes" -> "id=?";
            case "finance_budgets" -> "category=?";
            case "quick_income_categories" -> "name=?";
            case "daily_records" -> "entry_date=?";
            case "planner_settings" -> "setting_key=?";
            default -> "block_id=?";
        };
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM \"" + table + "\" WHERE " + where)) {
            bindRecordKey(delete, table, key);
            delete.executeUpdate();
        }
    }

    private void upsertSyncedRecord(String table, JsonObject data) throws SQLException {
        if (data == null || data.isJsonNull()) {
            throw new SQLException("Sync record has no row data.");
        }
        List<String> allowed = switch (table) {
            case "chat_messages" -> List.of("id", "message", "is_user", "time");
            case "transactions" -> List.of("id", "title", "amount_cents", "type", "date", "category");
            case "goals" -> List.of("id", "title", "target_date", "start_date", "term");
            case "planner_tasks" -> List.of("id", "title", "task_date", "task_time", "goal_id", "is_done");
            case "finance_budgets" -> List.of("category", "monthly_limit_cents");
            case "quick_income_categories" -> List.of("name", "is_deleted");
            case "daily_records" -> List.of("entry_date", "effort", "energy",
                    "development_note", "diary", "rest_activity");
            case "daily_entries" -> List.of("entry_date", "block_id", "is_done");
            case "goal_progress" -> List.of("goal_id", "progress_date", "note");
            case "planner_settings" -> List.of("setting_key", "setting_value");
            case "planner_blocks" -> List.of("block_id", "group_id", "group_title",
                    "group_caption", "title", "subtitle", "group_position", "position",
                    "is_deleted");
            case "voice_notes" -> List.of("id", "title", "content", "created_at");
            default -> throw new SQLException("Unknown sync table.");
        };
        String columns = String.join(",", allowed);
        String placeholders = String.join(",", java.util.Collections.nCopies(allowed.size(), "?"));
        String conflict = switch (table) {
            case "daily_entries" -> "entry_date,block_id";
            case "goal_progress" -> "goal_id,progress_date";
            case "chat_messages", "transactions", "goals", "planner_tasks", "voice_notes" -> "id";
            case "finance_budgets" -> "category";
            case "quick_income_categories" -> "name";
            case "daily_records" -> "entry_date";
            case "planner_settings" -> "setting_key";
            default -> "block_id";
        };
        List<String> updates = allowed.stream().filter(name -> !java.util.Arrays
                        .asList(conflict.split(",")).contains(name))
                .map(name -> name + "=excluded." + name).toList();
        String updateClause = updates.isEmpty() ? "DO NOTHING"
                : "DO UPDATE SET " + String.join(",", updates);
        String sql = "INSERT INTO \"" + table + "\"(" + columns + ") VALUES("
                + placeholders + ") ON CONFLICT(" + conflict + ") " + updateClause;
        try (PreparedStatement upsert = connection.prepareStatement(sql)) {
            for (int index = 0; index < allowed.size(); index++) {
                JsonElement value = data.get(allowed.get(index));
                if (value == null || value.isJsonNull()) {
                    upsert.setObject(index + 1, null);
                } else if (value.getAsJsonPrimitive().isBoolean()) {
                    upsert.setInt(index + 1, value.getAsBoolean() ? 1 : 0);
                } else if (value.getAsJsonPrimitive().isNumber()) {
                    upsert.setLong(index + 1, value.getAsLong());
                } else {
                    upsert.setString(index + 1, value.getAsString());
                }
            }
            upsert.executeUpdate();
        }
    }

    private String recordKey(String table, JsonObject data) {
        return switch (table) {
            case "daily_entries" -> data.get("entry_date").getAsString() + (char) 31
                    + data.get("block_id").getAsString();
            case "goal_progress" -> data.get("goal_id").getAsString() + (char) 31
                    + data.get("progress_date").getAsString();
            case "chat_messages", "transactions", "goals", "planner_tasks", "voice_notes" ->
                    data.get("id").getAsString();
            case "finance_budgets" -> data.get("category").getAsString();
            case "quick_income_categories" -> data.get("name").getAsString();
            case "daily_records" -> data.get("entry_date").getAsString();
            case "planner_settings" -> data.get("setting_key").getAsString();
            default -> data.get("block_id").getAsString();
        };
    }

    private void bindRecordKey(PreparedStatement statement, String table, String key)
            throws SQLException {
        String[] parts = key.split(String.valueOf((char) 31), -1);
        if (table.equals("daily_entries") || table.equals("goal_progress")) {
            if (parts.length != 2) {
                throw new SQLException("Invalid composite sync key.");
            }
            statement.setString(1, parts[0]);
            statement.setString(2, parts[1]);
        } else {
            if (table.equals("chat_messages") || table.equals("transactions")
                    || table.equals("goals") || table.equals("planner_tasks")
                    || table.equals("voice_notes")) {
                statement.setLong(1, Long.parseLong(key));
            } else {
                statement.setString(1, key);
            }
        }
    }

    private void ensurePlannerDefaults() throws SQLException {
        try (Statement query = connection.createStatement();
             ResultSet rows = query.executeQuery("SELECT COUNT(*) FROM planner_blocks")) {
            if (!rows.next() || rows.getInt(1) != 0) {
                return;
            }
        }
        String[][] groups = {
                {"🌅 Утро", "Запустить день без перегруза", "water|Вода|Старт дня",
                        "breathing|Дыхание|Box breathing 4–4–4–4",
                        "activation|Физическая активация|10–20 отжиманий + движение",
                        "breakfast|Завтрак + музыка|Без спешки", "walk|Прогулка|10–20 минут"},
                {"🧠 Развитие", "Выбери собственное направление",
                        "development|Развитие|AI / видео / озвучка / другое"},
                {"🎯 Фокус", "Работа над главным",
                        "focus_one|Главная задача|Один конкретный результат",
                        "focus_two|Рабочая сессия|Ещё один завершённый блок"},
                {"💪 Тело", "Движение по силам", "pushups|Отжимания|10–20",
                        "squats|Приседания|10–20", "crunches|Скручивания|10–20"},
                {"🌙 Завершение дня", "Спокойный финиш",
                        "diary|Дневник|Что сделал / не сделал / понял / завтра",
                        "book|Книга|10 минут"}
        };
        for (int group = 0; group < groups.length; group++) {
            for (int position = 2; position < groups[group].length; position++) {
                String[] block = groups[group][position].split("\\|", 3);
                saveDailyBlock(new DailyBlockEntry(block[0], "group_" + group,
                        groups[group][0], groups[group][1], block[1], block[2], group,
                        position - 2));
            }
        }
    }

    List<FinanceEntry> transactions() throws SQLException {
        List<FinanceEntry> entries = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT id, title, amount_cents, type, date, category "
                             + "FROM transactions ORDER BY date DESC, id DESC")) {
            while (rows.next()) {
                entries.add(new FinanceEntry(rows.getLong(1), rows.getString(2),
                        rows.getLong(3), rows.getInt(4) == 1, rows.getString(5),
                        rows.getString(6)));
            }
        }
        return entries;
    }

    long[] monthlyTotals(String month) throws SQLException {
        long[] totals = new long[2];
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT type, COALESCE(SUM(amount_cents), 0) FROM transactions "
                        + "WHERE date LIKE ? GROUP BY type")) {
            query.setString(1, month + "-%");
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    totals[rows.getInt(1) == 1 ? 0 : 1] = rows.getLong(2);
                }
            }
        }
        return totals;
    }

    void addTransaction(String title, long amountCents, boolean income, String category)
            throws SQLException {
        addTransaction(title, amountCents, income, LocalDate.now(), category);
    }

    void addTransaction(String title, long amountCents, boolean income, LocalDate date,
                        String category) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO transactions(id,title, amount_cents, type, date, category) "
                        + "VALUES (?, ?, ?, ?, ?, ?)")) {
            insert.setLong(1, uniqueId());
            insert.setString(2, title);
            insert.setLong(3, amountCents);
            insert.setInt(4, income ? 1 : -1);
            insert.setString(5, date.toString());
            insert.setString(6, category);
            insert.executeUpdate();
        }
    }

    void updateTransaction(long id, String title, long amountCents, boolean income,
                           LocalDate date, String category) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE transactions SET title=?, amount_cents=?, type=?, date=?, category=? "
                        + "WHERE id=?")) {
            update.setString(1, title);
            update.setLong(2, amountCents);
            update.setInt(3, income ? 1 : -1);
            update.setString(4, date.toString());
            update.setString(5, category);
            update.setLong(6, id);
            update.executeUpdate();
        }
    }

    void deleteTransaction(long id) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM transactions WHERE id=?")) {
            delete.setLong(1, id);
            delete.executeUpdate();
        }
    }

    List<String> incomeCategories() throws SQLException {
        List<String> categories = new ArrayList<>();
        try (Statement query = connection.createStatement();
             ResultSet rows = query.executeQuery("SELECT name FROM quick_income_categories "
                     + "WHERE is_deleted=0 ORDER BY rowid")) {
            while (rows.next()) {
                categories.add(rows.getString(1));
            }
        }
        return categories;
    }

    void saveIncomeCategory(String oldName, String name) throws SQLException {
        connection.setAutoCommit(false);
        try {
            if (oldName != null && !oldName.equalsIgnoreCase(name)) {
                try (PreparedStatement updateTransactions = connection.prepareStatement(
                        "UPDATE transactions SET category=? WHERE category=?");
                     PreparedStatement deleteBudget = connection.prepareStatement(
                             "DELETE FROM finance_budgets WHERE category=?");
                     PreparedStatement updateBudget = connection.prepareStatement(
                             "UPDATE finance_budgets SET category=? WHERE category=?");
                     PreparedStatement delete = connection.prepareStatement(
                             "DELETE FROM quick_income_categories WHERE name=?")) {
                    updateTransactions.setString(1, name);
                    updateTransactions.setString(2, oldName);
                    updateTransactions.executeUpdate();
                    deleteBudget.setString(1, name);
                    deleteBudget.executeUpdate();
                    updateBudget.setString(1, name);
                    updateBudget.setString(2, oldName);
                    updateBudget.executeUpdate();
                    delete.setString(1, oldName);
                    delete.executeUpdate();
                }
            }
            try (PreparedStatement upsert = connection.prepareStatement(
                    "INSERT OR REPLACE INTO quick_income_categories(name,is_deleted) VALUES(?,0)")) {
                upsert.setString(1, name);
                upsert.executeUpdate();
            }
            connection.commit();
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    void deleteIncomeCategory(String name) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement(
                "UPDATE quick_income_categories SET is_deleted=1 WHERE name=?")) {
            delete.setString(1, name);
            delete.executeUpdate();
        }
    }

    List<BudgetEntry> budgets(String month) throws SQLException {
        List<BudgetEntry> items = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT b.category,b.monthly_limit_cents,COALESCE(SUM(t.amount_cents),0) "
                        + "FROM finance_budgets b LEFT JOIN transactions t "
                        + "ON t.category=b.category AND t.type=-1 AND t.date LIKE ? "
                        + "GROUP BY b.category,b.monthly_limit_cents ORDER BY b.category")) {
            query.setString(1, month + "-%");
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    items.add(new BudgetEntry(rows.getString(1), rows.getLong(2),
                            rows.getLong(3)));
                }
            }
        }
        return items;
    }

    void saveBudget(String category, long amountCents) throws SQLException {
        try (PreparedStatement save = connection.prepareStatement(
                "INSERT OR REPLACE INTO finance_budgets(category,monthly_limit_cents) "
                        + "VALUES(?,?)")) {
            save.setString(1, category);
            save.setLong(2, amountCents);
            save.executeUpdate();
        }
    }

    void deleteBudget(String category) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM finance_budgets WHERE category=?")) {
            delete.setString(1, category);
            delete.executeUpdate();
        }
    }

    List<GoalEntry> goals() throws SQLException {
        List<GoalEntry> entries = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT id, title, start_date, target_date, term FROM goals "
                             + "ORDER BY target_date, id")) {
            while (rows.next()) {
                entries.add(new GoalEntry(rows.getLong(1), rows.getString(2),
                        rows.getString(3), rows.getString(4), rows.getString(5)));
            }
        }
        return entries;
    }

    void addGoal(String title, LocalDate start, LocalDate target, String term)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO goals(id,title, start_date, target_date, term) "
                        + "VALUES (?, ?, ?, ?, ?)")) {
            insert.setLong(1, uniqueId());
            insert.setString(2, title);
            insert.setString(3, start.toString());
            insert.setString(4, target.toString());
            insert.setString(5, term);
            insert.executeUpdate();
        }
    }

    void updateGoal(long id, String title, LocalDate start, LocalDate target, String term)
            throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE goals SET title=?,start_date=?,target_date=?,term=? WHERE id=?")) {
            update.setString(1, title);
            update.setString(2, start.toString());
            update.setString(3, target.toString());
            update.setString(4, term);
            update.setLong(5, id);
            update.executeUpdate();
        }
    }

    void markGoalProgress(long id, LocalDate date, String note) throws SQLException {
        try (PreparedStatement save = connection.prepareStatement(
                "INSERT OR REPLACE INTO goal_progress(goal_id,progress_date,note) VALUES(?,?,?)")) {
            save.setLong(1, id);
            save.setString(2, date.toString());
            save.setString(3, note);
            save.executeUpdate();
        }
    }

    List<GoalProgressEntry> goalProgress(long id) throws SQLException {
        List<GoalProgressEntry> entries = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT progress_date,note FROM goal_progress WHERE goal_id=? "
                        + "ORDER BY progress_date DESC LIMIT 30")) {
            query.setLong(1, id);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    entries.add(new GoalProgressEntry(rows.getString(1), rows.getString(2)));
                }
            }
        }
        return entries;
    }

    void deleteGoal(long id) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM goals WHERE id = ?")) {
            delete.setLong(1, id);
            delete.executeUpdate();
        }
    }

    List<TaskEntry> tasks(LocalDate date) throws SQLException {
        List<TaskEntry> entries = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT t.id,t.title,t.task_date,t.task_time,t.goal_id,t.is_done,"
                        + "COALESCE(g.title,'') FROM planner_tasks t LEFT JOIN goals g "
                        + "ON g.id=t.goal_id WHERE t.task_date=? ORDER BY t.task_time,t.id")) {
            query.setString(1, date.toString());
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    long goalId = rows.getLong(5);
                    Long linkedGoal = rows.wasNull() ? null : goalId;
                    entries.add(new TaskEntry(rows.getLong(1), rows.getString(2),
                            LocalDate.parse(rows.getString(3)), rows.getString(4), linkedGoal,
                            rows.getInt(6) == 1, rows.getString(7)));
                }
            }
        }
        return entries;
    }

    void addTask(String title, LocalDate date, String time, Long goalId) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO planner_tasks(id,title,task_date,task_time,goal_id,is_done) "
                        + "VALUES(?,?,?,?,?,0)")) {
            insert.setLong(1, uniqueId());
            insert.setString(2, title);
            insert.setString(3, date.toString());
            insert.setString(4, time);
            if (goalId == null) {
                insert.setNull(5, java.sql.Types.INTEGER);
            } else {
                insert.setLong(5, goalId);
            }
            insert.executeUpdate();
        }
    }

    void setTaskDone(long id, boolean done) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE planner_tasks SET is_done=? WHERE id=?")) {
            update.setInt(1, done ? 1 : 0);
            update.setLong(2, id);
            update.executeUpdate();
        }
    }

    void updateTask(long id, String title, LocalDate date, String time, Long goalId)
            throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE planner_tasks SET title=?,task_date=?,task_time=?,goal_id=? WHERE id=?")) {
            update.setString(1, title);
            update.setString(2, date.toString());
            update.setString(3, time);
            if (goalId == null) {
                update.setNull(4, java.sql.Types.INTEGER);
            } else {
                update.setLong(4, goalId);
            }
            update.setLong(5, id);
            update.executeUpdate();
        }
    }

    void deleteTask(long id) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM planner_tasks WHERE id=?")) {
            delete.setLong(1, id);
            delete.executeUpdate();
        }
    }

    List<DailyBlockEntry> dailyBlocks() throws SQLException {
        List<DailyBlockEntry> entries = new ArrayList<>();
        try (Statement query = connection.createStatement();
             ResultSet rows = query.executeQuery("SELECT block_id,group_id,group_title,"
                     + "group_caption,title,subtitle,group_position,position FROM planner_blocks "
                     + "WHERE is_deleted=0 ORDER BY group_position,position")) {
            while (rows.next()) {
                entries.add(new DailyBlockEntry(rows.getString(1), rows.getString(2),
                        rows.getString(3), rows.getString(4), rows.getString(5),
                        rows.getString(6), rows.getInt(7), rows.getInt(8)));
            }
        }
        return entries;
    }

    void saveDailyBlock(DailyBlockEntry block) throws SQLException {
        try (PreparedStatement save = connection.prepareStatement(
                "INSERT OR REPLACE INTO planner_blocks(block_id,group_id,group_title,"
                        + "group_caption,title,subtitle,group_position,position,is_deleted) "
                        + "VALUES(?,?,?,?,?,?,?,?,0)")) {
            save.setString(1, block.id());
            save.setString(2, block.groupId());
            save.setString(3, block.groupTitle());
            save.setString(4, block.groupCaption());
            save.setString(5, block.title());
            save.setString(6, block.subtitle());
            save.setInt(7, block.groupPosition());
            save.setInt(8, block.position());
            save.executeUpdate();
        }
    }

    void deleteDailyBlock(String id) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement(
                "UPDATE planner_blocks SET is_deleted=1 WHERE block_id=?")) {
            delete.setString(1, id);
            delete.executeUpdate();
        }
    }

    boolean dailyEntryDone(LocalDate date, String blockId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT is_done FROM daily_entries WHERE entry_date=? AND block_id=?")) {
            query.setString(1, date.toString());
            query.setString(2, blockId);
            try (ResultSet row = query.executeQuery()) {
                return row.next() && row.getInt(1) == 1;
            }
        }
    }

    void setDailyEntry(LocalDate date, String blockId, boolean done) throws SQLException {
        try (PreparedStatement save = connection.prepareStatement(
                "INSERT OR REPLACE INTO daily_entries(entry_date,block_id,is_done) VALUES(?,?,?)")) {
            save.setString(1, date.toString());
            save.setString(2, blockId);
            save.setInt(3, done ? 1 : 0);
            save.executeUpdate();
        }
    }

    DayRecordEntry dayRecord(LocalDate date) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT effort,energy,development_note,diary,rest_activity "
                        + "FROM daily_records WHERE entry_date=?")) {
            query.setString(1, date.toString());
            try (ResultSet row = query.executeQuery()) {
                if (row.next()) {
                    return new DayRecordEntry(date, row.getString(1), row.getString(2),
                            row.getString(3), row.getString(4), row.getString(5));
                }
            }
        }
        return new DayRecordEntry(date, "NORMAL", "NORMAL", "", "", "");
    }

    void saveDayRecord(DayRecordEntry record) throws SQLException {
        try (PreparedStatement save = connection.prepareStatement(
                "INSERT OR REPLACE INTO daily_records(entry_date,effort,energy,"
                        + "development_note,diary,rest_activity) VALUES(?,?,?,?,?,?)")) {
            save.setString(1, record.date().toString());
            save.setString(2, record.effort());
            save.setString(3, record.energy());
            save.setString(4, record.developmentNote());
            save.setString(5, record.diary());
            save.setString(6, record.restActivity());
            save.executeUpdate();
        }
    }

    String setting(String key, String fallback) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT setting_value FROM planner_settings WHERE setting_key=?")) {
            query.setString(1, key);
            try (ResultSet row = query.executeQuery()) {
                return row.next() ? row.getString(1) : fallback;
            }
        }
    }

    void saveSetting(String key, String value) throws SQLException {
        try (PreparedStatement save = connection.prepareStatement(
                "INSERT OR REPLACE INTO planner_settings(setting_key,setting_value) VALUES(?,?)")) {
            save.setString(1, key);
            save.setString(2, value);
            save.executeUpdate();
        }
    }

    List<NoteEntry> notes() throws SQLException {
        List<NoteEntry> entries = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT id, title, content, created_at FROM voice_notes ORDER BY id DESC")) {
            while (rows.next()) {
                entries.add(new NoteEntry(rows.getLong(1), rows.getString(2),
                        rows.getString(3), rows.getString(4)));
            }
        }
        return entries;
    }

    void addNote(String title, String content) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO voice_notes(id,title, content, created_at) VALUES (?, ?, ?, ?)")) {
            insert.setLong(1, uniqueId());
            insert.setString(2, title);
            insert.setString(3, content);
            insert.setString(4, LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            insert.executeUpdate();
        }
    }

    void updateNote(long id, String title, String content) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE voice_notes SET title=?,content=? WHERE id=?")) {
            update.setString(1, title);
            update.setString(2, content);
            update.setLong(3, id);
            update.executeUpdate();
        }
    }

    void deleteNote(long id) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM voice_notes WHERE id = ?")) {
            delete.setLong(1, id);
            delete.executeUpdate();
        }
    }

    void saveMessage(String message, boolean user) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO chat_messages(id,message, is_user, time) VALUES (?, ?, ?, ?)")) {
            insert.setLong(1, uniqueId());
            insert.setString(2, message);
            insert.setInt(3, user ? 1 : 0);
            insert.setString(4, LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm")));
            insert.executeUpdate();
        }
    }

    List<ChatEntry> messages() throws SQLException {
        List<ChatEntry> entries = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT message, is_user, time FROM chat_messages ORDER BY id")) {
            while (rows.next()) {
                entries.add(new ChatEntry(rows.getString(1), rows.getInt(2) == 1,
                        rows.getString(3)));
            }
        }
        return entries;
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }

    private long uniqueId() {
        long candidate = System.currentTimeMillis() * 1000L
                + java.util.concurrent.ThreadLocalRandom.current().nextInt(1000);
        lastGeneratedId = Math.max(candidate, lastGeneratedId + 1);
        return lastGeneratedId;
    }

    private long lastGeneratedId;

    record FinanceEntry(long id, String title, long amountCents, boolean income, String date,
                        String category) {
    }

    record BudgetEntry(String category, long limitCents, long spentCents) {
    }

    record GoalEntry(long id, String title, String startDate, String targetDate, String term) {
    }

    record GoalProgressEntry(String date, String note) {
    }

    record TaskEntry(long id, String title, LocalDate date, String time, Long goalId,
                     boolean done, String goalTitle) {
    }

    record DailyBlockEntry(String id, String groupId, String groupTitle, String groupCaption,
                           String title, String subtitle, int groupPosition, int position) {
    }

    record DayRecordEntry(LocalDate date, String effort, String energy, String developmentNote,
                          String diary, String restActivity) {
    }

    record NoteEntry(long id, String title, String content, String createdAt) {
    }

    record ChatEntry(String message, boolean user, String time) {
    }
}
