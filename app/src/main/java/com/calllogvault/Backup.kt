package com.calllogvault

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * ZIP backup and restore. Export writes calls.json, calls.csv,
 * calls.xml and README.txt. Import reads calls.json when present and
 * falls back to calls.xml. Everything runs through the Storage Access
 * Framework, so no storage permission is needed.
 */
object Backup {

    data class ImportResult(val added: Int, val skipped: Int, val parsed: Int, val error: String? = null)

    fun readmeText(): String {
        return "Call Log Vault backup\n" +
            "=====================\n\n" +
            "This ZIP holds a backup of your call history from Call Log Vault.\n" +
            "The vault in the app has no size limit and never deletes old calls.\n\n" +
            "Files in this ZIP:\n" +
            "calls.json : the full backup, with every field. Use this file to\n" +
            "             restore in Call Log Vault. Nothing is lost in this format.\n" +
            "calls.csv  : a plain table you can open in Excel or any spreadsheet.\n" +
            "calls.xml  : the call log format also used by SMS Backup and Restore.\n" +
            "             It is included so the data stays readable by other tools.\n" +
            "             Other apps restoring this file is not guaranteed.\n" +
            "README.txt : this file.\n\n" +
            "How to restore:\n" +
            "1. Install Call Log Vault on the phone and allow the call log permission.\n" +
            "2. Tap Restore ZIP and pick this ZIP file.\n" +
            "3. The calls go into the app vault first. Then tap Restore to Dialer\n" +
            "   to put them back into the phone call list, where any dialer app\n" +
            "   (Samsung Phone, Google Phone and others) will show them.\n\n" +
            "Note: many phones keep only the newest 2000 entries in the phone\n" +
            "call list and trim older ones as new calls arrive. The vault in\n" +
            "Call Log Vault keeps everything, with no limit.\n\n" +
            "Imagined by Arjun\n"
    }

    /**
     * Write the four backup entries for [records] into an open ZIP
     * stream. Returns the exact calls.json text written, so the
     * rolling writer can fingerprint its own write byte for byte.
     */
    fun writeZipEntries(zip: ZipOutputStream, records: List<VaultLogic.CallRecord>): String {
        val now = System.currentTimeMillis()
        val backupSet = UUID.randomUUID().toString()
        fun entry(name: String, text: String) {
            val e = ZipEntry(name)
            e.time = now
            zip.putNextEntry(e)
            zip.write(text.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        val json = VaultLogic.generateJson(records, now)
        entry("calls.json", json)
        entry("calls.csv", VaultLogic.generateCsv(records))
        entry("calls.xml", VaultLogic.generateCallsXml(records, backupSet, now))
        entry("README.txt", readmeText())
        return json
    }

    /** Write a full backup ZIP to the SAF stream. Returns the record count. */
    fun exportZip(context: Context, uri: Uri): Int {
        val helper = VaultDb.get(context)
        val db = helper.readableDatabase
        val records = helper.allAscending(db)
        context.contentResolver.openOutputStream(uri)?.use { raw: OutputStream ->
            ZipOutputStream(raw).use { zip -> writeZipEntries(zip, records) }
        } ?: throw IllegalStateException("Could not open the chosen file for writing")
        return records.size
    }

    /** Read a backup ZIP from the SAF stream and merge it into the vault. */
    fun importZip(context: Context, uri: Uri): ImportResult {
        var jsonText: String? = null
        var xmlText: String? = null
        try {
            context.contentResolver.openInputStream(uri)?.use { raw: InputStream ->
                ZipInputStream(raw).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val name = entry.name.substringAfterLast('/')
                        if (name.equals("calls.json", true) || name.equals("calls.xml", true)) {
                            val buf = ByteArrayOutputStream()
                            val chunk = ByteArray(8192)
                            while (true) {
                                val n = zip.read(chunk)
                                if (n < 0) break
                                buf.write(chunk, 0, n)
                            }
                            val text = buf.toString("UTF-8")
                            if (name.equals("calls.json", true)) jsonText = text else xmlText = text
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            } ?: return ImportResult(0, 0, 0, "Could not open the chosen file")
        } catch (e: Exception) {
            return ImportResult(0, 0, 0, e.message ?: "Could not read the ZIP file")
        }

        val records: List<VaultLogic.CallRecord> = when {
            jsonText != null -> try {
                VaultLogic.parseCallsJson(jsonText!!)
            } catch (e: Exception) {
                return ImportResult(0, 0, 0, "calls.json in this ZIP could not be parsed")
            }
            xmlText != null -> VaultLogic.parseCallsXml(xmlText!!)
            else -> return ImportResult(0, 0, 0, "No calls.json or calls.xml found in this ZIP")
        }
        if (records.isEmpty()) return ImportResult(0, 0, 0, "No call records found in this backup")

        val helper = VaultDb.get(context)
        val db = helper.writableDatabase
        val existing = helper.allKeys(db)
        var added = 0
        var skipped = 0
        db.beginTransaction()
        try {
            for (r in records) {
                val key = VaultLogic.dedupeKey(r)
                if (!existing.add(key)) { skipped++; continue }
                if (helper.insertIgnore(db, r)) added++ else skipped++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return ImportResult(added, skipped, records.size)
    }
}

/**
 * Rolling auto backup: ONE file, [VaultLogic.AUTO_BACKUP_FILENAME],
 * inside a folder the user picked with the system folder picker. The
 * folder lives outside this app, so clearing app data or uninstalling
 * the app can never delete the file. Only the persisted folder
 * permission may need to be granted again after a reinstall.
 *
 * Writes run ONLY from AutoBackupJobService, on their own schedule,
 * never inside a sync: a slow DocumentsProvider used to stall every
 * sync (and its progress dialog) for as long as its calls took. The
 * first write finds the rolling file by listing the folder children
 * through DocumentsContract (framework API) and remembers the file's
 * own document Uri; every later write opens that Uri directly and
 * skips the listing entirely. The listing fallback runs at most once
 * per write, only when the remembered file cannot be opened.
 *
 * Throttle (pure rule in VaultLogic.shouldWriteBackup, under the
 * schedule the user picked in the drawer): the default Smart schedule
 * is the original rule, write on the first capture, right away at 20
 * or more new calls, otherwise at most once every 15 minutes. The
 * other schedules write after every call, at most once every 6
 * hours, or at most once a day. A forced write (the folder was just
 * picked) bypasses the schedule entirely. Failures are recorded
 * silently in prefs and surfaced only as a drawer subtitle, never
 * as a nag.
 */
object AutoBackup {

    private const val KEY_URI = "auto_folder_uri"
    private const val KEY_FILE_URI = "auto_file_uri"
    private const val KEY_LAST_MS = "auto_last_ms"
    private const val KEY_LAST_COUNT = "auto_last_count"
    private const val KEY_SINCE = "auto_since"
    private const val KEY_ERROR = "auto_error"
    private const val KEY_FORCE = "auto_force_next"
    private const val KEY_SCHEDULE = "auto_schedule"

    /** At most one write runs at a time, across every caller. */
    private val writeInProgress = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun prefs(context: Context) =
        context.getSharedPreferences(UiKit.PREFS, Context.MODE_PRIVATE)

    fun folderUriString(context: Context): String =
        prefs(context).getString(KEY_URI, "") ?: ""

    fun hasFolder(context: Context): Boolean = folderUriString(context).isNotBlank()

    fun setFolder(context: Context, uriString: String) {
        prefs(context).edit()
            .putString(KEY_URI, uriString)
            // A different folder means a different rolling file: the
            // remembered file Uri belongs to the old folder.
            .remove(KEY_FILE_URI)
            .putBoolean(KEY_ERROR, false)
            .apply()
    }

    /**
     * The chosen backup schedule (VaultLogic.BACKUP_SCHEDULE_*).
     * Defaults to Smart; an unknown stored value also reads as
     * Smart, and VaultLogic.shouldWriteBackup treats unknown
     * values as Smart too, so the two can never disagree.
     */
    fun schedule(context: Context): Int {
        val stored = prefs(context).getInt(KEY_SCHEDULE, VaultLogic.BACKUP_SCHEDULE_SMART)
        return when (stored) {
            VaultLogic.BACKUP_SCHEDULE_SMART,
            VaultLogic.BACKUP_SCHEDULE_EVERY_CALL,
            VaultLogic.BACKUP_SCHEDULE_EVERY_6H,
            VaultLogic.BACKUP_SCHEDULE_DAILY -> stored
            else -> VaultLogic.BACKUP_SCHEDULE_SMART
        }
    }

    fun setSchedule(context: Context, schedule: Int) {
        prefs(context).edit().putInt(KEY_SCHEDULE, schedule).apply()
    }

    fun lastWriteMs(context: Context): Long = prefs(context).getLong(KEY_LAST_MS, 0L)
    fun lastCount(context: Context): Long = prefs(context).getLong(KEY_LAST_COUNT, 0L)
    fun newSince(context: Context): Int = prefs(context).getInt(KEY_SINCE, 0)
    fun hasError(context: Context): Boolean = prefs(context).getBoolean(KEY_ERROR, false)

    fun folderDisplayName(context: Context): String {
        val s = folderUriString(context)
        if (s.isBlank()) return "Not set"
        return try {
            val docId = DocumentsContract.getTreeDocumentId(Uri.parse(s))
            VaultLogic.folderNameFromTreeId(docId)
        } catch (e: Exception) {
            "Selected folder"
        }
    }

    /** True while a write is physically running, across all callers. */
    fun isWriteInProgress(): Boolean = writeInProgress.get()

    /**
     * Accumulate newly synced calls toward the next throttled write.
     * Called by the owners of a finished sync (MainActivity refresh,
     * VaultJobService), never by SyncEngine itself.
     */
    fun addNewSince(context: Context, added: Int) {
        if (added <= 0) return
        try {
            prefs(context).edit()
                .putInt(KEY_SINCE, newSince(context) + added)
                .apply()
        } catch (ignored: Exception) {
        }
    }

    /**
     * Ask the next scheduled run to write even if the throttle would
     * wait. Set when a folder is picked, so the first rolling file
     * appears right away. Stored in prefs so it survives job
     * replacement by id.
     */
    fun requestImmediateWrite(context: Context) {
        prefs(context).edit().putBoolean(KEY_FORCE, true).apply()
    }

    /** Record an error from outside a write attempt (a timed out run). */
    fun noteError(context: Context) {
        prefs(context).edit().putBoolean(KEY_ERROR, true).apply()
    }

    private fun consumeForceRequest(context: Context): Boolean {
        val forced = prefs(context).getBoolean(KEY_FORCE, false)
        if (forced) prefs(context).edit().putBoolean(KEY_FORCE, false).apply()
        return forced
    }

    private fun fileUriString(context: Context): String =
        prefs(context).getString(KEY_FILE_URI, "") ?: ""

    /**
     * The remembered rolling file document Uri, shared with the
     * watch folder reader so it can open the file directly instead
     * of listing the folder on every check.
     */
    fun rollingFileUriString(context: Context): String = fileUriString(context)

    /** Remember a rolling file Uri the watch reader found by listing. */
    fun rememberRollingFileUri(context: Context, uriString: String) {
        prefs(context).edit().putString(KEY_FILE_URI, uriString).apply()
    }

    /**
     * The exact calls.json bytes of the last write this process
     * made, kept so the write can store the watch fingerprint of
     * precisely what it wrote (never a read back, which a sync tool
     * could already have replaced).
     */
    @Volatile
    private var lastWrittenJsonBytes: ByteArray? = null

    /**
     * Entry point for AutoBackupJobService. Decides from the chosen
     * schedule (or a pending immediate request from the folder pick,
     * which bypasses the schedule) whether to write, then writes
     * through the guarded path. Never throws.
     *
     * The watch import runs FIRST, before the write decision
     * (v1.1.0): one scheduled run then merges both directions, the
     * folder's newer file into the vault and the merged vault back
     * into the folder. An import that changed the vault (records
     * added or names replaced) forces the write back even when the
     * schedule would have waited, so the merged file is what the
     * other device sees next. The watch check itself is inert
     * unless the user turned the watch switch on.
     */
    fun runScheduledWrite(context: Context): Boolean {
        if (!hasFolder(context)) return false
        val force = consumeForceRequest(context)
        val watchChanged = WatchFolder.importFromFolderIfChanged(context).changed
        val now = System.currentTimeMillis()
        if (!force && !watchChanged && !VaultLogic.shouldWriteBackup(
                schedule(context), lastWriteMs(context), newSince(context), now
            )
        ) {
            return false
        }
        return writeNow(context)
    }

    /**
     * Write the rolling file right away, ignoring the throttle. At
     * most one write runs at a time: a call that finds a write already
     * in progress returns false instead of overlapping it, and the
     * guard is cleared only when the running write actually returns,
     * so a hung provider call can never be overlapped by a later run.
     * Never throws.
     */
    fun writeNow(context: Context): Boolean {
        if (!hasFolder(context)) return false
        if (!writeInProgress.compareAndSet(false, true)) return false
        return try {
            val count = doWrite(context)
            prefs(context).edit()
                .putLong(KEY_LAST_MS, System.currentTimeMillis())
                .putLong(KEY_LAST_COUNT, count.toLong())
                .putInt(KEY_SINCE, 0)
                .putBoolean(KEY_ERROR, false)
                .apply()
            // The watch folder skips any file whose fingerprint it
            // already knows; recording this write's fingerprint is
            // what stops the app importing its own backup straight
            // back. Best effort: a failed store only costs one
            // harmless deduped re-check later.
            val jsonBytes = lastWrittenJsonBytes
            if (jsonBytes != null) {
                WatchFolder.storeOwnWriteFingerprint(context, jsonBytes)
            }
            true
        } catch (e: Exception) {
            // Silent by design: the drawer row shows "needs attention".
            prefs(context).edit()
                .putBoolean(KEY_ERROR, true)
                .apply()
            false
        } finally {
            writeInProgress.set(false)
        }
    }

    /**
     * The actual rolling write. Returns the number of records written.
     *
     * Steady state is the cheap path: the rolling file's document Uri
     * is remembered after the first create, and later writes open it
     * directly with "wt" (truncate and rewrite), never listing the
     * folder children. Only when there is no remembered Uri, or
     * opening it fails (the file was deleted), does it fall back to
     * one list-then-create pass and remember the result.
     */
    private fun doWrite(context: Context): Int {
        val treeUri = Uri.parse(folderUriString(context))
        val resolver = context.contentResolver

        val helper = VaultDb.get(context)
        val records = helper.allAscending(helper.readableDatabase)

        var stream: OutputStream? = null
        val storedFile = fileUriString(context)
        if (storedFile.isNotBlank()) {
            try {
                stream = resolver.openOutputStream(Uri.parse(storedFile), "wt")
            } catch (e: Exception) {
                stream = null
            }
        }
        if (stream == null) {
            // The one fallback attempt. If this throws, the write
            // fails and the error is recorded by the caller.
            val targetUri = findOrCreateRollingFile(resolver, treeUri)
            stream = resolver.openOutputStream(targetUri, "wt")
                ?: throw IllegalStateException("Could not open the auto backup file for writing")
            prefs(context).edit().putString(KEY_FILE_URI, targetUri.toString()).apply()
        }

        val jsonText = stream.use { raw ->
            ZipOutputStream(raw).use { zip -> Backup.writeZipEntries(zip, records) }
        }
        lastWrittenJsonBytes = jsonText.toByteArray(Charsets.UTF_8)
        return records.size
    }

    /** List the folder children to find the rolling file, or create it when missing. */
    private fun findOrCreateRollingFile(resolver: android.content.ContentResolver, treeUri: Uri): Uri {
        val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)
        resolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
            ),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                if (name == VaultLogic.AUTO_BACKUP_FILENAME) {
                    return DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0))
                }
            }
        }
        val folderDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)
        return DocumentsContract.createDocument(
            resolver, folderDocUri, "application/zip", VaultLogic.AUTO_BACKUP_FILENAME
        ) ?: throw IllegalStateException("Could not create the auto backup file")
    }
}

/**
 * Watch folder import (v1.1.0, GitHub issue #3 part 2). The watched
 * folder IS the auto backup folder: when the user's own folder sync
 * tool (Syncthing, Dropsync, a cloud drive folder, anything they
 * already trust) carries another device's rolling backup into it,
 * this app imports the new records into the vault. No server, no
 * account, no pairing: the folder is the whole transport, and this
 * app never sends or receives anything itself.
 *
 * Checks piggyback existing wakeups only, never a poll: inside the
 * scheduled auto backup run (before its write decision, in
 * AutoBackup.runScheduledWrite), on app open alongside the sync, and
 * after a manual import or restore (both end in a refresh, which
 * checks too). Loop prevention is a stored fingerprint of the
 * folder file (its size plus the SHA-256 of the backup entry): own
 * writes record theirs, so the app never imports its own backup
 * straight back, and an unchanged file is never even parsed.
 *
 * Imports are additive only. Records arrive; deletions never
 * travel, so a manual delete on one device can never eat another
 * device's history. When the same call exists on both devices with
 * different saved names, the later import's name wins
 * (VaultLogic.planWatchMerge). Auto restore to the phone call log
 * is a separate switch, default off.
 */
object WatchFolder {

    private const val KEY_ENABLED = "watch_enabled"
    private const val KEY_AUTO_RESTORE = "watch_auto_restore"
    private const val KEY_FINGERPRINT = "watch_fingerprint"
    private const val KEY_LAST_RESULT = "watch_last_result"
    private const val KEY_LAST_ADDED = "watch_last_added"
    private const val KEY_LAST_AT = "watch_last_at"

    /** Outcome when a check did nothing: off, no folder, no file, or an aborted read. */
    const val OUTCOME_SKIPPED = 0

    /**
     * Outcome of one watch check. [changed] is what callers act on:
     * a changed vault after an import makes the scheduled auto
     * backup run write the merged vault back to the folder.
     */
    data class WatchResult(val added: Int, val namesUpdated: Int, val outcome: Int) {
        val changed: Boolean get() = added > 0 || namesUpdated > 0
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(UiKit.PREFS, Context.MODE_PRIVATE)

    fun watchEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setWatchEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, value).apply()
    }

    fun autoRestore(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_RESTORE, false)

    fun setAutoRestore(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_RESTORE, value).apply()
    }

    private fun storedFingerprint(context: Context): String =
        prefs(context).getString(KEY_FINGERPRINT, "") ?: ""

    private fun storeFingerprint(context: Context, fingerprint: String) {
        prefs(context).edit().putString(KEY_FINGERPRINT, fingerprint).apply()
    }

    // ------------------------------------------------------------ Status

    private fun recordStatus(context: Context, result: Int, added: Int) {
        prefs(context).edit()
            .putInt(KEY_LAST_RESULT, result)
            .putInt(KEY_LAST_ADDED, added)
            .putLong(KEY_LAST_AT, System.currentTimeMillis())
            .apply()
    }

    /** The Status line for the watch, or null when no check has ever completed. */
    fun statusLine(context: Context): String? {
        val result = prefs(context).getInt(KEY_LAST_RESULT, 0)
        val at = prefs(context).getLong(KEY_LAST_AT, 0L)
        if (result == 0 || at <= 0) return null
        val base = VaultLogic.watchStatusText(
            result, prefs(context).getInt(KEY_LAST_ADDED, 0)
        )
        return if (result == VaultLogic.WATCH_RESULT_IMPORTED ||
            result == VaultLogic.WATCH_RESULT_UNREADABLE
        ) {
            base + ", " + VaultLogic.formatTime(at)
        } else {
            base
        }
    }

    // ------------------------------------------------------- File access

    /**
     * The rolling file in the watched folder, or null when it is not
     * there. The remembered document Uri is tried first; one listing
     * pass (the writer's own fallback pattern) finds it by name and
     * remembers it, so steady state checks never list the folder.
     */
    private fun locateRollingFile(context: Context): Uri? {
        val remembered = AutoBackup.rollingFileUriString(context)
        if (remembered.isNotBlank()) {
            val uri = Uri.parse(remembered)
            if (documentExists(context, uri)) return uri
        }
        val treeString = AutoBackup.folderUriString(context)
        if (treeString.isBlank()) return null
        val treeUri = Uri.parse(treeString)
        val resolver = context.contentResolver
        return try {
            val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)
            var found: Uri? = null
            resolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
                ),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    if ((c.getString(1) ?: "") == VaultLogic.AUTO_BACKUP_FILENAME) {
                        found = DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0))
                        break
                    }
                }
            }
            if (found != null) AutoBackup.rememberRollingFileUri(context, found.toString())
            found
        } catch (e: Exception) {
            null
        }
    }

    private fun documentExists(context: Context, uri: Uri): Boolean {
        return try {
            context.contentResolver.query(
                uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null
            )?.use { it.moveToFirst() } == true
        } catch (e: Exception) {
            false
        }
    }

    /** The file's size from the provider, or -1 when it will not say. */
    private fun documentSize(context: Context, uri: Uri): Long {
        return try {
            context.contentResolver.query(
                uri, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null
            )?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
            } ?: -1L
        } catch (e: Exception) {
            -1L
        }
    }

    /** One full read of the rolling file: its backup entries and the sizes around the read. */
    private class FolderRead(
        val sizeBefore: Long,
        val sizeAfter: Long,
        val json: ByteArray?,
        val csv: ByteArray?,
        val xml: ByteArray?
    ) {
        /** The entry the fingerprint hashes: the first one the import would read. */
        val entryBytes: ByteArray get() = json ?: csv ?: xml ?: ByteArray(0)

        val fingerprint: String get() = VaultLogic.zipFingerprint(sizeAfter, entryBytes)
    }

    /**
     * Read the rolling file's backup entries. Returns null when the
     * file cannot be opened or read at all. A file that opens but is
     * not a ZIP still returns, with every entry null: that is the
     * corrupt file case, and it flows into the unreadable path with
     * a fingerprint like any other read.
     */
    private fun readFolderFile(context: Context, fileUri: Uri): FolderRead? {
        val sizeBefore = documentSize(context, fileUri)
        var json: ByteArray? = null
        var csv: ByteArray? = null
        var xml: ByteArray? = null
        try {
            context.contentResolver.openInputStream(fileUri)?.use { raw: InputStream ->
                ZipInputStream(raw).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val name = entry.name.substringAfterLast('/')
                        if (name.equals("calls.json", true) || name.equals("calls.csv", true) ||
                            name.equals("calls.xml", true)
                        ) {
                            val buf = ByteArrayOutputStream()
                            val chunk = ByteArray(8192)
                            while (true) {
                                val n = zip.read(chunk)
                                if (n < 0) break
                                buf.write(chunk, 0, n)
                            }
                            val bytes = buf.toByteArray()
                            when {
                                name.equals("calls.json", true) -> json = bytes
                                name.equals("calls.csv", true) -> csv = bytes
                                else -> xml = bytes
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            } ?: return null
        } catch (e: Exception) {
            return null
        }
        return FolderRead(sizeBefore, documentSize(context, fileUri), json, csv, xml)
    }

    /**
     * The current fingerprint of the rolling file in the folder, or
     * null when there is no folder, no file, or the file cannot be
     * read at all.
     */
    fun readFolderFingerprint(context: Context): String? {
        if (!AutoBackup.hasFolder(context)) return null
        val fileUri = locateRollingFile(context) ?: return null
        return readFolderFile(context, fileUri)?.fingerprint
    }

    /**
     * Record the fingerprint of a write this app just made, from
     * the exact calls.json bytes it wrote and the written file's
     * size. Never throws; on any doubt nothing is stored, and the
     * next check simply re-reads the file (a harmless deduped
     * no-op against the vault).
     */
    fun storeOwnWriteFingerprint(context: Context, jsonBytes: ByteArray) {
        try {
            val fileUri = locateRollingFile(context) ?: return
            val size = documentSize(context, fileUri)
            if (size < 0) return
            storeFingerprint(context, VaultLogic.zipFingerprint(size, jsonBytes))
        } catch (ignored: Exception) {
        }
    }

    // ------------------------------------------------------------ Import

    private sealed class Parsed {
        class Ok(val records: List<VaultLogic.CallRecord>) : Parsed()
        object Unreadable : Parsed()
    }

    /**
     * Parse the folder file's entries under the watch acceptance
     * rule: only this app's own backup format. calls.json is the
     * primary entry; calls.csv and then calls.xml are the fallbacks,
     * the same entries the manual import reads. An entry only
     * counts when its structure checks out (a JSON root with a
     * calls list, the CSV header row, a calls XML root): a damaged
     * entry never hides a good one behind it, and a foreign file
     * with none of them is Unreadable. A structurally valid backup
     * with zero calls is Ok with an empty list, not an error.
     */
    private fun parseEntries(json: ByteArray?, csv: ByteArray?, xml: ByteArray?): Parsed {
        if (json != null) {
            val text = String(json, Charsets.UTF_8)
            val root = try {
                VaultLogic.JsonParser(text).parse() as? Map<*, *>
            } catch (e: Exception) {
                null
            }
            if (root != null && root["calls"] is List<*>) {
                return Parsed.Ok(VaultLogic.parseCallsJson(text))
            }
        }
        if (csv != null) {
            val text = String(csv, Charsets.UTF_8)
            if (VaultLogic.parseCsvRows(text).any { VaultLogic.isCsvHeaderRow(it) }) {
                return Parsed.Ok(VaultLogic.parseCallsCsv(text))
            }
        }
        if (xml != null) {
            val text = String(xml, Charsets.UTF_8)
            if (text.contains("<calls")) {
                return Parsed.Ok(VaultLogic.parseCallsXml(text))
            }
        }
        return Parsed.Unreadable
    }

    /**
     * One watch check: import the folder's rolling file into the
     * vault when it changed since the last known fingerprint.
     * No-ops quietly when watching is off, no folder is set, or no
     * file is there. An unreadable changed file is noted in Status
     * and remembered, so it is not re-read at every wakeup; a file
     * whose size moves during the read (a sync tool still copying
     * it) is aborted quietly and retried by the next check. Merging
     * is one transaction through VaultLogic.planWatchMerge. Never
     * throws.
     */
    fun importFromFolderIfChanged(context: Context): WatchResult {
        val skipped = WatchResult(0, 0, OUTCOME_SKIPPED)
        try {
            if (!watchEnabled(context) || !AutoBackup.hasFolder(context)) return skipped
            val fileUri = locateRollingFile(context) ?: return skipped
            val read = readFolderFile(context, fileUri) ?: run {
                recordStatus(context, VaultLogic.WATCH_RESULT_UNREADABLE, 0)
                return WatchResult(0, 0, VaultLogic.WATCH_RESULT_UNREADABLE)
            }
            if (!VaultLogic.shouldImportWatched(storedFingerprint(context), read.fingerprint, true)) {
                recordStatus(context, VaultLogic.WATCH_RESULT_UP_TO_DATE, 0)
                return WatchResult(0, 0, VaultLogic.WATCH_RESULT_UP_TO_DATE)
            }
            if (read.sizeBefore >= 0 && read.sizeAfter >= 0 && read.sizeBefore != read.sizeAfter) {
                return skipped
            }
            return when (val parsed = parseEntries(read.json, read.csv, read.xml)) {
                Parsed.Unreadable -> {
                    storeFingerprint(context, read.fingerprint)
                    recordStatus(context, VaultLogic.WATCH_RESULT_UNREADABLE, 0)
                    WatchResult(0, 0, VaultLogic.WATCH_RESULT_UNREADABLE)
                }
                is Parsed.Ok -> {
                    val helper = VaultDb.get(context)
                    val db = helper.writableDatabase
                    val plan = VaultLogic.planWatchMerge(helper.namesByKey(db), parsed.records)
                    val addedRecords = ArrayList<VaultLogic.CallRecord>()
                    var namesUpdated = 0
                    db.beginTransaction()
                    try {
                        for (r in plan.inserts) {
                            if (helper.insertIgnore(db, r)) addedRecords.add(r)
                        }
                        for (r in plan.nameUpdates) {
                            namesUpdated += helper.updateName(db, r)
                        }
                        db.setTransactionSuccessful()
                    } finally {
                        db.endTransaction()
                    }
                    storeFingerprint(context, read.fingerprint)
                    if (autoRestore(context) && addedRecords.isNotEmpty()) {
                        // Exactly the records this import added,
                        // oldest first like every other restore.
                        // The restore itself skips anything the
                        // phone log already holds.
                        SyncEngine.restore(context, addedRecords.sortedBy { it.dateMs })
                    }
                    val outcome = if (addedRecords.isNotEmpty()) {
                        VaultLogic.WATCH_RESULT_IMPORTED
                    } else {
                        VaultLogic.WATCH_RESULT_UP_TO_DATE
                    }
                    recordStatus(context, outcome, addedRecords.size)
                    WatchResult(addedRecords.size, namesUpdated, outcome)
                }
            }
        } catch (e: Exception) {
            return skipped
        }
    }
}
