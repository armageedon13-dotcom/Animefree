package com.anifree.app

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.MimeTypeMap
import org.json.JSONArray
import org.json.JSONObject

/** Only backend-approved, explicitly licensed sources may be added here. */
data class LicensedEpisode(
    val animeId: Int,
    val episodeTitle: String,
    val streamUrl: String,
    val streamType: StreamType,
    val subtitlePtUrl: String? = null,
    val downloadMp4Url: String? = null,
    val downloadAllowed: Boolean = false,
    val allowedHosts: Set<String>
)
enum class StreamType { MP4, HLS, DASH }

// Empty by design. Replace with signed, moderated backend results after licensing verification.
object LicensedCatalog {
    val episodes: List<LicensedEpisode> = emptyList()
}

object SourcePolicy {
    fun approved(url: String?, hosts: Set<String>): Boolean {
        if (url == null) return false
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        return uri.scheme == "https" && uri.userInfo == null && uri.host?.lowercase() in hosts.map { it.lowercase() } &&
            uri.port == -1 && uri.fragment == null
    }
    fun downloadable(episode: LicensedEpisode): Boolean {
        val url = episode.downloadMp4Url ?: return false
        return episode.downloadAllowed && approved(url, episode.allowedHosts) &&
            Uri.parse(url).path.orEmpty().lowercase().endsWith(".mp4")
    }
}

data class SavedDownload(val id: Long, val title: String, val fileName: String)

/** DownloadManager persists jobs and supports offline access to completed MP4 files. */
class OfflineLibrary(private val context: Context) {
    private val prefs = context.getSharedPreferences("offline_library", Context.MODE_PRIVATE)
    fun items(): List<SavedDownload> {
        val array = JSONArray(prefs.getString("items", "[]"))
        return (0 until array.length()).map { index ->
            val o = array.getJSONObject(index)
            SavedDownload(o.getLong("id"), o.getString("title"), o.getString("file"))
        }
    }
    fun enqueue(episode: LicensedEpisode): Long {
        require(SourcePolicy.downloadable(episode)) { "Download não autorizado ou formato indisponível" }
        val url = episode.downloadMp4Url!!
        val name = "anifree_${episode.animeId}_${System.currentTimeMillis()}.mp4"
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(episode.episodeTitle)
            .setDescription("Episódio autorizado • MP4")
            .setMimeType("video/mp4")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_MOVIES, name)
            .setAllowedOverMetered(false)
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val id = manager.enqueue(request)
        val current = JSONArray(prefs.getString("items", "[]"))
        current.put(JSONObject().put("id", id).put("title", episode.episodeTitle).put("file", name))
        prefs.edit().putString("items", current.toString()).apply()
        return id
    }
    fun status(id: Long): Int {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
            if (cursor.moveToFirst()) return cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
        }
        return DownloadManager.STATUS_FAILED
    }
    fun localUri(id: Long): Uri? {
        if (status(id) != DownloadManager.STATUS_SUCCESSFUL) return null
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return manager.getUriForDownloadedFile(id)
    }
    fun delete(id: Long) {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        manager.remove(id)
        val updated = JSONArray()
        items().filterNot { it.id == id }.forEach { updated.put(JSONObject().put("id", it.id).put("title", it.title).put("file", it.fileName)) }
        prefs.edit().putString("items", updated.toString()).apply()
    }
}
