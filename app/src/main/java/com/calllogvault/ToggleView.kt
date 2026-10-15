package com.calllogvault

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * The app's own switch (v1.2.0), drawn on canvas in the shape of
 * the design language's iOS toggle: a 50 by 30 pill track with a
 * white knob that slides on a short decelerate curve. The
 * framework Switch rendered as whatever the OEM theme felt like
 * and never matched the app, so every switch in the app is this
 * view now.
 *
 * Colors resolve once at construction from the live theme: the
 * track cross fades between a muted blend (off) and the primary
 * (on) as the knob travels. Taps on the view itself toggle it
 * through [performClick]; the drawer rows that host a toggle call
 * [toggle] from their own click handler. [setChecked] with
 * animation off snaps, which is how rows set their initial state
 * without firing [onCheckedChange].
 */
class ToggleView(context: Context) : View(context) {

    private val density: Float = context.resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density

    private val trackOn: Int = UiKit.c(context, R.color.primary)
    private val trackOff: Int = UiKit.blendOver(
        UiKit.c(context, R.color.bg), UiKit.c(context, R.color.muted), 0.35f
    )
    private val knobColor: Int = 0xFFFFFFFF.toInt()
    private val knobEdge: Int = UiKit.blendOver(
        UiKit.c(context, R.color.bg), UiKit.c(context, R.color.text), 0.14f
    )

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val knobEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    /** 0 = fully off (knob left), 1 = fully on (knob right). */
    private var fraction: Float = 0f
    private var animator: ValueAnimator? = null

    var isChecked: Boolean = false
        private set

    var onCheckedChange: ((Boolean) -> Unit)? = null

    init {
        isClickable = true
        isFocusable = true
        knobEdgePaint.color = knobEdge
    }

    override fun performClick(): Boolean {
        val handled = super.performClick()
        if (isEnabled) setChecked(!isChecked)
        return handled || true
    }

    fun toggle() {
        if (isEnabled) setChecked(!isChecked)
    }

    fun setChecked(value: Boolean, animate: Boolean = true) {
        val changed = isChecked != value
        isChecked = value
        animator?.cancel()
        animator = null
        val target = if (value) 1f else 0f
        if (animate && fraction != target) {
            animator = ValueAnimator.ofFloat(fraction, target).apply {
                duration = ANIM_MS
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    fraction = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            fraction = target
            invalidate()
        }
        if (changed) onCheckedChange?.invoke(value)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(dp(WIDTH_DP).toInt(), dp(HEIGHT_DP).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val alpha = if (isEnabled) 255 else 120
        // Track: a full pill whose color cross fades with the knob.
        trackPaint.color = UiKit.blendOver(trackOff, trackOn, fraction)
        trackPaint.alpha = alpha
        canvas.drawRoundRect(0f, 0f, w, h, h / 2f, h / 2f, trackPaint)
        // Knob: white, inset by the padding, traveling the track.
        val pad = dp(KNOB_PAD_DP)
        val r = h / 2f - pad
        val cx = pad + r + fraction * (w - 2 * pad - 2 * r)
        val cy = h / 2f
        knobPaint.color = knobColor
        knobPaint.alpha = alpha
        canvas.drawCircle(cx, cy, r, knobPaint)
        knobEdgePaint.alpha = alpha
        canvas.drawCircle(cx, cy, r - 0.5f, knobEdgePaint)
    }

    companion object {
        private const val WIDTH_DP = 50f
        private const val HEIGHT_DP = 30f
        private const val KNOB_PAD_DP = 2.5f
        private const val ANIM_MS = 180L
    }
}
