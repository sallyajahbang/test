package com.sally.glues

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val ONLINE_PAGE_SIZE = 20

data class OnlineVideo(
    val id: String,
    val title: String,
    val author: String,
    val thumbUrl: String?,
    val videoUrl: String,
    val durationMs: Long
)

/** Sumber video online gratis. Setiap sumber butuh API key sendiri. */
interface OnlineSource {
    val id: String
    val label: String
    val keyHelp: String
    suspend fun search(query: String, page: Int, apiKey: String): List<OnlineVideo>
}

private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

/** Pexels Video API: https://api.pexels.com/v1/videos/search (lisensi bebas pakai, wajib kredit). */
object PexelsSource : OnlineSource {
    override val id = "pexels"
    override val label = "Pexels"
    override val keyHelp = "Daftar gratis di pexels.com/api untuk mendapatkan API key."

    override suspend fun search(query: String, page: Int, apiKey: String): List<OnlineVideo> {
        val url = "https://api.pexels.com/v1/videos/search?query=${enc(query)}" +
            "&per_page=$ONLINE_PAGE_SIZE&page=$page&orientation=portrait"
        val json = JSONObject(httpGetText(url, mapOf("Authorization" to apiKey)))
        val arr = json.optJSONArray("videos") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val v = arr.getJSONObject(i)
            val files = v.optJSONArray("video_files") ?: return@mapNotNull null
            // Ambil file mp4 dengan tinggi paling dekat ke 1080 px
            var best: JSONObject? = null
            for (j in 0 until files.length()) {
                val f = files.getJSONObject(j)
                if (f.optString("file_type") != "video/mp4") continue
                val cur = best
                if (cur == null || abs(f.optInt("height") - 1080) < abs(cur.optInt("height") - 1080)) {
                    best = f
                }
            }
            val link = best?.optString("link").orEmpty()
            if (link.isEmpty()) return@mapNotNull null
            val author = v.optJSONObject("user")?.optString("name").orEmpty()
            OnlineVideo(
                id = v.optLong("id").toString(),
                title = "Video oleh ${author.ifEmpty { "Pexels" }}",
                author = author,
                thumbUrl = v.optString("image").ifEmpty { null },
                videoUrl = link,
                durationMs = v.optLong("duration") * 1000L
            )
        }
    }
}

/** Pixabay Video API: https://pixabay.com/api/videos/ (Content License, wajib cache 24 jam). */
object PixabaySource : OnlineSource {
    override val id = "pixabay"
    override val label = "Pixabay"
    override val keyHelp = "Login di pixabay.com/api/docs untuk melihat API key gratis."

    override suspend fun search(query: String, page: Int, apiKey: String): List<OnlineVideo> {
        val url = "https://pixabay.com/api/videos/?key=${enc(apiKey)}&q=${enc(query)}" +
            "&per_page=$ONLINE_PAGE_SIZE&page=$page&safesearch=true"
        val json = JSONObject(httpGetText(url))
        val hits = json.optJSONArray("hits") ?: return emptyList()
        return (0 until hits.length()).mapNotNull { i ->
            val h = hits.getJSONObject(i)
            val videos = h.optJSONObject("videos") ?: return@mapNotNull null
            val pick = videos.optJSONObject("medium") ?: videos.optJSONObject("small")
                ?: return@mapNotNull null
            val link = pick.optString("url")
            if (link.isEmpty()) return@mapNotNull null
            val picId = h.optString("picture_id")
            OnlineVideo(
                id = h.optLong("id").toString(),
                title = h.optString("tags").ifEmpty { "Video Pixabay" },
                author = h.optString("user"),
                thumbUrl = if (picId.isNotEmpty()) "https://i.vimeocdn.com/video/${picId}_640x360.jpg" else null,
                videoUrl = link,
                durationMs = h.optLong("duration") * 1000L
            )
        }
    }
}

val ONLINE_SOURCES: List<OnlineSource> = listOf(PexelsSource, PixabaySource)

object OnlinePrefs {
    private const val PREFS = "glues_online"

    fun keyFor(context: Context, sourceId: String): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("key_$sourceId", "").orEmpty()

    fun saveKey(context: Context, sourceId: String, key: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("key_$sourceId", key.trim())
            .apply()
    }
}

private fun openConnection(url: String): HttpURLConnection =
    (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "Glues/1.0 (Android)")
    }

suspend fun httpGetText(url: String, headers: Map<String, String> = emptyMap()): String =
    withContext(Dispatchers.IO) {
        val conn = openConnection(url)
        try {
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

suspend fun httpGetBytes(url: String): ByteArray =
    withContext(Dispatchers.IO) {
        val conn = openConnection(url)
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

/** Unduh ke file sementara lalu rename, supaya file rusak tidak tertinggal. */
suspend fun downloadTo(url: String, target: File, onProgress: (Float) -> Unit) {
    withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, target.name + ".part")
        val conn = openConnection(url)
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    var n = input.read(buf)
                    while (n >= 0) {
                        out.write(buf, 0, n)
                        read += n
                        if (total > 0) onProgress(read.toFloat() / total)
                        n = input.read(buf)
                    }
                }
            }
            if (!part.renameTo(target)) error("Gagal menyimpan file")
        } catch (e: Exception) {
            part.delete()
            throw e
        } finally {
            conn.disconnect()
        }
    }
}
