package com.personal.ytplayer.ui

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personal.ytplayer.extractor.Track

@Composable
fun PlayerScreen(viewModel: PlayerViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pendingStart by remember { mutableIntStateOf(0) }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            viewModel.playPlaylist(pendingStart)
        } else {
            viewModel.showMessage(
                "Allow notifications so audio can keep playing in the background and on your watch.",
            )
        }
    }
    val startPlaylist: (Int) -> Unit = { index ->
        pendingStart = index
        if (needsNotificationPermission(context)) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.playPlaylist(index)
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Background Play",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Paste one or more song links, then add them. Play playlist loads the whole list, and the watch skips through those songs.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                OutlinedTextField(
                    value = state.urlInput,
                    onValueChange = viewModel::onUrlChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("YouTube links") },
                    placeholder = { Text("One song link per line") },
                    minLines = 3,
                    maxLines = 8,
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = {
                            val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                .primaryClip
                                ?.getItemAt(0)
                                ?.coerceToText(context)
                                ?.toString()
                                .orEmpty()
                            if (clip.isNotBlank()) viewModel.onUrlChange(clip.trim())
                        },
                        enabled = !state.loading,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Paste")
                    }
                    Button(
                        onClick = viewModel::addToPlaylist,
                        enabled = !state.loading && state.urlInput.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    ) {
                        if (state.loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text("Add")
                        }
                    }
                }
            }
            item {
                Button(
                    onClick = { startPlaylist(0) },
                    enabled = !state.loading && state.playlist.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Play playlist")
                }
            }
            state.status?.let { status ->
                item {
                    Text(status, style = MaterialTheme.typography.bodyMedium)
                }
            }
            val problem = state.error ?: state.playbackError
            problem?.let { error ->
                item {
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            item {
                NowPlayingCard(state, viewModel)
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (state.playlist.isEmpty()) "Playlist" else "Playlist · ${state.playlist.size}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (state.playlist.isNotEmpty()) {
                        TextButton(onClick = viewModel::clearPlaylist, enabled = !state.loading) {
                            Text("Clear")
                        }
                    }
                }
            }
            if (state.playlist.isEmpty()) {
                item {
                    Text(
                        "No songs yet. Paste links above and tap Add.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                items(state.playlist.size, key = { index -> state.playlist[index].watchUrl }) { index ->
                    val track = state.playlist[index]
                    PlaylistItem(
                        track = track,
                        selected = track.watchUrl == state.currentMediaId,
                        onClick = { startPlaylist(index) },
                        onRemove = { viewModel.removeFromPlaylist(index) },
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun NowPlayingCard(state: PlayerUiState, viewModel: PlayerViewModel) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val duration = state.durationMs
    val fraction = when {
        dragFraction != null -> dragFraction!!
        duration > 0 -> (state.positionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
        else -> 0f
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp),
    ) {
        Text(
            text = state.title.ifBlank { "Nothing playing" },
            style = MaterialTheme.typography.titleLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (state.artist.isNotBlank()) {
            Text(
                text = state.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (state.buffering) {
            Text(
                text = "Getting audio…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Slider(
            value = fraction,
            onValueChange = { dragFraction = it },
            onValueChangeFinished = {
                val held = dragFraction
                if (held != null && duration > 0) {
                    viewModel.seekTo((held * duration).toLong())
                }
                dragFraction = null
            },
            enabled = duration > 0,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatClock(state.positionMs), style = MaterialTheme.typography.labelMedium)
            Text(formatClock(duration), style = MaterialTheme.typography.labelMedium)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = viewModel::previous, enabled = state.playlist.isNotEmpty()) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous")
            }
            IconButton(
                onClick = viewModel::togglePlay,
                enabled = state.playlist.isNotEmpty(),
                modifier = Modifier.size(64.dp),
            ) {
                Icon(
                    imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (state.isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(40.dp),
                )
            }
            IconButton(onClick = viewModel::next, enabled = state.playlist.size > 1) {
                Icon(Icons.Filled.SkipNext, contentDescription = "Next")
            }
        }
    }
}

@Composable
private fun PlaylistItem(
    track: Track,
    selected: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val background = if (selected) {
        MaterialTheme.colorScheme.surfaceVariant
    } else {
        MaterialTheme.colorScheme.surface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier
                .weight(1f)
                .clickable(onClick = onClick)
                .padding(vertical = 6.dp),
        ) {
            Text(
                text = track.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            if (track.artist.isNotBlank()) {
                Text(
                    text = track.artist,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (track.durationSec > 0) {
            Text(
                text = formatClock(track.durationSec * 1000),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Filled.Delete, contentDescription = "Remove")
        }
    }
}

private fun needsNotificationPermission(context: Context): Boolean {
    return Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
}

private fun formatClock(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
