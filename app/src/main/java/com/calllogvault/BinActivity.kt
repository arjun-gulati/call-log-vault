package com.calllogvault

import android.app.Activity
import android.content.Context
import android.content.res.Resources
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

/**
 * The Bin (v1.2.8, GitHub issue #9). Calls deleted from the vault
 * land here with their full rows for 30 days: restore puts a call
 * back into the vault (and removes its tombstone, so it stays
 * back), delete forever drops the bin copy while the tombstone
 * stays, and anything left simply ages out. The header says
 * plainly that calls deleted before the Bin existed are not here:
 * those deletes kept only a tombstone hash and are gone for good.
 */
class BinActivity : Activity() {

    private var dpF = 1f
    private fun dp(v: Int): Int = (v * dpF).toInt()
    private fun color(id: Int): Int = resources.getColor(id, theme)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(UiKit.wrapContext(newBase))
    }

    // AMOLED black substitution, same pattern as PersonActivity.
    private var wrappedRes: Resources? = null

    override fun getResources(): Resources {
        val base = super.getResources()
        if (!UiKit.isAmoled(this, base)) return base
        if (wrappedRes == null) wrappedRes = UiKit.wrapResources(this, base, null)
        return wrappedRes!!
    }

    private var entries: List<VaultLogic.BinEntry> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        dpF = resources.displayMetrics.density
        reload()
    }

    /** Load the bin on the vault worker, then build the screen. */
    private fun reload() {
        VaultExecutor.service.execute {
            val helper = VaultDb.get(this)
            // Writable: housekeeping purges expired rows.
            val db = helper.writableDatabase
            helper.housekeeping(db, System.currentTimeMillis())
            val loaded = helper.binEntries(db)
            runOnUiThread {
                entries = loaded
                buildUi()
            }
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.bg))
            setPadding(dp(20), dp(14), dp(20), dp(8))
        }

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

        root.addView(TextView(this).apply {
            text = "Bin"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.text))
        })
        root.addView(TextView(this).apply {
            text = "Deleted calls stay here for 30 days, then they are removed " +
                "for good. Tap a call to restore it or delete it forever. " +
                "Calls deleted before the Bin existed were removed for good " +
                "at the time, so they are not here."
            textSize = 13.5f
            setTextColor(color(R.color.muted))
            setPadding(0, dp(6), 0, 0)
        })

        if (entries.isEmpty()) {
            root.addView(TextView(this).apply {
                text = "The bin is empty."
                textSize = 15f
                setTextColor(color(R.color.muted))
                gravity = Gravity.CENTER
                setPadding(0, dp(48), 0, 0)
            })
            setContentView(root)
            return
        }

        val emptyBin = TextView(this).apply {
            text = "Empty bin"
            textSize = 14f
            setTextColor(color(R.color.red))
            gravity = Gravity.END
            setPadding(0, dp(12), 0, dp(2))
            isClickable = true
            isFocusable = true
            pressFeedback()
            setOnClickListener { confirmEmptyBin() }
        }
        root.addView(emptyBin)

        val now = System.currentTimeMillis()
        val adapter = object : BaseAdapter() {
            override fun getCount(): Int = entries.size
            override fun getItem(position: Int): Any = entries[position]
            override fun getItemId(position: Int): Long = entries[position].binId
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = if (convertView is LinearLayout) convertView else buildRow()
                bindRow(row, entries[position], now)
                return row
            }
        }
        val listView = ListView(this).apply {
            divider = null
            isScrollingCacheEnabled = false
            cacheColorHint = 0
            selector = UiKit.rowSelector(this@BinActivity)
            this.adapter = adapter
        }
        listView.setOnItemClickListener { _, _, position, _ ->
            entries.getOrNull(position)?.let { showEntryActions(it) }
        }
        root.addView(
            listView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        setContentView(root)
    }

    private class RowHolder(
        val title: TextView,
        val subtitle: TextView,
        val daysLeft: TextView
    )

    private fun buildRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        val title = TextView(this).apply {
            textSize = 16f
            setTextColor(color(R.color.text))
        }
        val subtitle = TextView(this).apply {
            textSize = 13f
            setTextColor(color(R.color.muted))
            setPadding(0, dp(2), 0, 0)
        }
        texts.addView(title)
        texts.addView(subtitle)
        row.addView(texts)
        val daysLeft = TextView(this).apply {
            textSize = 12.5f
            setTextColor(color(R.color.muted))
            gravity = Gravity.END
        }
        row.addView(daysLeft)
        row.tag = RowHolder(title, subtitle, daysLeft)
        return row
    }

    private fun bindRow(row: LinearLayout, entry: VaultLogic.BinEntry, nowMs: Long) {
        val holder = row.tag as RowHolder
        val r = entry.record
        holder.title.text = VaultLogic.effectiveName(r)
        holder.subtitle.text = VaultLogic.typeLabel(r.type) + " · " +
            VaultLogic.formatDateTime(r.dateMs)
        val days = VaultLogic.binDaysLeft(entry.deletedAtMs, nowMs)
        holder.daysLeft.text = when (days) {
            0 -> "Last day"
            1 -> "1 day left"
            else -> days.toString() + " days left"
        }
    }

    // ------------------------------------------------------------- Actions

    private fun showEntryActions(entry: VaultLogic.BinEntry) {
        val r = entry.record
        val days = VaultLogic.binDaysLeft(entry.deletedAtMs, System.currentTimeMillis())
        val leftText = when (days) {
            0 -> "It is removed for good today."
            1 -> "It has 1 day left in the Bin."
            else -> "It has " + days + " days left in the Bin."
        }
        VaultDialog.message(
            this,
            VaultLogic.effectiveName(r),
            VaultLogic.typeLabel(r.type) + " · " + VaultLogic.formatDateTime(r.dateMs) +
                "\n" + leftText + " Restoring puts the call back in the vault.",
            positiveLabel = "Restore",
            onPositive = { restoreEntry(entry) },
            negativeLabel = "Delete forever",
            onNegative = { confirmDeleteForever(entry) },
            negativeDestructive = true
        )
    }

    private fun restoreEntry(entry: VaultLogic.BinEntry) {
        VaultExecutor.service.execute {
            val helper = VaultDb.get(this)
            val inserted = helper.restoreFromBin(helper.writableDatabase, entry.binId)
            runOnUiThread {
                toast(
                    if (inserted) "Call restored to the vault"
                    else "That call was already in the vault"
                )
                reload()
            }
        }
    }

    private fun confirmDeleteForever(entry: VaultLogic.BinEntry) {
        VaultDialog.message(
            this,
            "Delete forever?",
            "This removes the call from the Bin for good. It cannot be " +
                "restored, and it will never come back to the vault.",
            positiveLabel = "Delete forever",
            onPositive = {
                VaultExecutor.service.execute {
                    val helper = VaultDb.get(this@BinActivity)
                    helper.deleteBinForever(helper.writableDatabase, entry.binId)
                    runOnUiThread {
                        toast("Deleted for good")
                        reload()
                    }
                }
            },
            negativeLabel = "Cancel",
            onNegative = null
        )
    }

    private fun confirmEmptyBin() {
        VaultDialog.message(
            this,
            "Empty the bin?",
            "This removes all " + entries.size + " calls in the Bin for good. " +
                "None of them can be restored afterwards.",
            positiveLabel = "Empty bin",
            onPositive = {
                VaultExecutor.service.execute {
                    val helper = VaultDb.get(this@BinActivity)
                    helper.emptyBin(helper.writableDatabase)
                    runOnUiThread {
                        toast("Bin emptied")
                        reload()
                    }
                }
            },
            negativeLabel = "Cancel",
            onNegative = null
        )
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}
