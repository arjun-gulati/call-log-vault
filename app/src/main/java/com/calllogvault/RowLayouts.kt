package com.calllogvault

import android.content.Context
import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import kotlin.math.ceil

/**
 * The text paints the row layouts are built with (v1.0.5). One set per
 * activity, resolved once like the avatar colors: title in the medium
 * typeface at 16sp (15sp compact), subtitle 13sp muted, right column
 * 12sp muted (13sp compact). RowView keeps its own paints with exactly
 * this configuration for its font metrics, so the block centering math
 * in onDraw matches the layouts built here.
 *
 * Why layouts at all: Canvas.drawText re-runs text shaping (Minikin,
 * including emoji fallback) on every call. A prebuilt StaticLayout is
 * shaped once, when the item list is built, and drawing it during a
 * fling is just blitting the shaped lines.
 */
class RowPaints(context: Context) {

    val density: Float = context.resources.displayMetrics.density
    private val scaledDensity: Float = context.resources.displayMetrics.scaledDensity

    fun dp(v: Float): Float = v * density
    private fun sp(v: Float): Float = v * scaledDensity

    private val textColor: Int = UiKit.c(context, R.color.text)
    private val mutedColor: Int = UiKit.c(context, R.color.muted)

    val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = UiKit.mediumTypeface
        color = textColor
        textSize = sp(16f)
    }
    val titleCompactPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = UiKit.mediumTypeface
        color = textColor
        textSize = sp(15f)
    }
    val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = mutedColor
        textSize = sp(13f)
    }
    val rightPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = mutedColor
        textSize = sp(12f)
    }
    val rightCompactPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = mutedColor
        textSize = sp(13f)
    }
}

/**
 * Builds the prebuilt StaticLayouts for one [RowDisplay] at a given
 * list width and stores them back on the display item. Called when
 * the item list is built (MainActivity.buildItems, the PersonActivity
 * timeline build), and again by RowView through the adapter's factory
 * reference if a row ever finds itself at a different width than the
 * layouts were built for (rotation, split screen resize).
 *
 * Width math, identical to the ellipsize pass RowView used to run:
 * text starts at 82dp (30dp compact), the right inset is 20dp, the
 * right column takes the wider of its two measured strings plus a
 * 12dp gap when it holds anything, and the subtitle reserves another
 * 19dp when a type icon sits at its left.
 */
class RowLayoutFactory(private val paints: RowPaints) {

    fun buildInto(d: RowDisplay, listWidthPx: Int) {
        val rightPaint = if (d.compact) paints.rightCompactPaint else paints.rightPaint
        var rightW = 0f
        if (d.rightTop.isNotEmpty()) rightW = maxOf(rightW, rightPaint.measureText(d.rightTop))
        if (d.rightBottom.isNotEmpty()) rightW = maxOf(rightW, rightPaint.measureText(d.rightBottom))

        val textStart = paints.dp(if (d.compact) RowView.LEFT_COMPACT_DP else RowView.LEFT_DP)
        var avail = listWidthPx.toFloat() - textStart - paints.dp(RowView.RIGHT_INSET_DP) -
            (if (rightW > 0f) rightW + paints.dp(RowView.RIGHT_GAP_DP) else 0f)
        if (avail < 1f) avail = 1f

        val titlePaint = if (d.compact) paints.titleCompactPaint else paints.titlePaint
        d.titleLayout = singleLine(d.title, titlePaint, avail.toInt())
        val subAvail = avail - if (!d.compact && d.hasIcon) paints.dp(RowView.ICON_SPACE_DP) else 0f
        d.subLayout = singleLine(d.subtitle, paints.subPaint, (if (subAvail < 1f) 1f else subAvail).toInt())
        d.rightLayout = buildRight(d, rightPaint, rightW)
        d.layoutsWidthPx = listWidthPx
    }

    private fun singleLine(text: String, paint: TextPaint, widthPx: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, widthPx.coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setMaxLines(1)
            .setIncludePad(false)
            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build()

    /**
     * The right column as one layout: two right aligned lines for
     * normal rows (joined with a newline so an empty bottom line keeps
     * the top line at the top, exactly where the old per line draws
     * put it, with the same 2dp line gap), one line for compact rows,
     * and null when there is nothing to show.
     */
    private fun buildRight(d: RowDisplay, paint: TextPaint, measuredW: Float): StaticLayout? {
        if (d.compact) {
            val single = d.rightBottom.ifEmpty { d.rightTop }
            if (single.isEmpty()) return null
            return StaticLayout.Builder.obtain(single, 0, single.length, paint, exactWidth(measuredW))
                .setAlignment(Layout.Alignment.ALIGN_OPPOSITE)
                .setMaxLines(1)
                .setIncludePad(false)
                .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .build()
        }
        if (d.rightTop.isEmpty() && d.rightBottom.isEmpty()) return null
        val text = d.rightTop + "\n" + d.rightBottom
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, exactWidth(measuredW))
            .setAlignment(Layout.Alignment.ALIGN_OPPOSITE)
            .setMaxLines(2)
            .setLineSpacing(paints.dp(RowView.LINE_GAP_DP), 1f)
            .setIncludePad(false)
            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build()
    }

    /**
     * Width for the right column layout: the measured text width,
     * rounded up plus a pixel so right aligned text can never wrap
     * onto a second line over a rounding difference.
     */
    private fun exactWidth(measured: Float): Int =
        (ceil(measured).toInt() + 1).coerceAtLeast(1)
}
