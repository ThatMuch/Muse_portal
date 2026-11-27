package com.portalmuse.gadget

import android.os.Handler
import android.os.Looper

/** État de l'appairage, partagé entre le service et l'écran. `null` : appairage fermé. */
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
            // Un message de fermeture ne reste pas à l'écran.
            if (!isOpen && text != null) main.postDelayed(dismiss, 15_000)
        }
    }

    private fun set(isOpen: Boolean, text: String?) {
        open = isOpen
        message = text
        listener?.invoke()
    }
}
