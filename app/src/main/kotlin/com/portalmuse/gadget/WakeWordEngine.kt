package com.portalmuse.gadget

/**
 * Le détecteur « Hey Muse » du SDK, exécuté sur le Portal. Aucun son ne quitte l'appareil.
 * Une instance ne sert qu'un fil.
 */
class WakeWordEngine private constructor(private var handle: Long) : AutoCloseable {
    /** [count] échantillons PCM 16 bits à 16 kHz ; vrai si le mot vient d'être entendu. */
    fun feed(samples: ShortArray, count: Int): Boolean = nativeFeed(handle, samples, count)

    /** Faux après un échec d'inférence. */
    val healthy get() = nativeOk(handle)

    override fun close() {
        if (handle != 0L) nativeDestroy(handle)
        handle = 0
    }

    private external fun nativeFeed(handle: Long, samples: ShortArray, count: Int): Boolean
    private external fun nativeOk(handle: Long): Boolean
    private external fun nativeDestroy(handle: Long)

    companion object {
        /** [sensitivity] : un des réglages du SDK, 0 (basse), 1 (normale) ou 2 (haute). */
        fun create(sensitivity: Int): WakeWordEngine? = try {
            System.loadLibrary("muse_wakeword")
            val engine = WakeWordEngine(0)
            val handle = engine.nativeCreate(sensitivity)
            if (handle == 0L) null else engine.also { it.handle = handle }
        } catch (e: UnsatisfiedLinkError) {
            null
        }
    }

    private external fun nativeCreate(sensitivity: Int): Long
}
