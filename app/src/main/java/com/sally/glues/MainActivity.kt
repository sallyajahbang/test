package com.sally.glues

import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GluesTheme { GluesRoot() }
        }
    }
}

@Composable
fun GluesRoot() {
    val context = LocalContext.current
    var config by remember { mutableStateOf(GlueStore.load(context)) }
    val updateConfig: (GlueConfig) -> Unit = { new ->
        config = new
        GlueStore.save(context, new)
    }

    var granted by remember { mutableStateOf(hasVideoPermission(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted = it }

    var videos by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    LaunchedEffect(granted) {
        if (granted) videos = loadVideos(context)
    }

    var editing by remember { mutableStateOf<VideoItem?>(null) }
    BackHandler(enabled = editing != null) { editing = null }

    val current = editing
    if (current == null) {
        BrowserScreen(
            videos = videos,
            granted = granted,
            currentUri = config.videoUri,
            onRequestPermission = { permissionLauncher.launch(videoPermission()) },
            onEdit = { video ->
                // Video yang sama mempertahankan pengaturan. Video baru mulai dari awal.
                if (config.videoUri != video.uri.toString()) {
                    updateConfig(
                        config.copy(
                            videoUri = video.uri.toString(),
                            startMs = 0L,
                            endMs = 0L,
                            offsetX = 0f,
                            offsetY = 0f
                        )
                    )
                }
                editing = video
            }
        )
    } else {
        EditorScreen(
            video = current,
            config = config,
            onConfigChange = updateConfig,
            onBack = { editing = null },
            onApply = { applyWallpaper(context) }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    videos: List<VideoItem>,
    granted: Boolean,
    currentUri: String?,
    onRequestPermission: () -> Unit,
    onEdit: (VideoItem) -> Unit
) {
    var tab by remember { mutableStateOf(0) }
    var highlighted by remember { mutableStateOf<VideoItem?>(null) }
    LaunchedEffect(videos) {
        if (highlighted == null) highlighted = videos.firstOrNull()
    }

    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text("Glues") }) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                    label = { Text("Video") }
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Default.Star, contentDescription = null) },
                    label = { Text("Online") }
                )
            }
        }
    ) { pad ->
        if (tab == 0) {
            VideoLibraryTab(
                pad = pad,
                videos = videos,
                granted = granted,
                currentUri = currentUri,
                highlighted = highlighted,
                onHighlight = { highlighted = it },
                onRequestPermission = onRequestPermission,
                onEdit = onEdit
            )
        } else {
            OnlineTab(pad = pad, onOpen = onEdit)
        }
    }
}

@Composable
fun VideoLibraryTab(
    pad: PaddingValues,
    videos: List<VideoItem>,
    granted: Boolean,
    currentUri: String?,
    highlighted: VideoItem?,
    onHighlight: (VideoItem) -> Unit,
    onRequestPermission: () -> Unit,
    onEdit: (VideoItem) -> Unit
) {
    if (!granted) {
        Column(
            Modifier.padding(pad).fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Glues butuh izin untuk membaca video di perangkatmu.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRequestPermission) { Text("Izinkan akses video") }
        }
        return
    }

    Column(Modifier.padding(pad).fillMaxSize()) {
        // Preview disematkan di atas: langsung tampil saat video dipilih, tidak ikut ter-scroll.
        Box(Modifier.padding(12.dp)) {
            PreviewCard(video = highlighted, onEdit = onEdit)
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(videos, key = { it.uri.toString() }) { v ->
                VideoTile(
                    video = v,
                    selected = v.uri == highlighted?.uri,
                    active = v.uri.toString() == currentUri,
                    onClick = { onHighlight(v) }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    video: VideoItem,
    config: GlueConfig,
    onConfigChange: (GlueConfig) -> Unit,
    onBack: () -> Unit,
    onApply: () -> Unit
) {
    val latest by rememberUpdatedState(config)
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var videoSize by remember { mutableStateOf(IntSize.Zero) }
    var showSettings by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val screen = LocalConfiguration.current
    val screenAspect = screen.screenWidthDp.toFloat() / screen.screenHeightDp.toFloat()

    // Ukuran video setelah cover ke kotak preview, sama dengan perhitungan di renderer.
    val known = videoSize.width > 0 && boxSize.width > 0
    val cover = if (known) {
        maxOf(
            boxSize.width / videoSize.width.toFloat(),
            boxSize.height / videoSize.height.toFloat()
        )
    } else 1f
    val pxW = if (known) videoSize.width * cover else boxSize.width.toFloat()
    val pxH = if (known) videoSize.height * cover else boxSize.height.toFloat()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(video.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showSettings = true }) {
                Icon(Icons.Default.Settings, contentDescription = "Atur wallpaper")
            }
        }
    ) { pad ->
        Box(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .fillMaxHeight(0.9f)
                    .aspectRatio(screenAspect)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.Black)
                    .onSizeChanged { boxSize = it }
                    .pointerInput(Unit) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            val size = boxSize
                            if (size.width > 0 && size.height > 0) {
                                val s = latest
                                onConfigChange(
                                    s.copy(
                                        offsetX = (s.offsetX + drag.x / size.width).coerceIn(-1f, 1f),
                                        offsetY = (s.offsetY + drag.y / size.height).coerceIn(-1f, 1f)
                                    )
                                )
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                key(video.uri) {
                    VideoSurface(
                        uri = video.uri,
                        muted = config.muted,
                        startMs = config.startMs,
                        endMs = config.endMs,
                        modifier = Modifier
                            .requiredSize(
                                with(density) { pxW.toDp() },
                                with(density) { pxH.toDp() }
                            )
                            .graphicsLayer {
                                scaleX = config.scale
                                scaleY = config.scale
                                translationX = config.offsetX * boxSize.width
                                translationY = config.offsetY * boxSize.height
                            },
                        onVideoSize = { w, h -> videoSize = IntSize(w, h) }
                    )
                }
            }
        }
    }

    if (showSettings) {
        WallpaperSettingsDialog(
            video = video,
            config = config,
            onConfigChange = onConfigChange,
            onDismiss = { showSettings = false },
            onApply = onApply
        )
    }
}

/** Dialog pengaturan: mute, skala, durasi (posisi awal dan akhir), dan tombol pasang wallpaper. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WallpaperSettingsDialog(
    video: VideoItem,
    config: GlueConfig,
    onConfigChange: (GlueConfig) -> Unit,
    onDismiss: () -> Unit,
    onApply: () -> Unit
) {
    val durationMs = video.durationMs.coerceAtLeast(1000L)
    val durF = durationMs.toFloat()
    val startMs = config.startMs.coerceIn(0L, durationMs)
    val endMs = if (config.endMs > 0) config.endMs.coerceIn(startMs, durationMs) else durationMs

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Atur wallpaper") },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Mute audio", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Video tetap berjalan tanpa suara",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = config.muted,
                        onCheckedChange = { onConfigChange(config.copy(muted = it)) }
                    )
                }

                Text(
                    "Skala: ${"%.2f".format(config.scale)}x",
                    style = MaterialTheme.typography.titleMedium
                )
                Slider(
                    value = config.scale,
                    onValueChange = { onConfigChange(config.copy(scale = it)) },
                    valueRange = 0.5f..3f
                )

                Text(
                    "Durasi loop: ${formatDuration(startMs)} - ${formatDuration(endMs)}",
                    style = MaterialTheme.typography.titleMedium
                )
                RangeSlider(
                    value = startMs.toFloat()..endMs.toFloat(),
                    onValueChange = { r ->
                        onConfigChange(
                            config.copy(
                                startMs = r.start.toLong(),
                                // Mendekati akhir video disimpan sebagai 0 = sampai akhir
                                endMs = if (r.endInclusive >= durF - 500f) 0L else r.endInclusive.toLong()
                            )
                        )
                    },
                    valueRange = 0f..durF
                )
                Text(
                    "Geser pegangan kiri untuk posisi awal dan kanan untuk posisi akhir.",
                    style = MaterialTheme.typography.bodySmall
                )

                OutlinedButton(
                    onClick = {
                        onConfigChange(
                            config.copy(scale = 1f, offsetX = 0f, offsetY = 0f, startMs = 0L, endMs = 0L)
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Reset skala, posisi, dan durasi") }
            }
        },
        confirmButton = {
            Button(onClick = onApply) { Text("Pasang wallpaper") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Tutup") }
        }
    )
}

fun applyWallpaper(context: Context) {
    val component = ComponentName(context, GlueWallpaperService::class.java)
    val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
        .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        context.startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
    }
}
