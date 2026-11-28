package com.portalmuse.gadget

import com.portalmuse.core.tr
import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
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
import com.portalmuse.core.CallEnd
import com.portalmuse.core.CallEvent
import com.portalmuse.core.CallFailure
import com.portalmuse.core.CallResult

private const val MICROPHONE_REQUEST = 8
/** Time to read why the call ended, before going back to the home screen. */
private const val AFTER_END_MS = 3_000L
private const val AFTER_FAILURE_MS = 12_000L
private const val SHOWN_LINES = 12

/** The call screen: state, duration, what Muse says, "Interrupt" and "Hang up". */
class CallPanel(
    private val activity: Activity,
    root: FrameLayout,
    /** The user touched "Hang up". */
    private val onHangUp: () -> Unit,
    private val onVisibility: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val settings = Settings(activity)
    private var call: MuseCall? = null
    /** A hung-up call that has not released the microphone yet. */
    private var releasing: MuseCall? = null
    private var connectedAt = 0L
    private var speaking = false
    private var wakeCall = false
    private var videoCall = false
    /** The conversation on screen, one entry per turn: never saved or logged. */
    private class Line(val fromUser: Boolean, var text: String, var ignored: Boolean = false)
    private val lines = LinkedHashMap<String, Line>()
    private var museTurns = 0
    private var museOpen: String? = null
    private val autoClose = Runnable { close() }
    private val tick = object : Runnable {
        override fun run() {
            val seconds = (SystemClock.elapsedRealtime() - connectedAt) / 1000
            duration.text = "%d:%02d".format(seconds / 60, seconds % 60)
            main.postDelayed(this, 1_000)
        }
    }
    private val name get() = settings.museName ?: "Muse"

    private val avatar = AvatarView(activity)
    private val title = Ui.label(activity, 22f, Ui.MUTED)
    private val duration = Ui.label(activity, 22f, Ui.FAINT)
    private val status = Ui.label(activity, 20f, Ui.MUTED)
    private val words = Ui.label(activity, 28f).apply { setLineSpacing(0f, 1.2f) }
    private val scroll = ScrollView(activity).apply {
        isVerticalFadingEdgeEnabled = true
        setFadingEdgeLength(Ui.dp(activity, 48))
        addView(words)
    }
    private val interrupt = Ui.pill(activity, tr(
        "Interrupt",
        "Interrompre",
    ), Ui.SURFACE).apply { setOnClickListener { call?.interrupt() } }
    private val hangUp = Ui.round(activity, R.drawable.ic_hang_up, Ui.DANGER, tr("Hang up", "Raccrocher")).apply {
        setOnClickListener { endRequested() }
    }
    private val again = Ui.round(activity, R.drawable.ic_phone, Ui.CALL, tr(
        "Call again",
        "Rappeler",
    )).apply { setOnClickListener { open() } }
    private val dismiss = Ui.round(activity, R.drawable.ic_close, Ui.SURFACE, tr("Close", "Fermer")).apply {
        setOnClickListener { close() }
    }

    private val screen = VideoScreen(activity)
    private val videoLabel = Ui.label(activity, 22f, Ui.MUTED)
    /** The video call: Muse's picture, large, the state in a corner, one button to hang up. */
    private val videoLayer = FrameLayout(activity).apply {
        setBackgroundColor(Ui.PANEL)
        visibility = View.GONE
        addView(screen, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(
            videoLabel,
            FrameLayout.LayoutParams(Ui.dp(activity, 320), WRAP_CONTENT, Gravity.TOP or Gravity.START)
                .apply { setMargins(Ui.dp(activity, 64), Ui.dp(activity, 56), 0, 0) },
        )
        addView(
            Ui.round(activity, R.drawable.ic_hang_up, Ui.DANGER, tr("Hang up", "Raccrocher")).apply {
                setOnClickListener { endRequested() }
            },
            FrameLayout.LayoutParams(Ui.dp(activity, 88), Ui.dp(activity, 88), Gravity.BOTTOM or Gravity.START)
                .apply { setMargins(Ui.dp(activity, 64), 0, 0, Ui.dp(activity, 40)) },
        )
    }

    private val panel = FrameLayout(activity).apply {
        setBackgroundColor(Ui.PANEL)
        isClickable = true
        visibility = View.GONE
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(activity).apply {
                addView(title)
                addView(duration, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
                    .apply { marginStart = Ui.dp(activity, 16) })
            })
            addView(status, Ui.spaced(activity, top = 8))
            addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = Ui.dp(activity, 20) })
            addView(Ui.actions(activity, interrupt, again, hangUp), Ui.spaced(activity, top = 20))
        }
        addView(Ui.stage(activity, avatar, column, dismiss), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(videoLayer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    init {
        root.addView(panel, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    val isOpen get() = panel.visibility == View.VISIBLE

    val holdsAudio get() = releasing != null

    fun reloadAvatar() = avatar.reload()

    /** Opens the screen and calls. [wakeMic]: opened by voice, the microphone is already capturing. */
    fun open(wakeMic: WakeMic? = null, video: Boolean = settings.videoCalls) {
        wakeCall = wakeMic != null
        main.removeCallbacks(autoClose)
        panel.visibility = View.VISIBLE
        onVisibility()
        avatar.state = AvatarView.State.WORKING
        avatar.active = true
        title.text = name
        words.text = ""
        duration.text = ""
        lines.clear()
        museOpen = null
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            wakeMic?.close()
            stopped(tr(
                "Allow the microphone to make calls.",
                "Autorisation du micro nécessaire pour appeler.",
            ), AFTER_FAILURE_MS)
            activity.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MICROPHONE_REQUEST)
            return
        }
        if (call != null) {
            wakeMic?.close()
            return
        }
        videoCall = video
        status.text = if (wakeCall) tr("I'm listening, go ahead…", "Je vous écoute, parlez…") else "Connexion…"
        show(interrupt = false, inCall = true)
        showVideo(video)
        lateinit var started: MuseCall
        started = MuseCall(
            activity.applicationContext,
            wakeMic = wakeMic,
            video = screen.takeIf { video },
            onEvent = { event -> main.post { if (call === started) received(event) } },
            onEnd = { outcome ->
                main.post {
                    if (call === started) {
                        call = null
                        ended(outcome)
                    } else if (releasing === started) {
                        releasing = null
                        onVisibility()
                    }
                }
            },
        )
        call = started
        started.start()
    }

    fun onPermissionResult(requestCode: Int, granted: Boolean) {
        if (requestCode == MICROPHONE_REQUEST && granted && isOpen) open()
    }

    /** Hangs up if there is a call, then closes. */
    fun close() {
        call?.let {
            releasing = it
            call = null
            it.hangUp()
        }
        main.removeCallbacks(autoClose)
        main.removeCallbacks(tick)
        showVideo(false)
        panel.visibility = View.GONE
        avatar.active = false
        onVisibility()
    }

    /** Back to the home screen at once; the end of the call carries on in the background. */
    private fun endRequested() {
        close()
        onHangUp()
    }

    private fun showVideo(shown: Boolean) {
        videoLayer.visibility = if (shown) View.VISIBLE else View.GONE
        videoLabel.text = if (wakeCall) tr(
            "$name\nI'm listening, go ahead…",
            "$name\nJe vous écoute, parlez…",
        ) else "$name\nConnexion…"
        if (shown) screen.start() else screen.stop()
        // The avatar has no reason to play behind the video.
        if (shown) avatar.active = false
    }

    private fun show(interrupt: Boolean, inCall: Boolean) {
        this.interrupt.visibility = if (interrupt) View.VISIBLE else View.GONE
        hangUp.visibility = if (inCall) View.VISIBLE else View.GONE
        again.visibility = if (inCall) View.GONE else View.VISIBLE
        dismiss.visibility = if (inCall) View.GONE else View.VISIBLE
    }

    /** In video, there is no transcript and no button to interrupt. */
    private fun receivedVideo(event: CallEvent) {
        when (event) {
            CallEvent.Connected -> {
                connectedAt = SystemClock.elapsedRealtime()
                videoLabel.text = if (call?.silentSpeaker == true) tr(
                    "$name\nThe volume is at zero",
                    "$name\nLe volume est à zéro",
                ) else tr("$name\nYour turn", "$name\nÀ vous")
            }
            is CallEvent.Speaking -> videoLabel.text = if (event.speaking) tr(
                "$name\nyour microphone is muted",
                "$name\nvotre micro est coupé",
            ) else tr("$name\nYour turn", "$name\nÀ vous")
            else -> Unit
        }
    }

    private fun received(event: CallEvent) {
        if (videoCall) return receivedVideo(event)
        when (event) {
            CallEvent.Connected -> {
                connectedAt = SystemClock.elapsedRealtime()
                main.removeCallbacks(tick)
                tick.run()
                listening()
            }
            is CallEvent.Speaking -> {
                speaking = event.speaking
                avatar.state = if (speaking) AvatarView.State.SPEAKING else AvatarView.State.IDLE
                if (speaking) {
                    status.text = if (call?.silentSpeaker == true) {
                        tr("The Portal's volume is at zero", "Le volume du Portal est à zéro")
                    } else {
                        tr("$name is speaking · your microphone is muted", "$name parle · votre micro est coupé")
                    }
                    show(interrupt = true, inCall = true)
                } else {
                    listening()
                }
            }
            is CallEvent.Words -> {
                val key = museOpen ?: "muse-${++museTurns}".also { museOpen = it }
                val line = lines.getOrPut(key) { Line(false, "") }
                if (event.final) {
                    line.text = event.text.trim()
                    museOpen = null
                } else {
                    line.text += event.text
                }
                render()
            }
            is CallEvent.Heard -> {
                // The sentence is complete: Muse is preparing its answer.
                if (event.final) avatar.state = AvatarView.State.WORKING
                val line = lines.getOrPut("user-${event.item}") { Line(true, "") }
                line.text = if (event.final) event.text.trim() else line.text + event.text
                render()
            }
            // Already applied by the call; Android's slider shows the level.
            is CallEvent.Volume -> Unit
            is CallEvent.Ignored -> {
                avatar.state = AvatarView.State.IDLE
                lines["user-${event.item}"]?.ignored = true
                status.text = tr("Not taken as addressed to $name", "Phrase non prise comme adressée à $name")
                render()
            }
        }
    }

    private fun render() {
        words.text = Ui.dialogue(lines.values.toList().takeLast(SHOWN_LINES).map { Ui.Line(it.fromUser, it.text, it.ignored) })
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun listening() {
        avatar.state = AvatarView.State.IDLE
        status.text = tr("Your turn", "À vous")
        show(interrupt = false, inCall = true)
    }

    private fun ended(outcome: MuseCall.Outcome) {
        val result = (outcome as? MuseCall.Outcome.Finished)?.result
        val end = (result as? CallResult.Ended)?.end
        // Hung up, woken without a request, or only the volume to set: nothing to read.
        if (end == CallEnd.HUNG_UP || end == CallEnd.NOTHING_ASKED || end == CallEnd.VOLUME) return close()
        if (videoCall && end == null) {
            // Video could not be set up: the same call carries on by voice. What was said after "Hey Muse" is
            // then lost.
            val repeat = wakeCall
            showVideo(false)
            open(video = false)
            status.text = tr(
                "Video unavailable, voice call…",
                "Vidéo indisponible, appel vocal…",
            ) + if (repeat) tr(" Say your question again.", " Redites votre question.") else ""
            return
        }
        stopped(describe(outcome), if (end != null) AFTER_END_MS else AFTER_FAILURE_MS)
    }

    private fun stopped(message: String, closeAfterMillis: Long) {
        if (videoLayer.visibility == View.VISIBLE) {
            showVideo(false)
            avatar.active = true
        }
        avatar.state = AvatarView.State.IDLE
        main.removeCallbacks(tick)
        status.text = message
        show(interrupt = false, inCall = false)
        main.removeCallbacks(autoClose)
        main.postDelayed(autoClose, closeAfterMillis)
    }

    private fun describe(outcome: MuseCall.Outcome): String = when (outcome) {
        MuseCall.Outcome.NoAudio -> tr(
            "The microphone or speaker is unavailable.",
            "Le micro ou le haut-parleur est indisponible.",
        )
        is MuseCall.Outcome.Unreachable ->
            if (outcome.status == null) tr("Voice connection failed.", "Connexion vocale impossible.") else tr(
                "Voice connection refused (HTTP ${outcome.status}).",
                "Connexion vocale refusée (HTTP ${outcome.status}).",
            )
        is MuseCall.Outcome.Finished -> when (val result = outcome.result) {
            is CallResult.Ended -> when (result.end) {
                CallEnd.HUNG_UP -> tr("Call ended.", "Appel terminé.")
                CallEnd.REMOTE_END, CallEnd.REMOTE_STOP -> tr("$name hung up.", "$name a raccroché.")
                CallEnd.IDLE -> tr(
                    "Call ended after 30 seconds of silence.",
                    "Appel terminé après 30 secondes sans échange.",
                )
                CallEnd.TOO_LONG -> tr("Call ended: 5 minutes at most.", "Appel terminé : 5 minutes au maximum.")
                CallEnd.GOODBYE -> tr("Goodbye.", "Au revoir.")
                CallEnd.VOLUME -> tr("Volume set.", "Volume réglé.")
                CallEnd.NOTHING_ASKED -> tr(
                    "No request heard: call ended.",
                    "Aucune demande entendue : appel terminé.",
                )
            }
            is CallResult.Failed -> when (result.failure) {
                CallFailure.REFUSED -> tr(
                    "The server refuses calls from this gadget (HTTP ${result.status}).",
                    "Le serveur refuse l'appel depuis ce gadget (HTTP ${result.status}).",
                )
                CallFailure.NOT_READY -> tr("The call did not start in time.", "L'appel n'a pas démarré à temps.")
                CallFailure.PROTOCOL -> tr("Unexpected answer from the server.", "Réponse inattendue du serveur.")
                CallFailure.CONNECTION -> tr("Connection lost.", "Connexion perdue.")
            }
        }
    }
}
