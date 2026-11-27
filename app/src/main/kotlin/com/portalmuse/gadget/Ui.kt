package com.portalmuse.gadget

import android.content.Context
import android.widget.ImageView
import android.widget.FrameLayout
import android.view.View
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView

/** Petits éléments communs aux panneaux posés sur le kiosque : noir, gris et trois couleurs. */
object Ui {
    /** Noir, comme le fond de l'avatar en version sombre. */
    const val PANEL = 0xFF000000.toInt()
    const val SURFACE = 0xFF1C1C1E.toInt()
    const val MUTED = 0xFF8E8E93.toInt()
    const val FAINT = 0xFF55555A.toInt()
    const val ACCENT = 0xFF3B82F6.toInt()
    const val CALL = 0xFF2F9E5B.toInt()
    const val DANGER = 0xFFD9453D.toInt()
    private const val BUTTON_DP = 80

    fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()

    fun label(context: Context, size: Float, color: Int = Color.WHITE) = TextView(context).apply {
        textSize = size
        setTextColor(color)
    }

    fun spaced(context: Context, top: Int, height: Int = WRAP_CONTENT) =
        LinearLayout.LayoutParams(MATCH_PARENT, height).apply { topMargin = dp(context, top) }

    /** Bouton arrondi à libellé, assez grand pour le doigt. */
    fun pill(context: Context, text: String, fill: Int) = TextView(context).apply {
        this.text = text
        textSize = 22f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        minWidth = dp(context, 160)
        setPadding(dp(context, 32), 0, dp(context, 32), 0)
        isClickable = true
        isFocusable = true
        background = pressable(GradientDrawable().apply { cornerRadius = dp(context, BUTTON_DP / 2).toFloat() }, fill)
    }

    /** Bouton rond à pictogramme ; [description] est lue par les aides d'accessibilité. */
    fun round(context: Context, icon: Int, fill: Int, description: String) = ImageView(context).apply {
        setImageResource(icon)
        scaleType = ImageView.ScaleType.CENTER
        contentDescription = description
        isClickable = true
        isFocusable = true
        background = pressable(GradientDrawable().apply { shape = GradientDrawable.OVAL }, fill)
        layoutParams = LinearLayout.LayoutParams(dp(context, BUTTON_DP), dp(context, BUTTON_DP))
    }

    private fun pressable(shape: GradientDrawable, fill: Int) = shape.let {
        it.setColor(fill)
        RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), it, null)
    }

    /** La colonne du panneau à gauche, l'avatar à droite quand il y en a un, la croix en haut à droite. */
    fun stage(context: Context, avatar: AvatarView, column: LinearLayout, close: View) = FrameLayout(context).apply {
        val row = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            column.setPadding(dp(context, 64), dp(context, 56), dp(context, 64), dp(context, 40))
            addView(column, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
            addView(
                avatar,
                LinearLayout.LayoutParams(dp(context, 400), dp(context, 400)).apply { marginEnd = dp(context, 48) },
            )
        }
        addView(row, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        // Sans avatar la colonne prend toute la largeur : la croix reste au-dessus de la marge.
        addView(
            close,
            FrameLayout.LayoutParams(dp(context, 64), dp(context, 64), Gravity.TOP or Gravity.END)
                .apply { setMargins(0, dp(context, 40), dp(context, 40), 0) },
        )
    }

    /** Les boutons d'un panneau, centrés sous le texte. */
    fun actions(context: Context, vararg buttons: View) = LinearLayout(context).apply {
        gravity = Gravity.CENTER
        for (button in buttons) {
            val width = if (button is TextView) WRAP_CONTENT else dp(context, BUTTON_DP)
            addView(
                button,
                LinearLayout.LayoutParams(width, dp(context, BUTTON_DP))
                    .apply { setMargins(dp(context, 12), 0, dp(context, 12), 0) },
            )
        }
    }

    /** Une phrase d'un échange ; [ignored] : restée sans réponse. */
    class Line(val fromUser: Boolean, val text: String, val ignored: Boolean = false)

    /** Comme des sous-titres : vos phrases en gris et plus petites, celles de Muse en blanc. */
    fun dialogue(lines: List<Line>): CharSequence {
        val out = SpannableStringBuilder()
        for (line in lines) {
            if (out.isNotEmpty()) out.append("\n\n")
            val start = out.length
            if (line.fromUser) {
                out.append(line.text.trim())
                val color = ForegroundColorSpan(if (line.ignored) FAINT else MUTED)
                out.setSpan(color, start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(RelativeSizeSpan(0.8f), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                out.append(formatted(line.text.trim().takeLast(4_000)))
            }
        }
        return out
    }

    /** Mise en forme minimale du Markdown : titres, puces et gras. */
    fun formatted(text: String): CharSequence {
        val plain = text.replace(Regex("(?m)^#{1,6}\\s+"), "").replace(Regex("(?m)^(\\s*)[-*]\\s+"), "$1•  ")
        val out = SpannableStringBuilder()
        plain.split("**").forEachIndexed { index, part ->
            val start = out.length
            out.append(part)
            if (index % 2 == 1) out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return out
    }
}
