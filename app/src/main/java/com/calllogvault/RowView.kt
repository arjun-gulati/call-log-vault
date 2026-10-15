package com.calllogvault

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.text.StaticLayout
import android.view.View

/** Drawable resource for a call type, shared by both list screens. */
fun callTypeIconRes(type: Int): Int = when (type) {
    VaultLogic.TYPE_INCOMING -> R.drawable.ic_call_in
    VaultLogic.TYPE_OUTGOING -> R.drawable.ic_call_out
    VaultLogic.TYPE_MISSED -> R.drawable.ic_call_missed
    VaultLogic.TYPE_REJECTED -> R.drawable.ic_call_rejected
    VaultLogic.TYPE_BLOCKED -> R.drawable.ic_call_blocked
    VaultLogic.TYPE_VOICEMAIL -> R.drawable.ic_voicemail
    else -> R.drawable.ic_call_in
}

/**
 * Everything one list row shows, fully precomputed when the item list
 * is built (see VaultLogic row display strings). Binding a row is
 * assigning one of these to a RowView: no formatting, no color
 * resolution, no string building happens at bind or draw time.
 *
 * The texts also arrive as prebuilt StaticLayouts (see
 * RowLayoutFactory), shaped once per item per width, so a draw never
 * re-runs text shaping. [hasIcon] records whether the subtitle has a
 * type icon at its left (it reserves the icon's width in the layout).
 *
 * [avatarInitials] null means the compact timeline variant: no avatar
 * circle, the type icon is drawn at the left instead and the row is
 * shorter. [avatarBg] and [avatarFg] are resolved palette colors, only
 * meaningful when [avatarInitials] is not null.
 */
data class RowDisplay(
    val title: String,
    val subtitle: String,
    val rightTop: String,
    val rightBottom: String,
    val avatarInitials: String?,
    val avatarBg: Int,
    val avatarFg: Int,
    val typeIcon: Int,
    val showDivider: Boolean,
    val numberNorm: String,
    val compact: Boolean = false,
    val hasIcon: Boolean = false,
    // Resolved tint color for the type icon, used by compact rows
    // to back the icon with a soft chip (v1.2.0). 0 means no chip.
    val typeTint: Int = 0
) {
    /**
     * Selection state for the person timeline's multi select
     * (v1.2.0). A body property, flipped by the activity when the
     * selection changes; the next bind draws the tint and the
     * check badge. Not part of equality: two displays with the
     * same content are the same row either way.
     */
    var selected: Boolean = false
    /** Prebuilt text layouts for [layoutsWidthPx], set by RowLayoutFactory. */
    var titleLayout: StaticLayout? = null
    var subLayout: StaticLayout? = null
    var rightLayout: StaticLayout? = null
    var layoutsWidthPx: Int = -1
}

/**
 * A list row as a single custom drawn view: no child views, no layouts
 * of its own, no background drawable.
 *
 * The v1.0.2 rows were a nested hierarchy of about nine views that
 * ListView had to measure on every bind during a fling. v1.0.3 made
 * the row one view drawing with Canvas.drawText, but drawText still
 * re-shapes its text on every draw (Minikin, emoji fallback and all),
 * and every row carried its own RippleDrawable background whose state
 * churned as rows recycled. v1.0.5 removes both costs: the texts are
 * prebuilt StaticLayouts drawn verbatim, and the row has no
 * background at all (the pressed highlight is now the ListView
 * selector, one drawable for the whole list). What remains in onDraw
 * is the avatar circle, the initials (two glyphs, drawn as before),
 * the icon bitmap, three layout draws and the divider line.
 *
 * The paints here mirror RowPaints exactly; they exist so the block
 * centering math can use the same font metrics the layouts were
 * built with.
 */
class RowView(context: Context) : View(context) {

    private val dpF: Float = context.resources.displayMetrics.density
    private val spF: Float = context.resources.displayMetrics.scaledDensity
    private fun dp(v: Float): Float = v * dpF
    private fun sp(v: Float): Float = v * spF

    private val textColor: Int = UiKit.c(context, R.color.text)
    private val mutedColor: Int = UiKit.c(context, R.color.muted)
    private val borderColor: Int = UiKit.c(context, R.color.border)
    private val cardColor: Int = UiKit.c(context, R.color.card)
    private val primaryColor: Int = UiKit.c(context, R.color.primary)

    private val avatarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    // Selection tint and chip paints (v1.2.0): flat precomputed
    // colors, no per draw allocation, same discipline as the rest.
    private val selectionPaint = Paint().apply {
        color = UiKit.blendOver(UiKit.c(context, R.color.bg), primaryColor, 0.14f)
        style = Paint.Style.FILL
    }
    private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = 0xFFFFFFFF.toInt()
    }
    private val checkPath = android.graphics.Path()
    private val initialsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = UiKit.mediumTypeface
        textAlign = Paint.Align.CENTER
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = UiKit.mediumTypeface
        color = textColor
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = mutedColor }
    private val rightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = mutedColor
        textAlign = Paint.Align.RIGHT
    }
    private val dividerPaint = Paint().apply { color = borderColor }

    private var item: RowDisplay? = null
    private var icons: Map<Int, Bitmap> = emptyMap()
    private var heightPx: Int = (ROW_HEIGHT_DP * dpF).toInt()

    /**
     * Supplied by the adapter: rebuilds one item's layouts when the
     * view's width is not the width they were built for (rotation,
     * split screen). In portrait use the item build already used the
     * list width, so this essentially never runs.
     */
    var layoutFactory: RowLayoutFactory? = null

    init {
        // No background since v1.0.5: the per row ripple drawable is
        // gone, the ListView selector draws the pressed highlight.
        isClickable = false
    }

    fun setItem(newItem: RowDisplay, newIcons: Map<Int, Bitmap>) {
        item = newItem
        icons = newIcons
        titlePaint.textSize = sp(if (newItem.compact) 15f else 16f)
        subPaint.textSize = sp(13f)
        rightPaint.textSize = sp(if (newItem.compact) 13f else 12f)
        initialsPaint.textSize = sp(15f)
        val h = ((if (newItem.compact) COMPACT_HEIGHT_DP else ROW_HEIGHT_DP) * dpF).toInt()
        if (h != heightPx) {
            heightPx = h
            requestLayout()
        }
        ensureLayouts()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val wMode = MeasureSpec.getMode(widthMeasureSpec)
        val w = if (wMode == MeasureSpec.UNSPECIFIED) suggestedMinimumWidth
        else MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, heightPx)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw) ensureLayouts()
    }

    /** Rebuild the item's layouts if they were built for another width. */
    private fun ensureLayouts() {
        val d = item ?: return
        val w = width
        if (w <= 0) return
        if (d.titleLayout != null && d.layoutsWidthPx == w) return
        layoutFactory?.buildInto(d, w)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d = item ?: return
        ensureLayouts()
        val w = width.toFloat()
        val cy = height / 2f
        val textStart = if (d.compact) dp(30f) else dp(82f)

        // Selection (v1.2.0): a flat primary tint under everything.
        if (d.selected) {
            canvas.drawRect(0f, 0f, w, height.toFloat(), selectionPaint)
        }

        // Left: avatar circle with initials, or (compact) the type
        // icon on its soft tinted chip.
        if (d.compact) {
            val bmp = icons[d.typeIcon]
            if (bmp != null) {
                if (d.typeTint != 0) {
                    chipPaint.color = UiKit.blendOver(cardColor, d.typeTint, 0.16f)
                    val half = dp(13f)
                    canvas.drawRoundRect(
                        0f, cy - half, dp(26f), cy + half,
                        dp(8f), dp(8f), chipPaint
                    )
                    canvas.drawBitmap(bmp, dp(13f) - bmp.width / 2f, cy - bmp.height / 2f, null)
                } else {
                    canvas.drawBitmap(bmp, 0f, cy - bmp.height / 2f, null)
                }
            }
        } else if (d.avatarInitials != null) {
            val cx = dp(44f)
            avatarPaint.color = d.avatarBg
            canvas.drawCircle(cx, cy, dp(24f), avatarPaint)
            initialsPaint.color = d.avatarFg
            val fm = initialsPaint.fontMetrics
            canvas.drawText(d.avatarInitials, cx, cy - (fm.ascent + fm.descent) / 2f, initialsPaint)
        }

        // Middle: title over subtitle, the pair centered as one block,
        // the same centering math as the drawText version. The texts
        // are the prebuilt layouts, drawn at the block positions.
        val titleFm = titlePaint.fontMetrics
        val subFm = subPaint.fontMetrics
        val titleLineH = titleFm.descent - titleFm.ascent
        val subLineH = subFm.descent - subFm.ascent
        val gap = dp(2f)
        val blockTop = cy - (titleLineH + gap + subLineH) / 2f
        val titleLayout = d.titleLayout
        if (titleLayout != null) {
            canvas.save()
            canvas.translate(textStart, blockTop)
            titleLayout.draw(canvas)
            canvas.restore()
        }
        var subX = textStart
        val subTop = blockTop + titleLineH + gap
        if (!d.compact && d.typeIcon >= 0) {
            val bmp = icons[d.typeIcon]
            if (bmp != null) {
                canvas.drawBitmap(bmp, textStart, subTop + subLineH / 2f - bmp.height / 2f, null)
                subX += dp(19f)
            }
        }
        val subLayout = d.subLayout
        if (subLayout != null) {
            canvas.save()
            canvas.translate(subX, subTop)
            subLayout.draw(canvas)
            canvas.restore()
        }

        // Right column: the prebuilt right aligned layout, its right
        // edge at the same 20dp inset. Normal rows center the two line
        // block like the old per line draws; compact rows center the
        // single line on the row middle. A selected row trades the
        // right column for the check badge (v1.2.0).
        val rightLayout = d.rightLayout
        if (d.selected) {
            val cx = w - dp(20f) - dp(11f)
            avatarPaint.color = primaryColor
            canvas.drawCircle(cx, cy, dp(11f), avatarPaint)
            checkPaint.strokeWidth = dp(2f)
            checkPath.reset()
            checkPath.moveTo(cx - dp(4.5f), cy + dp(0.5f))
            checkPath.lineTo(cx - dp(1.5f), cy + dp(3.8f))
            checkPath.lineTo(cx + dp(5f), cy - dp(3.8f))
            canvas.drawPath(checkPath, checkPaint)
        } else if (rightLayout != null) {
            val rightTop = if (d.compact) {
                cy - rightLayout.height / 2f
            } else {
                val fm = rightPaint.fontMetrics
                val lineH = fm.descent - fm.ascent
                cy - (2 * lineH + gap) / 2f
            }
            canvas.save()
            canvas.translate(w - dp(20f) - rightLayout.width, rightTop)
            rightLayout.draw(canvas)
            canvas.restore()
        }

        // Divider, inset like the old separate divider view.
        if (d.showDivider) {
            val inset = if (d.compact) dp(30f) else dp(82f)
            canvas.drawRect(inset, height - dp(1f), w, height.toFloat(), dividerPaint)
        }
    }

    companion object {
        const val ROW_HEIGHT_DP = 76f
        const val COMPACT_HEIGHT_DP = 60f

        // Text geometry shared with RowLayoutFactory (v1.0.5).
        const val LEFT_DP = 82f
        const val LEFT_COMPACT_DP = 30f
        const val RIGHT_INSET_DP = 20f
        const val RIGHT_GAP_DP = 12f
        const val ICON_SPACE_DP = 19f
        const val LINE_GAP_DP = 2f

        /** The call types that have a subtitle icon bitmap. */
        val ICON_TYPES = intArrayOf(
            VaultLogic.TYPE_INCOMING, VaultLogic.TYPE_OUTGOING, VaultLogic.TYPE_MISSED,
            VaultLogic.TYPE_VOICEMAIL, VaultLogic.TYPE_REJECTED, VaultLogic.TYPE_BLOCKED
        )

        /**
         * Render every call type vector drawable once into a tinted
         * bitmap of [sizeDp]. The tint is applied with a SRC_IN color
         * filter, the same treatment ImageView.setColorFilter gave the
         * icons in the old rows, so the colors match exactly.
         */
        fun buildIconBitmaps(
            context: Context,
            sizeDp: Int,
            tintFor: (Int) -> Int
        ): Map<Int, Bitmap> {
            val px = (sizeDp * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
            val out = HashMap<Int, Bitmap>()
            for (type in ICON_TYPES) {
                val drawable = context.getDrawable(callTypeIconRes(type)) ?: continue
                drawable.setColorFilter(PorterDuffColorFilter(tintFor(type), PorterDuff.Mode.SRC_IN))
                val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                drawable.setBounds(0, 0, px, px)
                drawable.draw(canvas)
                out[type] = bmp
            }
            return out
        }
    }
}
