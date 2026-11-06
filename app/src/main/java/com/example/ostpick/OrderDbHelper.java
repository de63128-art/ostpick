package com.example.ostpick;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;

public class OrderDbHelper extends SQLiteOpenHelper {
    public static class Order {
        public long id;
        public String number;
        public boolean done;
        public String completedAt;
        public String location;
    }
    public OrderDbHelper(Context context) {
        super(context, "ostpick.db", null, 2);
    }
    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE orders ("
                + "_id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "number TEXT NOT NULL UNIQUE, "
                + "done INTEGER NOT NULL DEFAULT 0, "
                + "completed_at TEXT NOT NULL DEFAULT '', "
                + "location TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_orders_location_done ON orders(location, done)");
    }
    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            // 原地升级：保留旧单号、完成状态及时间；旧记录库位先留空。
            db.execSQL("ALTER TABLE orders ADD COLUMN location TEXT NOT NULL DEFAULT ''");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_orders_location_done ON orders(location, done)");
        }
    }
    private Order readOrder(Cursor cursor) {
        Order order = new Order();
        order.id = cursor.getLong(0);
        order.number = cursor.getString(1);
        order.done = cursor.getInt(2) == 1;
        order.completedAt = cursor.getString(3);
        order.location = cursor.getString(4);
        return order;
    }
    public List<Order> getAll() {
        List<Order> orders = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("orders",
                new String[]{"_id", "number", "done", "completed_at", "location"},
                null, null, null, null, "_id ASC")) {
            while (cursor.moveToNext()) orders.add(readOrder(cursor));
        }
        return orders;
    }
    public Order getById(long id) {
        try (Cursor cursor = getReadableDatabase().query("orders",
                new String[]{"_id", "number", "done", "completed_at", "location"},
                "_id = ?", new String[]{String.valueOf(id)}, null, null, null)) {
            return cursor.moveToFirst() ? readOrder(cursor) : null;
        }
    }
    public boolean exists(String number, long excludedId) {
        try (Cursor cursor = getReadableDatabase().query("orders",
                new String[]{"_id"}, "number = ? AND _id <> ?",
                new String[]{number, String.valueOf(excludedId)},
                null, null, null, "1")) {
            return cursor.moveToFirst();
        }
    }
    public void add(String number, String location) {
        ContentValues values = new ContentValues();
        values.put("number", number.trim());
        values.put("location", location.trim());
        getWritableDatabase().insertOrThrow("orders", null, values);
    }
    /** 采集确认：新增或覆盖同号库位，保持该单号的完成状态和时间。 */
    public boolean saveCollected(String number, String location) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            long existingId = -1;
            try (Cursor cursor = db.query("orders", new String[]{"_id"},
                    "number = ?", new String[]{number.trim()}, null, null, null, "1")) {
                if (cursor.moveToFirst()) existingId = cursor.getLong(0);
            }
            ContentValues values = new ContentValues();
            values.put("number", number.trim());
            values.put("location", location.trim());
            boolean created = existingId < 0;
            if (created) {
                db.insertOrThrow("orders", null, values);
            } else {
                // 包括空库位也覆盖；不使用 REPLACE，避免丢失 DONE 和完成时间。
                int changed = db.update("orders", values, "_id = ?",
                        new String[]{String.valueOf(existingId)});
                if (changed != 1) throw new SQLiteException("单号更新失败，请重试");
            }
            db.setTransactionSuccessful();
            return created;
        } finally {
            db.endTransaction();
        }
    }

    public void update(long id, String number, String location) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            Order previous = getById(id);
            if (previous == null) throw new SQLiteException("记录已被删除，请刷新列表");
            ContentValues values = new ContentValues();
            values.put("number", number.trim());
            values.put("location", location.trim());
            // 只改库位不重置 DONE；修改单号内容才重置。
            if (!number.trim().equals(previous.number)) {
                values.put("done", 0);
                values.put("completed_at", "");
            }
            db.update("orders", values, "_id = ?", new String[]{String.valueOf(id)});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }
    public void delete(long id) {
        getWritableDatabase().delete("orders", "_id = ?", new String[]{String.valueOf(id)});
    }
    public void deleteAll() {
        getWritableDatabase().delete("orders", null, null);
    }
    public int countPendingAtLocation(String location) {
        if (location == null || location.trim().isEmpty()) return -1;
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM orders WHERE location = ? AND done = 0",
                new String[]{location.trim()})) {
            cursor.moveToFirst();
            return cursor.getInt(0);
        }
    }
    public boolean markDone(long id, String number, String location, String completedAt) {
        ContentValues values = new ContentValues();
        values.put("done", 1);
        values.put("completed_at", completedAt);
        return getWritableDatabase().update("orders", values,
                "_id = ? AND number = ? AND location = ? AND done = 0",
                new String[]{String.valueOf(id), number, location}) == 1;
    }
    public static class ImportResult {
        public int added;
        public int duplicates;
        public int locationFilled;
        public int conflicts;
        public int empty;
        public int invalid;
        public int headerSkipped;
        public String summary() {
            return "采集完成：新增 " + added + " 条，已有 " + duplicates
                    + " 条（补充库位 " + locationFilled + " 条），空值 " + empty
                    + " 条，无效 " + invalid + " 条，跳过表头 " + headerSkipped + " 行"
                    + (conflicts == 0 ? "" : "\n库位冲突 " + conflicts
                    + " 条，保留原库位；请在列表编辑核对。");
        }
    }
    public ImportResult importRows(List<GoogleSheetImporter.SheetRow> rows, boolean skipHeader) {
        ImportResult result = new ImportResult();
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (int i = 0; i < rows.size(); i++) {
                if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("导入已取消");
                if (skipHeader && i == 0) {
                    result.headerSkipped++;
                    continue;
                }
                String number = rows.get(i).number.trim();
                String location = rows.get(i).location.trim();
                if (number.isEmpty()) {
                    result.empty++;
                    continue;
                }
                if ("DONE".equalsIgnoreCase(number) || hasControl(number) || hasControl(location)) {
                    result.invalid++;
                    continue;
                }
                ContentValues item = new ContentValues();
                item.put("number", number);
                item.put("location", location);
                long id = db.insertWithOnConflict("orders", null, item, SQLiteDatabase.CONFLICT_IGNORE);
                if (id != -1) {
                    result.added++;
                    continue;
                }
                result.duplicates++;
                // 重复单号只补空库位。已有非空库位不被远程数据静默覆盖。
                if (!location.isEmpty()) {
                    try (Cursor cursor = db.query("orders", new String[]{"location"},
                            "number = ?", new String[]{number}, null, null, null)) {
                        if (!cursor.moveToFirst()) throw new SQLiteException("无法读取重复单号");
                        String previousLocation = cursor.getString(0);
                        if (previousLocation.isEmpty()) {
                            ContentValues fill = new ContentValues();
                            fill.put("location", location);
                            db.update("orders", fill, "number = ?", new String[]{number});
                            result.locationFilled++;
                        } else if (!previousLocation.equals(location)) {
                            result.conflicts++;
                        }
                    }
                }
            }
            if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("导入已取消");
            db.setTransactionSuccessful();
            return result;
        } finally {
            db.endTransaction();
        }
    }
    private boolean hasControl(String text) {
        return text.contains("\n") || text.contains("\r") || text.indexOf('\0') >= 0;
    }
}
