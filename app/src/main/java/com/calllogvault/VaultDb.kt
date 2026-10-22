package com.calllogvault

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * The vault: this app's own unlimited SQLite store of call records.
 * There is no cap and no auto delete anywhere. Records are deduplicated
 * by the unique index on (number_norm, date_ms, type).
 *
 * Database v2 (app v1.2.0) adds the tombstones table: one row per
 * manually deleted call, holding ONLY the SHA-256 of its dedupe key
 * and the delete time. Sync, backup import and the watch folder
 * merge all refuse tombstoned keys, so a manual delete is permanent
 * on this device without the table revealing what was deleted.
 *
 * Database v3 (app v1.2.8) adds two tables. Bin holds the FULL row
 * of every call deleted from this version onward, so a delete can
 * be undone for 30 days; the tombstone written at the same moment
 * keeps the call refused by every import path while it sits in
 * the bin, and a restore removes that tombstone again. Deletes
 * from before v3 left only their tombstone hash and can never be
 * recovered. Pause_windows holds one row per pause the user ever
 * started: import paths refuse any call dated inside a window,
 * even after the window has ended. A pause with no planned end
 * (v1.2.9) is stored with VaultLogic.OPEN_PAUSE_END_MS as its
 * end_ms; resuming rewrites that to the resume moment.
 */
class VaultDb(context: Context) : SQLiteOpenHelper(context, "vault.db", null, 3) {

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
        db.execSQL(CREATE_TOMBSTONES)
        db.execSQL(CREATE_BIN)
        db.execSQL(CREATE_PAUSE_WINDOWS)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // v2: tombstones for manual deletes. No existing data changes.
            db.execSQL(CREATE_TOMBSTONES)
        }
        if (oldVersion < 3) {
            // v3: the bin and the pause windows. No existing data changes;
            // deletes made before this version stay tombstone only.
            db.execSQL(CREATE_BIN)
            db.execSQL(CREATE_PAUSE_WINDOWS)
        }
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
     * Every dedupe key in the vault mapped to its saved name. The
     * watch folder merge plans against this map (which records are
     * new, which saved names an import would replace).
     */
    fun namesByKey(db: SQLiteDatabase): HashMap<String, String> {
        val map = HashMap<String, String>()
        db.rawQuery("SELECT number_norm, date_ms, type, name_cached FROM calls", null).use { c ->
            while (c.moveToNext()) {
                map[VaultLogic.dedupeKey(c.getString(0), c.getLong(1), c.getInt(2))] =
                    c.getString(3) ?: ""
            }
        }
        return map
    }

    /**
     * Replace only the saved name of the row with this record's
     * dedupe identity. Used by the watch folder merge when a later
     * import carries a different name for a call the vault already
     * holds. Returns the number of rows changed (0 or 1).
     */
    fun updateName(db: SQLiteDatabase, r: VaultLogic.CallRecord): Int {
        val cv = ContentValues()
        cv.put("name_cached", r.name)
        return db.update(
            "calls", cv, "number_norm = ? AND date_ms = ? AND type = ?",
            arrayOf(r.numberNorm, r.dateMs.toString(), r.type.toString())
        )
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

    // ------------------------------------------------------- Tombstones

    /** Every stored tombstone hash, for the acceptance checks. */
    fun tombstoneHashes(db: SQLiteDatabase): HashSet<String> {
        val set = HashSet<String>()
        db.rawQuery("SELECT key_hash FROM tombstones", null).use { c ->
            while (c.moveToNext()) set.add(c.getString(0))
        }
        return set
    }

    /** How many tombstones exist. The table never says what they were. */
    fun tombstoneCount(db: SQLiteDatabase): Long {
        db.rawQuery("SELECT COUNT(*) FROM tombstones", null).use { c ->
            return if (c.moveToFirst()) c.getLong(0) else 0L
        }
    }

    /** Write one tombstone for [key]. Call inside a transaction. */
    private fun addTombstone(db: SQLiteDatabase, key: String, deletedAtMs: Long) {
        val cv = ContentValues()
        cv.put("key_hash", VaultLogic.tombstoneHash(key))
        cv.put("deleted_at", deletedAtMs)
        db.insertWithOnConflict("tombstones", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
    }

    /** Copy one record's full row into the bin. Call inside a transaction. */
    private fun addToBin(db: SQLiteDatabase, r: VaultLogic.CallRecord, deletedAtMs: Long) {
        val cv = ContentValues()
        cv.put("system_id", r.systemId)
        cv.put("number_raw", r.numberRaw)
        cv.put("number_norm", r.numberNorm)
        cv.put("name_cached", r.name)
        cv.put("date_ms", r.dateMs)
        cv.put("type", r.type)
        cv.put("duration_sec", r.durationSec)
        cv.put("subscription_id", r.subscriptionId)
        cv.put("deleted_at", deletedAtMs)
        db.insert("bin", null, cv)
    }

    /**
     * Delete [records] from the vault, tombstone every one of
     * their keys, and move each removed row into the bin, in a
     * single transaction: the delete, its tombstone and its bin
     * copy land together or not at all. The tombstone means the
     * call stays refused by sync and imports while it sits in
     * the bin; only a restore from the bin (or 30 days passing)
     * settles it. Manual deletes from the person screen are the
     * only deletes that exist anywhere in the app, each behind
     * the two flavor confirmation dialog. Returns the number of
     * rows removed.
     */
    fun deleteEntries(
        db: SQLiteDatabase,
        records: List<VaultLogic.CallRecord>,
        deletedAtMs: Long
    ): Int {
        var removed = 0
        db.beginTransaction()
        try {
            for (r in records) {
                addTombstone(db, VaultLogic.dedupeKey(r), deletedAtMs)
                if (r.vaultId >= 0) {
                    val n = db.delete("calls", "_id = ?", arrayOf(r.vaultId.toString()))
                    if (n > 0) {
                        addToBin(db, r, deletedAtMs)
                        removed += n
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return removed
    }

    /** Delete every record for one normalized number, with tombstones. */
    fun deleteNumberEntries(
        db: SQLiteDatabase,
        numberNorm: String,
        deletedAtMs: Long
    ): Int {
        return deleteEntries(db, forNumber(db, numberNorm), deletedAtMs)
    }

    // ------------------------------------------------------------- Bin

    /** Every binned call, most recently deleted first. */
    fun binEntries(db: SQLiteDatabase): List<VaultLogic.BinEntry> {
        val out = ArrayList<VaultLogic.BinEntry>()
        db.query("bin", null, null, null, null, null, "deleted_at DESC, date_ms DESC").use { c ->
            val cols = RowColumns(c)
            val deletedAtCol = c.getColumnIndexOrThrow("deleted_at")
            while (c.moveToNext()) {
                // The row id read here is the bin's own, so the
                // record's vaultId is cleared: it is not a calls row.
                out.add(
                    VaultLogic.BinEntry(
                        c.getLong(cols.id),
                        readRow(c, cols).copy(vaultId = -1),
                        c.getLong(deletedAtCol)
                    )
                )
            }
        }
        return out
    }

    fun binCount(db: SQLiteDatabase): Long {
        db.rawQuery("SELECT COUNT(*) FROM bin", null).use { c ->
            return if (c.moveToFirst()) c.getLong(0) else 0L
        }
    }

    /** One binned call by its bin row id, or null when it is gone. */
    fun binEntry(db: SQLiteDatabase, binId: Long): VaultLogic.BinEntry? {
        db.query("bin", null, "_id = ?", arrayOf(binId.toString()), null, null, null).use { c ->
            if (!c.moveToFirst()) return null
            val cols = RowColumns(c)
            return VaultLogic.BinEntry(
                c.getLong(cols.id), readRow(c, cols).copy(vaultId = -1),
                c.getLong(c.getColumnIndexOrThrow("deleted_at"))
            )
        }
    }

    /**
     * Restore one binned call: its row goes back into the vault
     * and its tombstone is removed in the same transaction, so
     * the call is fully back and stays back. The bin row goes
     * either way. Returns true when the vault row was newly
     * reinserted (false when the vault somehow already held it).
     */
    fun restoreFromBin(db: SQLiteDatabase, binId: Long): Boolean {
        val entry = binEntry(db, binId) ?: return false
        var inserted = false
        db.beginTransaction()
        try {
            db.delete(
                "tombstones", "key_hash = ?",
                arrayOf(VaultLogic.tombstoneHash(VaultLogic.dedupeKey(entry.record)))
            )
            inserted = insertIgnore(db, entry.record)
            db.delete("bin", "_id = ?", arrayOf(binId.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return inserted
    }

    /** Delete one bin row for good. Its tombstone stays, as always. */
    fun deleteBinForever(db: SQLiteDatabase, binId: Long): Int {
        return db.delete("bin", "_id = ?", arrayOf(binId.toString()))
    }

    /** Empty the whole bin. Every tombstone stays, as always. */
    fun emptyBin(db: SQLiteDatabase): Int {
        return db.delete("bin", null, null)
    }

    /**
     * Purge bin rows whose 30 days are up. The tombstone of a
     * purged row stays, so the call remains refused forever; only
     * the restorable copy goes. Returns the number purged.
     */
    fun purgeExpiredBin(db: SQLiteDatabase, nowMs: Long): Int {
        val expired = ArrayList<Long>()
        db.query("bin", arrayOf("_id", "deleted_at"), null, null, null, null, null).use { c ->
            while (c.moveToNext()) {
                if (VaultLogic.isBinExpired(c.getLong(1), nowMs)) expired.add(c.getLong(0))
            }
        }
        var removed = 0
        for (id in expired) removed += db.delete("bin", "_id = ?", arrayOf(id.toString()))
        return removed
    }

    // ----------------------------------------------------- Pause windows

    /** Every recorded pause window, oldest first. */
    fun pauseWindows(db: SQLiteDatabase): List<VaultLogic.PauseWindow> {
        val out = ArrayList<VaultLogic.PauseWindow>()
        db.query("pause_windows", arrayOf("start_ms", "end_ms"), null, null, null, null, "start_ms ASC")
            .use { c ->
                while (c.moveToNext()) {
                    out.add(VaultLogic.PauseWindow(c.getLong(0), c.getLong(1)))
                }
            }
        return out
    }

    /** Record the start of a pause. Returns the new row id. */
    fun startPause(db: SQLiteDatabase, startMs: Long, endMs: Long): Long {
        val cv = ContentValues()
        cv.put("start_ms", startMs)
        cv.put("end_ms", endMs)
        return db.insert("pause_windows", null, cv)
    }

    /**
     * End the pause in force at [nowMs] right now: its stored end
     * is clamped to this moment, so the window stays on record
     * (calls inside it stay refused) but nothing after now is
     * blocked. Returns true when a live window was ended. An
     * open pause (v1.2.9, stored with VaultLogic.OPEN_PAUSE_END_MS
     * as its end) is ended the same way: it sorts first under
     * end_ms DESC and its clamp lands on now, turning it into a
     * finished window on record.
     */
    fun endPauseNow(db: SQLiteDatabase, nowMs: Long): Boolean {
        db.query(
            "pause_windows", arrayOf("_id", "start_ms", "end_ms"),
            "start_ms <= ? AND end_ms > ?", arrayOf(nowMs.toString(), nowMs.toString()),
            null, null, "end_ms DESC"
        ).use { c ->
            if (!c.moveToFirst()) return false
            val id = c.getLong(0)
            val window = VaultLogic.PauseWindow(c.getLong(1), c.getLong(2))
            val cv = ContentValues()
            cv.put("end_ms", VaultLogic.clampedPauseEnd(window, nowMs))
            return db.update("pause_windows", cv, "_id = ?", arrayOf(id.toString())) > 0
        }
    }

    /** Drop windows that ended long ago (see VaultLogic.isPauseWindowStale). */
    fun prunePauseWindows(db: SQLiteDatabase, nowMs: Long): Int {
        val stale = ArrayList<Long>()
        db.query("pause_windows", arrayOf("_id", "start_ms", "end_ms"), null, null, null, null, null)
            .use { c ->
                while (c.moveToNext()) {
                    val w = VaultLogic.PauseWindow(c.getLong(1), c.getLong(2))
                    if (VaultLogic.isPauseWindowStale(w, nowMs)) stale.add(c.getLong(0))
                }
            }
        var removed = 0
        for (id in stale) removed += db.delete("pause_windows", "_id = ?", arrayOf(id.toString()))
        return removed
    }

    /**
     * The cheap upkeep that rides existing wakeups (app open,
     * syncs, job runs): purge expired bin rows and prune pause
     * windows older than 90 days past their end. Two small scans,
     * no new jobs, services or alarms.
     */
    fun housekeeping(db: SQLiteDatabase, nowMs: Long) {
        try {
            purgeExpiredBin(db, nowMs)
            prunePauseWindows(db, nowMs)
        } catch (e: Exception) {
            // Upkeep must never sink the wakeup that carried it.
        }
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
        private const val CREATE_TOMBSTONES =
            "CREATE TABLE IF NOT EXISTS tombstones (" +
                "key_hash TEXT PRIMARY KEY, " +
                "deleted_at INTEGER NOT NULL)"

        private const val CREATE_BIN =
            "CREATE TABLE IF NOT EXISTS bin (" +
                "_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "system_id INTEGER NOT NULL DEFAULT -1, " +
                "number_raw TEXT NOT NULL, " +
                "number_norm TEXT NOT NULL, " +
                "name_cached TEXT NOT NULL DEFAULT '', " +
                "date_ms INTEGER NOT NULL, " +
                "type INTEGER NOT NULL, " +
                "duration_sec INTEGER NOT NULL DEFAULT 0, " +
                "subscription_id TEXT NOT NULL DEFAULT '', " +
                "deleted_at INTEGER NOT NULL)"

        private const val CREATE_PAUSE_WINDOWS =
            "CREATE TABLE IF NOT EXISTS pause_windows (" +
                "_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "start_ms INTEGER NOT NULL, " +
                "end_ms INTEGER NOT NULL)"

        @Volatile private var instance: VaultDb? = null
        fun get(context: Context): VaultDb {
            return instance ?: synchronized(this) {
                instance ?: VaultDb(context.applicationContext).also { instance = it }
            }
        }
    }
}
