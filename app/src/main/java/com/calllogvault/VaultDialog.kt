package com.calllogvault

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * A running progress popup in the custom dialog shape. Stands in
 * for the framework ProgressDialog, which kept its stock look in
 * every theme.
 */
class VaultProgress internal constructor(private val dialog: Dialog) {

    val isShowing: Boolean
        get() = try {
            dialog.isShowing
        } catch (e: Exception) {
            false
        }

    fun dismiss() {
        try {
            dialog.dismiss()
        } catch (e: Exception) {
            // Already gone.
        }
    }
}

/**
 * The app's dialog component (v1.2.0). The framework AlertDialog
 * looked flat and stock in every theme, so every popup in the app
 * is built here instead: a rounded card in the live theme (on
 * AMOLED a lifted dark card on the true black page), generous
 * padding, the design language type scale, and pill buttons: a
 * filled primary for the main action, a quiet text button to
 * cancel, a filled red for destructive confirms.
 *
 * All builders take the activity so colors and type resolve
 * through its (possibly AMOLED wrapped) resources, exactly like
 * the rest of the UI.
 */
object VaultDialog {

    private fun dp(c: Activity, v: Int): Int = UiKit.dp(c, v.toFloat())
    private fun color(c: Activity, id: Int): Int = UiKit.c(c, id)

    /** A plain message dialog with one OK button, or an OK and a quiet second button. */
    fun message(
        activity: Activity,
        title: String,
        message: String,
        positiveLabel: String = "OK",
        onPositive: (() -> Unit)? = null,
        negativeLabel: String? = null,
        onNegative: (() -> Unit)? = null,
        negativeDestructive: Boolean = false
    ): Dialog {
        return show(
            activity, title, message, null,
            positiveLabel, onPositive,
            negativeLabel, onNegative,
            positiveDestructive = false,
            negativeDestructive = negativeDestructive,
            cancelable = true
        )
    }

    /** A dialog around caller built [content], with the standard button row. */
    fun custom(
        activity: Activity,
        title: String,
        content: View,
        positiveLabel: String = "OK",
        onPositive: (() -> Unit)? = null,
        negativeLabel: String? = null,
        onNegative: (() -> Unit)? = null
    ): Dialog {
        return show(
            activity, title, null, content,
            positiveLabel, onPositive,
            negativeLabel, onNegative,
            positiveDestructive = false,
            negativeDestructive = false,
            cancelable = true
        )
    }

    /** The non cancelable progress popup, as a [VaultProgress] handle. */
    fun progress(activity: Activity, message: String): VaultProgress {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val bar = ProgressBar(activity).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(dp(activity, 28), dp(activity, 28))
            try {
                indeterminateDrawable?.colorFilter = android.graphics.PorterDuffColorFilter(
                    color(activity, R.color.primary),
                    android.graphics.PorterDuff.Mode.SRC_IN
                )
            } catch (e: Exception) {
                // An untinted spinner still spins.
            }
        }
        row.addView(bar)
        row.addView(TextView(activity).apply {
            text = message
            textSize = 15f
            setTextColor(color(activity, R.color.text))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(activity, 14) }
        })
        val dialog = buildDialog(activity, null, null, row)
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
        fitWidth(activity, dialog)
        return VaultProgress(dialog)
    }

    /**
     * A single choice list in the custom dialog: one row per label,
     * the chosen row tinted with a filled radio at its left, every
     * other row quiet with an empty ring. [selectedIndex] may be
     * -1 for no current choice. Picking a row dismisses and calls
     * [onPick] with its index.
     */
    fun singleChoice(
        activity: Activity,
        title: String,
        labels: List<String>,
        selectedIndex: Int,
        onPick: (Int) -> Unit
    ): Dialog {
        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        for ((index, label) in labels.withIndex()) {
            list.addView(choiceRow(activity, label, index == selectedIndex) {
                onPick(index)
            })
        }
        return show(
            activity, title, null, list,
            positiveLabel = null, onPositive = null,
            negativeLabel = "Cancel", onNegative = null,
            positiveDestructive = false, negativeDestructive = false,
            cancelable = true
        )
    }

    /**
     * The delete confirmation (v1.2.0), with the two flavors the
     * owner asked for side by side: "Vault only" (the record
     * leaves the vault, the phone log keeps it) and "Vault and
     * phone log" in destructive red (the record leaves both). The
     * caller composes [title] and [message]; the honest permanence
     * line lives in the message, and the phone flavor's own
     * consequence line sits above its button.
     */
    fun deleteConfirm(
        activity: Activity,
        title: String,
        message: String,
        onVaultOnly: () -> Unit,
        onVaultAndPhone: () -> Unit
    ): Dialog {
        val extra = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        extra.addView(TextView(activity).apply {
            text = "The phone log option also removes the call from your dialer, " +
                "and that cannot be undone."
            textSize = 13f
            setTextColor(color(activity, R.color.muted))
            setPadding(0, dp(activity, 14), 0, 0)
        })
        val stack = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(activity, 16), 0, 0)
        }
        var dialog: Dialog? = null
        val vaultOnlyBtn = pillButton(activity, "Vault only", filled = true, destructive = false) {
            dialog?.dismiss()
            onVaultOnly()
        }
        stack.addView(vaultOnlyBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val bothBtn = pillButton(activity, "Vault and phone log", filled = true, destructive = true) {
            dialog?.dismiss()
            onVaultAndPhone()
        }
        stack.addView(bothBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(activity, 10) })
        val cancelBtn = quietButton(activity, "Cancel") { dialog?.dismiss() }
        stack.addView(cancelBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(activity, 6) })
        extra.addView(stack)
        val built = buildDialog(activity, title, message, extra)
        dialog = built
        built.setCancelable(true)
        built.show()
        fitWidth(activity, built)
        return built
    }

    // ------------------------------------------------------------- Pieces

    private fun choiceRow(
        activity: Activity,
        label: String,
        selected: Boolean,
        onClick: () -> Unit
    ): LinearLayout {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(activity, 52)
            setPadding(dp(activity, 12), dp(activity, 8), dp(activity, 12), dp(activity, 8))
            if (selected) {
                background = GradientDrawable().apply {
                    cornerRadius = dp(activity, 12).toFloat()
                    setColor(
                        UiKit.blendOver(
                            color(activity, R.color.card),
                            color(activity, R.color.primary), 0.13f
                        )
                    )
                }
            }
            isClickable = true
            isFocusable = true
            pressFeedback()
            setOnClickListener { onClick() }
        }
        // Radio: an outer ring (primary when chosen, border when
        // not) with a filled primary dot inside when chosen.
        val radio = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(activity, 22), dp(activity, 22))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke(
                    dp(activity, 2),
                    color(activity, if (selected) R.color.primary else R.color.border)
                )
            }
        }
        if (selected) {
            radio.addView(View(activity).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color(activity, R.color.primary))
                }
                layoutParams = FrameLayout.LayoutParams(
                    dp(activity, 12), dp(activity, 12), Gravity.CENTER
                )
            })
        }
        row.addView(radio)
        row.addView(TextView(activity).apply {
            text = label
            textSize = 15f
            setTextColor(color(activity, R.color.text))
            if (selected) typeface = UiKit.mediumTypeface
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginStart = dp(activity, 12) }
        })
        return row
    }

    /** A filled pill button: primary, or red when [destructive]. */
    private fun pillButton(
        activity: Activity,
        label: String,
        filled: Boolean,
        destructive: Boolean,
        onClick: () -> Unit
    ): TextView {
        return TextView(activity).apply {
            text = label
            textSize = 15f
            gravity = Gravity.CENTER
            typeface = UiKit.mediumTypeface
            setPadding(dp(activity, 20), dp(activity, 11), dp(activity, 20), dp(activity, 11))
            if (filled) {
                background = GradientDrawable().apply {
                    cornerRadius = dp(activity, 22).toFloat()
                    setColor(color(activity, if (destructive) R.color.red else R.color.primary))
                }
                setTextColor(
                    if (destructive) 0xFFFFFFFF.toInt() else color(activity, R.color.on_primary)
                )
            } else {
                background = GradientDrawable().apply {
                    cornerRadius = dp(activity, 22).toFloat()
                    setColor(color(activity, R.color.pill_unsel_bg))
                    setStroke(dp(activity, 1), color(activity, R.color.pill_unsel_border))
                }
                setTextColor(color(activity, R.color.text))
            }
            isClickable = true
            isFocusable = true
            pressFeedback()
            setOnClickListener { onClick() }
        }
    }

    /** A borderless quiet button, for cancels and secondary actions. */
    private fun quietButton(
        activity: Activity,
        label: String,
        destructive: Boolean = false,
        onClick: () -> Unit
    ): TextView {
        return TextView(activity).apply {
            text = label
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(activity, 16), dp(activity, 11), dp(activity, 16), dp(activity, 11))
            setTextColor(color(activity, if (destructive) R.color.red else R.color.muted))
            isClickable = true
            isFocusable = true
            pressFeedback()
            setOnClickListener { onClick() }
        }
    }

    /**
     * The shared dialog shell: title, optional message, optional
     * content, and an optional right aligned button row (quiet
     * negative, then filled positive). Button taps dismiss first,
     * then run their callback, mirroring how the framework dialogs
     * behaved. Returns the dialog unshown except where the caller
     * notes otherwise; [show] below is the common path.
     */
    private fun show(
        activity: Activity,
        title: String?,
        message: String?,
        content: View?,
        positiveLabel: String?,
        onPositive: (() -> Unit)?,
        negativeLabel: String?,
        onNegative: (() -> Unit)?,
        positiveDestructive: Boolean,
        negativeDestructive: Boolean,
        cancelable: Boolean
    ): Dialog {
        val dialog = buildDialog(activity, title, message, content)
        if (positiveLabel != null || negativeLabel != null) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                setPadding(0, dp(activity, 18), 0, 0)
            }
            if (negativeLabel != null) {
                row.addView(quietButton(activity, negativeLabel, negativeDestructive) {
                    dialog.dismiss()
                    onNegative?.invoke()
                })
            }
            if (positiveLabel != null) {
                val btn = pillButton(activity, positiveLabel, filled = true, positiveDestructive) {
                    dialog.dismiss()
                    onPositive?.invoke()
                }
                row.addView(btn, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { if (negativeLabel != null) marginStart = dp(activity, 8) })
            }
            (dialog.findViewById<LinearLayout>(ROOT_ID)).addView(row)
        }
        dialog.setCancelable(cancelable)
        dialog.show()
        fitWidth(activity, dialog)
        return dialog
    }

    private fun buildDialog(
        activity: Activity,
        title: String?,
        message: String?,
        content: View?
    ): Dialog {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(activity).apply {
            id = ROOT_ID
            orientation = LinearLayout.VERTICAL
            background = UiKit.cardBackground(activity, 24)
            setPadding(
                dp(activity, 24), dp(activity, 22),
                dp(activity, 24), dp(activity, 18)
            )
        }
        if (title != null) {
            root.addView(TextView(activity).apply {
                text = title
                textSize = 18f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(color(activity, R.color.text))
                includeFontPadding = false
            })
        }
        if (message != null) {
            root.addView(TextView(activity).apply {
                text = message
                textSize = 15f
                setTextColor(color(activity, R.color.text))
                setLineSpacing(dp(activity, 3).toFloat(), 1f)
                setPadding(0, if (title != null) dp(activity, 10) else 0, 0, 0)
            })
        }
        if (content != null) {
            // A caller that preset layout params (the Status cards
            // cap their scroll height) keeps them; only the top
            // margin is standardized.
            val lp = content.layoutParams as? LinearLayout.LayoutParams
                ?: LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            lp.topMargin = dp(activity, 6)
            root.addView(content, lp)
        }
        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        return dialog
    }

    private fun fitWidth(activity: Activity, dialog: Dialog) {
        val width = (activity.resources.displayMetrics.widthPixels * 0.92f).toInt()
        dialog.window?.setLayout(width, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private const val ROOT_ID = 0x6D1A09
}
