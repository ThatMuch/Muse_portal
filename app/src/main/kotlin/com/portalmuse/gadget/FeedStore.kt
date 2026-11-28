package com.portalmuse.gadget

import android.content.Context
import android.util.Log
import com.portalmuse.core.FeedItem
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

private const val TAG = "PortalMuse"
private const val INDEX = "feed.json"

/** The last feed sent by Muse, in private storage. Pictures are files there. */
object FeedStore {
    class Saved(val items: List<FeedItem>, val savedAt: Long)

    private fun directory(context: Context) = File(context.applicationContext.filesDir, "feed")

    @Synchronized
    fun save(context: Context, items: List<FeedItem>) {
        val directory = directory(context)
        val work = File(directory.path + ".new")
        try {
            work.deleteRecursively()
            if (!work.mkdirs()) throw IOException("folder not created")
            val list = JSONArray()
            items.forEachIndexed { index, item ->
                val picture = (item.image as? ByteArray)?.let {
                    "%02d.jpg".format(index).also { name -> File(work, name).writeBytes(it) }
                }
                list.put(
                    JSONObject().put("title", item.title).putOpt("text", item.text).putOpt("icon", item.icon)
                        .put("label", item.actionLabel).put("message", item.actionMessage).putOpt("image", picture),
                )
            }
            File(work, INDEX).writeText(JSONObject().put("at", System.currentTimeMillis()).put("items", list).toString())
            directory.deleteRecursively()
            if (!work.renameTo(directory)) throw IOException("folder not renamed")
        } catch (e: IOException) {
            Log.w(TAG, "feed: saving failed (${e.javaClass.simpleName})")
            work.deleteRecursively()
        }
    }

    /** `null` without a feed. Each item's image is the [File] to show. */
    @Synchronized
    fun load(context: Context): Saved? {
        val directory = directory(context)
        return try {
            val index = JSONObject(File(directory, INDEX).readText())
            val list = index.getJSONArray("items")
            val items = (0 until list.length()).map { position ->
                val item = list.getJSONObject(position)
                fun text(key: String) = item.optString(key).ifEmpty { null }
                FeedItem(
                    item.getString("title"), text("text"), text("icon"), item.getString("label"),
                    item.getString("message"), text("image")?.let { File(directory, it).takeIf(File::exists) },
                )
            }
            if (items.isEmpty()) null else Saved(items, index.getLong("at"))
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
