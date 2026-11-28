package com.portalmuse.gadget

import android.content.Context
import android.util.Log
import com.portalmuse.core.CallEvent
import com.portalmuse.core.CallResult
import com.portalmuse.core.DeviceCommands
import com.portalmuse.core.DeviceDescription
import com.portalmuse.core.LinkSession
import com.portalmuse.core.NoiseUrl
import com.portalmuse.core.OkHttpFrameSocket
import com.portalmuse.core.UpgradeRejected
import com.portalmuse.core.VideoCall
import com.portalmuse.core.VoiceCall
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private const val TAG = "PortalMuse"
private const val TRANSPORT_TIMEOUT_S = 35L
private const val WAKE_FIRST_TURN_MS = 15_000L
/** In video we wait for the first answer, for lack of knowing what Muse heard. */
private const val WAKE_VIDEO_FIRST_MS = 20_000L
/** Without any exchange during this time, the call hangs up on its own. */
private const val IDLE_HANG_UP_MS = 30_000L

/**
 * A voice or video call: a separate Muse connection reserved for the call (the permanent session stays), then
 * the call itself.
 */
class MuseCall(
    private val context: Context,
    /**
     * Call opened by "Hey Muse": its microphone, capturing since the wake word. The call closes it, and ends if
     * no request arrives.
     */
    private val wakeMic: WakeMic?,
    /** Video call: where to show Muse's pictures; `null` for a voice call. */
    private val video: VideoScreen? = null,
    private val onEvent: (CallEvent) -> Unit,
    private val onEnd: (Outcome) -> Unit,
) {
    sealed class Outcome {
        class Finished(val result: CallResult) : Outcome()
        /** The voice connection could not be opened. */
        class Unreachable(val status: Int?) : Outcome()
        object NoAudio : Outcome()
    }

    @Volatile private var cancelled = false
    @Volatile private var call: VoiceCall? = null
    @Volatile private var videoCall: VideoCall? = null
    @Volatile var silentSpeaker = false
        private set

    fun hangUp() {
        cancelled = true
        call?.hangUp()
        videoCall?.hangUp()
    }

    fun interrupt() {
        thread(name = "call-interrupt") { call?.interrupt() }
    }

    fun start() {
        thread(name = "muse-call") {
            val outcome = try {
                run()
            } catch (e: UpgradeRejected) {
                Outcome.Unreachable(e.status)
            } catch (e: IOException) {
                Log.w(TAG, "call: ${e.message}")
                Outcome.Unreachable(null)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "call: audio unavailable (${e.javaClass.simpleName})")
                Outcome.NoAudio
            } catch (e: RuntimeException) {
                // The type only: a message could contain a token.
                Log.e(TAG, "call failed: ${e.javaClass.simpleName}")
                Outcome.Unreachable(null)
            }
            wakeMic?.close()
            Log.i(TAG, "call ended: ${describe(outcome)}")
            onEnd(outcome)
        }
    }

    private fun run(): Outcome {
        val pairing = SecretStore(context).pairing ?: return Outcome.Unreachable(null)
        val log: (String) -> Unit = { Log.i(TAG, "call: $it") }
        val lookup = MuseHttp.api(context).fetchVms(pairing.accessToken, pairing.apiUrlV2, log)
        val vm = lookup.vms.firstOrNull { it.isDefault } ?: lookup.vms.firstOrNull()
            ?: return Outcome.Unreachable(lookup.status)
        if (cancelled) return hungUp()
        val socket = OkHttpFrameSocket.connect(
            MuseHttp.client, NoiseUrl.of(pairing, vm, NoiseUrl.VOICE_APP_ID), vm.authToken, MuseHttp.userAgent(context),
        )
        val ready = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val session = LinkSession(
            socket,
            DeviceDescription(pairing.nodeId, "Portal Muse", "voice", "portalmuse-portal-10", "Portal 10 inch (DIY client)"),
            DeviceCommands({ JSONObject() }, "voice"), executor,
            onRegistered = { ready.countDown() }, log = log, registerDevice = false,
        )
        thread(name = "call-link") { session.run() }
        try {
            if (!ready.await(TRANSPORT_TIMEOUT_S, TimeUnit.SECONDS)) return Outcome.Unreachable(null)
            if (cancelled) return hungUp()
            if (video != null) return Outcome.Finished(videoCall(session, pairing.nodeId, video, log))
            val audio = PhoneAudio(context, wakeMic)
            silentSpeaker = audio.silentSpeaker
            try {
                val voice = VoiceCall(
                    session, pairing.nodeId, audio,
                    idleTimeoutMillis = IDLE_HANG_UP_MS,
                    firstTurnTimeoutMillis = WAKE_FIRST_TURN_MS.takeIf { wakeMic != null },
                    onEvent = onEvent, log = log,
                )
                call = voice
                if (cancelled) voice.hangUp()
                return Outcome.Finished(voice.run())
            } finally {
                audio.release()
            }
        } finally {
            session.stop()
            executor.shutdown()
        }
    }

    private fun videoCall(link: LinkSession, nodeId: String, screen: VideoScreen, log: (String) -> Unit): CallResult {
        val audio = VideoPhoneAudio(context, wakeMic)
        silentSpeaker = audio.silentSpeaker
        try {
            val created = VideoCall(
                link, nodeId, audio, onVideo = screen::push, onEvent = onEvent,
                firstTurnTimeoutMillis = WAKE_VIDEO_FIRST_MS.takeIf { wakeMic != null }, log = log,
                museName = Settings(context).museName,
            )
            videoCall = created
            screen.onNeedKeyframe = created::requestKeyframe
            if (cancelled) created.hangUp()
            return created.run()
        } finally {
            screen.onNeedKeyframe = {}
            audio.release()
        }
    }

    private fun hungUp() = Outcome.Finished(CallResult.Ended(com.portalmuse.core.CallEnd.HUNG_UP))

    private fun describe(outcome: Outcome) = when (outcome) {
        is Outcome.Finished -> outcome.result.toString()
        is Outcome.Unreachable -> "connection failed${outcome.status?.let { " HTTP $it" } ?: ""}"
        Outcome.NoAudio -> "audio unavailable"
    }
}
