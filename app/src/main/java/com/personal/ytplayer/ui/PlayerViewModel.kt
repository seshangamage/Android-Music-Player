package com.personal.ytplayer.ui

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.personal.ytplayer.extractor.Track
import com.personal.ytplayer.extractor.YoutubeCatalog
import com.personal.ytplayer.playback.PlaybackService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class QueueRow(
    val index: Int,
    val title: String,
    val artist: String,
    val durationSec: Long,
)

data class PlayerUiState(
    val urlInput: String = "",
    val loading: Boolean = false,
    val status: String? = null,
    val error: String? = null,
    val playbackError: String? = null,
    val title: String = "",
    val artist: String = "",
    val isPlaying: Boolean = false,
    val buffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val queue: List<QueueRow> = emptyList(),
    val currentIndex: Int = 0,
    val currentMediaId: String? = null,
    val playlist: List<Track> = emptyList(),
)

@OptIn(UnstableApi::class)
class PlayerViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences(PREFS, Application.MODE_PRIVATE)
    private val _uiState = MutableStateFlow(
        PlayerUiState(
            urlInput = prefs.getString(KEY_URL, "").orEmpty(),
            playlist = readPlaylist(),
        ),
    )
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private val controllerFuture = MediaController.Builder(
        app,
        SessionToken(app, ComponentName(app, PlaybackService::class.java)),
    ).buildAsync()

    private var controller: MediaController? = null
    private var playJob: Job? = null

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            publish()
        }
    }

    init {
        controllerFuture.addListener(
            {
                try {
                    attach(controllerFuture.get())
                } catch (error: Exception) {
                    _uiState.update {
                        it.copy(error = error.message ?: "Could not connect to the player.")
                    }
                }
            },
            ContextCompat.getMainExecutor(app),
        )
        viewModelScope.launch {
            while (isActive) {
                if (controller?.isPlaying == true) publish()
                delay(500)
            }
        }
    }

    fun onUrlChange(value: String) {
        _uiState.update { it.copy(urlInput = value, error = null) }
        prefs.edit().putString(KEY_URL, value).apply()
    }

    fun showMessage(message: String) {
        _uiState.update { it.copy(error = message, loading = false) }
    }

    fun addToPlaylist() {
        val parts = extractUrls(_uiState.value.urlInput)
        if (parts.isEmpty()) {
            showMessage("Paste one or more YouTube song links.")
            return
        }
        playJob?.cancel()
        playJob = viewModelScope.launch {
            _uiState.update {
                it.copy(loading = true, error = null, playbackError = null, status = "Adding songs…")
            }
            val added = mutableListOf<Track>()
            val failures = mutableListOf<String>()
            parts.forEachIndexed { index, url ->
                _uiState.update { it.copy(status = "Adding ${index + 1} of ${parts.size}…") }
                try {
                    val queue = withContext(Dispatchers.IO) { YoutubeCatalog.load(url) }
                    added += queue.tracks
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    failures += error.message ?: url
                }
            }
            val existing = _uiState.value.playlist
            val merged = (existing + added).distinctBy { it.watchUrl }.take(MAX_PLAYLIST)
            val newTracks = merged.drop(existing.size)
            persist(merged)
            val capped = existing.size + added.distinctBy { it.watchUrl }.size > MAX_PLAYLIST
            val status = when {
                newTracks.isEmpty() && failures.isEmpty() -> "Those songs are already in the playlist."
                failures.isEmpty() -> addedSummary(newTracks.size, capped)
                newTracks.isEmpty() -> failures.first()
                else -> {
                    val failed = if (failures.size == 1) "1 link failed." else "${failures.size} links failed."
                    "${addedSummary(newTracks.size, capped)} $failed"
                }
            }
            _uiState.update {
                it.copy(
                    loading = false,
                    playlist = merged,
                    urlInput = if (newTracks.isNotEmpty()) "" else it.urlInput,
                    status = status,
                    error = if (newTracks.isEmpty() && failures.isNotEmpty()) failures.first() else null,
                )
            }
            if (newTracks.isNotEmpty()) {
                prefs.edit().remove(KEY_URL).apply()
                appendToPlayer(newTracks)
            }
        }
    }

    fun playPlaylist(startIndex: Int = 0) {
        val tracks = _uiState.value.playlist
        if (tracks.isEmpty()) {
            showMessage("Add song links to the playlist first.")
            return
        }
        val index = startIndex.coerceIn(tracks.indices)
        playJob?.cancel()
        playJob = viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null, playbackError = null, status = "Starting playlist…") }
            try {
                val player = awaitController()
                if (sameQueue(player, tracks)) {
                    player.seekToDefaultPosition(index)
                    player.play()
                } else {
                    player.setMediaItems(tracks.map { it.toMediaItem() }, index, 0L)
                    player.prepare()
                    player.play()
                }
                _uiState.update {
                    it.copy(loading = false, status = songCount(tracks.size), error = null)
                }
                publish()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(loading = false, error = error.message ?: "Could not start the playlist.")
                }
            }
        }
    }

    fun removeFromPlaylist(index: Int) {
        val current = _uiState.value.playlist
        if (index !in current.indices) return
        val removed = current[index]
        val updated = current.filterIndexed { position, _ -> position != index }
        persist(updated)
        _uiState.update { it.copy(playlist = updated, status = songCount(updated.size)) }
        val player = controller ?: return
        val playerIndex = (0 until player.mediaItemCount).firstOrNull { itemIndex ->
            player.getMediaItemAt(itemIndex).mediaId == removed.watchUrl
        }
        if (playerIndex != null) player.removeMediaItem(playerIndex)
    }

    fun clearPlaylist() {
        persist(emptyList())
        _uiState.update { it.copy(playlist = emptyList(), status = "Playlist cleared", error = null) }
        controller?.let { player ->
            player.stop()
            player.clearMediaItems()
        }
    }

    fun togglePlay() {
        val player = controller ?: return
        if (player.isPlaying) player.pause() else player.play()
    }

    fun next() {
        controller?.seekToNext()
    }

    fun previous() {
        controller?.seekToPrevious()
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
    }

    private fun appendToPlayer(tracks: List<Track>) {
        val player = controller ?: return
        val playlist = _uiState.value.playlist
        val before = playlist.dropLast(tracks.size)
        if (player.mediaItemCount == 0 || !sameQueue(player, before)) return
        tracks.forEach { player.addMediaItem(it.toMediaItem()) }
    }

    private fun sameQueue(player: Player, tracks: List<Track>): Boolean {
        if (player.mediaItemCount != tracks.size) return false
        return tracks.indices.all { index -> player.getMediaItemAt(index).mediaId == tracks[index].watchUrl }
    }

    private fun persist(tracks: List<Track>) {
        val array = JSONArray()
        tracks.forEach { track ->
            array.put(
                JSONObject()
                    .put("title", track.title)
                    .put("artist", track.artist)
                    .put("watchUrl", track.watchUrl)
                    .put("artworkUrl", track.artworkUrl.orEmpty())
                    .put("durationSec", track.durationSec),
            )
        }
        prefs.edit().putString(KEY_PLAYLIST, array.toString()).apply()
    }

    private fun readPlaylist(): List<Track> {
        val raw = prefs.getString(KEY_PLAYLIST, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val url = item.optString("watchUrl")
                    if (url.isBlank()) continue
                    add(
                        Track(
                            title = item.optString("title").ifBlank { "Untitled" },
                            artist = item.optString("artist"),
                            watchUrl = url,
                            artworkUrl = item.optString("artworkUrl").ifBlank { null },
                            durationSec = item.optLong("durationSec"),
                        ),
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun extractUrls(raw: String): List<String> {
        val explicit = URL_PATTERN.findAll(raw).map { match ->
            match.value.trim().trimEnd(',', '.', ')', ']', '"', '\'')
        }
        val bare = raw.lineSequence()
            .map { it.trim() }
            .filter { line ->
                !line.startsWith("http", ignoreCase = true) &&
                    (line.contains("youtube.com", ignoreCase = true) || line.contains("youtu.be", ignoreCase = true))
            }
            .map { line -> "https://${line.substringBefore(" ")}" }
        return (explicit + bare).filter { it.isNotBlank() }.distinct().toList()
    }

    private fun addedSummary(count: Int, capped: Boolean): String {
        val songs = if (count == 1) "Added 1 song." else "Added $count songs."
        return if (capped) "$songs Playlist is full at $MAX_PLAYLIST." else songs
    }

    private fun songCount(count: Int): String {
        return when (count) {
            0 -> "Playlist is empty."
            1 -> "1 song"
            else -> "$count songs"
        }
    }

    override fun onCleared() {
        controller?.removeListener(playerListener)
        MediaController.releaseFuture(controllerFuture)
        super.onCleared()
    }

    private fun attach(player: MediaController) {
        if (controller === player) {
            publish()
            return
        }
        controller = player
        player.addListener(playerListener)
        publish()
    }

    private suspend fun awaitController(): MediaController {
        controller?.let { return it }
        return suspendCancellableCoroutine { continuation ->
            controllerFuture.addListener(
                {
                    try {
                        val player = controllerFuture.get()
                        attach(player)
                        if (continuation.isActive) continuation.resume(player)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                },
                ContextCompat.getMainExecutor(getApplication()),
            )
        }
    }

    private fun publish() {
        val player = controller ?: return
        val item = player.currentMediaItem
        val metadata = item?.mediaMetadata
        val duration = player.duration
        val playbackError = player.playerError?.cause?.message ?: player.playerError?.message
        val queue = buildList {
            for (index in 0 until player.mediaItemCount) {
                val media = player.getMediaItemAt(index)
                val meta = media.mediaMetadata
                add(
                    QueueRow(
                        index = index,
                        title = meta.title?.toString().orEmpty().ifBlank { "Untitled" },
                        artist = meta.artist?.toString().orEmpty(),
                        durationSec = meta.extras?.getLong(EXTRA_DURATION_SEC) ?: 0L,
                    ),
                )
            }
        }
        _uiState.update { current ->
            current.copy(
                title = metadata?.title?.toString().orEmpty().ifBlank { current.title },
                artist = metadata?.artist?.toString().orEmpty(),
                isPlaying = player.isPlaying,
                buffering = player.playbackState == Player.STATE_BUFFERING,
                positionMs = player.currentPosition.coerceAtLeast(0L),
                durationMs = if (duration > 0) duration else 0L,
                queue = queue,
                currentIndex = player.currentMediaItemIndex.coerceAtLeast(0),
                currentMediaId = item?.mediaId,
                playbackError = if (player.playerError != null) playbackError else null,
            )
        }
    }

    private fun Track.toMediaItem(): MediaItem {
        return MediaItem.Builder()
            .setMediaId(watchUrl)
            .setUri(watchUrl)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setArtworkUri(artworkUrl?.let(Uri::parse))
                    .setExtras(Bundle().apply { putLong(EXTRA_DURATION_SEC, durationSec) })
                    .setIsPlayable(true)
                    .build(),
            )
            .build()
    }

    private companion object {
        const val PREFS = "player"
        const val KEY_URL = "url"
        const val KEY_PLAYLIST = "playlist"
        const val EXTRA_DURATION_SEC = "duration_sec"
        const val MAX_PLAYLIST = 300
        val URL_PATTERN = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
    }
}
