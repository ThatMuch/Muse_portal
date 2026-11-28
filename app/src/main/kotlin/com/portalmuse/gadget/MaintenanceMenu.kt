package com.portalmuse.gadget

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings as SystemSettings
import android.text.InputType
import android.view.View
import android.webkit.WebView
import android.widget.EditText
import android.widget.Toast
import com.portalmuse.core.NightSettings
import com.portalmuse.core.SdkToken
import java.text.DateFormat
import java.util.Date
import kotlin.concurrent.thread

private const val WAKE_PERMISSION_REQUEST = 9
/** Les réglages du SDK et ses mesures sur des voix de synthèse. */
private val SENSITIVITIES = listOf(
    "basse" to "moins de fausses alertes",
    "normale" to "le réglage du SDK",
    "haute" to "vous entend mieux, mais environ trois fois plus de fausses alertes",
)
private const val LOCATION_PERMISSION_REQUEST = 10
private const val CAMERA_PERMISSION_REQUEST = 11

private const val TEACH_SCREEN = "Retiens ceci : le gadget Portal Muse est un écran tactile de 10 pouces " +
    "posé chez moi. Ses appels te sont annoncés comme venant d'un accessoire portable, mais il a un grand écran. " +
    "Quand je te parle depuis lui ou que je dis « sur le Portal », utilise ses commandes plutôt que mon " +
    "téléphone : display.show_page et display.show_images pour montrer, display.notify pour une bannière, " +
    "display.ask pour me demander un accord, apps.save pour une petite appli, feed.set pour mon fil."

/** Menu de maintenance du kiosque. [reload] recharge le tableau de bord. */
class MaintenanceMenu(
    private val activity: Activity,
    private val settings: Settings,
    private val onWakeWordChanged: () -> Unit,
    private val onHomeChanged: (dimNow: Boolean) -> Unit,
    /** Envoie un message à Muse au nom de l'utilisateur et montre sa réponse. */
    private val tell: (String) -> Unit,
    private val reload: () -> Unit,
) {
    private val secrets = SecretStore(activity)

    /** Le Portal impose un fond de dialogue blanc : il faut un thème clair pour lire le texte. */
    private fun dialog() = AlertDialog.Builder(activity, android.R.style.Theme_Material_Light_Dialog_Alert)

    private class Entry(val label: String, val run: () -> Unit)

    private fun yes(value: Boolean) = if (value) "oui" else "non"

    /** Six rubriques ; chaque réglage affiche son état, et un toucher le change. */
    fun show() = list(
        "Maintenance",
        listOf(
            Entry("Voix et appels", ::voiceMenu),
            Entry("Ce que Muse peut faire", ::rightsMenu),
            Entry("Écran", ::screenMenu),
            Entry("Connexion à Muse", ::connectionMenu),
            Entry("Informations", ::showInfo),
            Entry("Quitter le kiosque") { activity.finishAndRemoveTask() },
        ),
        back = false,
    )

    private fun list(title: String, entries: List<Entry>, back: Boolean = true) {
        val shown = dialog()
            .setTitle(title)
            .setItems(entries.map { it.label }.toTypedArray()) { _, which -> entries[which].run() }
            .setNegativeButton("Fermer", null)
        if (back) shown.setNeutralButton("Retour") { _, _ -> show() }
        shown.show()
    }

    private fun voiceMenu(): Unit = list(
        "Voix et appels",
        buildList {
            add(
                Entry("« Hey Muse » : ${if (settings.wakeWord) "activé" else "désactivé"}") {
                    if (settings.wakeWord) setWakeWord(false) else enableWakeWord()
                },
            )
            if (settings.wakeWord) {
                add(Entry("Sensibilité : ${SENSITIVITIES[settings.wakeSensitivity].first}", ::chooseSensitivity))
            }
            add(
                Entry("Appels : ${if (settings.videoCalls) "avec la vidéo de Muse" else "voix seule"}") {
                    settings.videoCalls = !settings.videoCalls
                    voiceMenu()
                },
            )
            add(
                Entry("Réponses lues à voix haute : ${yes(settings.spokenReplies)}") {
                    settings.spokenReplies = !settings.spokenReplies
                    voiceMenu()
                },
            )
            add(
                Entry("Conversation à part pour les messages écrits : ${yes(settings.separateChat)}") {
                    settings.separateChat = !settings.separateChat
                    voiceMenu()
                },
            )
        },
    )

    private fun rightsMenu(): Unit = list(
        "Ce que Muse peut faire",
        buildList {
            add(Entry("Utiliser l'écran : ${yes(settings.screenAllowed)}") { allowScreen(!settings.screenAllowed) })
            if (settings.screenAllowed) add(Entry("Apprendre à Muse à utiliser cet écran", ::teachScreen))
            add(
                Entry("Photos « Regarde ça » : ${yes(settings.cameraAllowed)}") {
                    if (settings.cameraAllowed) allowCamera(false) else enableCamera()
                },
            )
            if (settings.cameraAllowed) {
                add(
                    Entry("Ranger les photos envoyées chez Muse : ${yes(settings.keepPhotos)}") {
                        settings.keepPhotos = !settings.keepPhotos
                        rightsMenu()
                    },
                )
            }
            add(
                Entry("Bluetooth : ${yes(settings.bluetoothAllowed)}") {
                    if (settings.bluetoothAllowed) allowBluetooth(false) else enableBluetooth()
                },
            )
            val known = settings.bluetoothDevices.size
            if (known > 0) add(Entry("Oublier les objets Bluetooth autorisés ($known)", ::forgetBluetooth))
            add(
                Entry("Être prévenu des pannes : ${yes(settings.crashReports)}") {
                    settings.crashReports = !settings.crashReports
                    rightsMenu()
                },
            )
        },
    )

    private fun screenMenu(): Unit = list(
        "Écran",
        buildList {
            add(Entry("Mode nuit : ${nightSummary()}", ::nightMenu))
            add(
                Entry("Derniers messages sous « Plus » : ${yes(settings.historyShown)}") {
                    settings.historyShown = !settings.historyShown
                    onHomeChanged(false)
                    screenMenu()
                },
            )
            if (FrameStore.load(activity) != null) add(Entry("Retirer les photos du cadre", ::removeFrame))
            add(Entry("Adresse du tableau de bord", ::editUrl))
            add(Entry("Recharger la page", reload))
            if (!SystemSettings.canDrawOverlays(activity)) {
                add(Entry("Autoriser l'écran au démarrage", ::allowBootScreen))
            }
        },
    )

    private fun connectionMenu(): Unit = list(
        "Connexion à Muse",
        buildList {
            if (PairingStatus.open) {
                add(Entry("Arrêter l'appairage") { service(GadgetService.ACTION_STOP_PAIRING) })
            } else {
                add(Entry("Ouvrir l'appairage Bluetooth", ::openPairing))
            }
            add(Entry("Jeton SDK Muse", ::editToken))
            if (secrets.pairing != null) add(Entry("Dissocier de Muse", ::unpair))
        },
    )

    private fun service(action: String) {
        activity.startForegroundService(Intent(activity, GadgetService::class.java).setAction(action))
    }

    private fun toast(text: String) = Toast.makeText(activity, text, Toast.LENGTH_LONG).show()

    private fun openPairing() {
        if (secrets.sdkToken == null) {
            toast("Saisir d'abord le jeton SDK Muse.")
            editToken()
        } else if (secrets.pairing != null) {
            confirm("Réappairer ?", "Le Portal est déjà appairé. Un nouvel appairage remplace les jetons.", "Réappairer") {
                service(GadgetService.ACTION_START_PAIRING)
            }
        } else {
            service(GadgetService.ACTION_START_PAIRING)
        }
    }

    private fun confirm(title: String, message: String, action: String, run: () -> Unit) {
        dialog().setTitle(title).setMessage(message)
            .setPositiveButton(action) { _, _ -> run() }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun unpair() = confirm(
        "Dissocier de Muse ?",
        "Les jetons sont effacés du Portal. Retirez aussi l'appareil dans l'app Muse.",
        "Dissocier",
    ) {
        secrets.deletePairing()
        AvatarStore.clear(activity)
        FrameStore.clear(activity)
        AppStore.clear(activity)
        FeedStore.clear(activity)
        settings.forgetChatSession()
        BleCentral.closeAll()
        settings.forgetBluetoothDevices()
        settings.customShortcuts = emptyList()
        onHomeChanged(false)
        service(GadgetService.ACTION_PAIRING_CHANGED)
        toast("Jetons effacés du Portal.")
    }

    private fun teachScreen() = confirm(
        "Apprendre l'écran à Muse ?",
        "Ce message part dans votre conversation, pour que Muse s'en souvienne :\n\n« $TEACH_SCREEN »",
        "Envoyer",
    ) { tell(TEACH_SCREEN) }

    /** La session se rouvre pour déclarer, ou retirer, les commandes d'écran. */
    private fun allowScreen(allowed: Boolean) {
        settings.screenAllowed = allowed
        service(GadgetService.ACTION_PAIRING_CHANGED)
        onHomeChanged(false)
        toast(if (allowed) "Muse peut utiliser l'écran." else "Muse ne peut plus utiliser l'écran.")
    }

    private fun isNight() = settings.night.isNight(Clock.minuteOfDay(), System.currentTimeMillis())

    private fun nightSummary(): String {
        val night = settings.night
        val hours = "${NightSettings.formatTime(night.startMinute)} à ${NightSettings.formatTime(night.endMinute)}"
        return (if (isNight()) "actif, " else "") + if (night.scheduled) hours else "sans horaires"
    }

    private fun nightMenu() {
        val active = isNight()
        val scheduled = settings.night.scheduled
        val entries = listOf<Pair<String, () -> Unit>>(
            (if (active) "Sortir du mode nuit maintenant" else "Passer en mode nuit maintenant") to {
                settings.changeNight { it.force(!active, Clock.minuteOfDay(), System.currentTimeMillis()) }
                onHomeChanged(!active)
            },
            "Changer les horaires" to ::editNightHours,
            (if (scheduled) "Désactiver les horaires" else "Activer les horaires") to {
                settings.changeNight { it.copy(scheduled = !scheduled, forced = null) }
                onHomeChanged(false)
            },
        )
        dialog().setTitle("Mode nuit")
            .setItems(entries.map { it.first }.toTypedArray()) { _, which -> entries[which].second() }
            .setNegativeButton("Fermer", null)
            .show()
    }

    private fun editNightHours() = pickTime("Début de la nuit", settings.night.startMinute) { start ->
        pickTime("Fin de la nuit", settings.night.endMinute) { end ->
            settings.changeNight { it.copy(scheduled = true, startMinute = start, endMinute = end, forced = null) }
            onHomeChanged(false)
        }
    }

    private fun pickTime(title: String, minute: Int, done: (Int) -> Unit) {
        TimePickerDialog(
            activity, android.R.style.Theme_Material_Light_Dialog_Alert,
            { _, hours, minutes -> done(hours * 60 + minutes) }, minute / 60, minute % 60, true,
        ).apply { setTitle(title) }.show()
    }

    private fun removeFrame() = confirm("Retirer les photos ?", "Le cadre photo ne s'affichera plus.", "Retirer") {
        FrameStore.clear(activity)
        onHomeChanged(false)
    }

    private fun enableCamera() = confirm(
        "Activer les photos ?",
        "Un bouton « Regarde ça » apparaît à l'accueil, et Muse pourra demander une photo. Dans les deux cas, " +
            "rien n'est pris ni envoyé sans que vous touchiez « Prendre la photo » puis « Envoyer ».",
        "Activer",
    ) {
        if (activity.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
        }
        allowCamera(true)
    }

    /** La session se rouvre pour déclarer, ou retirer, la commande photo. */
    private fun allowCamera(allowed: Boolean) {
        settings.cameraAllowed = allowed
        onHomeChanged(false)
        service(GadgetService.ACTION_PAIRING_CHANGED)
        toast(if (allowed) "Photos activées." else "Photos désactivées.")
    }

    private fun enableBluetooth() = confirm(
        "Autoriser le Bluetooth ?",
        "Muse pourra voir les appareils Bluetooth autour du Portal. Pour se connecter à un nouvel objet, " +
            "il demandera votre accord à l'écran. Android exige l'autorisation « position » pour les recherches.",
        "Autoriser",
    ) {
        if (activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), LOCATION_PERMISSION_REQUEST)
        }
        allowBluetooth(true)
    }

    /** La session se rouvre pour déclarer, ou retirer, les commandes Bluetooth. */
    private fun allowBluetooth(allowed: Boolean) {
        settings.bluetoothAllowed = allowed
        if (!allowed) BleCentral.closeAll()
        service(GadgetService.ACTION_PAIRING_CHANGED)
        toast(if (allowed) "Muse peut utiliser le Bluetooth." else "Muse ne peut plus utiliser le Bluetooth.")
    }

    private fun forgetBluetooth() = confirm(
        "Oublier ces objets ?",
        settings.bluetoothDevices.entries.joinToString("\n") { "${it.value.ifEmpty { "Sans nom" }}  ${it.key}" },
        "Oublier",
    ) {
        BleCentral.closeAll()
        settings.forgetBluetoothDevices()
    }

    private fun chooseSensitivity() {
        val labels = SENSITIVITIES.map { "${it.first.replaceFirstChar(Char::uppercase)} : ${it.second}" }
        dialog().setTitle("Sensibilité de « Hey Muse »")
            .setSingleChoiceItems(labels.toTypedArray(), settings.wakeSensitivity) { shown, which ->
                settings.wakeSensitivity = which
                shown.dismiss()
                onWakeWordChanged()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun setWakeWord(enabled: Boolean) {
        settings.wakeWord = enabled
        onWakeWordChanged()
    }

    private fun enableWakeWord() = confirm(
        "Activer « Hey Muse » ?",
        "Tant que cet écran est affiché, le micro écoute le mot sur le Portal, sans rien envoyer. " +
            "Quand il l'entend, un appel s'ouvre et le son part vers Muse. Il se ferme après 20 s sans demande. " +
            "Il peut se tromper, jusqu'à une dizaine de fois par jour.",
        "Activer",
    ) {
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), WAKE_PERMISSION_REQUEST)
        }
        setWakeWord(true)
    }

    /** Ouvre le réglage Android : c'est à l'utilisateur de l'activer. */
    private fun allowBootScreen() = confirm(
        "Écran au démarrage",
        "Pour s'ouvrir seule après un redémarrage, l'app a besoin de « Afficher par-dessus les autres " +
            "applications ». Elle ne dessine rien par-dessus les autres apps.",
        "Ouvrir le réglage",
    ) {
        val target = Uri.parse("package:${activity.packageName}")
        try {
            activity.startActivity(Intent(SystemSettings.ACTION_MANAGE_OVERLAY_PERMISSION, target))
        } catch (e: ActivityNotFoundException) {
            toast("Ce réglage n'est pas disponible sur le Portal.")
        }
    }

    /** Le jeton est masqué, jamais réaffiché ni journalisé. */
    private fun editToken() {
        val builder = dialog()
        val input = EditText(builder.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            hint = "mgst_…"
        }
        val current = secrets.sdkToken
        builder
            .setTitle("Jeton SDK Muse")
            .setMessage("gadgets.muse.ai > Settings > SDK tokens.\nJeton actuel : ${SdkToken.fingerprint(current)}.")
            .setView(input)
            .setPositiveButton("Enregistrer") { _, _ ->
                val token = input.text.toString().trim()
                if (!SdkToken.isValid(token)) {
                    toast("Jeton non enregistré : format inattendu (${token.length} caractères).")
                } else {
                    secrets.sdkToken = token
                    toast(if (secrets.sdkToken == token) "Jeton enregistré." else "Échec du stockage chiffré.")
                }
            }
            .setNegativeButton("Annuler", null)
            .apply {
                if (current != null) setNeutralButton("Effacer") { _, _ -> secrets.sdkToken = null }
            }
            .show()
    }

    private fun editUrl() {
        val builder = dialog()
        val input = EditText(builder.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            // Android 10 ne résout pas les noms en .local : il faut l'adresse IP.
            hint = "http://192.168.1.50:8123"
            setText(settings.dashboardUrl.takeIf { it != Settings.WELCOME_URL }.orEmpty())
        }
        builder
            .setTitle("Adresse du tableau de bord")
            .setMessage("Laisser vide pour revenir à la page d'accueil.")
            .setView(input)
            .setPositiveButton("Enregistrer") { _, _ ->
                val text = input.text.toString().trim()
                val uri = Uri.parse(text)
                if (text.isEmpty()) {
                    settings.dashboardUrl = Settings.WELCOME_URL
                } else if (uri.scheme in listOf("http", "https") && !uri.host.isNullOrEmpty()) {
                    settings.dashboardUrl = text
                } else {
                    toast("L'adresse doit commencer par http:// ou https://")
                    return@setPositiveButton
                }
                reload()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun showInfo() {
        val version = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName
        val webView = WebView.getCurrentWebViewPackage()?.versionName ?: "inconnue"
        val pairing = secrets.pairing?.let {
            val date = DateFormat.getDateTimeInstance().format(Date(it.accessTokenSavedAt * 1000))
            "oui (${it.nodeId}), jetons vérifiés le $date"
        } ?: "non"
        val lines = listOf(
            "Version : $version",
            "Nom Bluetooth d'appairage : ${settings.identity().bleName}",
            "Appairé à Muse : $pairing",
            "Session Muse : ${MuseStatus.text}",
            "Écran pour Muse : ${if (settings.screenAllowed) "autorisé" else "interdit"}",
            "Photos : ${if (settings.cameraAllowed) "activées" else "désactivées"}",
            "Bluetooth pour Muse : ${if (settings.bluetoothAllowed) "autorisé" else "interdit"}",
            "Hey Muse : ${if (settings.wakeWord) "activé" else "désactivé"}",
            "Jeton SDK : ${SdkToken.fingerprint(secrets.sdkToken)}",
            "Écran au démarrage : ${if (SystemSettings.canDrawOverlays(activity)) "autorisé" else "non autorisé"}",
            "Stockage chiffré : ${if (secrets.selfCheck()) "fonctionne" else "en échec"}",
            "WebView : $webView",
            "Adresse : ${settings.dashboardUrl}",
        )
        fun text(server: String) = (lines + "Serveur Muse : $server").joinToString("\n")
        val shown = dialog().setTitle("Informations").setMessage(text("test en cours…"))
            .setPositiveButton("OK", null).show()
        thread(name = "muse-probe") {
            val result = try {
                MuseHttp.api(activity).probe()
            } catch (e: Exception) {
                "TLS indisponible (${e.javaClass.simpleName})"
            }
            activity.runOnUiThread { if (shown.isShowing) shown.setMessage(text(result)) }
        }
    }
}
