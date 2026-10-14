package com.example.myjarvis;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.UUID;

public class OfflineDataStore extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "lira_offline.db";
    private static final int DATABASE_VERSION = 6;

    public OfflineDataStore(Context context) {
        super(context.getApplicationContext(), DATABASE_NAME, null, DATABASE_VERSION);
    }

    public static class DailyBlock {
        public final String id;
        public final String groupId;
        public final String groupTitle;
        public final String groupCaption;
        public final String title;
        public final String subtitle;
        public final int groupPosition;
        public final int position;

        public DailyBlock(String id, String groupId, String groupTitle, String groupCaption,
                          String title, String subtitle, int groupPosition, int position) {
            this.id = id;
            this.groupId = groupId;
            this.groupTitle = groupTitle;
            this.groupCaption = groupCaption;
            this.title = title;
            this.subtitle = subtitle;
            this.groupPosition = groupPosition;
            this.position = position;
        }
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        db.setForeignKeyConstraintsEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE chat_messages ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "message TEXT NOT NULL, is_user INTEGER NOT NULL, time TEXT NOT NULL)");
        db.execSQL("CREATE TABLE transactions ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "title TEXT NOT NULL, amount_cents INTEGER NOT NULL, "
                + "type INTEGER NOT NULL, date TEXT NOT NULL, category TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE TABLE goals ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "title TEXT NOT NULL, target_date TEXT NOT NULL, "
                + "start_date TEXT NOT NULL DEFAULT '', term TEXT NOT NULL DEFAULT 'day')");
        db.execSQL("CREATE TABLE planner_tasks ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "title TEXT NOT NULL, task_date TEXT NOT NULL, task_time TEXT NOT NULL, "
                + "goal_id INTEGER, is_done INTEGER NOT NULL DEFAULT 0, "
                + "FOREIGN KEY(goal_id) REFERENCES goals(id) ON DELETE CASCADE)");
        createDailyTrackerTables(db);
        createPlanningTables(db);
        createPlannerBlockTable(db);
        createQuickIncomeCategoryTable(db);
        createSyncTracking(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 1) {
            onCreate(db);
            return;
        }
        if (oldVersion < 2) {
            createDailyTrackerTables(db);
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE transactions ADD COLUMN category TEXT NOT NULL DEFAULT ''");
            db.execSQL("UPDATE transactions SET category = title WHERE category = ''");
            db.execSQL("ALTER TABLE goals ADD COLUMN start_date TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE goals ADD COLUMN term TEXT NOT NULL DEFAULT 'day'");
            db.execSQL("UPDATE goals SET start_date = date('now', 'localtime') WHERE start_date = ''");
            createPlanningTables(db);
        }
        if (oldVersion < 4) {
            createPlannerBlockTable(db);
        }
        if (oldVersion < 5) {
            createQuickIncomeCategoryTable(db);
        }
        if (oldVersion < 6) {
            remapLegacyIds(db);
            createSyncTracking(db);
        }
    }

    private void createSyncTracking(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS sync_revisions ("
                + "table_name TEXT NOT NULL, record_key TEXT NOT NULL, "
                + "modified_at INTEGER NOT NULL, origin_id TEXT NOT NULL DEFAULT '', "
                + "is_deleted INTEGER NOT NULL DEFAULT 0, "
                + "PRIMARY KEY(table_name,record_key))");
        db.execSQL("CREATE TABLE IF NOT EXISTS sync_device (device_id TEXT NOT NULL)");
        try (Cursor cursor = db.rawQuery("SELECT 1 FROM sync_device LIMIT 1", null)) {
            if (!cursor.moveToFirst()) {
                ContentValues device = new ContentValues();
                device.put("device_id", UUID.randomUUID().toString());
                db.insertOrThrow("sync_device", null, device);
            }
        }
        for (String[] tableKey : SYNC_KEYS) {
            String table = tableKey[0];
            String key = tableKey[1];
            if (tableKey.length == 3) {
                key += " || char(31) || " + tableKey[2];
            }
            for (String action : new String[]{"INSERT", "UPDATE", "DELETE"}) {
                String prefix = action.equals("DELETE") ? "OLD" : "NEW";
                String keyExpression = key.replaceAll(
                        "\\b(id|category|name|entry_date|block_id|goal_id|progress_date|setting_key)\\b",
                        prefix + ".$1");
                String recordKey = "CAST(" + keyExpression + " AS TEXT)";
                db.execSQL("CREATE TRIGGER IF NOT EXISTS sync_" + table + "_"
                        + action.toLowerCase(java.util.Locale.ROOT) + " AFTER " + action
                        + " ON " + table + " BEGIN "
                        + "DELETE FROM sync_revisions WHERE table_name='" + table
                        + "' AND record_key=" + recordKey + "; "
                        + "INSERT INTO sync_revisions"
                        + "(table_name,record_key,modified_at,origin_id,is_deleted) VALUES('"
                        + table + "',CAST(" + keyExpression
                        + " AS TEXT),MAX(CAST((julianday('now')-2440587.5)*86400000 AS INTEGER),"
                        + "(SELECT COALESCE(MAX(modified_at),0)+1 FROM sync_revisions)),"
                        + "(SELECT device_id FROM sync_device LIMIT 1),"
                        + (action.equals("DELETE") ? "1" : "0") + "); END");
            }
            String keyExpression = key.replaceAll(
                    "\\b(id|category|name|entry_date|block_id|goal_id|progress_date|setting_key)\\b",
                    "source_row.$1");
            db.execSQL("INSERT OR IGNORE INTO sync_revisions"
                    + "(table_name,record_key,modified_at,origin_id,is_deleted) "
                    + "SELECT '" + table + "',CAST(" + keyExpression
                    + " AS TEXT),CAST((julianday('now')-2440587.5)*86400000 AS INTEGER),"
                    + "(SELECT device_id FROM sync_device LIMIT 1),0 FROM " + table + " source_row");
        }
    }

    private void remapLegacyIds(SQLiteDatabase db) {
        String[] idTables = {"chat_messages", "transactions", "planner_tasks", "voice_notes"};
        for (String table : idTables) {
            remapIds(db, table, "id");
        }
        try (Cursor cursor = db.query("goals", new String[]{"id"}, null, null,
                null, null, "id ASC")) {
            List<Long> ids = new ArrayList<>();
            while (cursor.moveToNext()) {
                ids.add(cursor.getLong(0));
            }
            for (long oldId : ids) {
                long newId = uniqueId();
                ContentValues goal = new ContentValues();
                try (Cursor row = db.query("goals", null, "id=?",
                        new String[]{String.valueOf(oldId)}, null, null, null)) {
                    if (!row.moveToFirst()) {
                        continue;
                    }
                    for (int column = 0; column < row.getColumnCount(); column++) {
                        int type = row.getType(column);
                        String name = row.getColumnName(column);
                        if (name.equals("id")) {
                            goal.put(name, newId);
                        } else if (type == Cursor.FIELD_TYPE_INTEGER) {
                            goal.put(name, row.getLong(column));
                        } else if (type == Cursor.FIELD_TYPE_STRING) {
                            goal.put(name, row.getString(column));
                        } else if (type == Cursor.FIELD_TYPE_FLOAT) {
                            goal.put(name, row.getDouble(column));
                        } else if (type == Cursor.FIELD_TYPE_NULL) {
                            goal.putNull(name);
                        }
                    }
                }
                db.insertOrThrow("goals", null, goal);
                ContentValues newReference = new ContentValues();
                newReference.put("goal_id", newId);
                db.update("planner_tasks", newReference, "goal_id=?",
                        new String[]{String.valueOf(oldId)});
                db.update("goal_progress", newReference, "goal_id=?",
                        new String[]{String.valueOf(oldId)});
                db.delete("goals", "id=?", new String[]{String.valueOf(oldId)});
            }
        }
    }

    private void remapIds(SQLiteDatabase db, String table, String column) {
        List<Long> ids = new ArrayList<>();
        try (Cursor cursor = db.query(table, new String[]{column}, null, null,
                null, null, column + " ASC")) {
            while (cursor.moveToNext()) {
                ids.add(cursor.getLong(0));
            }
        }
        for (long id : ids) {
            ContentValues values = new ContentValues();
            values.put(column, uniqueId());
            db.update(table, values, column + "=?", new String[]{String.valueOf(id)});
        }
    }

    private static long lastGeneratedId;

    private static synchronized long uniqueId() {
        long candidate = System.currentTimeMillis() * 1000L
                + ThreadLocalRandom.current().nextInt(1000);
        lastGeneratedId = Math.max(candidate, lastGeneratedId + 1);
        return lastGeneratedId;
    }

    private static final String[][] SYNC_KEYS = {
            {"chat_messages", "id"}, {"transactions", "id"}, {"goals", "id"},
            {"planner_tasks", "id"}, {"finance_budgets", "category"},
            {"quick_income_categories", "name"}, {"daily_records", "entry_date"},
            {"daily_entries", "entry_date", "block_id"},
            {"goal_progress", "goal_id", "progress_date"},
            {"planner_settings", "setting_key"}, {"planner_blocks", "block_id"},
            {"voice_notes", "id"}
    };

    private static final Map<String, String[]> SYNC_COLUMNS = createSyncColumns();

    private static Map<String, String[]> createSyncColumns() {
        Map<String, String[]> columns = new HashMap<>();
        columns.put("chat_messages", new String[]{"id", "message", "is_user", "time"});
        columns.put("transactions", new String[]{"id", "title", "amount_cents", "type",
                "date", "category"});
        columns.put("goals", new String[]{"id", "title", "target_date", "start_date", "term"});
        columns.put("planner_tasks", new String[]{"id", "title", "task_date", "task_time",
                "goal_id", "is_done"});
        columns.put("finance_budgets", new String[]{"category", "monthly_limit_cents"});
        columns.put("quick_income_categories", new String[]{"name", "is_deleted"});
        columns.put("daily_records", new String[]{"entry_date", "effort", "energy",
                "development_note", "diary", "rest_activity"});
        columns.put("daily_entries", new String[]{"entry_date", "block_id", "is_done"});
        columns.put("goal_progress", new String[]{"goal_id", "progress_date", "note"});
        columns.put("planner_settings", new String[]{"setting_key", "setting_value"});
        columns.put("planner_blocks", new String[]{"block_id", "group_id", "group_title",
                "group_caption", "title", "subtitle", "group_position", "position",
                "is_deleted"});
        columns.put("voice_notes", new String[]{"id", "title", "content", "created_at"});
        return Collections.unmodifiableMap(columns);
    }

    public JSONObject exportSyncSnapshot() throws JSONException {
        JSONObject snapshot = new JSONObject();
        JSONArray records = new JSONArray();
        SQLiteDatabase db = getReadableDatabase();
        for (String[] tableKey : SYNC_KEYS) {
            String table = tableKey[0];
            try (Cursor cursor = db.query(table, null, null, null, null, null, null)) {
                while (cursor.moveToNext()) {
                    JSONObject data = rowAsJson(cursor);
                    String key = syncKey(tableKey, data);
                    SyncRevision revision = getSyncRevision(table, key);
                    JSONObject record = new JSONObject();
                    record.put("table", table);
                    record.put("key", key);
                    record.put("modifiedAt", revision.modifiedAt);
                    record.put("origin", revision.origin);
                    record.put("deleted", false);
                    record.put("data", data);
                    records.put(record);
                }
            }
        }
        try (Cursor cursor = db.query("sync_revisions",
                new String[]{"table_name", "record_key", "modified_at", "origin_id"},
                "is_deleted = 1", null, null, null, null)) {
            while (cursor.moveToNext()) {
                JSONObject record = new JSONObject();
                record.put("table", cursor.getString(0));
                record.put("key", cursor.getString(1));
                record.put("modifiedAt", cursor.getLong(2));
                record.put("origin", cursor.getString(3));
                record.put("deleted", true);
                record.put("data", JSONObject.NULL);
                records.put(record);
            }
        }
        snapshot.put("records", records);
        return snapshot;
    }

    private JSONObject rowAsJson(Cursor cursor) throws JSONException {
        JSONObject row = new JSONObject();
        for (int index = 0; index < cursor.getColumnCount(); index++) {
            int type = cursor.getType(index);
            Object value = switch (type) {
                case Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index);
                case Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index);
                case Cursor.FIELD_TYPE_STRING -> cursor.getString(index);
                default -> JSONObject.NULL;
            };
            row.put(cursor.getColumnName(index), value);
        }
        return row;
    }

    private String syncKey(String[] tableKey, JSONObject data) throws JSONException {
        if (tableKey.length == 2) {
            return data.getString(tableKey[1]);
        }
        return data.getString(tableKey[1]) + (char) 31 + data.getString(tableKey[2]);
    }

    private SyncRevision getSyncRevision(String table, String key) {
        try (Cursor cursor = getReadableDatabase().query("sync_revisions",
                new String[]{"modified_at", "origin_id"}, "table_name=? AND record_key=?",
                new String[]{table, key}, null, null, null)) {
            return cursor.moveToFirst()
                    ? new SyncRevision(cursor.getLong(0), cursor.getString(1))
                    : new SyncRevision(0, "");
        }
    }

    public void mergeSyncSnapshot(JSONObject snapshot) throws JSONException {
        JSONArray records = snapshot.optJSONArray("records");
        if (records == null) {
            throw new JSONException("Missing sync records.");
        }
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (int index = 0; index < records.length(); index++) {
                JSONObject record = records.getJSONObject(index);
                String table = record.getString("table");
                if (!SYNC_COLUMNS.containsKey(table)) {
                    throw new JSONException("Unknown sync table: " + table);
                }
                String key = record.getString("key");
                long modifiedAt = record.getLong("modifiedAt");
                String origin = record.optString("origin", "");
                SyncRevision current = getSyncRevision(table, key);
                if (modifiedAt < current.modifiedAt || (modifiedAt == current.modifiedAt
                        && origin.compareTo(current.origin) <= 0)) {
                    continue;
                }
                String[] primaryKeys = syncPrimaryKeys(table);
                String where = syncWhere(primaryKeys);
                String[] whereArgs = key.split(String.valueOf((char) 31), -1);
                if (record.getBoolean("deleted")) {
                    db.delete(table, where, whereArgs);
                } else {
                    JSONObject data = record.getJSONObject("data");
                    ContentValues values = new ContentValues();
                    for (String column : SYNC_COLUMNS.get(table)) {
                        if (!data.has(column) || data.isNull(column)) {
                            values.putNull(column);
                        } else {
                            Object value = data.get(column);
                            if (value instanceof Boolean bool) {
                                values.put(column, bool ? 1 : 0);
                            } else if (value instanceof Integer || value instanceof Long) {
                                values.put(column, ((Number) value).longValue());
                            } else if (value instanceof Number number) {
                                values.put(column, number.doubleValue());
                            } else {
                                values.put(column, value.toString());
                            }
                        }
                    }
                    if (db.update(table, values, where, whereArgs) == 0) {
                        db.insertOrThrow(table, null, values);
                    }
                }
                ContentValues revision = new ContentValues();
                revision.put("table_name", table);
                revision.put("record_key", key);
                revision.put("modified_at", modifiedAt);
                revision.put("origin_id", origin);
                revision.put("is_deleted", record.getBoolean("deleted") ? 1 : 0);
                db.insertWithOnConflict("sync_revisions", null, revision,
                        SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private String[] syncPrimaryKeys(String table) throws JSONException {
        for (String[] keys : SYNC_KEYS) {
            if (keys[0].equals(table)) {
                return java.util.Arrays.copyOfRange(keys, 1, keys.length);
            }
        }
        throw new JSONException("Unknown sync table.");
    }

    private String syncWhere(String[] keys) {
        StringBuilder where = new StringBuilder();
        for (String key : keys) {
            if (where.length() > 0) {
                where.append(" AND ");
            }
            where.append(key).append("=?");
        }
        return where.toString();
    }

    private static final class SyncRevision {
        final long modifiedAt;
        final String origin;

        SyncRevision(long modifiedAt, String origin) {
            this.modifiedAt = modifiedAt;
            this.origin = origin == null ? "" : origin;
        }
    }

    private void createPlannerBlockTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE planner_blocks ("
                + "block_id TEXT PRIMARY KEY, group_id TEXT NOT NULL, "
                + "group_title TEXT NOT NULL, group_caption TEXT NOT NULL, "
                + "title TEXT NOT NULL, subtitle TEXT NOT NULL DEFAULT '', "
                + "group_position INTEGER NOT NULL, position INTEGER NOT NULL, "
                + "is_deleted INTEGER NOT NULL DEFAULT 0)");
    }

    private void createQuickIncomeCategoryTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE quick_income_categories ("
                + "name TEXT PRIMARY KEY, is_deleted INTEGER NOT NULL DEFAULT 0)");
    }

    public void ensureQuickIncomeCategories(List<String> defaults) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (String name : defaults) {
                ContentValues values = new ContentValues();
                values.put("name", name);
                db.insertWithOnConflict("quick_income_categories", null, values,
                        SQLiteDatabase.CONFLICT_IGNORE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public List<String> getQuickIncomeCategories() {
        List<String> categories = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("quick_income_categories",
                new String[]{"name"}, "is_deleted = 0", null, null, null, "rowid ASC")) {
            while (cursor.moveToNext()) {
                categories.add(cursor.getString(0));
            }
        }
        return categories;
    }

    public void saveQuickIncomeCategory(String previousName, String name) {
        SQLiteDatabase db = getWritableDatabase();
        if (previousName != null && !previousName.equals(name)) {
            db.beginTransaction();
            try {
                ContentValues transactionCategory = new ContentValues();
                transactionCategory.put("category", name);
                db.update("transactions", transactionCategory, "category = ?",
                        new String[]{previousName});
                try (Cursor targetBudget = db.query("finance_budgets",
                        new String[]{"category"}, "category = ?", new String[]{name},
                        null, null, null)) {
                    if (targetBudget.moveToFirst()) {
                        db.delete("finance_budgets", "category = ?",
                                new String[]{previousName});
                    } else {
                        ContentValues oldBudget = new ContentValues();
                        oldBudget.put("category", name);
                        db.update("finance_budgets", oldBudget, "category = ?",
                                new String[]{previousName});
                    }
                }
                db.delete("quick_income_categories", "name = ?",
                        new String[]{previousName});
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }
        ContentValues values = new ContentValues();
        values.put("name", name);
        values.put("is_deleted", 0);
        db.insertWithOnConflict("quick_income_categories", null, values,
                SQLiteDatabase.CONFLICT_REPLACE);
    }

    public void deleteQuickIncomeCategory(String name) {
        ContentValues values = new ContentValues();
        values.put("is_deleted", 1);
        getWritableDatabase().update("quick_income_categories", values, "name = ?",
                new String[]{name});
    }

    public void ensureDailyBlocks(List<DailyBlock> defaults) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (DailyBlock block : defaults) {
                ContentValues values = new ContentValues();
                values.put("block_id", block.id);
                values.put("group_id", block.groupId);
                values.put("group_title", block.groupTitle);
                values.put("group_caption", block.groupCaption);
                values.put("title", block.title);
                values.put("subtitle", block.subtitle);
                values.put("group_position", block.groupPosition);
                values.put("position", block.position);
                db.insertWithOnConflict("planner_blocks", null, values,
                        SQLiteDatabase.CONFLICT_IGNORE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public List<DailyBlock> getDailyBlocks() {
        List<DailyBlock> blocks = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("planner_blocks",
                new String[]{"block_id", "group_id", "group_title", "group_caption",
                        "title", "subtitle", "group_position", "position"},
                "is_deleted = 0", null, null, null,
                "group_position ASC, position ASC, block_id ASC")) {
            while (cursor.moveToNext()) {
                blocks.add(new DailyBlock(cursor.getString(0), cursor.getString(1),
                        cursor.getString(2), cursor.getString(3), cursor.getString(4),
                        cursor.getString(5), cursor.getInt(6), cursor.getInt(7)));
            }
        }
        return blocks;
    }

    public void saveDailyBlock(DailyBlock block) {
        ContentValues values = new ContentValues();
        values.put("block_id", block.id);
        values.put("group_id", block.groupId);
        values.put("group_title", block.groupTitle);
        values.put("group_caption", block.groupCaption);
        values.put("title", block.title);
        values.put("subtitle", block.subtitle);
        values.put("group_position", block.groupPosition);
        values.put("position", block.position);
        values.put("is_deleted", 0);
        getWritableDatabase().insertWithOnConflict("planner_blocks", null, values,
                SQLiteDatabase.CONFLICT_REPLACE);
    }

    public int getNextDailyBlockPosition(String groupId) {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT COALESCE(MAX(position), -1) + 1 FROM planner_blocks "
                        + "WHERE group_id = ? AND is_deleted = 0",
                new String[]{groupId})) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    public void deleteDailyBlock(String blockId) {
        ContentValues values = new ContentValues();
        values.put("is_deleted", 1);
        getWritableDatabase().update("planner_blocks", values, "block_id = ?",
                new String[]{blockId});
    }

    private void createPlanningTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE finance_budgets ("
                + "category TEXT PRIMARY KEY, monthly_limit_cents INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE goal_progress ("
                + "goal_id INTEGER NOT NULL, progress_date TEXT NOT NULL, "
                + "note TEXT NOT NULL DEFAULT '', PRIMARY KEY(goal_id, progress_date), "
                + "FOREIGN KEY(goal_id) REFERENCES goals(id) ON DELETE CASCADE)");
        db.execSQL("CREATE TABLE voice_notes ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, "
                + "content TEXT NOT NULL, created_at TEXT NOT NULL)");
    }

    private void createDailyTrackerTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE daily_entries ("
                + "entry_date TEXT NOT NULL, block_id TEXT NOT NULL, "
                + "is_done INTEGER NOT NULL DEFAULT 0, "
                + "PRIMARY KEY(entry_date, block_id))");
        db.execSQL("CREATE TABLE daily_records ("
                + "entry_date TEXT PRIMARY KEY, effort TEXT NOT NULL DEFAULT 'NORMAL', "
                + "energy TEXT NOT NULL DEFAULT 'NORMAL', development_note TEXT NOT NULL DEFAULT '', "
                + "diary TEXT NOT NULL DEFAULT '', rest_activity TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE TABLE planner_settings (setting_key TEXT PRIMARY KEY, setting_value TEXT NOT NULL)");
    }

    public void saveChatMessage(ChatMessage message) {
        ContentValues values = new ContentValues();
        values.put("id", uniqueId());
        values.put("message", message.getMessage());
        values.put("is_user", message.isUser() ? 1 : 0);
        values.put("time", message.getTime());
        getWritableDatabase().insertOrThrow("chat_messages", null, values);
    }

    public List<ChatMessage> getChatMessages() {
        List<ChatMessage> messages = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(
                "chat_messages", new String[]{"message", "is_user", "time"},
                null, null, null, null, "id ASC")) {
            while (cursor.moveToNext()) {
                messages.add(new ChatMessage(cursor.getString(0), cursor.getInt(1) == 1,
                        cursor.getString(2)));
            }
        }
        return messages;
    }

    public long addTransaction(String title, long amountCents, boolean isIncome, String date) {
        return addTransaction(title, amountCents, isIncome, date, title);
    }

    public long addTransaction(String title, long amountCents, boolean isIncome,
                               String date, String category) {
        ContentValues values = new ContentValues();
        values.put("id", uniqueId());
        values.put("title", title);
        values.put("amount_cents", amountCents);
        values.put("type", isIncome ? 1 : -1);
        values.put("date", date);
        values.put("category", category);
        return getWritableDatabase().insertOrThrow("transactions", null, values);
    }

    public void deleteTransaction(long id) {
        getWritableDatabase().delete("transactions", "id = ?", new String[]{String.valueOf(id)});
    }

    public void updateTransaction(long id, String title, long amountCents, boolean isIncome,
                                  String date, String category) {
        ContentValues values = new ContentValues();
        values.put("title", title);
        values.put("amount_cents", amountCents);
        values.put("type", isIncome ? 1 : -1);
        values.put("date", date);
        values.put("category", category);
        getWritableDatabase().update("transactions", values, "id = ?",
                new String[]{String.valueOf(id)});
    }

    public List<Transaction> getTransactions() {
        List<Transaction> items = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(
                "transactions", new String[]{"id", "title", "amount_cents", "type", "date",
                        "category"},
                null, null, null, null, "date DESC, id DESC")) {
            while (cursor.moveToNext()) {
                items.add(new Transaction(cursor.getLong(0), cursor.getString(1),
                        cursor.getLong(2), cursor.getInt(3) == 1, cursor.getString(4),
                        cursor.getString(5)));
            }
        }
        return items;
    }

    public long[] getFinanceTotals() {
        long[] totals = new long[2];
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT type, COALESCE(SUM(amount_cents), 0) FROM transactions GROUP BY type",
                null)) {
            while (cursor.moveToNext()) {
                if (cursor.getInt(0) == 1) {
                    totals[0] = cursor.getLong(1);
                } else {
                    totals[1] = cursor.getLong(1);
                }
            }
        }
        return totals;
    }

    public long[] getFinanceTotals(String month) {
        long[] totals = new long[2];
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT type, COALESCE(SUM(amount_cents), 0) FROM transactions "
                        + "WHERE date LIKE ? GROUP BY type",
                new String[]{month + "-%"})) {
            while (cursor.moveToNext()) {
                if (cursor.getInt(0) == 1) {
                    totals[0] = cursor.getLong(1);
                } else {
                    totals[1] = cursor.getLong(1);
                }
            }
        }
        return totals;
    }

    public List<CategorySpending> getCategorySpending(String month) {
        List<CategorySpending> categories = new ArrayList<>();
        String query = "SELECT t.category, COALESCE(SUM(t.amount_cents), 0), "
                + "COALESCE(b.monthly_limit_cents, 0) FROM transactions t "
                + "LEFT JOIN finance_budgets b ON b.category = t.category "
                + "WHERE t.type = -1 AND t.date LIKE ? GROUP BY t.category, b.monthly_limit_cents "
                + "ORDER BY SUM(t.amount_cents) DESC";
        try (Cursor cursor = getReadableDatabase().rawQuery(query,
                new String[]{month + "-%"})) {
            while (cursor.moveToNext()) {
                categories.add(new CategorySpending(cursor.getString(0), cursor.getLong(1),
                        cursor.getLong(2)));
            }
        }
        return categories;
    }

    public void saveMonthlyBudget(String category, long amountCents) {
        ContentValues values = new ContentValues();
        values.put("category", category);
        values.put("monthly_limit_cents", amountCents);
        getWritableDatabase().insertWithOnConflict(
                "finance_budgets", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public long getMonthlyBudget(String category) {
        try (Cursor cursor = getReadableDatabase().query("finance_budgets",
                new String[]{"monthly_limit_cents"}, "category = ?", new String[]{category},
                null, null, null)) {
            return cursor.moveToFirst() ? cursor.getLong(0) : 0;
        }
    }

    public long addGoal(String title, String targetDate) {
        return addGoal(title, dateToday(), targetDate, "day");
    }

    public long addGoal(String title, String startDate, String targetDate, String term) {
        ContentValues values = new ContentValues();
        values.put("id", uniqueId());
        values.put("title", title);
        values.put("target_date", targetDate);
        values.put("start_date", startDate);
        values.put("term", term);
        return getWritableDatabase().insertOrThrow("goals", null, values);
    }

    public void updateGoal(long id, String title, String startDate,
                           String targetDate, String term) {
        ContentValues values = new ContentValues();
        values.put("title", title);
        values.put("start_date", startDate);
        values.put("target_date", targetDate);
        values.put("term", term);
        getWritableDatabase().update("goals", values, "id = ?",
                new String[]{String.valueOf(id)});
    }

    public List<Goal> getGoals() {
        List<Goal> items = new ArrayList<>();
        String query = "SELECT g.id, g.title, g.start_date, g.target_date, g.term, "
                + "(SELECT COUNT(*) FROM goal_progress p WHERE p.goal_id = g.id), "
                + "(SELECT COUNT(*) FROM goal_progress p WHERE p.goal_id = g.id "
                + "AND p.progress_date = date('now', 'localtime')) "
                + "FROM goals g ORDER BY g.target_date ASC, g.id DESC";
        try (Cursor cursor = getReadableDatabase().rawQuery(query, null)) {
            while (cursor.moveToNext()) {
                items.add(new Goal(cursor.getLong(0), cursor.getString(1), cursor.getString(2),
                        cursor.getString(3), cursor.getString(4), cursor.getInt(5),
                        cursor.getInt(6) > 0));
            }
        }
        return items;
    }

    public void deleteGoal(long id) {
        getWritableDatabase().delete("goals", "id = ?", new String[]{String.valueOf(id)});
    }

    public void markGoalProgress(long goalId, String date, String note) {
        ContentValues values = new ContentValues();
        values.put("goal_id", goalId);
        values.put("progress_date", date);
        values.put("note", note);
        getWritableDatabase().insertWithOnConflict(
                "goal_progress", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public void removeGoalProgress(long goalId, String date) {
        getWritableDatabase().delete("goal_progress", "goal_id = ? AND progress_date = ?",
                new String[]{String.valueOf(goalId), date});
    }

    public String getGoalProgressNote(long goalId, String date) {
        try (Cursor cursor = getReadableDatabase().query("goal_progress",
                new String[]{"note"}, "goal_id = ? AND progress_date = ?",
                new String[]{String.valueOf(goalId), date}, null, null, null)) {
            return cursor.moveToFirst() ? cursor.getString(0) : "";
        }
    }

    public List<GoalProgress> getGoalProgressHistory(long goalId, int limit) {
        List<GoalProgress> history = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("goal_progress",
                new String[]{"progress_date", "note"}, "goal_id = ?",
                new String[]{String.valueOf(goalId)}, null, null, "progress_date DESC",
                String.valueOf(Math.max(1, limit)))) {
            while (cursor.moveToNext()) {
                history.add(new GoalProgress(cursor.getString(0), cursor.getString(1)));
            }
        }
        return history;
    }

    public long addVoiceNote(String title, String content, String createdAt) {
        ContentValues values = new ContentValues();
        values.put("id", uniqueId());
        values.put("title", title);
        values.put("content", content);
        values.put("created_at", createdAt);
        return getWritableDatabase().insertOrThrow("voice_notes", null, values);
    }

    public List<VoiceNote> getVoiceNotes() {
        List<VoiceNote> notes = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("voice_notes",
                new String[]{"id", "title", "content", "created_at"},
                null, null, null, null, "id DESC")) {
            while (cursor.moveToNext()) {
                notes.add(new VoiceNote(cursor.getLong(0), cursor.getString(1),
                        cursor.getString(2), cursor.getString(3)));
            }
        }
        return notes;
    }

    public void deleteVoiceNote(long id) {
        getWritableDatabase().delete("voice_notes", "id = ?",
                new String[]{String.valueOf(id)});
    }

    public long addTask(String title, String date, String time, Long goalId) {
        ContentValues values = new ContentValues();
        values.put("id", uniqueId());
        values.put("title", title);
        values.put("task_date", date);
        values.put("task_time", time);
        if (goalId == null) {
            values.putNull("goal_id");
        } else {
            values.put("goal_id", goalId);
        }
        values.put("is_done", 0);
        return getWritableDatabase().insertOrThrow("planner_tasks", null, values);
    }

    public List<Task> getTasksForDate(String date) {
        List<Task> items = new ArrayList<>();
        String query = "SELECT t.id, t.title, t.task_date, t.task_time, t.is_done, "
                + "COALESCE(g.title, '') FROM planner_tasks t "
                + "LEFT JOIN goals g ON g.id = t.goal_id "
                + "WHERE t.task_date = ? ORDER BY t.task_time ASC, t.id ASC";
        try (Cursor cursor = getReadableDatabase().rawQuery(query, new String[]{date})) {
            while (cursor.moveToNext()) {
                items.add(new Task(cursor.getLong(0), cursor.getString(1), cursor.getString(2),
                        cursor.getString(3), cursor.getInt(4) == 1, cursor.getString(5)));
            }
        }
        return items;
    }

    public void setTaskDone(long id, boolean isDone) {
        ContentValues values = new ContentValues();
        values.put("is_done", isDone ? 1 : 0);
        getWritableDatabase().update("planner_tasks", values, "id = ?",
                new String[]{String.valueOf(id)});
    }

    public void deleteTask(long id) {
        getWritableDatabase().delete("planner_tasks", "id = ?", new String[]{String.valueOf(id)});
    }

    public DayRecord getDayRecord(String date) {
        try (Cursor cursor = getReadableDatabase().query("daily_records",
                new String[]{"effort", "energy", "development_note", "diary", "rest_activity"},
                "entry_date = ?", new String[]{date}, null, null, null)) {
            if (cursor.moveToFirst()) {
                return new DayRecord(cursor.getString(0), cursor.getString(1), cursor.getString(2),
                        cursor.getString(3), cursor.getString(4));
            }
        }
        return new DayRecord("NORMAL", "NORMAL", "", "", "");
    }

    public void updateDayRecord(String date, String effort, String energy,
                                String developmentNote, String diary, String restActivity) {
        ContentValues values = new ContentValues();
        values.put("entry_date", date);
        values.put("effort", effort);
        values.put("energy", energy);
        values.put("development_note", developmentNote);
        values.put("diary", diary);
        values.put("rest_activity", restActivity);
        getWritableDatabase().insertWithOnConflict(
                "daily_records", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public boolean isDailyEntryDone(String date, String blockId) {
        try (Cursor cursor = getReadableDatabase().query("daily_entries", new String[]{"is_done"},
                "entry_date = ? AND block_id = ?", new String[]{date, blockId},
                null, null, null)) {
            return cursor.moveToFirst() && cursor.getInt(0) == 1;
        }
    }

    public void setDailyEntry(String date, String blockId, boolean isDone) {
        ContentValues values = new ContentValues();
        values.put("entry_date", date);
        values.put("block_id", blockId);
        values.put("is_done", isDone ? 1 : 0);
        getWritableDatabase().insertWithOnConflict(
                "daily_entries", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public List<DayProgress> getMonthProgress(String month, int totalBlocks) {
        List<DayProgress> days = new ArrayList<>();
        String query = "SELECT entry_date, ?, SUM(is_done) FROM daily_entries "
                + "WHERE entry_date LIKE ? GROUP BY entry_date ORDER BY entry_date";
        try (Cursor cursor = getReadableDatabase().rawQuery(query,
                new String[]{String.valueOf(totalBlocks), month + "-%"})) {
            while (cursor.moveToNext()) {
                days.add(new DayProgress(cursor.getString(0), cursor.getInt(1),
                        cursor.getInt(2)));
            }
        }
        return days;
    }

    public int getTotalProgressDays() {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT COUNT(DISTINCT entry_date) FROM daily_entries WHERE is_done = 1", null)) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    public double getAverageDailyCompletion(int totalBlocks) {
        String query = "SELECT AVG(completion) FROM ("
                + "SELECT 100.0 * SUM(is_done) / ? AS completion "
                + "FROM daily_entries GROUP BY entry_date HAVING SUM(is_done) > 0)";
        try (Cursor cursor = getReadableDatabase().rawQuery(query,
                new String[]{String.valueOf(totalBlocks)})) {
            return cursor.moveToFirst() ? cursor.getDouble(0) : 0;
        }
    }

    public String getSetting(String key, String defaultValue) {
        try (Cursor cursor = getReadableDatabase().query("planner_settings",
                new String[]{"setting_value"}, "setting_key = ?", new String[]{key},
                null, null, null)) {
            return cursor.moveToFirst() ? cursor.getString(0) : defaultValue;
        }
    }

    public void saveSetting(String key, String value) {
        ContentValues values = new ContentValues();
        values.put("setting_key", key);
        values.put("setting_value", value);
        getWritableDatabase().insertWithOnConflict(
                "planner_settings", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public static class DayRecord {
        public final String effort;
        public final String energy;
        public final String developmentNote;
        public final String diary;
        public final String restActivity;

        DayRecord(String effort, String energy, String developmentNote,
                  String diary, String restActivity) {
            this.effort = effort;
            this.energy = energy;
            this.developmentNote = developmentNote;
            this.diary = diary;
            this.restActivity = restActivity;
        }
    }

    public static class DayProgress {
        public final String date;
        public final int total;
        public final int completed;

        DayProgress(String date, int total, int completed) {
            this.date = date;
            this.total = total;
            this.completed = completed;
        }
    }

    public static class Transaction {
        public final long id;
        public final String title;
        public final long amountCents;
        public final boolean isIncome;
        public final String date;
        public final String category;

        Transaction(long id, String title, long amountCents, boolean isIncome, String date,
                    String category) {
            this.id = id;
            this.title = title;
            this.amountCents = amountCents;
            this.isIncome = isIncome;
            this.date = date;
            this.category = category;
        }
    }

    public static class CategorySpending {
        public final String category;
        public final long amountCents;
        public final long budgetCents;

        CategorySpending(String category, long amountCents, long budgetCents) {
            this.category = category;
            this.amountCents = amountCents;
            this.budgetCents = budgetCents;
        }
    }

    public static class Goal {
        public final long id;
        public final String title;
        public final String targetDate;
        public final String startDate;
        public final String term;
        public final int progressDays;
        public final boolean progressedToday;

        Goal(long id, String title, String startDate, String targetDate, String term,
             int progressDays, boolean progressedToday) {
            this.id = id;
            this.title = title;
            this.startDate = startDate;
            this.targetDate = targetDate;
            this.term = term;
            this.progressDays = progressDays;
            this.progressedToday = progressedToday;
        }
    }

    public static class GoalProgress {
        public final String date;
        public final String note;

        GoalProgress(String date, String note) {
            this.date = date;
            this.note = note;
        }
    }

    public static class VoiceNote {
        public final long id;
        public final String title;
        public final String content;
        public final String createdAt;

        VoiceNote(long id, String title, String content, String createdAt) {
            this.id = id;
            this.title = title;
            this.content = content;
            this.createdAt = createdAt;
        }
    }

    private String dateToday() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(new java.util.Date());
    }

    public static class Task {
        public final long id;
        public final String title;
        public final String date;
        public final String time;
        public final boolean isDone;
        public final String goalTitle;

        Task(long id, String title, String date, String time, boolean isDone, String goalTitle) {
            this.id = id;
            this.title = title;
            this.date = date;
            this.time = time;
            this.isDone = isDone;
            this.goalTitle = goalTitle;
        }
    }
}
