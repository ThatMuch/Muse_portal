package com.portalmuse.core

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Un groupe de commandes que l'appareil propose à Muse. */
interface CommandSet {
    fun specs(): Map<String, JSONObject>

    /** `null` si [command] n'est pas de ce groupe. */
    fun run(command: String, params: JSONObject): JSONObject?
}

/** La forme commune des déclarations et des réponses. Les textes pour Muse sont en anglais. */
internal object Specs {
    fun field(type: String, description: String): JSONObject = JSONObject().put("type", type).put("description", description)

    fun of(description: String, required: JSONObject, optional: JSONObject): JSONObject = JSONObject()
        .put("description", description).put("required", required).put("optional", optional)

    fun ok(payload: JSONObject): JSONObject = JSONObject().put("ok", true).put("payload", payload)
    fun error(message: String): JSONObject = JSONObject().put("ok", false).put("error", message)
    fun unavailable() = error("the screen is showing something else right now")

    fun text(from: JSONObject, key: String, max: Int): String? =
        (from.opt(key) as? String)?.trim()?.takeIf { it.isNotEmpty() }?.take(max)
}

/** La lecture et le chargement d'une liste d'images désignées par Muse. */
internal object Pictures {
    private const val MAX_PATH_CHARS = 2_048
    private const val MAX_CAPTION_CHARS = 200

    /** (source, légende) pour chaque élément, ou `null` si l'un d'eux est invalide. */
    fun parse(list: JSONArray): List<Pair<PictureSource, String?>>? = (0 until list.length()).map { index ->
        val item = list.optJSONObject(index) ?: return null
        val url = Specs.text(item, "url", MAX_PATH_CHARS)
        val path = Specs.text(item, "path", MAX_PATH_CHARS)
        if ((url == null) == (path == null)) return null
        val source = if (url != null) PictureSource.Web(url) else PictureSource.File(path!!)
        source to Specs.text(item, "caption", MAX_CAPTION_CHARS)
    }

    /** En parallèle, avec une échéance commune : une image lente ne bloque pas les autres. */
    fun load(sources: List<PictureSource>, load: (PictureSource) -> Any?, deadlineSeconds: Long): List<Any?> {
        val pool = Executors.newFixedThreadPool(minOf(sources.size, 4))
        return try {
            pool.invokeAll(sources.map { source -> Callable { load(source) } }, deadlineSeconds, TimeUnit.SECONDS)
                .map { future ->
                    if (future.isCancelled) null
                    else try {
                        future.get()
                    } catch (e: ExecutionException) {
                        null
                    }
                }
        } finally {
            pool.shutdownNow()
        }
    }
}
