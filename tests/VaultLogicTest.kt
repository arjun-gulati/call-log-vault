import com.calllogvault.VaultLogic

var failures = 0
var checks = 0
fun check(name: String, cond: Boolean) {
    checks++
    if (cond) println("PASS: $name") else { println("FAIL: $name"); failures++ }
}

fun rec(
    number: String = "+919000000010",
    name: String = "Asha",
    date: Long = 1700000000000L,
    type: Int = VaultLogic.TYPE_INCOMING,
    duration: Long = 65,
    sub: String = ""
): VaultLogic.CallRecord = VaultLogic.CallRecord(
    numberRaw = number,
    numberNorm = VaultLogic.normalizeNumber(number),
    name = name,
    dateMs = date,
    type = type,
    durationSec = duration,
    subscriptionId = sub
)

fun main() {
    // Pin the timezone before any formatter is created, so the literal
    // expected strings in the formatter tests are stable everywhere.
    java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
    testNormalize()
    testDedupe()
    testXml()
    testCsv()
    testJson()
    testDates()
    testFilters()
    testGrouping()
    testDurationFormat()
    testInitials()
    testAvatarColors()
    testAutoBackupThrottle()
    testAutoBackupWriteDecision()
    testFolderNames()
    testAppearanceResolution()
    testAppearanceAuto()
    testBackupSchedules()
    testFormatterConsistency()
    testRowDisplayStrings()
    testEllipsize()
    testShouldApplyRebuild()
    println("CHECKS=$checks FAILURES=$failures")
    println(if (failures == 0) "ALL_VAULT_TESTS_PASSED" else "VAULT_TEST_FAILURES=$failures")
    if (failures > 0) kotlin.system.exitProcess(1)
}

fun testNormalize() {
    println("== normalizeNumber ==")
    check("spaces stripped", VaultLogic.normalizeNumber("+91 90000 00010") == "+919000000010")
    check("dashes and parens stripped", VaultLogic.normalizeNumber("(090) 000-0010") == "0900000010")
    check("plain digits kept", VaultLogic.normalizeNumber("9000000010") == "9000000010")
    check("leading 00 becomes plus", VaultLogic.normalizeNumber("0044 20 7946 0958") == "+442079460958")
    check("mid plus dropped", VaultLogic.normalizeNumber("98+765") == "98765")
    check("empty stays empty", VaultLogic.normalizeNumber("  ") == "")
    check("digitsOnly", VaultLogic.digitsOnly("+919000000010") == "919000000010")
}

fun testDedupe() {
    println("== dedupe key ==")
    val a = rec()
    val b = rec()
    check("same fields same key", VaultLogic.dedupeKey(a) == VaultLogic.dedupeKey(b))
    check("different date different key", VaultLogic.dedupeKey(a) != VaultLogic.dedupeKey(rec(date = a.dateMs + 1000)))
    check("different type different key", VaultLogic.dedupeKey(a) != VaultLogic.dedupeKey(rec(type = VaultLogic.TYPE_MISSED)))
    check(
        "formatted variants share a key",
        VaultLogic.dedupeKey(VaultLogic.normalizeNumber("+91 90000 00010"), a.dateMs, a.type) ==
            VaultLogic.dedupeKey(a)
    )
    val set = HashSet<String>()
    check("set dedupe works", set.add(VaultLogic.dedupeKey(a)) && !set.add(VaultLogic.dedupeKey(b)))
}

fun testXml() {
    println("== XML ==")
    val tricky = rec(name = "Tom & \"Jerry\" <Brothers> O'Brien", number = "+1 555 0100", sub = "sim&1")
    val plain = rec(name = "", number = "12345", date = 1700003600000L, type = VaultLogic.TYPE_MISSED, duration = 0)
    val xml = VaultLogic.generateCallsXml(listOf(tricky, plain), "set-123", 1700010000000L)
    check("root has count", xml.contains("<calls count=\"2\""))
    check("root has backup_set", xml.contains("backup_set=\"set-123\""))
    check("root has backup_date", xml.contains("backup_date=\"1700010000000\""))
    check("ampersand escaped", xml.contains("Tom &amp;"))
    check("quote escaped", xml.contains("&quot;Jerry&quot;"))
    check("angle brackets escaped", xml.contains("&lt;Brothers&gt;"))
    check("apos escaped", xml.contains("O&apos;Brien"))
    check("no raw ampersand in name", !xml.contains("Tom & \""))
    check("unknown name written as (Unknown)", xml.contains("contact_name=\"(Unknown)\""))
    check("fields present", xml.contains("presentation=\"1\"") && xml.contains("readable_date=") && xml.contains("subscription_id="))

    val parsed = VaultLogic.parseCallsXml(xml)
    check("round trip count", parsed.size == 2)
    check("round trip name with specials", parsed[0].name == "Tom & \"Jerry\" <Brothers> O'Brien")
    check("round trip number", parsed[0].numberRaw == "+1 555 0100")
    check("round trip normalized", parsed[0].numberNorm == "+15550100")
    check("round trip sub", parsed[0].subscriptionId == "sim&1")
    check("round trip duration and type", parsed[0].durationSec == 65L && parsed[0].type == VaultLogic.TYPE_INCOMING)
    check("round trip unknown name cleared", parsed[1].name == "")
    check("round trip missed type", parsed[1].type == VaultLogic.TYPE_MISSED)

    // A file in the shape other tools produce must parse too.
    val foreign = "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>\n" +
        "<calls count=\"1\" backup_set=\"abc\" backup_date=\"1493725143465\">\n" +
        " <call number=\"+43699112233\" duration=\"139\" date=\"1493725143465\" type=\"1\" presentation=\"1\" readable_date=\"02.05.2017 13:39:03\" contact_name=\"Franz\" />\n" +
        "</calls>"
    val f = VaultLogic.parseCallsXml(foreign)
    check("foreign file parses one record", f.size == 1)
    check("foreign file fields", f.isNotEmpty() && f[0].name == "Franz" && f[0].durationSec == 139L && f[0].subscriptionId == "")
    check("empty xml parses empty", VaultLogic.parseCallsXml("").isEmpty())
    check("xmlUnescape plain", VaultLogic.xmlUnescape("plain & simple") == "plain & simple")
}

fun testCsv() {
    println("== CSV ==")
    check("plain not quoted", VaultLogic.csvQuote("abc") == "abc")
    check("comma quoted", VaultLogic.csvQuote("a,b") == "\"a,b\"")
    check("quote doubled", VaultLogic.csvQuote("say \"hi\"") == "\"say \"\"hi\"\"\"")
    check("newline quoted", VaultLogic.csvQuote("a\nb") == "\"a\nb\"")
    val csv = VaultLogic.generateCsv(listOf(rec(name = "Doe, John")))
    val lines = csv.trim().split("\n")
    check("header row", lines[0] == "number,name,date_ms,date,type,type_label,duration_sec,subscription_id")
    check("comma name quoted in row", lines[1].contains("\"Doe, John\""))
    check("type label in row", lines[1].contains("Incoming"))
}

fun testJson() {
    println("== JSON ==")
    val tricky = rec(name = "Quote \" and \\ backslash\nnewline\ttab", sub = "s\"1")
    val other = rec(number = "999", name = "", date = 1700007200000L, type = VaultLogic.TYPE_OUTGOING, duration = 5)
    val json = VaultLogic.generateJson(listOf(tricky, other), 1700010000000L)
    check("json escapes quote", json.contains("\\\""))
    check("json escapes backslash", json.contains("\\\\"))
    check("json escapes newline", json.contains("\\n"))
    val parsed = VaultLogic.parseCallsJson(json)
    check("json round trip count", parsed.size == 2)
    check("json round trip name", parsed[0].name == "Quote \" and \\ backslash\nnewline\ttab")
    check("json round trip sub", parsed[0].subscriptionId == "s\"1")
    check("json round trip fields", parsed[1].numberRaw == "999" && parsed[1].type == VaultLogic.TYPE_OUTGOING && parsed[1].durationSec == 5L)
    check("json round trip norm kept", parsed[0].numberNorm == VaultLogic.normalizeNumber("+919000000010"))
    check("bad json parses empty", VaultLogic.parseCallsJson("not json").isEmpty())
    check("empty object parses empty", VaultLogic.parseCallsJson("{}").isEmpty())
}

fun testDates() {
    println("== date headers ==")
    val now = System.currentTimeMillis()
    check("today label", VaultLogic.dateHeaderLabel(now, now) == "Today")
    check("yesterday label", VaultLogic.dateHeaderLabel(now - 24L * 3600 * 1000, now) == "Yesterday")
    val old = VaultLogic.dateHeaderLabel(now - 10L * 24 * 3600 * 1000, now)
    check("older label is a date", old != "Today" && old != "Yesterday" && old.contains(","))
    check(
        "same day earlier is today",
        VaultLogic.dateHeaderLabel(now - 60_000, now) == "Today"
    )
}

fun testFilters() {
    println("== filters and search ==")
    val incoming = rec(type = VaultLogic.TYPE_INCOMING, name = "Asha")
    val missedUnknown = rec(number = "5550001111", name = "", type = VaultLogic.TYPE_MISSED)
    val blocked = rec(number = "5552223333", name = "Spam Co", type = VaultLogic.TYPE_BLOCKED)
    val all = listOf(incoming, missedUnknown, blocked)
    check("ALL matches all", VaultLogic.filterCalls(all, VaultLogic.Filter.ALL, "").size == 3)
    check("INCOMING filter", VaultLogic.filterCalls(all, VaultLogic.Filter.INCOMING, "") == listOf(incoming))
    check("MISSED filter", VaultLogic.filterCalls(all, VaultLogic.Filter.MISSED, "") == listOf(missedUnknown))
    check("BLOCKED filter", VaultLogic.filterCalls(all, VaultLogic.Filter.BLOCKED, "") == listOf(blocked))
    check("UNKNOWN filter catches empty name", VaultLogic.filterCalls(all, VaultLogic.Filter.UNKNOWN, "") == listOf(missedUnknown))
    check("OUTGOING filter empty here", VaultLogic.filterCalls(all, VaultLogic.Filter.OUTGOING, "").isEmpty())
    check("search by name case insensitive", VaultLogic.filterCalls(all, VaultLogic.Filter.ALL, "asha") == listOf(incoming))
    check("search by partial digits", VaultLogic.filterCalls(all, VaultLogic.Filter.ALL, "000111") == listOf(missedUnknown))
    check("search by formatted digits", VaultLogic.filterCalls(all, VaultLogic.Filter.ALL, "0000 01") == listOf(incoming))
    check("search no match", VaultLogic.filterCalls(all, VaultLogic.Filter.ALL, "zzzz").isEmpty())
    check("unknown name helpers", VaultLogic.isUnknownName("") && VaultLogic.isUnknownName("(Unknown)") && !VaultLogic.isUnknownName("Asha"))
    check("effective name falls back to number", VaultLogic.effectiveName(missedUnknown) == "5550001111")
    check("type labels", VaultLogic.typeLabel(1) == "Incoming" && VaultLogic.typeLabel(3) == "Missed" && VaultLogic.typeLabel(6) == "Blocked" && VaultLogic.typeLabel(99) == "Other")
}

fun testGrouping() {
    println("== grouping ==")
    val a1 = rec(date = 1000, duration = 60)
    val a2 = rec(date = 3000, duration = 30)
    val b1 = rec(number = "111", name = "Bala", date = 2000, duration = 10)
    val unnamed = rec(number = "222", name = "", date = 4000, duration = 0)
    val people = VaultLogic.groupPeople(listOf(a1, b1, a2, unnamed))
    check("three people", people.size == 3)
    check("sorted by last call desc", people[0].numberNorm == "222" && people[1].numberNorm == a1.numberNorm)
    val asha = people.first { it.numberNorm == a1.numberNorm }
    check("counts merged", asha.callCount == 2)
    check("durations summed", asha.totalDurationSec == 90L)
    check("display name from named call", asha.displayName == "Asha")
    check("unnamed person shows number", people[0].displayName == "222")
    check("empty list groups empty", VaultLogic.groupPeople(emptyList()).isEmpty())
}

fun testInitials() {
    println("== initials ==")
    check("two words", VaultLogic.initialsFor("Asha Rani", "+9198765") == "AR")
    check("one word", VaultLogic.initialsFor("Asha", "+9198765") == "A")
    check("three words capped at two", VaultLogic.initialsFor("Asha Rani Kapoor", "1") == "AR")
    check("lowercase uppercased", VaultLogic.initialsFor("asha rani", "1") == "AR")
    check("extra spaces handled", VaultLogic.initialsFor("  Asha   Rani  ", "1") == "AR")
    check("empty name has no initials", VaultLogic.initialsFor("", "+9198765") == "")
    check("unknown marker has no initials", VaultLogic.initialsFor("(Unknown)", "123") == "")
    check("number as name has no initials", VaultLogic.initialsFor("+91 90000 00010", "+91 90000 00010") == "")
    check("digits only name has no initials", VaultLogic.initialsFor("98765", "999") == "")
    check("name equal to raw number has no initials", VaultLogic.initialsFor("5551234", "5551234") == "")
}

fun testAvatarColors() {
    println("== avatar colors ==")
    check("palette has six colors", VaultLogic.AVATAR_COLORS.size == 6)
    val keys = listOf("+919000000010", "5550001111", "+442079460958", "12345", "999", "+15550100")
    for (k in keys) {
        val idx = VaultLogic.avatarColorIndex(k)
        check("index in range for $k", idx in 0 until VaultLogic.AVATAR_COLORS.size)
        check("index deterministic for $k", VaultLogic.avatarColorIndex(k) == idx)
        check("color matches palette for $k", VaultLogic.avatarColor(k) == VaultLogic.AVATAR_COLORS[idx])
    }
    val distinct = keys.map { VaultLogic.avatarColorIndex(it) }.toSet()
    check("different keys spread over palette", distinct.size >= 3)
    check("empty key still in range", VaultLogic.avatarColorIndex("") in 0 until VaultLogic.AVATAR_COLORS.size)
}

fun testAutoBackupThrottle() {
    println("== auto backup throttle ==")
    val now = 1700100000000L
    val min = VaultLogic.AUTO_BACKUP_MIN_INTERVAL_MS
    check("nothing new never writes", !VaultLogic.shouldWriteAutoBackup(now - 60 * min, 0, now))
    check("nothing new and never written does not write", !VaultLogic.shouldWriteAutoBackup(0, 0, now))
    check("first write happens with one new call", VaultLogic.shouldWriteAutoBackup(0, 1, now))
    check("recent write with few new calls waits", !VaultLogic.shouldWriteAutoBackup(now - min / 2, 3, now))
    check("write after interval with one new call", VaultLogic.shouldWriteAutoBackup(now - min - 1, 1, now))
    check("burst of 20 writes right away", VaultLogic.shouldWriteAutoBackup(now - 60_000, 20, now))
    check("19 new calls inside interval still waits", !VaultLogic.shouldWriteAutoBackup(now - 60_000, 19, now))
    check("auto backup filename stable", VaultLogic.AUTO_BACKUP_FILENAME == "call-log-vault-auto.zip")
}

fun testAutoBackupWriteDecision() {
    println("== auto backup scheduling gate ==")
    check("no schedule when a sync added nothing", !VaultLogic.shouldScheduleAutoBackup(0))
    check("schedule when a sync added one call", VaultLogic.shouldScheduleAutoBackup(1))
    check("schedule when a sync added many calls", VaultLogic.shouldScheduleAutoBackup(250))
}

fun testFolderNames() {
    println("== folder names ==")
    check("tree id tail", VaultLogic.folderNameFromTreeId("primary:Download/Backups") == "Backups")
    check("tree id volume root", VaultLogic.folderNameFromTreeId("primary:") == "Selected folder")
    check("plain name kept", VaultLogic.folderNameFromTreeId("Backups") == "Backups")
}

fun testAppearanceResolution() {
    println("== appearance resolution ==")
    val light = VaultLogic.APPEARANCE_LIGHT
    val dark = VaultLogic.APPEARANCE_DARK
    val amoled = VaultLogic.APPEARANCE_AMOLED
    check("unset resolves to AMOLED", VaultLogic.resolveAppearance(false, 0, false, 0) == amoled)
    check("legacy System resolves to AMOLED",
        VaultLogic.resolveAppearance(false, 0, true, VaultLogic.LEGACY_APPEARANCE_SYSTEM) == amoled)
    check("legacy Light carries over",
        VaultLogic.resolveAppearance(false, 0, true, VaultLogic.LEGACY_APPEARANCE_LIGHT) == light)
    check("legacy Dark carries over",
        VaultLogic.resolveAppearance(false, 0, true, VaultLogic.LEGACY_APPEARANCE_DARK) == dark)
    check("unknown legacy value resolves to AMOLED",
        VaultLogic.resolveAppearance(false, 0, true, 99) == amoled)
    check("v2 Light round trips", VaultLogic.resolveAppearance(true, light, false, 0) == light)
    check("v2 Dark round trips", VaultLogic.resolveAppearance(true, dark, false, 0) == dark)
    check("v2 AMOLED round trips", VaultLogic.resolveAppearance(true, amoled, false, 0) == amoled)
    check("v2 wins over legacy", VaultLogic.resolveAppearance(true, dark, true, VaultLogic.LEGACY_APPEARANCE_LIGHT) == dark)
    check("unknown v2 value falls back to AMOLED", VaultLogic.resolveAppearance(true, 42, false, 0) == amoled)
    check("preset names", VaultLogic.appearanceName(light) == "Light" &&
        VaultLogic.appearanceName(dark) == "Dark" &&
        VaultLogic.appearanceName(amoled) == "AMOLED")
}

fun testAppearanceAuto() {
    println("== appearance Auto resolution (v1.0.8) ==")
    val light = VaultLogic.APPEARANCE_LIGHT
    val dark = VaultLogic.APPEARANCE_DARK
    val amoled = VaultLogic.APPEARANCE_AMOLED
    val auto = VaultLogic.APPEARANCE_AUTO
    // Stored v2 value: Auto round trips like the concrete presets.
    check("v2 Auto round trips", VaultLogic.resolveAppearance(true, auto, false, 0) == auto)
    check("Auto has a name", VaultLogic.appearanceName(auto) == "Auto")
    // Explicit modes resolve to themselves, day and night alike.
    check("explicit Light in day", VaultLogic.resolveAppearance(light, amoled, false) == light)
    check("explicit Light in night", VaultLogic.resolveAppearance(light, amoled, true) == light)
    check("explicit Dark in day", VaultLogic.resolveAppearance(dark, amoled, false) == dark)
    check("explicit Dark in night", VaultLogic.resolveAppearance(dark, light, true) == dark)
    check("explicit AMOLED in day", VaultLogic.resolveAppearance(amoled, dark, false) == amoled)
    check("explicit AMOLED in night", VaultLogic.resolveAppearance(amoled, dark, true) == amoled)
    // Auto follows the system, with the stored flavour at night.
    check("Auto in day is Light (AMOLED flavour)",
        VaultLogic.resolveAppearance(auto, amoled, false) == light)
    check("Auto in day is Light (Dark flavour)",
        VaultLogic.resolveAppearance(auto, dark, false) == light)
    check("Auto at night with AMOLED flavour",
        VaultLogic.resolveAppearance(auto, amoled, true) == amoled)
    check("Auto at night with Dark flavour",
        VaultLogic.resolveAppearance(auto, dark, true) == dark)
    // A flavour that is not a dark preset counts as AMOLED.
    check("Auto at night with Light flavour falls back to AMOLED",
        VaultLogic.resolveAppearance(auto, light, true) == amoled)
    check("Auto at night with Auto flavour falls back to AMOLED",
        VaultLogic.resolveAppearance(auto, auto, true) == amoled)
    check("Auto at night with garbage flavour falls back to AMOLED",
        VaultLogic.resolveAppearance(auto, 99, true) == amoled)
    // Unknown modes keep the standing AMOLED fallback.
    check("unknown mode falls back to AMOLED",
        VaultLogic.resolveAppearance(42, dark, false) == amoled)
}

fun testBackupSchedules() {
    println("== backup schedules (v1.0.8) ==")
    val now = 1700100000000L
    val smart = VaultLogic.BACKUP_SCHEDULE_SMART
    val everyCall = VaultLogic.BACKUP_SCHEDULE_EVERY_CALL
    val every6h = VaultLogic.BACKUP_SCHEDULE_EVERY_6H
    val daily = VaultLogic.BACKUP_SCHEDULE_DAILY
    val min = VaultLogic.AUTO_BACKUP_MIN_INTERVAL_MS
    val h6 = VaultLogic.BACKUP_6H_INTERVAL_MS
    val day = VaultLogic.BACKUP_DAY_INTERVAL_MS
    check("schedule names",
        VaultLogic.backupScheduleName(smart) == "Smart" &&
            VaultLogic.backupScheduleName(everyCall) == "After every call" &&
            VaultLogic.backupScheduleName(every6h) == "Every 6 hours" &&
            VaultLogic.backupScheduleName(daily) == "Daily" &&
            VaultLogic.backupScheduleName(99) == "Smart")
    // Smart is exactly the old throttle.
    check("Smart: nothing new never writes", !VaultLogic.shouldWriteBackup(smart, now - 60 * min, 0, now))
    check("Smart: first write with one new call", VaultLogic.shouldWriteBackup(smart, 0, 1, now))
    check("Smart: recent write with few new calls waits", !VaultLogic.shouldWriteBackup(smart, now - min / 2, 3, now))
    check("Smart: write after 15 minutes", VaultLogic.shouldWriteBackup(smart, now - min - 1, 1, now))
    check("Smart: burst of 20 writes right away", VaultLogic.shouldWriteBackup(smart, now - 60_000, 20, now))
    // After every call: any new call writes, interval does not matter.
    check("Every call: one new call a minute after a write",
        VaultLogic.shouldWriteBackup(everyCall, now - 60_000, 1, now))
    check("Every call: several new calls write", VaultLogic.shouldWriteBackup(everyCall, now - 1000, 5, now))
    check("Every call: first write", VaultLogic.shouldWriteBackup(everyCall, 0, 1, now))
    check("Every call: nothing new does not write", !VaultLogic.shouldWriteBackup(everyCall, now - 60_000, 0, now))
    check("Every call: nothing new and never written does not write", !VaultLogic.shouldWriteBackup(everyCall, 0, 0, now))
    // Every 6 hours: interval gate, exact boundary included.
    check("6h: first write with one new call", VaultLogic.shouldWriteBackup(every6h, 0, 1, now))
    check("6h: inside the interval waits", !VaultLogic.shouldWriteBackup(every6h, now - h6 / 2, 1, now))
    check("6h: one ms before the interval still waits", !VaultLogic.shouldWriteBackup(every6h, now - h6 + 1, 3, now))
    check("6h: exactly at the interval writes", VaultLogic.shouldWriteBackup(every6h, now - h6, 1, now))
    check("6h: past the interval writes", VaultLogic.shouldWriteBackup(every6h, now - h6 - 1, 1, now))
    check("6h: nothing new never writes even past the interval", !VaultLogic.shouldWriteBackup(every6h, now - 2 * h6, 0, now))
    // Daily: same shape at 24 hours.
    check("Daily: first write with one new call", VaultLogic.shouldWriteBackup(daily, 0, 1, now))
    check("Daily: at 6 hours still waits", !VaultLogic.shouldWriteBackup(daily, now - h6, 1, now))
    check("Daily: one ms before 24h still waits", !VaultLogic.shouldWriteBackup(daily, now - day + 1, 2, now))
    check("Daily: exactly at 24h writes", VaultLogic.shouldWriteBackup(daily, now - day, 1, now))
    check("Daily: past 24h writes", VaultLogic.shouldWriteBackup(daily, now - day - 1, 1, now))
    check("Daily: nothing new never writes", !VaultLogic.shouldWriteBackup(daily, now - 2 * day, 0, now))
    // Unknown schedule values behave as Smart.
    check("unknown schedule waits like Smart", !VaultLogic.shouldWriteBackup(99, now - min / 2, 3, now))
    check("unknown schedule bursts like Smart", VaultLogic.shouldWriteBackup(99, now - 60_000, 20, now))
}

fun testFormatterConsistency() {
    println("== formatter consistency (cached formatters, UTC pinned in main) ==")
    val t1 = 1700000000000L // 14 Nov 2023, 22:13:20 UTC
    val t3 = 1685577600000L // 01 Jun 2023, 00:00:00 UTC
    check("formatTime evening", VaultLogic.formatTime(t1) == "10:13 PM")
    check("formatTime midnight", VaultLogic.formatTime(0L) == "12:00 AM")
    check("formatTime start of day", VaultLogic.formatTime(t3) == "12:00 AM")
    check("formatDateTime evening", VaultLogic.formatDateTime(t1) == "14 Nov 2023, 10:13 PM")
    check("formatDateTime epoch", VaultLogic.formatDateTime(0L) == "01 Jan 1970, 12:00 AM")
    check("formatDateTime june", VaultLogic.formatDateTime(t3) == "01 Jun 2023, 12:00 AM")
    check("readableDate evening", VaultLogic.readableDate(t1) == "14.11.2023 22:13:20")
    check("readableDate epoch", VaultLogic.readableDate(0L) == "01.01.1970 00:00:00")
    check("header older date", VaultLogic.dateHeaderLabel(t1 - 10L * 24 * 3600 * 1000, t1) == "Sat, 04 Nov 2023")
    check("cached formatter stable on repeat", VaultLogic.formatDateTime(t1) == VaultLogic.formatDateTime(t1))
}

fun testRowDisplayStrings() {
    println("== row display strings (v1.0.3 precomputed row content) ==")
    val t1 = 1700000000000L // 14 Nov 2023, 22:13:20 UTC
    val dayMs = 24L * 3600 * 1000
    check("call subtitle with name",
        VaultLogic.callSubtitle("Asha", "+919000000010", VaultLogic.TYPE_INCOMING) == "+919000000010  ·  Incoming")
    check("call subtitle unknown name is type only",
        VaultLogic.callSubtitle("", "+919000000010", VaultLogic.TYPE_MISSED) == "Missed")
    check("call subtitle (Unknown) name is type only",
        VaultLogic.callSubtitle("(Unknown)", "12345", VaultLogic.TYPE_OUTGOING) == "Outgoing")
    check("call right bottom with duration",
        VaultLogic.callRightBottom(65) == "1m 05s")
    check("call right bottom zero is empty", VaultLogic.callRightBottom(0) == "")
    check("person subtitle",
        VaultLogic.personSubtitle("+919000000010", 3, 125) == "+919000000010  ·  3 calls  ·  2m 05s total")
    check("person right top today",
        VaultLogic.personRightTop(t1, t1 + 3600_000) == "Today")
    check("person right top yesterday",
        VaultLogic.personRightTop(t1, t1 + dayMs) == "Yesterday")
    check("person right top older is short date",
        VaultLogic.personRightTop(t1, t1 + 10 * dayMs) == "14 Nov 2023")
    check("timeline subtitle",
        VaultLogic.timelineSubtitle(t1, t1) == "Today  ·  10:13 PM  ·  14 Nov 2023")
}

fun testEllipsize() {
    println("== ellipsizeToWidth (10px per char measure) ==")
    val m: (String) -> Float = { it.length * 10f }
    check("fits unchanged", VaultLogic.ellipsizeToWidth("Asha", 100f, m) == "Asha")
    check("exact fit unchanged", VaultLogic.ellipsizeToWidth("Asha", 40f, m) == "Asha")
    check("empty text", VaultLogic.ellipsizeToWidth("", 100f, m) == "")
    check("truncates with single ellipsis",
        VaultLogic.ellipsizeToWidth("AlexanderTheGreat", 100f, m) == "Alexander…")
    check("truncated result fits", m(VaultLogic.ellipsizeToWidth("AlexanderTheGreat", 95f, m)) <= 95f)
    check("ellipsis alone too wide", VaultLogic.ellipsizeToWidth("Hello", 5f, m) == "")
    check("zero width", VaultLogic.ellipsizeToWidth("Hello", 0f, m) == "")
    check("one char plus ellipsis", VaultLogic.ellipsizeToWidth("Hello", 20f, m) == "H…")
}

fun testShouldApplyRebuild() {
    println("== shouldApplyRebuild (v1.0.6 no-change skip) ==")
    check(
        "nothing on screen yet always rebuilds",
        VaultLogic.shouldApplyRebuild(snapshotCount = -1L, newCount = 0L, added = 0, hasRecords = false)
    )
    check(
        "first population rebuilds even at equal counts",
        VaultLogic.shouldApplyRebuild(snapshotCount = 0L, newCount = 0L, added = 0, hasRecords = false)
    )
    check(
        "no change skips the rebuild",
        !VaultLogic.shouldApplyRebuild(snapshotCount = 2083L, newCount = 2083L, added = 0, hasRecords = true)
    )
    check(
        "added calls rebuild",
        VaultLogic.shouldApplyRebuild(snapshotCount = 2083L, newCount = 2084L, added = 1, hasRecords = true)
    )
    check(
        "added rebuilds even if the count somehow matches",
        VaultLogic.shouldApplyRebuild(snapshotCount = 2083L, newCount = 2083L, added = 2, hasRecords = true)
    )
    check(
        "count grew without added (import or restore) rebuilds",
        VaultLogic.shouldApplyRebuild(snapshotCount = 2000L, newCount = 2083L, added = 0, hasRecords = true)
    )
    check(
        "count shrank rebuilds",
        VaultLogic.shouldApplyRebuild(snapshotCount = 2083L, newCount = 2000L, added = 0, hasRecords = true)
    )
    check(
        "no snapshot yet (count -1) rebuilds",
        VaultLogic.shouldApplyRebuild(snapshotCount = -1L, newCount = 2083L, added = 0, hasRecords = true)
    )
}

fun testDurationFormat() {
    println("== duration format ==")
    check("zero", VaultLogic.formatDuration(0) == "0s")
    check("seconds", VaultLogic.formatDuration(45) == "45s")
    check("minutes", VaultLogic.formatDuration(65) == "1m 05s")
    check("hours", VaultLogic.formatDuration(3661) == "1h 01m")
    check("negative clamps", VaultLogic.formatDuration(-5) == "0s")
}
