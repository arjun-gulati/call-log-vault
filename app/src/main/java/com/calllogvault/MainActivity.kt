package com.calllogvault

import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
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
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Home is the call log itself. Everything else (backup, restore, sync,
 * appearance, status) lives in the left drawer:
 * a 300dp animated panel over a scrim, brand header on top, vault
 * stats inside, support card pinned at the bottom.
 */
class MainActivity : Activity() {

    private var dpF = 1f
    private fun dp(v: Int): Int = (v * dpF).toInt()
    private fun color(id: Int): Int = resources.getColor(id, theme)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(UiKit.wrapContext(newBase))
    }

    // AMOLED: when the AMOLED preset is active,
    // resources come through the black substituting wrapper (cached).
    private var wrappedRes: Resources? = null

    override fun getResources(): Resources {
        val base = super.getResources()
        if (!UiKit.isAmoled(this, base)) return base
        if (wrappedRes == null) wrappedRes = UiKit.wrapResources(this, base, null)
        return wrappedRes!!
    }

    // Content views
    private lateinit var frame: FrameLayout
    private lateinit var content: LinearLayout
    private lateinit var chipRow: LinearLayout
    private lateinit var chipScroll: HorizontalScrollView
    private lateinit var listView: ListView
    private lateinit var emptyView: TextView
    private lateinit var permPanel: LinearLayout
    private lateinit var searchEdit: EditText
    private lateinit var clearBtn: TextView
    private lateinit var watchingBox: LinearLayout

    // Drawer views
    private lateinit var scrim: View
    private lateinit var panel: LinearLayout
    private var drawerOpen = false
    private var drawerW = 0
    private lateinit var statTotal: TextView
    private lateinit var statOldest: TextView
    private lateinit var statTalk: TextView
    private lateinit var statPhone: TextView
    private lateinit var statSync: TextView
    private lateinit var statAuto: TextView
    private var autoSubtitle: TextView? = null
    private var scheduleSubtitle: TextView? = null

    private var allRecords: List<VaultLogic.CallRecord> = emptyList()
    private var people: List<VaultLogic.PersonSummary> = emptyList()
    private var filter: VaultLogic.Filter = VaultLogic.Filter.ALL
    private var peopleMode = false
    private var query = ""
    private var syncing = false
    private var syncStartedMs = 0L
    private var loadingVault = false

    /**
     * Vault snapshot from the last full apply: the pair the no-change
     * skip in refresh() compares against (v1.0.6). Count leads; the
     * max date rides along for the Status story and future rules.
     * Records are newest first, so the max date is the first record.
     */
    private var snapshotCount = -1L
    private var snapshotMaxDateMs = 0L

    /** Debounced search rebuild state (v1.0.6, see scheduleSearchRebuild). */
    private val searchHandler = Handler(Looper.getMainLooper())
    private var searchToken = 0
    private var searchRunnable: Runnable? = null

    private sealed class Item {
        class Header(val text: String) : Item()
        class Display(val display: RowDisplay) : Item()
    }

    private var items: List<Item> = emptyList()

    /** Avatar theme colors, resolved once for this activity instance. */
    private val avatarColors: UiKit.AvatarColors by lazy { UiKit.avatarColors(this) }

    /** Type icons rendered once into tinted bitmaps for RowView. */
    private val iconBitmaps: Map<Int, android.graphics.Bitmap> by lazy {
        RowView.buildIconBitmaps(this, 14) { type -> color(typeTintRes(type)) }
    }

    /** Shared row text paints and the prebuilt layout factory (v1.0.5). */
    private val rowPaints: RowPaints by lazy { RowPaints(this) }
    private val rowLayoutFactory: RowLayoutFactory by lazy { RowLayoutFactory(rowPaints) }

    /**
     * The width the row layouts should be built for: the list's own
     * width once it is laid out, else the screen width (the list is
     * full width, so they agree). Read when items are built, on
     * whichever thread builds them.
     */
    private fun currentListWidthPx(): Int {
        val w = if (::listView.isInitialized) listView.width else 0
        return if (w > 0) w else resources.displayMetrics.widthPixels
    }

    /**
     * The avatar triple for one row (initials, background, foreground),
     * resolved with exactly the colors UiKit.bindAvatar used for the old
     * view rows, so the drawn rows look identical. The palette arrives
     * as resolved color ints, so this is pure int math and safe to call
     * from the background thread that builds items.
     */
    private fun avatarColorsFor(
        name: String,
        numberRaw: String,
        key: String,
        palette: UiKit.AvatarColors
    ): Triple<String, Int, Int> {
        val initials = VaultLogic.initialsFor(name, numberRaw)
        return if (initials.isEmpty()) {
            Triple("?", palette.tileBg, palette.muted)
        } else {
            val base = VaultLogic.avatarColor(key)
            Triple(
                initials,
                UiKit.blendOver(palette.card, base, 0.32f),
                UiKit.blendOver(palette.card, base, 0.92f)
            )
        }
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getViewTypeCount(): Int = 2
        override fun getItemViewType(position: Int): Int = when (items[position]) {
            is Item.Header -> 0
            else -> 1
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val item = items[position]
            if (item is Item.Header) {
                val tv = (convertView as? TextView) ?: TextView(this@MainActivity).apply {
                    textSize = 11f
                    letterSpacing = 0.18f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(color(R.color.eyebrow))
                    setPadding(dp(20), dp(18), dp(20), dp(6))
                }
                tv.text = item.text.uppercase(Locale.ENGLISH)
                return tv
            }
            // Rows are single custom drawn views. Binding is only an
            // assignment plus invalidate: every string, color and
            // prebuilt text layout was computed in buildItems. There
            // is no entrance animation on rows anymore (removed in
            // v1.0.3: it fought recycling and cost frames during
            // scrolls). The factory reference lets a row rebuild its
            // layouts if it ever lands at a different width.
            val display = (item as Item.Display).display
            val rv = if (convertView is RowView) convertView else RowView(this@MainActivity)
            rv.layoutFactory = rowLayoutFactory
            rv.setItem(display, iconBitmaps)
            return rv
        }
    }

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
        buildUi()
        armSnifferOnce()
        updatePermState()
        // Load first: the screen populates straight from the vault, then
        // the silent sync tops it up. Display never waits on sync.
        if (PermUtil.hasCallLogRead(this)) loadVaultOnly()
        if (PermUtil.hasAll(this)) refresh(silent = true)
    }

    override fun onResume() {
        super.onResume()
        if (PermUtil.hasCallLogRead(this)) {
            armSnifferOnce()
            refresh(silent = true)
        }
        updatePermState()
    }

    /**
     * Arm the sniffer at most once per process from the activity
     * (v1.0.6). Arming re-schedules the job ids, and scheduling an id
     * that is currently running stops that job, so onCreate, onResume
     * and permission grants all scheduling unconditionally was part
     * of the sniffer's stop cycle. The boot receiver still arms
     * unguarded after a real boot.
     */
    private fun armSnifferOnce() {
        if (SnifferScheduler.sessionArmed.compareAndSet(false, true)) {
            SnifferScheduler.scheduleAll(this)
        }
    }

    override fun onDestroy() {
        // A debounced search rebuild must not outlive the activity.
        searchRunnable?.let { searchHandler.removeCallbacks(it) }
        searchRunnable = null
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (drawerOpen) {
            closeDrawer()
            return
        }
        super.onBackPressed()
    }

    // ------------------------------------------------------------------ UI

    private fun buildUi() {
        frame = FrameLayout(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.bg))
        }
        frame.addView(content, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // Top bar
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(10), dp(20), dp(6))
        }
        val burger = TextView(this).apply {
            text = "☰"
            textSize = 21f
            setTextColor(color(R.color.text))
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            isClickable = true
            isFocusable = true
            pressFeedback()
            setOnClickListener { if (drawerOpen) closeDrawer() else openDrawer() }
        }
        topBar.addView(burger)
        val title = TextView(this).apply {
            text = "Call Log Vault"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.text))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(4) }
        }
        topBar.addView(title)
        watchingBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        val dot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color(R.color.green))
            }
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8))
        }
        watchingBox.addView(dot)
        watchingBox.addView(TextView(this).apply {
            text = "Watching"
            textSize = 12f
            setTextColor(color(R.color.muted))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(6) }
        })
        topBar.addView(watchingBox)
        content.addView(topBar)

        // Search pill
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(color(R.color.input_fill))
            }
            setPadding(dp(16), 0, dp(8), 0)
        }
        val pillParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(48)
        ).apply {
            marginStart = dp(20); marginEnd = dp(20)
            topMargin = dp(8); bottomMargin = dp(4)
        }
        val searchIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_search)
            setColorFilter(color(R.color.muted))
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
        }
        pill.addView(searchIcon)
        searchEdit = EditText(this).apply {
            hint = "Search name or number"
            textSize = 15f
            setSingleLine(true)
            background = null
            setTextColor(color(R.color.text))
            setHintTextColor(color(R.color.muted))
            setPadding(dp(10), 0, dp(4), 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString() ?: ""
                    clearBtn.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
                    scheduleSearchRebuild()
                }
            })
        }
        pill.addView(searchEdit)
        clearBtn = TextView(this).apply {
            text = "✕"
            textSize = 14f
            setTextColor(color(R.color.muted))
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(8), dp(10), dp(8))
            visibility = View.GONE
            isClickable = true
            pressFeedback()
            setOnClickListener { searchEdit.text.clear() }
        }
        pill.addView(clearBtn)
        content.addView(pill, pillParams)

        // Filter chips
        chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        chipScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(dp(20), 0, dp(12), 0)
            addView(chipRow)
        }
        val chipParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8); bottomMargin = dp(6) }
        content.addView(chipScroll, chipParams)
        buildChips()

        // List area: list, empty state, permission panel stacked
        val listArea = FrameLayout(this)
        listView = ListView(this).apply {
            adapter = this@MainActivity.adapter
            divider = null
            isScrollingCacheEnabled = false
            cacheColorHint = 0
            // v1.0.5: the pressed highlight lives here, one drawable
            // for the whole list, drawn behind the (transparent) rows.
            // Rows no longer carry individual ripple backgrounds.
            selector = UiKit.rowSelector(this@MainActivity)
            setOnItemClickListener { _, _, position, _ ->
                when (val item = items.getOrNull(position)) {
                    is Item.Display -> openPerson(item.display.numberNorm)
                    else -> {}
                }
            }
        }
        listArea.addView(listView)
        emptyView = TextView(this).apply {
            textSize = 14f
            setTextColor(color(R.color.muted))
            gravity = Gravity.CENTER
            setPadding(dp(36), 0, dp(36), 0)
            visibility = View.GONE
        }
        listArea.addView(emptyView)
        permPanel = buildPermPanel()
        permPanel.visibility = View.GONE
        listArea.addView(permPanel)
        content.addView(listArea, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        buildDrawer()
        setContentView(frame)
    }

    private fun buildPermPanel(): LinearLayout {
        val panelBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(36), dp(40), dp(36), 0)
        }
        val iconHolder = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color(R.color.tile_bg))
            }
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(72))
            val iv = ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_nav_dialer)
                setColorFilter(color(R.color.tile_icon))
                setPadding(dp(18), dp(18), dp(18), dp(18))
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            }
            addView(iv)
        }
        panelBox.addView(iconHolder)
        panelBox.addView(TextView(this).apply {
            text = "Save every call"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.text))
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, 0)
        })
        panelBox.addView(TextView(this).apply {
            text = "To save your calls before the phone trims the list at 2000, " +
                "the app needs call log access. Everything stays on this phone. " +
                "The app has no internet permission at all.\n\n" +
                "Backups live outside this app, in a folder you choose, so clearing " +
                "app data or uninstalling never deletes a backup file. This vault " +
                "can be refilled any time with Restore from ZIP."
            textSize = 14f
            setTextColor(color(R.color.muted))
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        })
        val btn = Button(this).apply {
            text = "Allow call log access"
            isAllCaps = false
            textSize = 15f
            setTextColor(color(R.color.on_primary))
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(color(R.color.primary))
            }
            setPadding(dp(22), dp(10), dp(22), dp(10))
            setOnClickListener { explainAndRequestPermissions() }
        }
        panelBox.addView(btn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(22) })
        return panelBox
    }

    private fun updatePermState() {
        if (!::permPanel.isInitialized) return
        val has = PermUtil.hasCallLogRead(this)
        permPanel.visibility = if (has) View.GONE else View.VISIBLE
        listView.visibility = if (has) View.VISIBLE else View.GONE
        watchingBox.visibility = if (has) View.VISIBLE else View.GONE
        if (!has) emptyView.visibility = View.GONE
    }

    // -------------------------------------------------------------- Drawer

    private fun buildDrawer() {
        drawerW = dp(300)
        scrim = View(this).apply {
            setBackgroundColor(0x66000000.toInt())
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            isClickable = true
            isFocusable = true
            setOnClickListener { closeDrawer() }
        }
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.card))
            elevation = dp(8).toFloat()
            layoutParams = FrameLayout.LayoutParams(drawerW, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.START)
            translationX = -drawerW.toFloat()
        }
        panel.addView(drawerHeader())

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(16))
        }
        body.addView(statsBlock())

        body.addView(eyebrow("BROWSE"))
        body.addView(drawerRow(R.drawable.ic_nav_log, "Call log", null) {
            closeDrawer()
            setMode(people = false)
        }.first)
        body.addView(drawerRow(R.drawable.ic_nav_people, "People", null) {
            closeDrawer()
            setMode(people = true)
        }.first)

        body.addView(eyebrow("BACKUP AND RESTORE"))
        body.addView(drawerRow(R.drawable.ic_nav_backup, "Backup to ZIP", null) {
            closeDrawer()
            backupInfoDialog()
        }.first)
        body.addView(drawerRow(R.drawable.ic_nav_restore, "Restore from ZIP", null) {
            closeDrawer()
            launchImport()
        }.first)
        body.addView(drawerRow(R.drawable.ic_nav_dialer, "Restore to dialer", null) {
            closeDrawer()
            chooseRestoreMode()
        }.first)
        val autoRow = drawerRow(R.drawable.ic_nav_folder, "Auto backup folder", "") {
            closeDrawer()
            launchAutoFolderPicker()
        }
        autoSubtitle = autoRow.second
        body.addView(autoRow.first)
        val scheduleRow = drawerRow(R.drawable.ic_nav_backup, "Backup schedule", "") {
            closeDrawer()
            showBackupScheduleDialog()
        }
        scheduleSubtitle = scheduleRow.second
        body.addView(scheduleRow.first)

        body.addView(eyebrow("APP"))
        body.addView(drawerRow(R.drawable.ic_nav_sync, "Sync now", null) {
            closeDrawer()
            manualSync()
        }.first)
        body.addView(drawerRow(R.drawable.ic_nav_info, "Status and about", null) {
            closeDrawer()
            showInfo()
        }.first)

        body.addView(eyebrow("THEME"))
        body.addView(themeSegment())

        val scroll = ScrollView(this)
        scroll.addView(body)
        panel.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        panel.addView(drawerSupportCard())

        frame.addView(scrim)
        frame.addView(panel)
        updateAutoRow()
        updateScheduleRow()
    }

    /**
     * Theme presets as drawer segments: four equal
     * buttons in one rounded container. Tapping one saves the preset
     * and recreates the activity. The Appearance popup dialog is gone.
     */
    private fun themeSegment(): LinearLayout {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(2), dp(20), dp(6))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(color(R.color.input_fill))
                setStroke(dp(1), color(R.color.border))
            }
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val presets = listOf(
            UiKit.APPEARANCE_LIGHT to "Light",
            UiKit.APPEARANCE_DARK to "Dark",
            UiKit.APPEARANCE_AMOLED to "AMOLED",
            UiKit.APPEARANCE_AUTO to "Auto"
        )
        val current = UiKit.appearance(this)
        for ((value, label) in presets) {
            val selected = value == current
            val seg = TextView(this).apply {
                text = label
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(9), dp(6), dp(9))
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                if (selected) {
                    background = GradientDrawable().apply {
                        cornerRadius = dp(16).toFloat()
                        setColor(color(R.color.pill_sel_bg))
                        setStroke(dp(1), color(R.color.pill_sel_border))
                    }
                    setTextColor(color(R.color.pill_sel_text))
                    typeface = UiKit.mediumTypeface
                } else {
                    setTextColor(color(R.color.pill_unsel_text))
                }
                isClickable = true
                isFocusable = true
                pressFeedback()
                setOnClickListener {
                    if (UiKit.appearance(this@MainActivity) != value) {
                        UiKit.setAppearance(this@MainActivity, value)
                        recreate()
                    } else {
                        closeDrawer()
                    }
                }
            }
            row.addView(seg)
        }
        wrap.addView(row)
        return wrap
    }

    private fun drawerHeader(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(20), dp(20), dp(20), dp(16))
        addView(FrameLayout(this@MainActivity).apply {
            background = resources.getDrawable(R.drawable.tile_squircle, theme)
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_nav_dialer)
                setColorFilter(color(R.color.tile_icon))
                scaleType = ImageView.ScaleType.CENTER
                setPadding(dp(11), dp(11), dp(11), dp(11))
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            })
        })
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(14) }
            addView(TextView(this@MainActivity).apply {
                text = "Call Log Vault"
                textSize = 17f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(color(R.color.text))
            })
            addView(TextView(this@MainActivity).apply {
                text = "Version " + versionName() + " · Fully offline"
                textSize = 12f
                setTextColor(color(R.color.muted))
            })
            addView(TextView(this@MainActivity).apply {
                text = "Imagined by Arjun"
                textSize = 12f
                setTextColor(color(R.color.muted))
            })
        })
    }

    private fun statsBlock(): LinearLayout {
        val block = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(color(R.color.surface))
            }
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(16); marginEnd = dp(16); topMargin = dp(4)
            }
        }
        fun statRow(label: String): TextView {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(5), 0, dp(5))
            }
            row.addView(TextView(this).apply {
                text = label
                textSize = 13f
                setTextColor(color(R.color.muted))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            val value = TextView(this).apply {
                text = "…"
                textSize = 13f
                setTextColor(color(R.color.text))
                typeface = UiKit.mediumTypeface
                gravity = Gravity.END
            }
            row.addView(value)
            block.addView(row)
            return value
        }
        statTotal = statRow("Total saved")
        statOldest = statRow("Oldest saved")
        statTalk = statRow("Total talk time")
        statPhone = statRow("In phone now")
        statSync = statRow("Last sync")
        statAuto = statRow("Auto backup")
        return block
    }

    private fun eyebrow(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 11f
        setTypeface(typeface, Typeface.BOLD)
        letterSpacing = 0.18f
        setTextColor(color(R.color.eyebrow))
        setPadding(dp(20), dp(18), dp(20), dp(8))
    }

    private fun drawerRow(
        icon: Int,
        label: String,
        withSubtitle: String?,
        onClick: () -> Unit
    ): Pair<LinearLayout, TextView?> {
        var subtitle: TextView? = null
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            isClickable = true
            isFocusable = true
            pressFeedback()
        }
        row.addView(ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            setBackgroundResource(R.drawable.tile_squircle)
            setImageResource(icon)
            setColorFilter(color(R.color.tile_icon))
            scaleType = ImageView.ScaleType.CENTER
            setPadding(dp(9), dp(9), dp(9), dp(9))
        })
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(16) }
            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 16f
                setTextColor(color(R.color.text))
            })
            if (withSubtitle != null) {
                val sub = TextView(this@MainActivity).apply {
                    textSize = 12f
                    setTextColor(color(R.color.muted))
                    setSingleLine(true)
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                subtitle = sub
                addView(sub)
            }
        })
        row.setOnClickListener { onClick() }
        return row to subtitle
    }

    private fun drawerSupportCard(): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(color(R.color.bg))
            }
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(16); marginEnd = dp(16)
                topMargin = dp(8); bottomMargin = dp(16)
            }
            isClickable = true
            isFocusable = true
            pressFeedback()
        }
        card.addView(TextView(this).apply {
            text = "☕"
            textSize = 22f
            gravity = Gravity.CENTER
        })
        card.addView(TextView(this).apply {
            text = "Support the developer"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.text))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(12) }
        })
        card.setOnClickListener {
            closeDrawer()
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SUPPORT_URL)))
            } catch (e: Exception) {
                toast("No browser found")
            }
        }
        return card
    }

    /**
     * Drawer motion, kept deliberately plain for smoothness: the content
     * underneath does not move at all, and only the panel slides on a
     * hardware layer with a decelerate curve. Animating the whole
     * content (the old scale and shift) forced a full screen redraw of
     * the list on every frame and made opening and closing jittery.
     */
    private fun panelAnimator(toX: Float, durationMs: Long): ObjectAnimator {
        val anim = ObjectAnimator.ofFloat(panel, "translationX", panel.translationX, toX)
        anim.duration = durationMs
        anim.interpolator = DecelerateInterpolator()
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationStart(animation: android.animation.Animator) {
                panel.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            }

            override fun onAnimationEnd(animation: android.animation.Animator) {
                panel.setLayerType(View.LAYER_TYPE_NONE, null)
            }

            override fun onAnimationCancel(animation: android.animation.Animator) {
                panel.setLayerType(View.LAYER_TYPE_NONE, null)
            }
        })
        return anim
    }

    private fun openDrawer() {
        if (drawerOpen) return
        drawerOpen = true
        updateAutoRow()
        updateScheduleRow()
        scrim.visibility = View.VISIBLE
        scrim.alpha = 0f
        try {
            window.decorView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        } catch (ignored: Exception) {
        }
        panelAnimator(0f, 240).start()
        ObjectAnimator.ofFloat(scrim, "alpha", 0f, 1f).apply {
            duration = 240
            interpolator = DecelerateInterpolator()
            start()
        }
    }

    private fun closeDrawer() {
        if (!drawerOpen) return
        drawerOpen = false
        panelAnimator(-drawerW.toFloat(), 200).start()
        val fade = ObjectAnimator.ofFloat(scrim, "alpha", 1f, 0f).apply {
            duration = 200
            interpolator = DecelerateInterpolator()
        }
        fade.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (!drawerOpen) scrim.visibility = View.GONE
            }
        })
        fade.start()
    }

    private fun updateAutoRow() {
        val sub = autoSubtitle ?: return
        when {
            !AutoBackup.hasFolder(this) -> {
                sub.text = "Not set"
                sub.visibility = View.VISIBLE
            }
            AutoBackup.hasError(this) -> {
                sub.text = "Needs attention"
                sub.visibility = View.VISIBLE
            }
            else -> {
                sub.text = AutoBackup.folderDisplayName(this)
                sub.visibility = View.VISIBLE
            }
        }
        if (::statAuto.isInitialized) {
            statAuto.text = autoBackupStatText()
        }
    }

    private fun updateScheduleRow() {
        val sub = scheduleSubtitle ?: return
        sub.text = VaultLogic.backupScheduleName(AutoBackup.schedule(this))
        sub.visibility = View.VISIBLE
    }

    /**
     * Single choice dialog for the backup schedule (v1.0.8). Smart
     * is the original throttle and stays the default; the others are
     * plainer promises about how often the rolling file is rewritten.
     * The choice takes effect on the next scheduled auto backup run.
     */
    private fun showBackupScheduleDialog() {
        val schedules = listOf(
            VaultLogic.BACKUP_SCHEDULE_SMART,
            VaultLogic.BACKUP_SCHEDULE_EVERY_CALL,
            VaultLogic.BACKUP_SCHEDULE_EVERY_6H,
            VaultLogic.BACKUP_SCHEDULE_DAILY
        )
        val labels = arrayOf(
            "Smart (recommended)",
            VaultLogic.backupScheduleName(VaultLogic.BACKUP_SCHEDULE_EVERY_CALL),
            VaultLogic.backupScheduleName(VaultLogic.BACKUP_SCHEDULE_EVERY_6H),
            VaultLogic.backupScheduleName(VaultLogic.BACKUP_SCHEDULE_DAILY)
        )
        val current = schedules.indexOf(AutoBackup.schedule(this)).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Backup schedule")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                AutoBackup.setSchedule(this, schedules[which])
                updateScheduleRow()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun autoBackupStatText(): String {
        if (!AutoBackup.hasFolder(this)) return "Not set"
        if (AutoBackup.hasError(this)) return "Needs attention"
        val ms = AutoBackup.lastWriteMs(this)
        if (ms <= 0) return "Waiting"
        return VaultLogic.formatDateTime(ms).substringBefore(",") + " · " +
            AutoBackup.lastCount(this) + " calls"
    }

    private fun setMode(people: Boolean) {
        peopleMode = people
        chipScroll.visibility = if (people) View.GONE else View.VISIBLE
        rebuildItems()
    }

    // --------------------------------------------------------------- Chips

    private fun buildChips() {
        chipRow.removeAllViews()
        val filters = listOf(
            VaultLogic.Filter.ALL to "All",
            VaultLogic.Filter.INCOMING to "Incoming",
            VaultLogic.Filter.OUTGOING to "Outgoing",
            VaultLogic.Filter.MISSED to "Missed",
            VaultLogic.Filter.REJECTED to "Rejected",
            VaultLogic.Filter.BLOCKED to "Blocked",
            VaultLogic.Filter.UNKNOWN to "Unknown"
        )
        for ((f, label) in filters) {
            chipRow.addView(makeChip(label, filter == f) {
                filter = f
                buildChips()
                rebuildItems()
            })
        }
    }

    private fun makeChip(label: String, selected: Boolean, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(7), dp(14), dp(7))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(color(if (selected) R.color.pill_sel_bg else R.color.pill_unsel_bg))
                setStroke(dp(1), color(if (selected) R.color.pill_sel_border else R.color.pill_unsel_border))
            }
            setTextColor(color(if (selected) R.color.pill_sel_text else R.color.pill_unsel_text))
            isClickable = true
            pressFeedback()
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(8) }
        }
    }

    // ---------------------------------------------------------- Permissions

    private fun explainAndRequestPermissions() {
        AlertDialog.Builder(this)
            .setTitle("Call log permission")
            .setMessage(
                "Call Log Vault copies each new call into its own list on this phone, " +
                    "so calls stay saved after the phone trims its own list. " +
                    "No data leaves the phone. You can remove the permission any time in Settings."
            )
            .setPositiveButton("Continue") { _, _ ->
                requestPermissions(PermUtil.missing(this), REQ_PERMISSIONS)
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMISSIONS) {
            if (PermUtil.hasCallLogRead(this)) {
                armSnifferOnce()
                updatePermState()
                refresh(silent = false)
            } else {
                toast("Call log permission is needed to save calls")
            }
        }
    }

    // ---------------------------------------------------------------- Sync

    private fun manualSync() {
        if (!PermUtil.hasCallLogRead(this)) {
            explainAndRequestPermissions()
            return
        }
        refresh(silent = false)
    }

    /**
     * Sync entry point. Wedge proof: in v1.0.1 the background thread had
     * no try/finally, so any throw before its UI post left [syncing]
     * true forever and every later sync, including Sync now, silently
     * did nothing. Now the whole thread body is wrapped, the finally
     * always posts the flag reset, a non silent caller always gets
     * feedback, and a flag older than 120 seconds is treated as wedged
     * and reset instead of blocking forever.
     *
     * v1.0.4: the work runs on the shared VaultExecutor (one serial
     * worker for all vault background work), the sync no longer runs
     * an auto backup write at its end (that write could block this
     * thread, and so this dialog, for as long as a storage provider
     * took), and the progress dialog has a hard 30 second cap.
     *
     * v1.0.6: a sync that changed nothing in the vault skips the
     * full rebuild entirely (VaultLogic.shouldApplyRebuild against
     * the snapshot from the last apply): no grouping, no item build,
     * no notifyDataSetChanged, just the last sync text and toasts.
     */
    private fun refresh(silent: Boolean) {
        if (!PermUtil.hasCallLogRead(this)) return
        if (syncing) {
            val ageMs = System.currentTimeMillis() - syncStartedMs
            if (ageMs < SYNC_WEDGE_MS) {
                if (!silent) toast("Sync is already running")
                return
            }
            // Stale flag: an earlier sync died without resetting. Recover.
            syncing = false
        }
        syncing = true
        syncStartedMs = System.currentTimeMillis()
        val progress = if (silent) null else showProgress("Syncing calls")
        if (progress != null) {
            // Hard cap on the dialog: it can never spin forever. If the
            // sync is still running when the cap hits, the dialog goes
            // away, the user is told, and the sync finishes in the
            // background (its completion toast still fires on landing).
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    if (progress.isShowing) {
                        progress.dismiss()
                        toast("Sync is taking longer than usual, it will finish in the background")
                    }
                } catch (ignored: Exception) {
                }
            }, SYNC_DIALOG_CAP_MS)
        }
        VaultExecutor.service.execute {
            try {
                val helper = VaultDb.get(this)
                val db = helper.writableDatabase
                val empty = helper.count(db) == 0L
                val result = SyncEngine.sync(this, windowed = !empty)
                // Sync is pure now (no auto backup inside it): new
                // calls accumulate toward the next auto backup and
                // schedule its separate job.
                if (VaultLogic.shouldScheduleAutoBackup(result.added)) {
                    AutoBackup.addNewSince(this, result.added)
                    AutoBackupScheduler.schedule(this)
                }
                val newCount = helper.count(db)
                if (!VaultLogic.shouldApplyRebuild(
                        snapshotCount, newCount, result.added, allRecords.isNotEmpty()
                    )
                ) {
                    // No-change skip (v1.0.6): the sync added nothing
                    // and the vault holds exactly what the last full
                    // apply put on screen, so grouping 2000+ records,
                    // rebuilding every item and notifying the adapter
                    // would redraw an identical list for nothing.
                    // Silent syncs run often (every resume, every
                    // sniffer catch up), and each one used to pay
                    // that full rebuild. Only the last sync text
                    // moves, plus the usual manual sync feedback.
                    val lastSync = SyncEngine.lastSyncMs(this)
                    runOnUiThread {
                        statSync.text = if (lastSync > 0) {
                            VaultLogic.formatDateTime(lastSync).substringBefore(",")
                        } else {
                            "Not yet"
                        }
                        if (result.error != null && !silent) {
                            toast(result.error)
                        }
                        if (!silent && result.error == null) {
                            toast("Sync done: " + result.added + " new calls saved")
                        }
                    }
                } else {
                    val records = helper.all(db)
                    val oldest = helper.oldestDate(db)
                    val totalDur = helper.totalDuration(db)
                    val sysCount = SyncEngine.systemCount(this)
                    val peopleList = VaultLogic.groupPeople(records)
                    val built = buildItems(
                        records, peopleList, peopleMode, filter, query, avatarColors, currentListWidthPx()
                    )
                    runOnUiThread {
                        // The list may already be populated by loadVaultOnly:
                        // this is simply the second, fresher update.
                        applyVaultData(records, peopleList, built, oldest, totalDur, sysCount)
                        if (result.error != null && !silent) {
                            toast(result.error)
                        }
                        if (!silent && result.error == null) {
                            toast("Sync done: " + result.added + " new calls saved")
                        }
                    }
                }
            } catch (e: Exception) {
                SyncEngine.recordSyncError(this, e.message ?: "Sync failed")
                if (!silent) {
                    runOnUiThread {
                        toast("Sync failed: " + (e.message ?: "unknown error"))
                    }
                }
            } finally {
                // Always release the flag and the progress dialog, on the
                // UI thread, whatever happened above.
                runOnUiThread {
                    syncing = false
                    progress?.dismiss()
                }
            }
        }
    }

    /**
     * Load first: populate the screen straight from the vault database
     * WITHOUT running SyncEngine.sync. Decoupled from sync on purpose:
     * in v1.0.2 the sync thread's UI callback was the only path that
     * filled allRecords, so after a theme recreate any delay, coalescing
     * or silent failure in that thread left an empty list until a full
     * restart. This load uses its own flag, never touches [syncing], and
     * always runs on activity creation when permission is present.
     */
    private fun loadVaultOnly() {
        if (!PermUtil.hasCallLogRead(this)) return
        if (loadingVault) return
        loadingVault = true
        VaultExecutor.service.execute {
            try {
                val helper = VaultDb.get(this)
                val db = helper.readableDatabase
                val records = helper.all(db)
                val oldest = helper.oldestDate(db)
                val totalDur = helper.totalDuration(db)
                val sysCount = SyncEngine.systemCount(this)
                val peopleList = VaultLogic.groupPeople(records)
                val built = buildItems(
                    records, peopleList, peopleMode, filter, query, avatarColors, currentListWidthPx()
                )
                runOnUiThread {
                    loadingVault = false
                    applyVaultData(records, peopleList, built, oldest, totalDur, sysCount)
                }
            } catch (e: Exception) {
                runOnUiThread { loadingVault = false }
            }
        }
    }

    /**
     * Fill records, people, stats and the list from vault data alone.
     * The people grouping and the item list arrive prebuilt from the
     * background thread; this only assigns fields, refreshes the
     * drawer stats, and applies the items on the UI thread.
     */
    private fun applyVaultData(
        records: List<VaultLogic.CallRecord>,
        peopleList: List<VaultLogic.PersonSummary>,
        builtItems: List<Item>,
        oldest: Long,
        totalDur: Long,
        sysCount: Int
    ) {
        allRecords = records
        people = peopleList
        // Snapshot for the no-change skip in refresh(): after this
        // apply, the screen shows exactly this vault state.
        snapshotCount = records.size.toLong()
        snapshotMaxDateMs = records.firstOrNull()?.dateMs ?: 0L
        statTotal.text = records.size.toString()
        statOldest.text = if (oldest > 0) VaultLogic.formatDateTime(oldest).substringBefore(",") else "None yet"
        statTalk.text = VaultLogic.formatDuration(totalDur)
        statPhone.text = if (sysCount >= 0) sysCount.toString() else "Unknown"
        val lastSync = SyncEngine.lastSyncMs(this)
        statSync.text = if (lastSync > 0) VaultLogic.formatDateTime(lastSync).substringBefore(",") else "Not yet"
        statAuto.text = autoBackupStatText()
        updateAutoRow()
        applyItems(builtItems)
    }

    /**
     * Build the adapter item list from data alone: every RowDisplay
     * string and color is computed here, and since v1.0.5 so is every
     * row's prebuilt text layout (RowLayoutFactory, for [listWidthPx]).
     * This touches no views (all formatting goes through VaultLogic,
     * and the palette arrives as resolved color ints), so refresh and
     * the vault load run it on the vault worker thread and post only
     * the finished list to the UI thread through [applyItems]. Chip
     * and mode changes still rebuild through [rebuildItems] on the
     * UI thread (single user paced actions); search typing goes
     * through the debounced worker path in [scheduleSearchRebuild].
     */
    private fun buildItems(
        records: List<VaultLogic.CallRecord>,
        peopleList: List<VaultLogic.PersonSummary>,
        mode: Boolean,
        activeFilter: VaultLogic.Filter,
        activeQuery: String,
        palette: UiKit.AvatarColors,
        listWidthPx: Int
    ): List<Item> {
        val now = System.currentTimeMillis()
        return if (mode) {
            val q = activeQuery.trim()
            val qDigits = q.filter { it.isDigit() }
            val filteredPeople = peopleList.filter { p ->
                q.isEmpty() ||
                    p.displayName.lowercase(Locale.ROOT).contains(q.lowercase(Locale.ROOT)) ||
                    (qDigits.isNotEmpty() && VaultLogic.digitsOnly(p.numberNorm).contains(qDigits))
            }
            filteredPeople.mapIndexed { i, p ->
                val (initials, bg, fg) = avatarColorsFor(p.displayName, p.numberRaw, p.numberNorm, palette)
                val display = RowDisplay(
                    title = p.displayName,
                    subtitle = VaultLogic.personSubtitle(p.numberRaw, p.callCount, p.totalDurationSec),
                    rightTop = VaultLogic.personRightTop(p.lastDateMs, now),
                    rightBottom = VaultLogic.formatTime(p.lastDateMs),
                    avatarInitials = initials,
                    avatarBg = bg,
                    avatarFg = fg,
                    typeIcon = -1,
                    showDivider = i < filteredPeople.size - 1,
                    numberNorm = p.numberNorm
                )
                rowLayoutFactory.buildInto(display, listWidthPx)
                Item.Display(display)
            }
        } else {
            val filtered = VaultLogic.filterCalls(records, activeFilter, activeQuery)
            val out = ArrayList<Item>()
            var lastHeader = ""
            for ((i, r) in filtered.withIndex()) {
                val header = VaultLogic.dateHeaderLabel(r.dateMs, now)
                if (header != lastHeader) {
                    out.add(Item.Header(header))
                    lastHeader = header
                }
                val next = filtered.getOrNull(i + 1)
                val divider = next != null &&
                    VaultLogic.dateHeaderLabel(next.dateMs, now) == header
                val (initials, bg, fg) = avatarColorsFor(r.name, r.numberRaw, r.numberNorm, palette)
                val display = RowDisplay(
                    title = VaultLogic.effectiveName(r),
                    subtitle = VaultLogic.callSubtitle(r.name, r.numberRaw, r.type),
                    rightTop = VaultLogic.formatTime(r.dateMs),
                    rightBottom = VaultLogic.callRightBottom(r.durationSec),
                    avatarInitials = initials,
                    avatarBg = bg,
                    avatarFg = fg,
                    typeIcon = r.type,
                    showDivider = divider,
                    numberNorm = r.numberNorm,
                    hasIcon = r.type in RowView.ICON_TYPES
                )
                rowLayoutFactory.buildInto(display, listWidthPx)
                out.add(Item.Display(display))
            }
            out
        }
    }

    /**
     * Search rebuilds, debounced and off the UI thread (v1.0.6).
     * Typing used to rebuild the whole item list synchronously inside
     * the text watcher: a filter over every record plus a layout
     * build for every visible match, per keystroke, on the main
     * thread. Now a keystroke only updates the query, bumps the
     * token and re-arms a short timer. When the timer fires, the
     * filter and item build run on the vault worker, and the result
     * is applied on the UI thread only when its token is still the
     * latest, so a slow stale build can never overwrite a newer one.
     */
    private fun scheduleSearchRebuild() {
        val token = ++searchToken
        searchRunnable?.let { searchHandler.removeCallbacks(it) }
        val runnable = Runnable {
            val q = query
            val mode = peopleMode
            val activeFilter = filter
            VaultExecutor.service.execute {
                val built = buildItems(
                    allRecords, people, mode, activeFilter, q, avatarColors, currentListWidthPx()
                )
                runOnUiThread {
                    if (token == searchToken) applyItems(built)
                }
            }
        }
        searchRunnable = runnable
        searchHandler.postDelayed(runnable, SEARCH_DEBOUNCE_MS)
    }

    /**
     * Cancel any pending or in flight search rebuild. Synchronous
     * rebuilds (chip clicks, mode switches) call this through
     * [rebuildItems] so an older debounced result can never land
     * after them and resurrect a stale filter or query.
     */
    private fun invalidateSearch() {
        searchToken++
        searchRunnable?.let { searchHandler.removeCallbacks(it) }
        searchRunnable = null
    }

    /** UI thread rebuild for user paced changes (chips, mode). */
    private fun rebuildItems() {
        invalidateSearch()
        applyItems(
            buildItems(allRecords, people, peopleMode, filter, query, avatarColors, currentListWidthPx())
        )
    }

    /** UI thread only: swap in a prebuilt item list and refresh the empty state. */
    private fun applyItems(newItems: List<Item>) {
        items = newItems
        adapter.notifyDataSetChanged()
        // Empty states
        if (!PermUtil.hasCallLogRead(this)) {
            emptyView.visibility = View.GONE
            return
        }
        if (items.isEmpty()) {
            emptyView.text = when {
                allRecords.isEmpty() ->
                    "No calls saved yet.\nNew calls will be copied here automatically.\n\n" +
                        "Backups live outside this app, in a folder you choose, " +
                        "so clearing app data or uninstalling never deletes a backup file."
                peopleMode -> "No people match this search."
                query.isNotBlank() -> "Nothing matches this search."
                else -> "Nothing in this view yet."
            }
            emptyView.visibility = View.VISIBLE
        } else {
            emptyView.visibility = View.GONE
        }
    }

    private fun openPerson(numberNorm: String) {
        val intent = Intent(this, PersonActivity::class.java)
        intent.putExtra(PersonActivity.EXTRA_NUMBER_NORM, numberNorm)
        startActivity(intent)
    }

    // ------------------------------------------------------------- SAF flows

    private fun backupInfoDialog() {
        val helper = VaultDb.get(this)
        if (helper.count(helper.readableDatabase) == 0L) {
            toast("Nothing to back up yet")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Backup to ZIP")
            .setMessage(
                "The backup file is saved in the folder you pick next, outside this app. " +
                    "Clearing app data or uninstalling the app will not delete it."
            )
            .setPositiveButton("Choose folder") { _, _ -> launchExport() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun launchExport() {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.ENGLISH).format(Date())
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(Intent.EXTRA_TITLE, "call-log-vault-$stamp.zip")
        }
        startActivityForResult(intent, REQ_EXPORT)
    }

    private fun launchImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
        }
        startActivityForResult(intent, REQ_IMPORT)
    }

    private fun launchAutoFolderPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
        startActivityForResult(intent, REQ_FOLDER)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQ_FOLDER -> {
                val flags = (data.flags) and
                    (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                try {
                    contentResolver.takePersistableUriPermission(uri, flags)
                } catch (e: Exception) {
                    toast("Could not keep permission for that folder")
                    return
                }
                AutoBackup.setFolder(this, uri.toString())
                updateAutoRow()
                if (::statAuto.isInitialized) statAuto.text = autoBackupStatText()
                toast("Auto backup folder set: " + AutoBackup.folderDisplayName(this))
                // The first write runs as a scheduled auto backup job,
                // like every other write: on its own worker, under the
                // hard timeout, never on a raw thread here. A failure
                // surfaces as "Needs attention" on the drawer row and
                // in Status, instead of blocking anything.
                AutoBackup.requestImmediateWrite(this)
                AutoBackupScheduler.schedule(this)
            }
            REQ_EXPORT -> {
                val progress = showProgress("Writing backup ZIP")
                VaultExecutor.service.execute {
                    try {
                        val count = Backup.exportZip(this, uri)
                        runOnUiThread {
                            progress.dismiss()
                            showResult("Backup done", count.toString() + " calls written to the ZIP file.\n\n" +
                                "It lives in the folder you chose, outside this app, so clearing app data " +
                                "or uninstalling will not delete it.")
                            refresh(silent = true)
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            progress.dismiss()
                            showResult("Backup failed", e.message ?: "Unknown error")
                        }
                    }
                }
            }
            REQ_IMPORT -> {
                val progress = showProgress("Reading backup ZIP")
                VaultExecutor.service.execute {
                    val result = Backup.importZip(this, uri)
                    runOnUiThread {
                        progress.dismiss()
                        if (result.error != null) {
                            showResult("Restore ZIP failed", result.error)
                        } else {
                            showResult(
                                "Restore ZIP done",
                                "Added " + result.added + " calls to the vault, skipped " +
                                    result.skipped + " that were already saved."
                            )
                            refresh(silent = true)
                            AlertDialog.Builder(this)
                                .setTitle("Put calls back in the dialer?")
                                .setMessage("The calls are in the vault now. You can also put them back into the phone call list, so your dialer shows them.")
                                .setPositiveButton("Restore to Dialer") { _, _ -> chooseRestoreMode() }
                                .setNegativeButton("Later", null)
                                .show()
                        }
                    }
                }
            }
        }
    }

    // -------------------------------------------------------- Restore flows

    private fun chooseRestoreMode() {
        if (!PermUtil.has(this, android.Manifest.permission.WRITE_CALL_LOG)) {
            explainAndRequestPermissions()
            return
        }
        val helper = VaultDb.get(this)
        val total = helper.count(helper.readableDatabase)
        if (total == 0L) {
            toast("Nothing saved yet")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Restore to Dialer")
            .setItems(arrayOf("Latest 2000 calls", "All calls (" + total + ")")) { _, which ->
                if (which == 0) {
                    doRestore(latestOnly = true)
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("Restore all calls")
                        .setMessage(
                            "Your phone keeps only the newest 2000 entries in its call list. " +
                                "If you restore more than that, the phone will trim the oldest ones " +
                                "from the dialer again as new calls arrive. Nothing is lost: " +
                                "every call stays in this vault, with no limit."
                        )
                        .setPositiveButton("Restore all") { _, _ -> doRestore(latestOnly = false) }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun doRestore(latestOnly: Boolean) {
        val progress = showProgress("Restoring calls to the phone call list")
        VaultExecutor.service.execute {
            val helper = VaultDb.get(this)
            val db = helper.readableDatabase
            var records = helper.allAscending(db)
            if (latestOnly && records.size > 2000) {
                records = records.subList(records.size - 2000, records.size)
            }
            val result = SyncEngine.restore(this, records)
            runOnUiThread {
                progress.dismiss()
                if (result.error != null) {
                    showResult("Restore stopped", result.error)
                } else {
                    showResult(
                        "Restore done",
                        "Put " + result.inserted + " calls back into the phone call list, skipped " +
                            result.skipped + " that were already there."
                    )
                }
                refresh(silent = true)
            }
        }
    }

    // -------------------------------------------------------------- Dialogs

    private fun showInfo() {
        val helper = VaultDb.get(this)
        val db = helper.readableDatabase
        val count = helper.count(db)
        val oldest = helper.oldestDate(db)
        val lastSync = SyncEngine.lastSyncMs(this)
        val sb = StringBuilder()
        sb.append("Version: ").append(versionName()).append('\n')
        sb.append("Calls in vault: ").append(count).append('\n')
        if (oldest > 0) sb.append("Oldest saved: ").append(VaultLogic.formatDateTime(oldest)).append('\n')
        if (lastSync > 0) {
            sb.append("Last sync: ").append(VaultLogic.formatDateTime(lastSync))
                .append(" (added ").append(SyncEngine.lastSyncAdded(this)).append(")\n")
        } else {
            sb.append("Last sync: not yet\n")
        }
        sb.append("Auto save on new call: ")
            .append(if (SnifferScheduler.isTriggerScheduled(this)) "scheduled" else "not scheduled, open the app to rearm it")
            .append('\n')
        sb.append("Daily safety sync: ")
            .append(if (SnifferScheduler.isPeriodicScheduled(this)) "scheduled" else "not scheduled")
            .append('\n')
        sb.append("Auto backup folder: ")
            .append(if (AutoBackup.hasFolder(this)) AutoBackup.folderDisplayName(this) else "not set")
            .append('\n')
        if (AutoBackup.hasFolder(this)) {
            val ms = AutoBackup.lastWriteMs(this)
            sb.append("Last auto backup: ")
                .append(if (ms > 0) VaultLogic.formatDateTime(ms) else "not yet")
                .append('\n')
            if (AutoBackup.hasError(this)) sb.append("Auto backup status: needs attention\n")
        }
        val appearanceMode = UiKit.appearance(this)
        val appearanceText = if (appearanceMode == UiKit.APPEARANCE_AUTO) {
            // Never leave Auto ambiguous in Status: name the concrete
            // preset it currently resolves to as well.
            UiKit.appearanceName(appearanceMode) + " (" +
                UiKit.appearanceName(UiKit.concreteAppearance(this)) + ")"
        } else {
            UiKit.appearanceName(appearanceMode)
        }
        sb.append("Appearance: ").append(appearanceText).append('\n')
        sb.append("\nDiagnostics\n")
        sb.append("Vault entries in database: ").append(count).append('\n')
        sb.append("Last sync time: ")
            .append(if (lastSync > 0) VaultLogic.formatDateTime(lastSync) else "not yet")
            .append('\n')
        val syncError = SyncEngine.lastSyncError(this)
        sb.append("Last sync result: ").append(
            when {
                syncError.isNotEmpty() -> syncError
                lastSync > 0 -> "No error (added " + SyncEngine.lastSyncAdded(this) + ")"
                else -> "No error"
            }
        ).append('\n')
        sb.append("Auto backup folder: ")
            .append(
                if (AutoBackup.hasFolder(this)) "set (" + AutoBackup.folderDisplayName(this) + ")"
                else "not set"
            )
            .append('\n')
        if (AutoBackup.hasFolder(this)) {
            val autoMs = AutoBackup.lastWriteMs(this)
            sb.append("Last auto backup: ")
                .append(
                    if (autoMs > 0) {
                        VaultLogic.formatDateTime(autoMs) + ", " + AutoBackup.lastCount(this) + " records"
                    } else {
                        "not yet"
                    }
                )
                .append('\n')
            sb.append("Auto backup status: ")
                .append(if (AutoBackup.hasError(this)) "needs attention" else "ok")
                .append('\n')
        }
        sb.append("The vault has no limit and never deletes calls.\n")
        sb.append("Backup files live in folders you choose, outside this app, " +
            "so clearing data or uninstalling never deletes them.\n")
        sb.append("This app works fully offline and has no internet permission.\n")
        val crashSummary = CrashLog.summary(this)
        if (crashSummary != null) {
            sb.append("\nLast crash\n").append(crashSummary).append('\n')
        }
        sb.append("\nImagined by Arjun")
        val builder = AlertDialog.Builder(this)
            .setTitle("Status and about")
            .setMessage(sb.toString())
            .setPositiveButton("OK", null)
        if (crashSummary != null) {
            builder.setNegativeButton("View crash") { _, _ -> showCrashDetail() }
        }
        builder.show()
    }

    private fun showCrashDetail() {
        val trace = CrashLog.read(this) ?: return
        val tv = TextView(this).apply {
            text = trace
            textSize = 11f
            setTextColor(color(R.color.text))
            typeface = Typeface.MONOSPACE
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        val scroll = ScrollView(this)
        scroll.addView(tv)
        AlertDialog.Builder(this)
            .setTitle("Last crash")
            .setView(scroll)
            .setPositiveButton("OK", null)
            .setNegativeButton("Clear crash log") { _, _ ->
                CrashLog.clear(this)
                toast("Crash log cleared")
            }
            .show()
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.6"
    } catch (e: Exception) {
        "1.0.6"
    }

    private fun showProgress(msg: String): ProgressDialog {
        val p = ProgressDialog(this)
        p.setMessage(msg)
        p.setCancelable(false)
        p.show()
        return p
    }

    private fun showResult(title: String, msg: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val SUPPORT_URL = "https://viewing-graphs-requests-peaceful.trycloudflare.com/"
        private const val SYNC_WEDGE_MS = 120_000L
        private const val SYNC_DIALOG_CAP_MS = 30_000L
        private const val SEARCH_DEBOUNCE_MS = 120L
        private const val REQ_PERMISSIONS = 100
        private const val REQ_EXPORT = 2001
        private const val REQ_IMPORT = 2002
        private const val REQ_FOLDER = 3001
    }
}
