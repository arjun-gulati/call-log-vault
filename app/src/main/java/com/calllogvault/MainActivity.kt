package com.calllogvault

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
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
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
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
    private var watchToggle: ToggleView? = null
    private var autoRestoreToggle: ToggleView? = null
    private var autoRestoreRowView: LinearLayout? = null

    private var allRecords: List<VaultLogic.CallRecord> = emptyList()
    private var people: List<VaultLogic.PersonSummary> = emptyList()
    private var filter: VaultLogic.Filter = VaultLogic.Filter.ALL
    private var peopleMode = false
    private var query = ""
    private var syncing = false
    private var syncStartedMs = 0L
    private var loadingVault = false

    // Pull to refresh (v1.2.3, GitHub issue #5). One state machine
    // plus one chip for the list that backs both the Call log and
    // the People page; the gesture and visuals live further down,
    // next to the sync section they feed. v1.2.4 adds the state
    // label under the chip and result reporting for pull syncs.
    private lateinit var pullMachine: PullRefreshMachine
    private lateinit var pullBox: LinearLayout
    private lateinit var pullChip: FrameLayout
    private lateinit var pullChipIcon: ImageView
    private lateinit var pullLabel: TextView
    private var pullOffsetPx = 0f
    private var pullAnim: ValueAnimator? = null
    private var pullAnimToken = 0
    private var pullSpin: ObjectAnimator? = null
    private val pullSettleHandler = Handler(Looper.getMainLooper())
    private var pullSettleStartMs = 0L

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
        // Neither may the pull to refresh settle watch or its animators.
        pullSettleHandler.removeCallbacks(pullSettleCheck)
        pullAnim?.cancel()
        pullSpin?.cancel()
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

        // Pull to refresh chip (v1.2.3, GitHub issue #5): a small
        // circular reload badge that slides in over the top of the
        // list while the finger pulls. It rests fully above the list
        // area and is clipped by it, so it appears to emerge from
        // under the chips row. Every color comes from the theme
        // palette, so light, dark and AMOLED all agree.
        //
        // v1.2.4: the chip rides in a small vertical box with a
        // state label under it (Pull to sync / Release to sync /
        // Syncing…), so the pull says what it is doing instead of
        // only spinning. The label's words come from
        // pullStateLabel(); the machine is the only source.
        pullChipIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_nav_sync)
            setColorFilter(color(R.color.tile_icon))
            setPadding(dp(9), dp(9), dp(9), dp(9))
        }
        pullChip = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color(R.color.card))
            }
            elevation = dp(4).toFloat()
            addView(pullChipIcon, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT))
        }
        pullLabel = TextView(this).apply {
            textSize = 12f
            setTextColor(color(R.color.muted))
            gravity = Gravity.CENTER
        }
        pullBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            alpha = 0f
            visibility = View.GONE
            addView(pullChip, LinearLayout.LayoutParams(
                dp(PULL_CHIP_DP), dp(PULL_CHIP_DP)))
            addView(pullLabel, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2) })
        }
        listArea.addView(pullBox, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        installPullToRefresh()

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
        body.addView(watchSwitchRow())
        body.addView(autoRestoreSwitchRow())

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
        body.addView(amoledSwitchRow())

        val scroll = ScrollView(this)
        scroll.addView(body)
        panel.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        panel.addView(drawerSupportCard())

        frame.addView(scrim)
        frame.addView(panel)
        updateAutoRow()
        updateScheduleRow()
        updateWatchRows()
    }

    /**
     * Theme modes as drawer segments: three equal buttons in one
     * rounded container. The middle button is the Dark mode; its
     * label reads AMOLED while the switch under the segments is on,
     * because that is what Dark renders then. Tapping a segment
     * saves the mode and recreates the activity. The Appearance
     * popup dialog is gone.
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
        val darkLabel = if (UiKit.amoledForDark(this)) "AMOLED" else "Dark"
        val presets = listOf(
            UiKit.APPEARANCE_LIGHT to "Light",
            UiKit.APPEARANCE_DARK to darkLabel,
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
                    // Filled pill (v1.2.0): the primary blended over
                    // the card color at a flat tint, full strength
                    // primary border, no shadow.
                    background = GradientDrawable().apply {
                        cornerRadius = dp(16).toFloat()
                        setColor(
                            UiKit.blendOver(color(R.color.card), color(R.color.primary), 0.18f)
                        )
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

    /**
     * The AMOLED overlap switch, directly under the theme segments
     * and styled like the other drawer rows. Flipping it saves the
     * pref and recreates, the same way a segment tap does, so the
     * middle segment's label (Dark or AMOLED) and the rendered
     * theme both follow at once. Tapping the row itself flips the
     * switch too.
     */
    private fun amoledSwitchRow(): LinearLayout {
        val toggle = ToggleView(this)
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
            setImageResource(R.drawable.ic_nav_appearance)
            setColorFilter(color(R.color.tile_icon))
            scaleType = ImageView.ScaleType.CENTER
            setPadding(dp(9), dp(9), dp(9), dp(9))
        })
        row.addView(TextView(this).apply {
            text = "Use AMOLED for Dark"
            textSize = 16f
            setTextColor(color(R.color.text))
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginStart = dp(16) }
        })
        toggle.setChecked(UiKit.amoledForDark(this), animate = false)
        toggle.onCheckedChange = { checked ->
            if (UiKit.amoledForDark(this@MainActivity) != checked) {
                UiKit.setAmoledForDark(this@MainActivity, checked)
                recreate()
            }
        }
        row.addView(toggle)
        row.setOnClickListener { toggle.toggle() }
        return row
    }

    /**
     * A drawer switch row in the same shape as the AMOLED switch:
     * icon tile, a title with a short subtitle under it, and the
     * app's own ToggleView at the end (the framework Switch is gone
     * everywhere since v1.2.0). Tapping the row flips the toggle,
     * like the AMOLED row. The checked state is set before the
     * listener is attached, so building the row never fires it.
     */
    private fun switchRow(
        icon: Int,
        title: String,
        subtitle: String,
        checked: Boolean,
        onFlip: (Boolean) -> Unit
    ): Pair<LinearLayout, ToggleView> {
        val toggle = ToggleView(this)
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
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginStart = dp(16) }
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 16f
                setTextColor(color(R.color.text))
            })
            addView(TextView(this@MainActivity).apply {
                text = subtitle
                textSize = 12f
                setTextColor(color(R.color.muted))
            })
        })
        toggle.setChecked(checked, animate = false)
        toggle.onCheckedChange = { isChecked -> onFlip(isChecked) }
        row.addView(toggle)
        row.setOnClickListener { toggle.toggle() }
        return row to toggle
    }

    /**
     * The watch folder switch (v1.1.0), directly under the backup
     * schedule row. Off is the default and means the app behaves
     * exactly as before: the check is inert. Turning it on runs a
     * check straight away through the normal refresh, which is
     * where every later check rides along too.
     */
    private fun watchSwitchRow(): LinearLayout {
        val (row, toggle) = switchRow(
            R.drawable.ic_nav_folder,
            "Watch backup folder",
            "Import new backups that appear in your backup folder",
            WatchFolder.watchEnabled(this)
        ) { checked ->
            if (WatchFolder.watchEnabled(this@MainActivity) != checked) {
                WatchFolder.setWatchEnabled(this@MainActivity, checked)
                updateWatchRows()
                if (checked) refresh(silent = true)
            }
        }
        watchToggle = toggle
        return row
    }

    /**
     * The auto restore switch, directly under the watch switch.
     * Importing into the vault is quiet; writing into the phone
     * call log is not, so it is its own opt in, default off. The
     * row only works while watching is on and a backup folder is
     * set, and it dims to say so (see updateWatchRows); the saved
     * choice itself is never erased by dimming.
     */
    private fun autoRestoreSwitchRow(): LinearLayout {
        val (row, toggle) = switchRow(
            R.drawable.ic_nav_restore,
            "Restore imported calls to phone log",
            "New calls imported from the folder also appear in your dialer",
            WatchFolder.autoRestore(this)
        ) { checked ->
            if (WatchFolder.autoRestore(this@MainActivity) != checked) {
                WatchFolder.setAutoRestore(this@MainActivity, checked)
            }
        }
        autoRestoreToggle = toggle
        autoRestoreRowView = row
        row.setOnClickListener {
            if (WatchFolder.watchEnabled(this@MainActivity) && AutoBackup.hasFolder(this@MainActivity)) {
                toggle.toggle()
            }
        }
        return row
    }

    /** Dim and disable the auto restore row unless it can actually act. */
    private fun updateWatchRows() {
        val canRestore = WatchFolder.watchEnabled(this) && AutoBackup.hasFolder(this)
        autoRestoreRowView?.alpha = if (canRestore) 1f else 0.45f
        autoRestoreToggle?.isEnabled = canRestore
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
                setStroke(dp(1), color(R.color.border))
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
                setStroke(dp(1), color(R.color.border))
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
        updateWatchRows()
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
     * Single choice dialog for the backup schedule (v1.0.8), in the
     * custom dialog since v1.2.0: radio rows with the current
     * schedule filled. Smart is the original throttle and stays the
     * default; the others are plainer promises about how often the
     * rolling file is rewritten. The choice takes effect on the
     * next scheduled auto backup run.
     */
    private fun showBackupScheduleDialog() {
        val schedules = listOf(
            VaultLogic.BACKUP_SCHEDULE_SMART,
            VaultLogic.BACKUP_SCHEDULE_EVERY_CALL,
            VaultLogic.BACKUP_SCHEDULE_EVERY_6H,
            VaultLogic.BACKUP_SCHEDULE_DAILY
        )
        val labels = listOf(
            "Smart (recommended)",
            VaultLogic.backupScheduleName(VaultLogic.BACKUP_SCHEDULE_EVERY_CALL),
            VaultLogic.backupScheduleName(VaultLogic.BACKUP_SCHEDULE_EVERY_6H),
            VaultLogic.backupScheduleName(VaultLogic.BACKUP_SCHEDULE_DAILY)
        )
        val current = schedules.indexOf(AutoBackup.schedule(this)).coerceAtLeast(0)
        VaultDialog.singleChoice(this, "Backup schedule", labels, current) { which ->
            AutoBackup.setSchedule(this, schedules[which])
            updateScheduleRow()
        }
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
        VaultDialog.message(
            this,
            "Call log permission",
            "Call Log Vault copies each new call into its own list on this phone, " +
                "so calls stay saved after the phone trims its own list. " +
                "No data leaves the phone. You can remove the permission any time in Settings.",
            positiveLabel = "Continue",
            onPositive = { requestPermissions(PermUtil.missing(this), REQ_PERMISSIONS) },
            negativeLabel = "Not now"
        )
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

    // ------------------------------------------------------- Pull to sync

    /**
     * Pull to refresh on the main list (v1.2.3, GitHub issue #5).
     * The one ListView backs both the Call log and the People page
     * (setMode only flips peopleMode), so a single install covers
     * both. The gesture can only start at the very top of the list
     * and only on a clearly downward finger; [PullRefreshMachine]
     * makes every arming and triggering decision. Events the pull
     * does not claim are never consumed, so row taps, long presses
     * and ordinary scrolling behave exactly as before. The sync the
     * pull starts is the ordinary [refresh] entry point, the same
     * one Sync now reaches through [manualSync]; there is no second
     * sync pipeline and no second in-flight flag.
     */
    private fun installPullToRefresh() {
        pullMachine = PullRefreshMachine(dp(PULL_ARM_DP).toFloat())
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var tracking = false
        var engaged = false
        var wasArmed = false
        var startX = 0f
        var startY = 0f
        listView.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    tracking = !pullMachine.isRefreshing && listAtTop()
                    engaged = false
                    wasArmed = false
                    startX = e.x
                    startY = e.y
                    // A fresh touch at the top restarts any settle
                    // spring still running from the last gesture, so
                    // the list never drifts under the finger.
                    if (tracking && pullAnim != null) {
                        animatePullOffsetTo(0f) { pullBox.visibility = View.GONE }
                    }
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!tracking) return@setOnTouchListener false
                    val dx = e.x - startX
                    val dy = e.y - startY
                    if (!engaged) {
                        when {
                            dy > slop && dy > kotlin.math.abs(dx) * 1.2f -> {
                                engaged = pullMachine.beginPull()
                                if (!engaged) {
                                    tracking = false
                                    return@setOnTouchListener false
                                }
                                // The pull owns the gesture from here:
                                // stop any offset spring and follow the
                                // finger directly.
                                pullAnimToken++
                                pullAnim?.cancel()
                                pullAnim = null
                                // The list saw the finger down but will
                                // see nothing more of this gesture: hand
                                // its own touch handling a cancel so the
                                // pressed row state and its pending tap
                                // checks reset instead of sticking.
                                val cancelEvent = MotionEvent.obtain(
                                    e.downTime, e.eventTime,
                                    MotionEvent.ACTION_CANCEL, e.x, e.y, 0
                                )
                                listView.onTouchEvent(cancelEvent)
                                cancelEvent.recycle()
                            }
                            kotlin.math.abs(dx) > slop && kotlin.math.abs(dx) >= dy -> {
                                // Horizontal drift wins: this gesture is
                                // a scroll or a swipe elsewhere, and the
                                // pull lets go of it completely.
                                tracking = false
                                return@setOnTouchListener false
                            }
                            else -> return@setOnTouchListener false
                        }
                    }
                    pullMachine.updatePull(dy)
                    if (pullMachine.isArmed && !wasArmed) {
                        listView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    }
                    wasArmed = pullMachine.isArmed
                    applyPullOffset(dampedPullOffset(dy))
                    renderPullGlyph()
                    renderPullLabel()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!engaged) {
                        tracking = false
                        return@setOnTouchListener false
                    }
                    tracking = false
                    engaged = false
                    // Consumed, so the release cannot also land as a
                    // row tap after a pull.
                    if (pullMachine.release()) startPullRefresh() else settlePullVisual()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (!engaged) {
                        tracking = false
                        return@setOnTouchListener false
                    }
                    tracking = false
                    engaged = false
                    pullMachine.cancel()
                    settlePullVisual()
                    true
                }
                else -> false
            }
        }
    }

    /** True only when the list is showing its very first row at its top. */
    private fun listAtTop(): Boolean {
        if (listView.visibility != View.VISIBLE) return false
        if (listView.firstVisiblePosition != 0) return false
        val first = listView.getChildAt(0) ?: return true
        return first.top >= listView.paddingTop
    }

    /**
     * Display offset for a finger travel: the list trails the finger
     * at a little over half speed and stops a short way past the
     * arming threshold, so the pull feels elastic, never 1:1 stiff.
     */
    private fun dampedPullOffset(dy: Float): Float {
        if (dy <= 0f) return 0f
        val cap = dp(PULL_ARM_DP).toFloat() * 1.5f
        return (dy * 0.55f).coerceAtMost(cap)
    }

    /** The glyph winds up with pull progress and lights up when armed. */
    private fun renderPullGlyph() {
        pullChipIcon.rotation = 300f * pullMachine.progress
        pullChipIcon.setColorFilter(
            color(if (pullMachine.isArmed) R.color.primary else R.color.tile_icon)
        )
    }

    /**
     * The words under the chip track the machine (v1.2.4): they flip
     * the moment a pull arms or disarms, and read Syncing… while the
     * sync runs. The mapping itself is pure and tested
     * ([pullStateLabel]); this only renders it.
     */
    private fun renderPullLabel() {
        pullLabel.text = pullStateLabel(pullMachine.state) ?: ""
    }

    /**
     * Move everything the pull moves: the list and the empty state
     * slide together, the chip box rides the same offset in from
     * above the list area. Called by the finger and by the offset
     * animator. The box (chip over its label) is exactly the hold
     * height when laid out, so at the settle offset the whole box
     * sits in the space the list slid away from.
     */
    private fun applyPullOffset(offsetPx: Float) {
        pullOffsetPx = offsetPx
        listView.translationY = offsetPx
        emptyView.translationY = offsetPx
        val boxH = if (pullBox.height > 0) pullBox.height.toFloat()
            else dp(PULL_BOX_FALLBACK_DP).toFloat()
        pullBox.translationY = offsetPx - boxH
        if (offsetPx > 0f || pullMachine.isRefreshing) {
            if (pullBox.visibility != View.VISIBLE) pullBox.visibility = View.VISIBLE
            pullBox.alpha = if (pullMachine.isRefreshing) 1f
                else (offsetPx / dp(PULL_HOLD_DP).toFloat()).coerceIn(0f, 1f)
        } else {
            pullBox.alpha = 0f
            pullBox.visibility = View.GONE
        }
    }

    /**
     * Spring the pull offset to [targetPx]. A generation token keeps
     * a superseded spring's end action from firing after a newer
     * gesture or animation has taken over the offset.
     */
    private fun animatePullOffsetTo(targetPx: Float, endAction: (() -> Unit)? = null) {
        val token = ++pullAnimToken
        pullAnim?.cancel()
        val anim = ValueAnimator.ofFloat(pullOffsetPx, targetPx)
        anim.duration = PULL_ANIM_MS
        anim.interpolator = DecelerateInterpolator()
        anim.addUpdateListener { applyPullOffset(it.animatedValue as Float) }
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (pullAnim === anim) pullAnim = null
                if (token == pullAnimToken) endAction?.invoke()
            }
        })
        pullAnim = anim
        anim.start()
    }

    /**
     * An armed pull was released: run the sync. Silent about
     * progress, because the chip is the progress UI (no dialog), but
     * v1.2.4 asks refresh() to report the result: a spinner alone
     * never told Arjun whether the sync had actually happened. If a
     * sync is already in flight, refresh() leaves it alone and
     * reports nothing for this call (the originator reports its own
     * result), and the chip simply mirrors that sync through the
     * settle watch below until the shared [syncing] flag clears.
     */
    private fun startPullRefresh() {
        pullChipIcon.setColorFilter(color(R.color.primary))
        pullSpin?.cancel()
        pullSpin = ObjectAnimator.ofFloat(pullChipIcon, "rotation", 0f, 360f).apply {
            duration = 850
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
        renderPullLabel()
        animatePullOffsetTo(dp(PULL_HOLD_DP).toFloat())
        refresh(silent = true, reportResult = true)
        pullSettleStartMs = System.currentTimeMillis()
        pullSettleHandler.removeCallbacks(pullSettleCheck)
        pullSettleHandler.postDelayed(pullSettleCheck, PULL_SETTLE_POLL_MS)
    }

    /**
     * Settle watch: poll the shared sync flag until it clears, then
     * put the chip away. The minimum spin keeps a very fast sync
     * from flashing the chip, and the hard cap means the indicator
     * can never spin forever, whatever the sync does.
     */
    private val pullSettleCheck = object : Runnable {
        override fun run() {
            val waited = System.currentTimeMillis() - pullSettleStartMs
            if ((!syncing && waited >= PULL_MIN_SPIN_MS) || waited > PULL_SETTLE_CAP_MS) {
                settlePullAfterRefresh()
            } else {
                pullSettleHandler.postDelayed(this, PULL_SETTLE_POLL_MS)
            }
        }
    }

    private fun settlePullAfterRefresh() {
        if (!pullMachine.syncSettled()) return
        pullSpin?.cancel()
        pullSpin = null
        pullChipIcon.rotation = 0f
        renderPullLabel()
        animatePullOffsetTo(0f) {
            pullBox.visibility = View.GONE
            pullChipIcon.setColorFilter(color(R.color.tile_icon))
        }
    }

    /** An unarmed release or a cancelled pull: spring back, nothing runs. */
    private fun settlePullVisual() {
        renderPullLabel()
        animatePullOffsetTo(0f) { pullBox.visibility = View.GONE }
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
     *
     * v1.2.4: progress and result are separate switches. [silent]
     * still owns progress (the dialog; the pull chip covers it for
     * the pull path), while [reportResult] owns the completion
     * toast. It defaults to following [silent], so every existing
     * caller behaves exactly as before; the pull passes it
     * explicitly, because its verdict was that a silent spinner
     * never proved a sync happened. A call that finds a sync
     * already running returns above without starting anything, so
     * it can never report: only the originator does
     * ([shouldReportSyncResult]).
     */
    private fun refresh(silent: Boolean, reportResult: Boolean = !silent) {
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
        // Reaching this line means THIS call starts the sync (the
        // already running path returned), so it owns the result
        // report whenever one was requested.
        val report = shouldReportSyncResult(
            startedByThisCall = true, reportRequested = reportResult
        )
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
                // Watch check (v1.1.0): this refresh is the app open
                // wakeup the watch rides on, and every manual import
                // and restore ends in a refresh too, so those checks
                // come free here. Inert unless the watch switch is
                // on. An import that changed the vault asks the
                // auto backup job to write the merged vault back.
                val watch = WatchFolder.importFromFolderIfChanged(this)
                if (watch.changed) {
                    AutoBackup.requestImmediateWrite(this)
                    AutoBackupScheduler.schedule(this)
                }
                val newCount = helper.count(db)
                if (!VaultLogic.shouldApplyRebuild(
                        snapshotCount, newCount,
                        result.added + watch.added + watch.namesUpdated,
                        allRecords.isNotEmpty()
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
                        if (result.error != null && report) {
                            toast(result.error)
                        }
                        if (report && result.error == null) {
                            toast(syncDoneMessage(result.added))
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
                        if (result.error != null && report) {
                            toast(result.error)
                        }
                        if (report && result.error == null) {
                            toast(syncDoneMessage(result.added))
                        }
                    }
                }
            } catch (e: Exception) {
                SyncEngine.recordSyncError(this, e.message ?: "Sync failed")
                if (report) {
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
        VaultDialog.message(
            this,
            "Backup to ZIP",
            "The backup file is saved in the folder you pick next, outside this app. " +
                "Clearing app data or uninstalling the app will not delete it.",
            positiveLabel = "Choose folder",
            onPositive = { launchExport() },
            negativeLabel = "Cancel"
        )
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
                updateWatchRows()
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
                            VaultDialog.message(
                                this,
                                "Put calls back in the dialer?",
                                "The calls are in the vault now. You can also put them back into the phone call list, so your dialer shows them.",
                                positiveLabel = "Restore to Dialer",
                                onPositive = { chooseRestoreMode() },
                                negativeLabel = "Later"
                            )
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
        VaultDialog.singleChoice(
            this,
            "Restore to Dialer",
            listOf("Latest 2000 calls", "All calls (" + total + ")"),
            selectedIndex = -1
        ) { which ->
            if (which == 0) {
                doRestore(latestOnly = true)
            } else {
                VaultDialog.message(
                    this,
                    "Restore all calls",
                    "Your phone keeps only the newest 2000 entries in its call list. " +
                        "If you restore more than that, the phone will trim the oldest ones " +
                        "from the dialer again as new calls arrive. Nothing is lost: " +
                        "every call stays in this vault, with no limit.",
                    positiveLabel = "Restore all",
                    onPositive = { doRestore(latestOnly = false) },
                    negativeLabel = "Cancel"
                )
            }
        }
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

    /**
     * One Status card (v1.2.0): an eyebrow title over label and
     * value rows, on the shared card surface. The Status popup is
     * grouped cards like this (Vault, Backup, Appearance, Watch,
     * About) instead of the old single block of plain text lines.
     */
    private fun infoCard(title: String, rows: List<Pair<String, String>>): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = UiKit.cardBackground(this@MainActivity, 16)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }
        card.addView(TextView(this).apply {
            text = title.uppercase(Locale.ENGLISH)
            textSize = 11f
            letterSpacing = 0.18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.eyebrow))
            setPadding(0, 0, 0, dp(4))
        })
        for ((label, value) in rows) {
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
            row.addView(TextView(this).apply {
                text = value
                textSize = 13f
                typeface = UiKit.mediumTypeface
                setTextColor(color(R.color.text))
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f)
            })
            card.addView(row)
        }
        return card
    }

    private fun showInfo() {
        val helper = VaultDb.get(this)
        val db = helper.readableDatabase
        val count = helper.count(db)
        val oldest = helper.oldestDate(db)
        val lastSync = SyncEngine.lastSyncMs(this)
        val syncError = SyncEngine.lastSyncError(this)

        val vaultRows = listOf(
            "Calls in vault" to count.toString(),
            "Oldest saved" to
                if (oldest > 0) VaultLogic.formatDateTime(oldest) else "None yet",
            "Last sync" to
                if (lastSync > 0) {
                    VaultLogic.formatDateTime(lastSync) +
                        " (added " + SyncEngine.lastSyncAdded(this) + ")"
                } else {
                    "Not yet"
                },
            "Last sync result" to when {
                syncError.isNotEmpty() -> syncError
                lastSync > 0 -> "No error (added " + SyncEngine.lastSyncAdded(this) + ")"
                else -> "No error"
            }
        )
        val backupRows = ArrayList<Pair<String, String>>()
        backupRows.add(
            "Auto backup folder" to
                if (AutoBackup.hasFolder(this)) AutoBackup.folderDisplayName(this) else "Not set"
        )
        backupRows.add(
            "Backup schedule" to VaultLogic.backupScheduleName(AutoBackup.schedule(this))
        )
        if (AutoBackup.hasFolder(this)) {
            val autoMs = AutoBackup.lastWriteMs(this)
            backupRows.add(
                "Last auto backup" to
                    if (autoMs > 0) {
                        VaultLogic.formatDateTime(autoMs) + ", " +
                            AutoBackup.lastCount(this) + " records"
                    } else {
                        "Not yet"
                    }
            )
            backupRows.add(
                "Auto backup status" to
                    if (AutoBackup.hasError(this)) "Needs attention" else "Ok"
            )
        }
        val appearanceMode = UiKit.appearance(this)
        val appearanceText = if (appearanceMode == UiKit.APPEARANCE_AUTO) {
            // Never leave Auto ambiguous in Status: name the concrete
            // preset it currently resolves to as well.
            UiKit.appearanceName(appearanceMode) + " (" +
                UiKit.appearanceName(UiKit.concreteAppearance(this)) + ")"
        } else {
            // Explicit modes name their concrete preset, so the Dark
            // mode with the AMOLED switch on reads AMOLED here.
            UiKit.appearanceName(UiKit.concreteAppearance(this))
        }
        val aboutRows = listOf(
            "Version" to versionName(),
            "Auto save on new call" to
                if (SnifferScheduler.isTriggerScheduled(this)) {
                    "Scheduled"
                } else {
                    "Not scheduled, open the app to rearm it"
                },
            "Daily safety sync" to
                if (SnifferScheduler.isPeriodicScheduled(this)) "Scheduled" else "Not scheduled"
        )

        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(infoCard("Vault", vaultRows))
        content.addView(infoCard("Backup", backupRows))
        content.addView(infoCard("Appearance", listOf("Appearance" to appearanceText)))
        if (WatchFolder.watchEnabled(this)) {
            val line = WatchFolder.statusLine(this)
            val watchText = if (line == null) {
                "Not checked yet"
            } else {
                line.removePrefix("Watch folder: ")
                    .replaceFirstChar { it.uppercaseChar() }
            }
            content.addView(infoCard("Watch", listOf("Last check" to watchText)))
        }
        val aboutCard = infoCard("About", aboutRows)
        aboutCard.addView(TextView(this).apply {
            text = "The vault has no limit and never deletes calls on its own.\n" +
                "Backup files live in folders you choose, outside this app, " +
                "so clearing data or uninstalling never deletes them.\n" +
                "This app works fully offline and has no internet permission.\n\n" +
                "Imagined by Arjun"
            textSize = 13f
            setTextColor(color(R.color.muted))
            setPadding(0, dp(8), 0, dp(2))
        })
        val crashSummary = CrashLog.summary(this)
        if (crashSummary != null) {
            aboutCard.addView(TextView(this).apply {
                text = "Last crash: " + crashSummary + "  (tap to view)"
                textSize = 13f
                setTextColor(color(R.color.primary))
                setPadding(0, dp(8), 0, dp(2))
                isClickable = true
                pressFeedback()
                setOnClickListener { showCrashDetail() }
            })
        }
        content.addView(aboutCard)

        val scroll = ScrollView(this)
        scroll.addView(content)
        scroll.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (resources.displayMetrics.heightPixels * 0.55f).toInt()
        )
        VaultDialog.custom(this, "Status and about", scroll)
    }

    private fun showCrashDetail() {
        val trace = CrashLog.read(this) ?: return
        val tv = TextView(this).apply {
            text = trace
            textSize = 11f
            setTextColor(color(R.color.text))
            typeface = Typeface.MONOSPACE
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val scroll = ScrollView(this)
        scroll.addView(tv)
        scroll.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (resources.displayMetrics.heightPixels * 0.5f).toInt()
        )
        VaultDialog.custom(
            this, "Last crash", scroll,
            negativeLabel = "Clear crash log",
            onNegative = {
                CrashLog.clear(this)
                toast("Crash log cleared")
            }
        )
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.6"
    } catch (e: Exception) {
        "1.0.6"
    }

    private fun showProgress(msg: String): VaultProgress = VaultDialog.progress(this, msg)

    private fun showResult(title: String, msg: String) {
        VaultDialog.message(this, title, msg)
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val SUPPORT_URL = "https://viewing-graphs-requests-peaceful.trycloudflare.com/"
        private const val SYNC_WEDGE_MS = 120_000L
        private const val SYNC_DIALOG_CAP_MS = 30_000L
        private const val PULL_ARM_DP = 72
        private const val PULL_HOLD_DP = 56
        private const val PULL_CHIP_DP = 38
        // Chip (38) + label gap (2) + one label line (16): the pull
        // box height before its first layout measures it for real.
        private const val PULL_BOX_FALLBACK_DP = 56
        private const val PULL_ANIM_MS = 220L
        private const val PULL_SETTLE_POLL_MS = 120L
        private const val PULL_MIN_SPIN_MS = 450L
        private const val PULL_SETTLE_CAP_MS = 135_000L
        private const val SEARCH_DEBOUNCE_MS = 120L
        private const val REQ_PERMISSIONS = 100
        private const val REQ_EXPORT = 2001
        private const val REQ_IMPORT = 2002
        private const val REQ_FOLDER = 3001
    }
}
