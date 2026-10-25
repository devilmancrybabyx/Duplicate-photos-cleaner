package com.example.photodedup

import android.Manifest
import android.app.Activity
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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
    val dateAdded: Long, // Epoch seconds
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

data class UiState(
    val isScanning: Boolean = false,
    val scanProgress: Float = 0f,
    val scannedCount: Int = 0,
    val totalCount: Int = 0,
    val groups: List<SimilarGroup> = emptyList(),
    val globalSort: SortOption = SortOption.SIZE_DESC,
    val similarityThreshold: Int = 8, // Max Hamming distance
    val activeModalGroup: SimilarGroup? = null,
    val activeModalInitialIndex: Int = 0,
    val isCacheLoaded: Boolean = false
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
                    val dateAdded = pObj.getLong("dateAdded")
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
        if (_uiState.value.isCacheLoaded || _uiState.value.groups.isNotEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val cached = ScanCacheManager.load(context)
            if (cached != null) {
                _uiState.update {
                    it.copy(
                        groups = cached.first,
                        scannedCount = cached.second,
                        totalCount = cached.third,
                        isCacheLoaded = true
                    )
                }
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
            _uiState.update { it.copy(isScanning = true, scanProgress = 0f, groups = emptyList()) }

            val rawMedia = withContext(Dispatchers.IO) {
                val list = mutableListOf<PhotoItem>()

                // 1. Query Images (JPEG, PNG, GIF, WEBP, HEIC, etc.)
                val imageProjection = arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.DATE_ADDED,
                    MediaStore.Images.Media.SIZE,
                    MediaStore.Images.Media.WIDTH,
                    MediaStore.Images.Media.HEIGHT,
                    MediaStore.Images.Media.MIME_TYPE
                )
                val imageOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

                contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    imageProjection,
                    null,
                    null,
                    imageOrder
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                    val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                    val widthCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                    val heightCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
                    val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val name = cursor.getString(nameCol) ?: "Image_$id"
                        val date = cursor.getLong(dateCol)
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
                                list.add(PhotoItem(id, uri, name, date, size, w, h, dHash, isVideo = false, mimeType = mime))
                            }
                        } catch (e: Exception) {
                            // Skip corrupted files
                        }
                    }
                }

                // 2. Query Videos (MP4, MKV, 3GP, MOV, etc.)
                val videoProjection = arrayOf(
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.DATE_ADDED,
                    MediaStore.Video.Media.SIZE,
                    MediaStore.Video.Media.WIDTH,
                    MediaStore.Video.Media.HEIGHT,
                    MediaStore.Video.Media.DURATION,
                    MediaStore.Video.Media.MIME_TYPE
                )
                val videoOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

                contentResolver.query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    videoProjection,
                    null,
                    null,
                    videoOrder
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                    val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                    val widthCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
                    val heightCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
                    val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                    val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val name = cursor.getString(nameCol) ?: "Video_$id"
                        val date = cursor.getLong(dateCol)
                        val size = cursor.getLong(sizeCol)
                        val w = cursor.getInt(widthCol)
                        val h = cursor.getInt(heightCol)
                        val duration = cursor.getLong(durationCol)
                        val mime = cursor.getString(mimeCol) ?: "video/*"
                        val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)

                        try {
                            val thumb = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                contentResolver.loadThumbnail(uri, Size(64, 64), null)
                            } else {
                                @Suppress("DEPRECATION")
                                MediaStore.Video.Thumbnails.getThumbnail(
                                    contentResolver, id, MediaStore.Video.Thumbnails.MINI_KIND, null
                                )
                            }
                            if (thumb != null) {
                                val dHash = PerceptualHasher.computeDHash(thumb)
                                thumb.recycle()
                                list.add(
                                    PhotoItem(
                                        id, uri, name, date, size, w, h, dHash,
                                        isVideo = true, durationMs = duration, mimeType = mime
                                    )
                                )
                            }
                        } catch (e: Exception) {
                            // Skip corrupted video files
                        }
                    }
                }

                list
            }

            val totalMediaCount = rawMedia.size
            _uiState.update { it.copy(totalCount = totalMediaCount) }

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

                        // Check aspect ratio to avoid false positive matches between portrait and landscape
                        if (!base.isVideo) {
                            val candRatio = if (candidate.height > 0) candidate.width.toFloat() / candidate.height else 1f
                            if (abs(baseRatio - candRatio) > 0.35f) continue
                        } else {
                            // For videos, duration must be within 2 seconds
                            if (abs(base.durationMs - candidate.durationMs) > 2000L) continue
                        }

                        val dist = PerceptualHasher.hammingDistance(base.dHash, candidate.dHash)

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

                    if (i % 15 == 0 || i == rawMedia.size - 1) {
                        _uiState.update {
                            it.copy(
                                scannedCount = i + 1,
                                scanProgress = if (totalMediaCount > 0) (i + 1).toFloat() / totalMediaCount else 1f
                            )
                        }
                    }
                }
                resultGroups
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

    fun sortGroup(groupId: Int, sortOption: SortOption) {
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
    }

    fun sortAllGroups(sortOption: SortOption) {
        _uiState.update { state ->
            val updatedGroups = state.groups.map { group ->
                group.copy(
                    sortOption = sortOption,
                    photos = sortPhotosList(group.photos, sortOption)
                )
            }
            state.copy(globalSort = sortOption, groups = updatedGroups)
        }
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
        _uiState.update { state ->
            val cleanedGroups = state.groups.mapNotNull { group ->
                val remaining = group.photos.filterNot { it.id in deletedIds }
                if (remaining.size > 1) group.copy(photos = remaining) else null
            }
            state.copy(groups = cleanedGroups, activeModalGroup = null)
        }
        ScanCacheManager.save(context, _uiState.value)
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
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF4CAF50),
                    secondary = Color(0xFF03DAC6),
                    background = Color(0xFF121212),
                    surface = Color(0xFF1E1E1E),
                    error = Color(0xFFE53935)
                )
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    DedupAppScreen()
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
                    Text("Version 1.1.0", fontSize = 12.sp, color = Color.Gray)
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = Color(0xFF2C2C2C))

                // Stats Section
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Text("Scan Statistics", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF03DAC6))
                    Spacer(Modifier.height(8.dp))
                    Text("Groups detected: ${state.groups.size}", fontSize = 14.sp, color = Color.LightGray)
                    Text(
                        "Recoverable storage: ${formatFileSize(markedPhotos.sumOf { it.sizeBytes })}",
                        fontSize = 14.sp,
                        color = Color.LightGray
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = Color(0xFF2C2C2C))

                // Actions
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

                // Contact Section with GitHub Icon
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
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color.Black),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Code, contentDescription = "GitHub", tint = Color.White)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("devilmancrybabyx", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.White)
                            Text("Tap to visit GitHub", fontSize = 11.sp, color = Color(0xFF03DAC6))
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
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Dupe cleaner", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text(
                                if (state.groups.isNotEmpty()) "${state.groups.size} groups • ${markedPhotos.size} to delete"
                                else "Smart Media Cleaner",
                                fontSize = 11.sp,
                                color = Color.LightGray
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Open Drawer")
                        }
                    },
                    actions = {
                        // GitHub Contact Icon in Top Bar
                        IconButton(onClick = { openGitHubProfile(context) }) {
                            Icon(Icons.Default.Code, contentDescription = "GitHub Profile", tint = Color(0xFF03DAC6))
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
                                        viewModel.sortAllGroups(option)
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
                            total = state.totalCount
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
                                "Scans all pictures, videos & GIFs with deep perceptual gradient hashing (dHash) and cryptographic SHA-256 for maximum precision.",
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
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            items(state.groups, key = { it.id }) { group ->
                                SimilarGroupCard(
                                    group = group,
                                    onTogglePhoto = { photoId -> viewModel.togglePhotoStatus(context, group.id, photoId) },
                                    onHoldPhoto = { index -> viewModel.openFullScreen(group, index) },
                                    onSortChange = { sortOpt -> viewModel.sortGroup(group.id, sortOpt) },
                                    onSelectAllGroup = { markRed, keepBest ->
                                        viewModel.setGroupSelection(context, group.id, markRed, keepBest)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
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
fun ScanningAnimationView(progress: Float, scanned: Int, total: Int) {
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
            "Analyzing Photos, Videos & GIFs...",
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
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
            "$scanned of $total items processed (${(progress * 100).toInt()}%)",
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
    onSelectAllGroup: (Boolean, Boolean) -> Unit
) {
    var sortMenuOpen by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header Row: EXACT tag (only if exact) + Title + Sort Dropdown
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

                    Text(
                        "Group #${group.id} (${group.photos.size})",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }

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

            // Horizontal Photo/Video Row inside Group
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(group.photos.size) { index ->
                    val photo = group.photos[index]
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

// --- FULL SCREEN SWIPE MODAL (WITH SHARE BUTTON) ---

@Composable
fun FullScreenGroupPagerModal(
    group: SimilarGroup,
    initialIndex: Int,
    onToggleStatus: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
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
        ) {
            // Horizontal Pager
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val item = group.photos[page]
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

            // Top Overlay: Close Button + Position Indicator + Share Button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(Color.Black.copy(alpha = 0.6f))
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

            // Bottom Overlay: Metadata + Large Green (Keep) & Red (Delete) Toggle Buttons
            currentPhoto?.let { photo ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(Color.Black.copy(alpha = 0.82f))
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

fun formatDate(epochSeconds: Long): String {
    val sdf = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
    return sdf.format(Date(epochSeconds * 1000L))
}

fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return String.format(Locale.US, "%d:%02d", min, sec)
}
