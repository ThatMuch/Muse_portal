package com.portalmuse.gadget

import android.app.Activity
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.portalmuse.core.AskResult
import com.portalmuse.core.Question

/** Le temps de lire avant que les boutons réagissent : un doigt déjà en route ne répond pas. */
private const val ARMING_MS = 1_200L

/** Une demande de validation : la question, et deux grands boutons. */
class QuestionPanel(private val activity: Activity, root: FrameLayout, private val onVisibility: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var reply: ((AskResult) -> Unit)? = null
    private var expiresAt = 0L

    private val from = Ui.label(activity, 22f, Ui.MUTED)
    private val question = Ui.label(activity, 40f).apply { typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) }
    private val details = Ui.label(activity, 24f, Ui.MUTED).apply { setLineSpacing(0f, 1.2f) }
    private val remaining = Ui.label(activity, 18f, Ui.FAINT).apply { gravity = Gravity.CENTER }
    private val reject: TextView = Ui.pill(activity, "", Ui.SURFACE).apply {
        setOnClickListener { answer(AskResult.REJECTED) }
    }
    private val approve: TextView = Ui.pill(activity, "", Ui.CALL).apply {
        setOnClickListener { answer(AskResult.APPROVED) }
    }
    private val arm = Runnable { buttons(true) }
    private val countdown = object : Runnable {
        override fun run() {
            val left = (expiresAt - SystemClock.elapsedRealtime() + 999) / 1000
            if (left <= 0) return answer(AskResult.NO_ANSWER)
            remaining.text = "Expire dans %d:%02d".format(left / 60, left % 60)
            main.postDelayed(this, 1_000)
        }
    }

    private val panel = FrameLayout(activity).apply {
        setBackgroundColor(Ui.PANEL)
        isClickable = true
        visibility = View.GONE
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(96), dp(64), dp(96), dp(40))
            addView(from)
            addView(question, Ui.spaced(activity, top = 12))
            addView(
                ScrollView(activity).apply { addView(details) },
                LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = dp(20) },
            )
            addView(remaining, Ui.spaced(activity, top = 12))
            val row = LinearLayout(activity).apply {
                gravity = Gravity.CENTER
                addView(reject, LinearLayout.LayoutParams(dp(400), dp(96)))
                addView(approve, LinearLayout.LayoutParams(dp(400), dp(96)).apply { marginStart = dp(24) })
            }
            addView(row, Ui.spaced(activity, top = 20, height = WRAP_CONTENT))
        }
        addView(column, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    init {
        root.addView(panel, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    val isOpen get() = panel.visibility == View.VISIBLE

    fun ask(asked: Question, onAnswer: (AskResult) -> Unit): Boolean {
        if (reply != null) return false
        reply = onAnswer
        from.text = "${Settings(activity).museName ?: "Muse"} demande votre accord"
        question.text = asked.question
        details.text = Ui.formatted(asked.details.orEmpty())
        reject.text = asked.rejectLabel
        approve.text = asked.approveLabel
        buttons(false)
        main.postDelayed(arm, ARMING_MS)
        expiresAt = SystemClock.elapsedRealtime() + asked.timeoutMillis
        panel.visibility = View.VISIBLE
        countdown.run()
        Chime.alert()
        onVisibility()
        return true
    }

    /** La question disparaît sans réponse. */
    fun cancel() {
        if (reply != null) answer(AskResult.NO_ANSWER)
    }

    private fun answer(result: AskResult) {
        val waiting = reply ?: return
        reply = null
        main.removeCallbacks(arm)
        main.removeCallbacks(countdown)
        panel.visibility = View.GONE
        waiting(result)
        val said = when (result) {
            AskResult.APPROVED -> "Réponse envoyée : ${approve.text}"
            AskResult.REJECTED -> "Réponse envoyée : ${reject.text}"
            else -> "Demande restée sans réponse"
        }
        Toast.makeText(activity, said, Toast.LENGTH_SHORT).show()
        onVisibility()
    }

    private fun buttons(enabled: Boolean) {
        for (button in listOf(reject, approve)) {
            button.isEnabled = enabled
            button.alpha = if (enabled) 1f else 0.35f
        }
    }

    private fun dp(value: Int) = Ui.dp(activity, value)
}
