package com.calllogvault

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

    /** Group by normalized number. Sorted by most recent call first. */
    fun groupPeople(records: List<CallRecord>): List<PersonSummary> {
        val byNumber = LinkedHashMap<String, MutableList<CallRecord>>()
        for (r in records) byNumber.getOrPut(r.numberNorm) { ArrayList() }.add(r)
        val out = ArrayList<PersonSummary>()
        for ((norm, list) in byNumber) {
            val latest = list.maxByOrNull { it.dateMs } ?: continue
            // Display name: the name on the most recent named call, if any.
            val named = list.filter { !isUnknownName(it.name) }.maxByOrNull { it.dateMs }
            out.add(
                PersonSummary(
                    numberNorm = norm,
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

    /** Top right text of a person row: Today or Yesterday, else the short date. */
    fun personRightTop(lastDateMs: Long, nowMs: Long): String {
        val label = dateHeaderLabel(lastDateMs, nowMs)
        return if (label == "Today" || label == "Yesterday") label
        else formatDateTime(lastDateMs).substringBefore(",")
    }

    /** Subtitle of a person timeline row: relative day, time and short date. */
    fun timelineSubtitle(dateMs: Long, nowMs: Long): String =
        dateHeaderLabel(dateMs, nowMs) + "  ·  " + formatTime(dateMs) +
            "  ·  " + formatDateTime(dateMs).substringBefore(",")

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
