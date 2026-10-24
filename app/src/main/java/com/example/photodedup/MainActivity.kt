package com.example.photodedup

import android.Manifest
import android.app.Activity
import android.content.ContentResolver
import android.content.ContentUris
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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*

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
    val similarityThreshold: Int = 8, // Max Hamming distance (0 = exact perceptual match, 10 = loose)
    val activeModalGroup: SimilarGroup? = null,
    val activeModalInitialIndex: Int = 0
)

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
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
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

    fun scanDevicePhotos(contentResolver: ContentResolver) {
        if (_uiState.value.isScanning) return
        viewModelScope.launch {
            _uiState.update { it.copy(isScanning = true, scanProgress = 0f, groups = emptyList()) }

            val rawPhotos = withContext(Dispatchers.IO) {
                val list = mutableListOf<PhotoItem>()
                val projection = arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.DATE_ADDED,
                    MediaStore.Images.Media.SIZE,
                    MediaStore.Images.Media.WIDTH,
                    MediaStore.Images.Media.HEIGHT
                )
                val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

                contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    null,
                    null,
                    sortOrder
                )?.use { cursor ->
                    val total = cursor.count
                    _uiState.update { it.copy(totalCount = total) }
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                    val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                    val widthCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                    val heightCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)

                    var processed = 0
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val name = cursor.getString(nameCol) ?: "Photo_$id"
                        val date = cursor.getLong(dateCol)
                        val size = cursor.getLong(sizeCol)
                        val w = cursor.getInt(widthCol)
                        val h = cursor.getInt(heightCol)
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
                                list.add(PhotoItem(id, uri, name, date, size, w, h, dHash))
                            }
                        } catch (e: Exception) {
                            // Skip unreadable/corrupted image files
                        }

                        processed++
                        if (processed % 15 == 0 || processed == total) {
                            _uiState.update {
                                it.copy(
                                    scannedCount = processed,
                                    scanProgress = if (total > 0) processed.toFloat() / total else 1f
                                )
                            }
                        }
                    }
                }
                list
            }

            // Cluster into Duplicate & Similar Groups
            val clusteredGroups = withContext(Dispatchers.Default) {
                val threshold = _uiState.value.similarityThreshold
                val visited = BooleanArray(rawPhotos.size)
                val resultGroups = mutableListOf<SimilarGroup>()
                var groupIdCounter = 1

                for (i in rawPhotos.indices) {
                    if (visited[i]) continue
                    val base = rawPhotos[i]
                    val currentCluster = mutableListOf(base)
                    visited[i] = true
                    var allExact = true

                    for (j in i + 1 until rawPhotos.size) {
                        if (visited[j]) continue
                        val candidate = rawPhotos[j]
                        val dist = PerceptualHasher.hammingDistance(base.dHash, candidate.dHash)

                        // Group photos if visually similar within threshold or exact match
                        if (dist <= threshold) {
                            visited[j] = true
                            currentCluster.add(candidate)
                            val isExactMatch = if (dist == 0 && base.sizeBytes == candidate.sizeBytes) {
                                // Double check with SHA-256 when size and dHash match
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
                        // Smart Auto-Mark: Keep the first photo (Green) and mark the rest for deletion (Red)
                        val smartMarked = sortedCluster.mapIndexed { idx, item ->
                            item.copy(markedForDelete = idx > 0)
                        }
                        resultGroups.add(
                            SimilarGroup(
                                id = groupIdCounter++,
                                title = if (allExact) "Exact Duplicates" else "Similar Photos",
                                isExactDuplicate = allExact,
                                photos = smartMarked,
                                sortOption = _uiState.value.globalSort
                            )
                        )
                    }
                }
                resultGroups
            }

            _uiState.update {
                it.copy(isScanning = false, groups = clusteredGroups)
            }
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

    // Toggle individual photo between RED (Delete) and GREEN (Keep)
    fun togglePhotoStatus(groupId: Int, photoId: Long) {
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
    }

    // Sort a single group by Date or Size
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

    // Sort all groups globally
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

    // Group-Level Select All (Mark all Red, or Keep Best & Mark Rest Red, or Keep All Green)
    fun setGroupSelection(groupId: Int, markDeleteAll: Boolean, keepBestOne: Boolean = false) {
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
    }

    // Global Select / Deselect across ALL groups
    fun setGlobalSelection(markDeleteAll: Boolean, keepBestInEachGroup: Boolean = false) {
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
    }

    fun openFullScreen(group: SimilarGroup, initialIndex: Int) {
        _uiState.update { it.copy(activeModalGroup = group, activeModalInitialIndex = initialIndex) }
    }

    fun closeFullScreen() {
        _uiState.update { it.copy(activeModalGroup = null) }
    }

    fun removeDeletedPhotosFromState(deletedIds: Set<Long>) {
        _uiState.update { state ->
            val cleanedGroups = state.groups.mapNotNull { group ->
                val remaining = group.photos.filterNot { it.id in deletedIds }
                if (remaining.size > 1) group.copy(photos = remaining) else null
            }
            state.copy(groups = cleanedGroups, activeModalGroup = null)
        }
    }
}

// --- MAIN ACTIVITY & UI ---

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DedupAppScreen(viewModel: DedupViewModel = viewModel()) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    val permissionsToRequest = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.values.any { it }
        if (granted) {
            viewModel.scanDevicePhotos(context.contentResolver)
        }
    }

    // Launcher for Android 11+ Scoped Storage Batch Deletion Dialog
    val markedPhotos = remember(state.groups) {
        state.groups.flatMap { it.photos }.filter { it.markedForDelete }
    }
    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.removeDeletedPhotosFromState(markedPhotos.map { it.id }.toSet())
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
            viewModel.removeDeletedPhotosFromState(markedPhotos.map { it.id }.toSet())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Photo Dedup Pro", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(
                            "${state.groups.size} groups • ${markedPhotos.size} marked to delete",
                            fontSize = 12.sp,
                            color = Color.LightGray
                        )
                    }
                },
                actions = {
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
                    Column(modifier = Modifier.padding(12.dp)) {
                        // Global Selection Controls Bar
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { viewModel.setGlobalSelection(markDeleteAll = false, keepBestInEachGroup = true) },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(vertical = 6.dp, horizontal = 8.dp)
                            ) {
                                Text("Smart Select", fontSize = 11.sp)
                            }
                            OutlinedButton(
                                onClick = { viewModel.setGlobalSelection(markDeleteAll = true) },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(vertical = 6.dp, horizontal = 8.dp)
                            ) {
                                Text("Select All (Red)", fontSize = 11.sp)
                            }
                            OutlinedButton(
                                onClick = { viewModel.setGlobalSelection(markDeleteAll = false) },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(vertical = 6.dp, horizontal = 8.dp)
                            ) {
                                Text("Keep All (Green)", fontSize = 11.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

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
                                "Delete ${markedPhotos.size} Photos (${formatFileSize(totalBytesToFree)})",
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
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(progress = { state.scanProgress })
                        Spacer(Modifier.height(16.dp))
                        Text("Scanning & Computing Perceptual Hashes...", fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${state.scannedCount} / ${state.totalCount} photos",
                            fontSize = 13.sp,
                            color = Color.Gray
                        )
                    }
                }
                state.groups.isEmpty() -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.PhotoLibrary,
                            contentDescription = null,
                            modifier = Modifier.size(72.dp),
                            tint = Color(0xFF4CAF50)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text("Find Duplicate & Similar Photos", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Scan your photo gallery to detect duplicates and visually similar photos. Tap any photo to toggle Green (Keep) or Red (Delete). Hold-press any photo to inspect full screen and swipe through its group.",
                            color = Color.Gray,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(24.dp))
                        Button(onClick = { permissionLauncher.launch(permissionsToRequest) }) {
                            Icon(Icons.Default.Search, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Start Smart Photo Scan")
                        }
                    }
                }
                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(state.groups, key = { it.id }) { group ->
                            SimilarGroupCard(
                                group = group,
                                onTogglePhoto = { photoId -> viewModel.togglePhotoStatus(group.id, photoId) },
                                onHoldPhoto = { index -> viewModel.openFullScreen(group, index) },
                                onSortChange = { sortOpt -> viewModel.sortGroup(group.id, sortOpt) },
                                onSelectAllGroup = { markRed, keepBest ->
                                    viewModel.setGroupSelection(group.id, markRed, keepBest)
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // Deletion Confirmation Dialog (UX best practice)
    if (showDeleteConfirmDialog) {
        val totalBytesToFree = markedPhotos.sumOf { it.sizeBytes }
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFE53935)) },
            title = { Text("Delete Marked Photos?") },
            text = {
                Text("You are about to delete ${markedPhotos.size} photos and recover ${formatFileSize(totalBytesToFree)}. The OS will ask for your confirmation to delete or move them to the trash.")
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

    // Full-Screen Hold-Click Inspection & Swipe Pager Modal
    state.activeModalGroup?.let { modalGroup ->
        FullScreenGroupPagerModal(
            group = modalGroup,
            initialIndex = state.activeModalInitialIndex,
            onToggleStatus = { photoId -> viewModel.togglePhotoStatus(modalGroup.id, photoId) },
            onDismiss = { viewModel.closeFullScreen() }
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
        Column(modifier = Modifier.padding(12.dp)) {
            // Group Header: Title + Sort Menu + Group Select/Deselect
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Badge(
                        containerColor = if (group.isExactDuplicate) Color(0xFFE53935) else Color(0xFFFF9800)
                    ) {
                        Text(
                            if (group.isExactDuplicate) "EXACT" else "SIMILAR",
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Group #${group.id} (${group.photos.size})",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }

                // Group Sort Button (Date / Size)
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

            // Group Selection Controls
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AssistChip(
                    onClick = { onSelectAllGroup(false, true) },
                    label = { Text("Keep 1st Only", fontSize = 11.sp) }
                )
                AssistChip(
                    onClick = { onSelectAllGroup(true, false) },
                    label = { Text("Delete All (Red)", fontSize = 11.sp) }
                )
                AssistChip(
                    onClick = { onSelectAllGroup(false, false) },
                    label = { Text("Keep All (Green)", fontSize = 11.sp) }
                )
            }

            // Horizontal Photo Row inside Group
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

// --- PHOTO THUMBNAIL TILE WITH RED / GREEN ICON & LONG-PRESS ---

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhotoThumbnailTile(
    photo: PhotoItem,
    onTap: () -> Unit,
    onLongPress: () -> Unit
) {
    val borderColor = if (photo.markedForDelete) Color(0xFFE53935) else Color(0xFF4CAF50)
    val badgeBgColor = if (photo.markedForDelete) Color(0xFFE53935) else Color(0xFF4CAF50)

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
            model = photo.uri,
            contentDescription = photo.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

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

// --- FULL SCREEN SWIPE MODAL (OPENED ON HOLD-CLICK) ---

@Composable
fun FullScreenGroupPagerModal(
    group: SimilarGroup,
    initialIndex: Int,
    onToggleStatus: (Long) -> Unit,
    onDismiss: () -> Unit
) {
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
            // Swipeable Horizontal Pager for the current group
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val item = group.photos[page]
                AsyncImage(
                    model = item.uri,
                    contentDescription = item.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Top Overlay: Close Button + Position Indicator
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                }
                Text(
                    "Photo ${pagerState.currentPage + 1} of ${group.photos.size}",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.width(48.dp))
            }

            // Bottom Overlay: Metadata + Large Green (Keep) & Red (Delete) Toggle Buttons
            currentPhoto?.let { photo ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(Color.Black.copy(alpha = 0.8f))
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(photo.name, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text(
                        "${formatFileSize(photo.sizeBytes)} • ${photo.width}x${photo.height} • ${formatDate(photo.dateAdded)}",
                        color = Color.LightGray,
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // GREEN KEEP BUTTON
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

                        // RED DELETE BUTTON
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
