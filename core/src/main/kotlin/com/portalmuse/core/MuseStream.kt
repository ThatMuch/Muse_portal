// Portage Kotlin de muse-gadget-sdk (Copyright (c) Meta Platforms, Inc. and affiliates, Apache-2.0).
// Source : linux/src/musegadget/link_client.py (_Request, HttpStream)
package com.portalmuse.core

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger


/** Requêtes HTTP portées par une session Muse enregistrée. */
interface MuseStreams {
    @Throws(IOException::class)
    fun open(service: ServiceType, verb: String, path: String, headers: List<Pair<String, String>>, body: ByteArray): MuseStream
}

/**
 * La réponse à une requête : statut puis corps par morceaux. La session l'alimente sans jamais
 * se bloquer ; [close] annule la requête si elle n'est pas terminée.
 */
class MuseStream internal constructor(
    val id: Long, private val maxBufferedBytes: Int, private val cancel: (MuseStream) -> Unit,
) {
    private sealed class Item {
        class Head(val status: Int) : Item()
        class Data(val bytes: ByteArray) : Item()
        object End : Item()
        class Broken(val reason: String) : Item()
    }

    private val items = LinkedBlockingQueue<Item>()
    private val buffered = AtomicInteger()
    private var headSeen = false

    @Volatile var ended = false
        private set

    /** Les en-têtes de la réponse, noms en minuscules ; disponibles après [awaitStatus]. */
    @Volatile var headers: Map<String, String> = emptyMap()
        private set

    /** Appelé par la boucle de réception seulement. */
    internal fun receive(frame: ServiceFrame) {
        if (ended) return
        val (data, last) = when (frame) {
            is ServiceFrame.Response -> {
                if (headSeen || frame.status !in 100..599) return fail("en-tête de réponse invalide")
                headSeen = true
                headers = frame.headers.associate { it.first.lowercase() to it.second }
                items.add(Item.Head(frame.status))
                frame.body to frame.endBody
            }
            is ServiceFrame.BodyChunk -> {
                if (!headSeen) return fail("corps reçu avant l'en-tête")
                frame.data to frame.endBody
            }
            is ServiceFrame.Reset -> {
                ended = true
                items.add(Item.Broken("flux réinitialisé (code ${frame.code})"))
                return
            }
            is ServiceFrame.Request -> return fail("requête inattendue")
        }
        if (data.isNotEmpty()) {
            if (buffered.addAndGet(data.size) > maxBufferedBytes) return fail("réponse non consommée")
            items.add(Item.Data(data))
        }
        if (last) {
            ended = true
            items.add(Item.End)
        }
    }

    internal fun fail(reason: String) {
        items.add(Item.Broken(reason))
    }

    @Throws(IOException::class)
    fun awaitStatus(timeoutMillis: Long): Int {
        val item = items.poll(timeoutMillis, TimeUnit.MILLISECONDS) ?: throw SocketTimeoutException("aucune réponse")
        return when (item) {
            is Item.Head -> item.status
            is Item.Broken -> throw IOException(item.reason)
            else -> throw IOException("réponse sans en-tête")
        }
    }

    /** Le morceau suivant, vide si rien n'est arrivé à temps, `null` à la fin du corps. */
    @Throws(IOException::class)
    fun read(timeoutMillis: Long): ByteArray? =
        when (val item = items.poll(timeoutMillis, TimeUnit.MILLISECONDS)) {
            null -> ByteArray(0)
            is Item.Data -> item.bytes.also { buffered.addAndGet(-it.size) }
            Item.End -> null.also { items.add(Item.End) }
            is Item.Broken -> throw IOException(item.reason)
            is Item.Head -> throw IOException("second en-tête")
        }

    fun close() = cancel(this)

    companion object {
        const val DEFAULT_BUFFER_BYTES = 1024 * 1024
    }
}
