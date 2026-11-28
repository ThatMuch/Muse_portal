package com.portalmuse.gadget

import com.portalmuse.core.tr
import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.portalmuse.core.AskResult
import com.portalmuse.core.LoadedPicture
import com.portalmuse.core.Notice
import com.portalmuse.core.PhotoResult
import com.portalmuse.core.Question
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import kotlin.concurrent.thread
import kotlin.math.abs

private const val TAG = "PortalMuse"
private const val CORNER_DP = 80
private const val KEYBOARD_MIN_DP = 150
private const val HOLD_MS = 2_000L
private const val RETRY_MS = 15_000L
private const val MAX_HOME_VIDEO_BYTES = 12L * 1024 * 1024
private const val TICK_MS = 5_000L
private const val NIGHT_WAKE_MS = 30_000L
private const val SHORTCUT_SCHEME = "portal-shortcut"
private const val TIMER_SCHEME = "portal-timer"

/** Full screen dashboard. Hidden menu: hold the bottom left corner. */
class KioskActivity : Activity(), ScreenPresenter {
    private lateinit var settings: Settings
    private lateinit var menu: MaintenanceMenu
    private lateinit var conversation: ConversationPanel
    private lateinit var phone: CallPanel
    private lateinit var wakeHint: TextView
    private lateinit var shown: ScreenOverlay
    private lateinit var photoFrame: PhotoFrame
    private lateinit var dimmer: NightDimmer
    private lateinit var questions: QuestionPanel
    private lateinit var photos: PhotoPanel
    private lateinit var apps: AppPanel
    private lateinit var feed: FeedPanel
    private lateinit var hub: HubPanel
    private lateinit var alarm: TimerPanel
    private lateinit var notices: Notices
    private var keyboardShown = false
    /** Last touch: at night, the screen stays lit for a moment afterwards. */
    private var lastTouch = 0L
    /** Last use, touch or panel: the photo frame waits from this point. */
    private var lastUse = SystemClock.elapsedRealtime()
    private var swallowGesture = false
    private val ticker = object : Runnable {
        override fun run() {
            refreshAmbience()
            handler.postDelayed(this, TICK_MS)
        }
    }
    private var listener: WakeListener? = null
    private var resumed = false
    private val avatarChanged: () -> Unit = {
        showAvatarOnHome()
        if (conversation.isOpen) conversation.reloadAvatar()
        if (phone.isOpen) phone.reloadAvatar()
    }
    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var errorBanner: TextView
    private lateinit var pairingBanner: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val openMenu = Runnable { menu.show() }
    private val retry = Runnable { loadDashboard() }
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var loadFailed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        menu = MaintenanceMenu(
            this, settings, onWakeWordChanged = ::restartListening, onHomeChanged = ::homeChanged,
            tell = { conversation.sendShortcut(it) },
        ) {
            web.clearHistory()
            loadDashboard()
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root = FrameLayout(this)
        web = createWebView()
        errorBanner = banner(Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        pairingBanner = banner(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        root.addView(web, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(errorBanner)
        conversation = ConversationPanel(this, root, onCall = { phone.open() }, onMore = ::showMore) {
            updateListening()
            panelsChanged()
        }
        phone = CallPanel(this, root, onHangUp = { shown.close() }) {
            conversation.covered = phone.isOpen
            updateListening()
            panelsChanged()
        }
        // The Portal's volume buttons set the volume of Muse's voice.
        volumeControlStream = AudioManager.STREAM_MUSIC
        // Above the panels: you look at what Muse shows while talking to it.
        shown = ScreenOverlay(this, root) { panelsChanged() }
        hub = HubPanel(this, root) { panelsChanged() }
        feed = FeedPanel(this, root, onAsk = { conversation.sendShortcut(it) }, onStale = ::refreshFeed) {
            panelsChanged()
        }
        apps = AppPanel(this, root) { panelsChanged() }
        photoFrame = PhotoFrame(this, root)
        dimmer = NightDimmer(this, root)
        // A question and the banners go above everything, even at night.
        photos = PhotoPanel(this, root) {
            updateListening()
            panelsChanged()
        }
        questions = QuestionPanel(this, root) {
            panelsChanged()
        }
        alarm = TimerPanel(this, root) { panelsChanged() }
        notices = Notices(this, root) { refreshAmbience() }
        root.addView(pairingBanner)
        wakeHint = TextView(this).apply {
            text = tr("Say “Hey Muse”", "Dites « Hey Muse »")
            textSize = 16f
            setTextColor(Ui.FAINT)
            visibility = View.GONE
            val margin = Ui.dp(this@KioskActivity, 24)
            layoutParams = FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.START)
                .apply { setMargins(margin, margin, margin, margin + Ui.dp(this@KioskActivity, 10)) }
        }
        // Under the panels, which cover the screen when open.
        root.addView(wakeHint, root.indexOfChild(errorBanner) + 1)
        setContentView(root)
        root.viewTreeObserver.addOnGlobalLayoutListener {
            val visible = Rect()
            root.getWindowVisibleDisplayFrame(visible)
            val hidden = root.rootView.height - visible.bottom
            keyboardShown = hidden > KEYBOARD_MIN_DP * resources.displayMetrics.density
            conversation.keyboardShown = keyboardShown
        }
        // Restores the original Bluetooth name if the app was killed during pairing.
        if (!PairingStatus.open) GadgetService.restoreBluetoothName(this)
        // The service keeps the Muse session, even after leaving the kiosk.
        startForegroundService(Intent(this, GadgetService::class.java).setAction(GadgetService.ACTION_CONNECT))
        loadDashboard()
    }

    override fun onStart() {
        super.onStart()
        TimerCenter.onChange = ::timersChanged
        AvatarStore.observe(avatarChanged)
        showAvatarOnHome()
        PairingStatus.observe {
            conversation.refresh()
            showShortcutsOnHome()
            updateListening()
            pairingBanner.text = PairingStatus.message
            pairingBanner.visibility = if (PairingStatus.message == null) View.GONE else View.VISIBLE
        }
    }

    override fun onStop() {
        PairingStatus.observe(null)
        AvatarStore.remove(avatarChanged)
        // No listening once the kiosk is off screen: the call is hung up.
        TimerCenter.onChange = null
        hub.close()
        apps.close()
        feed.close()
        questions.cancel()
        photos.cancel()
        notices.clear()
        photoFrame.close()
        shown.close()
        phone.close()
        conversation.close()
        handler.removeCallbacks(openMenu)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        KioskReturn.inFront(this, true)
        web.onResume()
        resumed = true
        ScreenBridge.presenter = this
        updateListening()
        lastUse = SystemClock.elapsedRealtime()
        showShortcutsOnHome()
        timersChanged()
        ticker.run()
    }

    override fun onPause() {
        KioskReturn.inFront(this, false)
        resumed = false
        ScreenBridge.presenter = null
        handler.removeCallbacks(ticker)
        updateListening()
        web.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        web.destroy()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
            if (!conversation.isOpen) conversation.refresh()
            showShortcutsOnHome()
        }
        updateListening()
    }

    /**
     * "Hey Muse" only listens if the user enabled it and the kiosk is alone on screen: no menu, voice note or
     * call.
     */
    /** The sensitivity is only read when listening starts. */
    private fun restartListening() {
        listener?.stop()
        listener = null
        updateListening()
    }

    private fun updateListening() {
        if (!::wakeHint.isInitialized || !::phone.isInitialized) return
        val wanted = resumed && hasWindowFocus() && settings.wakeWord && !conversation.isOpen && !phone.isOpen &&
            !phone.holdsAudio && !(::photos.isInitialized && photos.isOpen) &&
            !PairingStatus.open && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
            SecretStore(this).pairing != null
        if (wanted && listener == null) {
            listener = WakeListener(settings.wakeSensitivity, ::woken, ::listeningFailed).also { it.start() }
        } else if (!wanted) {
            listener?.stop()
            listener = null
        }
        showListening()
    }

    /** The built-in page shows listening itself, under the avatar; other pages get a label. */
    private fun showListening() {
        val listening = listener != null
        val home = web.url == Settings.WELCOME_URL
        if (home) web.evaluateJavascript("window.setListening && window.setListening($listening)", null)
        wakeHint.visibility = if (listening && !home) View.VISIBLE else View.GONE
    }

    private fun panelsChanged() {
        if (!::notices.isInitialized) return
        shown.bottomRoom = conversation.isOpen || phone.isOpen
        apps.bottomRoom = conversation.isOpen || phone.isOpen
        lastUse = SystemClock.elapsedRealtime()
        refreshAmbience()
        pauseHomeAvatar()
    }

    /** A panel asks for attention: no photo frame and no dimming. */
    private fun busy() =
        phone.isOpen || conversation.isOpen || shown.isOpen || questions.isOpen || photos.isOpen || apps.isOpen ||
            feed.isOpen || hub.isOpen || alarm.isOpen || PairingStatus.open

    /** Applies night mode and opens or closes the photo frame, depending on the time and inactivity. */
    private fun refreshAmbience() {
        if (!::notices.isInitialized) return
        val now = SystemClock.elapsedRealtime()
        val night = settings.night.isNight(Clock.minuteOfDay(), System.currentTimeMillis())
        val wasDimmed = dimmer.dimmed
        val free = !busy() && !keyboardShown
        dimmer.dimmed = night && free && !notices.hasUrgent && (lastTouch == 0L || now - lastTouch >= NIGHT_WAKE_MS)
        holdNotices()
        val photos = if (!night && free && settings.screenAllowed) FrameStore.load(this) else null
        val wasOpen = photoFrame.isOpen
        if (photos == null) {
            photoFrame.close()
        } else if (wasOpen || (resumed && hasWindowFocus() && now - lastUse >= photos.idleMinutes * 60_000L)) {
            photoFrame.open(photos)
        }
        // In the dark there is no point decoding the home video.
        if (wasOpen != photoFrame.isOpen || wasDimmed != dimmer.dimmed) pauseHomeAvatar()
    }

    /** "More": the Feed, messages, photo and apps, depending on what is allowed. */
    private fun showMore() {
        val tiles = buildList {
            if (settings.screenAllowed) add(HubPanel.Tile(R.drawable.ic_feed, tr("Feed", "Fil")) { feed.open() })
            if (settings.historyShown) {
                add(HubPanel.Tile(R.drawable.ic_history, "Messages") { conversation.showHistory() })
            }
            if (settings.cameraAllowed) add(HubPanel.Tile(R.drawable.ic_camera, "Photo", ::look))
            if (settings.screenAllowed && AppStore.list(this@KioskActivity).isNotEmpty()) {
                add(HubPanel.Tile(R.drawable.ic_apps, tr("Apps", "Applications")) { apps.showList() })
            }
        }
        hub.open(tiles)
    }

    override fun showFeed() {
        // During a call, the feed waits to be opened: it would hide Muse.
        if (phone.isOpen) return
        photoFrame.close()
        conversation.close()
        feed.open()
    }

    override fun openApp(name: String): Boolean {
        photoFrame.close()
        apps.bottomRoom = conversation.isOpen || phone.isOpen
        return apps.open(name)
    }

    /** A timer started, ended or was cancelled. */
    private fun timersChanged() {
        if (!::notices.isInitialized) return
        if (TimerCenter.ringing.isNotEmpty()) photoFrame.close()
        alarm.refresh()
        showTimersOnHome()
    }

    private fun showTimersOnHome() {
        if (!::web.isInitialized || web.url != Settings.WELCOME_URL) return
        val timers = JSONArray(
            TimerCenter.running().map { JSONObject().put("id", it.id).put("label", it.label.orEmpty()).put("ends", it.endsAt) },
        )
        web.evaluateJavascript("window.setTimers && window.setTimers($timers)", null)
    }

    override fun showNotice(notice: Notice) {
        notices.show(notice)
        holdNotices()
    }

    private fun holdNotices() {
        notices.held = questions.isOpen || photos.isOpen || dimmer.dimmed
    }

    override fun askQuestion(question: Question, reply: (AskResult) -> Unit) = questions.ask(question, reply)

    override fun cancelQuestion() {
        questions.cancel()
        photos.cancel()
    }

    override fun askPhoto(reason: String?, reply: (PhotoResult) -> Unit): Boolean {
        photoFrame.close()
        return photos.ask(tr(
            "${settings.museName ?: "Muse"} would like to see",
            "${settings.museName ?: "Muse"} aimerait voir",
        ), reason, reply)
    }

    /** "Look at this", started by the user. */
    private fun look() {
        photos.ask(tr(
            "Show ${settings.museName ?: "Muse"}",
            "Montrer à ${settings.museName ?: "Muse"}",
        ), null) { result ->
            if (result is PhotoResult.Taken) conversation.sendPhoto(result.jpeg)
        }
    }

    override fun homeChanged(dimNow: Boolean) {
        if (dimNow) lastTouch = 0L
        conversation.refresh()
        showShortcutsOnHome()
        conversation.refresh()
        feed.refresh()
        refreshAmbience()
    }

    /** Asks for the feed again in the background: the written reply stays in the conversation. */
    private fun refreshFeed(done: () -> Unit): Boolean {
        val chat = MuseSession.chat(settings.chatSession()) ?: return false
        thread(name = "feed-refresh") {
            try {
                chat.sendText(FeedPanel.QUIET_REFRESH)
            } catch (e: IOException) {
                Log.w(TAG, "feed: request failed (${e.javaClass.simpleName})")
            } catch (e: RuntimeException) {
                Log.w(TAG, "feed: request failed (${e.javaClass.simpleName})")
            } finally {
                handler.post(done)
            }
        }
        return true
    }

    /** The quick action buttons, on the built-in home page and only once paired. */
    private fun showShortcutsOnHome() {
        if (!::web.isInitialized || web.url != Settings.WELCOME_URL) return
        val labels = if (SecretStore(this).pairing != null) settings.shortcuts.map { it.label } else emptyList()
        web.evaluateJavascript("window.setShortcuts && window.setShortcuts(${JSONArray(labels)})", null)
    }

    override fun showPictures(pictures: List<LoadedPicture>, title: String?, durationMillis: Long) {
        photoFrame.close()
        shown.bottomRoom = conversation.isOpen || phone.isOpen
        shown.showPictures(pictures, title, durationMillis)
    }

    override fun showPage(html: String, title: String?, durationMillis: Long) {
        photoFrame.close()
        shown.bottomRoom = conversation.isOpen || phone.isOpen
        shown.showPage(html, title, durationMillis)
    }

    override fun closeScreen() = shown.close()

    /**
     * Slips the avatar into the built-in home page, and nowhere else: a remote dashboard never receives it. The
     * page cannot read the app's files.
     */
    private fun showAvatarOnHome() {
        if (web.url != Settings.WELCOME_URL) return
        thread(name = "avatar-home") {
            val files = AvatarStore.current(this)
            val image = files?.image?.let { dataUrl(it, "image/webp") }
            val video = files?.idle?.takeIf { it.length() <= MAX_HOME_VIDEO_BYTES }?.let { dataUrl(it, "video/mp4") }
            web.post {
                if (web.url == Settings.WELCOME_URL) {
                    web.evaluateJavascript("window.setAvatar(${quote(image)}, ${quote(video)})", null)
                    pauseHomeAvatar()
                }
            }
        }
    }

    /** No point decoding the home video behind a panel. */
    private fun pauseHomeAvatar() {
        if (web.url != Settings.WELCOME_URL || !::shown.isInitialized) return
        val covered = conversation.isOpen || phone.isOpen || shown.isOpen || photoFrame.isOpen || dimmer.dimmed
        web.evaluateJavascript("window.pauseAvatar($covered)", null)
    }

    private fun dataUrl(file: File, type: String): String? = try {
        "data:$type;base64," + Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
    } catch (e: IOException) {
        null // the file has just been replaced: the next notification reloads it
    }

    /** A "data" address holds no quote or line break. */
    private fun quote(value: String?) = value?.let { "\"$it\"" } ?: "null"

    private fun woken(mic: WakeMic) {
        listener?.stop()
        listener = null
        Chime.heard()
        photoFrame.close()
        hub.close()
        apps.close()
        feed.close()
        phone.open(mic)
    }

    private fun listeningFailed(reason: String) {
        listener = null
        showListening()
        Toast.makeText(this, tr(
            "“Hey Muse” unavailable: $reason.",
            "« Hey Muse » indisponible : $reason.",
        ), Toast.LENGTH_LONG).show()
    }

    @Deprecated("Back navigates within the page and does not leave the kiosk.")
    override fun onBackPressed() {
        when {
            // A question is closed with its buttons.
            questions.isOpen -> Unit
            photos.isOpen -> photos.cancel()
            apps.isOpen -> apps.close()
            feed.isOpen -> feed.close()
            hub.isOpen -> hub.close()
            photoFrame.isOpen -> photoFrame.close()
            shown.isOpen -> shown.close()
            phone.isOpen -> phone.close()
            conversation.isOpen -> conversation.close()
            web.canGoBack() -> web.goBack()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        conversation.onPermissionResult(requestCode, granted)
        conversation.refresh()
        phone.onPermissionResult(requestCode, granted)
    }

    /** Watches for the long press without consuming the touch: the page stays usable. */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val density = resources.displayMetrics.density
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // The touch that wakes the screen or leaves the frame touches nothing underneath. A question or
                // an urgent banner already lights the screen.
                swallowGesture = photoFrame.isOpen || dimmer.dimmed
                lastTouch = SystemClock.elapsedRealtime()
                lastUse = lastTouch
                if (::apps.isInitialized) apps.touched()
                if (::feed.isInitialized) feed.touched()
                if (swallowGesture) {
                    photoFrame.close()
                    dimmer.dimmed = false
                    holdNotices()
                    pauseHomeAvatar()
                }
                touchStartX = event.x
                touchStartY = event.y
                val corner = CORNER_DP * density
                if (event.x <= corner && event.y >= root.height - corner) handler.postDelayed(openMenu, HOLD_MS)
            }
            MotionEvent.ACTION_MOVE ->
                if (abs(event.x - touchStartX) > 20 * density || abs(event.y - touchStartY) > 20 * density) {
                    handler.removeCallbacks(openMenu)
                }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN ->
                handler.removeCallbacks(openMenu)
        }
        return swallowGesture || super.dispatchTouchEvent(event)
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    private fun banner(position: Int) = TextView(this).apply {
        setTextColor(Color.WHITE)
        setBackgroundColor(0xE61C56A0.toInt())
        textSize = 18f
        gravity = Gravity.CENTER
        val pad = (16 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad, pad, pad)
        visibility = View.GONE
        layoutParams = FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, position).apply {
            // The top of the Portal's screen is taken by the system overlay.
            topMargin = 4 * pad
            bottomMargin = pad
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView() = WebView(this).apply {
        setBackgroundColor(Color.BLACK)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        // A long press does not select text: selecting cancelled the opening of the menu. Input fields keep it
        // (to paste), and the page's own long presses stay active.
        isHapticFeedbackEnabled = false
        setOnLongClickListener { hitTestResult.type != WebView.HitTestResult.EDIT_TEXT_TYPE }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.scheme == TIMER_SCHEME) {
                    if (view.url == Settings.WELCOME_URL) {
                        request.url.schemeSpecificPart.toIntOrNull()?.let { TimerCenter.cancel(it) }
                    }
                    return true
                }
                if (request.url.scheme != SHORTCUT_SCHEME) return request.url.scheme !in listOf("http", "https")
                // Only the built-in page can send a message in the user's name.
                if (view.url == Settings.WELCOME_URL) {
                    val index = request.url.schemeSpecificPart.toIntOrNull()
                    this@KioskActivity.settings.shortcuts.getOrNull(index ?: -1)?.let { conversation.sendShortcut(it.message) }
                }
                return true
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!request.isForMainFrame) return
                Log.w(TAG, "page unreachable: ${error.errorCode} ${error.description}")
                showLoadError(tr(
                    "Page unreachable (${error.description}). Trying again in 15 s.",
                    "Page injoignable (${error.description}). Nouvel essai dans 15 s.",
                ))
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (!loadFailed) errorBanner.visibility = View.GONE
                showAvatarOnHome()
                showShortcutsOnHome()
                showListening()
                showTimersOnHome()
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                Log.w(TAG, "renderer stopped, recreating the screen")
                recreate()
                return true
            }
        }
    }

    private fun showLoadError(text: String) {
        loadFailed = true
        errorBanner.text = text
        errorBanner.visibility = View.VISIBLE
        handler.removeCallbacks(retry)
        handler.postDelayed(retry, RETRY_MS)
    }

    private fun loadDashboard() {
        handler.removeCallbacks(retry)
        loadFailed = false
        web.loadUrl(settings.dashboardUrl)
    }
}
