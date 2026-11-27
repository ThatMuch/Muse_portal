package com.portalmuse.gadget

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

private const val TAG = "PortalMuse"
private const val META = "meta.json"

/** Les photos du cadre et le délai avant qu'il s'affiche. */
class Frame(val photos: List<Pair<File, String?>>, val idleMinutes: Int)

/** Les photos du cadre, dans le stockage privé de l'app. */
object FrameStore {
    private fun directory(context: Context) = File(context.applicationContext.filesDir, "frame")

    /** Remplace tout le contenu ; renvoie le nombre de photos enregistrées (0 en cas d'échec). */
    @Synchronized
    fun save(context: Context, photos: List<Pair<ByteArray, String?>>, idleMinutes: Int): Int {
        val directory = directory(context)
        val work = File(directory.path + ".new")
        return try {
            work.deleteRecursively()
            if (!work.mkdirs()) throw IOException("dossier non créé")
            val list = JSONArray()
            photos.forEachIndexed { index, (bytes, caption) ->
                val name = "%02d.jpg".format(index)
                File(work, name).writeBytes(bytes)
                list.put(JSONObject().put("file", name).put("caption", caption.orEmpty()))
            }
            File(work, META).writeText(JSONObject().put("idle_minutes", idleMinutes).put("photos", list).toString())
            directory.deleteRecursively()
            if (!work.renameTo(directory)) throw IOException("dossier non renommé")
            photos.size
        } catch (e: IOException) {
            Log.w(TAG, "cadre photo : enregistrement en échec (${e.javaClass.simpleName})")
            work.deleteRecursively()
            0
        }
    }

    /** `null` sans photos. */
    @Synchronized
    fun load(context: Context): Frame? {
        val directory = directory(context)
        return try {
            val meta = JSONObject(File(directory, META).readText())
            val list = meta.getJSONArray("photos")
            val photos = (0 until list.length()).mapNotNull { index ->
                val item = list.getJSONObject(index)
                File(directory, item.getString("file")).takeIf { it.exists() }
                    ?.let { it to item.optString("caption").ifEmpty { null } }
            }
            if (photos.isEmpty()) null else Frame(photos, meta.getInt("idle_minutes"))
        } catch (e: IOException) {
            null
        } catch (e: JSONException) {
            null
        }
    }

    @Synchronized
    fun clear(context: Context) {
        directory(context).deleteRecursively()
    }
}
