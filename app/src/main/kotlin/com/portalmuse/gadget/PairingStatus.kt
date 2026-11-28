package com.portalmuse.gadget

import android.os.Handler
import android.os.Looper

/** Pairing state, shared between the service and the screen. `null`: pairing closed. */
object PairingStatus {
    private val main = Handler(Looper.getMainLooper())

    var message: String? = null
        private set
    var open = false
        private set
    private var listener: (() -> Unit)? = null
    private val dismiss = Runnable { set(false, null) }

    fun observe(onChange: (() -> Unit)?) {
        listener = onChange
        onChange?.invoke()
    }

    fun publish(isOpen: Boolean, text: String?) {
        main.post {
            main.removeCallbacks(dismiss)
            set(isOpen, text)
            // A closing message does not stay on screen.
            if (!isOpen && text != null) main.postDelayed(dismiss, 15_000)
        }
    }

    private fun set(isOpen: Boolean, text: String?) {
        open = isOpen
        message = text
        listener?.invoke()
    }
}
