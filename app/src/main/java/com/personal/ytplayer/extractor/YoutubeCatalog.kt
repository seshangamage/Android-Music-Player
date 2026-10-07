package com.personal.ytplayer.extractor

import android.net.Uri
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.ContentAvailability
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

data class Track(
    val title: String,
    val artist: String,
    val watchUrl: String,
    val artworkUrl: String?,
    val durationSec: Long,
)

data class PlayQueue(
    val name: String,
    val tracks: List<Track>,
    val startIndex: Int,
    val truncated: Boolean,
)

/**
 * Turns a YouTube link into a list of tracks, and each track into a direct audio URL
 * when ExoPlayer is ready to open it. Nothing is written to storage.
 */
object YoutubeCatalog {
    const val MAX_TRACKS = 250

    private val resolvedAudio = ConcurrentHashMap<String, String>()

    fun load(raw: String): PlayQueue {
        val target = parse(raw)
        if (!target.listId.isNullOrBlank()) {
            try {
                val queue = loadPlaylist(target)
                if (queue.tracks.isNotEmpty()) return queue
            } catch (error: Exception) {
                if (target.videoId == null) throw friendly(error)
            }
        }
        if (target.videoId != null || target.normalized.contains("watch") || target.normalized.contains("youtu.be")) {
            return try {
                loadSingle(target.normalized)
            } catch (error: Exception) {
                throw friendly(error)
            }
        }
        throw IllegalArgumentException("That link is not a YouTube video or playlist.")
    }

    fun resolvePlayableUrl(pageUrl: String): String {
        resolvedAudio[pageUrl]?.let { return it }
        try {
            val info = StreamInfo.getInfo(pageUrl)
            val audio = pickAudio(info)
            val streamUrl = audio.content
            if (!audio.isUrl || !streamUrl.startsWith("http")) {
                throw IOException("No direct audio stream for \"${info.name}\".")
            }
            resolvedAudio[pageUrl] = streamUrl
            val canonical = info.url
            if (!canonical.isNullOrBlank()) resolvedAudio[canonical] = streamUrl
            return streamUrl
        } catch (error: IOException) {
            throw error
        } catch (error: Exception) {
            throw IOException(friendly(error).message, error)
        }
    }

    private fun loadSingle(url: String): PlayQueue {
        val info = StreamInfo.getInfo(url)
        val audio = pickAudio(info)
        val watchUrl = info.url?.takeIf { it.isNotBlank() } ?: url
        if (audio.isUrl && audio.content.startsWith("http")) {
            resolvedAudio[watchUrl] = audio.content
            resolvedAudio[url] = audio.content
        }
        val track = Track(
            title = info.name.ifBlank { "Untitled" },
            artist = info.uploaderName.orEmpty(),
            watchUrl = watchUrl,
            artworkUrl = bestArtwork(info.thumbnails),
            durationSec = info.duration,
        )
        return PlayQueue(
            name = track.title,
            tracks = listOf(track),
            startIndex = 0,
            truncated = false,
        )
    }

    private fun loadPlaylist(target: LinkTarget): PlayQueue {
        val playlistUrl = "https://www.youtube.com/playlist?list=${target.listId}"
        val info = PlaylistInfo.getInfo(playlistUrl)
        val items = ArrayList<StreamInfoItem>()
        items.addAll(info.relatedItems.orEmpty())
        var page = info.nextPage
        val service = NewPipe.getServiceByUrl(playlistUrl)
        while (page != null && items.size < MAX_TRACKS) {
            val more = PlaylistInfo.getMoreItems(service, playlistUrl, page)
            items.addAll(more.items.orEmpty())
            page = more.nextPage
        }
        val mapped = items.mapNotNull { it.toTrack() }.distinctBy { it.watchUrl }
        val truncated = page != null || mapped.size > MAX_TRACKS
        val tracks = mapped.take(MAX_TRACKS)
        if (tracks.isEmpty()) {
            throw IllegalStateException("That playlist has no playable videos.")
        }
        val start = startIndex(tracks, target)
        val name = info.name?.takeIf { it.isNotBlank() } ?: "Playlist"
        return PlayQueue(name, tracks, start, truncated)
    }

    private fun startIndex(tracks: List<Track>, target: LinkTarget): Int {
        val videoId = target.videoId
        if (!videoId.isNullOrBlank()) {
            val match = tracks.indexOfFirst { track ->
                val uri = Uri.parse(track.watchUrl)
                uri.getQueryParameter("v") == videoId || uri.lastPathSegment == videoId
            }
            if (match >= 0) return match
        }
        val index = target.index
        if (index != null && index in tracks.indices) return index
        return 0
    }

    private fun StreamInfoItem.toTrack(): Track? {
        if (url.isNullOrBlank()) return null
        if (streamType == StreamType.LIVE_STREAM || streamType == StreamType.AUDIO_LIVE_STREAM) {
            return null
        }
        if (contentAvailability == ContentAvailability.UPCOMING ||
            contentAvailability == ContentAvailability.PAID ||
            contentAvailability == ContentAvailability.MEMBERSHIP
        ) {
            return null
        }
        return Track(
            title = name.ifBlank { "Untitled" },
            artist = uploaderName.orEmpty(),
            watchUrl = url,
            artworkUrl = bestArtwork(thumbnails),
            durationSec = duration,
        )
    }

    private fun pickAudio(info: StreamInfo): AudioStream {
        val direct = info.audioStreams.filter { stream ->
            stream.isUrl && stream.content.startsWith("http")
        }
        val progressive = direct.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
        val pool = progressive.ifEmpty { direct }
        if (pool.isEmpty()) {
            throw IOException("No audio stream for \"${info.name}\".")
        }
        return pool.maxWith(
            compareBy<AudioStream> { rank(it) }
                .thenBy { if (it.format == MediaFormat.M4A) 1 else 0 },
        )
    }

    private fun rank(stream: AudioStream): Int {
        val average = stream.averageBitrate
        val bitrate = stream.bitrate
        return maxOf(average, bitrate).coerceAtLeast(0)
    }

    private fun bestArtwork(images: List<org.schabi.newpipe.extractor.Image>?): String? {
        if (images.isNullOrEmpty()) return null
        return images.maxByOrNull { image ->
            val width = image.width.toLong().coerceAtLeast(0)
            val height = image.height.toLong().coerceAtLeast(0)
            width * height
        }?.url
    }

    private fun parse(raw: String): LinkTarget {
        val trimmed = raw.trim()
        require(trimmed.isNotEmpty()) { "Paste a YouTube link first." }
        val withScheme = when {
            trimmed.startsWith("https://") || trimmed.startsWith("http://") -> trimmed
            else -> "https://$trimmed"
        }
        val uri = Uri.parse(withScheme)
        val host = uri.host?.lowercase()?.removePrefix("www.")
            ?: throw IllegalArgumentException("That is not a YouTube link.")
        val allowed = host == "youtu.be" ||
            host == "youtube.com" ||
            host == "music.youtube.com" ||
            host == "m.youtube.com" ||
            host == "youtube-nocookie.com"
        require(allowed) { "Paste a youtube.com or youtu.be link." }

        val shortsId = Regex("^/shorts/([\\w-]{6,})").find(uri.path.orEmpty())?.groupValues?.get(1)
        val videoId = shortsId ?: when (host) {
            "youtu.be" -> uri.pathSegments.firstOrNull()?.takeIf { it.isNotBlank() }
            else -> uri.getQueryParameter("v")?.takeIf { it.isNotBlank() }
        }
        val listId = uri.getQueryParameter("list")?.takeIf { it.isNotBlank() }
        val index = uri.getQueryParameter("index")?.toIntOrNull()?.minus(1)?.takeIf { it >= 0 }

        val normalized = when {
            shortsId != null -> {
                val builder = Uri.Builder()
                    .scheme("https")
                    .authority("www.youtube.com")
                    .path("watch")
                    .appendQueryParameter("v", shortsId)
                if (listId != null) builder.appendQueryParameter("list", listId)
                builder.build().toString()
            }
            host == "youtu.be" -> uri.buildUpon().scheme("https").build().toString()
            else -> uri.buildUpon().scheme("https").authority("www.youtube.com").build().toString()
        }
        return LinkTarget(normalized, listId, videoId, index)
    }

    private fun friendly(error: Exception): Exception {
        return when (error) {
            is IllegalArgumentException -> error
            is AgeRestrictedContentException ->
                IllegalStateException("That video is age-restricted, so it cannot be played without a YouTube login.")
            is GeographicRestrictionException ->
                IllegalStateException("That video is blocked in this region.")
            is ContentNotAvailableException ->
                IllegalStateException("That video is private, deleted, or unavailable.")
            else -> IllegalStateException(error.message ?: "Could not read that YouTube link.", error)
        }
    }

    private data class LinkTarget(
        val normalized: String,
        val listId: String?,
        val videoId: String?,
        val index: Int?,
    )
}
