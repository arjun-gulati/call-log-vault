package com.calllogvault

import java.security.MessageDigest
import kotlin.math.roundToInt
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Pure logic for Call Log Vault. No Android imports in this file, so the
 * exact same code runs in the JVM tests. Formats, filters, grouping,
 * escaping and parsing all live here.
 */
object VaultLogic {

    // CallLog.Calls type constants, mirrored so tests do not need Android.
    const val TYPE_INCOMING = 1
    const val TYPE_OUTGOING = 2
    const val TYPE_MISSED = 3
    const val TYPE_VOICEMAIL = 4
    const val TYPE_REJECTED = 5
    const val TYPE_BLOCKED = 6

    data class CallRecord(
        val numberRaw: String,
        val numberNorm: String,
        val name: String,
        val dateMs: Long,
        val type: Int,
        val durationSec: Long,
        val subscriptionId: String = "",
        val systemId: Long = -1L,
        // Row id inside the vault database itself, filled when a
        // record is read back from VaultDb. Records built from the
        // phone call log or a backup file carry -1 here.
        val vaultId: Long = -1L
    )

    data class PersonSummary(
        val numberNorm: String,
        val numberRaw: String,
        val displayName: String,
        val callCount: Int,
        val totalDurationSec: Long,
        val lastDateMs: Long
    )

    enum class Filter { ALL, INCOMING, OUTGOING, MISSED, REJECTED, BLOCKED, UNKNOWN }

    /** Strip separators, keep digits and a single leading plus sign. */
    fun normalizeNumber(raw: String): String {
        val t = raw.trim()
        val sb = StringBuilder()
        for ((i, c) in t.withIndex()) {
            when {
                c.isDigit() -> sb.append(c)
                c == '+' && i == 0 -> sb.append(c)
            }
        }
        val s = sb.toString()
        // A leading 00 is the international prefix written without a plus.
        return if (s.startsWith("00") && s.length > 2) "+" + s.substring(2) else s
    }

    /** Digits only form of a normalized number, for tolerant comparisons. */
    fun digitsOnly(norm: String): String = norm.filter { it.isDigit() }

    /** Stable identity of one call. Two calls with the same key are duplicates. */
    fun dedupeKey(numberNorm: String, dateMs: Long, type: Int): String =
        numberNorm + "|" + dateMs + "|" + type

    fun dedupeKey(r: CallRecord): String = dedupeKey(r.numberNorm, r.dateMs, r.type)

    fun typeLabel(type: Int): String = when (type) {
        TYPE_INCOMING -> "Incoming"
        TYPE_OUTGOING -> "Outgoing"
        TYPE_MISSED -> "Missed"
        TYPE_VOICEMAIL -> "Voicemail"
        TYPE_REJECTED -> "Rejected"
        TYPE_BLOCKED -> "Blocked"
        else -> "Other"
    }

    fun isUnknownName(name: String): Boolean {
        val n = name.trim()
        return n.isEmpty() || n.equals("(Unknown)", true) || n.equals("Unknown", true)
    }

    /** The name to show for a record: the saved name, falling back to the number. */
    fun effectiveName(r: CallRecord): String =
        if (isUnknownName(r.name)) r.numberRaw else r.name.trim()

    fun matchesFilter(r: CallRecord, filter: Filter): Boolean = when (filter) {
        Filter.ALL -> true
        Filter.INCOMING -> r.type == TYPE_INCOMING
        Filter.OUTGOING -> r.type == TYPE_OUTGOING
        Filter.MISSED -> r.type == TYPE_MISSED
        Filter.REJECTED -> r.type == TYPE_REJECTED
        Filter.BLOCKED -> r.type == TYPE_BLOCKED
        Filter.UNKNOWN -> isUnknownName(r.name)
    }

    /** Live search: name substring (any case) or digit substring of the number. */
    fun matchesQuery(r: CallRecord, query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        if (r.name.lowercase(Locale.ROOT).contains(q.lowercase(Locale.ROOT))) return true
        val qDigits = q.filter { it.isDigit() }
        if (qDigits.isNotEmpty() && digitsOnly(r.numberNorm).contains(qDigits)) return true
        return r.numberRaw.lowercase(Locale.ROOT).contains(q.lowercase(Locale.ROOT))
    }

    fun filterCalls(records: List<CallRecord>, filter: Filter, query: String): List<CallRecord> =
        records.filter { matchesFilter(it, filter) && matchesQuery(it, query) }

    /**
     * The detail header count word (v1.3.0, issue #12): a single call
     * reads "Call", everything else reads "Calls". Callers uppercase
     * it for the header, so this stays in title case.
     */
    fun callCountLabel(count: Int): String = if (count == 1) "Call" else "Calls"

    /**
     * Whether two normalized numbers name the same person (v1.3.0,
     * issue #13, grouping only): one's digit string is a suffix of
     * the other's, so +122233 and 22233 match, in either order.
     * Empty digit strings never match, so digitless numbers are
     * never merged by accident. Deliberate trade-off, stated in the
     * v1.3.0 notes: a short number that happens to be a suffix of a
     * longer unrelated number merges too. Nothing here touches
     * storage, dedupe, sync or import; it only decides grouping.
     */
    fun numbersSamePerson(normA: String, normB: String): Boolean {
        val a = digitsOnly(normA)
        val b = digitsOnly(normB)
        if (a.isEmpty() || b.isEmpty()) return false
        return a.endsWith(b) || b.endsWith(a)
    }

    /**
     * Group by person. Numbers that name the same person (see
     * numbersSamePerson) land in one group, so a contact reached
     * with and without its country code shows once, in either call
     * order. The merged group's representative number is the
     * longest digit form (the plus form wins a tie), its display
     * name follows the existing rule (the name on the most recent
     * named call, else the latest raw number), and no call record
     * is merged or lost: every record stays in the group.
     * Sorted by most recent call first.
     */
    fun groupPeople(records: List<CallRecord>): List<PersonSummary> {
        val byNumber = LinkedHashMap<String, MutableList<CallRecord>>()
        for (r in records) byNumber.getOrPut(r.numberNorm) { ArrayList() }.add(r)
        val merged = ArrayList<MutableList<CallRecord>>()
        val mergedRep = ArrayList<String>()
        for ((norm, list) in byNumber) {
            var target = -1
            for (i in merged.indices) {
                if (numbersSamePerson(mergedRep[i], norm)) {
                    target = i
                    break
                }
            }
            if (target < 0) {
                merged.add(ArrayList(list))
                mergedRep.add(norm)
            } else {
                merged[target].addAll(list)
                val curDigits = digitsOnly(mergedRep[target])
                val newDigits = digitsOnly(norm)
                if (newDigits.length > curDigits.length ||
                    (newDigits.length == curDigits.length &&
                        norm.startsWith("+") && !mergedRep[target].startsWith("+"))
                ) {
                    mergedRep[target] = norm
                }
            }
        }
        val out = ArrayList<PersonSummary>()
        for (i in merged.indices) {
            val list = merged[i]
            val latest = list.maxByOrNull { it.dateMs } ?: continue
            // Display name: the name on the most recent named call, if any.
            val named = list.filter { !isUnknownName(it.name) }.maxByOrNull { it.dateMs }
            out.add(
                PersonSummary(
                    numberNorm = mergedRep[i],
                    numberRaw = latest.numberRaw,
                    displayName = named?.name?.trim() ?: latest.numberRaw,
                    callCount = list.size,
                    totalDurationSec = list.sumOf { it.durationSec },
                    lastDateMs = latest.dateMs
                )
            )
        }
        out.sortByDescending { it.lastDateMs }
        return out
    }

    /** Section label for a call date relative to now. */
    fun dateHeaderLabel(dateMs: Long, nowMs: Long): String {
        fun dayStart(ms: Long): Long {
            val c = Calendar.getInstance()
            c.timeInMillis = ms
            c.set(Calendar.HOUR_OF_DAY, 0)
            c.set(Calendar.MINUTE, 0)
            c.set(Calendar.SECOND, 0)
            c.set(Calendar.MILLISECOND, 0)
            return c.timeInMillis
        }
        val dayMs = 24L * 60 * 60 * 1000
        val diffDays = (dayStart(nowMs) - dayStart(dateMs)) / dayMs
        return when (diffDays) {
            0L -> "Today"
            1L -> "Yesterday"
            else -> fmtHeaderDate.get().format(Date(dateMs))
        }
    }

    // ------------------------------------------------- Cached date formats

    /**
     * SimpleDateFormat is not thread safe and is expensive to build, and
     * list rows format dates on every bind while scrolling. One cached
     * formatter per pattern per thread: same output as constructing a
     * fresh SimpleDateFormat each time, without the per call cost.
     */
    private fun threadLocalFormat(pattern: String): ThreadLocal<SimpleDateFormat> =
        object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue(): SimpleDateFormat =
                SimpleDateFormat(pattern, Locale.ENGLISH)
        }

    private val fmtHeaderDate = threadLocalFormat("EEE, dd MMM yyyy")
    private val fmtDateTime = threadLocalFormat("dd MMM yyyy, hh:mm a")
    private val fmtTime = threadLocalFormat("hh:mm a")
    private val fmtReadable = threadLocalFormat("dd.MM.yyyy HH:mm:ss")

    // ------------------------------------------------------- Avatars (pure)

    /** Soft avatar palette (family tile colors). Index comes from [avatarColorIndex]. */
    val AVATAR_COLORS: IntArray = intArrayOf(
        0xFFCFAB6E.toInt(), // sand
        0xFF80B2CB.toInt(), // dusty blue
        0xFFA39CC9.toInt(), // lavender
        0xFF6EB98E.toInt(), // mint
        0xFFCD98B3.toInt(), // rose
        0xFFABB0BA.toInt()  // grey
    )

    /**
     * Initials for an avatar: the first letters of up to two name words,
     * uppercased. Returns empty for unknown names and for names that are
     * really just a number, so the UI can show its unknown glyph instead.
     */
    fun initialsFor(name: String, numberRaw: String): String {
        val n = name.trim()
        if (isUnknownName(n)) return ""
        if (n == numberRaw.trim()) return ""
        val numberish = n.all { it.isDigit() || it == '+' || it == ' ' || it == '-' || it == '(' || it == ')' }
        if (numberish) return ""
        val words = n.split(WHITESPACE).filter { w -> w.isNotEmpty() && w[0].isLetter() }
        if (words.isEmpty()) return ""
        val sb = StringBuilder()
        for (w in words.take(2)) sb.append(w[0].uppercaseChar())
        return sb.toString()
    }

    /** Deterministic palette slot for a stable key (usually the normalized number). */
    fun avatarColorIndex(key: String): Int {
        var h = 0
        for (c in key) h = 31 * h + c.code
        val m = h % AVATAR_COLORS.size
        return if (m < 0) m + AVATAR_COLORS.size else m
    }

    fun avatarColor(key: String): Int = AVATAR_COLORS[avatarColorIndex(key)]

    // ------------------------------------------------------ Auto backup

    /** Rolling auto backup file name inside the user chosen folder. */
    const val AUTO_BACKUP_FILENAME = "call-log-vault-auto.zip"

    const val AUTO_BACKUP_MIN_INTERVAL_MS = 15L * 60 * 1000
    const val AUTO_BACKUP_BURST_CALLS = 20

    /**
     * Throttle for the rolling auto backup. Simple rule:
     * - nothing new since the last write: never write
     * - never written before: write as soon as there is anything new
     * - at least [AUTO_BACKUP_BURST_CALLS] new calls: write right away
     * - otherwise write once [AUTO_BACKUP_MIN_INTERVAL_MS] has passed
     */
    fun shouldWriteAutoBackup(lastWriteMs: Long, newCallsSince: Int, nowMs: Long): Boolean {
        if (newCallsSince <= 0) return false
        if (lastWriteMs <= 0L) return true
        if (newCallsSince >= AUTO_BACKUP_BURST_CALLS) return true
        return nowMs - lastWriteMs >= AUTO_BACKUP_MIN_INTERVAL_MS
    }

    /** Auto backup is scheduled only when a sync actually added calls. */
    fun shouldScheduleAutoBackup(added: Int): Boolean = added > 0

    // --------------------------------------- Backup schedules (v1.0.8)

    /** Smart: the original throttle, unchanged. The default. */
    const val BACKUP_SCHEDULE_SMART = 0

    /** Write whenever a sync added at least one new call. */
    const val BACKUP_SCHEDULE_EVERY_CALL = 1

    /** Write at most once every 6 hours. */
    const val BACKUP_SCHEDULE_EVERY_6H = 2

    /** Write at most once every 24 hours. */
    const val BACKUP_SCHEDULE_DAILY = 3

    const val BACKUP_6H_INTERVAL_MS = 6L * 60 * 60 * 1000
    const val BACKUP_DAY_INTERVAL_MS = 24L * 60 * 60 * 1000

    /** Short display name of a schedule, for the drawer row subtitle. */
    fun backupScheduleName(schedule: Int): String = when (schedule) {
        BACKUP_SCHEDULE_EVERY_CALL -> "After every call"
        BACKUP_SCHEDULE_EVERY_6H -> "Every 6 hours"
        BACKUP_SCHEDULE_DAILY -> "Daily"
        else -> "Smart"
    }

    /**
     * The write decision for one scheduled auto backup run, under the
     * user's chosen [schedule]. A run happens when a sync added calls
     * (or a write was forced, which bypasses this function entirely).
     *
     * - SMART defers to the original throttle, exactly as before.
     * - EVERY_CALL writes whenever at least one new call arrived
     *   since the last write.
     * - EVERY_6H and DAILY write when at least one new call arrived
     *   and the interval has passed since the last write. A backup
     *   that was never written counts as due, so the first file
     *   appears as soon as there is anything to put in it.
     *
     * Nothing new since the last write never writes, in every mode.
     * An unknown schedule value behaves as SMART.
     */
    fun shouldWriteBackup(
        schedule: Int,
        lastWriteMs: Long,
        newCallsSinceWrite: Int,
        nowMs: Long
    ): Boolean {
        if (newCallsSinceWrite <= 0) return false
        return when (schedule) {
            BACKUP_SCHEDULE_EVERY_CALL -> true
            BACKUP_SCHEDULE_EVERY_6H ->
                lastWriteMs <= 0L || nowMs - lastWriteMs >= BACKUP_6H_INTERVAL_MS
            BACKUP_SCHEDULE_DAILY ->
                lastWriteMs <= 0L || nowMs - lastWriteMs >= BACKUP_DAY_INTERVAL_MS
            else -> shouldWriteAutoBackup(lastWriteMs, newCallsSinceWrite, nowMs)
        }
    }

    // --------------------------------------- Watch folder (v1.1.0)

    /** Watch check outcomes, stored for the Status line. */
    const val WATCH_RESULT_IMPORTED = 1
    const val WATCH_RESULT_UP_TO_DATE = 2
    const val WATCH_RESULT_UNREADABLE = 3

    /**
     * Fingerprint of the rolling backup file in the watched folder:
     * the file size plus the SHA-256 of the backup entry the import
     * would read (calls.json when present, else the fallback entry).
     * Two reads of an unchanged file always produce the same
     * fingerprint; any rewrite, by this device or another, changes
     * it. Kept as one opaque string so the comparison stays trivial.
     */
    /** Lowercase hex SHA-256 of [bytes]. */
    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val hex = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            hex.append("0123456789abcdef"[v shr 4])
            hex.append("0123456789abcdef"[v and 0x0F])
        }
        return hex.toString()
    }

    /** Lowercase hex SHA-256 of [text] as UTF-8. */
    fun sha256Hex(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))

    fun zipFingerprint(fileSize: Long, entryBytes: ByteArray): String {
        return fileSize.toString() + ":" + sha256Hex(entryBytes)
    }

    /**
     * The watch decision. Watching off never imports. No file in
     * the folder (a null current fingerprint) never imports, it
     * skips quietly. A current fingerprint equal to the stored one
     * means the file is unchanged, or it is this device's own last
     * write (own writes store their fingerprint too), so it skips.
     * Anything else imports.
     */
    fun shouldImportWatched(
        fingerprintStored: String,
        fingerprintCurrent: String?,
        watchEnabled: Boolean
    ): Boolean {
        if (!watchEnabled) return false
        if (fingerprintCurrent == null) return false
        return fingerprintCurrent != fingerprintStored
    }

    /**
     * Name resolution for a watched import when the same call (same
     * dedupe key) already sits in the vault with a saved name: the
     * later import's saved name wins, because each device saves the
     * name as it was shown on that device. One guard: a missing
     * incoming name (unknown on the other device) never erases a
     * name this device already saved.
     */
    fun mergedImportName(existingName: String, incomingName: String): String {
        val incoming = incomingName.trim()
        if (isUnknownName(incoming)) return existingName
        return incoming
    }

    /** What one watched import should do: rows to insert, rows whose saved name changes. */
    data class MergePlan(val inserts: List<CallRecord>, val nameUpdates: List<CallRecord>)

    /**
     * Plan a watched import merge against the vault's current
     * key to saved name map. Pure, so the whole merge semantics are
     * testable: the result is always the union of both sets
     * (imports only ever add, deletions never travel), records
     * whose key is new are inserts, and a record whose key exists
     * with a different name becomes a name update carrying the
     * winning name from [mergedImportName]. Duplicates inside the
     * incoming list itself resolve the same way, in file order:
     * the first occurrence inserts, a later one may rename it.
     * The caller's map is never mutated.
     *
     * A record whose key is tombstoned on this device (see
     * [tombstoneHash]) is skipped entirely: no insert, no rename.
     * A manual delete on this device wins over the other device's
     * copy arriving again, exactly like it wins over a sync. The
     * same holds for a record dated inside one of [pauseWindows]:
     * a paused span of history is refused forever, by every path.
     */
    fun planWatchMerge(
        existingNames: Map<String, String>,
        incoming: List<CallRecord>,
        tombstoneHashes: Set<String> = emptySet(),
        pauseWindows: List<PauseWindow> = emptyList()
    ): MergePlan {
        val working = HashMap(existingNames)
        val inserts = ArrayList<CallRecord>()
        val updates = ArrayList<CallRecord>()
        for (r in incoming) {
            val key = dedupeKey(r)
            if (isTombstoned(key, tombstoneHashes)) continue
            // A call dated inside a pause window (v1.2.8) never
            // enters the vault, not even by arriving again later
            // through the watch folder: no insert, no rename.
            if (isInPauseWindow(r.dateMs, pauseWindows)) continue
            val current = working[key]
            if (current == null) {
                inserts.add(r)
                working[key] = r.name
            } else {
                val winner = mergedImportName(current, r.name)
                if (winner != current) {
                    updates.add(r.copy(name = winner))
                    working[key] = winner
                }
            }
        }
        return MergePlan(inserts, updates)
    }

    // --------------------------------------- Tombstones (v1.2.0)

    /**
     * The stored form of a tombstone: the one way SHA-256 of a
     * dedupe key. A manual delete writes one of these for every
     * record it removes, and every path that can bring a record
     * into the vault (system sync, backup import, watch folder
     * merge) refuses a record whose key hashes to a stored value.
     * The vault itself stores nothing readable about what was
     * deleted, only the hash and the time. Because the key carries
     * the call's exact date, a tombstone can never block a
     * genuinely new call from the same number.
     */
    fun tombstoneHash(key: String): String = sha256Hex(key)

    /** True when [key] hashes to one of the stored [tombstoneHashes]. */
    fun isTombstoned(key: String, tombstoneHashes: Set<String>): Boolean =
        tombstoneHashes.contains(tombstoneHash(key))

    /**
     * Whether an incoming record may enter the vault: its key is
     * neither already saved nor tombstoned. A tombstoned key loses
     * even when the vault no longer holds the record, which is the
     * whole point: a manual delete wins over every later import,
     * sync and merge.
     */
    fun shouldAcceptRecord(
        key: String,
        vaultKeys: Set<String>,
        tombstoneHashes: Set<String>
    ): Boolean = !vaultKeys.contains(key) && !isTombstoned(key, tombstoneHashes)

    /** The records of [records] that [shouldAcceptRecord] lets in, in file order. */
    fun filterAccepted(
        records: List<CallRecord>,
        vaultKeys: Set<String>,
        tombstoneHashes: Set<String>
    ): List<CallRecord> = records.filter { shouldAcceptRecord(dedupeKey(it), vaultKeys, tombstoneHashes) }

    // --------------------------------------------- Bin (v1.2.8)

    /** How long a deleted call stays in the bin before it is purged for good. */
    const val BIN_RETENTION_MS: Long = 30L * 24 * 60 * 60 * 1000

    private const val DAY_MS: Long = 24L * 60 * 60 * 1000

    /** One binned call: its bin row id, the full saved record, and when it was deleted. */
    data class BinEntry(val binId: Long, val record: CallRecord, val deletedAtMs: Long)

    /**
     * True once a bin row has sat for the full retention. The
     * boundary is inclusive: a row deleted exactly 30 days ago is
     * expired. A delete time in the future (a clock that jumped
     * back) is never expired, so nothing is purged early.
     */
    fun isBinExpired(deletedAtMs: Long, nowMs: Long): Boolean =
        nowMs - deletedAtMs >= BIN_RETENTION_MS

    /**
     * Whole days a bin row still has left, for the Bin screen:
     * 30 at the moment of the delete, 1 through its last day, 0
     * once expired. Never negative, never above 30.
     */
    fun binDaysLeft(deletedAtMs: Long, nowMs: Long): Int {
        val remaining = BIN_RETENTION_MS - (nowMs - deletedAtMs)
        if (remaining <= 0L) return 0
        val days = (remaining + DAY_MS - 1) / DAY_MS
        return days.coerceIn(1L, 30L).toInt()
    }

    // -------------------------------------------- Pause (v1.2.8)

    /**
     * One recorded pause: imports refuse any call dated inside
     * [startMs, endMs), forever. The start is inclusive and the
     * end exclusive, so a call made at the exact resume moment
     * counts as life after the pause and imports normally. A
     * pause with no planned end (v1.2.9, "until I turn it back
     * on") is stored with [OPEN_PAUSE_END_MS] as its end, so
     * while it is live every window check below reads it as
     * [startMs, forever); resuming rewrites the stored end to
     * the resume moment and it becomes an ordinary window.
     */
    data class PauseWindow(val startMs: Long, val endMs: Long)

    /** The four pause lengths the drawer offers. */
    enum class PauseChoice { ONE_HOUR, FOUR_HOURS, UNTIL_TOMORROW, UNTIL_TURNED_ON }

    /**
     * The stored end of a pause that has no planned end: so far
     * in the future that every real timestamp falls before it,
     * which is what makes the open window read as endless to
     * [isInPauseWindow] and [activePauseWindow] without a second
     * code path. It is never a real resume time and must never
     * be shown to the user as one; test for it with [isOpenPause].
     */
    const val OPEN_PAUSE_END_MS: Long = Long.MAX_VALUE

    /** True when [window] is a pause with no planned end, still open. */
    fun isOpenPause(window: PauseWindow): Boolean = window.endMs == OPEN_PAUSE_END_MS

    /** How long finished pause windows are kept as refusal records. */
    const val PAUSE_WINDOW_KEEP_MS: Long = 90L * 24 * 60 * 60 * 1000

    /**
     * The resume moment for one pause [choice], counted from
     * [nowMs]. UNTIL_TOMORROW is the next 08:00 local time strictly
     * in the future: at 07:59 it is this morning, at 08:00 sharp it
     * is already tomorrow morning. UNTIL_TURNED_ON has no resume
     * moment at all: its end is [OPEN_PAUSE_END_MS].
     */
    fun pauseEndForChoice(choice: PauseChoice, nowMs: Long): Long = when (choice) {
        PauseChoice.ONE_HOUR -> nowMs + 60L * 60 * 1000
        PauseChoice.FOUR_HOURS -> nowMs + 4L * 60 * 60 * 1000
        PauseChoice.UNTIL_TURNED_ON -> OPEN_PAUSE_END_MS
        PauseChoice.UNTIL_TOMORROW -> {
            val cal = Calendar.getInstance()
            cal.timeInMillis = nowMs
            cal.set(Calendar.HOUR_OF_DAY, 8)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            if (cal.timeInMillis <= nowMs) cal.add(Calendar.DAY_OF_YEAR, 1)
            cal.timeInMillis
        }
    }

    /** True when [callDateMs] falls inside any recorded pause window. */
    fun isInPauseWindow(callDateMs: Long, windows: List<PauseWindow>): Boolean =
        windows.any { callDateMs >= it.startMs && callDateMs < it.endMs }

    /**
     * The pause in force at [nowMs], or null when the vault is
     * live. Overlapping windows resolve to the one that ends
     * latest, which is the honest resume time; an open window's
     * endless end always wins that resolution, so it stays the
     * pause in force until it is resumed.
     */
    fun activePauseWindow(windows: List<PauseWindow>, nowMs: Long): PauseWindow? =
        windows.filter { nowMs >= it.startMs && nowMs < it.endMs }
            .maxByOrNull { it.endMs }

    /** True when the vault is paused at [nowMs]. */
    fun isPausedAt(nowMs: Long, windows: List<PauseWindow>): Boolean =
        activePauseWindow(windows, nowMs) != null

    /**
     * Where a resume-now at [nowMs] leaves the window's end: the
     * earlier of the planned end and now, and never before the
     * window's own start, so a clamped window is never inverted.
     * For an open window the planned end is endless, so the
     * resume always lands exactly on now and closes it.
     */
    fun clampedPauseEnd(window: PauseWindow, nowMs: Long): Long =
        minOf(window.endMs, maxOf(window.startMs, nowMs))

    /**
     * True when a finished window is old enough to prune: it ended
     * more than [PAUSE_WINDOW_KEEP_MS] ago. A window still running
     * or recently ended is never stale, and an open window is
     * never stale at all: housekeeping must not prune the pause
     * the user has not ended yet.
     */
    fun isPauseWindowStale(window: PauseWindow, nowMs: Long): Boolean {
        if (isOpenPause(window)) return false
        return nowMs - window.endMs > PAUSE_WINDOW_KEEP_MS
    }

    /**
     * An exact delete against the phone's own call log for one
     * record: the CallLog.Calls selection and its arguments,
     * matching on the stored number column, the date and the type.
     * The number argument is the normalized number, the identity
     * the vault itself uses; a provider that stored the number in
     * another format simply matches nothing here, and the caller
     * falls back to comparing normalized numbers row by row.
     */
    data class ProviderDeleteSpec(val selection: String, val args: List<String>)

    fun providerDeleteSpec(r: CallRecord): ProviderDeleteSpec = ProviderDeleteSpec(
        selection = "number = ? AND date = ? AND type = ?",
        args = listOf(r.numberNorm, r.dateMs.toString(), r.type.toString())
    )

    /**
     * The result line after a manual delete: plain counts of what
     * left the vault and, for the vault and phone log flavor, what
     * left the phone log. [phoneRemoved] is null when the phone
     * log was not touched (the vault only flavor).
     */
    fun deleteOutcomeText(vaultRemoved: Int, phoneRemoved: Int?): String {
        val vault = if (vaultRemoved == 1) {
            "Removed 1 entry from the vault."
        } else {
            "Removed " + vaultRemoved + " entries from the vault."
        }
        if (phoneRemoved == null) return vault
        val phone = when (phoneRemoved) {
            0 -> "No matching entries were in the phone log."
            1 -> "Removed 1 entry from the phone log."
            else -> "Removed " + phoneRemoved + " entries from the phone log."
        }
        return vault + " " + phone
    }

    /** The Status line text for the last watch check, without its time. */
    fun watchStatusText(result: Int, added: Int): String = when (result) {
        WATCH_RESULT_IMPORTED ->
            if (added == 1) "Watch folder: 1 new call imported"
            else "Watch folder: " + added + " new calls imported"
        WATCH_RESULT_UNREADABLE -> "Watch folder: a changed backup could not be read"
        else -> "Watch folder: up to date"
    }

    /**
     * Whether a finished sync needs the full on screen rebuild
     * (regroup people, rebuild every list item, notify the adapter).
     * A sync that added nothing AND left the vault count equal to the
     * snapshot from the last full apply, with records already on
     * screen, changed nothing the user can see, so the rebuild is
     * skipped and only the last sync text moves. Anything else (first
     * population, calls added, count moved by an import, a restore or
     * a trim) rebuilds as before. Before v1.0.6 every silent sync,
     * including the sniffer's own repeated runs, paid the full
     * rebuild over thousands of rows.
     */
    fun shouldApplyRebuild(
        snapshotCount: Long,
        newCount: Long,
        added: Int,
        hasRecords: Boolean
    ): Boolean {
        if (!hasRecords) return true
        if (added != 0) return true
        return newCount != snapshotCount
    }

    /** Main top bar title for the two browse pages (GitHub issue #8). */
    fun mainPageTitle(peopleMode: Boolean): String = if (peopleMode) "People" else "Call Logs"

    /**
     * One page's saved list position: the first visible adapter row
     * and that row's offset from the list's top padding. The offset
     * is negative while the row is partly scrolled above the top.
     */
    data class PageScrollState(
        val firstVisiblePosition: Int = 0,
        val firstChildOffsetPx: Int = 0
    )

    /** Sanitized scroll state captured from a list. */
    fun pageScrollState(firstVisiblePosition: Int, firstChildOffsetPx: Int?): PageScrollState =
        PageScrollState(
            firstVisiblePosition = firstVisiblePosition.coerceAtLeast(0),
            firstChildOffsetPx = firstChildOffsetPx ?: 0
        )

    /** A saved scroll state made safe for a list of [itemCount] rows. */
    fun restoredPageScrollState(state: PageScrollState, itemCount: Int): PageScrollState {
        if (itemCount <= 0) return PageScrollState()
        return state.copy(
            firstVisiblePosition = state.firstVisiblePosition.coerceIn(0, itemCount - 1)
        )
    }

    /** True when the first row is at, or back above, the list's top padding. */
    fun isAtTop(firstVisiblePosition: Int, firstChildTopPx: Int, paddingTopPx: Int): Boolean =
        firstVisiblePosition == 0 && firstChildTopPx >= paddingTopPx

    /**
     * True once at least one whole row has scrolled above the list.
     * A partly scrolled first row alone does not bring up the
     * scroll to top button, so it never flickers on for tiny scrolls.
     */
    fun isMeaningfullyAwayFromTop(firstVisiblePosition: Int): Boolean =
        firstVisiblePosition > 0

    /**
     * Estimated distance the list has scrolled, in pixels (v1.2.6).
     * Whole rows above the first visible one are approximated with
     * the first visible row's own height (rows and date headers are
     * close enough in height for a visibility threshold), plus the
     * part of that first row already above the top padding.
     */
    fun estimatedScrollPx(
        firstVisiblePosition: Int,
        firstChildTopPx: Int,
        paddingTopPx: Int,
        firstChildHeightPx: Int
    ): Int {
        if (firstVisiblePosition < 0) return 0
        val within = (paddingTopPx - firstChildTopPx).coerceAtLeast(0)
        val rowHeight = if (firstChildHeightPx > 0) firstChildHeightPx else 0
        return firstVisiblePosition * rowHeight + within
    }

    /**
     * The scroll to top pill's "away" test (v1.2.6): any whole row
     * above the list is away, and so is an estimated scroll past
     * [thresholdPx] while the first row is still the partly scrolled
     * one (a tall first row, or a position read mid layout). This
     * replaces the v1.2.5 position only test, which a single stale
     * pre layout read of position 0 could latch off.
     */
    fun scrollToTopAwayPx(
        firstVisiblePosition: Int,
        firstChildTopPx: Int,
        paddingTopPx: Int,
        firstChildHeightPx: Int,
        thresholdPx: Int
    ): Boolean {
        if (firstVisiblePosition < 0) return false
        if (firstVisiblePosition > 0) return true
        return estimatedScrollPx(
            firstVisiblePosition, firstChildTopPx, paddingTopPx, firstChildHeightPx
        ) >= thresholdPx.coerceAtLeast(0)
    }

    /**
     * The list's scrolled fraction, for the fast scroll scrubber's
     * thumb (v1.2.7): 0 at the very top, 1 when the last page of
     * rows is on screen. [firstVisible] and [visibleCount] are the
     * rows currently laid out of [totalCount]. When everything fits
     * on one screen there is no travel, so the fraction is 0, and
     * out of range reports clamp instead of escaping 0..1.
     */
    fun scrollFraction(firstVisible: Int, visibleCount: Int, totalCount: Int): Float {
        if (totalCount <= 0 || visibleCount <= 0) return 0f
        val travel = totalCount - visibleCount
        if (travel <= 0) return 0f
        val travelled = firstVisible.coerceAtLeast(0).toFloat() / travel.toFloat()
        return travelled.coerceIn(0f, 1f)
    }

    /**
     * The adapter index the scrubber thumb targets at [fraction]
     * of its track (v1.2.7): fraction 0 is the first row and
     * fraction 1 the last, so a short thumb drag crosses the whole
     * list (the scroll multiplier feel Arjun asked for). Fractions
     * outside 0..1 clamp, and an empty list targets 0 rather than
     * a row that does not exist.
     */
    fun scrubIndexForFraction(fraction: Float, itemCount: Int): Int {
        if (itemCount <= 0) return 0
        val clamped = fraction.coerceIn(0f, 1f)
        return (clamped * (itemCount - 1)).roundToInt().coerceIn(0, itemCount - 1)
    }

    /**
     * How long the list must sit idle before the scrubber hides
     * (v1.3.0, issue #14; quickened to 1200ms in v1.3.1), and the
     * pure visibility decision behind it: while the thumb is
     * being dragged it always shows, otherwise it shows until
     * the idle time reaches the threshold, and at or past the
     * threshold it hides. The activity owns the timer; this
     * only decides.
     */
    const val SCRUB_IDLE_FADE_MS = 1200L

    fun shouldScrubberShow(
        isDragging: Boolean,
        idleMs: Long,
        thresholdMs: Long = SCRUB_IDLE_FADE_MS
    ): Boolean = isDragging || idleMs < thresholdMs

    /**
     * Uniform shrink hide and slow smooth show (v1.3.2,
     * replacing the v1.3.1 scaleX only collapse, which read as
     * a sideways squeeze, and the fast 220ms overshoot pop,
     * which read as a jerk): the hide shrinks the pill
     * proportionally, both axes together, from 1 down to
     * SCRUB_HIDDEN_SCALE over SCRUB_HIDE_MS, and the show
     * grows it back from that same small size to 1 over
     * SCRUB_SHOW_MS with a gentle ease. The show starts at
     * the hidden size only when the pill was hidden; an
     * already visible pill needs no start state change.
     */
    const val SCRUB_HIDE_MS = 250L
    const val SCRUB_SHOW_MS = 380L
    const val SCRUB_HIDDEN_SCALE = 0.25f

    fun scrubShowStartScale(wasHidden: Boolean): Float =
        if (wasHidden) SCRUB_HIDDEN_SCALE else 1f

    /** Display name for a document tree id like "primary:Download/Backups". */
    fun folderNameFromTreeId(docId: String): String {
        val tail = docId.substringAfterLast('/').substringAfterLast(':').trim()
        return if (tail.isEmpty()) "Selected folder" else tail
    }

    fun formatDuration(totalSec: Long): String {
        if (totalSec <= 0) return "0s"
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return when {
            h > 0 -> String.format(Locale.ENGLISH, "%dh %02dm", h, m)
            m > 0 -> String.format(Locale.ENGLISH, "%dm %02ds", m, s)
            else -> String.format(Locale.ENGLISH, "%ds", s)
        }
    }

    fun formatDateTime(dateMs: Long): String = fmtDateTime.get().format(Date(dateMs))

    fun formatTime(dateMs: Long): String = fmtTime.get().format(Date(dateMs))

    fun readableDate(dateMs: Long): String = fmtReadable.get().format(Date(dateMs))

    // ------------------------------------------------- Row display strings
    // The list rows are single custom drawn views (RowView). Every string
    // a row shows is composed here, once, when the item list is built, so
    // binding a row during a scroll does no formatting at all. These
    // functions reproduce the exact strings the old view based rows built
    // per bind in v1.0.2.

    /** Subtitle of a call row: the number plus the type, or just the type for unknown names. */
    fun callSubtitle(name: String, numberRaw: String, type: Int): String =
        if (isUnknownName(name)) typeLabel(type)
        else numberRaw + "  ·  " + typeLabel(type)

    /** Bottom right text of a call row: the duration, empty when the call had none. */
    fun callRightBottom(durationSec: Long): String =
        if (durationSec > 0) formatDuration(durationSec) else ""

    /** Subtitle of a person row: number, call count and total talk time. */
    fun personSubtitle(numberRaw: String, callCount: Int, totalDurationSec: Long): String =
        numberRaw + "  ·  " + callCount + " calls  ·  " + formatDuration(totalDurationSec) + " total"

    /**
     * Person row subtitle under the privacy toggle (v1.4.0): with
     * stats on it is the full subtitle; with stats off it is just
     * the number, so no count or talk time leaks into the list.
     */
    fun personRowSubtitle(numberRaw: String, callCount: Int, totalDurationSec: Long, showStats: Boolean): String =
        if (showStats) personSubtitle(numberRaw, callCount, totalDurationSec) else numberRaw

    /** People mode only counts when the People menu itself is on (v1.4.0). */
    fun effectivePeopleMode(requestedPeople: Boolean, showPeopleMenu: Boolean): Boolean =
        requestedPeople && showPeopleMenu

    /**
     * The What's New popup fires once per version: whenever the
     * stored last shown version differs from the running one,
     * including a first install (stored 0), so upgraders from any
     * older build see it exactly once.
     */
    fun shouldShowWhatsNew(lastShownVersion: Int, currentVersion: Int): Boolean =
        lastShownVersion != currentVersion

    /** Top right text of a person row: Today or Yesterday, else the short date. */
    fun personRightTop(lastDateMs: Long, nowMs: Long): String {
        val label = dateHeaderLabel(lastDateMs, nowMs)
        return if (label == "Today" || label == "Yesterday") label
        else formatDateTime(lastDateMs).substringBefore(",")
    }

    /** Subtitle of a person timeline row: relative day, time and short date. */
    fun timelineSubtitle(dateMs: Long, nowMs: Long): String {
        val label = dateHeaderLabel(dateMs, nowMs)
        val base = label + "  ·  " + formatTime(dateMs)
        // Today and Yesterday carry no date in the label, so append the
        // short date. Older labels already are the full date; appending
        // it again would print the date twice.
        return if (label == "Today" || label == "Yesterday") {
            base + "  ·  " + formatDateTime(dateMs).substringBefore(",")
        } else {
            base
        }
    }

    /**
     * Ellipsize [text] so its measured width fits [maxWidth], appending a
     * single ellipsis character. [measure] is injected (a Paint measure in
     * the app, a plain function in tests), which keeps this pure. Returns
     * the text unchanged when it already fits, and an empty string when
     * even the ellipsis alone cannot fit.
     */
    fun ellipsizeToWidth(text: String, maxWidth: Float, measure: (String) -> Float): String {
        if (text.isEmpty() || maxWidth <= 0f) return ""
        if (measure(text) <= maxWidth) return text
        val ellipsis = "…"
        if (measure(ellipsis) > maxWidth) return ""
        var lo = 0
        var hi = text.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (measure(text.substring(0, mid) + ellipsis) <= maxWidth) lo = mid else hi = mid - 1
        }
        return text.substring(0, lo) + ellipsis
    }

    // --------------------------------------------------------- Appearance

    /** Precompiled once: initialsFor runs on every list bind. */
    private val WHITESPACE = Regex("\\s+")

    // Appearance v2 values, stored under a new prefs key in v1.0.2.
    // LIGHT, DARK and AUTO are the stored modes. AMOLED is a concrete
    // render preset only: since the appearance refinement it is no
    // longer stored as a mode of its own; the Dark mode renders it
    // whenever the amoled_for_dark switch is on.
    const val APPEARANCE_LIGHT = 0
    const val APPEARANCE_DARK = 1
    const val APPEARANCE_AMOLED = 2

    /**
     * Follow the device: not a concrete preset of its own, it
     * resolves through [resolveAppearance] below using the system
     * night flag and the amoled_for_dark switch.
     */
    const val APPEARANCE_AUTO = 3

    // v1.0.1 legacy values, stored under the old "appearance" key.
    const val LEGACY_APPEARANCE_SYSTEM = 0
    const val LEGACY_APPEARANCE_LIGHT = 1
    const val LEGACY_APPEARANCE_DARK = 2

    /** The appearance state in the current scheme: a mode plus the switch. */
    data class AppearanceState(val mode: Int, val amoledForDark: Boolean)

    /**
     * Whether a remembered v1.0.8 dark flavour means the AMOLED
     * switch starts on. Only an explicit Dark flavour turns it off;
     * an AMOLED flavour, a missing flavour and any other stored
     * value all count as on, matching the app's AMOLED defaults.
     */
    private fun flavourMeansAmoled(hasFlavour: Boolean, flavour: Int): Boolean =
        !(hasFlavour && flavour == APPEARANCE_DARK)

    /**
     * Migrate stored appearance state from before the refinement
     * (where the stored value was a concrete preset and v1.0.8 also
     * remembered a "dark flavour") into the current scheme of a
     * mode plus the amoled_for_dark switch, landing every user on
     * exactly the look they had:
     * - stored Light stays Light (the switch follows the flavour).
     * - stored Dark becomes the Dark mode with the switch off.
     * - stored AMOLED becomes the Dark mode with the switch on.
     * - stored Auto stays Auto; its night side followed the flavour,
     *   so the switch takes the flavour (on unless it was Dark).
     * - an unknown stored value keeps the old fallback look, AMOLED,
     *   as the Dark mode with the switch on.
     * Without a v2 value, an explicit legacy Light or Dark carries
     * over the same way, and everything else (legacy System, no
     * stored value at all, a fresh install) lands on the Dark mode
     * with the switch on, the AMOLED look the app has always
     * defaulted to.
     */
    fun migrateAppearance(
        hasV2: Boolean,
        v2: Int,
        hasFlavour: Boolean,
        flavour: Int,
        hasLegacy: Boolean,
        legacy: Int
    ): AppearanceState {
        if (hasV2) {
            return when (v2) {
                APPEARANCE_LIGHT ->
                    AppearanceState(APPEARANCE_LIGHT, flavourMeansAmoled(hasFlavour, flavour))
                APPEARANCE_DARK -> AppearanceState(APPEARANCE_DARK, false)
                APPEARANCE_AMOLED -> AppearanceState(APPEARANCE_DARK, true)
                APPEARANCE_AUTO ->
                    AppearanceState(APPEARANCE_AUTO, flavourMeansAmoled(hasFlavour, flavour))
                else -> AppearanceState(APPEARANCE_DARK, true)
            }
        }
        if (hasLegacy && legacy == LEGACY_APPEARANCE_LIGHT) {
            return AppearanceState(APPEARANCE_LIGHT, flavourMeansAmoled(hasFlavour, flavour))
        }
        if (hasLegacy && legacy == LEGACY_APPEARANCE_DARK) {
            return AppearanceState(APPEARANCE_DARK, false)
        }
        return AppearanceState(APPEARANCE_DARK, true)
    }

    /**
     * Resolve a stored appearance [mode] to the concrete preset to
     * render with. Light is always Light. Dark renders AMOLED when
     * [amoledForDark] is on and plain Dark when it is off. Auto is
     * Light while the system is not in night mode, and at night it
     * follows the same switch as Dark. An unknown mode counts as
     * AMOLED, the standing fallback.
     */
    fun resolveAppearance(mode: Int, amoledForDark: Boolean, systemNight: Boolean): Int {
        return when (mode) {
            APPEARANCE_LIGHT -> APPEARANCE_LIGHT
            APPEARANCE_DARK -> if (amoledForDark) APPEARANCE_AMOLED else APPEARANCE_DARK
            APPEARANCE_AUTO -> when {
                !systemNight -> APPEARANCE_LIGHT
                amoledForDark -> APPEARANCE_AMOLED
                else -> APPEARANCE_DARK
            }
            else -> APPEARANCE_AMOLED
        }
    }

    fun appearanceName(value: Int): String = when (value) {
        APPEARANCE_LIGHT -> "Light"
        APPEARANCE_DARK -> "Dark"
        APPEARANCE_AMOLED -> "AMOLED"
        APPEARANCE_AUTO -> "Auto"
        else -> "AMOLED"
    }

    /**
     * File Master bar surfaces (v1.3.3, taken verbatim from FM
     * Theme.barDrawable in ~/workspace/file-master, which is the
     * locked flat look after the glass saga closed): solid quiet
     * fills, no gradient, no blur, no translucency. Light white,
     * dark #2C2C2E, AMOLED #1C1C1E. Hairline black 10 percent on
     * light (FM withAlpha rounds to 0x1A), white 12 percent on
     * dark and AMOLED (FM withAlpha rounds to 0x1F).
     */
    fun solidBarFill(appearance: Int): Int = when (appearance) {
        APPEARANCE_LIGHT -> 0xFFFFFFFF.toInt()
        APPEARANCE_AMOLED -> 0xFF1C1C1E.toInt()
        else -> 0xFF2C2C2E.toInt()
    }

    fun solidBarStroke(appearance: Int): Int = when (appearance) {
        APPEARANCE_LIGHT -> 0x1A000000.toInt()
        else -> 0x1FFFFFFF.toInt()
    }

    // ------------------------------------------------------------------ XML

    fun xmlEscape(v: String): String {
        val sb = StringBuilder()
        for (c in v) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    fun xmlUnescape(v: String): String {
        if (!v.contains('&')) return v
        val sb = StringBuilder()
        var i = 0
        while (i < v.length) {
            val c = v[i]
            if (c == '&') {
                val semi = v.indexOf(';', i)
                if (semi > i && semi - i <= 6) {
                    when (v.substring(i, semi + 1)) {
                        "&amp;" -> { sb.append('&'); i = semi + 1; continue }
                        "&lt;" -> { sb.append('<'); i = semi + 1; continue }
                        "&gt;" -> { sb.append('>'); i = semi + 1; continue }
                        "&quot;" -> { sb.append('"'); i = semi + 1; continue }
                        "&apos;" -> { sb.append('\''); i = semi + 1; continue }
                    }
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    /**
     * SMS Backup and Restore calls format. Root element calls with count,
     * backup_set and backup_date attributes, one call element per record
     * with number, duration, date, type, presentation, subscription_id,
     * readable_date and contact_name attributes.
     */
    fun generateCallsXml(records: List<CallRecord>, backupSet: String, backupDateMs: Long): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\" ?>\n")
        sb.append("<calls count=\"").append(records.size)
            .append("\" backup_set=\"").append(xmlEscape(backupSet))
            .append("\" backup_date=\"").append(backupDateMs).append("\">\n")
        for (r in records) {
            sb.append(" <call number=\"").append(xmlEscape(r.numberRaw))
                .append("\" duration=\"").append(r.durationSec)
                .append("\" date=\"").append(r.dateMs)
                .append("\" type=\"").append(r.type)
                .append("\" presentation=\"1\" subscription_id=\"").append(xmlEscape(r.subscriptionId))
                .append("\" readable_date=\"").append(readableDate(r.dateMs))
                .append("\" contact_name=\"").append(xmlEscape(if (isUnknownName(r.name)) "(Unknown)" else r.name.trim()))
                .append("\" />\n")
        }
        sb.append("</calls>\n")
        return sb.toString()
    }

    /** Parse calls XML in the format above (also reads files from other tools). */
    fun parseCallsXml(xml: String): List<CallRecord> {
        val out = ArrayList<CallRecord>()
        var idx = 0
        while (true) {
            val start = xml.indexOf("<call", idx)
            if (start < 0) break
            // Skip the root <calls ...> element: it has no space-slash ending
            // pattern of a record and carries count instead of number.
            val after = start + 5
            if (after < xml.length && (xml[after] == 's')) { idx = after; continue }
            val end = findTagEnd(xml, after)
            if (end < 0) break
            val attrs = parseAttributes(xml.substring(after, end))
            idx = end + 1
            val number = attrs["number"] ?: continue
            val date = attrs["date"]?.toLongOrNull() ?: continue
            val type = attrs["type"]?.toIntOrNull() ?: TYPE_INCOMING
            val name = attrs["contact_name"] ?: ""
            out.add(
                CallRecord(
                    numberRaw = number,
                    numberNorm = normalizeNumber(number),
                    name = if (name == "(Unknown)") "" else name,
                    dateMs = date,
                    type = type,
                    durationSec = attrs["duration"]?.toLongOrNull() ?: 0L,
                    subscriptionId = attrs["subscription_id"] ?: ""
                )
            )
        }
        return out
    }

    private fun findTagEnd(s: String, from: Int): Int {
        var inQuote = false
        var i = from
        while (i < s.length) {
            val c = s[i]
            if (c == '"') inQuote = !inQuote
            if (c == '>' && !inQuote) return i
            i++
        }
        return -1
    }

    private fun parseAttributes(s: String): Map<String, String> {
        val map = HashMap<String, String>()
        var i = 0
        val n = s.length
        while (i < n) {
            while (i < n && (s[i].isWhitespace() || s[i] == '/')) i++
            if (i >= n) break
            val nameStart = i
            while (i < n && s[i] != '=' && !s[i].isWhitespace()) i++
            val name = s.substring(nameStart, i)
            while (i < n && s[i].isWhitespace()) i++
            if (i >= n || s[i] != '=') { continue }
            i++
            while (i < n && s[i].isWhitespace()) i++
            if (i >= n || s[i] != '"') { continue }
            i++
            val valStart = i
            while (i < n && s[i] != '"') i++
            val value = s.substring(valStart, i)
            if (i < n) i++
            if (name.isNotEmpty()) map[name] = xmlUnescape(value)
        }
        return map
    }

    // ------------------------------------------------------------------ CSV

    fun csvQuote(v: String): String {
        return if (v.contains(',') || v.contains('"') || v.contains('\n') || v.contains('\r')) {
            "\"" + v.replace("\"", "\"\"") + "\""
        } else v
    }

    fun generateCsv(records: List<CallRecord>): String {
        val sb = StringBuilder()
        sb.append("number,name,date_ms,date,type,type_label,duration_sec,subscription_id\n")
        for (r in records) {
            sb.append(csvQuote(r.numberRaw)).append(',')
                .append(csvQuote(r.name)).append(',')
                .append(r.dateMs).append(',')
                .append(csvQuote(formatDateTime(r.dateMs))).append(',')
                .append(r.type).append(',')
                .append(csvQuote(typeLabel(r.type))).append(',')
                .append(r.durationSec).append(',')
                .append(csvQuote(r.subscriptionId)).append('\n')
        }
        return sb.toString()
    }

    /**
     * Split CSV text into rows of fields, honouring the quoting
     * [csvQuote] writes: quoted fields may contain commas, doubled
     * quotes and newlines. A trailing row without a final newline
     * is still returned. Pure tokenizer, shared by the parser and
     * the tests.
     */
    fun parseCsvRows(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var inQuotes = false
        var fieldStarted = false
        fun endField() {
            row.add(field.toString())
            field.setLength(0)
            fieldStarted = false
        }
        fun endRow() {
            endField()
            rows.add(row)
            row = ArrayList()
        }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes -> {
                    if (c == '"') {
                        if (i + 1 < text.length && text[i + 1] == '"') {
                            field.append('"')
                            i++
                        } else {
                            inQuotes = false
                        }
                    } else {
                        field.append(c)
                    }
                }
                c == '"' && !fieldStarted -> {
                    inQuotes = true
                    fieldStarted = true
                }
                c == ',' -> endField()
                c == '\n' -> endRow()
                c == '\r' -> {
                    // Part of a CRLF pair, or a lone row end: the
                    // following LF (if any) finishes the row.
                    if (i + 1 >= text.length || text[i + 1] != '\n') endRow()
                }
                else -> {
                    field.append(c)
                    fieldStarted = true
                }
            }
            i++
        }
        if (fieldStarted || field.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }

    /** True when [row] is the header row [generateCsv] writes. */
    fun isCsvHeaderRow(row: List<String>): Boolean =
        row.size >= 3 && row[0] == "number" && row[2] == "date_ms"

    /**
     * Parse a calls.csv in the [generateCsv] format back into
     * records. The header row is skipped; rows with too few fields
     * or an unreadable date are skipped. Column order is the export
     * order: number, name, date_ms, date, type, type_label,
     * duration_sec, subscription_id.
     */
    fun parseCallsCsv(csv: String): List<CallRecord> {
        val out = ArrayList<CallRecord>()
        for (row in parseCsvRows(csv)) {
            if (isCsvHeaderRow(row)) continue
            if (row.size < 7) continue
            val number = row[0]
            if (number.isBlank()) continue
            val date = row[2].toLongOrNull() ?: continue
            out.add(
                CallRecord(
                    numberRaw = number,
                    numberNorm = normalizeNumber(number),
                    name = row[1],
                    dateMs = date,
                    type = row[4].toIntOrNull() ?: TYPE_INCOMING,
                    durationSec = row[6].toLongOrNull() ?: 0L,
                    subscriptionId = if (row.size > 7) row[7] else ""
                )
            )
        }
        return out
    }

    // ----------------------------------------------------------------- JSON

    fun jsonEscape(v: String): String {
        val sb = StringBuilder()
        for (c in v) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (c.code < 0x20) sb.append(String.format(Locale.ENGLISH, "\\u%04x", c.code))
                    else sb.append(c)
                }
            }
        }
        return sb.toString()
    }

    fun generateJson(records: List<CallRecord>, exportedAtMs: Long): String {
        val sb = StringBuilder()
        sb.append("{\n  \"app\": \"Call Log Vault\",\n  \"format_version\": 1,\n  \"exported_at\": ")
            .append(exportedAtMs).append(",\n  \"count\": ").append(records.size).append(",\n  \"calls\": [\n")
        for ((i, r) in records.withIndex()) {
            sb.append("    {\"number\": \"").append(jsonEscape(r.numberRaw))
                .append("\", \"number_norm\": \"").append(jsonEscape(r.numberNorm))
                .append("\", \"name\": \"").append(jsonEscape(r.name))
                .append("\", \"date\": ").append(r.dateMs)
                .append(", \"type\": ").append(r.type)
                .append(", \"duration\": ").append(r.durationSec)
                .append(", \"subscription_id\": \"").append(jsonEscape(r.subscriptionId))
                .append("\"}")
            if (i < records.size - 1) sb.append(',')
            sb.append('\n')
        }
        sb.append("  ]\n}\n")
        return sb.toString()
    }

    /** Minimal JSON parser, enough for this app's own export format. */
    class JsonParser(private val text: String) {
        private var pos = 0

        fun parse(): Any? {
            skipWs()
            val v = parseValue()
            skipWs()
            return v
        }

        private fun skipWs() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        private fun parseValue(): Any? {
            skipWs()
            if (pos >= text.length) return null
            return when (text[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> { expect("true"); true }
                'f' -> { expect("false"); false }
                'n' -> { expect("null"); null }
                else -> parseNumber()
            }
        }

        private fun expect(word: String) {
            if (text.regionMatches(pos, word, 0, word.length)) pos += word.length
            else throw IllegalArgumentException("Bad JSON at $pos")
        }

        private fun parseObject(): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            pos++ // {
            skipWs()
            if (pos < text.length && text[pos] == '}') { pos++; return map }
            while (true) {
                skipWs()
                val key = parseString()
                skipWs()
                if (pos < text.length && text[pos] == ':') pos++
                map[key] = parseValue()
                skipWs()
                if (pos < text.length && text[pos] == ',') { pos++; continue }
                if (pos < text.length && text[pos] == '}') { pos++; break }
                break
            }
            return map
        }

        private fun parseArray(): List<Any?> {
            val list = ArrayList<Any?>()
            pos++ // [
            skipWs()
            if (pos < text.length && text[pos] == ']') { pos++; return list }
            while (true) {
                list.add(parseValue())
                skipWs()
                if (pos < text.length && text[pos] == ',') { pos++; continue }
                if (pos < text.length && text[pos] == ']') { pos++; break }
                break
            }
            return list
        }

        private fun parseString(): String {
            val sb = StringBuilder()
            pos++ // opening quote
            while (pos < text.length) {
                val c = text[pos]
                when (c) {
                    '"' -> { pos++; return sb.toString() }
                    '\\' -> {
                        if (pos + 1 >= text.length) { pos++; break }
                        when (val e = text[pos + 1]) {
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            '/' -> sb.append('/')
                            '\\' -> sb.append('\\')
                            '"' -> sb.append('"')
                            'u' -> {
                                if (pos + 5 < text.length) {
                                    val hex = text.substring(pos + 2, pos + 6)
                                    sb.append(hex.toIntOrNull(16)?.toChar() ?: '?')
                                    pos += 4
                                }
                            }
                            else -> sb.append(e)
                        }
                        pos += 2
                    }
                    else -> { sb.append(c); pos++ }
                }
            }
            return sb.toString()
        }

        private fun parseNumber(): Long {
            val start = pos
            if (pos < text.length && text[pos] == '-') pos++
            var seenDot = false
            while (pos < text.length && (text[pos].isDigit() || text[pos] == '.' || text[pos] == 'e' || text[pos] == 'E' || text[pos] == '+' || text[pos] == '-')) {
                if (text[pos] == '.') seenDot = true
                pos++
            }
            val num = text.substring(start, pos)
            return if (seenDot) num.toDoubleOrNull()?.toLong() ?: 0L
            else num.toLongOrNull() ?: 0L
        }
    }

    fun parseCallsJson(json: String): List<CallRecord> {
        val root = try {
            JsonParser(json).parse() as? Map<*, *>
        } catch (e: Exception) {
            null
        } ?: return emptyList()
        val calls = root["calls"] as? List<*> ?: return emptyList()
        val out = ArrayList<CallRecord>()
        for (item in calls) {
            val m = item as? Map<*, *> ?: continue
            val number = m["number"]?.toString() ?: continue
            val date = (m["date"] as? Number)?.toLong() ?: continue
            out.add(
                CallRecord(
                    numberRaw = number,
                    numberNorm = m["number_norm"]?.toString() ?: normalizeNumber(number),
                    name = m["name"]?.toString() ?: "",
                    dateMs = date,
                    type = (m["type"] as? Number)?.toInt() ?: TYPE_INCOMING,
                    durationSec = (m["duration"] as? Number)?.toLong() ?: 0L,
                    subscriptionId = m["subscription_id"]?.toString() ?: ""
                )
            )
        }
        return out
    }
}

/**
 * Pull to refresh state machine (v1.2.3, GitHub issue #5). Pure:
 * the view layer feeds finger travel in pixels and renders from
 * [state] and [progress]; every decision (when a pull arms, when a
 * release triggers, why a second pull can never double trigger)
 * lives here so the JVM tests pin it without Android.
 *
 * Distances are raw finger travel from the pull start. The view
 * applies its own damping for display, so arming tracks the real
 * gesture, not the eased pixels on screen.
 */
class PullRefreshMachine(private val armThresholdPx: Float) {

    init {
        require(armThresholdPx > 0f) { "arm threshold must be positive" }
    }

    enum class State { IDLE, PULLING, ARMED, REFRESHING }

    var state: State = State.IDLE
        private set

    /** Progress toward arming: 0 at rest, 1 at the threshold. */
    var progress: Float = 0f
        private set

    val isArmed: Boolean get() = state == State.ARMED
    val isRefreshing: Boolean get() = state == State.REFRESHING

    /**
     * A finger started pulling at the top of the list. Refused
     * while a sync is refreshing (a pull must never double trigger)
     * or while a pull is already being tracked.
     */
    fun beginPull(): Boolean {
        if (state != State.IDLE) return false
        state = State.PULLING
        progress = 0f
        return true
    }

    /**
     * The finger moved; [dyPx] is its downward travel from the pull
     * start. Travel back above the start reads as zero. Arming is
     * exact: at or beyond the threshold the pull is armed, and
     * slipping back below the threshold disarms it again.
     */
    fun updatePull(dyPx: Float): State {
        if (state != State.PULLING && state != State.ARMED) return state
        val clamped = if (dyPx > 0f) dyPx else 0f
        progress = (clamped / armThresholdPx).coerceAtMost(1f)
        state = if (clamped >= armThresholdPx) State.ARMED else State.PULLING
        return state
    }

    /**
     * The finger lifted. From ARMED the refresh starts and this
     * returns true, exactly once; from PULLING the pull just
     * settles. In REFRESHING a release can never retrigger.
     */
    fun release(): Boolean {
        return when (state) {
            State.ARMED -> {
                state = State.REFRESHING
                progress = 1f
                true
            }
            State.PULLING -> {
                state = State.IDLE
                progress = 0f
                false
            }
            else -> false
        }
    }

    /**
     * The gesture was cancelled (horizontal drift won, the touch
     * stream ended, the list took the gesture back). Pulling states
     * settle to idle. A running refresh is untouched: only
     * [syncSettled] ends it.
     */
    fun cancel(): Boolean {
        if (state == State.PULLING || state == State.ARMED) {
            state = State.IDLE
            progress = 0f
            return true
        }
        return false
    }

    /** The sync the indicator reflects has finished; settle. */
    fun syncSettled(): Boolean {
        if (state != State.REFRESHING) return false
        state = State.IDLE
        progress = 0f
        return true
    }
}

/**
 * Scroll to top button state machine (v1.2.5, GitHub issue #7).
 * Pure: the view layer reports whether the list is meaningfully
 * away from the top and whether it is exactly at the top, and this
 * machine owns every visibility and progress decision. Since
 * v1.2.6 the away report is the pixel estimate above, re-derived
 * from many list events, after the v1.2.5 pill was missed on
 * device. A return can
 * only start from READY, so a second tap can never restart it, and
 * arrival is a separate brief state instead of the button simply
 * vanishing the moment the list lands.
 */
class ScrollToTopMachine {

    enum class State { HIDDEN, READY, RETURNING, ARRIVED }

    var state: State = State.HIDDEN
        private set

    /** Fold one list position report into the current state. */
    fun onPosition(awayFromTop: Boolean, atTop: Boolean): State {
        state = when (state) {
            State.RETURNING -> if (atTop) State.ARRIVED else State.RETURNING
            State.ARRIVED -> when {
                awayFromTop -> State.READY
                atTop -> State.ARRIVED
                else -> State.HIDDEN
            }
            State.HIDDEN, State.READY -> if (awayFromTop) State.READY else State.HIDDEN
        }
        return state
    }

    /** Start the return. Only a visible, ready button can start one. */
    fun startReturn(): Boolean {
        if (state != State.READY) return false
        state = State.RETURNING
        return true
    }

    /**
     * The user, a mode switch or a data change interrupted the
     * return. The button then simply reflects where the list is.
     */
    fun interrupt(awayFromTop: Boolean, atTop: Boolean): State {
        if (state == State.RETURNING || state == State.ARRIVED) {
            state = when {
                awayFromTop -> State.READY
                atTop -> State.HIDDEN
                else -> State.HIDDEN
            }
        }
        return state
    }

    /** The brief arrived state has been shown; put the button away. */
    fun finishArrived(): State {
        if (state == State.ARRIVED) state = State.HIDDEN
        return state
    }

    /** Forget the button entirely (page switch, empty or blocked list). */
    fun reset(): State {
        state = State.HIDDEN
        return state
    }
}

/** The scroll to top button's words for each machine state. */
fun scrollToTopLabel(state: ScrollToTopMachine.State): String? = when (state) {
    ScrollToTopMachine.State.HIDDEN -> null
    ScrollToTopMachine.State.READY -> "↑ Top"
    ScrollToTopMachine.State.RETURNING -> "↑ Going up"
    ScrollToTopMachine.State.ARRIVED -> "✓ At top"
}

/**
 * How long a return to the top may take: longer lists get more
 * time, inside a floor and a cap so the progress state is always
 * visible but never drags.
 */
fun scrollToTopDurationMs(firstVisiblePosition: Int): Int =
    (450 + firstVisiblePosition.coerceAtLeast(0) * 12).coerceIn(450, 1500)

/**
 * The words shown under the pull chip for each machine state
 * (v1.2.4). v1.2.3 shipped the gesture with only a spinning chip,
 * and the on-device verdict was that a spinner never says whether
 * a sync actually happened, so the pull narrates itself now. IDLE
 * has no label: the chip is off screen then too.
 */
fun pullStateLabel(state: PullRefreshMachine.State): String? = when (state) {
    PullRefreshMachine.State.IDLE -> null
    PullRefreshMachine.State.PULLING -> "Pull to sync"
    PullRefreshMachine.State.ARMED -> "Release to sync"
    PullRefreshMachine.State.REFRESHING -> "Syncing…"
}

/**
 * Whether a sync completion may be surfaced to the user (v1.2.4):
 * only the caller that actually started the sync reports it, and
 * only when it asked to. A pull that latched onto a sync someone
 * else started stays quiet, so the originator's report is the only
 * one; silent auto syncs never report at all.
 */
fun shouldReportSyncResult(startedByThisCall: Boolean, reportRequested: Boolean): Boolean =
    startedByThisCall && reportRequested

/**
 * The one completion line for a finished sync (v1.2.4): what Sync
 * now has always said on success, now built in exactly one place
 * so the manual and pull paths can never drift apart.
 */
fun syncDoneMessage(added: Int): String = "Sync done: " + added + " new calls saved"
