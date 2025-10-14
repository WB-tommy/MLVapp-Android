package fm.forum.mlvapp

import android.content.Context
import android.util.Log
import fm.forum.mlvapp.NativeInterface.NativeLib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale

object FocusPixelManager {
    private const val TAG = "FocusPixelManager"
    private const val BASE_URL =
        "https://raw.githubusercontent.com/ilia3101/MLV-App/master/pixel_maps"
    private const val MAP_INDEX_URL =
        "https://api.github.com/repos/ilia3101/MLV-App/contents/pixel_maps"

    private data class MapEntry(
        val name: String,
        val downloadUrl: String
    )

    enum class DownloadAllResult {
        SUCCESS,
        NONE_FOR_CAMERA,
        INDEX_UNAVAILABLE,
        FAILED
    }

    fun ensureFocusPixelMap(
        context: Context,
        fileName: String
    ): Boolean {
        val destination = File(context.filesDir, fileName)
        return destination.exists() && destination.length() > 0
    }

    suspend fun downloadFocusPixelMap(
        context: Context,
        fileName: String
    ): Boolean {
        if (fileName.isBlank()) return false
        val destination = File(context.filesDir, fileName)
        val url = "$BASE_URL/$fileName"
        val result = downloadFile(url, destination)
        if (!result) {
            Log.w(TAG, "Failed to download focus pixel map $fileName from $url")
        }
        return result && ensureFocusPixelMap(context, fileName)
    }

    suspend fun downloadFocusPixelMapsForCamera(
        context: Context,
        cameraIdRaw: String
    ): DownloadAllResult {
        val cameraId = cameraIdRaw.uppercase(Locale.ROOT)
        val entries = fetchMapEntries()
        if (entries.isEmpty()) {
            Log.i(TAG, "Focus pixel map index unavailable.")
            return DownloadAllResult.INDEX_UNAVAILABLE
        }
        val matchingEntries = entries.filter { entry ->
            entry.name.uppercase(Locale.ROOT).startsWith(cameraId)
        }
        if (matchingEntries.isEmpty()) {
            Log.i(TAG, "No focus pixel maps found for camera $cameraId")
            return DownloadAllResult.NONE_FOR_CAMERA
        }
        var downloaded = false
        matchingEntries.forEach { entry ->
            val destination = File(context.filesDir, entry.name)
            val success = downloadFile(entry.downloadUrl, destination)
            if (!success) {
                Log.w(TAG, "Failed to download focus pixel map ${entry.name}")
                return DownloadAllResult.FAILED
            }
            downloaded = true
        }
        return if (downloaded) DownloadAllResult.SUCCESS else DownloadAllResult.FAILED
    }

    private suspend fun fetchMapEntries(): List<MapEntry> = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(MAP_INDEX_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                setRequestProperty("User-Agent", "MLVApp-Android")
            }
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "Failed to fetch focus pixel map index: HTTP $responseCode")
                return@withContext emptyList()
            }
            val payload = connection.inputStream.use { input ->
                InputStreamReader(input, StandardCharsets.UTF_8).use { reader ->
                    reader.readText()
                }
            }
            val array = JSONArray(payload)
            val results = ArrayList<MapEntry>(array.length())
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val name = item.optString("name").orEmpty()
                val downloadUrl = item.optString("download_url").orEmpty()
                if (name.isNotEmpty() && downloadUrl.isNotEmpty()) {
                    results.add(MapEntry(name, downloadUrl))
                }
            }
            results
        } catch (ioe: IOException) {
            Log.w(TAG, "Error while fetching focus pixel map index", ioe)
            emptyList()
        } finally {
            connection?.disconnect()
        }
    }

    private suspend fun downloadFile(url: String, destination: File): Boolean =
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                destination.parentFile?.mkdirs()
                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 20_000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "MLVApp-Android")
                }
                val responseCode = connection.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    if (responseCode == HttpURLConnection.HTTP_NOT_FOUND) {
                        Log.i(TAG, "Focus pixel map not found at $url")
                        return@withContext false
                    }
                    throw IOException("HTTP $responseCode while downloading $url")
                }

                connection.inputStream.use { input ->
                    destination.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                Log.i(TAG, "Downloaded focus pixel map to ${destination.absolutePath}")
                true
            } catch (ioe: IOException) {
                Log.w(TAG, "Failed to download focus pixel map from $url", ioe)
                if (destination.exists()) {
                    destination.delete()
                }
                false
            } finally {
                connection?.disconnect()
            }
        }
}
