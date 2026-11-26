package com.sally.glues

import android.media.MediaPlayer
import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay

/**
 * Pemutar video memakai VideoView.
 * Mute lewat MediaPlayer.setVolume; loop startMs..endMs dijaga dengan polling ringan
 * supaya preview sama dengan hasil di wallpaper.
 */
@Composable
fun VideoSurface(
    uri: Uri,
    muted: Boolean,
    modifier: Modifier = Modifier,
    startMs: Long = 0L,
    endMs: Long = 0L,
    onVideoSize: (Int, Int) -> Unit = { _, _ -> }
) {
    val player = remember(uri) { mutableStateOf<MediaPlayer?>(null) }

    LaunchedEffect(player.value, muted) {
        val v = if (muted) 0f else 1f
        runCatching { player.value?.setVolume(v, v) }
    }

    LaunchedEffect(player.value, startMs, endMs) {
        val mp = player.value ?: return@LaunchedEffect
        while (true) {
            delay(100)
            runCatching {
                val dur = mp.duration.toLong()
                val end = if (endMs > 0) minOf(endMs, dur) else dur
                if (end > startMs) {
                    val pos = mp.currentPosition.toLong()
                    if (pos >= end - 60 || pos < startMs) mp.seekTo(startMs.toInt())
                }
            }
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            VideoView(ctx).apply {
                setOnPreparedListener { mp ->
                    mp.isLooping = true
                    onVideoSize(mp.videoWidth, mp.videoHeight)
                    player.value = mp
                    if (startMs > 0) mp.seekTo(startMs.toInt())
                    start()
                }
                setVideoURI(uri)
            }
        },
        onRelease = { it.stopPlayback() }
    )
}

/** Kartu video di grid: thumbnail, durasi, dan penanda video aktif. */
@Composable
fun VideoTile(video: VideoItem, selected: Boolean, active: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val thumb by produceState<ImageBitmap?>(initialValue = null, video.uri) {
        value = loadThumbnail(context, video.uri)?.asImageBitmap()
    }

    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            thumb?.let {
                Image(
                    bitmap = it,
                    contentDescription = video.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
            Text(
                text = formatDuration(video.durationMs),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall
            )
            if (active) {
                Text(
                    text = "Aktif",
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
        Text(
            text = video.name,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Kartu preview. Muncul begitu sebuah video dipilih. */
@Composable
fun PreviewCard(video: VideoItem?, onEdit: (VideoItem) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(20.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            if (video != null) {
                key(video.uri) {
                    VideoSurface(uri = video.uri, muted = true, modifier = Modifier.fillMaxSize())
                }
            } else {
                Text("Pilih video untuk preview", color = Color.White)
            }
        }
        if (video != null) {
            Button(onClick = { onEdit(video) }, modifier = Modifier.fillMaxWidth()) {
                Text("Atur video ini")
            }
        }
    }
}
