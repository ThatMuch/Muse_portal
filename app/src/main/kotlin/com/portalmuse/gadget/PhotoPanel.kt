package com.portalmuse.gadget

import com.portalmuse.core.tr
import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import com.portalmuse.core.PhotoResult
import java.io.ByteArrayOutputStream

private const val TAG = "PortalMuse"
private const val EXPIRES_MS = 90_000L
private const val COUNTDOWN = 3

/**
 * A photo, in two gestures: "Take the photo" after seeing the live picture, then "Send" after seeing the photo.
 * Without an answer, nothing is sent.
 */
class PhotoPanel(private val activity: Activity, root: FrameLayout, private val onVisibility: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val settings = Settings(activity)
    private var camera: CameraShot? = null
    private var reply: ((PhotoResult) -> Unit)? = null
    private var photo: Bitmap? = null
    private var counting = 0
    private val expire = Runnable { finish(PhotoResult.Declined) }
    private val count = object : Runnable {
        override fun run() {
            if (counting == 0) {
                number.visibility = View.GONE
                camera?.take()
                return
            }
            number.text = counting.toString()
            number.visibility = View.VISIBLE
            counting--
            main.postDelayed(this, 1_000)
        }
    }

    private val image = ImageView(activity).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private val title = Ui.label(activity, 22f)
    private val why = Ui.label(activity, 20f, Ui.MUTED)
    private val number = Ui.label(activity, 160f).apply {
        gravity = Gravity.CENTER
        setShadowLayer(16f, 0f, 0f, 0xFF000000.toInt())
    }
    private val shutter = Ui.pill(activity, tr(
        "Take the photo",
        "Prendre la photo",
    ), Ui.ACCENT).apply { setOnClickListener { start() } }
    private val turn = Ui.pill(activity, tr(
        "Rotate",
        "Pivoter",
    ), Ui.SURFACE).apply { setOnClickListener { rotate() } }
    private val again = Ui.pill(activity, tr(
        "Retake",
        "Recommencer",
    ), Ui.SURFACE).apply { setOnClickListener { live() } }
    private val send = Ui.pill(activity, tr(
        "Send",
        "Envoyer",
    ), Ui.CALL).apply { setOnClickListener { confirm() } }

    private val panel = FrameLayout(activity).apply {
        setBackgroundColor(Ui.PANEL)
        isClickable = true
        visibility = View.GONE
        addView(
            image,
            FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply { setMargins(0, dp(120), 0, dp(128)) },
        )
        addView(number, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val heading = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(why, Ui.spaced(activity, top = 6))
        }
        addView(
            heading,
            FrameLayout.LayoutParams(dp(1000), WRAP_CONTENT, Gravity.TOP or Gravity.START)
                .apply { setMargins(dp(48), dp(40), 0, 0) },
        )
        addView(
            Ui.round(activity, R.drawable.ic_close, Ui.SURFACE, tr("Send nothing", "Ne rien envoyer")).apply {
                setOnClickListener { finish(PhotoResult.Declined) }
            },
            FrameLayout.LayoutParams(dp(64), dp(64), Gravity.TOP or Gravity.END)
                .apply { setMargins(0, dp(40), dp(40), 0) },
        )
        addView(
            Ui.actions(activity, turn, shutter, again, send),
            FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM).apply { bottomMargin = dp(32) },
        )
    }

    init {
        root.addView(panel, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    val isOpen get() = panel.visibility == View.VISIBLE

    /** False if a photo is already in progress. [onReply] is called exactly once. */
    fun ask(heading: String, reason: String?, onReply: (PhotoResult) -> Unit): Boolean {
        if (reply != null) return false
        if (activity.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            onReply(PhotoResult.Unavailable("the camera permission is not granted on the device"))
            return true
        }
        reply = onReply
        title.text = heading
        why.text = reason.orEmpty()
        why.visibility = if (reason.isNullOrEmpty()) View.GONE else View.VISIBLE
        image.setImageDrawable(null)
        panel.visibility = View.VISIBLE
        camera = CameraShot(
            activity.applicationContext,
            onPreview = { frame -> main.post { if (photo == null && reply != null) image.setImageBitmap(frame) } },
            onPhoto = { taken -> main.post { review(taken) } },
            onFailure = { reason -> main.post { failed(reason) } },
        ).also {
            it.rotation = settings.photoRotation
            it.open()
        }
        live()
        onVisibility()
        return true
    }

    /** Closes without sending anything. */
    fun cancel() {
        if (reply != null) finish(PhotoResult.Declined)
    }

    private fun live() {
        photo = null
        counting = 0
        number.visibility = View.GONE
        buttons(live = true)
        patience()
    }

    private fun start() {
        buttons(live = null)
        counting = COUNTDOWN
        count.run()
        patience()
    }

    private fun review(taken: Bitmap) {
        if (reply == null) return
        photo = taken
        image.setImageBitmap(taken)
        buttons(live = false)
        patience()
    }

    private fun rotate() {
        settings.photoRotation = (settings.photoRotation + 90) % 360
        camera?.rotation = settings.photoRotation
        patience()
    }

    private fun confirm() {
        val taken = photo ?: return
        val out = ByteArrayOutputStream()
        taken.compress(Bitmap.CompressFormat.JPEG, 82, out)
        Log.i(TAG, "photo: sending ${out.size() / 1024} KB approved on screen")
        finish(PhotoResult.Taken(out.toByteArray()))
    }

    /** [live]: preview, photo taken, or neither during the countdown. */
    private fun buttons(live: Boolean?) {
        shutter.visibility = if (live == true) View.VISIBLE else View.GONE
        turn.visibility = shutter.visibility
        send.visibility = if (live == false) View.VISIBLE else View.GONE
        again.visibility = send.visibility
    }

    private fun patience() {
        main.removeCallbacks(expire)
        main.postDelayed(expire, EXPIRES_MS)
    }

    private fun failed(reason: String) {
        if (reply == null) return
        Log.w(TAG, "photo: $reason")
        Toast.makeText(activity, tr(
            "Photo failed: $reason.",
            "Photo impossible : $reason.",
        ), Toast.LENGTH_LONG).show()
        finish(PhotoResult.Unavailable("the camera is unavailable"))
    }

    private fun finish(result: PhotoResult) {
        val waiting = reply ?: return
        reply = null
        main.removeCallbacks(expire)
        main.removeCallbacks(count)
        camera?.close()
        camera = null
        photo = null
        image.setImageDrawable(null)
        panel.visibility = View.GONE
        onVisibility()
        waiting(result)
    }

    private fun dp(value: Int) = Ui.dp(activity, value)
}
