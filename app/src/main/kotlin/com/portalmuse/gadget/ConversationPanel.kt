package com.portalmuse.gadget

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import com.portalmuse.core.ChatFailure
import com.portalmuse.core.ChatProgress
import com.portalmuse.core.ChatResult
import com.portalmuse.core.History
import com.portalmuse.core.MuseChat
import com.portalmuse.core.Wav
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

private const val TAG = "PortalMuse"
private const val MICROPHONE_REQUEST = 7
private const val AUTO_CLOSE_MS = 120_000L

/**
 * La conversation écrite avec son Muse : elle s'ouvre depuis un bouton d'accueil, affiche la
 * réponse, et permet de répondre à la voix ou au clavier. Le micro ne sert qu'entre le toucher et
 * l'envoi. Porte aussi le bouton d'appel de l'accueil.
 */
class ConversationPanel(
    private val activity: Activity,
    private val root: FrameLayout,
    private val onCall: () -> Unit,
    /** « Plus » : le reste de l'accueil. */
    private val onMore: () -> Unit,
    private val onVisibility: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val settings = Settings(activity)
    private val autoClose = Runnable { close() }
    private var recorder: VoiceRecorder? = null
    private val voice = SpeechPlayer()
    /** L'utilisateur a coupé la lecture de la demande en cours. */
    private var hushed = false
    private var waiting = false

    /** Le bouton d'appel s'efface sous le clavier, qui le laisserait transparaître. */
    var keyboardShown = false
        set(value) {
            if (field != value) {
                field = value
                refresh()
            }
        }

    /** Change à chaque demande : les réponses tardives d'une demande abandonnée sont ignorées. */
    @Volatile private var turn = 0

    private val name get() = settings.museName ?: "Muse"

    private val more = Ui.round(activity, R.drawable.ic_more, Ui.SURFACE, "Plus").apply { setOnClickListener { onMore() } }

    /** Les deux boutons de l'accueil : « Plus », discret, puis l'appel. */
    private val floating = LinearLayout(activity).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(more, LinearLayout.LayoutParams(dp(72), dp(72)).apply { marginEnd = dp(16) })
        addView(
            Ui.round(activity, R.drawable.ic_phone, Ui.CALL, "Appeler").apply { setOnClickListener { onCall() } },
            LinearLayout.LayoutParams(dp(88), dp(88)),
        )
        layoutParams = FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
            marginEnd = dp(48)
            bottomMargin = dp(40)
        }
    }

    /** Un autre panneau (l'appel) couvre l'écran. */
    var covered = false
        set(value) {
            field = value
            refresh()
        }

    private val avatar = AvatarView(activity)
    private val title = Ui.label(activity, 22f, Ui.MUTED)
    /** Ce qu'un bouton d'accueil a envoyé : on voit ce qui part en son nom. */
    private val asked = Ui.label(activity, 22f, Ui.MUTED).apply {
        maxLines = 5
        ellipsize = TextUtils.TruncateAt.END
        visibility = View.GONE
    }
    private val status = Ui.label(activity, 20f, Ui.MUTED)
    private val level = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
        progressTintList = ColorStateList.valueOf(Color.WHITE)
        progressBackgroundTintList = ColorStateList.valueOf(Ui.SURFACE)
    }
    private val reply = Ui.label(activity, 28f).apply { setLineSpacing(0f, 1.2f) }
    private val scroll = ScrollView(activity).apply {
        isVerticalFadingEdgeEnabled = true
        setFadingEdgeLength(Ui.dp(activity, 32))
        addView(reply)
    }
    private val talk = Ui.round(activity, R.drawable.ic_mic, Ui.SURFACE, "Parler").apply {
        setOnClickListener { talkOrSend() }
    }
    private val write = Ui.round(activity, R.drawable.ic_keyboard, Ui.SURFACE, "Écrire").apply {
        setOnClickListener { writeMessage() }
    }
    /** En toutes lettres : un pictogramme barré laissait croire que le son était coupé. */
    private val hush = Ui.pill(activity, "Arrêter la lecture", Ui.SURFACE).apply {
        visibility = View.GONE
        setOnClickListener {
            hushed = true
            Log.i(TAG, "voix : arrêt demandé à l'écran")
            stopVoice()
            // La réponse peut encore s'écrire : on ne rend les boutons qu'ensuite.
            if (!waiting) idle("")
        }
    }
    private val dismiss = Ui.round(activity, R.drawable.ic_close, Ui.SURFACE, "Fermer").apply {
        setOnClickListener { close() }
    }

    private val panel: FrameLayout = FrameLayout(activity).apply {
        setBackgroundColor(Ui.PANEL)
        // Le panneau capte les touchers : la page derrière ne reçoit rien.
        isClickable = true
        visibility = View.GONE
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(asked, Ui.spaced(activity, top = 20))
            addView(status, Ui.spaced(activity, top = 12))
            addView(level, Ui.spaced(activity, top = 16, height = dp(4)))
            addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = dp(16) })
            addView(Ui.actions(activity, hush, write, talk), Ui.spaced(activity, top = 20))
        }
        addView(Ui.stage(activity, avatar, column, dismiss), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    private fun dp(value: Int) = Ui.dp(activity, value)

    init {
        root.addView(floating)
        root.addView(panel, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        refresh()
    }

    val isOpen get() = panel.visibility == View.VISIBLE

    fun reloadAvatar() = avatar.reload()

    /** Le bouton d'appel n'apparaît qu'une fois le Portal appairé, panneaux fermés. */
    fun refresh() {
        // Rien à proposer : pas de bouton.
        val anything = settings.screenAllowed || settings.historyShown || settings.cameraAllowed
        more.visibility = if (anything) View.VISIBLE else View.GONE
        val shown = !isOpen && !covered && !keyboardShown && SecretStore(activity).pairing != null
        floating.visibility = if (shown) View.VISIBLE else View.GONE
    }

    /** Un bouton d'accueil : envoie son message et affiche la réponse, sans ouvrir le micro. */
    fun sendShortcut(message: String) {
        if (isOpen || covered) return
        show()
        asked.text = "« $message »"
        asked.visibility = View.VISIBLE
        send { chat, progress, cancelled -> chat.sendText(message, progress, cancelled, settings.spokenReplies) }
    }

    /** Les derniers messages, lus à la demande et jamais gardés sur le Portal. */
    fun showHistory() {
        if (isOpen || covered) return
        show()
        val chat = chat() ?: return unreachable()
        main.removeCallbacks(autoClose)
        val mine = ++turn
        say("Lecture des derniers messages…")
        level.visibility = View.INVISIBLE
        talk.visibility = View.GONE
        write.visibility = View.GONE
        thread(name = "muse-history") {
            val result = try {
                chat.history()
            } catch (e: RuntimeException) {
                Log.e(TAG, "historique en échec : ${e.javaClass.simpleName}")
                History.Unavailable(null)
            }
            main.post { if (mine == turn) showPast(result) }
        }
    }

    private fun showPast(result: History) {
        when (result) {
            is History.Messages -> {
                reply.text = Ui.dialogue(result.messages.map { Ui.Line(it.fromUser, it.text) })
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                idle(if (result.messages.isEmpty()) "Aucun message à afficher." else "")
            }
            is History.Unavailable -> idle(
                "Historique indisponible" + (result.status?.let { " (HTTP $it)." } ?: "."),
            )
        }
    }

    /** Envoie la photo approuvée et affiche la réponse. */
    fun sendPhoto(jpeg: ByteArray) {
        if (covered) return
        if (!isOpen) show()
        asked.text = "Photo envoyée : « Regarde ça. »"
        asked.visibility = View.VISIBLE
        val keep = settings.keepPhotos
        send { chat, progress, cancelled ->
            var message = "Regarde ça."
            if (keep) {
                val path = "portal-photos/${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.jpg"
                val status = chat.upload(path, jpeg, "image/jpeg")
                Log.i(TAG, "photo : rangement chez Muse, HTTP $status")
                // Le chemin n'est donné à Muse que si le fichier y est vraiment.
                if (status != null && status in 200..299) message += " (Photo rangée dans tes fichiers : $path)"
            }
            chat.sendImage(jpeg, message, progress, cancelled, settings.spokenReplies)
        }
    }

    private fun show() {
        panel.visibility = View.VISIBLE
        floating.visibility = View.GONE
        // Le micro doit être libéré avant que la note vocale le prenne.
        onVisibility()
        title.text = name
        avatar.active = true
        reply.text = ""
        asked.visibility = View.GONE
    }

    private fun stopVoice() {
        voice.stop()
        hush.visibility = View.GONE
    }

    /** Chaque message est lu dès qu'il est complet, sans attendre la fin de la demande. */
    private fun readAloud(messageIds: List<String>) {
        if (hushed || !settings.spokenReplies || messageIds.isEmpty()) return
        val chat = chat() ?: return
        for (id in messageIds) voice.add(chat, id)
        avatar.state = AvatarView.State.SPEAKING
        hush.visibility = View.VISIBLE
    }

    /** La réponse écrite reste à l'écran pendant que la lecture se termine ; le panneau attend. */
    private fun finishReading(written: String) {
        main.removeCallbacks(autoClose)
        avatar.state = AvatarView.State.SPEAKING
        talk.visibility = View.VISIBLE
        write.visibility = View.VISIBLE
        talkButton(sending = false)
        val silent = activity.getSystemService(AudioManager::class.java)
            .getStreamVolume(AudioManager.STREAM_MUSIC) == 0
        say(if (silent) "Le volume du Portal est à zéro." else "$name lit la réponse…")
        voice.finish { outcome ->
            hush.visibility = View.GONE
            idle(
                when {
                    outcome.played -> written
                    outcome.status != null -> "Lecture à voix haute refusée (HTTP ${outcome.status})."
                    else -> "Lecture à voix haute impossible."
                },
            )
        }
    }

    fun close() {
        stopVoice()
        waiting = false
        turn++
        main.removeCallbacks(autoClose)
        recorder?.cancel()
        recorder = null
        val wasOpen = isOpen
        panel.visibility = View.GONE
        avatar.active = false
        refresh()
        if (wasOpen) onVisibility()
    }

    private fun idle(message: String) {
        recorder = null
        avatar.state = AvatarView.State.IDLE
        status.text = message
        level.visibility = View.INVISIBLE
        talkButton(sending = false)
        status.visibility = if (message.isEmpty()) View.GONE else View.VISIBLE
        talk.visibility = View.VISIBLE
        write.visibility = View.VISIBLE
        main.removeCallbacks(autoClose)
        main.postDelayed(autoClose, AUTO_CLOSE_MS)
    }

    /** Le même bouton ouvre le micro, puis envoie. */
    private fun talkButton(sending: Boolean) {
        talk.setImageResource(if (sending) R.drawable.ic_send else R.drawable.ic_mic)
        talk.contentDescription = if (sending) "Envoyer" else "Parler"
        (talk.background as RippleDrawable).let {
            (it.getDrawable(0) as GradientDrawable).setColor(if (sending) Ui.ACCENT else Ui.SURFACE)
        }
    }

    private fun say(message: String) {
        status.text = message
        status.visibility = View.VISIBLE
    }

    private fun talkOrSend() {
        val listening = recorder
        if (listening != null) listening.finish() else startListening()
    }

    private fun startListening() {
        stopVoice()
        main.removeCallbacks(autoClose)
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            idle("Autorisation du micro nécessaire pour parler. Le clavier reste possible.")
            activity.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MICROPHONE_REQUEST)
            return
        }
        if (chat() == null) return unreachable()
        turn++
        avatar.state = AvatarView.State.IDLE
        reply.text = ""
        asked.visibility = View.GONE
        say("Je vous écoute. Faites une pause ou touchez la flèche.")
        level.progress = 0
        level.visibility = View.VISIBLE
        talkButton(sending = true)
        talk.visibility = View.VISIBLE
        write.visibility = View.GONE
        recorder = VoiceRecorder(
            onLevel = { level.progress = (it * 100).toInt() },
            onDone = ::heard,
        ).also { it.start() }
    }

    fun onPermissionResult(requestCode: Int, granted: Boolean) {
        if (requestCode == MICROPHONE_REQUEST && granted && isOpen) startListening()
    }

    private fun heard(result: VoiceRecorder.Result) {
        recorder = null
        when (result) {
            is VoiceRecorder.Result.Audio -> send { chat, progress, cancelled ->
                chat.sendVoice(Wav.encode(result.pcm, result.sampleRate), progress, cancelled, settings.spokenReplies)
            }
            VoiceRecorder.Result.Muted -> idle("Le micro ne capte rien. Vérifiez le bouton de coupure du Portal.")
            VoiceRecorder.Result.NothingHeard -> idle("Je n'ai rien entendu.")
            VoiceRecorder.Result.Failed -> idle("Le micro est indisponible. Le clavier reste possible.")
        }
    }

    private fun writeMessage() {
        main.removeCallbacks(autoClose)
        val builder = AlertDialog.Builder(activity, android.R.style.Theme_Material_Light_Dialog_Alert)
        val input = EditText(builder.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 4
        }
        builder.setTitle("Message à $name").setView(input)
            .setPositiveButton("Envoyer") { _, _ ->
                val message = input.text.toString().trim()
                if (message.isNotEmpty()) send { chat, progress, cancelled ->
                    chat.sendText(message, progress, cancelled, settings.spokenReplies)
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun chat(): MuseChat? = MuseSession.chat(settings.chatSession())

    private fun unreachable() = idle("$name est injoignable pour l'instant (session : ${MuseStatus.text}).")

    private fun send(request: (MuseChat, (ChatProgress) -> Unit, () -> Boolean) -> ChatResult) {
        val chat = chat() ?: return unreachable().also { asked.visibility = View.GONE }
        stopVoice()
        hushed = false
        waiting = true
        main.removeCallbacks(autoClose)
        val mine = ++turn
        reply.text = ""
        say("$name réfléchit…")
        avatar.state = AvatarView.State.WORKING
        level.visibility = View.INVISIBLE
        talk.visibility = View.GONE
        write.visibility = View.GONE
        thread(name = "muse-chat") {
            val result = try {
                request(chat, { progress -> main.post { if (mine == turn) show(progress) } }, { mine != turn })
            } catch (e: RuntimeException) {
                Log.e(TAG, "conversation en échec : ${e.javaClass.simpleName}")
                ChatResult.Failed(ChatFailure.UNCERTAIN)
            }
            // Jamais le texte échangé dans les journaux.
            Log.i(TAG, "conversation : ${describe(result)}")
            main.post { if (mine == turn) finished(result) }
        }
    }

    private fun describe(result: ChatResult) = when (result) {
        is ChatResult.Answer -> if (result.partial) "réponse partielle" else "réponse reçue"
        is ChatResult.Failed -> "échec ${result.failure}${result.status?.let { " HTTP $it" } ?: ""}"
    }

    private fun show(progress: ChatProgress) {
        readAloud(progress.finished)
        say(progress.activity ?: if (progress.text.isEmpty()) "$name réfléchit…" else "$name répond…")
        if (progress.text.isNotEmpty()) {
            if (progress.finished.isNotEmpty() && !hushed && settings.spokenReplies) say("$name lit la réponse…")
            avatar.state = AvatarView.State.SPEAKING
            reply.text = Ui.formatted(progress.text)
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun finished(result: ChatResult) {
        waiting = false
        when (result) {
            is ChatResult.Answer -> {
                reply.text = Ui.formatted(result.text)
                scroll.post { scroll.scrollTo(0, 0) }
                val written = when {
                    result.partial -> "Réponse peut-être incomplète. La suite est sur votre téléphone."
                    result.text.isEmpty() -> "$name a terminé sans réponse écrite."
                    else -> ""
                }
                readAloud(result.messageIds)
                if (settings.spokenReplies && !hushed && result.messageIds.isNotEmpty()) {
                    finishReading(written)
                } else {
                    idle(written)
                }
            }
            is ChatResult.Failed -> idle(
                when (result.failure) {
                    ChatFailure.NOT_SENT -> "Message non envoyé."
                    ChatFailure.UNCERTAIN -> "Envoi incertain : vérifiez la conversation sur votre téléphone."
                    ChatFailure.REFUSED -> "$name a refusé le message (HTTP ${result.status})."
                    ChatFailure.NO_REPLY -> "Message envoyé, pas encore de réponse. Voyez votre téléphone."
                },
            )
        }
    }
}
