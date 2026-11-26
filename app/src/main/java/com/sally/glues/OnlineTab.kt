package com.sally.glues

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.item
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Tab Online: cari video gratis dari Pexels/Pixabay, unduh sekali, lalu buka di editor. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnlineTab(pad: PaddingValues, onOpen: (VideoItem) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var sourceIndex by remember { mutableStateOf(0) }
    val source = ONLINE_SOURCES[sourceIndex]
    var keys by remember {
        mutableStateOf(ONLINE_SOURCES.associate { it.id to OnlinePrefs.keyFor(context, it.id) })
    }
    val apiKey = keys[source.id].orEmpty()

    var query by remember { mutableStateOf("nature") }
    var submitted by remember { mutableStateOf("nature") }
    var results by remember { mutableStateOf<List<OnlineVideo>>(emptyList()) }
    var page by remember { mutableStateOf(1) }
    var hasMore by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var downloadProgress by remember { mutableStateOf<Float?>(null) }
    var showKeyDialog by remember { mutableStateOf(false) }

    LaunchedEffect(sourceIndex, submitted, apiKey) {
        results = emptyList()
        page = 1
        hasMore = false
        error = null
        if (apiKey.isBlank()) return@LaunchedEffect
        loading = true
        try {
            results = source.search(submitted, 1, apiKey)
            hasMore = results.size >= ONLINE_PAGE_SIZE
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = friendlyError(e)
        } finally {
            loading = false
        }
    }

    fun search() {
        submitted = query.trim().ifEmpty { "nature" }
    }

    fun loadMore() {
        if (loading || !hasMore || apiKey.isBlank()) return
        scope.launch {
            loading = true
            try {
                val next = page + 1
                val more = source.search(submitted, next, apiKey)
                results = results + more
                page = next
                hasMore = more.size >= ONLINE_PAGE_SIZE
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = friendlyError(e)
            } finally {
                loading = false
            }
        }
    }

    fun openItem(item: OnlineVideo) {
        if (downloadProgress != null) return
        scope.launch {
            val file = File(context.cacheDir, "online/${source.id}_${item.id}.mp4")
            try {
                if (!file.exists()) {
                    downloadProgress = 0f
                    downloadTo(item.videoUrl, file) { downloadProgress = it }
                }
                onOpen(VideoItem(Uri.fromFile(file), item.title, item.durationMs))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = friendlyError(e)
            } finally {
                downloadProgress = null
            }
        }
    }

    Column(Modifier.padding(pad).fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ONLINE_SOURCES.forEachIndexed { i, s ->
                FilterChip(
                    selected = i == sourceIndex,
                    onClick = { sourceIndex = i },
                    label = { Text(s.label) }
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { showKeyDialog = true }) {
                Icon(Icons.Default.Settings, contentDescription = "Atur API key")
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("Cari video") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search() })
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { search() }) { Text("Cari") }
        }

        if (apiKey.isBlank()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "API key ${source.label} belum diisi",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    source.keyHelp,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = { showKeyDialog = true }) { Text("Atur API key") }
            }
        }

        error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }

        if (loading && results.isEmpty()) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(160.dp),
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(results, key = { "${source.id}_${it.id}" }) { item ->
                OnlineCard(
                    item = item,
                    sourceLabel = source.label,
                    enabled = downloadProgress == null,
                    onClick = { openItem(item) }
                )
            }
            if (hasMore) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    OutlinedButton(
                        onClick = { loadMore() },
                        enabled = !loading,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (loading) "Memuat..." else "Muat lagi")
                    }
                }
            }
        }
    }

    downloadProgress?.let { p ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Mengunduh video") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (p > 0f) {
                        LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                        Text("${(p * 100).toInt()}%")
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {}
        )
    }

    if (showKeyDialog) {
        ApiKeyDialog(
            source = source,
            currentKey = apiKey,
            onSave = { newKey ->
                OnlinePrefs.saveKey(context, source.id, newKey)
                keys = keys + (source.id to newKey.trim())
                showKeyDialog = false
            },
            onDismiss = { showKeyDialog = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiKeyDialog(
    source: OnlineSource,
    currentKey: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(currentKey) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("API key ${source.label}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(source.keyHelp, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text("API key") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { Button(onClick = { onSave(text) }) { Text("Simpan") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } }
    )
}

@Composable
fun OnlineCard(item: OnlineVideo, sourceLabel: String, enabled: Boolean, onClick: () -> Unit) {
    val thumb by produceState<ImageBitmap?>(initialValue = null, item.thumbUrl) {
        val url = item.thumbUrl ?: return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = httpGetBytes(url)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        }
    }

    ElevatedCard(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(9f / 16f)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            val bmp = thumb
            if (bmp != null) {
                Image(
                    bitmap = bmp,
                    contentDescription = item.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Text("Tanpa preview", style = MaterialTheme.typography.labelSmall)
            }
            Text(
                text = formatDuration(item.durationMs),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall
            )
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                item.title,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${item.author.ifEmpty { "Tidak diketahui" }} · $sourceLabel",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

fun friendlyError(e: Throwable): String {
    val msg = e.message.orEmpty()
    return when {
        msg.contains("401") || msg.contains("403") -> "API key tidak valid atau ditolak."
        msg.contains("429") -> "Batas permintaan API tercapai. Coba lagi nanti."
        e is UnknownHostException || e is SocketTimeoutException -> "Tidak bisa terhubung ke internet."
        else -> "Gagal memuat: ${msg.ifEmpty { e.javaClass.simpleName }}"
    }
}
