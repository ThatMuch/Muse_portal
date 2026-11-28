package com.portalmuse.gadget

import com.portalmuse.core.Station
import com.portalmuse.core.RadioHost
import com.portalmuse.core.HandoffHost
import com.portalmuse.core.CardHost
import com.portalmuse.core.Card
import com.portalmuse.core.AlarmHost
import com.portalmuse.core.Alarm
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.portalmuse.core.AppHost
import com.portalmuse.core.AppInfo
import com.portalmuse.core.AskResult
import com.portalmuse.core.AssistantHost
import com.portalmuse.core.AvatarClient
import com.portalmuse.core.CameraHost
import com.portalmuse.core.FeedHost
import com.portalmuse.core.FeedItem
import com.portalmuse.core.HomeHost
import com.portalmuse.core.LoadedPicture
import com.portalmuse.core.NightSettings
import com.portalmuse.core.Notice
import com.portalmuse.core.PhotoResult
import com.portalmuse.core.PictureSource
import com.portalmuse.core.Question
import com.portalmuse.core.ScreenHost
import com.portalmuse.core.Shortcut
import com.portalmuse.core.WebImages
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "PortalMuse"
private const val MAX_FILE_BYTES = 12 * 1024 * 1024
private const val MAX_SIDE = 1600
private const val MAX_PIXELS = 60_000_000L
private const val JPEG_QUALITY = 88
private const val ANSWER_GRACE_MS = 10_000L
/** Beyond the panel's delay, which restarts at every gesture. */
private const val PHOTO_WAIT_MS = 190_000L

/** What the visible kiosk can show; called on the main thread. */
interface ScreenPresenter {
    fun showPictures(pictures: List<LoadedPicture>, title: String?, durationMillis: Long)
    fun showPage(html: String, title: String?, durationMillis: Long)
    fun closeScreen()
    fun showNotice(notice: Notice)

    /** False if a question is already waiting. [reply] is called exactly once. */
    fun askQuestion(question: Question, reply: (AskResult) -> Unit): Boolean
    fun cancelQuestion()

    fun showFeed()

    fun openUrl(url: String, title: String?)

    /** False if the app does not exist. */
    fun openApp(name: String): Boolean

    /** False if a photo is already in progress. */
    fun askPhoto(reason: String?, reply: (PhotoResult) -> Unit): Boolean

    /** Shortcuts, night mode or photo frame changed. [dimNow]: night was just requested. */
    fun homeChanged(dimNow: Boolean)
}

/** Connects the commands received from Muse to the kiosk, when it is on screen, and to the settings. */
class ScreenBridge(context: Context) :
    ScreenHost, AssistantHost, CameraHost, HomeHost, AppHost, FeedHost, CardHost, HandoffHost, AlarmHost, RadioHost {
    private val context = context.applicationContext
    private val settings = Settings(this.context)
    private val log: (String) -> Unit = { Log.i(TAG, "screen: $it") }

    override fun isAvailable() = presenter != null

    override fun load(source: PictureSource): Any? = bitmap(source)

    /** A frame photo stays compressed: thirty decoded pictures would not fit in memory. */
    override fun loadPhoto(source: PictureSource): Any? = bitmap(source)?.let { image ->
        val out = ByteArrayOutputStream()
        image.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        image.recycle()
        out.toByteArray()
    }

    private fun bitmap(source: PictureSource): Bitmap? {
        val bytes = when (source) {
            is PictureSource.Web -> WebImages(MuseHttp.client, MuseHttp.userAgent(context)).fetch(source.url, log)
            is PictureSource.File -> MuseSession.live()?.let { session ->
                val out = ByteArrayOutputStream()
                if (AvatarClient(session).download(source.path, MAX_FILE_BYTES, out, log)) out.toByteArray() else null
            }
        } ?: return null
        return decode(bytes) ?: null.also { log("received file unreadable as a picture") }
    }

    /** Scales large pictures down to the screen size before loading them into memory. */
    private fun decode(bytes: ByteArray): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        val width = options.outWidth
        val height = options.outHeight
        if (width <= 0 || height <= 0 || width.toLong() * height > MAX_PIXELS) return null
        var sample = 1
        while (maxOf(width, height) / sample > MAX_SIDE) sample *= 2
        options.inJustDecodeBounds = false
        options.inSampleSize = sample
        return try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    override fun showPictures(pictures: List<LoadedPicture>, title: String?, durationMillis: Long) = onScreen {
        log("${pictures.size} picture(s)")
        it.showPictures(pictures, title, durationMillis)
        true
    }

    override fun showPage(html: String, title: String?, durationMillis: Long) = onScreen {
        log("a page of ${html.length} characters")
        it.showPage(html, title, durationMillis)
        true
    }

    override fun close() {
        onScreen {
            it.closeScreen()
            true
        }
    }

    override fun notify(notice: Notice) = onScreen {
        // Never the text of the banner or question in the logs.
        log("banner${if (notice.urgent) " urgent" else ""}")
        it.showNotice(notice)
        true
    }

    override fun ask(question: Question): AskResult {
        val answers = LinkedBlockingQueue<AskResult>(1)
        if (!onScreen { it.askQuestion(question) { answer -> answers.offer(answer) } }) return AskResult.BUSY
        log("question on screen")
        val answer = answers.poll(question.timeoutMillis + ANSWER_GRACE_MS, TimeUnit.MILLISECONDS) ?: AskResult.NO_ANSWER
        log("question: $answer")
        return answer
    }

    override fun setShortcuts(shortcuts: List<Shortcut>) {
        settings.customShortcuts = shortcuts
        changed()
    }

    override fun night(change: (NightSettings) -> NightSettings): Pair<NightSettings, Boolean> {
        val before = settings.night
        val after = settings.changeNight(change)
        val night = after.isNight(Clock.minuteOfDay(), System.currentTimeMillis())
        if (after != before) changed(dimNow = night)
        return after to night
    }

    override fun setFrame(pictures: List<LoadedPicture>, idleMinutes: Int): Int {
        val saved = FrameStore.save(context, pictures.map { (it.image as ByteArray) to it.caption }, idleMinutes)
        log("photo frame: $saved picture(s)")
        changed()
        return saved
    }

    override fun clearFrame() {
        FrameStore.clear(context)
        changed()
    }

    override fun setFeed(items: List<FeedItem>, open: Boolean): Boolean {
        if (items.isEmpty()) FeedStore.clear(context) else FeedStore.save(context, items)
        log("feed: ${items.size} item(s)")
        changed()
        return open && onScreen {
            it.showFeed()
            true
        }
    }

    override fun setCards(cards: List<Card>) {
        CardStore.set(context, cards)
        log("cards: ${cards.size}")
        changed()
    }

    override fun openUrl(url: String, title: String?) = onScreen {
        log("a web page")
        it.openUrl(url, title)
        true
    }

    override fun addAlarm(alarm: Alarm) = AlarmCenter.add(alarm)

    override fun alarms() = AlarmCenter.alarms()

    override fun cancelAlarms(id: Int?) = AlarmCenter.cancel(id)

    override fun playRadio(station: Station) {
        log("radio: ${station.id}")
        RadioCenter.play(context, station)
    }

    override fun stopRadio() = RadioCenter.stop()

    override fun radio() = RadioCenter.station

    override fun startTimer(durationMillis: Long, label: String?) =
        TimerCenter.start(durationMillis, label)?.let(TimerCenter::describe)

    override fun timers() = TimerCenter.running().map(TimerCenter::describe)

    override fun cancelTimers(id: Int?) = TimerCenter.cancel(id)

    override fun volume() = Loudness.percent(context)

    override fun setVolume(percent: Int) = Loudness.set(context, percent)

    override fun save(app: AppInfo, html: String) = AppStore.save(context, app, html).also { if (it) changed() }

    override fun list() = AppStore.list(context)

    override fun open(name: String) = onScreen { it.openApp(name) }

    override fun remove(name: String) = AppStore.remove(context, name).also { if (it) changed() }

    override fun photo(reason: String?): PhotoResult {
        val answers = LinkedBlockingQueue<PhotoResult>(1)
        if (!onScreen { it.askPhoto(reason) { answer -> answers.offer(answer) } }) {
            return PhotoResult.Unavailable("the screen is busy or not in front")
        }
        log("photo request on screen")
        val answer = answers.poll(PHOTO_WAIT_MS, TimeUnit.MILLISECONDS) ?: PhotoResult.Declined
        log("photo request: ${answer.javaClass.simpleName}")
        return answer
    }

    private fun changed(dimNow: Boolean = false) {
        main.post { presenter?.homeChanged(dimNow) }
    }

    /** Runs on the main thread and waits; false if the kiosk is gone or refused. */
    private fun onScreen(action: (ScreenPresenter) -> Boolean): Boolean {
        val done = CountDownLatch(1)
        val shown = AtomicBoolean()
        main.post {
            presenter?.let { shown.set(action(it)) }
            done.countDown()
        }
        return done.await(5, TimeUnit.SECONDS) && shown.get()
    }

    companion object {
        private val main = Handler(Looper.getMainLooper())

        @Volatile
        var presenter: ScreenPresenter? = null

        /** The session is lost: an answer would no longer be sent, so the question disappears. */
        fun cancelQuestion() {
            main.post { presenter?.cancelQuestion() }
        }
    }
}
