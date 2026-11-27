// Le contrat est celui de muse-gadget-sdk, protocols/ERROR_REPORT.md.
package com.portalmuse.core

import org.json.JSONObject

private const val MAX_CAUSE = 47
private const val MAX_DETAIL = 95

/**
 * Ce qu'on dit à Muse d'une panne : seulement ce qui vient du code lui-même (type de
 * l'exception, fichier, ligne, fonction). Jamais le message d'une exception, un chemin, une
 * adresse ou un contenu.
 */
data class CrashReport(
    val cause: String,
    val detail: String?,
    val consecutive: Int,
    val version: String,
    val uptimeSeconds: Long,
) {
    /** Le message à envoyer sur le flux de contrôle ; sans identifiant, sans réponse. */
    fun message(): JSONObject {
        val fault = JSONObject().put("cause", cause).putOpt("detail", detail)
        val report = JSONObject()
            .put("schema_version", 1).put("kind", "crash").put("reason", "unhandled_exception")
            .put("consecutive_count", consecutive).put("version", version)
            .put("uptime_seconds", uptimeSeconds).put("fault", fault)
        return JSONObject().put("method", "link.error_report").put("report", report)
    }

    fun store(): String = JSONObject().put("cause", cause).putOpt("detail", detail).put("consecutive", consecutive)
        .put("version", version).put("uptime", uptimeSeconds).toString()

    companion object {
        /** L'ASCII imprimable, sans les caractères que JSON devrait échapper. */
        private fun clean(text: String, max: Int): String? =
            text.filter { it in ' '..'~' && it != '"' && it != '\\' }.take(max).takeIf { it.isNotEmpty() }

        /** [ownPackage] : la première ligne de la pile qui vient de l'application est retenue. */
        fun of(error: Throwable, ownPackage: String, consecutive: Int, version: String, uptimeSeconds: Long): CrashReport {
            // La cause première dit mieux où est la faute que l'enveloppe d'Android.
            var root = error
            var depth = 0
            while (depth++ < 10) root = root.cause?.takeIf { it !== root } ?: break
            val frame = root.stackTrace.firstOrNull { it.className.startsWith(ownPackage) } ?: root.stackTrace.firstOrNull()
            val detail = frame?.let {
                clean("${it.fileName ?: "?"}:${it.lineNumber} in ${it.methodName}", MAX_DETAIL)
            }
            return CrashReport(
                clean(root.javaClass.simpleName, MAX_CAUSE) ?: "Error", detail,
                consecutive.coerceAtLeast(1), clean(version, 31) ?: "0", uptimeSeconds.coerceAtLeast(0),
            )
        }

        /** `null` pour un enregistrement illisible. */
        fun restore(stored: String): CrashReport? = try {
            val json = JSONObject(stored)
            CrashReport(
                clean(json.getString("cause"), MAX_CAUSE) ?: "Error",
                (json.opt("detail") as? String)?.let { clean(it, MAX_DETAIL) },
                json.getInt("consecutive").coerceAtLeast(1),
                clean(json.getString("version"), 31) ?: "0", json.getLong("uptime").coerceAtLeast(0),
            )
        } catch (e: org.json.JSONException) {
            null
        }
    }
}
