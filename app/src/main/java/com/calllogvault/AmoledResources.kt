package com.calllogvault

import android.content.res.ColorStateList
import android.content.res.Configuration
import android.content.res.Resources

/**
 * AMOLED palette: a true black page with lifted dark surfaces.
 *
 * Android has no resource qualifier for "amoled", so the third theme is
 * a Resources wrapper: night mode is forced on by UiKit.wrapContext
 * (so values-night applies as the base) and surface colors are
 * substituted at lookup time. The page background stays true black,
 * but cards, tiles and inputs sit one step above it (the v1.2.0
 * layering), so sections read as layers on the black instead of
 * vanishing into it. Accents stay the dark ones (primary #84A3CD,
 * text #EDEFF3, muted #ABB0BA) and pass through untouched.
 */
class AmoledResources(orig: Resources) :
    Resources(orig.assets, orig.displayMetrics, Configuration(orig.configuration)) {

    override fun getColor(id: Int): Int = AMOLED[id] ?: super.getColor(id)

    override fun getColor(id: Int, theme: Theme?): Int =
        AMOLED[id] ?: super.getColor(id, theme)

    override fun getColorStateList(id: Int): ColorStateList =
        AMOLED[id]?.let { ColorStateList.valueOf(it) } ?: super.getColorStateList(id)

    override fun getColorStateList(id: Int, theme: Theme?): ColorStateList =
        AMOLED[id]?.let { ColorStateList.valueOf(it) } ?: super.getColorStateList(id, theme)

    companion object {
        private const val PRIMARY = 0xFF84A3CD.toInt()
        private const val BLACK = 0xFF000000.toInt()
        private const val BORDER = 0xFF2A2F3A.toInt()

        // Lifted surfaces over the true black page: surface is the
        // quietest layer (segment tracks, drawer stat blocks), card
        // and the unselected pill sit one step higher, tiles higher
        // still so their icons keep a visible home.
        private const val SURFACE = 0xFF101319.toInt()
        private const val CARD = 0xFF171A21.toInt()
        private const val TILE = 0xFF222834.toInt()

        private val AMOLED: Map<Int, Int> = mapOf(
            // The page itself stays true black; surfaces lift off it.
            R.color.bg to BLACK,
            R.color.card to CARD,
            R.color.surface to SURFACE,
            R.color.tile_bg to TILE,
            R.color.pill_unsel_bg to CARD,
            R.color.input_fill to SURFACE,
            // Borders are how cards and segments separate on black.
            R.color.border to BORDER,
            R.color.pill_unsel_border to BORDER,
            // Selected pill stays visible on black: primary at about
            // 12 percent alpha with a full strength primary border.
            R.color.pill_sel_bg to 0x1F84A3CD.toInt(),
            R.color.pill_sel_border to PRIMARY,
            R.color.pill_sel_text to 0xFFEDEFF3.toInt(),
            R.color.pill_unsel_text to 0xFFABB0BA.toInt(),
            // Dark accents, pinned so a plain getColor call can never
            // fall back to a light value.
            R.color.primary to PRIMARY,
            R.color.primary_dark to 0xFF5F7CA6.toInt(),
            R.color.accent to PRIMARY,
            R.color.tile_icon to PRIMARY,
            R.color.on_primary to 0xFF10131A.toInt(),
            R.color.text to 0xFFEDEFF3.toInt(),
            R.color.muted to 0xFFABB0BA.toInt(),
            R.color.eyebrow to 0xFFABB0BA.toInt()
        )
    }
}
