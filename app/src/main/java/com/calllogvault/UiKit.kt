package com.calllogvault

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.TextView

/**
 * Shared UI helpers: press feedback,
 * color blending for avatar tiles, the medium typeface (created once),
 * and the Light / Dark / AMOLED appearance presets.
 *
 * Appearance v2 (v1.0.2): the mode lives under a new prefs key
 * "appearance_v2" with VaultLogic.APPEARANCE_* values (Light, Dark,
 * Auto). A separate boolean pref, "amoled_for_dark", says whether
 * the Dark side renders AMOLED. Older state (the v1.0.1 values
 * under the old "appearance" key, a stored AMOLED preset, the
 * v1.0.8 "appearance_dark_flavour") is read exactly once, through
 * VaultLogic.migrateAppearance, and rewritten in the current
 * scheme, so old data can never be misread and every user keeps
 * the exact look they had.
 * Framework APIs only, no AppCompat.
 */
fun View.pressFeedback() {
    setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                v.animate().cancel()
                v.alpha = 0.65f
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.animate().cancel()
                v.animate().alpha(1f)
                    .setDuration(150)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        }
        false
    }
}

object UiKit {

    const val PREFS = "vault_prefs"
    private const val KEY_APPEARANCE_V2 = "appearance_v2"
    private const val KEY_APPEARANCE_LEGACY = "appearance"
    private const val KEY_AMOLED_FOR_DARK = "amoled_for_dark"

    /** Read only by the one time migration; never written anymore. */
    private const val KEY_DARK_FLAVOUR = "appearance_dark_flavour"

    const val APPEARANCE_LIGHT = VaultLogic.APPEARANCE_LIGHT
    const val APPEARANCE_DARK = VaultLogic.APPEARANCE_DARK
    const val APPEARANCE_AMOLED = VaultLogic.APPEARANCE_AMOLED
    const val APPEARANCE_AUTO = VaultLogic.APPEARANCE_AUTO

    /** The one medium typeface for the whole app, created exactly once. */
    val mediumTypeface: Typeface by lazy {
        Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    /**
     * The stored appearance state (mode plus the AMOLED switch),
     * kept in memory after the first resolve (v1.0.6). appearance()
     * is called from attachBaseContext, from both activities'
     * getResources() overrides on effectively every resource lookup
     * path, and from wrapContext, so reading SharedPreferences (a
     * disk backed map) on every call taxed the whole UI for values
     * that change only when the user taps a theme control. The
     * caches make those call sites a volatile read plus a flag test;
     * resolution results are identical.
     */
    @Volatile
    private var cachedAppearance: Int? = null

    @Volatile
    private var cachedAmoledForDark: Boolean? = null

    /**
     * Fill both caches from prefs. While the amoled_for_dark key is
     * absent the stored state predates the current scheme (or there
     * is none yet), so it goes through the one time migration in
     * VaultLogic and is rewritten as a mode plus the switch; after
     * that, reads are direct and the old flavour key is never
     * consulted again.
     */
    private fun resolveStored(context: Context) {
        if (cachedAppearance != null && cachedAmoledForDark != null) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_AMOLED_FOR_DARK)) {
            val hasV2 = prefs.contains(KEY_APPEARANCE_V2)
            val state = VaultLogic.migrateAppearance(
                hasV2 = hasV2,
                v2 = if (hasV2) prefs.getInt(KEY_APPEARANCE_V2, APPEARANCE_DARK) else 0,
                hasFlavour = prefs.contains(KEY_DARK_FLAVOUR),
                flavour = prefs.getInt(KEY_DARK_FLAVOUR, APPEARANCE_AMOLED),
                hasLegacy = prefs.contains(KEY_APPEARANCE_LEGACY),
                legacy = prefs.getInt(KEY_APPEARANCE_LEGACY, 0)
            )
            prefs.edit()
                .putInt(KEY_APPEARANCE_V2, state.mode)
                .putBoolean(KEY_AMOLED_FOR_DARK, state.amoledForDark)
                .apply()
            cachedAppearance = state.mode
            cachedAmoledForDark = state.amoledForDark
            return
        }
        val stored = prefs.getInt(KEY_APPEARANCE_V2, APPEARANCE_DARK)
        cachedAppearance =
            if (stored == APPEARANCE_LIGHT || stored == APPEARANCE_DARK ||
                stored == APPEARANCE_AUTO
            ) stored else APPEARANCE_DARK
        cachedAmoledForDark = prefs.getBoolean(KEY_AMOLED_FOR_DARK, true)
    }

    /** The stored mode: Light, Dark or Auto. */
    fun appearance(context: Context): Int {
        resolveStored(context)
        return cachedAppearance!!
    }

    fun setAppearance(context: Context, value: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_APPEARANCE_V2, value)
            .apply()
        cachedAppearance = value
    }

    /** Whether the Dark side (Dark mode, and Auto at night) renders AMOLED. */
    fun amoledForDark(context: Context): Boolean {
        resolveStored(context)
        return cachedAmoledForDark!!
    }

    fun setAmoledForDark(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AMOLED_FOR_DARK, value)
            .apply()
        cachedAmoledForDark = value
    }

    /** True when [res] carries the night uiMode flag. */
    fun systemNight(res: Resources): Boolean =
        (res.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /**
     * The concrete preset to render with: the stored mode resolved
     * through VaultLogic against the AMOLED switch and the night
     * flag of [res]. Callers inside a getResources() override pass
     * the base resources they already hold; nothing here reads
     * context.resources, so that call path can never loop.
     */
    fun concreteAppearance(context: Context, res: Resources): Int =
        VaultLogic.resolveAppearance(appearance(context), amoledForDark(context), systemNight(res))

    /** Concrete preset for ordinary UI thread call sites. */
    fun concreteAppearance(context: Context): Int =
        concreteAppearance(context, context.resources)

    fun isAmoled(context: Context, res: Resources): Boolean =
        concreteAppearance(context, res) == APPEARANCE_AMOLED

    fun appearanceName(value: Int): String = VaultLogic.appearanceName(value)

    /**
     * Force the night flag to match the chosen mode (the AMOLED
     * rendering of Dark is night plus the black resource
     * substitution below). The Auto mode is the one exception: the
     * system night flag passes through untouched, so the concrete
     * preset can follow the device (and the manifest leaves uiMode
     * recreation to the system, so a flip while the app runs
     * rethemes through a fresh activity).
     */
    fun wrapContext(newBase: Context): Context {
        val mode = try { appearance(newBase) } catch (e: Exception) { APPEARANCE_DARK }
        if (mode == APPEARANCE_AUTO) return newBase
        val night = if (mode == APPEARANCE_LIGHT)
            Configuration.UI_MODE_NIGHT_NO else Configuration.UI_MODE_NIGHT_YES
        val cfg = Configuration(newBase.resources.configuration)
        cfg.uiMode = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        return newBase.createConfigurationContext(cfg)
    }

    /** Resource wrapping for the AMOLED preset: only AMOLED wraps. */
    fun wrapResources(context: Context, base: Resources, cache: Resources?): Resources {
        if (!isAmoled(context, base)) return base
        return cache ?: AmoledResources(base)
    }

    /** Theme aware color lookup. */
    fun c(context: Context, id: Int): Int {
        val r = context.resources
        return if (r is AmoledResources) r.getColor(id) else r.getColor(id, null)
    }

    /** Blend opaque [fg] over opaque [bg] at [alpha] (0..1). */
    fun blendOver(bg: Int, fg: Int, alpha: Float): Int {
        val a = alpha.coerceIn(0f, 1f)
        fun ch(v: Int, shift: Int) = (v shr shift) and 0xFF
        val r = (ch(bg, 16) * (1 - a) + ch(fg, 16) * a + 0.5f).toInt()
        val g = (ch(bg, 8) * (1 - a) + ch(fg, 8) * a + 0.5f).toInt()
        val b = (ch(bg, 0) * (1 - a) + ch(fg, 0) * a + 0.5f).toInt()
        return 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
    }

    fun dp(context: Context, v: Float): Int =
        (v * context.resources.displayMetrics.density + 0.5f).toInt()

    /**
     * The list pressed highlight since v1.0.5: one StateListDrawable
     * per list, shared by every row through the ListView selector.
     * Rows used to each carry the theme's ripple background; thousands
     * of ripple drawables churning state as rows recycled during
     * flings were a real per frame cost. This is a flat blend of the
     * text color over the row background at 8 percent for the pressed
     * and focused states, close to what the ripple showed over the
     * same surface. Rows draw transparent, so the selector (drawn by
     * the list behind its children) shows through.
     */
    fun rowSelector(context: Context): StateListDrawable {
        val color = blendOver(c(context, R.color.bg), c(context, R.color.text), 0.08f)
        val selector = StateListDrawable()
        selector.addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(color))
        selector.addState(
            intArrayOf(android.R.attr.state_focused, android.R.attr.state_enabled),
            ColorDrawable(color)
        )
        return selector
    }

    /** Theme colors an avatar bind needs, resolved once by the caller. */
    class AvatarColors(val card: Int, val tileBg: Int, val muted: Int)

    fun avatarColors(context: Context): AvatarColors = AvatarColors(
        card = c(context, R.color.card),
        tileBg = c(context, R.color.tile_bg),
        muted = c(context, R.color.muted)
    )

    /**
     * Bind an avatar into a view that already exists with a background
     * drawable that already exists. This is the hot path used by list
     * rows while scrolling: it creates nothing, loads no typeface and
     * does no resource lookups, it only sets text and colors.
     */
    fun bindAvatar(
        tv: TextView,
        bg: GradientDrawable,
        colors: AvatarColors,
        displayName: String,
        numberRaw: String,
        key: String,
        sizeDp: Int
    ) {
        val initials = VaultLogic.initialsFor(displayName, numberRaw)
        if (initials.isEmpty()) {
            tv.text = "?"
            tv.textSize = sizeDp * 0.42f
            tv.typeface = Typeface.DEFAULT
            tv.setTextColor(colors.muted)
            bg.setColor(colors.tileBg)
        } else {
            val base = VaultLogic.avatarColor(key)
            tv.text = initials
            tv.textSize = if (sizeDp >= 60) 22f else 15f
            tv.typeface = mediumTypeface
            tv.setTextColor(blendOver(colors.card, base, 0.92f))
            bg.setColor(blendOver(colors.card, base, 0.32f))
        }
        if (tv.background !== bg) tv.background = bg
    }

    /**
     * One shot avatar for headers (not a list bind): creates the view
     * and its oval drawable once, then binds into them.
     */
    fun avatar(context: Context, displayName: String, numberRaw: String, key: String, sizeDp: Int): TextView {
        val d = dp(context, sizeDp.toFloat())
        val tv = TextView(context)
        tv.layoutParams = android.widget.LinearLayout.LayoutParams(d, d)
        tv.gravity = Gravity.CENTER
        tv.includeFontPadding = false
        val bg = GradientDrawable().apply { shape = GradientDrawable.OVAL }
        bindAvatar(tv, bg, avatarColors(context), displayName, numberRaw, key, sizeDp)
        return tv
    }
}
