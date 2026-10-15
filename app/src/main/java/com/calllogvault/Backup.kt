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

    /** Write the four backup entries for [records] into an open ZIP stream. */
    fun writeZipEntries(zip: ZipOutputStream, records: List<VaultLogic.CallRecord>) {
        val now = System.currentTimeMillis()
        val backupSet = UUID.randomUUID().toString()
        fun entry(name: String, text: String) {
            val e = ZipEntry(name)
            e.time = now
            zip.putNextEntry(e)
            zip.write(text.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        entry("calls.json", VaultLogic.generateJson(records, now))
        entry("calls.csv", VaultLogic.generateCsv(records))
        entry("calls.xml", VaultLogic.generateCallsXml(records, backupSet, now))
        entry("README.txt", readmeText())
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
 * Throttle (pure rule in VaultLogic.shouldWriteAutoBackup): write on
 * the first capture, right away at 20 or more new calls, otherwise at
 * most once every 15 minutes. Failures are recorded silently in prefs
 * and surfaced only as a drawer subtitle, never as a nag.
 */
object AutoBackup {

    private const val KEY_URI = "auto_folder_uri"
    private const val KEY_FILE_URI = "auto_file_uri"
    private const val KEY_LAST_MS = "auto_last_ms"
    private const val KEY_LAST_COUNT = "auto_last_count"
    private const val KEY_SINCE = "auto_since"
    private const val KEY_ERROR = "auto_error"
    private const val KEY_FORCE = "auto_force_next"

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
     * Entry point for AutoBackupJobService. Decides from the throttle
     * (or a pending immediate request from the folder pick) whether to
     * write, then writes through the guarded path. Never throws.
     */
    fun runScheduledWrite(context: Context): Boolean {
        if (!hasFolder(context)) return false
        val force = consumeForceRequest(context)
        val now = System.currentTimeMillis()
        if (!VaultLogic.shouldAttemptAutoWrite(force, lastWriteMs(context), newSince(context), now)) {
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

        stream.use { raw ->
            ZipOutputStream(raw).use { zip -> Backup.writeZipEntries(zip, records) }
        }
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
