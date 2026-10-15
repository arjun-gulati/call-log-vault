package com.calllogvault

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * The vault: this app's own unlimited SQLite store of call records.
 * There is no cap and no auto delete anywhere. Records are deduplicated
 * by the unique index on (number_norm, date_ms, type).
 */
class VaultDb(context: Context) : SQLiteOpenHelper(context, "vault.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE calls (" +
                "_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "system_id INTEGER NOT NULL DEFAULT -1, " +
                "number_raw TEXT NOT NULL, " +
                "number_norm TEXT NOT NULL, " +
                "name_cached TEXT NOT NULL DEFAULT '', " +
                "date_ms INTEGER NOT NULL, " +
                "type INTEGER NOT NULL, " +
                "duration_sec INTEGER NOT NULL DEFAULT 0, " +
                "subscription_id TEXT NOT NULL DEFAULT '')"
        )
        db.execSQL("CREATE UNIQUE INDEX idx_calls_dedupe ON calls(number_norm, date_ms, type)")
        db.execSQL("CREATE INDEX idx_calls_date ON calls(date_ms)")
        db.execSQL("CREATE INDEX idx_calls_number ON calls(number_norm)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1: nothing to upgrade yet.
    }

    /** Insert one record. Returns true when it was new, false when a duplicate. */
    fun insertIgnore(db: SQLiteDatabase, r: VaultLogic.CallRecord): Boolean {
        val cv = ContentValues()
        cv.put("system_id", r.systemId)
        cv.put("number_raw", r.numberRaw)
        cv.put("number_norm", r.numberNorm)
        cv.put("name_cached", r.name)
        cv.put("date_ms", r.dateMs)
        cv.put("type", r.type)
        cv.put("duration_sec", r.durationSec)
        cv.put("subscription_id", r.subscriptionId)
        return db.insertWithOnConflict("calls", null, cv, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    fun count(db: SQLiteDatabase): Long {
        db.rawQuery("SELECT COUNT(*) FROM calls", null).use { c ->
            return if (c.moveToFirst()) c.getLong(0) else 0L
        }
    }

    fun oldestDate(db: SQLiteDatabase): Long {
        db.rawQuery("SELECT MIN(date_ms) FROM calls", null).use { c ->
            return if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
        }
    }

    fun totalDuration(db: SQLiteDatabase): Long {
        db.rawQuery("SELECT COALESCE(SUM(duration_sec), 0) FROM calls", null).use { c ->
            return if (c.moveToFirst()) c.getLong(0) else 0L
        }
    }

    fun maxDate(db: SQLiteDatabase): Long {
        db.rawQuery("SELECT MAX(date_ms) FROM calls", null).use { c ->
            return if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
        }
    }

    fun allKeys(db: SQLiteDatabase): HashSet<String> {
        val set = HashSet<String>()
        db.rawQuery("SELECT number_norm, date_ms, type FROM calls", null).use { c ->
            while (c.moveToNext()) {
                set.add(VaultLogic.dedupeKey(c.getString(0), c.getLong(1), c.getInt(2)))
            }
        }
        return set
    }

    /**
     * Column positions for one cursor, resolved once per query.
     * Before v1.0.6 readRow called getColumnIndexOrThrow (a name
     * lookup over the cursor's column list) eight times for EVERY
     * row, so loading the whole vault paid thousands of lookups for
     * positions that cannot change inside one cursor.
     */
    private class RowColumns(c: android.database.Cursor) {
        val id = c.getColumnIndexOrThrow("_id")
        val numberRaw = c.getColumnIndexOrThrow("number_raw")
        val numberNorm = c.getColumnIndexOrThrow("number_norm")
        val name = c.getColumnIndexOrThrow("name_cached")
        val dateMs = c.getColumnIndexOrThrow("date_ms")
        val type = c.getColumnIndexOrThrow("type")
        val durationSec = c.getColumnIndexOrThrow("duration_sec")
        val subscriptionId = c.getColumnIndexOrThrow("subscription_id")
        val systemId = c.getColumnIndexOrThrow("system_id")
    }

    private fun readRow(c: android.database.Cursor, cols: RowColumns): VaultLogic.CallRecord {
        return VaultLogic.CallRecord(
            numberRaw = c.getString(cols.numberRaw),
            numberNorm = c.getString(cols.numberNorm),
            name = c.getString(cols.name),
            dateMs = c.getLong(cols.dateMs),
            type = c.getInt(cols.type),
            durationSec = c.getLong(cols.durationSec),
            subscriptionId = c.getString(cols.subscriptionId),
            systemId = c.getLong(cols.systemId),
            vaultId = c.getLong(cols.id)
        )
    }

    /**
     * Delete one record by its vault row id. Manual deletes from the
     * person screen are the only deletes that exist anywhere in the
     * app, and both are behind a confirmation dialog. Returns the
     * number of rows removed (0 or 1).
     */
    fun deleteRecord(db: SQLiteDatabase, vaultId: Long): Int {
        if (vaultId < 0) return 0
        return db.delete("calls", "_id = ?", arrayOf(vaultId.toString()))
    }

    /** Delete every record for one normalized number. Returns rows removed. */
    fun deleteNumber(db: SQLiteDatabase, numberNorm: String): Int {
        return db.delete("calls", "number_norm = ?", arrayOf(numberNorm))
    }

    /** All records, newest first. */
    fun all(db: SQLiteDatabase): List<VaultLogic.CallRecord> {
        val out = ArrayList<VaultLogic.CallRecord>()
        db.query("calls", null, null, null, null, null, "date_ms DESC").use { c ->
            val cols = RowColumns(c)
            while (c.moveToNext()) out.add(readRow(c, cols))
        }
        return out
    }

    /** All records, oldest first. Used for export files. */
    fun allAscending(db: SQLiteDatabase): List<VaultLogic.CallRecord> {
        val out = ArrayList<VaultLogic.CallRecord>()
        db.query("calls", null, null, null, null, null, "date_ms ASC").use { c ->
            val cols = RowColumns(c)
            while (c.moveToNext()) out.add(readRow(c, cols))
        }
        return out
    }

    fun forNumber(db: SQLiteDatabase, numberNorm: String): List<VaultLogic.CallRecord> {
        val out = ArrayList<VaultLogic.CallRecord>()
        db.query(
            "calls", null, "number_norm = ?", arrayOf(numberNorm),
            null, null, "date_ms DESC"
        ).use { c ->
            val cols = RowColumns(c)
            while (c.moveToNext()) out.add(readRow(c, cols))
        }
        return out
    }

    companion object {
        @Volatile private var instance: VaultDb? = null
        fun get(context: Context): VaultDb {
            return instance ?: synchronized(this) {
                instance ?: VaultDb(context.applicationContext).also { instance = it }
            }
        }
    }
}
