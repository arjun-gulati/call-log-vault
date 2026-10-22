package com.calllogvault

import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract

/**
 * Sync from the system call log into the vault, and restore from the
 * vault back into the system call log. All functions here are blocking
 * and must be called from a background thread.
 */
object SyncEngine {

    data class SyncResult(val added: Int, val skipped: Int, val seen: Int, val error: String? = null)
    data class RestoreResult(val inserted: Int, val skipped: Int, val error: String? = null)

    private const val PREFS = "vault_prefs"
    private const val KEY_LAST_SYNC_MS = "last_sync_ms"
    private const val KEY_LAST_SYNC_ADDED = "last_sync_added"
    private const val KEY_LAST_SYNC_ERROR = "last_sync_error"
    private const val WINDOW_MS = 7L * 24 * 60 * 60 * 1000

    /**
     * Exactly the columns the sync scan reads (v1.0.6). The scan used
     * to query with no projection, so the provider marshalled every
     * column of every call (photo URIs, geocoded country and address,
     * and other wide fields this app never touches) across the
     * process boundary for thousands of rows on every run.
     * subscription_id has no public CallLog.Calls constant, so the
     * literal column name rides along; see the retry below.
     */
    private val CALL_PROJECTION = arrayOf(
        CallLog.Calls._ID,
        CallLog.Calls.NUMBER,
        CallLog.Calls.CACHED_NAME,
        CallLog.Calls.DATE,
        CallLog.Calls.TYPE,
        CallLog.Calls.DURATION,
        CallLog.Calls.PHONE_ACCOUNT_ID,
        "subscription_id"
    )

    /** Standard CallLog.Calls constants only, the quirk safe retry. */
    private val CALL_PROJECTION_FALLBACK = arrayOf(
        CallLog.Calls._ID,
        CallLog.Calls.NUMBER,
        CallLog.Calls.CACHED_NAME,
        CallLog.Calls.DATE,
        CallLog.Calls.TYPE,
        CallLog.Calls.DURATION,
        CallLog.Calls.PHONE_ACCOUNT_ID
    )

    fun lastSyncMs(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_SYNC_MS, 0L)

    fun lastSyncAdded(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_LAST_SYNC_ADDED, 0)

    /** Error text of the most recent sync outcome, empty when it succeeded. */
    fun lastSyncError(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST_SYNC_ERROR, "") ?: ""

    /** Record the outcome of a sync attempt so Status can show it later. */
    fun recordSyncError(context: Context, error: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LAST_SYNC_ERROR, error)
            .apply()
    }

    /** Contacts display name for a number, used only when the system log has no cached name. */
    private fun lookupContactName(context: Context, number: String): String {
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)
            )
            context.contentResolver.query(
                uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0) ?: "" else ""
            } ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Copy new system call log entries into the vault, and persist the
     * outcome (error text, empty on success) for the Status diagnostics.
     */
    fun sync(context: Context, windowed: Boolean): SyncResult {
        val result = syncInternal(context, windowed)
        recordSyncError(context, result.error ?: "")
        return result
    }

    /**
     * First run (empty vault) imports everything. Later runs only scan a
     * window ending at the newest vault date, and the dedupe key plus the
     * unique index make a repeated scan harmless.
     */
    private fun syncInternal(context: Context, windowed: Boolean): SyncResult {
        val helper = VaultDb.get(context)
        val db = helper.writableDatabase
        var added = 0
        var skipped = 0
        var seen = 0
        try {
            // Cheap upkeep rides this existing wakeup (v1.2.8):
            // expired bin rows purged, old pause windows pruned.
            helper.housekeeping(db, System.currentTimeMillis())
            // Pause windows (v1.2.8): while a pause is in force
            // this scan imports nothing at all. The per record
            // date filter below is the permanent backstop: after
            // the pause ends, its stored window still refuses the
            // calls that happened inside it.
            val pauseWindows = helper.pauseWindows(db)
            if (VaultLogic.isPausedAt(System.currentTimeMillis(), pauseWindows)) {
                return SyncResult(0, 0, 0)
            }
            val since: Long? = if (windowed) {
                val max = helper.maxDate(db)
                if (max > 0) max - WINDOW_MS else null
            } else null

            val selection = if (since != null) CallLog.Calls.DATE + " >= ?" else null
            val args = if (since != null) arrayOf(since.toString()) else null

            // Explicit projection (see CALL_PROJECTION). Column
            // indices are still resolved defensively after the query.
            // If a provider rejects the projected set (an OEM quirk,
            // most likely over the constant-less subscription_id
            // column), the scan retries once with the constants-only
            // projection before giving up; the phone account id then
            // fills the subscription slot, as the row code below
            // already falls back to it.
            val sortOrder = CallLog.Calls.DATE + " ASC"
            val cursor = try {
                context.contentResolver.query(
                    CallLog.Calls.CONTENT_URI, CALL_PROJECTION, selection, args, sortOrder
                )
            } catch (e: Exception) {
                context.contentResolver.query(
                    CallLog.Calls.CONTENT_URI, CALL_PROJECTION_FALLBACK, selection, args, sortOrder
                )
            }
            cursor?.use { c ->
                val iId = c.getColumnIndex(CallLog.Calls._ID)
                val iNumber = c.getColumnIndex(CallLog.Calls.NUMBER)
                val iName = c.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val iDate = c.getColumnIndex(CallLog.Calls.DATE)
                val iType = c.getColumnIndex(CallLog.Calls.TYPE)
                val iDuration = c.getColumnIndex(CallLog.Calls.DURATION)
                val iSub = c.getColumnIndex("subscription_id")
                val iPhoneAccount = c.getColumnIndex(CallLog.Calls.PHONE_ACCOUNT_ID)
                if (iNumber < 0 || iDate < 0 || iType < 0) {
                    return SyncResult(0, 0, 0, "System call log columns not available")
                }
                val seenKeys = HashSet<String>()
                // Manual deletes leave tombstones (v1.2.0): a call
                // the user deleted from the vault on purpose must
                // never be copied straight back in by this scan,
                // even while it still sits in the phone's own log.
                val tombstones = helper.tombstoneHashes(db)
                // Speed: one transaction wraps every insert below (VaultDb
                // uses insertWithOnConflict with CONFLICT_IGNORE), and
                // contact lookups are cached per normalized number for the
                // duration of this run, so a 2000 row first import does at
                // most one PhoneLookup per distinct unknown number.
                val nameCache = HashMap<String, String>()
                db.beginTransaction()
                try {
                    while (c.moveToNext()) {
                        val number = c.getString(iNumber) ?: continue
                        if (number.isBlank()) continue
                        val date = c.getLong(iDate)
                        val type = c.getInt(iType)
                        val norm = VaultLogic.normalizeNumber(number)
                        val key = VaultLogic.dedupeKey(norm, date, type)
                        seen++
                        if (!seenKeys.add(key)) { skipped++; continue }
                        if (VaultLogic.isTombstoned(key, tombstones)) { skipped++; continue }
                        if (VaultLogic.isInPauseWindow(date, pauseWindows)) { skipped++; continue }
                        var name = if (iName >= 0) c.getString(iName) ?: "" else ""
                        if (VaultLogic.isUnknownName(name)) {
                            val looked = nameCache[norm] ?: lookupContactName(context, number)
                                .also { nameCache[norm] = it }
                            if (looked.isNotBlank()) name = looked
                        }
                        val sub = when {
                            iSub >= 0 && !c.isNull(iSub) -> c.getString(iSub) ?: ""
                            iPhoneAccount >= 0 && !c.isNull(iPhoneAccount) -> c.getString(iPhoneAccount) ?: ""
                            else -> ""
                        }
                        val rec = VaultLogic.CallRecord(
                            numberRaw = number,
                            numberNorm = norm,
                            name = name,
                            dateMs = date,
                            type = type,
                            durationSec = if (iDuration >= 0) c.getLong(iDuration) else 0L,
                            subscriptionId = sub,
                            systemId = if (iId >= 0) c.getLong(iId) else -1L
                        )
                        if (helper.insertIgnore(db, rec)) added++ else skipped++
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(KEY_LAST_SYNC_MS, System.currentTimeMillis())
                .putInt(KEY_LAST_SYNC_ADDED, added)
                .apply()
            // Sync is pure: it never touches auto backup, in any form.
            // A sync that blocked on a slow storage provider write was
            // the v1.0.3 infinite Sync spinner. Callers that learn new
            // calls were added accumulate them in AutoBackup and
            // schedule the separate AutoBackupJobService instead.
            return SyncResult(added, skipped, seen)
        } catch (e: SecurityException) {
            return SyncResult(added, skipped, seen, "Call log permission not granted")
        } catch (e: Exception) {
            return SyncResult(added, skipped, seen, e.message ?: "Sync failed")
        }
    }

    /**
     * Delete [records] from the phone's own call log (the "vault
     * and phone log" delete flavor, v1.2.0). Each record is first
     * deleted with the exact selection from
     * [VaultLogic.providerDeleteSpec] (normalized number, date,
     * type). A provider that stored the number in another format
     * matches nothing there, so a record that deleted zero rows
     * gets one fallback pass: rows at the same date are read back,
     * their numbers normalized and compared in code, and matches
     * are deleted by row id. Returns the number of phone log rows
     * removed; a record already gone from the log simply adds 0.
     * Blocking, background thread only.
     */
    fun deleteFromPhoneLog(context: Context, records: List<VaultLogic.CallRecord>): Int {
        var removed = 0
        for (r in records) {
            try {
                val spec = VaultLogic.providerDeleteSpec(r)
                var n = context.contentResolver.delete(
                    CallLog.Calls.CONTENT_URI, spec.selection, spec.args.toTypedArray()
                )
                if (n == 0) n = deleteFromPhoneLogByScan(context, r)
                removed += n
            } catch (e: Exception) {
                // A failed row never sinks the rest of the delete.
            }
        }
        return removed
    }

    /** The format tolerant fallback for [deleteFromPhoneLog]. */
    private fun deleteFromPhoneLogByScan(context: Context, r: VaultLogic.CallRecord): Int {
        var removed = 0
        try {
            val ids = ArrayList<Long>()
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.TYPE),
                CallLog.Calls.DATE + " = ?",
                arrayOf(r.dateMs.toString()),
                null
            )?.use { c ->
                while (c.moveToNext()) {
                    if (c.getInt(2) != r.type) continue
                    val number = c.getString(1) ?: continue
                    if (VaultLogic.normalizeNumber(number) == r.numberNorm) {
                        ids.add(c.getLong(0))
                    }
                }
            }
            for (id in ids) {
                removed += context.contentResolver.delete(
                    ContentUris.withAppendedId(CallLog.Calls.CONTENT_URI, id), null, null
                )
            }
        } catch (e: Exception) {
            // Counted as zero for this record by the caller.
        }
        return removed
    }

    /** How many entries the system call log currently holds, or -1 when unreadable. */
    fun systemCount(context: Context): Int {
        return try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls._ID), null, null, null
            )?.use { it.count } ?: -1
        } catch (e: Exception) {
            -1
        }
    }

    /** Existing system keys, normalized number plus date plus type, for restore dedupe. */
    private fun systemKeys(context: Context): HashSet<String> {
        val set = HashSet<String>()
        try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.TYPE),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val number = c.getString(0) ?: continue
                    set.add(
                        VaultLogic.dedupeKey(
                            VaultLogic.normalizeNumber(number), c.getLong(1), c.getInt(2)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // If the system log cannot be read, restore would duplicate.
            // Treat that as fatal by returning a clearly marked set.
            set.add("__unreadable__")
        }
        return set
    }

    /**
     * Insert vault records that are missing from the system call log.
     * Records must be supplied oldest first for sane batching, though the
     * order does not affect correctness. Any dialer reads the same system
     * log, so restored calls appear in every dialer app.
     */
    fun restore(context: Context, records: List<VaultLogic.CallRecord>): RestoreResult {
        val existing = systemKeys(context)
        if (existing.contains("__unreadable__")) {
            return RestoreResult(0, 0, "Could not read the system call log, restore stopped to avoid duplicates")
        }
        var inserted = 0
        var skipped = 0
        val pending = ArrayList<ContentValues>()
        fun flush() {
            if (pending.isEmpty()) return
            try {
                val ops = ArrayList<ContentProviderOperation>()
                for (cv in pending) {
                    ops.add(
                        ContentProviderOperation.newInsert(CallLog.Calls.CONTENT_URI)
                            .withValues(cv).build()
                    )
                }
                val results = context.contentResolver.applyBatch("call_log", ops)
                inserted += results.size
            } catch (e: Exception) {
                // Fall back to one by one so a single bad row cannot sink a batch.
                for (cv in pending) {
                    try {
                        val uri = context.contentResolver.insert(CallLog.Calls.CONTENT_URI, cv)
                        if (uri != null) inserted++ else skipped++
                    } catch (ignored: Exception) {
                        skipped++
                    }
                }
            }
            pending.clear()
        }
        try {
            for (r in records) {
                val key = VaultLogic.dedupeKey(r.numberNorm, r.dateMs, r.type)
                if (!existing.add(key)) { skipped++; continue }
                val cv = ContentValues()
                cv.put(CallLog.Calls.NUMBER, r.numberRaw)
                cv.put(CallLog.Calls.DATE, r.dateMs)
                cv.put(CallLog.Calls.TYPE, r.type)
                cv.put(CallLog.Calls.DURATION, r.durationSec)
                if (r.name.isNotBlank() && !VaultLogic.isUnknownName(r.name)) {
                    cv.put(CallLog.Calls.CACHED_NAME, r.name)
                }
                cv.put(CallLog.Calls.NEW, if (r.type == VaultLogic.TYPE_MISSED) 1 else 0)
                pending.add(cv)
                if (pending.size >= 200) flush()
            }
            flush()
            return RestoreResult(inserted, skipped)
        } catch (e: SecurityException) {
            return RestoreResult(inserted, skipped, "Call log write permission not granted")
        } catch (e: Exception) {
            return RestoreResult(inserted, skipped, e.message ?: "Restore failed")
        }
    }
}
