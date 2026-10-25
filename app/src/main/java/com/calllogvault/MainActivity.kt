package com.calllogvault

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
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
import android.view.animation.OvershootInterpolator
import android.widget.AbsListView
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
    // The header indicator's dot and label (v1.2.8): they read
    // Watching in green while the vault is live, and switch to a
    // Paused state in amber, naming the resume time, while a pause
    // window is in force.
    private lateinit var watchDot: View
    private lateinit var watchLabel: TextView
    private lateinit var pageTitle: TextView
    private lateinit var scrollTopButton: TextView
    // Fast scroll scrubber (v1.2.7): the solid thumb docked to
    // the right edge of the list area, shorter and wider since
    // v1.2.9, plus the section bubble that rides beside it while
    // it is dragged. scrubbing marks a live drag: the finger then
    // owns the thumb's position, and the scroll callbacks must
    // not re-derive it mid drag.
    private lateinit var scrubPill: LinearLayout
    private lateinit var scrubBubble: TextView
    private var scrubbing = false
    private var scrubStartY = 0f
    private var scrubStartFraction = 0f
    // Idle hide (v1.3.0, issue #14; animation reworked in
    // v1.3.1, then again in v1.3.2): once the list has sat
    // idle for SCRUB_IDLE_FADE_MS the thumb shrinks
    // proportionally into the right edge, and any scroll
    // start grows it back out smoothly. scrubbing is the
    // live drag (never hides), scrubScrolling tracks the
    // list state, scrubFadedOut is the hidden state that
    // layout rechecks must not resurrect on their own.
    private val scrubHandler = Handler(Looper.getMainLooper())
    private var scrubScrolling = false
    private var scrubFadedOut = false
    private val scrubFadeRunnable = Runnable {
        if (scrubbing || scrubScrolling || !scrubberAvailable()) return@Runnable
        scrubPill.animate().cancel()
        // Uniform shrink toward the right edge: pivot on the
        // edge itself at mid height, both axes together, so
        // the pill keeps its proportions as it disappears
        // into the edge instead of being squeezed sideways
        // (v1.3.2: the v1.3.1 scaleX only collapse read as
        // cheap on device).
        scrubPill.pivotX = scrubPillRightEdgePx()
        scrubPill.pivotY = scrubPill.height / 2f
        scrubPill.animate()
            .scaleX(VaultLogic.SCRUB_HIDDEN_SCALE)
            .scaleY(VaultLogic.SCRUB_HIDDEN_SCALE)
            .alpha(0f)
            .setDuration(VaultLogic.SCRUB_HIDE_MS)
            .withEndAction {
                if (!scrubbing && !scrubScrolling) {
                    scrubPill.visibility = View.GONE
                    scrubFadedOut = true
                }
            }.start()
    }

    /** The pill's right edge in pixels, for the shrink pivot. */
    private fun scrubPillRightEdgePx(): Float =
        if (::scrubPill.isInitialized && scrubPill.width > 0) scrubPill.width.toFloat()
        else dp(SCRUB_PILL_WIDTH_DP).toFloat()

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
    private var pauseSubtitle: TextView? = null
    private var binSubtitle: TextView? = null
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

    // Independent page scroll and scroll to top (v1.2.5, GitHub
    // issues #6 and #7). The two browse pages still share one
    // ListView for the pull gesture and the adapter, but each page
    // owns a saved scroll state that is captured before a switch
    // and restored after it. The button state is pure and tested.
    // v1.2.6: the pill's visibility is re-derived from many real
    // list events (scroll callback values, state changes, touches,
    // layout passes, applies, restores, resumes), never one path.
    private var callLogScrollState = VaultLogic.PageScrollState()
    private var peopleScrollState = VaultLogic.PageScrollState()
    private val scrollTopMachine = ScrollToTopMachine()
    private val scrollTopHandler = Handler(Looper.getMainLooper())
    private var scrollTopPulse: ObjectAnimator? = null
    private var scrollTopLastWatchPosition = -1
    // v1.2.6 pill rendering: the last state actually drawn, so the
    // spring in, pulse and spring out animations fire only on real
    // transitions and never restart on every scroll callback. The
    // generation token retires stale animator completions (a hide
    // finishing after a re-show must not hide the pill again), and
    // the posted check is the post layout re-read that corrects any
    // stale pre layout position the synchronous updates measured.
    private var scrollTopRenderedState: ScrollToTopMachine.State? = null
    private var scrollTopAnimGeneration = 0
    private var scrollTopCheckPosted = false
    private val scrollTopCheck = Runnable {
        scrollTopCheckPosted = false
        updateScrollToTopFromList()
        refreshScrubber()
    }

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
        // What's New once per version (v1.4.0): posted, so it never
        // blocks the first load or the silent sync.
        frame.post { showWhatsNewIfNeeded() }
        armSnifferOnce()
        updatePermState()
        updateWatchIndicator()
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
        updateWatchIndicator()
        updateBinRow()
        // Coming back to the screen is one more chance to catch the
        // pill up with wherever the list really rests (v1.2.6).
        scheduleScrollTopCheck()
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
        // Neither may the scroll to top watch, arrived beat or pulse.
        scrollTopHandler.removeCallbacks(scrollTopWatch)
        scrollTopHandler.removeCallbacks(scrollTopArrivedFinish)
        scrollTopHandler.removeCallbacks(scrollTopCheck)
        if (::listView.isInitialized) listView.removeCallbacks(scrollTopCheck)
        scrollTopPulse?.cancel()
        scrollTopPulse = null
        scrollTopAnimGeneration++
        if (::scrollTopButton.isInitialized) scrollTopButton.animate().cancel()
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
        pageTitle = TextView(this).apply {
            text = VaultLogic.mainPageTitle(peopleMode)
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.text))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(4) }
        }
        topBar.addView(pageTitle)
        watchingBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            // While paused the indicator is the Resume now action
            // (v1.2.8); while watching, a tap does nothing.
            isClickable = true
            isFocusable = true
            pressFeedback()
            setOnClickListener { onWatchIndicatorTap() }
        }
        watchDot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color(R.color.green))
            }
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8))
        }
        watchingBox.addView(watchDot)
        watchLabel = TextView(this).apply {
            text = "Watching"
            textSize = 12f
            setTextColor(color(R.color.muted))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(6) }
        }
        watchingBox.addView(watchLabel)
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
            // v1.2.7: the stock scrollbar thumb at the right edge is
            // replaced by the draggable scrubber pill below, so the
            // edge carries one affordance, not two stacked thumbs.
            isVerticalScrollBarEnabled = false
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
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view: AbsListView, scrollState: Int) {
                    // A finger taking the list over mid return ends
                    // the return: the button then just reports where
                    // the list really is. Programmatic return scroll
                    // reports FLING, never TOUCH_SCROLL, so it is
                    // never mistaken for the user.
                    if (scrollState == AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL &&
                        scrollTopMachine.state == ScrollToTopMachine.State.RETURNING
                    ) {
                        interruptScrollToTopReturn()
                    }
                    // Every state change (a fling starting, a scroll
                    // settling idle) earns a post layout recheck, so
                    // the pill is never left stale (v1.2.6).
                    // Idle hide (v1.3.0, issue #14; animation
                    // in v1.3.2): any scroll start grows the
                    // scrubber back out from the right edge, and
                    // settling idle starts the hide timer.
                    if (scrollState == AbsListView.OnScrollListener.SCROLL_STATE_IDLE) {
                        scrubScrolling = false
                        if (!scrubbing) scheduleScrubFade()
                    } else {
                        scrubScrolling = true
                        showScrubberNow()
                    }
                    scheduleScrollTopCheck()
                }

                override fun onScroll(
                    view: AbsListView,
                    firstVisibleItem: Int,
                    visibleItemCount: Int,
                    totalItemCount: Int
                ) {
                    // v1.2.6: measure from the callback's own report
                    // (the row this callback is about), not a second
                    // re-read of the view, and queue the post layout
                    // recheck behind it.
                    updateScrollToTopFromList(firstVisibleItem)
                    refreshScrubber()
                    scheduleScrollTopCheck()
                }
            })
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                // A position read before this layout pass can be a
                // stale pre layout one; re-read once the rows are
                // actually placed (v1.2.6, the v1.2.5 pill miss).
                scheduleScrollTopCheck()
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

        // Scroll to top pill (v1.2.5, GitHub issue #7; rebuilt as a
        // springy glass pill in v1.2.6 after the flat v1.2.5 pill
        // was missed on device): a floating pill anchored to the
        // bottom right of the list area. It is a sibling of the
        // list, never a child, so it takes no part in scrolling,
        // row taps or the pull gesture; the pull chip lives at the
        // top centre, far from it. Its words and visibility come
        // from ScrollToTopMachine, rendered by
        // renderScrollToTopButton() with a translucent gradient,
        // a bright hairline stroke and a real shadow, so it reads
        // as a solid pill floating over the rows on every theme, AMOLED
        // included. It is added last in the list area, and the
        // render brings it to the front again, so no sibling can
        // ever cover it.
        scrollTopButton = TextView(this).apply {
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(18), 0, dp(18), 0)
            minWidth = dp(104)
            elevation = dp(12).toFloat()
            visibility = View.GONE
            isClickable = true
            isFocusable = true
            pressFeedback()
            setOnClickListener { startScrollToTop() }
        }
        listArea.addView(scrollTopButton, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, dp(48),
            Gravity.BOTTOM or Gravity.END
        ).apply {
            marginEnd = dp(18)
            bottomMargin = dp(20)
        })
        renderScrollToTopButton()

        // Fast scroll scrubber (v1.2.7): what Arjun meant by
        // "scroll helper" all along (his v1.2.6 screenshot arrow
        // pointed at the right edge scrollbar all along): a solid
        // thumb docked to the right edge of the list area whose
        // short drag scrubs the whole history, the scroll
        // multiplier feel. v1.2.9 reshapes it iOS style: shorter
        // and wider at rest, springing larger while it is held and
        // shrinking back on release, in the solid File Master bar
        // surface (see solidBarBackground). It is a sibling of the
        // list like the scroll to top pill, never a row: touches
        // that land on it are consumed by its own touch listener
        // and nothing else in the list ever sees them, and every
        // other touch behaves exactly as before. Its track runs
        // down the right edge but stops short of the bottom
        // corner, so it can never overlap the scroll to top pill.
        // While it is dragged, a small bubble rides beside it
        // naming the section being targeted (the date header on
        // Call Logs, the person on People).
        scrubBubble = TextView(this).apply {
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.text))
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = solidBarBackground(14)
            elevation = dp(12).toFloat()
            visibility = View.GONE
            isClickable = false
            isFocusable = false
        }
        listArea.addView(scrubBubble, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END
        ).apply {
            marginEnd = dp(SCRUB_PILL_WIDTH_DP + 12)
            topMargin = dp(SCRUB_TRACK_TOP_DP)
        })
        scrubPill = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = solidBarBackground(SCRUB_PILL_WIDTH_DP / 2)
            elevation = dp(SCRUB_REST_ELEVATION_DP).toFloat()
            alpha = SCRUB_REST_ALPHA
            visibility = View.GONE
            isClickable = true
            isFocusable = true
            contentDescription = "Fast scroll handle"
            // The grip: three short bars, always drawn the same on
            // every theme, so the thumb reads as grabbable. They
            // are sized for the wider v1.2.9 thumb.
            for (i in 0 until 3) {
                addView(View(this@MainActivity).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(1).toFloat()
                        setColor(color(R.color.text))
                    }
                }, LinearLayout.LayoutParams(dp(16), dp(2)).apply {
                    if (i > 0) topMargin = dp(3)
                })
            }
            setOnTouchListener { _, event -> onScrubTouch(event) }
        }
        listArea.addView(scrubPill, FrameLayout.LayoutParams(
            dp(SCRUB_PILL_WIDTH_DP), dp(SCRUB_PILL_HEIGHT_DP),
            Gravity.TOP or Gravity.END
        ).apply {
            marginEnd = dp(4)
            topMargin = dp(SCRUB_TRACK_TOP_DP)
        })
        refreshScrubber()

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
        // The permission panel owns the list area while it is up:
        // the scroll to top button must never sit on top of it.
        updateScrollToTopFromList()
        scheduleScrollTopCheck()
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
        // The People entry itself is hidden while the privacy toggle
        // is off (v1.4.0); the data stays in the vault.
        if (UiKit.showPeopleMenu(this)) {
            body.addView(drawerRow(R.drawable.ic_nav_people, "People", null) {
                closeDrawer()
                setMode(people = true)
            }.first)
        }

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
        val pauseRow = drawerRow(R.drawable.ic_nav_pause, "Pause vault", "") {
            closeDrawer()
            showPauseDialog()
        }
        pauseSubtitle = pauseRow.second
        body.addView(pauseRow.first)
        val binRow = drawerRow(R.drawable.ic_nav_bin, "Bin", "") {
            closeDrawer()
            startActivity(Intent(this, BinActivity::class.java))
        }
        binSubtitle = binRow.second
        body.addView(binRow.first)
        body.addView(drawerRow(R.drawable.ic_nav_sync, "Sync now", null) {
            closeDrawer()
            manualSync()
        }.first)
        body.addView(drawerRow(R.drawable.ic_nav_info, "Status and about", null) {
            closeDrawer()
            showInfo()
        }.first)
        body.addView(drawerRow(R.drawable.ic_nav_info, "What's new", null) {
            closeDrawer()
            showWhatsNewDialog()
        }.first)

        body.addView(eyebrow("PRIVACY"))
        body.addView(switchRow(
            R.drawable.ic_nav_log,
            "Show call details",
            "First call, last call, total calls and total talk time",
            UiKit.showCallDetails(this)
        ) { checked ->
            if (UiKit.showCallDetails(this@MainActivity) != checked) {
                UiKit.setShowCallDetails(this@MainActivity, checked)
                rebuildItems()
            }
        }.first)
        body.addView(switchRow(
            R.drawable.ic_nav_people,
            "Show People menu",
            "Hide the whole People section",
            UiKit.showPeopleMenu(this)
        ) { checked ->
            if (UiKit.showPeopleMenu(this@MainActivity) != checked) {
                UiKit.setShowPeopleMenu(this@MainActivity, checked)
                // The drawer rows are built once, so a recreate is
                // what adds or removes the People entry, same as the
                // theme switches below. A restart always lands on
                // Call log, so turning the menu off mid People page
                // falls back cleanly.
                recreate()
            }
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
        updatePauseRow()
        updateBinRow()
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
        // People is unreachable while its menu is off (v1.4.0):
        // a stale request falls back to Call log instead of opening it.
        val target = VaultLogic.effectivePeopleMode(people, UiKit.showPeopleMenu(this))
        val switching = target != peopleMode
        if (switching) {
            // Issue #6: capture the outgoing page exactly where it
            // is, then kill its momentum before any item changes.
            // A fling left running here is what used to carry the
            // other page away under the user.
            savePageScrollState(peopleMode)
            stopListMomentum()
            cancelScrollToTopWork()
        }
        peopleMode = target
        pageTitle.text = VaultLogic.mainPageTitle(peopleMode)
        chipScroll.visibility = if (target) View.GONE else View.VISIBLE
        rebuildItems()
        if (switching) {
            restorePageScrollState(peopleMode)
        } else {
            updateScrollToTopFromList()
            scheduleScrollTopCheck()
        }
    }

    /** Capture the shared list's current position for one page. */
    private fun savePageScrollState(forPeople: Boolean) {
        if (!::listView.isInitialized) return
        val firstChild = listView.getChildAt(0)
        val offset = firstChild?.let { it.top - listView.paddingTop }
        val state = VaultLogic.pageScrollState(listView.firstVisiblePosition, offset)
        if (forPeople) peopleScrollState = state else callLogScrollState = state
    }

    /** Restore one page's saved position after its items land. */
    private fun restorePageScrollState(forPeople: Boolean) {
        val saved = if (forPeople) peopleScrollState else callLogScrollState
        val target = VaultLogic.restoredPageScrollState(saved, items.size)
        listView.post {
            // A fast second switch owns the list now; a stale
            // restore for the earlier page must never land on it.
            if (peopleMode != forPeople) return@post
            stopListMomentum()
            listView.setSelectionFromTop(target.firstVisiblePosition, target.firstChildOffsetPx)
            updateScrollToTopFromList()
            // The read above still races the layout the restore
            // just requested; the posted recheck (and the list's
            // layout listener behind it) settles the pill on the
            // restored page's real position (v1.2.6).
            scheduleScrollTopCheck()
        }
    }

    /**
     * Stop any fling or smooth scroll the shared list is running.
     * A zero distance smooth scroll is the public AbsListView call
     * that stops its position and fling scrollers in place, without
     * moving the selection or the visible rows.
     */
    private fun stopListMomentum() {
        if (!::listView.isInitialized) return
        listView.smoothScrollBy(0, 0)
    }

    // ------------------------------------------------------- Scroll to top

    /**
     * Whether the scroll to top button may exist at all right now:
     * a real, permitted, non empty list is on screen. The permission
     * panel and the empty state own the list area while they show.
     */
    private fun scrollTopListAvailable(): Boolean {
        if (!::listView.isInitialized || !::scrollTopButton.isInitialized) return false
        if (listView.visibility != View.VISIBLE) return false
        if (::permPanel.isInitialized && permPanel.visibility == View.VISIBLE) return false
        if (::emptyView.isInitialized && emptyView.visibility == View.VISIBLE) return false
        return items.isNotEmpty()
    }

    /**
     * Queue one post layout re-read of the list position (v1.2.6).
     * v1.2.5 measured synchronously at moments when the ListView
     * had not laid out its rows yet (right after a data apply, in
     * the middle of a page restore), so a stale position 0 read
     * could latch the pill hidden with nothing left to revive it.
     * Every list event, layout pass, apply, restore, permission
     * change and resume now also queues this recheck; it coalesces,
     * so a fling costs at most one queued read per frame turn.
     */
    private fun scheduleScrollTopCheck() {
        if (!::listView.isInitialized || scrollTopCheckPosted) return
        scrollTopCheckPosted = true
        listView.post(scrollTopCheck)
    }

    /** Fold the list's real position into the pill machine and render. */
    private fun updateScrollToTopFromList() {
        updateScrollToTopFromList(null)
    }

    /**
     * The position fold itself: the machine decides, this only
     * measures and draws. [reportedFirstVisible] is the scroll
     * listener's own callback value when the call comes from there
     * (v1.2.6: trust the callback, do not re-read the view for the
     * row the callback just described); other callers pass null and
     * the view is read directly. The "away" test is the pixel
     * estimate in VaultLogic, not a bare adapter position.
     */
    private fun updateScrollToTopFromList(reportedFirstVisible: Int?) {
        if (!::scrollTopButton.isInitialized) return
        if (!scrollTopListAvailable()) {
            cancelScrollToTopWork()
            return
        }
        val first = reportedFirstVisible ?: listView.firstVisiblePosition
        val firstChild = listView.getChildAt(0)
        val away = if (firstChild == null) {
            first > 0
        } else {
            VaultLogic.scrollToTopAwayPx(
                first, firstChild.top, listView.paddingTop,
                firstChild.height, dp(SCROLL_TOP_AWAY_DP)
            )
        }
        val atTop = if (reportedFirstVisible == null) {
            listAtTop()
        } else if (firstChild == null) {
            first == 0
        } else {
            VaultLogic.isAtTop(first, firstChild.top, listView.paddingTop)
        }
        val before = scrollTopMachine.state
        val after = scrollTopMachine.onPosition(awayFromTop = away, atTop = atTop)
        if (before != ScrollToTopMachine.State.ARRIVED &&
            after == ScrollToTopMachine.State.ARRIVED
        ) {
            onScrollToTopArrived()
        }
        renderScrollToTopButton()
    }

    /**
     * The pills' solid skin (v1.3.3): File Master's bar surface,
     * taken verbatim from FM Theme.barDrawable, which is the
     * locked flat look after the glass saga closed. No glass,
     * no frost, no translucency, no gradient, no blur, no
     * sheen: one fully opaque fill per theme (light white,
     * dark #2C2C2E, AMOLED #1C1C1E) with a 1dp hairline, plus
     * the view's elevation for the soft shadow. The fill and
     * stroke come from VaultLogic.solidBarFill/solidBarStroke
     * so the mapping is unit tested. Light theme contrast:
     * the white fill sits on the #F7F9FB list page, and the
     * pill's definition comes from the black 10 percent
     * hairline, the 12dp elevation shadow, and the dark grip
     * and text, not from translucency.
     */
    private fun solidBarBackground(cornerDp: Int): GradientDrawable {
        val appearance = UiKit.concreteAppearance(this)
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(cornerDp).toFloat()
            setColor(VaultLogic.solidBarFill(appearance))
            setStroke(dp(1), VaultLogic.solidBarStroke(appearance))
        }
    }

    /**
     * The scroll to top pill's solid skin for one state
     * (v1.3.3): the bar surface, except while returning,
     * when the pill is the solid primary fill with the
     * on-primary text the render already sets.
     */
    private fun scrollTopSolidBackground(state: ScrollToTopMachine.State): Drawable {
        if (state == ScrollToTopMachine.State.RETURNING) {
            return GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                setColor(color(R.color.primary))
                setStroke(dp(1), color(R.color.primary_dark))
            }
        }
        return solidBarBackground(24)
    }

    /**
     * The scrubber exists only when the shared list does and is
     * long enough to need one: the same availability gate as the
     * scroll to top pill (no permission panel, no empty view), plus
     * the length threshold.
     */
    private fun scrubberAvailable(): Boolean =
        scrollTopListAvailable() && items.size > SCRUB_MIN_ITEMS

    /**
     * Show or hide the scrubber and, while no drag is live, mirror
     * the list's position onto the thumb. Called from the scroll
     * callbacks and the same post layout recheck that keeps the
     * scroll to top pill honest, so it costs one fraction read and
     * one translation per event, nothing more.
     */
    /**
     * Idle hide helpers (v1.3.0, issue #14; show/hide animation
     * reworked in v1.3.1 and v1.3.2). The hide timer only ever
     * starts on a real idle transition or a drag release, so
     * the thumb never hides on first sight, only after
     * scrolling has settled. showScrubberNow cancels any hide
     * in flight: a hidden pill grows back out from the right
     * edge smoothly, an already visible one is restored
     * smoothly if caught mid hide (a grab owns its scale while
     * dragging, so a show never touches a held pill).
     */
    private fun scheduleScrubFade() {
        scrubHandler.removeCallbacks(scrubFadeRunnable)
        scrubHandler.postDelayed(scrubFadeRunnable, VaultLogic.SCRUB_IDLE_FADE_MS)
    }

    private fun cancelScrubFade() {
        scrubHandler.removeCallbacks(scrubFadeRunnable)
    }

    private fun showScrubberNow() {
        cancelScrubFade()
        if (!::scrubPill.isInitialized) return
        if (scrubbing) return
        val wasHidden = scrubFadedOut || scrubPill.visibility != View.VISIBLE
        scrubFadedOut = false
        scrubPill.animate().cancel()
        if (!wasHidden) {
            // Already on screen but possibly caught mid hide:
            // a cancelled hide must not leave it half collapsed,
            // and the restore is a smooth grow, not a snap
            // (v1.3.2: the fast pop read as a jerk on device).
            if (scrubPill.scaleX < 1f || scrubPill.scaleY < 1f ||
                scrubPill.alpha < SCRUB_REST_ALPHA
            ) {
                scrubPill.pivotX = scrubPillRightEdgePx()
                scrubPill.pivotY = scrubPill.height / 2f
                scrubPill.animate()
                    .scaleX(1f).scaleY(1f).alpha(SCRUB_REST_ALPHA)
                    .setDuration(VaultLogic.SCRUB_SHOW_MS)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            return
        }
        scrubPill.visibility = View.VISIBLE
        scrubPill.pivotX = scrubPillRightEdgePx()
        scrubPill.pivotY = scrubPill.height / 2f
        scrubPill.scaleX = VaultLogic.scrubShowStartScale(true)
        scrubPill.scaleY = VaultLogic.scrubShowStartScale(true)
        scrubPill.alpha = 0f
        scrubPill.animate()
            .scaleX(1f).scaleY(1f).alpha(SCRUB_REST_ALPHA)
            .setDuration(VaultLogic.SCRUB_SHOW_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun refreshScrubber() {
        if (!::scrubPill.isInitialized) return
        if (!scrubberAvailable()) {
            cancelScrubFade()
            scrubFadedOut = false
            if (scrubbing) endScrub()
            if (scrubPill.visibility != View.GONE) {
                scrubPill.visibility = View.GONE
                scrubBubble.visibility = View.GONE
            }
            return
        }
        // Faded out and still idle: layout rechecks and scroll
        // callbacks must not resurrect the thumb on their own.
        // Only a scroll start (showScrubberNow) or a grab does.
        if (scrubFadedOut && !scrubbing && !scrubScrolling) return
        if (scrubPill.visibility != View.VISIBLE) {
            scrubPill.visibility = View.VISIBLE
            if (!scrubbing) {
                // A re-show after a page switch must never
                // inherit the hidden shrink scale.
                scrubPill.scaleX = 1f
                scrubPill.scaleY = 1f
                scrubPill.alpha = SCRUB_REST_ALPHA
            }
        }
        if (scrubbing) return
        val fraction = VaultLogic.scrollFraction(
            listView.firstVisiblePosition, listView.childCount, items.size
        )
        positionScrubPill(fraction)
    }

    /**
     * The thumb's travel in pixels: the list viewport minus the
     * track's top inset, the reserved bottom corner (the scroll to
     * top pill's ground, 84dp: its 48dp height, 20dp margin and a
     * gap) and the thumb's own height.
     */
    private fun scrubTrackTravelPx(): Int {
        val viewport = if (listView.height > 0) listView.height
            else (scrubPill.parent as? View)?.height ?: 0
        val travel = viewport - dp(SCRUB_TRACK_TOP_DP) -
            dp(SCRUB_TRACK_BOTTOM_DP) - dp(SCRUB_PILL_HEIGHT_DP)
        return travel.coerceAtLeast(0)
    }

    /** Place the thumb at [fraction] of its track. */
    private fun positionScrubPill(fraction: Float) {
        val travel = scrubTrackTravelPx()
        scrubPill.translationY =
            fraction.coerceIn(0f, 1f) * travel.toFloat()
    }

    /** Centre the bubble on the thumb, clamped inside the track. */
    private fun positionScrubBubble() {
        val bubbleHeight = if (scrubBubble.height > 0) scrubBubble.height else dp(34)
        val centre = dp(SCRUB_TRACK_TOP_DP) + scrubPill.translationY +
            dp(SCRUB_PILL_HEIGHT_DP) / 2f
        val maxY = (scrubTrackTravelPx() + dp(SCRUB_TRACK_TOP_DP)).toFloat()
        scrubBubble.translationY =
            (centre - bubbleHeight / 2f).coerceIn(0f, maxY)
    }

    /**
     * The section a scrub landing on [index] belongs to: on Call
     * Logs the nearest date header at or above the row (the list
     * already interleaves them), on People, which has no headers,
     * the person at that row. Nothing is invented: both strings
     * come straight from the adapter items on screen.
     */
    private fun scrubSectionLabel(index: Int): String {
        if (items.isEmpty()) return ""
        val at = index.coerceIn(0, items.size - 1)
        for (i in at downTo 0) {
            val item = items[i]
            if (item is Item.Header) return item.text
        }
        return (items[at] as? Item.Display)?.display?.title.orEmpty()
    }

    /**
     * The scrubber's touch discipline: every event that lands on
     * the thumb belongs to the scrubber and is consumed, so the
     * list underneath never scrolls, pulls or taps from it. A drag
     * maps the finger's travel along the track onto the full item
     * range, so a short drag crosses the whole history.
     */
    private fun onScrubTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!scrubberAvailable()) return false
                // A grab never meets a fading thumb: cancel the
                // fade and make sure the thumb is on screen.
                cancelScrubFade()
                scrubFadedOut = false
                scrubPill.visibility = View.VISIBLE
                scrubbing = true
                scrubStartY = event.rawY
                scrubStartFraction = VaultLogic.scrollFraction(
                    listView.firstVisiblePosition, listView.childCount, items.size
                )
                // The grab (v1.2.9): the thumb springs up to held
                // size with the app's overshoot curve and its
                // shadow deepens, iOS style, and it stays enlarged
                // for the whole drag.
                scrubPill.elevation = dp(SCRUB_HELD_ELEVATION_DP).toFloat()
                scrubPill.animate().cancel()
                scrubPill.animate()
                    .alpha(1f).scaleX(SCRUB_HELD_SCALE).scaleY(SCRUB_HELD_SCALE)
                    .setDuration(SCRUB_GRAB_MS)
                    .setInterpolator(OvershootInterpolator(2.0f))
                    .start()
                scrubBubble.visibility = View.VISIBLE
                scrubToFraction(scrubStartFraction)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scrubbing) return false
                val travel = scrubTrackTravelPx()
                if (travel <= 0) return true
                val fraction = scrubStartFraction +
                    (event.rawY - scrubStartY) / travel.toFloat()
                scrubToFraction(fraction.coerceIn(0f, 1f))
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!scrubbing) return false
                endScrub()
                return true
            }
        }
        return scrubbing
    }

    /**
     * One scrub step: the thumb follows the finger's fraction, the
     * list jumps to the targeted row, and the bubble names the
     * section that row belongs to, so the landing is visible
     * before the finger lifts.
     */
    private fun scrubToFraction(fraction: Float) {
        if (items.isEmpty()) return
        positionScrubPill(fraction)
        val target = VaultLogic.scrubIndexForFraction(fraction, items.size)
        listView.setSelection(target)
        scrubBubble.text = scrubSectionLabel(target)
        positionScrubBubble()
    }

    /**
     * End a drag: bubble away, and the thumb springs back to its
     * resting size and strength (v1.2.9): the release runs on a
     * gentle overshoot, so the scale settles through its target
     * instead of stopping dead, and the shadow thins back down.
     */
    private fun endScrub() {
        scrubbing = false
        scrubBubble.visibility = View.GONE
        scrubPill.elevation = dp(SCRUB_REST_ELEVATION_DP).toFloat()
        scrubPill.animate().cancel()
        scrubPill.animate()
            .alpha(SCRUB_REST_ALPHA).scaleX(1f).scaleY(1f)
            .setDuration(SCRUB_RELEASE_MS)
            .setInterpolator(OvershootInterpolator(1.4f))
            .start()
        refreshScrubber()
        // A released drag restarts the idle timer when the list
        // itself is already idle (v1.3.0, issue #14); if it is
        // still moving, the idle transition will start it. The
        // availability guard keeps a teardown path from arming
        // a timer for a list that is no longer there.
        if (!scrubScrolling && scrubberAvailable()) scheduleScrubFade()
    }

    /**
     * The pill in its machine state: words, solid fill, clickability,
     * and the spring motion for the transition. Repeated renders in
     * the same state only refresh the content; animations fire on
     * transitions, tracked in [scrollTopRenderedState]. When the
     * state first becomes visible the pill springs in (overshoot
     * scale, a small rise, a fade); when it hides it springs back
     * down and only then goes GONE, unless [instant] asks for an
     * immediate hide (page switch, blocked list, teardown).
     */
    private fun renderScrollToTopButton(instant: Boolean = false) {
        if (!::scrollTopButton.isInitialized) return
        val state = scrollTopMachine.state
        val label = scrollToTopLabel(state)
        val previous = scrollTopRenderedState
        if (label == null) {
            stopScrollTopPulse()
            scrollTopRenderedState = state
            if (instant || scrollTopButton.visibility != View.VISIBLE) {
                scrollTopAnimGeneration++
                scrollTopButton.animate().cancel()
                scrollTopButton.visibility = View.GONE
                scrollTopButton.alpha = 1f
                scrollTopButton.scaleX = 1f
                scrollTopButton.scaleY = 1f
                scrollTopButton.translationY = 0f
            } else {
                springOutScrollTopPill()
            }
            return
        }
        scrollTopButton.text = label
        scrollTopButton.contentDescription = when (state) {
            ScrollToTopMachine.State.READY -> "Scroll to top"
            ScrollToTopMachine.State.RETURNING -> "Scrolling to top"
            ScrollToTopMachine.State.ARRIVED -> "At the top"
            ScrollToTopMachine.State.HIDDEN -> "Scroll to top"
        }
        scrollTopButton.background = scrollTopSolidBackground(state)
        scrollTopButton.setTextColor(
            when (state) {
                ScrollToTopMachine.State.RETURNING -> color(R.color.on_primary)
                ScrollToTopMachine.State.ARRIVED -> color(R.color.green)
                else -> color(R.color.text)
            }
        )
        val ready = state == ScrollToTopMachine.State.READY
        scrollTopButton.isClickable = ready
        scrollTopButton.isFocusable = ready
        // The pill floats above the list and every list area
        // sibling; reassert both on every render.
        scrollTopButton.bringToFront()
        scrollTopButton.elevation = dp(12).toFloat()
        val wasVisible = scrollTopButton.visibility == View.VISIBLE && previous != null &&
            previous != ScrollToTopMachine.State.HIDDEN
        scrollTopButton.visibility = View.VISIBLE
        scrollTopRenderedState = state
        if (!wasVisible) {
            springInScrollTopPill()
        } else if (previous != state && state == ScrollToTopMachine.State.ARRIVED) {
            popScrollTopPill()
        }
        if (state == ScrollToTopMachine.State.RETURNING) startScrollTopPulse()
            else stopScrollTopPulse()
    }

    /** Spring in: rise, fade and overshoot to full size. */
    private fun springInScrollTopPill() {
        val generation = ++scrollTopAnimGeneration
        scrollTopButton.animate().cancel()
        scrollTopButton.alpha = 0f
        scrollTopButton.scaleX = 0.55f
        scrollTopButton.scaleY = 0.55f
        scrollTopButton.translationY = dp(18).toFloat()
        scrollTopButton.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .setDuration(SCROLL_TOP_SPRING_MS)
            .setInterpolator(OvershootInterpolator(1.7f))
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (generation != scrollTopAnimGeneration) return
                    scrollTopButton.animate().setListener(null)
                }
            })
            .start()
    }

    /** Spring out: sink, shrink and fade, then GONE. */
    private fun springOutScrollTopPill() {
        val generation = ++scrollTopAnimGeneration
        scrollTopButton.animate().cancel()
        scrollTopButton.animate()
            .alpha(0f)
            .scaleX(0.6f)
            .scaleY(0.6f)
            .translationY(dp(14).toFloat())
            .setDuration(SCROLL_TOP_SPRING_OUT_MS)
            .setInterpolator(DecelerateInterpolator())
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (generation != scrollTopAnimGeneration) return
                    scrollTopButton.animate().setListener(null)
                    if (scrollTopMachine.state == ScrollToTopMachine.State.HIDDEN) {
                        scrollTopButton.visibility = View.GONE
                        scrollTopButton.alpha = 1f
                        scrollTopButton.scaleX = 1f
                        scrollTopButton.scaleY = 1f
                        scrollTopButton.translationY = 0f
                    }
                }
            })
            .start()
    }

    /** A small celebratory pop when the return lands. */
    private fun popScrollTopPill() {
        val generation = ++scrollTopAnimGeneration
        scrollTopButton.animate().cancel()
        scrollTopButton.animate()
            .scaleX(1.14f)
            .scaleY(1.14f)
            .setDuration(130)
            .setInterpolator(DecelerateInterpolator())
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (generation != scrollTopAnimGeneration) return
                    scrollTopButton.animate().setListener(null)
                    scrollTopButton.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(240)
                        .setInterpolator(OvershootInterpolator(2.0f))
                        .start()
                }
            })
            .start()
    }

    /** A gentle breathing scale while the return is running. */
    private fun startScrollTopPulse() {
        if (scrollTopPulse != null) return
        scrollTopPulse = ObjectAnimator.ofPropertyValuesHolder(
            scrollTopButton,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.06f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.06f)
        ).apply {
            duration = 460
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            interpolator = DecelerateInterpolator()
            start()
        }
    }

    private fun stopScrollTopPulse() {
        scrollTopPulse?.cancel()
        scrollTopPulse = null
        if (::scrollTopButton.isInitialized && scrollTopRenderedState != null &&
            scrollTopMachine.state != ScrollToTopMachine.State.RETURNING
        ) {
            // The pulse owned the scale while it ran; hand it back
            // unless an entrance or pop animation is using it.
            if (scrollTopButton.visibility == View.VISIBLE &&
                scrollTopRenderedState != ScrollToTopMachine.State.HIDDEN
            ) {
                scrollTopButton.scaleX = 1f
                scrollTopButton.scaleY = 1f
            }
        }
    }

    /** Tap: start the smooth return and arm its arrival watch. */
    private fun startScrollToTop() {
        updateScrollToTopFromList()
        if (!scrollTopMachine.startReturn()) {
            renderScrollToTopButton()
            return
        }
        scrollTopButton.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        renderScrollToTopButton()
        val durationMs = scrollToTopDurationMs(listView.firstVisiblePosition)
        scrollTopLastWatchPosition = listView.firstVisiblePosition
        listView.smoothScrollToPositionFromTop(0, 0, durationMs)
        scrollTopHandler.removeCallbacks(scrollTopWatch)
        scrollTopHandler.postDelayed(
            scrollTopWatch, durationMs + SCROLL_TOP_WATCH_GRACE_MS
        )
    }

    /**
     * Arrival fallback: the scroll listener normally spots the top
     * first. A return that is still making progress toward the top
     * gets another short watch; one that has stalled anywhere else
     * (a data change or an interrupted smooth scroll) falls back to
     * reporting the list's real position instead of spinning on.
     */
    private val scrollTopWatch: Runnable = object : Runnable {
        override fun run() {
            if (scrollTopMachine.state != ScrollToTopMachine.State.RETURNING) return
            if (scrollTopListAvailable() && listAtTop()) {
                scrollTopMachine.onPosition(awayFromTop = false, atTop = true)
                onScrollToTopArrived()
                renderScrollToTopButton()
                return
            }
            val position = if (::listView.isInitialized) listView.firstVisiblePosition else -1
            if (position >= 0 && position < scrollTopLastWatchPosition) {
                scrollTopLastWatchPosition = position
                scrollTopHandler.postDelayed(this, SCROLL_TOP_WATCH_GRACE_MS)
            } else {
                interruptScrollToTopReturn()
            }
        }
    }

    /** The return landed: stop the motion and start the brief beat. */
    private fun onScrollToTopArrived() {
        scrollTopHandler.removeCallbacks(scrollTopWatch)
        scrollTopHandler.removeCallbacks(scrollTopArrivedFinish)
        scrollTopHandler.postDelayed(scrollTopArrivedFinish, SCROLL_TOP_ARRIVED_MS)
    }

    private val scrollTopArrivedFinish = Runnable {
        if (scrollTopMachine.finishArrived() == ScrollToTopMachine.State.HIDDEN) {
            renderScrollToTopButton()
        }
    }

    /**
     * End a return without arriving: the user grabbed the list, the
     * list changed under it, or the fallback watch found it still
     * travelling after its time. The machine lands on whatever the
     * list is really doing.
     */
    private fun interruptScrollToTopReturn() {
        scrollTopHandler.removeCallbacks(scrollTopWatch)
        scrollTopHandler.removeCallbacks(scrollTopArrivedFinish)
        if (scrollTopListAvailable()) {
            scrollTopMachine.interrupt(
                awayFromTop = VaultLogic.isMeaningfullyAwayFromTop(listView.firstVisiblePosition),
                atTop = listAtTop()
            )
        } else {
            scrollTopMachine.reset()
        }
        renderScrollToTopButton()
    }

    /** Drop every pending scroll to top action and hide the pill at once. */
    private fun cancelScrollToTopWork() {
        scrollTopHandler.removeCallbacks(scrollTopWatch)
        scrollTopHandler.removeCallbacks(scrollTopArrivedFinish)
        stopScrollTopPulse()
        scrollTopMachine.reset()
        renderScrollToTopButton(instant = true)
        // v1.2.7: the scrubber hides with the pill on a page switch
        // or a blocked list; the rechecks behind the switch re-show
        // it mirrored to whichever page and position lands.
        // v1.3.0: a switch is a fresh context, so a faded out
        // thumb is cleared here and the rechecks may show it again.
        cancelScrubFade()
        scrubFadedOut = false
        if (::scrubPill.isInitialized && !scrubbing) {
            scrubPill.visibility = View.GONE
            scrubBubble.visibility = View.GONE
        }
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

    // ------------------------------------------------- Pause (v1.2.8)

    /** The pause in force right now, read from the stored windows. */
    private fun currentPauseWindow(): VaultLogic.PauseWindow? {
        return try {
            val helper = VaultDb.get(this)
            val now = System.currentTimeMillis()
            VaultLogic.activePauseWindow(helper.pauseWindows(helper.readableDatabase), now)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * The header indicator and the drawer pause row, restated from
     * the stored windows: green Watching while live, amber Paused
     * naming the resume time while a timed window is in force, and
     * a plain Paused for an open one (v1.2.9), which has no resume
     * time to name.
     */
    private fun updateWatchIndicator() {
        if (!::watchLabel.isInitialized) return
        val active = currentPauseWindow()
        val dotBg = watchDot.background as? GradientDrawable
        if (active != null) {
            dotBg?.setColor(color(R.color.amber))
            watchLabel.text = if (VaultLogic.isOpenPause(active)) {
                "Paused"
            } else {
                "Paused until " + VaultLogic.formatTime(active.endMs)
            }
        } else {
            dotBg?.setColor(color(R.color.green))
            watchLabel.text = "Watching"
        }
        updatePauseRow()
    }

    private fun updatePauseRow() {
        val sub = pauseSubtitle ?: return
        val active = currentPauseWindow()
        sub.text = when {
            active == null -> "Stop saving new calls for a while"
            VaultLogic.isOpenPause(active) -> "Paused until you turn it back on"
            else -> "Paused, resumes " + VaultLogic.formatTime(active.endMs)
        }
    }

    private fun updateBinRow() {
        val sub = binSubtitle ?: return
        val count = try {
            val helper = VaultDb.get(this)
            helper.binCount(helper.readableDatabase)
        } catch (e: Exception) {
            0L
        }
        sub.text = when {
            count <= 0L -> "Deleted calls are kept here for 30 days"
            count == 1L -> "1 deleted call, kept for 30 days"
            else -> count.toString() + " deleted calls, kept for 30 days"
        }
    }

    /** Tapping the header indicator while paused offers the resume. */
    private fun onWatchIndicatorTap() {
        val active = currentPauseWindow() ?: return
        val body = if (VaultLogic.isOpenPause(active)) {
            "New calls are not being saved. The vault stays paused until " +
                "you resume. Calls made during the pause are never saved, " +
                "even after it ends."
        } else {
            "New calls are not being saved. The pause ends at " +
                VaultLogic.formatTime(active.endMs) + ", or right now if you resume. " +
                "Calls made during the pause are never saved, even after it ends."
        }
        VaultDialog.message(
            this,
            "Vault is paused",
            body,
            positiveLabel = "Resume now",
            onPositive = { resumePause() },
            negativeLabel = "Stay paused",
            onNegative = null
        )
    }

    /**
     * The drawer pause action: pick a length, or resume when
     * paused. While any pause is in force, timed or open, the
     * only way offered is Resume now: pauses never stack, so a
     * new length can only be picked from a live vault.
     */
    private fun showPauseDialog() {
        val active = currentPauseWindow()
        if (active != null) {
            onWatchIndicatorTap()
            return
        }
        VaultDialog.singleChoice(
            this,
            "Pause the vault",
            listOf(
                "For 1 hour", "For 4 hours", "Until tomorrow, 8:00 AM",
                "Until I turn it back on"
            ),
            -1
        ) { index ->
            val choice = when (index) {
                0 -> VaultLogic.PauseChoice.ONE_HOUR
                1 -> VaultLogic.PauseChoice.FOUR_HOURS
                2 -> VaultLogic.PauseChoice.UNTIL_TOMORROW
                else -> VaultLogic.PauseChoice.UNTIL_TURNED_ON
            }
            startPause(choice)
        }
    }

    private fun startPause(choice: VaultLogic.PauseChoice) {
        val now = System.currentTimeMillis()
        val end = VaultLogic.pauseEndForChoice(choice, now)
        VaultExecutor.service.execute {
            try {
                val helper = VaultDb.get(this)
                helper.startPause(helper.writableDatabase, now, end)
            } catch (ignored: Exception) {
            }
            runOnUiThread {
                updateWatchIndicator()
                if (choice == VaultLogic.PauseChoice.UNTIL_TURNED_ON) {
                    toast("Vault paused until you turn it back on")
                } else {
                    toast("Vault paused until " + VaultLogic.formatTime(end))
                }
            }
        }
    }

    /**
     * Resume now: the live window's stored end is clamped to this
     * moment (the window stays on record, so calls inside it stay
     * refused), then an ordinary silent sync catches the vault up
     * with everything after the resume.
     */
    private fun resumePause() {
        VaultExecutor.service.execute {
            try {
                val helper = VaultDb.get(this)
                helper.endPauseNow(helper.writableDatabase, System.currentTimeMillis())
            } catch (ignored: Exception) {
            }
            runOnUiThread {
                updateWatchIndicator()
                toast("Vault resumed")
                refresh(silent = true)
            }
        }
    }

    // ------------------------------------------------------- Pull to sync

    /**
     * Pull to refresh on the main list (v1.2.3, GitHub issue #5).
     * The one ListView backs both the Call log and the People page,
     * so a single install covers both. Since v1.2.5 setMode saves
     * the outgoing page's scroll state and restores the incoming
     * page's, but the gesture itself is unchanged. It can only
     * start at the very top of the list
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
            // The finger itself is a list event too (v1.2.6): a
            // move, a release or a cancel queues the post layout
            // recheck, so the pill tracks the list even on any path
            // where the scroll callback is coalesced or missed.
            // The gesture handling below is untouched.
            if (e.actionMasked == MotionEvent.ACTION_MOVE ||
                e.actionMasked == MotionEvent.ACTION_UP ||
                e.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                scheduleScrollTopCheck()
            }
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
        val first = listView.getChildAt(0) ?: return listView.firstVisiblePosition == 0
        return VaultLogic.isAtTop(listView.firstVisiblePosition, first.top, listView.paddingTop)
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
        // Pause gate (v1.2.8): while a pause window is in force,
        // no import path runs from here at all: no system sync, no
        // watch check. A caller who asked for feedback is told the
        // vault is paused instead of being shown a pretend sync.
        // The date filter inside the engines is the backstop for
        // the background job paths, which never pass through here.
        val activePause = currentPauseWindow()
        if (activePause != null) {
            VaultExecutor.service.execute {
                try {
                    val helper = VaultDb.get(this)
                    helper.housekeeping(helper.writableDatabase, System.currentTimeMillis())
                } catch (ignored: Exception) {
                }
            }
            updateWatchIndicator()
            if (!silent || reportResult) {
                if (VaultLogic.isOpenPause(activePause)) {
                    toast("Vault is paused until you turn it back on, nothing imported")
                } else {
                    toast(
                        "Vault is paused until " + VaultLogic.formatTime(activePause.endMs) +
                            ", nothing imported"
                    )
                }
            }
            return
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
                    updateWatchIndicator()
                    updateBinRow()
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
                    subtitle = VaultLogic.personRowSubtitle(
                        p.numberRaw, p.callCount, p.totalDurationSec,
                        UiKit.showCallDetails(this)
                    ),
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
        // A list landing mid return ends the return: its positions
        // describe a different list than the one the return started
        // on. (Mode switches reset the machine before they rebuild,
        // so this only fires for filters, searches and refreshes.)
        if (scrollTopMachine.state == ScrollToTopMachine.State.RETURNING) {
            interruptScrollToTopReturn()
        }
        items = newItems
        adapter.notifyDataSetChanged()
        // Empty states
        if (!PermUtil.hasCallLogRead(this)) {
            emptyView.visibility = View.GONE
            updateScrollToTopFromList()
            scheduleScrollTopCheck()
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
        updateScrollToTopFromList()
        // The read above ran before the applied rows were laid
        // out; queue the post layout correction (v1.2.6).
        scheduleScrollTopCheck()
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

    /**
     * What's New (v1.4.0): written in the simplest language, covering
     * everything since the last public release (v1.2.9), because the
     * 1.3.x builds were never published. No em dashes, no jargon.
     */
    private val whatsNewText: String =
        "• One call now reads 1 CALL, not 1 CALLS.\n" +
        "• A person saved with and without a country code now shows as one person.\n" +
        "• The side scroller now fades and shrinks away smoothly after you scroll, and grows back gently.\n" +
        "• The pills now use calm solid colours with no glass look, and the scroller is easy to see on white screens.\n" +
        "• New privacy options in the menu: you can hide call details like first call, last call, total calls and total talk time, and you can hide the whole People section.\n" +
        "• This popup is new too. It appears once after each update, so you always know what changed."

    /** Version code of the running build, 0 when it cannot be read. */
    private fun currentVersionCode(): Int = try {
        val pi = packageManager.getPackageInfo(packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else pi.versionCode
    } catch (e: Exception) {
        0
    }

    /** Show the What's New popup once per version, then record it. */
    private fun showWhatsNewIfNeeded() {
        val current = currentVersionCode()
        if (current <= 0) return
        if (!VaultLogic.shouldShowWhatsNew(UiKit.whatsNewShownVersion(this), current)) return
        UiKit.setWhatsNewShownVersion(this, current)
        showWhatsNewDialog()
    }

    /** The What's New popup itself, also reopened from the drawer. */
    private fun showWhatsNewDialog() {
        VaultDialog.message(
            this,
            "What's new in " + versionName(),
            whatsNewText,
            "Got it"
        )
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
        private const val SCROLL_TOP_ARRIVED_MS = 900L
        private const val SCROLL_TOP_WATCH_GRACE_MS = 350L
        // v1.2.6 pill: the pixel estimate past which even a still
        // first row counts as away, and the spring timings.
        private const val SCROLL_TOP_AWAY_DP = 96
        private const val SCROLL_TOP_SPRING_MS = 380L
        private const val SCROLL_TOP_SPRING_OUT_MS = 220L
        // v1.2.7 fast scroll scrubber: only lists longer than this
        // grow the edge thumb; its track keeps the top inset small
        // and reserves the bottom 84dp (48dp pill + 20dp margin +
        // gap) so the thumb never reaches the scroll to top pill.
        // v1.2.9: the thumb is shorter and wider, iOS style (36x44),
        // and grows while held: scale to SCRUB_HELD_SCALE with a
        // springy overshoot on the grab, back to 1x on the release,
        // with the shadow deepening while it is in the hand.
        // v1.3.2 restyles it in the frosted language.
        // v1.3.3 replaces the frost with the solid File Master
        // bar surface (see solidBarBackground).
        private const val SCRUB_MIN_ITEMS = 25
        private const val SCRUB_PILL_WIDTH_DP = 36
        private const val SCRUB_PILL_HEIGHT_DP = 44
        private const val SCRUB_TRACK_TOP_DP = 8
        private const val SCRUB_TRACK_BOTTOM_DP = 84
        // v1.3.3: the pill rests fully opaque. The 0.55 rest alpha
        // belonged to the glass era; on a white list it halved
        // the hairline, the shadow and the grip along with the
        // fill, which is exactly the white-theme blending Arjun
        // reported. A solid File Master bar is opaque, and so
        // is this pill now.
        private const val SCRUB_REST_ALPHA = 1.0f
        private const val SCRUB_HELD_SCALE = 1.25f
        private const val SCRUB_GRAB_MS = 220L
        private const val SCRUB_RELEASE_MS = 240L
        // v1.3.0 idle hide (issue #14), animation reworked in
        // v1.3.1 and again in v1.3.2: the hide shrinks the pill
        // proportionally into the right edge and the show grows
        // it back smoothly; durations live in VaultLogic
        // (SCRUB_HIDE_MS / SCRUB_SHOW_MS), the wait before the
        // hide is VaultLogic.SCRUB_IDLE_FADE_MS.
        private const val SCRUB_REST_ELEVATION_DP = 12
        private const val SCRUB_HELD_ELEVATION_DP = 18
        private const val REQ_PERMISSIONS = 100
        private const val REQ_EXPORT = 2001
        private const val REQ_IMPORT = 2002
        private const val REQ_FOLDER = 3001
    }
}
