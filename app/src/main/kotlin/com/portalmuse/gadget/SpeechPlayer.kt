package com.portalmuse.gadget

import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.portalmuse.core.MuseChat
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

private const val TAG = "PortalMuse"
/** Enough MP3 to start playing while the rest arrives. */
private const val START_BYTES = 24 * 1024
private const val MAX_PLAY_MS = 5 * 60_000L
private const val END = ""

/**
 * Reads the messages of a reply aloud, one after the other, as they are announced. Methods are called from the
 * main thread.
 */
class SpeechPlayer {
    /** [status]: the HTTP status of a refusal, `null` if everything was read or on a cut. */
    class Outcome(val played: Boolean, val status: Int?)

    /** One playback: the queue of messages, `null` meaning no more will come. */
    private class Reading {
        val stopped = AtomicBoolean()
        val queue = LinkedBlockingQueue<String>()
        val known = HashSet<String>()
        @Volatile var onDone: ((Outcome) -> Unit)? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private var current: Reading? = null

    /** Adds a message; no effect if it is already queued. */
    fun add(chat: MuseChat, messageId: String) {
        val reading = current ?: Reading().also { created ->
            current = created
            thread(name = "reply-voice") { read(chat, created) }
        }
        if (reading.known.add(messageId)) reading.queue.put(messageId)
    }

    /** Nothing more to add: [onDone] is called after the last message is read. */
    fun finish(onDone: (Outcome) -> Unit) {
        val reading = current ?: return onDone(Outcome(false, null))
        reading.onDone = onDone
        reading.queue.put(END)
    }

    fun stop() {
        current?.let {
            it.stopped.set(true)
            it.queue.put(END)
        }
        current = null
    }

    private fun read(chat: MuseChat, reading: Reading) {
        var outcome = Outcome(false, null)
        var failed = false
        while (!reading.stopped.get()) {
            val id = reading.queue.take()
            if (id == END) break
            if (failed) continue
            outcome = try {
                speak(chat, id, reading.stopped)
            } catch (e: RuntimeException) {
                Log.w(TAG, "voice: playback failed (${e.javaClass.simpleName})")
                Outcome(false, null)
            }
            failed = !outcome.played
        }
        main.post {
            if (current === reading && !reading.stopped.get()) {
                current = null
                reading.onDone?.invoke(outcome)
            }
        }
    }

    /** Blocks until the end of playback or until stopped. */
    private fun speak(chat: MuseChat, id: String, stopped: AtomicBoolean): Outcome {
        val audio = GrowingAudio()
        var status: Int? = null
        val download = thread(name = "reply-voice-download") {
            status = chat.speech(id, audio::append) { stopped.get() }
            audio.finish()
        }
        try {
            audio.awaitBytes(START_BYTES, stopped)
            if (stopped.get()) return Outcome(false, null)
            if (audio.length == 0) {
                download.join(1_000)
                Log.i(TAG, "voice: no sound (HTTP $status)")
                return Outcome(false, status?.takeIf { it !in 200..299 })
            }
            var played = playThrough(audio, stopped)
            if (!played && !stopped.get()) {
                // Playing on the fly failed: try again once everything has arrived.
                download.join(MAX_PLAY_MS)
                played = playThrough(audio, stopped)
            }
            Log.i(TAG, "voice: ${audio.length / 1024} KB, ${if (played) "played" else "not played"}")
            return Outcome(played, null)
        } finally {
            audio.dismiss()
        }
    }

    private fun playThrough(audio: GrowingAudio, stopped: AtomicBoolean): Boolean {
        val player = MediaPlayer()
        val ended = CountDownLatch(1)
        val completed = AtomicBoolean()
        return try {
            player.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            player.setOnCompletionListener {
                completed.set(true)
                ended.countDown()
            }
            player.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "voice: player failed ($what, $extra)")
                ended.countDown()
                true
            }
            player.setDataSource(audio)
            player.prepare()
            player.start()
            Log.i(TAG, "voice: playback started")
            val deadline = System.currentTimeMillis() + MAX_PLAY_MS
            while (!stopped.get() && System.currentTimeMillis() < deadline) {
                if (ended.await(100, TimeUnit.MILLISECONDS)) break
            }
            if (stopped.get()) Log.i(TAG, "voice: interrupted after ${player.currentPosition} ms")
            completed.get()
        } catch (e: IOException) {
            false
        } catch (e: IllegalStateException) {
            false
        } finally {
            player.release()
        }
    }

    /** The MP3 received so far: the player waits for more as long as it may come. */
    private class GrowingAudio : MediaDataSource() {
        private val lock = Object()
        private var bytes = ByteArray(64 * 1024)
        @Volatile var length = 0
            private set
        private var finished = false
        private var dismissed = false

        fun append(chunk: ByteArray) = synchronized(lock) {
            if (length + chunk.size > bytes.size) bytes = bytes.copyOf(maxOf(bytes.size * 2, length + chunk.size))
            chunk.copyInto(bytes, length)
            length += chunk.size
            lock.notifyAll()
        }

        fun finish() = synchronized(lock) {
            finished = true
            lock.notifyAll()
        }

        fun awaitBytes(count: Int, stopped: AtomicBoolean) = synchronized(lock) {
            while (length < count && !finished && !stopped.get()) lock.wait(100)
        }

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int = synchronized(lock) {
            while (position >= length && !finished && !dismissed) lock.wait(100)
            if (dismissed || position >= length) return -1
            val count = minOf(size.toLong(), length - position).toInt()
            bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
            count
        }

        override fun getSize(): Long = synchronized(lock) { if (finished) length.toLong() else -1L }

        /** Called by each player being released: the source must be reusable. */
        override fun close() = Unit

        fun dismiss() = synchronized(lock) {
            dismissed = true
            lock.notifyAll()
        }
    }
}
