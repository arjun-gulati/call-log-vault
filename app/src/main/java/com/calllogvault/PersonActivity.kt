package com.calllogvault

import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

/**
 * Full vault timeline for one number, with call and restore actions.
 * Also the only place anything can be deleted: a long press on one
 * timeline entry, or the delete all action, each behind a
 * confirmation dialog, each removing vault records only. The
 * phone's own call log is never touched by a delete.
 */
class PersonActivity : Activity() {

    private var dpF = 1f
    private fun dp(v: Int): Int = (v * dpF).toInt()
    private fun color(id: Int): Int = resources.getColor(id, theme)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(UiKit.wrapContext(newBase))
    }

    // AMOLED black substitution, same pattern as MainActivity.
    private var wrappedRes: Resources? = null

    override fun getResources(): Resources {
        val base = super.getResources()
        if (!UiKit.isAmoled(this, base)) return base
        if (wrappedRes == null) wrappedRes = UiKit.wrapResources(this, base, null)
        return wrappedRes!!
    }

    private var records: List<VaultLogic.CallRecord> = emptyList()
    private var numberNorm: String = ""
    private var timelineDisplays: List<RowDisplay> = emptyList()

    /** Type icons rendered once into tinted bitmaps for the timeline rows. */
    private val iconBitmaps: Map<Int, android.graphics.Bitmap> by lazy {
        RowView.buildIconBitmaps(this, 18) { type -> color(typeTintRes(type)) }
    }

    /** Shared row text paints and the prebuilt layout factory (v1.0.5). */
    private val rowPaints: RowPaints by lazy { RowPaints(this) }
    private val rowLayoutFactory: RowLayoutFactory by lazy { RowLayoutFactory(rowPaints) }

    private fun typeTintRes(type: Int): Int = when (type) {
        VaultLogic.TYPE_INCOMING -> R.color.green
        VaultLogic.TYPE_OUTGOING -> R.color.primary
        VaultLogic.TYPE_MISSED -> R.color.red
        VaultLogic.TYPE_REJECTED -> R.color.amber
        VaultLogic.TYPE_BLOCKED -> R.color.red
        VaultLogic.TYPE_VOICEMAIL -> R.color.muted
        else -> R.color.muted
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        dpF = resources.displayMetrics.density
        numberNorm = intent.getStringExtra(EXTRA_NUMBER_NORM) ?: ""
        if (numberNorm.isEmpty()) {
            finish()
            return
        }
        val helper = VaultDb.get(this)
        records = helper.forNumber(helper.readableDatabase, numberNorm)
        if (records.isEmpty()) {
            Toast.makeText(this, "No calls saved for this number", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        buildUi()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.bg))
            setPadding(dp(20), dp(14), dp(20), dp(8))
        }

        // Back affordance
        val back = TextView(this).apply {
            text = "‹  Back"
            textSize = 15f
            setTextColor(color(R.color.primary))
            setPadding(0, dp(4), dp(12), dp(10))
            isClickable = true
            pressFeedback()
            setOnClickListener { finish() }
        }
        root.addView(back)

        val latest = records.first()
        val named = records.firstOrNull { !VaultLogic.isUnknownName(it.name) }
        val displayName = named?.name?.trim() ?: latest.numberRaw

        // Header card
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = resources.getDrawable(R.drawable.card_rounded, theme)
            setPadding(dp(20), dp(20), dp(20), dp(18))
        }
        val headRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headRow.addView(UiKit.avatar(this, named?.name ?: "", latest.numberRaw, numberNorm, 64))
        val headText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(16) }
        }
        headText.addView(TextView(this).apply {
            text = displayName
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.text))
        })
        headText.addView(TextView(this).apply {
            text = latest.numberRaw
            textSize = 14f
            setTextColor(color(R.color.muted))
            setPadding(0, dp(3), 0, 0)
        })
        headRow.addView(headText)
        card.addView(headRow)

        // Stats: two rows of two
        val totalDur = records.sumOf { it.durationSec }
        fun statCell(label: String, value: String): LinearLayout {
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            cell.addView(TextView(this).apply {
                text = value
                textSize = 15f
                typeface = UiKit.mediumTypeface
                setTextColor(color(R.color.text))
            })
            cell.addView(TextView(this).apply {
                text = label.uppercase()
                textSize = 10f
                letterSpacing = 0.14f
                setTextColor(color(R.color.muted))
                setPadding(0, dp(2), 0, 0)
            })
            return cell
        }
        val stats1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(18), 0, 0)
        }
        stats1.addView(statCell("Calls", records.size.toString()))
        stats1.addView(statCell("Talk time", VaultLogic.formatDuration(totalDur)))
        card.addView(stats1)
        val stats2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        stats2.addView(statCell("First call", VaultLogic.formatDateTime(records.last().dateMs).substringBefore(",")))
        stats2.addView(statCell("Last call", VaultLogic.formatDateTime(records.first().dateMs).substringBefore(",")))
        card.addView(stats2)
        root.addView(card)

        // Actions: primary pill Call, secondary pill Restore
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(16), 0, 0)
        }
        val callBtn = Button(this).apply {
            text = "Call"
            isAllCaps = false
            textSize = 15f
            setTextColor(color(R.color.on_primary))
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(color(R.color.primary))
            }
            setOnClickListener {
                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(latest.numberRaw)))
                startActivity(intent)
            }
        }
        row.addView(callBtn, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6) })
        val restoreBtn = Button(this).apply {
            text = "Restore this number"
            isAllCaps = false
            textSize = 15f
            setTextColor(color(R.color.pill_unsel_text))
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(color(R.color.pill_unsel_bg))
                setStroke(dp(1), color(R.color.pill_unsel_border))
            }
            setOnClickListener { restoreThisNumber() }
        }
        row.addView(restoreBtn, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(6) })
        root.addView(row)

        // Quiet text action under the pills: delete every vault entry
        // for this number. Deliberately not a third pill, the pill row
        // stays about calling and restoring.
        val deleteAll = TextView(this).apply {
            text = "Delete all entries for this number"
            textSize = 14f
            setTextColor(color(R.color.red))
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(2))
            isClickable = true
            isFocusable = true
            pressFeedback()
            setOnClickListener { confirmDeleteAll() }
        }
        root.addView(deleteAll)

        val header = TextView(this).apply {
            text = "TIMELINE"
            textSize = 11f
            letterSpacing = 0.18f
            setTextColor(color(R.color.eyebrow))
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(20), 0, dp(4))
        }
        root.addView(header)

        // Timeline rows are the same single custom drawn views as the
        // main list (compact variant: type icon at the left, no avatar),
        // with every string and every prebuilt text layout precomputed
        // here, once. The list spans the root width minus its 20dp
        // padding on both sides.
        val listWidthPx = resources.displayMetrics.widthPixels - dp(40)
        val now = System.currentTimeMillis()
        timelineDisplays = records.mapIndexed { i, r ->
            val display = RowDisplay(
                title = VaultLogic.typeLabel(r.type),
                subtitle = VaultLogic.timelineSubtitle(r.dateMs, now),
                rightTop = "",
                rightBottom = VaultLogic.callRightBottom(r.durationSec),
                avatarInitials = null,
                avatarBg = 0,
                avatarFg = 0,
                typeIcon = r.type,
                showDivider = i < records.size - 1,
                numberNorm = numberNorm,
                compact = true
            )
            rowLayoutFactory.buildInto(display, listWidthPx)
            display
        }
        val listView = ListView(this).apply {
            divider = null
            isScrollingCacheEnabled = false
            cacheColorHint = 0
            // Same pressed highlight as the main list (v1.0.5): the
            // selector, not per row ripple backgrounds.
            selector = UiKit.rowSelector(this@PersonActivity)
            adapter = object : BaseAdapter() {
                override fun getCount(): Int = timelineDisplays.size
                override fun getItem(position: Int): Any = timelineDisplays[position]
                override fun getItemId(position: Int): Long = position.toLong()
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val rv = if (convertView is RowView) convertView else RowView(this@PersonActivity)
                    rv.layoutFactory = rowLayoutFactory
                    rv.setItem(timelineDisplays[position], iconBitmaps)
                    return rv
                }
            }
        }
        // Long press one entry to delete just that entry from the
        // vault. The rows highlight through the same list selector
        // as a normal press while the long press builds.
        listView.setOnItemLongClickListener { _, _, position, _ ->
            val record = records.getOrNull(position)
            if (record != null) {
                confirmDeleteEntry(record)
                true
            } else {
                false
            }
        }
        root.addView(listView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    // -------------------------------------------------------------- Delete

    private fun confirmDeleteEntry(record: VaultLogic.CallRecord) {
        AlertDialog.Builder(this)
            .setTitle("Delete this entry from the vault?")
            .setMessage(
                "This removes this one call from the vault. It cannot be undone. " +
                    "Nothing is deleted from the phone's own call log."
            )
            .setPositiveButton("Delete") { _, _ -> deleteEntry(record) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteEntry(record: VaultLogic.CallRecord) {
        VaultExecutor.service.execute {
            val helper = VaultDb.get(this)
            helper.deleteRecord(helper.writableDatabase, record.vaultId)
            val remaining = helper.forNumber(helper.readableDatabase, numberNorm)
            runOnUiThread {
                Toast.makeText(this, "Entry deleted from the vault", Toast.LENGTH_SHORT).show()
                if (remaining.isEmpty()) {
                    // That was the last entry for this number: leave
                    // cleanly instead of showing an empty screen.
                    finish()
                } else {
                    records = remaining
                    buildUi()
                }
            }
        }
    }

    private fun confirmDeleteAll() {
        AlertDialog.Builder(this)
            .setTitle("Delete all entries for this number?")
            .setMessage(
                "This removes every entry for this number from the vault, " +
                    records.size + " in total. It cannot be undone. " +
                    "Nothing is deleted from the phone's own call log."
            )
            .setPositiveButton("Delete all") { _, _ -> deleteAllEntries() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteAllEntries() {
        VaultExecutor.service.execute {
            val helper = VaultDb.get(this)
            helper.deleteNumber(helper.writableDatabase, numberNorm)
            runOnUiThread {
                Toast.makeText(this, "Entries deleted from the vault", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun restoreThisNumber() {
        if (!PermUtil.has(this, android.Manifest.permission.WRITE_CALL_LOG)) {
            Toast.makeText(this, "Allow call log access in the main screen first", Toast.LENGTH_LONG).show()
            return
        }
        val progress = ProgressDialog(this)
        progress.setMessage("Restoring calls for this number")
        progress.setCancelable(false)
        progress.show()
        val toRestore = records.reversed() // oldest first
        VaultExecutor.service.execute {
            val result = SyncEngine.restore(this, toRestore)
            runOnUiThread {
                progress.dismiss()
                val msg = if (result.error != null) result.error
                else "Put " + result.inserted + " calls back into the phone call list, skipped " +
                    result.skipped + " that were already there."
                AlertDialog.Builder(this)
                    .setTitle("Restore this number")
                    .setMessage(msg)
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    companion object {
        const val EXTRA_NUMBER_NORM = "number_norm"
    }
}
