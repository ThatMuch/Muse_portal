package com.portalmuse.gadget

import com.portalmuse.core.tr
import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.Outline
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.portalmuse.core.FeedRefresh
import java.io.File
import java.text.DateFormat
import java.util.Date

private const val IDLE_CLOSE_MS = 5 * 60_000L

/** The "Feed": Muse's suggestions, one under the other, each with its button. */
class FeedPanel(
    private val activity: Activity,
    root: FrameLayout,
    /** A feed button: sends this message to Muse. */
    private val onAsk: (String) -> Unit,
    /** Asks for the feed again without leaving the page; `false` if Muse is unreachable. */
    private val onStale: (done: () -> Unit) -> Boolean,
    private val onVisibility: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val idle = Runnable { close() }
    private var askedAt: Long? = null
    private var refreshing = false
    private val updated = Ui.label(activity, 18f, Ui.FAINT)
    private val list = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(180), dp(8), dp(180), dp(48))
    }
    private val scroll = ScrollView(activity).apply { addView(list) }

    private val panel = FrameLayout(activity).apply {
        setBackgroundColor(Ui.PANEL)
        isClickable = true
        visibility = View.GONE
        addView(scroll, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply { topMargin = dp(128) })
        val heading = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(Ui.label(activity, 36f).apply {
                text = tr("Feed", "Fil")
                typeface = Typeface.DEFAULT_BOLD
            })
            addView(updated, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(20) })
        }
        addView(
            heading,
            FrameLayout.LayoutParams(WRAP_CONTENT, dp(64), Gravity.TOP or Gravity.START)
                .apply { setMargins(dp(180), dp(48), 0, 0) },
        )
        val buttons = Ui.actions(
            activity,
            Ui.pill(activity, tr(
                "Refresh",
                "Actualiser",
            ), Ui.SURFACE).apply { setOnClickListener { ask(REFRESH) } },
        )
        addView(
            buttons,
            FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.END)
                .apply { setMargins(0, dp(40), dp(124), 0) },
        )
        addView(
            Ui.round(activity, R.drawable.ic_close, Ui.SURFACE, tr(
                "Close",
                "Fermer",
            )).apply { setOnClickListener { close() } },
            FrameLayout.LayoutParams(dp(64), dp(64), Gravity.TOP or Gravity.END)
                .apply { setMargins(0, dp(48), dp(40), 0) },
        )
    }

    init {
        root.addView(panel, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        scroll.setOnScrollChangeListener { _, _, _, _, _ -> touched() }
    }

    val isOpen get() = panel.visibility == View.VISIBLE

    fun touched() {
        if (!isOpen) return
        main.removeCallbacks(idle)
        main.postDelayed(idle, IDLE_CLOSE_MS)
    }

    fun open() {
        render()
        panel.visibility = View.VISIBLE
        scroll.scrollTo(0, 0)
        touched()
        onVisibility()
        refreshIfStale()
    }

    /** A new feed arrived while it was being looked at. */
    fun refresh() {
        if (isOpen) render()
    }

    private fun refreshIfStale() {
        val now = System.currentTimeMillis()
        if (refreshing || !FeedRefresh.isDue(FeedStore.load(activity)?.savedAt, askedAt, now)) return
        refreshing = onStale {
            refreshing = false
            refresh()
        }
        if (refreshing) {
            askedAt = now
            render()
        }
    }

    fun close() {
        if (!isOpen) return
        main.removeCallbacks(idle)
        panel.visibility = View.GONE
        list.removeAllViews()
        onVisibility()
    }

    private fun ask(message: String) {
        close()
        onAsk(message)
    }

    private fun render() {
        list.removeAllViews()
        val feed = FeedStore.load(activity)
        val date = feed?.let {
            tr(
                "updated ",
                "mis à jour ",
            ) + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it.savedAt))
        }
        updated.text = if (refreshing) tr("updating…", "mise à jour en cours…") else date.orEmpty()
        if (feed == null) {
            val name = Settings(activity).museName ?: "Muse"
            list.addView(
                Ui.label(activity, 24f, Ui.MUTED).apply {
                    text = if (refreshing) tr("$name is preparing your feed…", "$name prépare votre fil…")
                    else tr(
                        "Nothing yet. Touch “Refresh” to ask $name for your feed.",
                        "Rien pour l'instant. Touchez « Actualiser » pour demander votre fil à $name.",
                    )
                },
                Ui.spaced(activity, top = 48),
            )
            return
        }
        for (item in feed.items) {
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(Ui.label(activity, 26f).apply {
                    text = listOfNotNull(item.icon, item.title).joinToString("  ")
                    typeface = Typeface.DEFAULT_BOLD
                })
                item.text?.let {
                    addView(
                        Ui.label(activity, 20f, Ui.MUTED).apply {
                            text = Ui.formatted(it)
                            setLineSpacing(0f, 1.2f)
                        },
                        Ui.spaced(activity, top = 8),
                    )
                }
                (item.image as? File)?.let {
                    addView(picture(it), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(14) })
                }
                addView(
                    Ui.pill(activity, item.actionLabel, Ui.SURFACE).apply {
                        textSize = 19f
                        setOnClickListener { ask(item.actionMessage) }
                    },
                    LinearLayout.LayoutParams(WRAP_CONTENT, dp(64)).apply { topMargin = dp(14) },
                )
            }
            list.addView(card, Ui.spaced(activity, top = 36))
        }
    }

    private fun picture(file: File) = ImageView(activity).apply {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        options.inJustDecodeBounds = false
        options.inSampleSize = maxOf(1, options.outWidth / dp(480))
        setImageBitmap(BitmapFactory.decodeFile(file.path, options))
        adjustViewBounds = true
        maxHeight = dp(240)
        maxWidth = dp(480)
        scaleType = ImageView.ScaleType.FIT_START
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(16).toFloat())
            }
        }
    }

    private fun dp(value: Int) = Ui.dp(activity, value)

    companion object {
        private val REFRESH = tr(
            "Send my feed to the Portal with the feed.set command, with pictures when you have them, and show it.",
            "Envoie mon fil sur le Portal avec la commande feed.set, avec les images " +
                "quand tu en as, et affiche-le.",
        )

        /** The request made on its own: it does not reopen a page that was just closed. */
        val QUIET_REFRESH = tr(
            "Update my feed on the Portal with the feed.set command, with pictures when you have them, " +
                "without opening it (open set to false).",
            "Mets à jour mon fil sur le Portal avec la commande feed.set, avec les images " +
                "quand tu en as, sans l'ouvrir (open à false).",
        )
    }
}
