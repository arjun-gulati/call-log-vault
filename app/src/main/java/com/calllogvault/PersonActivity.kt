package com.calllogvault

import android.app.Activity
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
 * Also the only place anything can be deleted: a long press enters
 * multi select on the timeline (v1.2.0), and the delete all action
 * sits under the pills. Every delete runs through the two flavor
 * confirmation: vault only, or vault and phone log. Both flavors
 * tombstone the deleted keys, so nothing deleted here ever comes
 * back through a sync, an old backup or the watch folder.
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

    // Multi select state (v1.2.0). Positions index into [records]
    // and [timelineDisplays], which are always built together.
    private var selecting = false
    private val selectedPositions = HashSet<Int>()
    private var timelineList: ListView? = null
    private var timelineAdapter: BaseAdapter? = null
    private var selectionBar: LinearLayout? = null
    private var selectionCount: TextView? = null

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

    override fun onBackPressed() {
        // Back leaves selection mode first, the standard pattern.
        if (selecting) {
            exitSelection()
            return
        }
        super.onBackPressed()
    }

    private fun buildUi() {
        // Every rebuild starts out of selection mode: the displays
        // are new objects, so stale selection flags would lie.
        selecting = false
        selectedPositions.clear()
        timelineList = null
        timelineAdapter = null
        selectionBar = null
        selectionCount = null

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
        root.addView(buildSelectionBar())

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
            pressFeedback()
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
            pressFeedback()
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
                compact = true,
                typeTint = color(typeTintRes(r.type))
            )
            rowLayoutFactory.buildInto(display, listWidthPx)
            display
        }
        val timelineAdapterLocal = object : BaseAdapter() {
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
        timelineAdapter = timelineAdapterLocal
        val listView = ListView(this).apply {
            divider = null
            isScrollingCacheEnabled = false
            cacheColorHint = 0
            // Same pressed highlight as the main list (v1.0.5): the
            // selector, not per row ripple backgrounds.
            selector = UiKit.rowSelector(this@PersonActivity)
            adapter = timelineAdapterLocal
        }
        timelineList = listView
        // In selection mode a tap toggles the entry; outside it a
        // tap does nothing, as before.
        listView.setOnItemClickListener { _, _, position, _ ->
            if (selecting) toggleSelection(position)
        }
        // Long press enters selection mode with that entry chosen
        // (the standard pattern), or toggles it like a tap when
        // selection is already running.
        listView.setOnItemLongClickListener { _, _, position, _ ->
            if (records.getOrNull(position) == null) {
                false
            } else {
                if (selecting) toggleSelection(position) else enterSelection(position)
                true
            }
        }
        root.addView(listView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    // ------------------------------------------------------------ Selection

    /**
     * The selection bar: close, the live count, Select all and
     * Delete. Hidden unless selection mode is running; it is built
     * with every screen build and shown by [applySelectionUi].
     */
    private fun buildSelectionBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(0, dp(2), 0, dp(8))
        }
        val close = TextView(this).apply {
            text = "✕"
            textSize = 16f
            setTextColor(color(R.color.text))
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(8), dp(14), dp(8))
            isClickable = true
            pressFeedback()
            setOnClickListener { exitSelection() }
        }
        bar.addView(close)
        val count = TextView(this).apply {
            textSize = 15f
            typeface = UiKit.mediumTypeface
            setTextColor(color(R.color.text))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        selectionCount = count
        bar.addView(count)
        val selectAll = TextView(this).apply {
            text = "Select all"
            textSize = 14f
            setTextColor(color(R.color.primary))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            isClickable = true
            pressFeedback()
            setOnClickListener {
                selectedPositions.clear()
                for (i in records.indices) selectedPositions.add(i)
                applySelectionUi()
            }
        }
        bar.addView(selectAll)
        val delete = TextView(this).apply {
            text = "Delete"
            textSize = 14f
            typeface = UiKit.mediumTypeface
            setTextColor(color(R.color.red))
            setPadding(dp(12), dp(8), dp(4), dp(8))
            isClickable = true
            pressFeedback()
            setOnClickListener { confirmDeleteSelected() }
        }
        bar.addView(delete)
        selectionBar = bar
        return bar
    }

    private fun enterSelection(position: Int) {
        selecting = true
        selectedPositions.add(position)
        applySelectionUi()
    }

    private fun toggleSelection(position: Int) {
        if (!selectedPositions.add(position)) selectedPositions.remove(position)
        if (selectedPositions.isEmpty()) selecting = false
        applySelectionUi()
    }

    private fun exitSelection() {
        selecting = false
        selectedPositions.clear()
        applySelectionUi()
    }

    /** Push the selection state into the displays, the bar and the list. */
    private fun applySelectionUi() {
        for (d in timelineDisplays) d.selected = false
        for (p in selectedPositions) timelineDisplays.getOrNull(p)?.selected = true
        timelineAdapter?.notifyDataSetChanged()
        selectionBar?.visibility = if (selecting) View.VISIBLE else View.GONE
        selectionCount?.text = selectedPositions.size.toString() + " selected"
    }

    // -------------------------------------------------------------- Delete

    /**
     * The selection delete confirmation. One dialog, two flavors:
     * vault only, or vault and phone log. Both tombstone the keys,
     * so the confirm can honestly promise the entries never come
     * back to this phone.
     */
    private fun confirmDeleteSelected() {
        val targets = selectedPositions.sorted().mapNotNull { records.getOrNull(it) }
        if (targets.isEmpty()) return
        val count = targets.size
        val title: String
        val message: String
        if (count == 1) {
            title = "Delete this entry from the vault?"
            message = "This removes this one call from the vault. " +
                "It will not come back on this phone, even if it is still in " +
                "your call log or an old backup."
        } else {
            title = "Delete " + count + " entries from the vault?"
            message = "This removes " + count + " entries from the vault. " +
                "They will not come back on this phone, even if they are still in " +
                "your call log or an old backup."
        }
        VaultDialog.deleteConfirm(this, title, message,
            onVaultOnly = { performDelete(targets, alsoPhone = false) },
            onVaultAndPhone = { performDelete(targets, alsoPhone = true) }
        )
    }

    private fun confirmDeleteAll() {
        val targets = records
        if (targets.isEmpty()) return
        VaultDialog.deleteConfirm(
            this,
            "Delete all entries for this number?",
            "This removes every entry for this number from the vault, " +
                targets.size + " in total. They will not come back on this phone, " +
                "even if they are still in your call log or an old backup.",
            onVaultOnly = { performDelete(targets, alsoPhone = false) },
            onVaultAndPhone = { performDelete(targets, alsoPhone = true) }
        )
    }

    /**
     * Run one confirmed delete on the vault worker: tombstones and
     * vault rows in one transaction, then (for the permanent
     * flavor) the phone log rows. The result dialog states the
     * plain counts, and its OK either rebuilds this screen or, when
     * that was the person's last entry, leaves cleanly instead of
     * showing an empty screen.
     */
    private fun performDelete(targets: List<VaultLogic.CallRecord>, alsoPhone: Boolean) {
        VaultExecutor.service.execute {
            val helper = VaultDb.get(this)
            val vaultRemoved = helper.deleteEntries(
                helper.writableDatabase, targets, System.currentTimeMillis()
            )
            val phoneRemoved: Int? = if (alsoPhone) {
                SyncEngine.deleteFromPhoneLog(this, targets)
            } else {
                null
            }
            val remaining = helper.forNumber(helper.readableDatabase, numberNorm)
            runOnUiThread {
                VaultDialog.message(
                    this, "Entries deleted",
                    VaultLogic.deleteOutcomeText(vaultRemoved, phoneRemoved),
                    onPositive = {
                        if (remaining.isEmpty()) {
                            finish()
                        } else {
                            records = remaining
                            buildUi()
                        }
                    }
                )
            }
        }
    }

    private fun restoreThisNumber() {
        if (!PermUtil.has(this, android.Manifest.permission.WRITE_CALL_LOG)) {
            Toast.makeText(this, "Allow call log access in the main screen first", Toast.LENGTH_LONG).show()
            return
        }
        val progress = VaultDialog.progress(this, "Restoring calls for this number")
        val toRestore = records.reversed() // oldest first
        VaultExecutor.service.execute {
            val result = SyncEngine.restore(this, toRestore)
            runOnUiThread {
                progress.dismiss()
                val msg = if (result.error != null) result.error
                else "Put " + result.inserted + " calls back into the phone call list, skipped " +
                    result.skipped + " that were already there."
                VaultDialog.message(this, "Restore this number", msg)
            }
        }
    }

    companion object {
        const val EXTRA_NUMBER_NORM = "number_norm"
    }
}
