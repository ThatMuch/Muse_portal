package com.portalmuse.gadget

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.portalmuse.core.Avatar
import com.portalmuse.core.AvatarClient
import com.portalmuse.core.AvatarIdentity
import com.portalmuse.core.MuseStreams
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "PortalMuse"
private const val MAX_IMAGE_BYTES = 6 * 1024 * 1024
private const val MAX_VIDEO_BYTES = 30 * 1024 * 1024
private const val IMAGE = "image"

/** Les états du firmware qui servent ici : au repos et au travail. */
private val CLIPS = listOf("idle", "working")

/** Les fichiers de l'avatar disponibles sur le Portal. */
class AvatarFiles(val image: File, val idle: File?, val working: File?)

/**
 * L'avatar du Muse, gardé dans le stockage privé de l'app et rafraîchi quand sa version change.
 * En cas d'échec, la dernière version complète reste affichée.
 */
object AvatarStore {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val syncing = AtomicBoolean()
    /** Change à chaque effacement : un téléchargement commencé avant ne doit rien publier. */
    private val generation = AtomicInteger()

    fun observe(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun remove(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun directory(context: Context) = File(context.applicationContext.filesDir, "avatar")

    @Synchronized
    fun current(context: Context): AvatarFiles? {
        val directory = directory(context)
        val image = File(directory, IMAGE)
        if (!File(directory, "meta.json").exists() || !image.exists()) return null
        fun clip(name: String) = File(directory, "$name.mp4").takeIf { it.exists() }
        return AvatarFiles(image, clip("idle"), clip("working"))
    }

    /** L'appairage disparaît : son avatar aussi. */
    @Synchronized
    fun clear(context: Context) {
        generation.incrementAndGet()
        directory(context).deleteRecursively()
        notifyChanged()
    }

    /** Bloquant : à appeler hors du fil principal. */
    fun sync(context: Context, streams: MuseStreams) {
        if (!syncing.compareAndSet(false, true)) return
        try {
            update(context.applicationContext, AvatarClient(streams))
        } catch (e: RuntimeException) {
            Log.w(TAG, "avatar : synchronisation en échec (${e.javaClass.simpleName})")
        } finally {
            syncing.set(false)
        }
    }

    private fun update(context: Context, client: AvatarClient) {
        val log: (String) -> Unit = { Log.i(TAG, "avatar : $it") }
        val (identity, shape) = client.identity(log) ?: return
        // Les écrans de l'app sont sombres.
        val avatar = (identity as? AvatarIdentity.Present)?.avatar?.forDarkScreen()
        if (avatar == null) {
            // Une image absente peut être en cours de création : on garde l'ancienne.
            log("aucun avatar utilisable ($shape)")
            return
        }
        val directory = directory(context)
        val version = describe(avatar)
        val meta = File(directory, "meta.json")
        if (meta.exists() && meta.readText() == version && File(directory, IMAGE).exists()) return
        log("nouvelle version, $shape")
        val started = generation.get()
        val work = File(context.cacheDir, "avatar-download").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            val image = fetch(client, avatar.imagePath, File(work, IMAGE), MAX_IMAGE_BYTES, log) ?: return
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(image.path, bounds)
            if (bounds.outWidth <= 0) return log("image illisible")
            log("image ${bounds.outWidth} x ${bounds.outHeight}, ${image.length() / 1024} Ko")
            val clips = CLIPS.mapNotNull { name ->
                val path = avatar.videos[name] ?: return@mapNotNull null
                val file = fetch(client, path, File(work, "$name.mp4"), MAX_VIDEO_BYTES, log)
                if (file == null || !isMp4(file)) null.also { log("vidéo $name non récupérée") }
                else file.also { log("vidéo $name, ${it.length() / 1024} Ko") }
            }
            synchronized(this) {
                if (generation.get() != started) return
                directory.deleteRecursively()
                directory.mkdirs()
                for (file in listOf(image) + clips) file.renameTo(File(directory, file.name))
                // La version n'est notée que si tout ce qui était annoncé est là : sinon on réessaiera.
                val complete = clips.size == CLIPS.count { it in avatar.videos }
                File(directory, "meta.json").writeText(if (complete) version else "incomplet")
            }
            notifyChanged()
            log("prêt")
        } finally {
            work.deleteRecursively()
        }
    }

    private fun fetch(client: AvatarClient, path: String, into: File, max: Int, log: (String) -> Unit): File? {
        val ok = into.outputStream().use { client.download(path, max, it, log) }
        return into.takeIf { ok && it.length() > 0 }
    }

    private fun describe(avatar: Avatar) = JSONObject()
        .put("revision", avatar.revision)
        .put("image", avatar.imagePath)
        .put("videos", CLIPS.joinToString("|") { avatar.videos[it].orEmpty() })
        .toString()

    /** Un fichier MP4 commence par une boîte « ftyp ». */
    private fun isMp4(file: File): Boolean {
        val head = ByteArray(12)
        val read = file.inputStream().use { it.read(head) }
        return read == head.size && String(head, 4, 4) == "ftyp"
    }

    private fun notifyChanged() {
        main.post { listeners.forEach { it() } }
    }
}
