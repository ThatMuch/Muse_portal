// Kotlin port of muse-gadget-sdk (Copyright (c) Meta Platforms, Inc. and affiliates, Apache-2.0). Sources:
// esp32/main/avatar_identity.c, avatar_clip_refs.c, avatar_sync.c, vm_file_path.h
package com.portalmuse.core

import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

private const val MAX_IDENTITY_BYTES = 64 * 1024
private const val MAX_PATH_CHARS = 511
private const val MAX_REVISION_CHARS = 127
private const val POLL_MS = 100L

/** The Muse's avatar: a still picture and, depending on the account, short videos per state. */
class Avatar(
    val imagePath: String,
    val revision: String,
    val videos: Map<String, String>,
    /** The version for a dark background, when the account has one. */
    val darkImagePath: String? = null,
    val darkVideos: Map<String, String> = emptyMap(),
) {
    /**
     * The set to show on a dark screen: the dark version if it has its picture and idle video, otherwise the
     * light one. The two are not mixed, their backgrounds differ.
     */
    fun forDarkScreen(): Avatar =
        if (darkImagePath != null && ("idle" in darkVideos || "idle" !in videos)) {
            Avatar(darkImagePath, revision, darkVideos)
        } else {
            this
        }

    override fun toString() = tr("Avatar(videos=${videos.keys})", "Avatar(vidéos=${videos.keys})")
}

sealed class AvatarIdentity {
    class Present(val avatar: Avatar) : AvatarIdentity()
    /** No picture for now; it may still be being generated. */
    object None : AvatarIdentity()
    /** Unexpected answer: keep what we have. */
    object Unknown : AvatarIdentity()
}

object AvatarParser {
    private val NAME = Regex("[a-z0-9_]{1,40}")

    fun parse(json: String): AvatarIdentity {
        val root = try {
            JSONObject(json)
        } catch (e: JSONException) {
            return AvatarIdentity.Unknown
        } catch (e: StackOverflowError) {
            return AvatarIdentity.Unknown
        }
        val avatar = root.takeIf { it.opt("ok") == true }?.optJSONObject("result")?.optJSONObject("avatar")
        val exists = avatar?.opt("image_exists") as? Boolean ?: return AvatarIdentity.Unknown
        if (!exists) return AvatarIdentity.None
        val image = avatar.opt("image_path") as? String
        val revision = avatar.opt("assets_revision") as? String ?: ""
        if (image.isNullOrEmpty() || image.length > MAX_PATH_CHARS || revision.length > MAX_REVISION_CHARS) {
            return AvatarIdentity.Unknown
        }
        fun path(value: Any?) = (value as? String)?.takeIf { it.isNotEmpty() && it.length <= MAX_PATH_CHARS }
        fun videos(field: String): Map<String, String> {
            val variants = avatar.optJSONObject(field) ?: return emptyMap()
            return variants.keys().asSequence().filter(NAME::matches)
                .mapNotNull { name -> path(variants.opt(name))?.let { name to it } }
                .toMap()
        }
        return AvatarIdentity.Present(
            Avatar(
                image, revision, videos("video_variants"),
                path(avatar.opt("darkmode_image_path")), videos("darkmode_video_variants"),
            ),
        )
    }

    /** The names of the fields received, for diagnosis. No values. */
    fun shape(json: String): String = try {
        val avatar = JSONObject(json).optJSONObject("result")?.optJSONObject("avatar")
        fun names(value: JSONObject?) = value?.keys()?.asSequence()?.filter(NAME::matches)?.sorted()?.joinToString(",").orEmpty()
        tr(
            "avatar{${names(avatar)}} videos{${names(avatar?.optJSONObject("video_variants"))}} " +
                "dark{${names(avatar?.optJSONObject("darkmode_video_variants"))}}",
            "avatar{${names(avatar)}} vidéos{${names(avatar?.optJSONObject("video_variants"))}} " +
                "sombres{${names(avatar?.optJSONObject("darkmode_video_variants"))}}",
        )
    } catch (e: JSONException) {
        "illisible"
    }

    /** The path of a VM file encoded as a path; `null` if it is suspicious. */
    fun filePath(path: String): String? {
        val relative = path.trimStart('/')
        if (relative.isEmpty() || relative.split('/').any { it == ".." } || relative.any { it.isISOControl() }) return null
        val out = StringBuilder("/fs/raw/")
        for (byte in relative.toByteArray()) {
            val c = (byte.toInt() and 0xFF).toChar()
            if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "-._~/") out.append(c)
            else out.append('%').append("%02X".format(byte.toInt() and 0xFF))
        }
        return out.toString()
    }
}

/** Reads the avatar record and files over the already open session, without a chat message. */
class AvatarClient(
    private val streams: MuseStreams,
    private val chunkBytes: Int = 128 * 1024,
    private val timeoutMillis: Long = 30_000,
) {
    /** The record and the description of its fields; `null` if it could not be read. */
    fun identity(log: (String) -> Unit = {}): Pair<AvatarIdentity, String>? {
        val body = ByteArrayOutputStream()
        return try {
            val (status, _) = get("/identity", emptyList(), body, MAX_IDENTITY_BYTES)
            if (status !in 200..299) {
                log("record refused: HTTP $status")
                null
            } else {
                val text = body.toString("UTF-8")
                AvatarParser.parse(text) to AvatarParser.shape(text)
            }
        } catch (e: IOException) {
            log("record unreadable: ${e.javaClass.simpleName}")
            null
        }
    }

    /** Writes the whole file to [out]; false if refused, incomplete or too large. */
    fun download(path: String, maxBytes: Int, out: OutputStream, log: (String) -> Unit = {}): Boolean {
        val target = AvatarParser.filePath(path) ?: return false
        var offset = 0
        var size: Int? = null
        try {
            while (true) {
                val part = ByteArrayOutputStream()
                // As in the firmware: once the size is known, ask for nothing beyond it.
                val end = minOf(offset + chunkBytes, size ?: Int.MAX_VALUE) - 1
                val (status, headers) = get(target, listOf("Range" to "bytes=$offset-$end"), part, maxBytes)
                val total = headers["content-range"]?.substringAfterLast('/')?.toIntOrNull()
                if (total != null && total > maxBytes) return fail(log, "file too large")
                size = total ?: size
                if (status == 200 && offset == 0) {
                    part.writeTo(out)
                    return part.size() > 0
                }
                // Without an announced size, the end is found by asking beyond it.
                if (status == 416 && total == null && offset > 0) return true
                if (status != 206 || part.size() == 0 || part.size() > chunkBytes) return fail(log, "HTTP $status")
                if (offset + part.size() > maxBytes) return fail(log, "file too large")
                part.writeTo(out)
                offset += part.size()
                if (total != null && offset >= total) return offset == total
                // An answer shorter than requested ends the file, unless its size says otherwise.
                if (total == null && part.size() < chunkBytes) return true
            }
        } catch (e: IOException) {
            return fail(log, e.javaClass.simpleName)
        }
    }

    private fun fail(log: (String) -> Unit, reason: String): Boolean {
        log("download interrupted: $reason")
        return false
    }

    private fun get(
        path: String, headers: List<Pair<String, String>>, out: ByteArrayOutputStream, maxBytes: Int,
    ): Pair<Int, Map<String, String>> {
        val stream = streams.open(ServiceType.DAEMON, "GET", path, headers + ("x-app-id" to "musegadget"), ByteArray(0))
        try {
            val status = stream.awaitStatus(timeoutMillis)
            var quiet = System.nanoTime()
            while (true) {
                val chunk = stream.read(POLL_MS) ?: break
                if (chunk.isNotEmpty()) quiet = System.nanoTime()
                if ((System.nanoTime() - quiet) / 1_000_000 > timeoutMillis) throw IOException("answer interrupted")
                out.write(chunk)
                if (out.size() > maxBytes) throw IOException("answer too large")
            }
            return status to stream.headers
        } finally {
            stream.close()
        }
    }
}
