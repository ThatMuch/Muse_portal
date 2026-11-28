package com.portalmuse.gadget

/**
 * The SDK's "Hey Muse" detector, run on the Portal. No sound leaves the device. One instance serves a single
 * thread.
 */
class WakeWordEngine private constructor(private var handle: Long) : AutoCloseable {
    /** [count] 16 bit PCM samples at 16 kHz; true if the word has just been heard. */
    fun feed(samples: ShortArray, count: Int): Boolean = nativeFeed(handle, samples, count)

    /** False after an inference failure. */
    val healthy get() = nativeOk(handle)

    override fun close() {
        if (handle != 0L) nativeDestroy(handle)
        handle = 0
    }

    private external fun nativeFeed(handle: Long, samples: ShortArray, count: Int): Boolean
    private external fun nativeOk(handle: Long): Boolean
    private external fun nativeDestroy(handle: Long)

    companion object {
        /** [sensitivity]: one of the SDK's settings, 0 (low), 1 (normal) or 2 (high). */
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
