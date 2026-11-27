package com.portalmuse.gadget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
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
import kotlin.math.roundToInt

private const val TAG = "PortalMuse"
private const val MAX_FILE_BYTES = 12 * 1024 * 1024
private const val MAX_SIDE = 1600
private const val MAX_PIXELS = 60_000_000L
private const val JPEG_QUALITY = 88
private const val ANSWER_GRACE_MS = 10_000L
/** Au-delà du délai du panneau, qui repart à chaque geste. */
private const val PHOTO_WAIT_MS = 190_000L

/** Ce que le kiosque visible sait afficher ; appelé sur le fil principal. */
interface ScreenPresenter {
    fun showPictures(pictures: List<LoadedPicture>, title: String?, durationMillis: Long)
    fun showPage(html: String, title: String?, durationMillis: Long)
    fun closeScreen()
    fun showNotice(notice: Notice)

    /** Faux si une question attend déjà. [reply] est appelé une seule fois. */
    fun askQuestion(question: Question, reply: (AskResult) -> Unit): Boolean
    fun cancelQuestion()

    fun showFeed()

    /** Faux si l'application n'existe pas. */
    fun openApp(name: String): Boolean

    /** Faux si une photo est déjà en cours. */
    fun askPhoto(reason: String?, reply: (PhotoResult) -> Unit): Boolean

    /** Raccourcis, mode nuit ou cadre photo ont changé. [dimNow] : la nuit vient d'être demandée. */
    fun homeChanged(dimNow: Boolean)
}

/** Relie les commandes reçues de Muse au kiosque, quand il est à l'écran, et aux réglages. */
class ScreenBridge(context: Context) : ScreenHost, AssistantHost, CameraHost, HomeHost, AppHost, FeedHost {
    private val context = context.applicationContext
    private val settings = Settings(this.context)
    private val log: (String) -> Unit = { Log.i(TAG, "écran : $it") }

    override fun isAvailable() = presenter != null

    override fun load(source: PictureSource): Any? = bitmap(source)

    /** Une photo du cadre reste compressée : trente images décodées ne tiendraient pas en mémoire. */
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
        return decode(bytes) ?: null.also { log("fichier reçu illisible comme image") }
    }

    /** Réduit les grandes images à la taille de l'écran avant de les charger en mémoire. */
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
        log("${pictures.size} image(s)")
        it.showPictures(pictures, title, durationMillis)
        true
    }

    override fun showPage(html: String, title: String?, durationMillis: Long) = onScreen {
        log("une page de ${html.length} caractères")
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
        // Jamais le texte du bandeau ni de la question dans les journaux.
        log("bandeau${if (notice.urgent) " urgent" else ""}")
        it.showNotice(notice)
        true
    }

    override fun ask(question: Question): AskResult {
        val answers = LinkedBlockingQueue<AskResult>(1)
        if (!onScreen { it.askQuestion(question) { answer -> answers.offer(answer) } }) return AskResult.BUSY
        log("question à l'écran")
        val answer = answers.poll(question.timeoutMillis + ANSWER_GRACE_MS, TimeUnit.MILLISECONDS) ?: AskResult.NO_ANSWER
        log("question : $answer")
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
        log("cadre photo : $saved image(s)")
        changed()
        return saved
    }

    override fun clearFrame() {
        FrameStore.clear(context)
        changed()
    }

    override fun setFeed(items: List<FeedItem>, open: Boolean): Boolean {
        if (items.isEmpty()) FeedStore.clear(context) else FeedStore.save(context, items)
        log("fil : ${items.size} élément(s)")
        changed()
        return open && onScreen {
            it.showFeed()
            true
        }
    }

    override fun startTimer(durationMillis: Long, label: String?) =
        TimerCenter.start(durationMillis, label)?.let(TimerCenter::describe)

    override fun timers() = TimerCenter.running().map(TimerCenter::describe)

    override fun cancelTimers(id: Int?) = TimerCenter.cancel(id)

    private val audio get() = context.getSystemService(AudioManager::class.java)

    override fun volume(): Int {
        val maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return (audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / maximum).roundToInt()
    }

    override fun setVolume(percent: Int): Int {
        val maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (percent * maximum / 100f).roundToInt(), 0)
        return volume()
    }

    override fun save(app: AppInfo, html: String) = AppStore.save(context, app, html).also { if (it) changed() }

    override fun list() = AppStore.list(context)

    override fun open(name: String) = onScreen { it.openApp(name) }

    override fun remove(name: String) = AppStore.remove(context, name).also { if (it) changed() }

    override fun photo(reason: String?): PhotoResult {
        val answers = LinkedBlockingQueue<PhotoResult>(1)
        if (!onScreen { it.askPhoto(reason) { answer -> answers.offer(answer) } }) {
            return PhotoResult.Unavailable("the screen is busy or not in front")
        }
        log("demande de photo à l'écran")
        val answer = answers.poll(PHOTO_WAIT_MS, TimeUnit.MILLISECONDS) ?: PhotoResult.Declined
        log("demande de photo : ${answer.javaClass.simpleName}")
        return answer
    }

    private fun changed(dimNow: Boolean = false) {
        main.post { presenter?.homeChanged(dimNow) }
    }

    /** Exécute sur le fil principal et attend ; faux si le kiosque n'est plus là ou a refusé. */
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

        /** La session est perdue : une réponse ne partirait plus, la question disparaît. */
        fun cancelQuestion() {
            main.post { presenter?.cancelQuestion() }
        }
    }
}
