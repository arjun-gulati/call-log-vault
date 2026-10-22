import com.calllogvault.PullRefreshMachine
import com.calllogvault.ScrollToTopMachine
import com.calllogvault.VaultLogic
import com.calllogvault.pullStateLabel
import com.calllogvault.scrollToTopDurationMs
import com.calllogvault.scrollToTopLabel
import com.calllogvault.shouldReportSyncResult
import com.calllogvault.syncDoneMessage

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
    testParseCsv()
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
    testAppearanceMigration()
    testAppearanceResolution()
    testBackupSchedules()
    testWatchFolder()
    testTombstones()
    testProviderDeleteSpec()
    testDeleteOutcomeText()
    testFormatterConsistency()
    testRowDisplayStrings()
    testEllipsize()
    testShouldApplyRebuild()
    testPullRefresh()
    testPullFeedback()
    testPageTitlesAndScrollStates()
    testScrollToTop()
    testBin()
    testPause()
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

fun testParseCsv() {
    println("== CSV parse (watch folder fallback entry) ==")
    val tricky = rec(name = "Doe, John \"JD\"", number = "+1 555 0100", sub = "sim,1")
    val plain = rec(name = "", number = "12345", date = 1700003600000L, type = VaultLogic.TYPE_MISSED, duration = 0)
    val csv = VaultLogic.generateCsv(listOf(tricky, plain))
    val parsed = VaultLogic.parseCallsCsv(csv)
    check("csv round trip count", parsed.size == 2)
    check("csv round trip quoted name", parsed[0].name == "Doe, John \"JD\"")
    check("csv round trip number", parsed[0].numberRaw == "+1 555 0100")
    check("csv round trip normalized", parsed[0].numberNorm == "+15550100")
    check("csv round trip sub with comma", parsed[0].subscriptionId == "sim,1")
    check("csv round trip duration and type", parsed[0].durationSec == 65L && parsed[0].type == VaultLogic.TYPE_INCOMING)
    check("csv round trip unknown name", parsed[1].name == "" && parsed[1].type == VaultLogic.TYPE_MISSED)
    // The date column carries a comma inside quotes ("14 Nov 2023, 10:13 PM"):
    // field alignment must survive it, which the round trip above proves.
    val rows = VaultLogic.parseCsvRows(csv)
    check("header row detected", VaultLogic.isCsvHeaderRow(rows[0]))
    check("data row is not a header", !VaultLogic.isCsvHeaderRow(rows[1]))
    check("quoted comma stays one field", rows[1].size == 8 && rows[1][3].contains(","))
    check("crlf rows split", VaultLogic.parseCsvRows("a,b\r\nc,d\r\n").size == 2)
    check("no trailing newline still yields the row", VaultLogic.parseCsvRows("a,b").size == 1)
    check("empty csv parses empty", VaultLogic.parseCallsCsv("").isEmpty())
    check("header only csv parses empty",
        VaultLogic.parseCallsCsv("number,name,date_ms,date,type,type_label,duration_sec,subscription_id\n").isEmpty())
    check("garbage row skipped", VaultLogic.parseCallsCsv("x,y\n").isEmpty())
    check("bad date row skipped",
        VaultLogic.parseCallsCsv("number,name,date_ms,date,type,type_label,duration_sec,subscription_id\n123,A,notadate,x,1,Incoming,5,\n").isEmpty())
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

fun testAppearanceMigration() {
    println("== appearance migration to mode plus AMOLED switch ==")
    val light = VaultLogic.APPEARANCE_LIGHT
    val dark = VaultLogic.APPEARANCE_DARK
    val amoled = VaultLogic.APPEARANCE_AMOLED
    val auto = VaultLogic.APPEARANCE_AUTO
    fun mig(hasV2: Boolean, v2: Int, hasFlavour: Boolean, flavour: Int,
            hasLegacy: Boolean, legacy: Int) =
        VaultLogic.migrateAppearance(hasV2, v2, hasFlavour, flavour, hasLegacy, legacy)
    // Fresh install: nothing stored anywhere.
    check("fresh install is Dark mode with the switch on",
        mig(false, 0, false, 0, false, 0) == VaultLogic.AppearanceState(dark, true))
    // Legacy v1.0.1 values, no v2 pref.
    check("legacy System lands on Dark mode with the switch on",
        mig(false, 0, false, 0, true, VaultLogic.LEGACY_APPEARANCE_SYSTEM) ==
            VaultLogic.AppearanceState(dark, true))
    check("legacy Light carries over as Light",
        mig(false, 0, false, 0, true, VaultLogic.LEGACY_APPEARANCE_LIGHT).mode == light)
    check("legacy Dark carries over as Dark mode with the switch off",
        mig(false, 0, false, 0, true, VaultLogic.LEGACY_APPEARANCE_DARK) ==
            VaultLogic.AppearanceState(dark, false))
    check("unknown legacy value lands on Dark mode with the switch on",
        mig(false, 0, false, 0, true, 99) == VaultLogic.AppearanceState(dark, true))
    // Stored v2 presets from before the refinement.
    check("stored Light stays Light",
        mig(true, light, false, 0, false, 0).mode == light)
    check("stored Light with a Dark flavour starts with the switch off",
        mig(true, light, true, dark, false, 0) == VaultLogic.AppearanceState(light, false))
    check("stored Light with an AMOLED flavour starts with the switch on",
        mig(true, light, true, amoled, false, 0) == VaultLogic.AppearanceState(light, true))
    check("stored Dark becomes Dark mode with the switch off",
        mig(true, dark, false, 0, false, 0) == VaultLogic.AppearanceState(dark, false))
    check("stored Dark stays off even with an AMOLED flavour stored",
        mig(true, dark, true, amoled, false, 0) == VaultLogic.AppearanceState(dark, false))
    check("stored AMOLED becomes Dark mode with the switch on",
        mig(true, amoled, false, 0, false, 0) == VaultLogic.AppearanceState(dark, true))
    check("stored AMOLED stays on even with a Dark flavour stored",
        mig(true, amoled, true, dark, false, 0) == VaultLogic.AppearanceState(dark, true))
    // Stored Auto: the switch takes the remembered flavour.
    check("stored Auto with an AMOLED flavour starts with the switch on",
        mig(true, auto, true, amoled, false, 0) == VaultLogic.AppearanceState(auto, true))
    check("stored Auto with a Dark flavour starts with the switch off",
        mig(true, auto, true, dark, false, 0) == VaultLogic.AppearanceState(auto, false))
    check("stored Auto with no flavour starts with the switch on",
        mig(true, auto, false, 0, false, 0) == VaultLogic.AppearanceState(auto, true))
    check("stored Auto with a garbage flavour starts with the switch on",
        mig(true, auto, true, 99, false, 0) == VaultLogic.AppearanceState(auto, true))
    // Priority and fallback.
    check("v2 wins over legacy",
        mig(true, dark, false, 0, true, VaultLogic.LEGACY_APPEARANCE_LIGHT) ==
            VaultLogic.AppearanceState(dark, false))
    check("unknown v2 value keeps the AMOLED look",
        mig(true, 42, false, 0, false, 0) == VaultLogic.AppearanceState(dark, true))
    check("preset and mode names", VaultLogic.appearanceName(light) == "Light" &&
        VaultLogic.appearanceName(dark) == "Dark" &&
        VaultLogic.appearanceName(amoled) == "AMOLED" &&
        VaultLogic.appearanceName(auto) == "Auto")
}

fun testAppearanceResolution() {
    println("== appearance resolution: mode plus AMOLED switch ==")
    val light = VaultLogic.APPEARANCE_LIGHT
    val dark = VaultLogic.APPEARANCE_DARK
    val amoled = VaultLogic.APPEARANCE_AMOLED
    val auto = VaultLogic.APPEARANCE_AUTO
    // Light is always Light, switch and system alike.
    check("Light in day, switch off", VaultLogic.resolveAppearance(light, false, false) == light)
    check("Light in day, switch on", VaultLogic.resolveAppearance(light, true, false) == light)
    check("Light at night, switch off", VaultLogic.resolveAppearance(light, false, true) == light)
    check("Light at night, switch on", VaultLogic.resolveAppearance(light, true, true) == light)
    // Dark renders plain Dark or AMOLED purely by the switch.
    check("Dark with the switch off in day", VaultLogic.resolveAppearance(dark, false, false) == dark)
    check("Dark with the switch off at night", VaultLogic.resolveAppearance(dark, false, true) == dark)
    check("Dark with the switch on in day", VaultLogic.resolveAppearance(dark, true, false) == amoled)
    check("Dark with the switch on at night", VaultLogic.resolveAppearance(dark, true, true) == amoled)
    // Auto follows the system; only its night side reads the switch.
    check("Auto in day, switch off", VaultLogic.resolveAppearance(auto, false, false) == light)
    check("Auto in day, switch on", VaultLogic.resolveAppearance(auto, true, false) == light)
    check("Auto at night, switch off", VaultLogic.resolveAppearance(auto, false, true) == dark)
    check("Auto at night, switch on", VaultLogic.resolveAppearance(auto, true, true) == amoled)
    // Unknown modes keep the standing AMOLED fallback.
    check("unknown mode falls back to AMOLED",
        VaultLogic.resolveAppearance(42, false, false) == amoled)
    check("unknown mode falls back to AMOLED at night too",
        VaultLogic.resolveAppearance(42, true, true) == amoled)
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

fun testWatchFolder() {
    println("== watch folder (v1.1.0) ==")
    // Fingerprint: size prefix plus SHA-256 of the entry bytes.
    val emptyHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    check("fingerprint of empty entry is the known empty SHA-256",
        VaultLogic.zipFingerprint(0L, ByteArray(0)) == "0:$emptyHash")
    val bytesA = "calls-json-a".toByteArray(Charsets.UTF_8)
    val bytesB = "calls-json-b".toByteArray(Charsets.UTF_8)
    check("fingerprint stable for identical bytes",
        VaultLogic.zipFingerprint(1200L, bytesA) == VaultLogic.zipFingerprint(1200L, bytesA.copyOf()))
    check("fingerprint differs when bytes differ",
        VaultLogic.zipFingerprint(1200L, bytesA) != VaultLogic.zipFingerprint(1200L, bytesB))
    check("fingerprint differs when size differs",
        VaultLogic.zipFingerprint(1200L, bytesA) != VaultLogic.zipFingerprint(1201L, bytesA))
    check("fingerprint carries the size prefix",
        VaultLogic.zipFingerprint(42L, bytesA).startsWith("42:"))
    // Import decision matrix.
    val stored = VaultLogic.zipFingerprint(1200L, bytesA)
    val changed = VaultLogic.zipFingerprint(1300L, bytesB)
    check("watch off never imports even when changed",
        !VaultLogic.shouldImportWatched(stored, changed, watchEnabled = false))
    check("watch off with no stored fingerprint never imports",
        !VaultLogic.shouldImportWatched("", changed, watchEnabled = false))
    check("same fingerprint skips", !VaultLogic.shouldImportWatched(stored, stored, watchEnabled = true))
    check("changed fingerprint imports", VaultLogic.shouldImportWatched(stored, changed, watchEnabled = true))
    check("absent file skips quietly", !VaultLogic.shouldImportWatched(stored, null, watchEnabled = true))
    check("absent file and nothing stored skips", !VaultLogic.shouldImportWatched("", null, watchEnabled = true))
    check("first check with nothing stored imports",
        VaultLogic.shouldImportWatched("", changed, watchEnabled = true))
    // Name resolution: the later import wins, but never erases.
    check("incoming name wins", VaultLogic.mergedImportName("Asha", "Asha Rani") == "Asha Rani")
    check("incoming fills an empty saved name", VaultLogic.mergedImportName("", "Asha") == "Asha")
    check("empty incoming never erases", VaultLogic.mergedImportName("Asha", "") == "Asha")
    check("unknown marker incoming never erases", VaultLogic.mergedImportName("Asha", "(Unknown)") == "Asha")
    check("both names missing stays missing", VaultLogic.mergedImportName("", "") == "")
    check("same name resolves to itself", VaultLogic.mergedImportName("Asha", "Asha") == "Asha")
    // Merge planning: union, dedupe, renames.
    val mine = rec(name = "Asha", date = 1000)
    val mineKey = VaultLogic.dedupeKey(mine)
    val theirs = rec(number = "5550001111", name = "Bala", date = 2000, type = VaultLogic.TYPE_OUTGOING)
    val theirsKey = VaultLogic.dedupeKey(theirs)
    val emptyPlan = VaultLogic.planWatchMerge(emptyMap(), emptyList())
    check("empty merge plans nothing", emptyPlan.inserts.isEmpty() && emptyPlan.nameUpdates.isEmpty())
    val unionPlan = VaultLogic.planWatchMerge(mapOf(mineKey to "Asha"), listOf(mine, theirs))
    check("union: the new record inserts", unionPlan.inserts == listOf(theirs))
    check("union: the shared record is untouched", unionPlan.nameUpdates.isEmpty())
    val allNewPlan = VaultLogic.planWatchMerge(emptyMap(), listOf(mine, theirs))
    check("empty vault takes everything as inserts",
        allNewPlan.inserts == listOf(mine, theirs) && allNewPlan.nameUpdates.isEmpty())
    val renamed = rec(name = "Asha Rani", date = 1000)
    val renamePlan = VaultLogic.planWatchMerge(mapOf(mineKey to "Asha"), listOf(renamed))
    check("conflict: later import name wins as an update",
        renamePlan.inserts.isEmpty() && renamePlan.nameUpdates == listOf(renamed))
    val unnamedIncoming = rec(name = "", date = 1000)
    val keepPlan = VaultLogic.planWatchMerge(mapOf(mineKey to "Asha"), listOf(unnamedIncoming))
    check("conflict: an unnamed arrival changes nothing",
        keepPlan.inserts.isEmpty() && keepPlan.nameUpdates.isEmpty())
    val fillPlan = VaultLogic.planWatchMerge(mapOf(mineKey to ""), listOf(mine))
    check("conflict: a named arrival fills a missing name",
        fillPlan.nameUpdates == listOf(mine) && fillPlan.inserts.isEmpty())
    val dupFirst = rec(name = "First", date = 3000)
    val dupSecond = rec(name = "Second", date = 3000)
    val dupPlan = VaultLogic.planWatchMerge(emptyMap(), listOf(dupFirst, dupSecond))
    check("in-file duplicate inserts once", dupPlan.inserts == listOf(dupFirst))
    check("in-file duplicate resolves to the later name",
        dupPlan.nameUpdates == listOf(dupSecond))
    val source = mutableMapOf(mineKey to "Asha")
    VaultLogic.planWatchMerge(source, listOf(renamed, theirs))
    check("the caller's map is never mutated", source == mutableMapOf(mineKey to "Asha"))
    check("disjoint keys both known", theirsKey != mineKey)
    // Status text.
    check("status imported plural",
        VaultLogic.watchStatusText(VaultLogic.WATCH_RESULT_IMPORTED, 12) ==
            "Watch folder: 12 new calls imported")
    check("status imported singular",
        VaultLogic.watchStatusText(VaultLogic.WATCH_RESULT_IMPORTED, 1) ==
            "Watch folder: 1 new call imported")
    check("status up to date",
        VaultLogic.watchStatusText(VaultLogic.WATCH_RESULT_UP_TO_DATE, 0) == "Watch folder: up to date")
    check("status unreadable",
        VaultLogic.watchStatusText(VaultLogic.WATCH_RESULT_UNREADABLE, 0) ==
            "Watch folder: a changed backup could not be read")
}

fun testTombstones() {
    println("== tombstones (v1.2.0 hashed manual delete markers) ==")
    // The hash itself: a known SHA-256 test vector.
    val abcHash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    check("sha256 of abc matches the known vector", VaultLogic.sha256Hex("abc") == abcHash)
    check("tombstone hash is the key's sha256", VaultLogic.tombstoneHash("abc") == abcHash)
    val call = rec()
    val callKey = VaultLogic.dedupeKey(call)
    check("tombstone hash is 64 lowercase hex chars",
        VaultLogic.tombstoneHash(callKey).length == 64 &&
            VaultLogic.tombstoneHash(callKey).all { it in '0'..'9' || it in 'a'..'f' })
    check("tombstone hash is deterministic",
        VaultLogic.tombstoneHash(callKey) == VaultLogic.tombstoneHash(VaultLogic.dedupeKey(rec())))
    // The stored hash reveals nothing readable about the call.
    check("the hash does not contain the number",
        !VaultLogic.tombstoneHash(callKey).contains("919000000010"))
    // Same number, a genuinely new call: a different date, a different key.
    val laterCall = rec(date = call.dateMs + 86_400_000L)
    check("same number different date makes a different key",
        VaultLogic.dedupeKey(laterCall) != callKey)
    check("same number different date makes a different hash",
        VaultLogic.tombstoneHash(VaultLogic.dedupeKey(laterCall)) != VaultLogic.tombstoneHash(callKey))
    // Acceptance: the tombstone wins over every arrival path.
    val tombs = setOf(VaultLogic.tombstoneHash(callKey))
    check("a tombstoned key is tombstoned", VaultLogic.isTombstoned(callKey, tombs))
    check("another key is not tombstoned",
        !VaultLogic.isTombstoned(VaultLogic.dedupeKey(laterCall), tombs))
    check("no tombstones means nothing is tombstoned", !VaultLogic.isTombstoned(callKey, emptySet()))
    check("a brand new record is accepted",
        VaultLogic.shouldAcceptRecord(VaultLogic.dedupeKey(laterCall), emptySet(), tombs))
    check("a record already in the vault is refused",
        !VaultLogic.shouldAcceptRecord(callKey, setOf(callKey), emptySet()))
    check("a tombstoned record is refused though the vault no longer holds it",
        !VaultLogic.shouldAcceptRecord(callKey, emptySet(), tombs))
    check("a tombstone wins over an import carrying the same key",
        !VaultLogic.shouldAcceptRecord(callKey, setOf(VaultLogic.dedupeKey(laterCall)), tombs))
    check("the later call from the same number is still accepted",
        VaultLogic.shouldAcceptRecord(VaultLogic.dedupeKey(laterCall), setOf(callKey), tombs))
    // The bulk filter keeps order and drops both kinds of refusals.
    val other = rec(number = "5550001111", name = "Bala", date = 2000, type = VaultLogic.TYPE_OUTGOING)
    val accepted = VaultLogic.filterAccepted(
        listOf(other, call, laterCall), setOf(VaultLogic.dedupeKey(other)), tombs
    )
    check("filterAccepted drops the saved and the tombstoned, keeps the new",
        accepted == listOf(laterCall))
    check("filterAccepted with no tombstones keeps everything new",
        VaultLogic.filterAccepted(listOf(call, laterCall), emptySet(), emptySet()) ==
            listOf(call, laterCall))
    // The watch merge plans around tombstones too.
    val tombPlan = VaultLogic.planWatchMerge(emptyMap(), listOf(call, laterCall), tombs)
    check("watch merge: a tombstoned arrival is not inserted",
        tombPlan.inserts == listOf(laterCall) && tombPlan.nameUpdates.isEmpty())
    val renamedCall = rec(name = "Asha Rani")
    val tombRenamePlan = VaultLogic.planWatchMerge(
        mapOf(callKey to "Asha"), listOf(renamedCall), tombs
    )
    check("watch merge: a tombstoned arrival is not renamed in either",
        tombRenamePlan.inserts.isEmpty() && tombRenamePlan.nameUpdates.isEmpty())
    val livePlan = VaultLogic.planWatchMerge(mapOf(callKey to "Asha"), listOf(renamedCall))
    check("watch merge without the tombstone still renames as before",
        livePlan.nameUpdates == listOf(renamedCall))
}

fun testProviderDeleteSpec() {
    println("== provider delete spec (v1.2.0 vault and phone log flavor) ==")
    val formatted = rec(number = "+91 90000 00010")
    val spec = VaultLogic.providerDeleteSpec(formatted)
    check("selection matches number, date and type",
        spec.selection == "number = ? AND date = ? AND type = ?")
    check("args are the normalized number, date and type",
        spec.args == listOf("+919000000010", "1700000000000", "1"))
    val local = rec(number = "0900000010", date = 1700003600000L, type = VaultLogic.TYPE_MISSED)
    val localSpec = VaultLogic.providerDeleteSpec(local)
    check("args follow the record",
        localSpec.args == listOf("0900000010", "1700003600000", "3"))
    check("selection has one placeholder per arg",
        spec.selection.count { it == '?' } == spec.args.size)
}

fun testDeleteOutcomeText() {
    println("== delete outcome text (v1.2.0) ==")
    check("vault only, one entry",
        VaultLogic.deleteOutcomeText(1, null) == "Removed 1 entry from the vault.")
    check("vault only, several entries",
        VaultLogic.deleteOutcomeText(4, null) == "Removed 4 entries from the vault.")
    check("both flavors, both removed",
        VaultLogic.deleteOutcomeText(1, 1) ==
            "Removed 1 entry from the vault. Removed 1 entry from the phone log.")
    check("both flavors, phone already missing the calls",
        VaultLogic.deleteOutcomeText(3, 0) ==
            "Removed 3 entries from the vault. No matching entries were in the phone log.")
    check("both flavors, several phone rows",
        VaultLogic.deleteOutcomeText(2, 2) ==
            "Removed 2 entries from the vault. Removed 2 entries from the phone log.")
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
    check("timeline subtitle yesterday keeps the date",
        VaultLogic.timelineSubtitle(t1, t1 + dayMs) == "Yesterday  ·  10:13 PM  ·  14 Nov 2023")
    check("timeline subtitle older has no repeated date",
        VaultLogic.timelineSubtitle(t1, t1 + 10 * dayMs) == "Tue, 14 Nov 2023  ·  10:13 PM")
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

fun near(actual: Float, expected: Float): Boolean = kotlin.math.abs(actual - expected) < 0.001f

fun testPullRefresh() {
    val idle = PullRefreshMachine.State.IDLE
    val pulling = PullRefreshMachine.State.PULLING
    val armed = PullRefreshMachine.State.ARMED
    val refreshing = PullRefreshMachine.State.REFRESHING

    // A fresh machine is at rest, and stray events change nothing.
    val m = PullRefreshMachine(100f)
    check("pull: fresh machine is idle", m.state == idle && near(m.progress, 0f))
    check("pull: move before begin is ignored", m.updatePull(150f) == idle && near(m.progress, 0f))
    check("pull: release before begin does not trigger", !m.release() && m.state == idle)
    check("pull: cancel before begin is a no-op", !m.cancel() && m.state == idle)
    check("pull: settle before begin is a no-op", !m.syncSettled() && m.state == idle)

    // Arming: progress tracks travel, the threshold arms exactly.
    check("pull: begin starts the pull", m.beginPull() && m.state == pulling)
    check("pull: a second begin is refused", !m.beginPull() && m.state == pulling)
    check("pull: 40 of 100 is pulling at 0.4", m.updatePull(40f) == pulling && near(m.progress, 0.4f))
    check("pull: just under the threshold stays unarmed", m.updatePull(99f) == pulling && !m.isArmed)
    check("pull: at the threshold the pull arms", m.updatePull(100f) == armed && m.isArmed)
    check("pull: past the threshold progress clamps at 1", m.updatePull(260f) == armed && near(m.progress, 1f))
    check("pull: slipping back below disarms", m.updatePull(50f) == pulling && near(m.progress, 0.5f))
    check("pull: travel above the start reads as zero", m.updatePull(-30f) == pulling && near(m.progress, 0f))

    // Release without arming settles quietly, no trigger.
    check("pull: unarmed release does not trigger", !m.release() && m.state == idle && near(m.progress, 0f))

    // Cancel paths from both pulling states.
    check("pull: begin again after settle", m.beginPull())
    check("pull: cancel from pulling settles", m.cancel() && m.state == idle)
    check("pull: begin for the armed cancel", m.beginPull())
    m.updatePull(140f)
    check("pull: cancel from armed settles without triggering",
        m.state == armed && m.cancel() && m.state == idle && !m.isRefreshing)

    // The armed release triggers exactly once.
    check("pull: begin for the trigger", m.beginPull())
    m.updatePull(100f)
    check("pull: armed release triggers", m.release() && m.state == refreshing && m.isRefreshing)
    check("pull: refreshing holds progress at full", near(m.progress, 1f))
    check("pull: a second release cannot double trigger", !m.release() && m.state == refreshing)
    check("pull: a new pull cannot begin while refreshing", !m.beginPull() && m.state == refreshing)
    check("pull: finger moves are ignored while refreshing", m.updatePull(200f) == refreshing)
    check("pull: a gesture cancel cannot end a refresh", !m.cancel() && m.state == refreshing)

    // Settle returns to rest and the whole cycle can repeat.
    check("pull: sync settle ends the refresh", m.syncSettled() && m.state == idle && near(m.progress, 0f))
    check("pull: a second settle is a no-op", !m.syncSettled() && m.state == idle)
    check("pull: a fresh pull works after settle", m.beginPull() && m.updatePull(120f) == armed)
    check("pull: the second cycle triggers too", m.release() && m.state == refreshing)
    check("pull: the second cycle settles too", m.syncSettled() && m.state == idle)

    // The production threshold (72dp in px at any density) behaves
    // the same: arm exactly at it, not a hair before.
    val prod = PullRefreshMachine(216f)
    prod.beginPull()
    check("pull: 215 of 216 is not armed", prod.updatePull(215f) == pulling)
    check("pull: 216 of 216 arms", prod.updatePull(216f) == armed)
    check("pull: half travel is half progress", prod.updatePull(108f) == pulling && near(prod.progress, 0.5f))
}

fun testPullFeedback() {
    val idle = PullRefreshMachine.State.IDLE
    val pulling = PullRefreshMachine.State.PULLING
    val armed = PullRefreshMachine.State.ARMED
    val refreshing = PullRefreshMachine.State.REFRESHING

    // The label mapping: one line per state, nothing at rest (v1.2.4).
    check("label: idle has no label", pullStateLabel(idle) == null)
    check("label: pulling invites", pullStateLabel(pulling) == "Pull to sync")
    check("label: armed says release", pullStateLabel(armed) == "Release to sync")
    check("label: refreshing says syncing", pullStateLabel(refreshing) == "Syncing…")

    // The label tracks the machine live, including the disarm flip:
    // the words change the moment the finger crosses the threshold
    // in either direction, and settle back to nothing at rest.
    val m = PullRefreshMachine(100f)
    check("label: rest before the pull", pullStateLabel(m.state) == null)
    m.beginPull()
    check("label: a fresh pull invites", pullStateLabel(m.state) == "Pull to sync")
    m.updatePull(99f)
    check("label: just under the line still invites", pullStateLabel(m.state) == "Pull to sync")
    m.updatePull(100f)
    check("label: at the line it flips to release", pullStateLabel(m.state) == "Release to sync")
    m.updatePull(40f)
    check("label: slipping back flips it back", pullStateLabel(m.state) == "Pull to sync")
    m.updatePull(160f)
    check("label: release starts the sync words", m.release() && pullStateLabel(m.state) == "Syncing…")
    check("label: settle clears the words", m.syncSettled() && pullStateLabel(m.state) == null)

    // The popup rule: only a caller that started its sync, and
    // asked for the result, may report it. A pull latched onto a
    // running sync started nothing, so it stays quiet even though
    // it asked; silent auto syncs started theirs but never asked.
    check("report: pull started and asked reports", shouldReportSyncResult(true, true))
    check("report: latched pull stays quiet", !shouldReportSyncResult(false, true))
    check("report: silent auto sync stays quiet", !shouldReportSyncResult(true, false))
    check("report: neither started nor asked stays quiet", !shouldReportSyncResult(false, false))

    // The completion line is the manual flow's own words, exactly.
    check("report: done line for an up to date vault", syncDoneMessage(0) == "Sync done: 0 new calls saved")
    check("report: done line counts the new calls", syncDoneMessage(7) == "Sync done: 7 new calls saved")
}

fun testPageTitlesAndScrollStates() {
    println("== page titles and scroll states ==")
    check("title: call log page", VaultLogic.mainPageTitle(false) == "Call Logs")
    check("title: people page", VaultLogic.mainPageTitle(true) == "People")

    val top = VaultLogic.pageScrollState(0, 0)
    check("scroll state: top is preserved", top == VaultLogic.PageScrollState(0, 0))
    check(
        "scroll state: a partly scrolled row keeps its negative offset",
        VaultLogic.pageScrollState(12, -18) == VaultLogic.PageScrollState(12, -18)
    )
    check(
        "scroll state: negative positions and a missing row are sanitized",
        VaultLogic.pageScrollState(-4, null) == VaultLogic.PageScrollState(0, 0)
    )
    check(
        "scroll state: restore clamps beyond the end of a shorter list",
        VaultLogic.restoredPageScrollState(VaultLogic.PageScrollState(90, -8), 25) ==
            VaultLogic.PageScrollState(24, -8)
    )
    check(
        "scroll state: restore into an empty list returns to the top",
        VaultLogic.restoredPageScrollState(VaultLogic.PageScrollState(90, -8), 0) ==
            VaultLogic.PageScrollState()
    )
    check(
        "scroll state: restore keeps a valid position",
        VaultLogic.restoredPageScrollState(VaultLogic.PageScrollState(7, -3), 25) ==
            VaultLogic.PageScrollState(7, -3)
    )

    check("top test: first row at the padding is the top", VaultLogic.isAtTop(0, 12, 12))
    check("top test: first row below the padding is the top", VaultLogic.isAtTop(0, 16, 12))
    check("top test: a partly scrolled first row is not the top", !VaultLogic.isAtTop(0, 4, 12))
    check("top test: any later first row is not the top", !VaultLogic.isAtTop(1, 12, 12))
    check("away test: the first row alone is not meaningfully away", !VaultLogic.isMeaningfullyAwayFromTop(0))
    check("away test: one full row above is meaningfully away", VaultLogic.isMeaningfullyAwayFromTop(1))
    check("away test: deep in the list is meaningfully away", VaultLogic.isMeaningfullyAwayFromTop(400))
    check("away test: a bad position is not away", !VaultLogic.isMeaningfullyAwayFromTop(-1))

    // v1.2.6: the pill's away test is a pixel estimate, not a bare
    // adapter position, so one stale position read cannot latch it.
    check("estimate: at the top is zero", VaultLogic.estimatedScrollPx(0, 12, 12, 80) == 0)
    check(
        "estimate: a partly scrolled first row counts its travel",
        VaultLogic.estimatedScrollPx(0, -18, 12, 80) == 30
    )
    check(
        "estimate: whole rows use the row height",
        VaultLogic.estimatedScrollPx(3, 2, 12, 80) == 250
    )
    check("estimate: a bad position is zero", VaultLogic.estimatedScrollPx(-2, 0, 12, 80) == 0)
    check(
        "estimate: a missing row height still counts travel",
        VaultLogic.estimatedScrollPx(0, -40, 0, 0) == 40
    )
    check("away px: any whole row above is away", VaultLogic.scrollToTopAwayPx(1, 12, 12, 80, 96))
    check(
        "away px: a short first row travel is not away",
        !VaultLogic.scrollToTopAwayPx(0, -18, 12, 80, 96)
    )
    check(
        "away px: deep first row travel is away",
        VaultLogic.scrollToTopAwayPx(0, -120, 0, 200, 96)
    )
    check(
        "away px: exactly at the threshold is away",
        VaultLogic.scrollToTopAwayPx(0, -96, 0, 200, 96)
    )
    check(
        "away px: a bad position is not away",
        !VaultLogic.scrollToTopAwayPx(-1, 0, 0, 80, 96)
    )

    // v1.2.7: the fast scroll scrubber's fraction and index math.
    // The fraction mirrors the list (0 at the top, 1 on the last
    // page); the index maps a thumb fraction onto the whole list.
    check("scrub fraction: the top is zero", VaultLogic.scrollFraction(0, 10, 100) == 0f)
    check("scrub fraction: the last page is one", VaultLogic.scrollFraction(90, 10, 100) == 1f)
    check("scrub fraction: mid travel is a half", VaultLogic.scrollFraction(45, 10, 100) == 0.5f)
    check("scrub fraction: an empty list is zero", VaultLogic.scrollFraction(0, 10, 0) == 0f)
    check("scrub fraction: a list that fits is zero", VaultLogic.scrollFraction(0, 10, 8) == 0f)
    check("scrub fraction: no rows laid out is zero", VaultLogic.scrollFraction(5, 0, 100) == 0f)
    check("scrub fraction: past the end clamps to one", VaultLogic.scrollFraction(95, 10, 100) == 1f)
    check("scrub fraction: a bad position clamps to zero", VaultLogic.scrollFraction(-3, 10, 100) == 0f)
    check("scrub index: fraction zero is the first row", VaultLogic.scrubIndexForFraction(0f, 100) == 0)
    check("scrub index: fraction one is the last row", VaultLogic.scrubIndexForFraction(1f, 100) == 99)
    check("scrub index: the middle lands mid list", VaultLogic.scrubIndexForFraction(0.5f, 101) == 50)
    check("scrub index: below zero clamps to the first row", VaultLogic.scrubIndexForFraction(-0.5f, 100) == 0)
    check("scrub index: above one clamps to the last row", VaultLogic.scrubIndexForFraction(1.5f, 100) == 99)
    check("scrub index: an empty list is zero", VaultLogic.scrubIndexForFraction(0.5f, 0) == 0)
    check("scrub index: a single row is zero", VaultLogic.scrubIndexForFraction(1f, 1) == 0)
}

fun testScrollToTop() {
    println("== scroll to top ==")
    val hidden = ScrollToTopMachine.State.HIDDEN
    val ready = ScrollToTopMachine.State.READY
    val returning = ScrollToTopMachine.State.RETURNING
    val arrived = ScrollToTopMachine.State.ARRIVED

    check("top label: hidden has no label", scrollToTopLabel(hidden) == null)
    check("top label: ready invites", scrollToTopLabel(ready) == "↑ Top")
    check("top label: returning narrates", scrollToTopLabel(returning) == "↑ Going up")
    check("top label: arrived confirms", scrollToTopLabel(arrived) == "✓ At top")

    val m = ScrollToTopMachine()
    check("top machine: starts hidden", m.state == hidden)
    check("top machine: a return cannot start while hidden", !m.startReturn() && m.state == hidden)
    check("top machine: at the top it stays hidden", m.onPosition(awayFromTop = false, atTop = true) == hidden)
    check("top machine: away from the top it becomes ready", m.onPosition(awayFromTop = true, atTop = false) == ready)
    check("top machine: still away it stays ready", m.onPosition(awayFromTop = true, atTop = false) == ready)
    check("top machine: scrolling home by hand hides it", m.onPosition(awayFromTop = false, atTop = true) == hidden)

    check("top machine: away again readies it", m.onPosition(awayFromTop = true, atTop = false) == ready)
    check("top machine: a tap starts the return", m.startReturn() && m.state == returning)
    check("top machine: a second tap cannot restart it", !m.startReturn() && m.state == returning)
    check("top machine: still travelling it keeps returning", m.onPosition(awayFromTop = true, atTop = false) == returning)
    check("top machine: landing arrives", m.onPosition(awayFromTop = false, atTop = true) == arrived)
    check("top machine: the top holds the arrived state", m.onPosition(awayFromTop = false, atTop = true) == arrived)
    check("top machine: the arrived state finishes hidden", m.finishArrived() == hidden)
    check("top machine: finishing twice is a no-op", m.finishArrived() == hidden)

    // Scrolling away during the arrived beat retargets the button.
    m.onPosition(awayFromTop = true, atTop = false)
    m.startReturn()
    m.onPosition(awayFromTop = false, atTop = true)
    check("top machine: scrolling away from arrived readies it", m.onPosition(awayFromTop = true, atTop = false) == ready)

    // Interruptions land back on whatever the list is really doing.
    check("top machine: ready for the interrupt checks", m.startReturn() && m.state == returning)
    check("top machine: an interrupt while away readies it", m.interrupt(awayFromTop = true, atTop = false) == ready)
    check("top machine: return again after an interrupt", m.startReturn() && m.state == returning)
    check("top machine: an interrupt at the top hides it", m.interrupt(awayFromTop = false, atTop = true) == hidden)
    check("top machine: an interrupt while hidden changes nothing", m.interrupt(awayFromTop = true, atTop = false) == hidden)
    check("top machine: reset hides a ready button", m.onPosition(awayFromTop = true, atTop = false) == ready && m.reset() == hidden)

    check("top duration: the top itself gets the floor", scrollToTopDurationMs(0) == 450)
    check("top duration: a bad position gets the floor", scrollToTopDurationMs(-20) == 450)
    check("top duration: ten rows takes a little longer", scrollToTopDurationMs(10) == 570)
    check("top duration: a huge list is capped", scrollToTopDurationMs(100000) == 1500)
    check(
        "top duration: longer lists never get less time",
        scrollToTopDurationMs(40) >= scrollToTopDurationMs(20) &&
            scrollToTopDurationMs(20) >= scrollToTopDurationMs(5)
    )
}

/** One UTC wall clock moment as epoch millis (tests pin the default zone to UTC). */
fun utcMs(year: Int, month0: Int, day: Int, hour: Int, minute: Int): Long {
    val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
    cal.set(year, month0, day, hour, minute, 0)
    cal.set(java.util.Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

fun testBin() {
    println("== bin ==")
    val day = 24L * 60 * 60 * 1000
    val t0 = 1700000000000L
    check("bin: a fresh row is not expired", !VaultLogic.isBinExpired(t0, t0))
    check("bin: 29 days is not expired", !VaultLogic.isBinExpired(t0, t0 + 29 * day))
    check("bin: a millisecond before 30 days is not expired", !VaultLogic.isBinExpired(t0, t0 + 30 * day - 1))
    check("bin: exactly 30 days is expired", VaultLogic.isBinExpired(t0, t0 + 30 * day))
    check("bin: 45 days is expired", VaultLogic.isBinExpired(t0, t0 + 45 * day))
    check("bin: a future delete time is never expired", !VaultLogic.isBinExpired(t0 + day, t0))

    check("bin days: 30 at the delete", VaultLogic.binDaysLeft(t0, t0) == 30)
    check("bin days: still 30 an hour later", VaultLogic.binDaysLeft(t0, t0 + 3600000L) == 30)
    check("bin days: 29 after a day and a bit", VaultLogic.binDaysLeft(t0, t0 + day + 3600000L) == 29)
    check("bin days: 1 on the last day", VaultLogic.binDaysLeft(t0, t0 + 29 * day) == 1)
    check("bin days: 1 near the end of the last day", VaultLogic.binDaysLeft(t0, t0 + 30 * day - 3600000L) == 1)
    check("bin days: 0 once expired", VaultLogic.binDaysLeft(t0, t0 + 30 * day) == 0)
    check("bin days: 0 long after expiry", VaultLogic.binDaysLeft(t0, t0 + 90 * day) == 0)
    check("bin days: a future delete time clamps to 30", VaultLogic.binDaysLeft(t0 + day, t0) == 30)
}

fun testPause() {
    println("== pause ==")
    val hour = 60L * 60 * 1000
    val day = 24L * hour
    val t0 = 1700000000000L

    check("pause end: one hour", VaultLogic.pauseEndForChoice(VaultLogic.PauseChoice.ONE_HOUR, t0) == t0 + hour)
    check("pause end: four hours", VaultLogic.pauseEndForChoice(VaultLogic.PauseChoice.FOUR_HOURS, t0) == t0 + 4 * hour)

    val today8 = utcMs(2026, 2, 10, 8, 0)
    val tomorrow8 = utcMs(2026, 2, 11, 8, 0)
    check(
        "pause end: 07:59 until tomorrow is today 08:00",
        VaultLogic.pauseEndForChoice(VaultLogic.PauseChoice.UNTIL_TOMORROW, utcMs(2026, 2, 10, 7, 59)) == today8
    )
    check(
        "pause end: 08:00 sharp rolls to tomorrow 08:00",
        VaultLogic.pauseEndForChoice(VaultLogic.PauseChoice.UNTIL_TOMORROW, utcMs(2026, 2, 10, 8, 0)) == tomorrow8
    )
    check(
        "pause end: 08:01 is tomorrow 08:00",
        VaultLogic.pauseEndForChoice(VaultLogic.PauseChoice.UNTIL_TOMORROW, utcMs(2026, 2, 10, 8, 1)) == tomorrow8
    )
    check(
        "pause end: 23:30 is tomorrow 08:00",
        VaultLogic.pauseEndForChoice(VaultLogic.PauseChoice.UNTIL_TOMORROW, utcMs(2026, 2, 10, 23, 30)) == tomorrow8
    )
    check(
        "pause end: 00:30 is today 08:00",
        VaultLogic.pauseEndForChoice(VaultLogic.PauseChoice.UNTIL_TOMORROW, utcMs(2026, 2, 10, 0, 30)) == today8
    )

    val w = VaultLogic.PauseWindow(1000L, 2000L)
    val w2 = VaultLogic.PauseWindow(5000L, 6000L)
    check("pause window: before the start is outside", !VaultLogic.isInPauseWindow(999L, listOf(w)))
    check("pause window: the start itself is inside", VaultLogic.isInPauseWindow(1000L, listOf(w)))
    check("pause window: the middle is inside", VaultLogic.isInPauseWindow(1500L, listOf(w)))
    check("pause window: the end itself is outside", !VaultLogic.isInPauseWindow(2000L, listOf(w)))
    check("pause window: an empty list never matches", !VaultLogic.isInPauseWindow(1500L, emptyList()))
    check("pause window: a second window matches", VaultLogic.isInPauseWindow(5500L, listOf(w, w2)))
    check("pause window: between windows is outside", !VaultLogic.isInPauseWindow(3000L, listOf(w, w2)))

    check("paused at: inside the window", VaultLogic.isPausedAt(1500L, listOf(w)))
    check("paused at: the end itself is over", !VaultLogic.isPausedAt(2000L, listOf(w)))
    check("paused at: nothing recorded", !VaultLogic.isPausedAt(1500L, emptyList()))
    check(
        "active window: an overlap picks the latest end",
        VaultLogic.activePauseWindow(listOf(w, VaultLogic.PauseWindow(1200L, 3000L)), 1500L)?.endMs == 3000L
    )
    check("active window: none live", VaultLogic.activePauseWindow(listOf(w), 2500L) == null)

    check("clamp: resuming mid window ends it now", VaultLogic.clampedPauseEnd(w, 1500L) == 1500L)
    check("clamp: resuming after the end keeps the end", VaultLogic.clampedPauseEnd(w, 2500L) == 2000L)
    check("clamp: resuming before the start keeps the start", VaultLogic.clampedPauseEnd(w, 500L) == 1000L)

    check(
        "stale: a window that ended 91 days ago",
        VaultLogic.isPauseWindowStale(VaultLogic.PauseWindow(t0, t0 + hour), t0 + 91 * day)
    )
    check(
        "stale: a window that ended 89 days ago is kept",
        !VaultLogic.isPauseWindowStale(VaultLogic.PauseWindow(t0, t0 + hour), t0 + 89 * day)
    )
    check(
        "stale: a window still running is kept",
        !VaultLogic.isPauseWindowStale(VaultLogic.PauseWindow(t0, t0 + 100 * day), t0 + day)
    )

    // The watch folder merge refuses pause dated records too.
    val inside = rec(date = 1500L)
    val outside = rec(date = 3000L)
    val plan = VaultLogic.planWatchMerge(emptyMap(), listOf(inside, outside), emptySet(), listOf(w))
    check("merge pause: the paused call is refused", plan.inserts.none { it.dateMs == 1500L })
    check("merge pause: the later call still imports", plan.inserts.any { it.dateMs == 3000L })
    check("merge pause: a paused call is not renamed either", plan.nameUpdates.isEmpty())
    val planNoWindows = VaultLogic.planWatchMerge(emptyMap(), listOf(inside), emptySet())
    check("merge pause: no windows means no refusals", planNoWindows.inserts.size == 1)

    // The open pause (v1.2.9): "until I turn it back on", the
    // app's off switch. It is stored as a window whose end is
    // OPEN_PAUSE_END_MS, so while it is live it reads as
    // [start, forever) to every window check.
    val open = VaultLogic.PauseWindow(t0, VaultLogic.OPEN_PAUSE_END_MS)
    check(
        "pause end: until turned on has no end",
        VaultLogic.pauseEndForChoice(VaultLogic.PauseChoice.UNTIL_TURNED_ON, t0) ==
            VaultLogic.OPEN_PAUSE_END_MS
    )
    check("open pause: it is open", VaultLogic.isOpenPause(open))
    check("open pause: a timed window is not open", !VaultLogic.isOpenPause(w))
    check("open window: before the start is outside", !VaultLogic.isInPauseWindow(t0 - 1, listOf(open)))
    check("open window: the start itself is inside", VaultLogic.isInPauseWindow(t0, listOf(open)))
    check(
        "open window: far future dates are still inside while live",
        VaultLogic.isInPauseWindow(t0 + 10 * 365 * day, listOf(open))
    )
    check("open pause: still paused a year on", VaultLogic.isPausedAt(t0 + 365 * day, listOf(open)))
    check(
        "open pause: it is the active window",
        VaultLogic.activePauseWindow(listOf(open), t0 + day) == open
    )
    check(
        "open pause: it wins an overlap with a timed window",
        VaultLogic.activePauseWindow(listOf(open, VaultLogic.PauseWindow(t0, t0 + 4 * hour)), t0 + hour) == open
    )
    check(
        "open pause: a timed window alone still resolves while one is recorded",
        VaultLogic.activePauseWindow(
            listOf(open, VaultLogic.PauseWindow(t0 - 2 * day, t0 - day)), t0 - 36 * hour
        )?.endMs == t0 - day
    )

    // Resuming closes the open window at that moment: it becomes
    // an ordinary finished window with the usual edges, and what
    // was inside it stays refused forever.
    val resumeAt = t0 + 2 * hour
    val closedOpen = VaultLogic.PauseWindow(open.startMs, VaultLogic.clampedPauseEnd(open, resumeAt))
    check("open pause: resuming clamps its end to now", closedOpen.endMs == resumeAt)
    check("open pause: once closed it is no longer open", !VaultLogic.isOpenPause(closedOpen))
    check(
        "closed open window: the last moment inside stays inside",
        VaultLogic.isInPauseWindow(resumeAt - 1, listOf(closedOpen))
    )
    check(
        "closed open window: the resume moment itself is outside",
        !VaultLogic.isInPauseWindow(resumeAt, listOf(closedOpen))
    )
    check(
        "closed open window: a call inside it stays refused long after",
        VaultLogic.isInPauseWindow(t0 + hour, listOf(closedOpen)) &&
            !VaultLogic.isPausedAt(resumeAt + day, listOf(closedOpen))
    )

    // Housekeeping never prunes an open window, however old.
    check(
        "stale: an open window is never stale",
        !VaultLogic.isPauseWindowStale(open, t0 + 3650 * day)
    )
    check(
        "stale: a closed open window prunes 90 days past its end like any other",
        VaultLogic.isPauseWindowStale(closedOpen, resumeAt + 91 * day)
    )

    // The watch merge honours an open window too: anything dated
    // from its start on is refused, earlier records still import.
    val beforeOpen = rec(date = t0 - hour)
    val duringOpen = rec(date = t0 + hour)
    val openPlan = VaultLogic.planWatchMerge(
        emptyMap(), listOf(beforeOpen, duringOpen), emptySet(), listOf(open)
    )
    check("merge open pause: the call from during it is refused", openPlan.inserts.none { it.dateMs == t0 + hour })
    check("merge open pause: the earlier call still imports", openPlan.inserts.any { it.dateMs == t0 - hour })
}
