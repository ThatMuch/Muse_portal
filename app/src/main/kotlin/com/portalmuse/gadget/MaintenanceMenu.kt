package com.portalmuse.gadget

import com.portalmuse.core.tr
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
/** The SDK's settings and its measurements on synthetic voices. */
private val SENSITIVITIES = listOf(
    tr("low", "basse") to tr("fewer false alarms", "moins de fausses alertes"),
    tr("normal", "normale") to tr("the SDK's setting", "le réglage du SDK"),
    tr("high", "haute") to tr(
        "hears you better, but about three times more false alarms",
        "vous entend mieux, mais environ trois fois plus de fausses alertes",
    ),
)
private const val LOCATION_PERMISSION_REQUEST = 10
private const val CAMERA_PERMISSION_REQUEST = 11

private val TEACH_SCREEN = tr(
    "Remember this: the Portal Muse gadget is a 10 inch touchscreen standing at my home. Its calls are " +
        "announced to you as coming from a wearable, but it has a large screen. When I talk to you from it or " +
        "say “on the Portal”, use its commands rather than my phone: display.show_page and display.show_images " +
        "to show things, display.notify for a banner, display.ask to ask for my approval, apps.save for a " +
        "small app, feed.set for my feed. During a call, when I ask for louder, quieter or a volume level, the " +
        "Portal does it by itself: just say OK, never that you cannot.",
    "Retiens ceci : le gadget Portal Muse est un écran tactile de 10 pouces " +
        "posé chez moi. Ses appels te sont annoncés comme venant d'un accessoire portable, mais il a un grand écran. " +
        "Quand je te parle depuis lui ou que je dis « sur le Portal », utilise ses commandes plutôt que mon " +
        "téléphone : display.show_page et display.show_images pour montrer, display.notify pour une bannière, " +
        "display.ask pour me demander un accord, apps.save pour une petite appli, feed.set pour mon fil. " +
        "Pendant un appel, quand je demande plus fort, moins fort ou un niveau de volume, le Portal le fait " +
        "lui-même : réponds simplement d'accord, sans jamais dire que tu ne peux pas.",
)

/** The kiosk's maintenance menu. [reload] reloads the dashboard. */
class MaintenanceMenu(
    private val activity: Activity,
    private val settings: Settings,
    private val onWakeWordChanged: () -> Unit,
    private val onHomeChanged: (dimNow: Boolean) -> Unit,
    /** Sends a message to Muse in the user's name and shows its reply. */
    private val tell: (String) -> Unit,
    private val reload: () -> Unit,
) {
    private val secrets = SecretStore(activity)

    /** The Portal forces a white dialog background: a light theme is needed to read the text. */
    private fun dialog() = AlertDialog.Builder(activity, android.R.style.Theme_Material_Light_Dialog_Alert)

    private class Entry(val label: String, val run: () -> Unit)

    private fun yes(value: Boolean) = if (value) tr("yes", "oui") else tr("no", "non")

    /** Six sections; each setting shows its state, and a touch changes it. */
    fun show() = list(
        "Maintenance",
        listOf(
            Entry(tr("Voice and calls", "Voix et appels"), ::voiceMenu),
            Entry(tr("What Muse may do", "Ce que Muse peut faire"), ::rightsMenu),
            Entry(tr("Screen", "Écran"), ::screenMenu),
            Entry(tr("Muse connection", "Connexion à Muse"), ::connectionMenu),
            Entry(tr("Information", "Informations"), ::showInfo),
            Entry(tr("Leave the kiosk", "Quitter le kiosque")) { activity.finishAndRemoveTask() },
        ),
        back = false,
    )

    private fun list(title: String, entries: List<Entry>, back: Boolean = true) {
        val shown = dialog()
            .setTitle(title)
            .setItems(entries.map { it.label }.toTypedArray()) { _, which -> entries[which].run() }
            .setNegativeButton(tr("Close", "Fermer"), null)
        if (back) shown.setNeutralButton(tr("Back", "Retour")) { _, _ -> show() }
        shown.show()
    }

    private fun voiceMenu(): Unit = list(
        tr("Voice and calls", "Voix et appels"),
        buildList {
            add(
                Entry(tr(
                    "“Hey Muse”: ${if (settings.wakeWord) "on" else "off"}",
                    "« Hey Muse » : ${if (settings.wakeWord) "activé" else "désactivé"}",
                )) {
                    if (settings.wakeWord) setWakeWord(false) else enableWakeWord()
                },
            )
            if (settings.wakeWord) {
                add(Entry(tr(
                    "Sensitivity: ${SENSITIVITIES[settings.wakeSensitivity].first}",
                    "Sensibilité : ${SENSITIVITIES[settings.wakeSensitivity].first}",
                ), ::chooseSensitivity))
            }
            add(
                Entry(tr(
                    "Calls: ${if (settings.videoCalls) "with Muse's video" else "voice only"}",
                    "Appels : ${if (settings.videoCalls) "avec la vidéo de Muse" else "voix seule"}",
                )) {
                    settings.videoCalls = !settings.videoCalls
                    voiceMenu()
                },
            )
            add(
                Entry(tr(
                    "Replies read aloud: ${yes(settings.spokenReplies)}",
                    "Réponses lues à voix haute : ${yes(settings.spokenReplies)}",
                )) {
                    settings.spokenReplies = !settings.spokenReplies
                    voiceMenu()
                },
            )
            add(
                Entry(tr(
                    "Separate conversation for written messages: ${yes(settings.separateChat)}",
                    "Conversation à part pour les messages écrits : ${yes(settings.separateChat)}",
                )) {
                    settings.separateChat = !settings.separateChat
                    voiceMenu()
                },
            )
        },
    )

    private fun rightsMenu(): Unit = list(
        tr("What Muse may do", "Ce que Muse peut faire"),
        buildList {
            add(Entry(tr(
                "Use the screen: ${yes(settings.screenAllowed)}",
                "Utiliser l'écran : ${yes(settings.screenAllowed)}",
            )) { allowScreen(!settings.screenAllowed) })
            if (settings.screenAllowed) add(Entry(tr(
                "Teach Muse to use this screen",
                "Apprendre à Muse à utiliser cet écran",
            ), ::teachScreen))
            add(
                Entry(tr(
                    "“Look at this” photos: ${yes(settings.cameraAllowed)}",
                    "Photos « Regarde ça » : ${yes(settings.cameraAllowed)}",
                )) {
                    if (settings.cameraAllowed) allowCamera(false) else enableCamera()
                },
            )
            if (settings.cameraAllowed) {
                add(
                    Entry(tr(
                        "Keep sent photos in Muse's files: ${yes(settings.keepPhotos)}",
                        "Ranger les photos envoyées chez Muse : ${yes(settings.keepPhotos)}",
                    )) {
                        settings.keepPhotos = !settings.keepPhotos
                        rightsMenu()
                    },
                )
            }
            add(
                Entry(tr(
                    "Bluetooth: ${yes(settings.bluetoothAllowed)}",
                    "Bluetooth : ${yes(settings.bluetoothAllowed)}",
                )) {
                    if (settings.bluetoothAllowed) allowBluetooth(false) else enableBluetooth()
                },
            )
            val known = settings.bluetoothDevices.size
            if (known > 0) add(Entry(tr(
                "Forget the allowed Bluetooth devices ($known)",
                "Oublier les objets Bluetooth autorisés ($known)",
            ), ::forgetBluetooth))
            add(
                Entry(tr(
                    "Be told about crashes: ${yes(settings.crashReports)}",
                    "Être prévenu des pannes : ${yes(settings.crashReports)}",
                )) {
                    settings.crashReports = !settings.crashReports
                    rightsMenu()
                },
            )
        },
    )

    private fun screenMenu(): Unit = list(
        tr("Screen", "Écran"),
        buildList {
            add(Entry(tr("Night mode: ${nightSummary()}", "Mode nuit : ${nightSummary()}"), ::nightMenu))
            add(
                Entry(tr(
                    "Latest messages under “More”: ${yes(settings.historyShown)}",
                    "Derniers messages sous « Plus » : ${yes(settings.historyShown)}",
                )) {
                    settings.historyShown = !settings.historyShown
                    onHomeChanged(false)
                    screenMenu()
                },
            )
            if (FrameStore.load(activity) != null) add(Entry(tr(
                "Remove the frame photos",
                "Retirer les photos du cadre",
            ), ::removeFrame))
            add(Entry(tr("Dashboard address", "Adresse du tableau de bord"), ::editUrl))
            add(Entry(tr("Reload the page", "Recharger la page"), reload))
            if (!SystemSettings.canDrawOverlays(activity)) {
                add(Entry(tr("Allow the screen at startup", "Autoriser l'écran au démarrage"), ::allowBootScreen))
            }
        },
    )

    private fun connectionMenu(): Unit = list(
        tr("Muse connection", "Connexion à Muse"),
        buildList {
            if (PairingStatus.open) {
                add(Entry(tr(
                    "Stop pairing",
                    "Arrêter l'appairage",
                )) { service(GadgetService.ACTION_STOP_PAIRING) })
            } else {
                add(Entry(tr("Open Bluetooth pairing", "Ouvrir l'appairage Bluetooth"), ::openPairing))
            }
            add(Entry(tr("Muse SDK token", "Jeton SDK Muse"), ::editToken))
            if (secrets.pairing != null) add(Entry(tr("Unpair from Muse", "Dissocier de Muse"), ::unpair))
        },
    )

    private fun service(action: String) {
        activity.startForegroundService(Intent(activity, GadgetService::class.java).setAction(action))
    }

    private fun toast(text: String) = Toast.makeText(activity, text, Toast.LENGTH_LONG).show()

    private fun openPairing() {
        if (secrets.sdkToken == null) {
            toast(tr("Enter the Muse SDK token first.", "Saisir d'abord le jeton SDK Muse."))
            editToken()
        } else if (secrets.pairing != null) {
            confirm(tr("Pair again?", "Réappairer ?"), tr(
                "The Portal is already paired. Pairing again replaces the tokens.",
                "Le Portal est déjà appairé. Un nouvel appairage remplace les jetons.",
            ), tr("Pair again", "Réappairer")) {
                service(GadgetService.ACTION_START_PAIRING)
            }
        } else {
            service(GadgetService.ACTION_START_PAIRING)
        }
    }

    private fun confirm(title: String, message: String, action: String, run: () -> Unit) {
        dialog().setTitle(title).setMessage(message)
            .setPositiveButton(action) { _, _ -> run() }
            .setNegativeButton(tr("Cancel", "Annuler"), null)
            .show()
    }

    private fun unpair() = confirm(
        tr("Unpair from Muse?", "Dissocier de Muse ?"),
        tr(
            "The tokens are erased from the Portal. Also remove the device in the Muse app.",
            "Les jetons sont effacés du Portal. Retirez aussi l'appareil dans l'app Muse.",
        ),
        tr("Unpair", "Dissocier"),
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
        toast(tr("Tokens erased from the Portal.", "Jetons effacés du Portal."))
    }

    private fun teachScreen() = confirm(
        tr("Teach Muse about this screen?", "Apprendre l'écran à Muse ?"),
        tr(
            "This message goes into your conversation, so that Muse remembers it:\n\n“$TEACH_SCREEN”",
            "Ce message part dans votre conversation, pour que Muse s'en souvienne :\n\n« $TEACH_SCREEN »",
        ),
        tr("Send", "Envoyer"),
    ) { tell(TEACH_SCREEN) }

    /** The session reopens to declare, or remove, the screen commands. */
    private fun allowScreen(allowed: Boolean) {
        settings.screenAllowed = allowed
        service(GadgetService.ACTION_PAIRING_CHANGED)
        onHomeChanged(false)
        toast(if (allowed) tr("Muse may use the screen.", "Muse peut utiliser l'écran.") else tr(
            "Muse may no longer use the screen.",
            "Muse ne peut plus utiliser l'écran.",
        ))
    }

    private fun isNight() = settings.night.isNight(Clock.minuteOfDay(), System.currentTimeMillis())

    private fun nightSummary(): String {
        val night = settings.night
        val hours = tr(
            "${NightSettings.formatTime(night.startMinute)} to ${NightSettings.formatTime(night.endMinute)}",
            "${NightSettings.formatTime(night.startMinute)} à ${NightSettings.formatTime(night.endMinute)}",
        )
        return (if (isNight()) tr("on, ", "actif, ") else "") + if (night.scheduled) hours else tr(
            "no schedule",
            "sans horaires",
        )
    }

    private fun nightMenu() {
        val active = isNight()
        val scheduled = settings.night.scheduled
        val entries = listOf<Pair<String, () -> Unit>>(
            (if (active) tr("Leave night mode now", "Sortir du mode nuit maintenant") else tr(
                "Start night mode now",
                "Passer en mode nuit maintenant",
            )) to {
                settings.changeNight { it.force(!active, Clock.minuteOfDay(), System.currentTimeMillis()) }
                onHomeChanged(!active)
            },
            tr("Change the hours", "Changer les horaires") to ::editNightHours,
            (if (scheduled) tr("Turn the schedule off", "Désactiver les horaires") else tr(
                "Turn the schedule on",
                "Activer les horaires",
            )) to {
                settings.changeNight { it.copy(scheduled = !scheduled, forced = null) }
                onHomeChanged(false)
            },
        )
        dialog().setTitle(tr("Night mode", "Mode nuit"))
            .setItems(entries.map { it.first }.toTypedArray()) { _, which -> entries[which].second() }
            .setNegativeButton(tr("Close", "Fermer"), null)
            .show()
    }

    private fun editNightHours() = pickTime(tr(
        "Start of the night",
        "Début de la nuit",
    ), settings.night.startMinute) { start ->
        pickTime(tr("End of the night", "Fin de la nuit"), settings.night.endMinute) { end ->
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

    private fun removeFrame() = confirm(tr("Remove the photos?", "Retirer les photos ?"), tr(
        "The photo frame will no longer show.",
        "Le cadre photo ne s'affichera plus.",
    ), tr("Remove", "Retirer")) {
        FrameStore.clear(activity)
        onHomeChanged(false)
    }

    private fun enableCamera() = confirm(
        tr("Enable photos?", "Activer les photos ?"),
        tr(
            "A “Look at this” button appears on the home screen, and Muse will be able to ask for a photo. " +
                "Either way, nothing is taken or sent unless you touch “Take the photo” then “Send”.",
            "Un bouton « Regarde ça » apparaît à l'accueil, et Muse pourra demander une photo. Dans les deux cas, " +
                "rien n'est pris ni envoyé sans que vous touchiez « Prendre la photo » puis « Envoyer ».",
        ),
        tr("Enable", "Activer"),
    ) {
        if (activity.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
        }
        allowCamera(true)
    }

    /** The session reopens to declare, or remove, the photo command. */
    private fun allowCamera(allowed: Boolean) {
        settings.cameraAllowed = allowed
        onHomeChanged(false)
        service(GadgetService.ACTION_PAIRING_CHANGED)
        toast(if (allowed) tr(
            "Photos enabled.",
            "Photos activées.",
        ) else tr("Photos disabled.", "Photos désactivées."))
    }

    private fun enableBluetooth() = confirm(
        tr("Allow Bluetooth?", "Autoriser le Bluetooth ?"),
        tr(
            "Muse will be able to see the Bluetooth devices around the Portal. To connect to a new one, it " +
                "will ask for your approval on screen. Android requires the “location” permission for scans.",
            "Muse pourra voir les appareils Bluetooth autour du Portal. Pour se connecter à un nouvel objet, " +
                "il demandera votre accord à l'écran. Android exige l'autorisation « position » pour les recherches.",
        ),
        tr("Allow", "Autoriser"),
    ) {
        if (activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), LOCATION_PERMISSION_REQUEST)
        }
        allowBluetooth(true)
    }

    /** The session reopens to declare, or remove, the Bluetooth commands. */
    private fun allowBluetooth(allowed: Boolean) {
        settings.bluetoothAllowed = allowed
        if (!allowed) BleCentral.closeAll()
        service(GadgetService.ACTION_PAIRING_CHANGED)
        toast(if (allowed) tr("Muse may use Bluetooth.", "Muse peut utiliser le Bluetooth.") else tr(
            "Muse may no longer use Bluetooth.",
            "Muse ne peut plus utiliser le Bluetooth.",
        ))
    }

    private fun forgetBluetooth() = confirm(
        tr("Forget these devices?", "Oublier ces objets ?"),
        settings.bluetoothDevices.entries.joinToString("\n") { tr(
            "${it.value.ifEmpty { "No name" }}  ${it.key}",
            "${it.value.ifEmpty { "Sans nom" }}  ${it.key}",
        ) },
        tr("Forget", "Oublier"),
    ) {
        BleCentral.closeAll()
        settings.forgetBluetoothDevices()
    }

    private fun chooseSensitivity() {
        val labels = SENSITIVITIES.map { it.first.replaceFirstChar(Char::uppercase) + tr(": ", " : ") + it.second }
        dialog().setTitle(tr("“Hey Muse” sensitivity", "Sensibilité de « Hey Muse »"))
            .setSingleChoiceItems(labels.toTypedArray(), settings.wakeSensitivity) { shown, which ->
                settings.wakeSensitivity = which
                shown.dismiss()
                onWakeWordChanged()
            }
            .setNegativeButton(tr("Cancel", "Annuler"), null)
            .show()
    }

    private fun setWakeWord(enabled: Boolean) {
        settings.wakeWord = enabled
        onWakeWordChanged()
    }

    private fun enableWakeWord() = confirm(
        tr("Enable “Hey Muse”?", "Activer « Hey Muse » ?"),
        tr(
            "While this screen is shown, the microphone listens for the words on the Portal, without " +
                "sending anything. When it hears them, a call opens and sound goes to Muse. It closes after 20 " +
                "s without a request. It can get it wrong, up to about ten times a day.",
            "Tant que cet écran est affiché, le micro écoute le mot sur le Portal, sans rien envoyer. " +
                "Quand il l'entend, un appel s'ouvre et le son part vers Muse. Il se ferme après 20 s sans demande. " +
                "Il peut se tromper, jusqu'à une dizaine de fois par jour.",
        ),
        tr("Enable", "Activer"),
    ) {
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), WAKE_PERMISSION_REQUEST)
        }
        setWakeWord(true)
    }

    /** Opens the Android setting: it is up to the user to turn it on. */
    private fun allowBootScreen() = confirm(
        tr("Screen at startup", "Écran au démarrage"),
        tr(
            "To open on its own after a reboot, the app needs “Display over other apps”. It draws nothing " +
                "over other apps.",
            "Pour s'ouvrir seule après un redémarrage, l'app a besoin de « Afficher par-dessus les autres " +
                "applications ». Elle ne dessine rien par-dessus les autres apps.",
        ),
        tr("Open the setting", "Ouvrir le réglage"),
    ) {
        val target = Uri.parse("package:${activity.packageName}")
        try {
            activity.startActivity(Intent(SystemSettings.ACTION_MANAGE_OVERLAY_PERMISSION, target))
        } catch (e: ActivityNotFoundException) {
            toast(tr(
                "This setting is not available on the Portal.",
                "Ce réglage n'est pas disponible sur le Portal.",
            ))
        }
    }

    /** The token is masked, never shown again or logged. */
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
            .setTitle(tr("Muse SDK token", "Jeton SDK Muse"))
            .setMessage(tr(
                "gadgets.muse.ai > Settings > SDK tokens.\nCurrent token: ${SdkToken.fingerprint(current)}.",
                "gadgets.muse.ai > Settings > SDK tokens.\nJeton actuel : ${SdkToken.fingerprint(current)}.",
            ))
            .setView(input)
            .setPositiveButton(tr("Save", "Enregistrer")) { _, _ ->
                val token = input.text.toString().trim()
                if (!SdkToken.isValid(token)) {
                    toast(tr(
                        "Token not saved: unexpected format (${token.length} characters).",
                        "Jeton non enregistré : format inattendu (${token.length} caractères).",
                    ))
                } else {
                    secrets.sdkToken = token
                    toast(if (secrets.sdkToken == token) tr("Token saved.", "Jeton enregistré.") else tr(
                        "Encrypted storage failed.",
                        "Échec du stockage chiffré.",
                    ))
                }
            }
            .setNegativeButton(tr("Cancel", "Annuler"), null)
            .apply {
                if (current != null) setNeutralButton(tr("Erase", "Effacer")) { _, _ -> secrets.sdkToken = null }
            }
            .show()
    }

    private fun editUrl() {
        val builder = dialog()
        val input = EditText(builder.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            // Android 10 does not resolve .local names: an IP address is needed.
            hint = "http://192.168.1.50:8123"
            setText(settings.dashboardUrl.takeIf { it != Settings.WELCOME_URL }.orEmpty())
        }
        builder
            .setTitle(tr("Dashboard address", "Adresse du tableau de bord"))
            .setMessage(tr(
                "Leave empty to go back to the home page.",
                "Laisser vide pour revenir à la page d'accueil.",
            ))
            .setView(input)
            .setPositiveButton(tr("Save", "Enregistrer")) { _, _ ->
                val text = input.text.toString().trim()
                val uri = Uri.parse(text)
                if (text.isEmpty()) {
                    settings.dashboardUrl = Settings.WELCOME_URL
                } else if (uri.scheme in listOf("http", "https") && !uri.host.isNullOrEmpty()) {
                    settings.dashboardUrl = text
                } else {
                    toast(tr(
                        "The address must start with http:// or https://",
                        "L'adresse doit commencer par http:// ou https://",
                    ))
                    return@setPositiveButton
                }
                reload()
            }
            .setNegativeButton(tr("Cancel", "Annuler"), null)
            .show()
    }

    private fun showInfo() {
        val version = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName
        val webView = WebView.getCurrentWebViewPackage()?.versionName ?: tr("unknown", "inconnue")
        val pairing = secrets.pairing?.let {
            val date = DateFormat.getDateTimeInstance().format(Date(it.accessTokenSavedAt * 1000))
            tr("yes (${it.nodeId}), tokens checked on $date", "oui (${it.nodeId}), jetons vérifiés le $date")
        } ?: tr("no", "non")
        val lines = listOf(
            tr("Version: $version", "Version : $version"),
            tr(
                "Bluetooth pairing name: ${settings.identity().bleName}",
                "Nom Bluetooth d'appairage : ${settings.identity().bleName}",
            ),
            tr("Paired with Muse: $pairing", "Appairé à Muse : $pairing"),
            tr("Muse session: ${MuseStatus.text}", "Session Muse : ${MuseStatus.text}"),
            tr(
                "Screen for Muse: ${if (settings.screenAllowed) "allowed" else "not allowed"}",
                "Écran pour Muse : ${if (settings.screenAllowed) "autorisé" else "interdit"}",
            ),
            tr(
                "Photos: ${if (settings.cameraAllowed) "enabled" else "disabled"}",
                "Photos : ${if (settings.cameraAllowed) "activées" else "désactivées"}",
            ),
            tr(
                "Bluetooth for Muse: ${if (settings.bluetoothAllowed) "allowed" else "not allowed"}",
                "Bluetooth pour Muse : ${if (settings.bluetoothAllowed) "autorisé" else "interdit"}",
            ),
            tr(
                "Hey Muse: ${if (settings.wakeWord) "on" else "off"}",
                "Hey Muse : ${if (settings.wakeWord) "activé" else "désactivé"}",
            ),
            tr(
                "SDK token: ${SdkToken.fingerprint(secrets.sdkToken)}",
                "Jeton SDK : ${SdkToken.fingerprint(secrets.sdkToken)}",
            ),
            tr(
                "Screen at startup: ${if (SystemSettings.canDrawOverlays(activity)) "allowed" else "not allowed"}",
                "Écran au démarrage : ${if (SystemSettings.canDrawOverlays(activity)) "autorisé" else "non autorisé"}",
            ),
            tr(
                "Encrypted storage: ${if (secrets.selfCheck()) "working" else "failing"}",
                "Stockage chiffré : ${if (secrets.selfCheck()) "fonctionne" else "en échec"}",
            ),
            tr("WebView: $webView", "WebView : $webView"),
            tr("Address: ${settings.dashboardUrl}", "Adresse : ${settings.dashboardUrl}"),
        )
        fun text(server: String) = (lines + tr(
            "Muse server: $server",
            "Serveur Muse : $server",
        )).joinToString("\n")
        val shown = dialog().setTitle(tr(
            "Information",
            "Informations",
        )).setMessage(text(tr("testing…", "test en cours…")))
            .setPositiveButton("OK", null).show()
        thread(name = "muse-probe") {
            val result = try {
                MuseHttp.api(activity).probe()
            } catch (e: Exception) {
                tr("TLS unavailable (${e.javaClass.simpleName})", "TLS indisponible (${e.javaClass.simpleName})")
            }
            activity.runOnUiThread { if (shown.isShowing) shown.setMessage(text(result)) }
        }
    }
}
