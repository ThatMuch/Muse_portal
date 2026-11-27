package com.portalmuse.gadget

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebStorage
import com.portalmuse.core.AppInfo
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

private const val TAG = "PortalMuse"
private const val MAX_APPS = 12

/** Les petites applications écrites par Muse, dans le stockage privé. */
object AppStore {
    private fun directory(context: Context) = File(context.applicationContext.filesDir, "apps")
    private fun index(context: Context) = File(directory(context), "index.json")

    /** L'adresse fictive d'une application : chacune a ainsi ses propres données. */
    fun origin(name: String) = "https://$name.apps.portal.invalid"

    @Synchronized
    fun list(context: Context): List<AppInfo> = try {
        val list = JSONArray(index(context).takeIf { it.exists() }?.readText() ?: "[]")
        (0 until list.length()).map { list.getJSONObject(it) }
            .map { AppInfo(it.getString("name"), it.getString("title")) }
    } catch (e: IOException) {
        emptyList()
    } catch (e: JSONException) {
        emptyList()
    }

    @Synchronized
    fun save(context: Context, app: AppInfo, html: String): Boolean {
        val apps = list(context)
        if (apps.size >= MAX_APPS && apps.none { it.name == app.name }) return false
        return try {
            directory(context).mkdirs()
            // Le nom a été vérifié par la commande : il ne contient ni point ni barre.
            File(directory(context), "${app.name}.html").writeText(html)
            write(context, apps.filter { it.name != app.name } + app)
            Log.i(TAG, "application enregistrée (${html.length} caractères)")
            true
        } catch (e: IOException) {
            false
        }
    }

    @Synchronized
    fun html(context: Context, name: String): String? = try {
        if (list(context).none { it.name == name }) null else File(directory(context), "$name.html").readText()
    } catch (e: IOException) {
        null
    }

    @Synchronized
    fun remove(context: Context, name: String): Boolean {
        val apps = list(context)
        if (apps.none { it.name == name }) return false
        return try {
            write(context, apps.filter { it.name != name })
            File(directory(context), "$name.html").delete()
            Handler(Looper.getMainLooper()).post { WebStorage.getInstance().deleteOrigin(origin(name)) }
            true
        } catch (e: IOException) {
            false
        }
    }

    /** L'appairage disparaît : les applications et leurs données aussi. */
    @Synchronized
    fun clear(context: Context) {
        for (app in list(context)) remove(context, app.name)
        directory(context).deleteRecursively()
    }

    private fun write(context: Context, apps: List<AppInfo>) {
        val list = JSONArray(apps.map { JSONObject().put("name", it.name).put("title", it.title) })
        index(context).writeText(list.toString())
    }
}
