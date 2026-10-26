package com.example.photodedup

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.Coil
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

// --- DATA MODELS ---

enum class SortOption(val label: String) {
    DATE_DESC("Newest First"),
    DATE_ASC("Oldest First"),
    SIZE_DESC("Largest First"),
    SIZE_ASC("Smallest First")
}

data class PhotoItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val dateAdded: Long, // Epoch milliseconds (DATE_TAKEN fallback to DATE_MODIFIED/DATE_ADDED)
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val dHash: Long,
    val sha256: String = "",
    val isVideo: Boolean = false,
    val durationMs: Long = 0L,
    val mimeType: String = "",
    val markedForDelete: Boolean = false // false = Green (Keep), true = Red (Delete)
)

data class SimilarGroup(
    val id: Int,
    val title: String,
    val isExactDuplicate: Boolean,
    val photos: List<PhotoItem>,
    val sortOption: SortOption = SortOption.SIZE_DESC
)

data class AppUpdateInfo(
    val latestVersion: String,
    val releaseNotes: String,
    val downloadUrl: String
)

data class UiState(
    val isScanning: Boolean = false,
    val scanProgress: Float = 0f,
    val scannedCount: Int = 0,
    val totalCount: Int = 0,
    val scanPhaseText: String = "Scanning...",
    val groups: List<SimilarGroup> = emptyList(),
    val globalSort: SortOption = SortOption.SIZE_DESC,
    val similarityThreshold: Int = 8, // Max Hamming distance
    val activeModalGroup: SimilarGroup? = null,
    val activeModalInitialIndex: Int = 0,
    val isCacheLoaded: Boolean = false,
    val isDarkMode: Boolean = true,
    val availableUpdate: AppUpdateInfo? = null,
    val isCheckingUpdate: Boolean = false,
    val deleteSuccessMessage: String? = null
)

// --- PERSISTENCE: SCAN CACHE MANAGER ---

object ScanCacheManager {
    // shortcut: JSON file cache in internal filesDir, upgrade to Room/DataStore if dataset exceeds 50k items
    private const val FILE_NAME = "dupe_scan_cache.json"

    fun save(context: Context, state: UiState) {
        try {
            val root = JSONObject()
            root.put("scannedCount", state.scannedCount)
            root.put("totalCount", state.totalCount)
            root.put("globalSort", state.globalSort.name)
            root.put("isDarkMode", state.isDarkMode)

            val groupsArray = JSONArray()
            state.groups.forEach { group ->
                val gObj = JSONObject()
                gObj.put("id", group.id)
                gObj.put("title", group.title)
                gObj.put("isExactDuplicate", group.isExactDuplicate)
                gObj.put("sortOption", group.sortOption.name)

                val photosArray = JSONArray()
                group.photos.forEach { p ->
                    val pObj = JSONObject()
                    pObj.put("id", p.id)
                    pObj.put("uri", p.uri.toString())
                    pObj.put("name", p.name)
                    pObj.put("dateAdded", p.dateAdded)
                    pObj.put("sizeBytes", p.sizeBytes)
                    pObj.put("width", p.width)
                    pObj.put("height", p.height)
                    pObj.put("dHash", p.dHash)
                    pObj.put("sha256", p.sha256)
                    pObj.put("isVideo", p.isVideo)
                    pObj.put("durationMs", p.durationMs)
                    pObj.put("mimeType", p.mimeType)
                    pObj.put("markedForDelete", p.markedForDelete)
                    photosArray.put(pObj)
                }
                gObj.put("photos", photosArray)
                groupsArray.put(gObj)
            }
            root.put("groups", groupsArray)

            File(context.filesDir, FILE_NAME).writeText(root.toString())
        } catch (e: Exception) {
            // Non-critical cache failure
        }
    }

    fun load(context: Context): Triple<List<SimilarGroup>, Int, Int>? {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return null
        return try {
            val root = JSONObject(file.readText())
            val scannedCount = root.optInt("scannedCount", 0)
            val totalCount = root.optInt("totalCount", 0)
            val groupsArray = root.getJSONArray("groups")
            val groups = mutableListOf<SimilarGroup>()

            for (i in 0 until groupsArray.length()) {
                val gObj = groupsArray.getJSONObject(i)
                val id = gObj.getInt("id")
                val title = gObj.getString("title")
                val isExactDuplicate = gObj.getBoolean("isExactDuplicate")
                val sortOption = try {
                    SortOption.valueOf(gObj.optString("sortOption", SortOption.SIZE_DESC.name))
                } catch (e: Exception) {
                    SortOption.SIZE_DESC
                }

                val photosArray = gObj.getJSONArray("photos")
                val photos = mutableListOf<PhotoItem>()
                for (j in 0 until photosArray.length()) {
                    val pObj = photosArray.getJSONObject(j)
                    val pId = pObj.getLong("id")
                    val uri = Uri.parse(pObj.getString("uri"))
                    val name = pObj.getString("name")
                    val rawDate = pObj.getLong("dateAdded")
                    // shortcut: timestamps < 10B are seconds from previous versions, upgrade to millis
                    val dateAdded = if (rawDate in 1 until 10_000_000_000L) rawDate * 1000L else rawDate
                    val sizeBytes = pObj.getLong("sizeBytes")
                    val width = pObj.getInt("width")
                    val height = pObj.getInt("height")
                    val dHash = pObj.getLong("dHash")
                    val sha256 = pObj.optString("sha256", "")
                    val isVideo = pObj.optBoolean("isVideo", false)
                    val durationMs = pObj.optLong("durationMs", 0L)
                    val mimeType = pObj.optString("mimeType", "")
                    val markedForDelete = pObj.getBoolean("markedForDelete")

                    photos.add(
                        PhotoItem(
                            pId, uri, name, dateAdded, sizeBytes, width, height, dHash,
                            sha256, isVideo, durationMs, mimeType, markedForDelete
                        )
                    )
                }
                if (photos.size > 1) {
                    groups.add(SimilarGroup(id, title, isExactDuplicate, photos, sortOption))
                }
            }
            if (groups.isNotEmpty()) Triple(groups, scannedCount, totalCount) else null
        } catch (e: Exception) {
            null
        }
    }

    fun isDark(context: Context): Boolean {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return true
        return try {
            JSONObject(file.readText()).optBoolean("isDarkMode", true)
        } catch (e: Exception) {
            true
        }
    }

    fun clear(context: Context) {
        try {
            File(context.filesDir, FILE_NAME).delete()
        } catch (e: Exception) {
            // Ignore
        }
    }
}

// --- PERCEPTUAL HASH (dHash) & SHA-256 ENGINE ---

object PerceptualHasher {
    /**
     * Computes a 64-bit Difference Hash (dHash) from a Bitmap.
     * Resizes to 9x8 grayscale and compares adjacent horizontal pixels.
     */
    fun computeDHash(bitmap: Bitmap): Long {
        val scaled = Bitmap.createScaledBitmap(bitmap, 9, 8, true)
        var hash = 0L
        var bitIndex = 0
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                val leftPixel = scaled.getPixel(x, y)
                val rightPixel = scaled.getPixel(x + 1, y)
                val leftLum = luminance(leftPixel)
                val rightLum = luminance(rightPixel)
                if (leftLum > rightLum) {
                    hash = hash or (1L shl bitIndex)
                }
                bitIndex++
            }
        }
        if (scaled != bitmap) scaled.recycle()
        return hash
    }

    private fun luminance(color: Int): Int {
        val r = AndroidColor.red(color)
        val g = AndroidColor.green(color)
        val b = AndroidColor.blue(color)
        return (0.299 * r + 0.587 * g + 0.114 * b).toInt()
    }

    fun hammingDistance(hash1: Long, hash2: Long): Int {
        return java.lang.Long.bitCount(hash1 xor hash2)
    }

    fun computeSha256(contentResolver: ContentResolver, uri: Uri): String {
        return try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalBytes = 0
                // shortcut: reads first 256KB for fast fingerprinting, upgrade to full streaming hash if collision on first 256KB occurs
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                    totalBytes += bytesRead
                    if (totalBytes >= 256 * 1024) break
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            } ?: ""
        } catch (e: Exception) {
            ""
        }
    }
}

// --- VIEWMODEL ---

class DedupViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun loadCacheIfAvailable(context: Context) {
        val savedDark = ScanCacheManager.isDark(context)
        _uiState.update { it.copy(isDarkMode = savedDark) }
        checkForUpdates(context, showToastIfLatest = false)

        if (_uiState.value.isCacheLoaded || _uiState.value.groups.isNotEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val cached = ScanCacheManager.load(context)
            if (cached != null) {
                val sortedGroups = sortGroupsList(cached.first, _uiState.value.globalSort)
                _uiState.update {
                    it.copy(
                        groups = sortedGroups,
                        scannedCount = cached.second,
                        totalCount = cached.third,
                        isCacheLoaded = true
                    )
                }
            }
        }
    }

    fun toggleTheme(context: Context) {
        _uiState.update { it.copy(isDarkMode = !it.isDarkMode) }
        ScanCacheManager.save(context, _uiState.value)
    }

    fun hideGroup(context: Context, groupId: Int) {
        _uiState.update { state ->
            val updated = state.groups.filterNot { it.id == groupId }
            state.copy(groups = updated)
        }
        ScanCacheManager.save(context, _uiState.value)
    }

    fun clearDeleteMessage() {
        _uiState.update { it.copy(deleteSuccessMessage = null) }
    }

    fun dismissUpdateDialog() {
        _uiState.update { it.copy(availableUpdate = null) }
    }

    fun checkForUpdates(context: Context, showToastIfLatest: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isCheckingUpdate = true) }
            try {
                // shortcut: direct GitHub API releases query, upgrade to OAuth if rate limiting becomes an issue
                val url = URL("https://api.github.com/repos/devilmancrybabyx/Duplicate-photos-cleaner/releases/latest")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("User-Agent", "DupeCleanerApp")
                }
                if (conn.responseCode == 200) {
                    val text = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(text)
                    val tagName = json.getString("tag_name")
                    val notes = json.optString("body", "Performance improvements and new features.")
                    val assets = json.optJSONArray("assets")
                    var downloadUrl: String? = null
                    if (assets != null) {
                        for (i in 0 until assets.length()) {
                            val a = assets.getJSONObject(i)
                            val name = a.getString("name")
                            if (name.endsWith("-release.apk") || name.endsWith(".apk")) {
                                downloadUrl = a.getString("browser_download_url")
                                if (name.contains("release")) break
                            }
                        }
                    }

                    val currentTag = "v1.1.5"
                    if (tagName != currentTag && downloadUrl != null) {
                        _uiState.update {
                            it.copy(
                                isCheckingUpdate = false,
                                availableUpdate = AppUpdateInfo(tagName, notes, downloadUrl)
                            )
                        }
                    } else {
                        _uiState.update { it.copy(isCheckingUpdate = false) }
                        if (showToastIfLatest) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "Dupe cleaner is up to date ($currentTag)", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                } else {
                    _uiState.update { it.copy(isCheckingUpdate = false) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isCheckingUpdate = false) }
            }
        }
    }

    fun clearCache(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            ScanCacheManager.clear(context)
            _uiState.update { UiState() }
        }
    }

    fun scanDevicePhotos(context: Context) {
        if (_uiState.value.isScanning) return
        val contentResolver = context.contentResolver

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isScanning = true,
                    scanProgress = 0f,
                    scannedCount = 0,
                    totalCount = 0,
                    scanPhaseText = "Discovering photos & videos...",
                    groups = emptyList()
                )
            }

            val rawMedia = withContext(Dispatchers.IO) {
                val list = mutableListOf<PhotoItem>()

                // 1. Image projection & cursor
                val imageProjection = arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.DATE_TAKEN,
                    MediaStore.Images.Media.DATE_MODIFIED,
                    MediaStore.Images.Media.DATE_ADDED,
                    MediaStore.Images.Media.SIZE,
                    MediaStore.Images.Media.WIDTH,
                    MediaStore.Images.Media.HEIGHT,
                    MediaStore.Images.Media.MIME_TYPE
                )
                val imageOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC, ${MediaStore.Images.Media.DATE_MODIFIED} DESC, ${MediaStore.Images.Media.DATE_ADDED} DESC"

                // 2. Video projection & cursor
                val videoProjection = arrayOf(
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.DATE_TAKEN,
                    MediaStore.Video.Media.DATE_MODIFIED,
                    MediaStore.Video.Media.DATE_ADDED,
                    MediaStore.Video.Media.SIZE,
                    MediaStore.Video.Media.WIDTH,
                    MediaStore.Video.Media.HEIGHT,
                    MediaStore.Video.Media.DURATION,
                    MediaStore.Video.Media.MIME_TYPE
                )
                val videoOrder = "${MediaStore.Video.Media.DATE_TAKEN} DESC, ${MediaStore.Video.Media.DATE_MODIFIED} DESC, ${MediaStore.Video.Media.DATE_ADDED} DESC"

                val imageCursor = try {
                    contentResolver.query(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        imageProjection, null, null, imageOrder
                    )
                } catch (e: Exception) {
                    null
                }

                val videoCursor = try {
                    contentResolver.query(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        videoProjection, null, null, videoOrder
                    )
                } catch (e: Exception) {
                    null
                }

                val totalImages = imageCursor?.count ?: 0
                val totalVideos = videoCursor?.count ?: 0
                val grandTotal = totalImages + totalVideos

                _uiState.update {
                    it.copy(
                        totalCount = grandTotal,
                        scannedCount = 0,
                        scanProgress = 0f,
                        scanPhaseText = "Reading gallery media..."
                    )
                }

                var processed = 0

                // Process Images
                imageCursor?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                    val takenCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
                    val modCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED)
                    val addedCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                    val widthCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                    val heightCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
                    val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val name = cursor.getString(nameCol) ?: "Image_$id"
                        val taken = if (takenCol >= 0) cursor.getLong(takenCol) else 0L
                        val mod = if (modCol >= 0) cursor.getLong(modCol) else 0L
                        val added = if (addedCol >= 0) cursor.getLong(addedCol) else 0L

                        val realDateMillis = when {
                            taken > 0L -> taken // DATE_TAKEN is in milliseconds
                            mod > 0L -> mod * 1000L // DATE_MODIFIED is in seconds -> to millis
                            added > 0L -> added * 1000L // DATE_ADDED is in seconds -> to millis
                            else -> System.currentTimeMillis()
                        }

                        val size = cursor.getLong(sizeCol)
                        val w = cursor.getInt(widthCol)
                        val h = cursor.getInt(heightCol)
                        val mime = cursor.getString(mimeCol) ?: "image/*"
                        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

                        try {
                            val thumb = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                contentResolver.loadThumbnail(uri, Size(64, 64), null)
                            } else {
                                @Suppress("DEPRECATION")
                                MediaStore.Images.Thumbnails.getThumbnail(
                                    contentResolver, id, MediaStore.Images.Thumbnails.MINI_KIND, null
                                )
                            }
                            if (thumb != null) {
                                val dHash = PerceptualHasher.computeDHash(thumb)
                                thumb.recycle()
                                list.add(PhotoItem(id, uri, name, realDateMillis, size, w, h, dHash, isVideo = false, mimeType = mime))
                            }
                        } catch (e: Exception) {
                            // Fallback pseudo-hash based on dimensions & size if thumbnail cannot be generated
                            val pseudoHash = (w.toLong() shl 32) xor (h.toLong() shl 16) xor size
                            list.add(PhotoItem(id, uri, name, realDateMillis, size, w, h, pseudoHash, isVideo = false, mimeType = mime))
                        }

                        processed++
                        if (processed % 10 == 0 || processed == grandTotal) {
                            val progressVal = if (grandTotal > 0) processed.toFloat() / grandTotal else 0f
                            _uiState.update {
                                it.copy(
                                    scannedCount = processed,
                                    scanProgress = progressVal,
                                    scanPhaseText = "Analyzing photos ($processed / $grandTotal)..."
                                )
                            }
                        }
                    }
                }

                // Process Videos (Fast duration & size fingerprinting to avoid heavy synchronous thumbnailing)
                videoCursor?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                    val takenCol = cursor.getColumnIndex(MediaStore.Video.Media.DATE_TAKEN)
                    val modCol = cursor.getColumnIndex(MediaStore.Video.Media.DATE_MODIFIED)
                    val addedCol = cursor.getColumnIndex(MediaStore.Video.Media.DATE_ADDED)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                    val widthCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
                    val heightCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
                    val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                    val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val name = cursor.getString(nameCol) ?: "Video_$id"
                        val taken = if (takenCol >= 0) cursor.getLong(takenCol) else 0L
                        val mod = if (modCol >= 0) cursor.getLong(modCol) else 0L
                        val added = if (addedCol >= 0) cursor.getLong(addedCol) else 0L

                        val realDateMillis = when {
                            taken > 0L -> taken // DATE_TAKEN is in milliseconds
                            mod > 0L -> mod * 1000L // DATE_MODIFIED is in seconds -> to millis
                            added > 0L -> added * 1000L // DATE_ADDED is in seconds -> to millis
                            else -> System.currentTimeMillis()
                        }

                        val size = cursor.getLong(sizeCol)
                        val w = cursor.getInt(widthCol)
                        val h = cursor.getInt(heightCol)
                        val duration = cursor.getLong(durationCol)
                        val mime = cursor.getString(mimeCol) ?: "video/*"
                        val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)

                        // Compute video fingerprint from duration and size (avoids blocking slow video frame decoders)
                        val videoHash = (duration shl 32) xor size
                        list.add(
                            PhotoItem(
                                id = id,
                                uri = uri,
                                name = name,
                                dateAdded = realDateMillis,
                                sizeBytes = size,
                                width = w,
                                height = h,
                                dHash = videoHash,
                                isVideo = true,
                                durationMs = duration,
                                mimeType = mime
                            )
                        )

                        processed++
                        if (processed % 5 == 0 || processed == grandTotal) {
                            val progressVal = if (grandTotal > 0) processed.toFloat() / grandTotal else 0f
                            _uiState.update {
                                it.copy(
                                    scannedCount = processed,
                                    scanProgress = progressVal,
                                    scanPhaseText = "Analyzing videos ($processed / $grandTotal)..."
                                )
                            }
                        }
                    }
                }

                list
            }

            _uiState.update {
                it.copy(
                    scanProgress = 1f,
                    scannedCount = rawMedia.size,
                    scanPhaseText = "Matching duplicates & clustering..."
                )
            }

            // Cluster into Duplicate & Similar Groups
            val clusteredGroups = withContext(Dispatchers.Default) {
                val threshold = _uiState.value.similarityThreshold
                val visited = BooleanArray(rawMedia.size)
                val resultGroups = mutableListOf<SimilarGroup>()
                var groupIdCounter = 1

                for (i in rawMedia.indices) {
                    if (visited[i]) continue
                    val base = rawMedia[i]
                    val currentCluster = mutableListOf(base)
                    visited[i] = true
                    var allExact = true

                    val baseRatio = if (base.height > 0) base.width.toFloat() / base.height else 1f

                    for (j in i + 1 until rawMedia.size) {
                        if (visited[j]) continue
                        val candidate = rawMedia[j]

                        // Separate photos and videos
                        if (base.isVideo != candidate.isVideo) continue

                        // Check aspect ratio for photos
                        if (!base.isVideo) {
                            val candRatio = if (candidate.height > 0) candidate.width.toFloat() / candidate.height else 1f
                            if (abs(baseRatio - candRatio) > 0.35f) continue
                        } else {
                            // For videos, duration must be within 1 second
                            if (abs(base.durationMs - candidate.durationMs) > 1000L) continue
                        }

                        val dist = if (!base.isVideo) {
                            PerceptualHasher.hammingDistance(base.dHash, candidate.dHash)
                        } else {
                            if (base.durationMs == candidate.durationMs && base.sizeBytes == candidate.sizeBytes) 0 else 20
                        }

                        // Similar or exact match check
                        if (dist <= threshold) {
                            visited[j] = true
                            currentCluster.add(candidate)

                            val isExactMatch = if (dist == 0 && base.sizeBytes == candidate.sizeBytes) {
                                val baseHash = base.sha256.ifEmpty {
                                    PerceptualHasher.computeSha256(contentResolver, base.uri)
                                }
                                val candidateHash = candidate.sha256.ifEmpty {
                                    PerceptualHasher.computeSha256(contentResolver, candidate.uri)
                                }
                                baseHash.isNotEmpty() && baseHash == candidateHash
                            } else {
                                false
                            }

                            if (!isExactMatch) {
                                allExact = false
                            }
                        }
                    }

                    if (currentCluster.size > 1) {
                        val sortedCluster = sortPhotosList(currentCluster, _uiState.value.globalSort)
                        // Smart Auto-Mark: Keep first photo (Green) and mark rest for deletion (Red)
                        val smartMarked = sortedCluster.mapIndexed { idx, item ->
                            item.copy(markedForDelete = idx > 0)
                        }
                        resultGroups.add(
                            SimilarGroup(
                                id = groupIdCounter++,
                                title = if (allExact) "Exact Duplicates" else "Similar Media",
                                isExactDuplicate = allExact,
                                photos = smartMarked,
                                sortOption = _uiState.value.globalSort
                            )
                        )
                    }
                }
                sortGroupsList(resultGroups, _uiState.value.globalSort)
            }

            _uiState.update {
                it.copy(isScanning = false, groups = clusteredGroups, isCacheLoaded = true)
            }

            // Persist scan cache to storage
            ScanCacheManager.save(context, _uiState.value)
        }
    }

    private fun sortPhotosList(photos: List<PhotoItem>, option: SortOption): List<PhotoItem> {
        return when (option) {
            SortOption.DATE_DESC -> photos.sortedByDescending { it.dateAdded }
            SortOption.DATE_ASC -> photos.sortedBy { it.dateAdded }
            SortOption.SIZE_DESC -> photos.sortedByDescending { it.sizeBytes }
            SortOption.SIZE_ASC -> photos.sortedBy { it.sizeBytes }
        }
    }

    private fun sortGroupsList(groups: List<SimilarGroup>, option: SortOption): List<SimilarGroup> {
        val groupsWithSortedPhotos = groups.map { group ->
            group.copy(
                sortOption = option,
                photos = sortPhotosList(group.photos, option)
            )
        }
        return when (option) {
            SortOption.DATE_DESC -> groupsWithSortedPhotos.sortedByDescending { g ->
                g.photos.maxOfOrNull { it.dateAdded } ?: 0L
            }
            SortOption.DATE_ASC -> groupsWithSortedPhotos.sortedBy { g ->
                g.photos.minOfOrNull { it.dateAdded } ?: Long.MAX_VALUE
            }
            SortOption.SIZE_DESC -> groupsWithSortedPhotos.sortedByDescending { g ->
                g.photos.sumOf { it.sizeBytes }
            }
            SortOption.SIZE_ASC -> groupsWithSortedPhotos.sortedBy { g ->
                g.photos.sumOf { it.sizeBytes }
            }
        }
    }

    fun togglePhotoStatus(context: Context, groupId: Int, photoId: Long) {
        _uiState.update { state ->
            val updatedGroups = state.groups.map { group ->
                if (group.id == groupId) {
                    group.copy(
                        photos = group.photos.map { photo ->
                            if (photo.id == photoId) photo.copy(markedForDelete = !photo.markedForDelete)
                            else photo
                        }
                    )
                } else group
            }
            val updatedModal = updatedGroups.find { it.id == state.activeModalGroup?.id }
            state.copy(groups = updatedGroups, activeModalGroup = updatedModal)
        }
        ScanCacheManager.save(context, _uiState.value)
    }

    fun sortGroup(context: Context? = null, groupId: Int, sortOption: SortOption) {
        _uiState.update { state ->
            val updatedGroups = state.groups.map { group ->
                if (group.id == groupId) {
                    group.copy(
                        sortOption = sortOption,
                        photos = sortPhotosList(group.photos, sortOption)
                    )
                } else group
            }
            state.copy(groups = updatedGroups)
        }
        context?.let { ScanCacheManager.save(it, _uiState.value) }
    }

    fun sortAllGroups(context: Context? = null, sortOption: SortOption) {
        _uiState.update { state ->
            val sortedGroups = sortGroupsList(state.groups, sortOption)
            state.copy(globalSort = sortOption, groups = sortedGroups)
        }
        context?.let { ScanCacheManager.save(it, _uiState.value) }
    }

    fun setGroupSelection(context: Context, groupId: Int, markDeleteAll: Boolean, keepBestOne: Boolean = false) {
        _uiState.update { state ->
            val updatedGroups = state.groups.map { group ->
                if (group.id == groupId) {
                    group.copy(
                        photos = group.photos.mapIndexed { index, photo ->
                            val shouldDelete = if (keepBestOne) index > 0 else markDeleteAll
                            photo.copy(markedForDelete = shouldDelete)
                        }
                    )
                } else group
            }
            state.copy(groups = updatedGroups)
        }
        ScanCacheManager.save(context, _uiState.value)
    }

    fun setGlobalSelection(context: Context, markDeleteAll: Boolean, keepBestInEachGroup: Boolean = false) {
        _uiState.update { state ->
            val updatedGroups = state.groups.map { group ->
                group.copy(
                    photos = group.photos.mapIndexed { index, photo ->
                        val shouldDelete = if (keepBestInEachGroup) index > 0 else markDeleteAll
                        photo.copy(markedForDelete = shouldDelete)
                    }
                )
            }
            state.copy(groups = updatedGroups)
        }
        ScanCacheManager.save(context, _uiState.value)
    }

    fun openFullScreen(group: SimilarGroup, initialIndex: Int) {
        _uiState.update { it.copy(activeModalGroup = group, activeModalInitialIndex = initialIndex) }
    }

    fun closeFullScreen() {
        _uiState.update { it.copy(activeModalGroup = null) }
    }

    fun removeDeletedPhotosFromState(context: Context, deletedIds: Set<Long>) {
        var freedBytes = 0L
        _uiState.update { state ->
            val cleanedGroups = state.groups.mapNotNull { group ->
                val toDelete = group.photos.filter { it.id in deletedIds }
                freedBytes += toDelete.sumOf { it.sizeBytes }
                val remaining = group.photos.filterNot { it.id in deletedIds }
                if (remaining.size > 1) group.copy(photos = remaining) else null
            }
            state.copy(
                groups = cleanedGroups,
                activeModalGroup = null,
                deleteSuccessMessage = "Cleaned ${deletedIds.size} items (${formatFileSize(freedBytes)}) successfully!"
            )
        }
        ScanCacheManager.save(context, _uiState.value)
    }
}

fun downloadAndInstallApk(context: Context, downloadUrl: String, versionTag: String) {
    try {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val fileName = "DupeCleaner-$versionTag-release.apk"
        val request = DownloadManager.Request(Uri.parse(downloadUrl)).apply {
            setTitle("Dupe Cleaner $versionTag")
            setDescription("Downloading latest release APK...")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            setMimeType("application/vnd.android.package-archive")
        }
        dm.enqueue(request)
        Toast.makeText(context, "Downloading update in background... Check notifications to install", Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(browserIntent)
    }
}

// --- MAIN ACTIVITY & UI ---

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Configure Coil with Video Frame Decoder and GIF Decoder
        val imageLoader = ImageLoader.Builder(this)
            .components {
                add(VideoFrameDecoder.Factory())
                if (Build.VERSION.SDK_INT >= 28) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .crossfade(true)
            .build()
        Coil.setImageLoader(imageLoader)

        setContent {
            val viewModel: DedupViewModel = viewModel()
            val state by viewModel.uiState.collectAsState()

            val darkColors = darkColorScheme(
                primary = Color(0xFF4CAF50),
                secondary = Color(0xFF03DAC6),
                background = Color(0xFF121212),
                surface = Color(0xFF1E1E1E),
                surfaceVariant = Color(0xFF252525),
                onBackground = Color.White,
                onSurface = Color.White,
                error = Color(0xFFE53935)
            )
            val lightColors = lightColorScheme(
                primary = Color(0xFF2E7D32),
                secondary = Color(0xFF00796B),
                background = Color(0xFFF7F9FA),
                surface = Color(0xFFFFFFFF),
                surfaceVariant = Color(0xFFEEEEEE),
                onBackground = Color(0xFF1C1B1F),
                onSurface = Color(0xFF1C1B1F),
                error = Color(0xFFD32F2F)
            )

            MaterialTheme(colorScheme = if (state.isDarkMode) darkColors else lightColors) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    DedupAppScreen(viewModel = viewModel)
                }
            }
        }
    }
}

fun openGitHubProfile(context: Context) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/devilmancrybabyx"))
        context.startActivity(intent)
    } catch (e: Exception) {
        // Fallback
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DedupAppScreen(viewModel: DedupViewModel = viewModel()) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    // Auto-load scan cache when starting up
    LaunchedEffect(Unit) {
        viewModel.loadCacheIfAvailable(context)
    }

    val permissionsToRequest = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.values.any { it }
        if (granted) {
            viewModel.scanDevicePhotos(context)
        }
    }

    val markedPhotos = remember(state.groups) {
        state.groups.flatMap { it.photos }.filter { it.markedForDelete }
    }

    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.removeDeletedPhotosFromState(context, markedPhotos.map { it.id }.toSet())
        }
    }

    fun triggerBatchDelete() {
        if (markedPhotos.isEmpty()) return
        val uris = markedPhotos.map { it.uri }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val pendingIntent = MediaStore.createDeleteRequest(context.contentResolver, uris)
            deleteLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
        } else {
            uris.forEach { context.contentResolver.delete(it, null, null) }
            viewModel.removeDeletedPhotosFromState(context, markedPhotos.map { it.id }.toSet())
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = Color(0xFF1E1E1E),
                modifier = Modifier.width(310.dp)
            ) {
                Spacer(Modifier.height(16.dp))
                // Drawer Header
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(60.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF2E3B2E)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.PhotoLibrary,
                            contentDescription = null,
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Dupe cleaner",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text("Version 1.1.5", fontSize = 12.sp, color = Color.Gray)
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = Color(0xFF2C2C2C))

                // Stats Section
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Text("Scan Statistics", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF03DAC6))
                    Spacer(Modifier.height(8.dp))
                    Text("Groups detected: ${state.groups.size}", fontSize = 13.sp, color = Color.LightGray)
                    Text(
                        "Recoverable storage: ${formatFileSize(markedPhotos.sumOf { it.sizeBytes })}",
                        fontSize = 13.sp,
                        color = Color.LightGray
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = Color(0xFF2C2C2C))

                // Actions
                NavigationDrawerItem(
                    icon = {
                        Icon(
                            if (state.isDarkMode) Icons.Default.LightMode else Icons.Default.DarkMode,
                            contentDescription = null,
                            tint = Color(0xFFFFB300)
                        )
                    },
                    label = { Text(if (state.isDarkMode) "Switch to Light Mode" else "Switch to Dark Mode") },
                    selected = false,
                    onClick = {
                        viewModel.toggleTheme(context)
                    },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )

                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Refresh, contentDescription = null, tint = Color(0xFF4CAF50)) },
                    label = { Text("Rescan Media Gallery") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        permissionLauncher.launch(permissionsToRequest)
                    },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )

                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = Color(0xFF03DAC6)) },
                    label = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Check for Updates")
                            if (state.availableUpdate != null) {
                                Spacer(Modifier.width(8.dp))
                                Badge(containerColor = Color(0xFFE53935)) {
                                    Text("NEW", color = Color.White, fontSize = 10.sp)
                                }
                            }
                        }
                    },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        viewModel.checkForUpdates(context, showToastIfLatest = true)
                    },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )

                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = Color(0xFFE53935)) },
                    label = { Text("Clear Scan Memory Cache") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        viewModel.clearCache(context)
                    },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )

                Spacer(modifier = Modifier.weight(1f))

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = Color(0xFF2C2C2C))

                // Contact Section with Official GitHub Logo
                Surface(
                    color = Color(0xFF252525),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { openGitHubProfile(context) }
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF161B22)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_github),
                                contentDescription = "GitHub",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("devilmancrybabyx", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.White)
                            Text("View GitHub Profile", fontSize = 11.sp, color = Color(0xFF03DAC6))
                        }
                    }
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        if (state.groups.isNotEmpty()) {
                            Text(
                                "${state.groups.size} groups • ${markedPhotos.size} to delete",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Open Drawer")
                        }
                    },
                    actions = {
                        // Light and Dark Mode Toggle Button (Requirement 5)
                        IconButton(onClick = { viewModel.toggleTheme(context) }) {
                            Icon(
                                if (state.isDarkMode) Icons.Default.LightMode else Icons.Default.DarkMode,
                                contentDescription = "Toggle Light/Dark Mode",
                                tint = if (state.isDarkMode) Color(0xFFFFB300) else Color(0xFF1976D2)
                            )
                        }

                        // Global Sort Menu
                        var sortMenuExpanded by remember { mutableStateOf(false) }
                        IconButton(onClick = { sortMenuExpanded = true }) {
                            Icon(Icons.Default.Sort, contentDescription = "Global Sort")
                        }
                        DropdownMenu(
                            expanded = sortMenuExpanded,
                            onDismissRequest = { sortMenuExpanded = false }
                        ) {
                            Text(
                                "Global Sort (All Groups)",
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                fontSize = 12.sp,
                                color = Color.Gray
                            )
                            SortOption.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.label) },
                                    onClick = {
                                        viewModel.sortAllGroups(context, option)
                                        sortMenuExpanded = false
                                    }
                                )
                            }
                        }

                        // Rescan Button
                        IconButton(onClick = { permissionLauncher.launch(permissionsToRequest) }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Rescan")
                        }
                    }
                )
            },
            bottomBar = {
                if (state.groups.isNotEmpty()) {
                    val totalBytesToFree = markedPhotos.sumOf { it.sizeBytes }
                    Surface(
                        tonalElevation = 8.dp,
                        color = Color(0xFF1A1A1A),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            // Global Selection Controls Bar
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { viewModel.setGlobalSelection(context, markDeleteAll = false, keepBestInEachGroup = true) },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp)
                                ) {
                                    Text("Smart Keep", fontSize = 11.sp)
                                }
                                OutlinedButton(
                                    onClick = { viewModel.setGlobalSelection(context, markDeleteAll = true) },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp)
                                ) {
                                    Text("Delete All", fontSize = 11.sp)
                                }
                                OutlinedButton(
                                    onClick = { viewModel.setGlobalSelection(context, markDeleteAll = false) },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp)
                                ) {
                                    Text("Keep All", fontSize = 11.sp)
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Button(
                                onClick = {
                                    if (markedPhotos.isNotEmpty()) {
                                        showDeleteConfirmDialog = true
                                    }
                                },
                                enabled = markedPhotos.isNotEmpty(),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935)),
                                modifier = Modifier.fillMaxWidth().height(50.dp)
                            ) {
                                Icon(Icons.Default.DeleteForever, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Delete ${markedPhotos.size} Items (${formatFileSize(totalBytesToFree)})",
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
            }
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                when {
                    state.isScanning -> {
                        ScanningAnimationView(
                            progress = state.scanProgress,
                            scanned = state.scannedCount,
                            total = state.totalCount,
                            phaseText = state.scanPhaseText
                        )
                    }
                    state.groups.isEmpty() -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(32.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(90.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF1E281E)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.PhotoLibrary,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = Color(0xFF4CAF50)
                                )
                            }
                            Spacer(Modifier.height(20.dp))
                            Text(
                                "Clean Duplicates & Save Space",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "Scans all pictures, videos & GIFs with perceptual difference hashing (dHash) and cryptographic SHA-256 for maximum precision.",
                                color = Color.Gray,
                                fontSize = 13.sp,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(28.dp))
                            Button(
                                onClick = { permissionLauncher.launch(permissionsToRequest) },
                                modifier = Modifier.height(48.dp)
                            ) {
                                Icon(Icons.Default.Search, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Start Smart Media Scan", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    else -> {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(state.groups, key = { it.id }) { group ->
                                Box(modifier = Modifier.animateItem()) {
                                    SimilarGroupCard(
                                        group = group,
                                        onTogglePhoto = { photoId -> viewModel.togglePhotoStatus(context, group.id, photoId) },
                                        onHoldPhoto = { index -> viewModel.openFullScreen(group, index) },
                                        onSortChange = { sortOpt -> viewModel.sortGroup(context, group.id, sortOpt) },
                                        onSelectAllGroup = { markRed, keepBest ->
                                            viewModel.setGroupSelection(context, group.id, markRed, keepBest)
                                        },
                                        onHideGroup = { viewModel.hideGroup(context, group.id) }
                                    )
                                }
                            }
                        }
                    }
                }

                // Celebratory Deletion Success Animated Banner (Requirement 8)
                AnimatedVisibility(
                    visible = state.deleteSuccessMessage != null,
                    enter = slideInVertically { -it } + fadeIn(),
                    exit = slideOutVertically { -it } + fadeOut(),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp, start = 16.dp, end = 16.dp)
                ) {
                    Surface(
                        color = Color(0xFF2E7D32),
                        shape = RoundedCornerShape(12.dp),
                        shadowElevation = 8.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color.White)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                state.deleteSuccessMessage ?: "",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Spacer(Modifier.weight(1f))
                            IconButton(
                                onClick = { viewModel.clearDeleteMessage() },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = Color.White, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    // Auto-clear deletion banner after delay
    LaunchedEffect(state.deleteSuccessMessage) {
        if (state.deleteSuccessMessage != null) {
            delay(3200)
            viewModel.clearDeleteMessage()
        }
    }

    // In-App APK Update Dialog (Requirement 9)
    if (state.availableUpdate != null) {
        val update = state.availableUpdate!!
        AlertDialog(
            onDismissRequest = { viewModel.dismissUpdateDialog() },
            icon = { Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = Color(0xFF03DAC6)) },
            title = { Text("Update Available (${update.latestVersion})", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("A newer version of Dupe cleaner is available:")
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            update.releaseNotes.take(300),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        downloadAndInstallApk(context, update.downloadUrl, update.latestVersion)
                        viewModel.dismissUpdateDialog()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("DOWNLOAD & INSTALL", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissUpdateDialog() }) {
                    Text("LATER")
                }
            }
        )
    }

    // Confirmation Dialog
    if (showDeleteConfirmDialog) {
        val totalBytesToFree = markedPhotos.sumOf { it.sizeBytes }
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFE53935)) },
            title = { Text("Delete Marked Items?", fontWeight = FontWeight.Bold) },
            text = {
                Text("You are about to delete ${markedPhotos.size} items and recover ${formatFileSize(totalBytesToFree)}. The OS will ask for confirmation to securely delete or move them to trash.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirmDialog = false
                        triggerBatchDelete()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935))
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Full-Screen Hold-Click Modal with Share Button
    state.activeModalGroup?.let { modalGroup ->
        FullScreenGroupPagerModal(
            group = modalGroup,
            initialIndex = state.activeModalInitialIndex,
            onToggleStatus = { photoId -> viewModel.togglePhotoStatus(context, modalGroup.id, photoId) },
            onDismiss = { viewModel.closeFullScreen() }
        )
    }
}

// --- BEAUTIFUL SCANNING ANIMATION ---

@Composable
fun ScanningAnimationView(progress: Float, scanned: Int, total: Int, phaseText: String) {
    val infiniteTransition = rememberInfiniteTransition(label = "scan")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.88f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(1300, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 0.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1300, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(160.dp)) {
            // Ripple Wave
            Box(
                modifier = Modifier
                    .size(140.dp)
                    .scale(pulseScale)
                    .clip(CircleShape)
                    .background(Color(0xFF4CAF50).copy(alpha = pulseAlpha))
            )
            // Rotating Radar Sweep
            Box(
                modifier = Modifier
                    .size(116.dp)
                    .rotate(rotation)
                    .border(
                        width = 3.dp,
                        brush = Brush.sweepGradient(
                            listOf(Color(0xFF4CAF50), Color(0xFF03DAC6), Color.Transparent)
                        ),
                        shape = CircleShape
                    )
            )
            // Center Media Icon
            Icon(
                Icons.Default.PhotoLibrary,
                contentDescription = null,
                tint = Color(0xFF4CAF50),
                modifier = Modifier.size(46.dp)
            )
        }

        Spacer(Modifier.height(24.dp))
        Text(
            phaseText,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.width(220.dp).height(8.dp).clip(RoundedCornerShape(4.dp)),
            color = Color(0xFF4CAF50),
            trackColor = Color(0xFF2C2C2C)
        )
        Spacer(Modifier.height(10.dp))
        Text(
            if (total > 0) "$scanned of $total items processed (${(progress * 100).toInt()}%)"
            else "Preparing media scan...",
            fontSize = 13.sp,
            color = Color.LightGray
        )
    }
}

// --- GROUP CARD COMPONENT ---

@Composable
fun SimilarGroupCard(
    group: SimilarGroup,
    onTogglePhoto: (Long) -> Unit,
    onHoldPhoto: (Int) -> Unit,
    onSortChange: (SortOption) -> Unit,
    onSelectAllGroup: (Boolean, Boolean) -> Unit,
    onHideGroup: () -> Unit
) {
    var sortMenuOpen by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header Row: EXACT tag (only if exact) + Title (#399) + Sort Dropdown + Hide/Finish Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Only show badge if EXACT duplicate, remove similar tag completely
                    if (group.isExactDuplicate) {
                        Badge(containerColor = Color(0xFFE53935)) {
                            Text(
                                "EXACT",
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                    }

                    // Requirement 6: remove "group #399" names to just "#399"
                    Text(
                        "#${group.id} (${group.photos.size})",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Sort Button
                    Box {
                        TextButton(onClick = { sortMenuOpen = true }) {
                            Icon(Icons.Default.SwapVert, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(group.sortOption.label, fontSize = 12.sp)
                        }
                        DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                            SortOption.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.label) },
                                    onClick = {
                                        onSortChange(option)
                                        sortMenuOpen = false
                                    }
                                )
                            }
                        }
                    }

                    Spacer(Modifier.width(4.dp))

                    // Requirement 4: Button for hide or finished a group
                    IconButton(
                        onClick = onHideGroup,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = "Hide or Finish group",
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            // Action Icons Row: Keep 1st Only, Delete All, Keep All (Replaced text chips with sleek icon buttons)
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Keep 1st Only
                FilledTonalIconButton(
                    onClick = { onSelectAllGroup(false, true) },
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = Color(0xFF2C2C2C),
                        contentColor = Color(0xFF03DAC6)
                    ),
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(Icons.Default.Filter1, contentDescription = "Keep 1st only", modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(20.dp))

                // Delete All (Red)
                FilledTonalIconButton(
                    onClick = { onSelectAllGroup(true, false) },
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = Color(0xFF382323),
                        contentColor = Color(0xFFE53935)
                    ),
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = "Delete all", modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(20.dp))

                // Keep All (Green)
                FilledTonalIconButton(
                    onClick = { onSelectAllGroup(false, false) },
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = Color(0xFF1E331E),
                        contentColor = Color(0xFF4CAF50)
                    ),
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(Icons.Default.DoneAll, contentDescription = "Keep all", modifier = Modifier.size(18.dp))
                }
            }

            Spacer(Modifier.height(8.dp))

            // Horizontal Photo/Video Row inside Group (Requirement 8: animated deletions)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(group.photos, key = { it.id }) { photo ->
                    Box(modifier = Modifier.animateItem()) {
                        val index = group.photos.indexOf(photo)
                        PhotoThumbnailTile(
                            photo = photo,
                            onTap = { onTogglePhoto(photo.id) },
                            onLongPress = { onHoldPhoto(index) }
                        )
                    }
                }
            }
        }
    }
}

// --- PHOTO / VIDEO THUMBNAIL TILE WITH RED / GREEN ICON & LONG-PRESS ---

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhotoThumbnailTile(
    photo: PhotoItem,
    onTap: () -> Unit,
    onLongPress: () -> Unit
) {
    val context = LocalContext.current
    val borderColor = if (photo.markedForDelete) Color(0xFFE53935) else Color(0xFF4CAF50)
    val badgeBgColor = if (photo.markedForDelete) Color(0xFFE53935) else Color(0xFF4CAF50)

    val imageRequest = remember(photo.uri) {
        ImageRequest.Builder(context)
            .data(photo.uri)
            .crossfade(true)
            .build()
    }

    Box(
        modifier = Modifier
            .width(145.dp)
            .height(185.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(3.dp, borderColor, RoundedCornerShape(10.dp))
            .combinedClickable(
                onClick = onTap,
                onLongClick = onLongPress
            )
    ) {
        AsyncImage(
            model = imageRequest,
            contentDescription = photo.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // Video Duration Badge
        if (photo.isVideo) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(12.dp)
                )
                Spacer(Modifier.width(2.dp))
                Text(
                    formatDuration(photo.durationMs),
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Top-Right Red (Delete) or Green (Keep) Icon Badge
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .size(32.dp)
                .clip(CircleShape)
                .background(badgeBgColor),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (photo.markedForDelete) Icons.Default.Delete else Icons.Default.Check,
                contentDescription = if (photo.markedForDelete) "Marked for Delete" else "Marked to Keep",
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }

        // Bottom Metadata Bar (Size & Date)
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.65f))
                .padding(6.dp)
        ) {
            Text(
                text = formatFileSize(photo.sizeBytes),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
            Text(
                text = formatDate(photo.dateAdded),
                color = Color.LightGray,
                fontSize = 10.sp
            )
        }
    }
}

// --- FULL SCREEN SWIPE MODAL (WITH VIDEO PLAYER, SHARE & 1-CLICK HIDE TEXT) ---

@Composable
fun FullScreenGroupPagerModal(
    group: SimilarGroup,
    initialIndex: Int,
    onToggleStatus: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var showControls by remember { mutableStateOf(true) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val pagerState = rememberPagerState(
            initialPage = initialIndex.coerceIn(0, (group.photos.size - 1).coerceAtLeast(0)),
            pageCount = { group.photos.size }
        )
        val currentPhoto = group.photos.getOrNull(pagerState.currentPage)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable { showControls = !showControls }
        ) {
            // Horizontal Pager (Requirement 1: Native Video Player for videos)
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val item = group.photos[page]
                if (item.isVideo) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable { showControls = !showControls },
                        contentAlignment = Alignment.Center
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                VideoView(ctx).apply {
                                    val mc = MediaController(ctx)
                                    mc.setAnchorView(this)
                                    setMediaController(mc)
                                    setVideoURI(item.uri)
                                    setOnPreparedListener { mp ->
                                        mp.isLooping = true
                                        if (pagerState.currentPage == page) start()
                                    }
                                }
                            },
                            update = { view ->
                                if (pagerState.currentPage == page) {
                                    if (!view.isPlaying) view.start()
                                } else {
                                    if (view.isPlaying) view.pause()
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                } else {
                    val request = remember(item.uri) {
                        ImageRequest.Builder(context)
                            .data(item.uri)
                            .crossfade(true)
                            .build()
                    }
                    AsyncImage(
                        model = request,
                        contentDescription = item.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // Top Overlay: Close Button + Position Indicator + Hide Text Toggle + Share Button (Requirement 2)
            AnimatedVisibility(
                visible = showControls,
                enter = fadeIn() + slideInVertically { -it },
                exit = fadeOut() + slideOutVertically { -it },
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.65f))
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }

                    Text(
                        "Item ${pagerState.currentPage + 1} of ${group.photos.size}",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )

                    Row {
                        // 1-Click Hide Text / Controls Button (Requirement 2)
                        IconButton(onClick = { showControls = false }) {
                            Icon(
                                Icons.Default.VisibilityOff,
                                contentDescription = "Hide Info",
                                tint = Color.White
                            )
                        }

                        // Share Button for held pictures/videos
                        IconButton(onClick = {
                            currentPhoto?.let { photo ->
                                try {
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = if (photo.isVideo) "video/*" else "image/*"
                                        putExtra(Intent.EXTRA_STREAM, photo.uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(shareIntent, "Share Media"))
                                } catch (e: Exception) {
                                    // Fallback
                                }
                            }
                        }) {
                            Icon(Icons.Default.Share, contentDescription = "Share", tint = Color.White)
                        }
                    }
                }
            }

            // Bottom Overlay: Metadata + Large Green (Keep) & Red (Delete) Toggle Buttons
            AnimatedVisibility(
                visible = showControls,
                enter = fadeIn() + slideInVertically { it },
                exit = fadeOut() + slideOutVertically { it },
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                currentPhoto?.let { photo ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.85f))
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(photo.name, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        val extraInfo = if (photo.isVideo) " • ${formatDuration(photo.durationMs)}" else ""
                        Text(
                            "${formatFileSize(photo.sizeBytes)} • ${photo.width}x${photo.height}$extraInfo • ${formatDate(photo.dateAdded)}",
                            color = Color.LightGray,
                            fontSize = 13.sp
                        )
                        Spacer(Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            // KEEP BUTTON
                            Button(
                                onClick = { if (photo.markedForDelete) onToggleStatus(photo.id) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (!photo.markedForDelete) Color(0xFF4CAF50) else Color(0xFF2E3B2E)
                                ),
                                modifier = Modifier.weight(1f).height(52.dp)
                            ) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color.White)
                                Spacer(Modifier.width(8.dp))
                                Text("KEEP", fontWeight = FontWeight.Bold, color = Color.White)
                            }

                            // DELETE BUTTON
                            Button(
                                onClick = { if (!photo.markedForDelete) onToggleStatus(photo.id) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (photo.markedForDelete) Color(0xFFE53935) else Color(0xFF3E2727)
                                ),
                                modifier = Modifier.weight(1f).height(52.dp)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null, tint = Color.White)
                                Spacer(Modifier.width(8.dp))
                                Text("DELETE", fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
}

// --- UTILITY FORMATTERS ---

fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.2f MB", mb)
    val gb = mb / 1024.0
    return String.format(Locale.US, "%.2f GB", gb)
}

fun formatDate(dateMillis: Long): String {
    // shortcut: handles legacy second timestamps < 10B, upgrade if handling negative epoch dates
    val millis = if (dateMillis in 1 until 10_000_000_000L) dateMillis * 1000L else dateMillis
    val sdf = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
    return sdf.format(Date(millis))
}

fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return String.format(Locale.US, "%d:%02d", min, sec)
}
