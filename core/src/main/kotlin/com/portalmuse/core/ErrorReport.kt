// The contract is the one of muse-gadget-sdk, protocols/ERROR_REPORT.md.
package com.portalmuse.core

import org.json.JSONObject

private const val MAX_CAUSE = 47
private const val MAX_DETAIL = 95

/**
 * What Muse is told about a crash: only what comes from the code itself (exception type, file, line, function).
 * Never an exception's message, a path, an address or any content.
 */
data class CrashReport(
    val cause: String,
    val detail: String?,
    val consecutive: Int,
    val version: String,
    val uptimeSeconds: Long,
) {
    /** The message to send on the control stream; no identifier, no answer. */
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
        /** Printable ASCII, without the characters JSON would need to escape. */
        private fun clean(text: String, max: Int): String? =
            text.filter { it in ' '..'~' && it != '"' && it != '\\' }.take(max).takeIf { it.isNotEmpty() }

        /** [ownPackage]: the first line of the stack that comes from the app is kept. */
        fun of(error: Throwable, ownPackage: String, consecutive: Int, version: String, uptimeSeconds: Long): CrashReport {
            // The root cause says better where the fault is than Android's wrapper.
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

        /** `null` for an unreadable record. */
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
