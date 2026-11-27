package com.portalmuse.gadget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Size
import java.io.ByteArrayOutputStream

private const val TAG = "PortalMuse"
/** La première caméra : l'autre est tenue par les services du Portal, qu'on ne dérange pas. */
private const val CAMERA = "0"
private const val PREVIEW_INTERVAL_MS = 90L

/**
 * La caméra le temps d'une photo : aperçu image par image, puis une vue fixe. L'aperçu et la
 * photo passent par la même rotation, on voit donc ce qui partira. Rien n'est écrit sur disque.
 * Les rappels arrivent sur un fil propre à la caméra.
 */
class CameraShot(
    private val context: Context,
    private val onPreview: (Bitmap) -> Unit,
    private val onPhoto: (Bitmap) -> Unit,
    private val onFailure: (String) -> Unit,
) {
    @Volatile var rotation = 0
    private val thread = HandlerThread("camera").apply { start() }
    private val handler = Handler(thread.looper)
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var preview: ImageReader? = null
    private var still: ImageReader? = null
    private var lastPreview = 0L
    @Volatile private var closed = false

    /** L'autorisation de la caméra doit déjà être accordée. */
    @SuppressLint("MissingPermission")
    fun open() = guarded {
        val manager = context.getSystemService(CameraManager::class.java)
        val sizes = manager.getCameraCharacteristics(CAMERA)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        fun closest(options: Array<Size>, width: Int, height: Int) =
            options.minByOrNull { Math.abs(it.width * it.height - width * height) }!!
        val small = closest(sizes.getOutputSizes(ImageFormat.YUV_420_888), 640, 480)
        val large = closest(sizes.getOutputSizes(ImageFormat.JPEG), 1280, 960)
        preview = ImageReader.newInstance(small.width, small.height, ImageFormat.YUV_420_888, 2).apply {
            setOnImageAvailableListener({ reader -> guarded { showFrame(reader) } }, handler)
        }
        still = ImageReader.newInstance(large.width, large.height, ImageFormat.JPEG, 1).apply {
            setOnImageAvailableListener({ reader -> guarded { keepPhoto(reader) } }, handler)
        }
        manager.openCamera(CAMERA, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                if (closed) return camera.close()
                device = camera
                guarded { startSession(camera) }
            }

            override fun onDisconnected(camera: CameraDevice) = fail("la caméra a été reprise par le Portal")

            override fun onError(camera: CameraDevice, error: Int) = fail("la caméra est indisponible (code $error)")
        }, handler)
    }

    @Suppress("DEPRECATION")
    private fun startSession(camera: CameraDevice) {
        val outputs = listOf(preview!!.surface, still!!.surface)
        camera.createCaptureSession(outputs, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(configured: CameraCaptureSession) {
                if (closed) return
                session = configured
                guarded {
                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                    request.addTarget(preview!!.surface)
                    configured.setRepeatingRequest(request.build(), null, handler)
                }
            }

            override fun onConfigureFailed(configured: CameraCaptureSession) = fail("la caméra refuse ce réglage")
        }, handler)
    }

    fun take() {
        handler.post {
            guarded {
                val camera = device ?: return@guarded fail("la caméra n'est pas prête")
                val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                request.addTarget(still!!.surface)
                request.set(CaptureRequest.JPEG_QUALITY, 90.toByte())
                request.set(CaptureRequest.JPEG_ORIENTATION, 0)
                session?.capture(request.build(), null, handler) ?: fail("la caméra n'est pas prête")
            }
        }
    }

    private fun showFrame(reader: ImageReader) {
        val frame = reader.acquireLatestImage() ?: return
        frame.use {
            val now = SystemClock.elapsedRealtime()
            if (now - lastPreview < PREVIEW_INTERVAL_MS) return
            lastPreview = now
            val jpeg = ByteArrayOutputStream()
            YuvImage(nv21(it), ImageFormat.NV21, it.width, it.height, null)
                .compressToJpeg(Rect(0, 0, it.width, it.height), 70, jpeg)
            decode(jpeg.toByteArray())?.let(onPreview)
        }
    }

    private fun keepPhoto(reader: ImageReader) {
        val photo = reader.acquireLatestImage() ?: return
        photo.use {
            val buffer = it.planes[0].buffer
            val bytes = ByteArray(buffer.remaining()).also(buffer::get)
            decode(bytes)?.let(onPhoto) ?: fail("photo illisible")
        }
    }

    private fun decode(jpeg: ByteArray): Bitmap? {
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return null
        val turn = rotation
        if (turn == 0) return bitmap
        val matrix = Matrix().apply { postRotate(turn.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /** Les trois plans d'une image YUV rangés en NV21, quels que soient leurs pas. */
    private fun nv21(image: Image): ByteArray {
        val width = image.width
        val height = image.height
        val out = ByteArray(width * height * 3 / 2)
        val luma = image.planes[0]
        for (row in 0 until height) {
            luma.buffer.position(row * luma.rowStride)
            luma.buffer.get(out, row * width, width)
        }
        var position = width * height
        val u = image.planes[1]
        val v = image.planes[2]
        for (row in 0 until height / 2) {
            for (column in 0 until width / 2) {
                out[position++] = v.buffer.get(row * v.rowStride + column * v.pixelStride)
                out[position++] = u.buffer.get(row * u.rowStride + column * u.pixelStride)
            }
        }
        return out
    }

    private fun guarded(action: () -> Unit) {
        if (closed) return
        try {
            action()
        } catch (e: CameraAccessException) {
            fail("la caméra est indisponible")
        } catch (e: RuntimeException) {
            Log.w(TAG, "photo : ${e.javaClass.simpleName}")
            fail("la caméra est indisponible")
        }
    }

    private fun fail(reason: String) {
        if (!closed) onFailure(reason)
    }

    fun close() {
        if (closed) return
        closed = true
        handler.post {
            runCatching { session?.close() }
            runCatching { device?.close() }
            preview?.close()
            still?.close()
            thread.quitSafely()
        }
    }
}
