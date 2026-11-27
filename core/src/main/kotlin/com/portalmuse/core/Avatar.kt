// Portage Kotlin de muse-gadget-sdk (Copyright (c) Meta Platforms, Inc. and affiliates, Apache-2.0).
// Sources : esp32/main/avatar_identity.c, avatar_clip_refs.c, avatar_sync.c, vm_file_path.h
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

/** L'avatar du Muse : une image fixe et, selon le compte, de courtes vidéos par état. */
class Avatar(
    val imagePath: String,
    val revision: String,
    val videos: Map<String, String>,
    /** La version pour fond sombre, quand le compte en a une. */
    val darkImagePath: String? = null,
    val darkVideos: Map<String, String> = emptyMap(),
) {
    /**
     * Le jeu à afficher sur un écran sombre : la version sombre si elle a son image et sa vidéo de
     * repos, sinon la claire. On ne mélange pas les deux, leurs fonds diffèrent.
     */
    fun forDarkScreen(): Avatar =
        if (darkImagePath != null && ("idle" in darkVideos || "idle" !in videos)) {
            Avatar(darkImagePath, revision, darkVideos)
        } else {
            this
        }

    override fun toString() = "Avatar(vidéos=${videos.keys})"
}

sealed class AvatarIdentity {
    class Present(val avatar: Avatar) : AvatarIdentity()
    /** Pas d'image pour l'instant ; elle peut être en cours de création. */
    object None : AvatarIdentity()
    /** Réponse inattendue : garder ce qu'on a. */
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

    /** Les noms des champs reçus, pour le diagnostic. Aucune valeur. */
    fun shape(json: String): String = try {
        val avatar = JSONObject(json).optJSONObject("result")?.optJSONObject("avatar")
        fun names(value: JSONObject?) = value?.keys()?.asSequence()?.filter(NAME::matches)?.sorted()?.joinToString(",").orEmpty()
        "avatar{${names(avatar)}} vidéos{${names(avatar?.optJSONObject("video_variants"))}} " +
            "sombres{${names(avatar?.optJSONObject("darkmode_video_variants"))}}"
    } catch (e: JSONException) {
        "illisible"
    }

    /** Le chemin d'un fichier de la VM encodé comme un chemin ; `null` s'il est douteux. */
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

/** Lit la fiche et les fichiers de l'avatar par la session déjà ouverte, sans message de chat. */
class AvatarClient(
    private val streams: MuseStreams,
    private val chunkBytes: Int = 128 * 1024,
    private val timeoutMillis: Long = 30_000,
) {
    /** La fiche et la description de ses champs ; `null` si elle n'a pas pu être lue. */
    fun identity(log: (String) -> Unit = {}): Pair<AvatarIdentity, String>? {
        val body = ByteArrayOutputStream()
        return try {
            val (status, _) = get("/identity", emptyList(), body, MAX_IDENTITY_BYTES)
            if (status !in 200..299) {
                log("fiche refusée : HTTP $status")
                null
            } else {
                val text = body.toString("UTF-8")
                AvatarParser.parse(text) to AvatarParser.shape(text)
            }
        } catch (e: IOException) {
            log("fiche illisible : ${e.javaClass.simpleName}")
            null
        }
    }

    /** Écrit le fichier entier dans [out] ; faux s'il est refusé, incomplet ou trop grand. */
    fun download(path: String, maxBytes: Int, out: OutputStream, log: (String) -> Unit = {}): Boolean {
        val target = AvatarParser.filePath(path) ?: return false
        var offset = 0
        var size: Int? = null
        try {
            while (true) {
                val part = ByteArrayOutputStream()
                // Comme le firmware : une fois la taille connue, ne rien demander au-delà.
                val end = minOf(offset + chunkBytes, size ?: Int.MAX_VALUE) - 1
                val (status, headers) = get(target, listOf("Range" to "bytes=$offset-$end"), part, maxBytes)
                val total = headers["content-range"]?.substringAfterLast('/')?.toIntOrNull()
                if (total != null && total > maxBytes) return fail(log, "fichier trop grand")
                size = total ?: size
                if (status == 200 && offset == 0) {
                    part.writeTo(out)
                    return part.size() > 0
                }
                // Sans taille annoncée, la fin se découvre en demandant au-delà.
                if (status == 416 && total == null && offset > 0) return true
                if (status != 206 || part.size() == 0 || part.size() > chunkBytes) return fail(log, "HTTP $status")
                if (offset + part.size() > maxBytes) return fail(log, "fichier trop grand")
                part.writeTo(out)
                offset += part.size()
                if (total != null && offset >= total) return offset == total
                // Une réponse plus courte que demandé finit le fichier, sauf si sa taille dit le contraire.
                if (total == null && part.size() < chunkBytes) return true
            }
        } catch (e: IOException) {
            return fail(log, e.javaClass.simpleName)
        }
    }

    private fun fail(log: (String) -> Unit, reason: String): Boolean {
        log("téléchargement interrompu : $reason")
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
                if ((System.nanoTime() - quiet) / 1_000_000 > timeoutMillis) throw IOException("réponse interrompue")
                out.write(chunk)
                if (out.size() > maxBytes) throw IOException("réponse trop grande")
            }
            return status to stream.headers
        } finally {
            stream.close()
        }
    }
}
