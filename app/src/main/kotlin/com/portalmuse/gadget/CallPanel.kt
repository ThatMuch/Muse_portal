package com.portalmuse.gadget

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
/** Le temps de lire pourquoi l'appel s'est terminé, avant de revenir à l'accueil. */
private const val AFTER_END_MS = 3_000L
private const val AFTER_FAILURE_MS = 12_000L
private const val SHOWN_LINES = 12

/** L'écran d'appel : état, durée, ce que dit Muse, « Interrompre » et « Raccrocher ». */
class CallPanel(
    private val activity: Activity,
    root: FrameLayout,
    /** L'utilisateur a touché « Raccrocher ». */
    private val onHangUp: () -> Unit,
    private val onVisibility: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val settings = Settings(activity)
    private var call: MuseCall? = null
    /** Un appel raccroché qui n'a pas encore rendu le micro. */
    private var releasing: MuseCall? = null
    private var connectedAt = 0L
    private var speaking = false
    private var wakeCall = false
    private var videoCall = false
    /** La conversation à l'écran, une entrée par tour : jamais enregistrée ni journalisée. */
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
    private val interrupt = Ui.pill(activity, "Interrompre", Ui.SURFACE).apply { setOnClickListener { call?.interrupt() } }
    private val hangUp = Ui.round(activity, R.drawable.ic_hang_up, Ui.DANGER, "Raccrocher").apply {
        setOnClickListener { endRequested() }
    }
    private val again = Ui.round(activity, R.drawable.ic_phone, Ui.CALL, "Rappeler").apply { setOnClickListener { open() } }
    private val dismiss = Ui.round(activity, R.drawable.ic_close, Ui.SURFACE, "Fermer").apply {
        setOnClickListener { close() }
    }

    private val screen = VideoScreen(activity)
    private val videoLabel = Ui.label(activity, 22f, Ui.MUTED)
    /** L'appel vidéo : l'image de Muse en grand, l'état dans un coin, un bouton pour raccrocher. */
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
            Ui.round(activity, R.drawable.ic_hang_up, Ui.DANGER, "Raccrocher").apply {
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

    /** Ouvre l'écran et appelle. [wakeMic] : ouvert à la voix, le micro capte déjà. */
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
            stopped("Autorisation du micro nécessaire pour appeler.", AFTER_FAILURE_MS)
            activity.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MICROPHONE_REQUEST)
            return
        }
        if (call != null) {
            wakeMic?.close()
            return
        }
        videoCall = video
        status.text = if (wakeCall) "Je vous écoute, parlez…" else "Connexion…"
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

    /** Raccroche s'il y a un appel, puis ferme. */
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

    /** Retour immédiat à l'accueil ; la fin de l'appel continue en arrière-plan. */
    private fun endRequested() {
        close()
        onHangUp()
    }

    private fun showVideo(shown: Boolean) {
        videoLayer.visibility = if (shown) View.VISIBLE else View.GONE
        videoLabel.text = if (wakeCall) "$name\nJe vous écoute, parlez…" else "$name\nConnexion…"
        if (shown) screen.start() else screen.stop()
        // L'avatar n'a pas à jouer derrière la vidéo.
        if (shown) avatar.active = false
    }

    private fun show(interrupt: Boolean, inCall: Boolean) {
        this.interrupt.visibility = if (interrupt) View.VISIBLE else View.GONE
        hangUp.visibility = if (inCall) View.VISIBLE else View.GONE
        again.visibility = if (inCall) View.GONE else View.VISIBLE
        dismiss.visibility = if (inCall) View.GONE else View.VISIBLE
    }

    /** En vidéo, il n'y a ni transcription ni bouton pour interrompre. */
    private fun receivedVideo(event: CallEvent) {
        when (event) {
            CallEvent.Connected -> {
                connectedAt = SystemClock.elapsedRealtime()
                videoLabel.text = if (call?.silentSpeaker == true) "$name\nLe volume est à zéro" else "$name\nÀ vous"
            }
            is CallEvent.Speaking -> videoLabel.text = if (event.speaking) "$name\nvotre micro est coupé" else "$name\nÀ vous"
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
                        "Le volume du Portal est à zéro"
                    } else {
                        "$name parle · votre micro est coupé"
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
                // La phrase est complète : Muse prépare sa réponse.
                if (event.final) avatar.state = AvatarView.State.WORKING
                val line = lines.getOrPut("user-${event.item}") { Line(true, "") }
                line.text = if (event.final) event.text.trim() else line.text + event.text
                render()
            }
            is CallEvent.Ignored -> {
                avatar.state = AvatarView.State.IDLE
                lines["user-${event.item}"]?.ignored = true
                status.text = "Phrase non prise comme adressée à $name"
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
        status.text = "À vous"
        show(interrupt = false, inCall = true)
    }

    private fun ended(outcome: MuseCall.Outcome) {
        val result = (outcome as? MuseCall.Outcome.Finished)?.result
        val end = (result as? CallResult.Ended)?.end
        // Raccroché ou réveil sans demande : rien à lire.
        if (end == CallEnd.HUNG_UP || end == CallEnd.NOTHING_ASKED) return close()
        if (videoCall && end == null) {
            // La vidéo n'a pas pu s'établir : le même appel continue à la voix. Ce qui a été dit
            // après « Hey Muse » est alors perdu.
            val repeat = wakeCall
            showVideo(false)
            open(video = false)
            status.text = "Vidéo indisponible, appel vocal…" + if (repeat) " Redites votre question." else ""
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
        MuseCall.Outcome.NoAudio -> "Le micro ou le haut-parleur est indisponible."
        is MuseCall.Outcome.Unreachable ->
            if (outcome.status == null) "Connexion vocale impossible." else "Connexion vocale refusée (HTTP ${outcome.status})."
        is MuseCall.Outcome.Finished -> when (val result = outcome.result) {
            is CallResult.Ended -> when (result.end) {
                CallEnd.HUNG_UP -> "Appel terminé."
                CallEnd.REMOTE_END, CallEnd.REMOTE_STOP -> "$name a raccroché."
                CallEnd.IDLE -> "Appel terminé après 30 secondes sans échange."
                CallEnd.TOO_LONG -> "Appel terminé : 5 minutes au maximum."
                CallEnd.GOODBYE -> "Au revoir."
                CallEnd.NOTHING_ASKED -> "Aucune demande entendue : appel terminé."
            }
            is CallResult.Failed -> when (result.failure) {
                CallFailure.REFUSED -> "Le serveur refuse l'appel depuis ce gadget (HTTP ${result.status})."
                CallFailure.NOT_READY -> "L'appel n'a pas démarré à temps."
                CallFailure.PROTOCOL -> "Réponse inattendue du serveur."
                CallFailure.CONNECTION -> "Connexion perdue."
            }
        }
    }
}
