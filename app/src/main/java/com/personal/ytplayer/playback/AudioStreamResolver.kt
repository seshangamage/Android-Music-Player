package com.personal.ytplayer.playback

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import com.personal.ytplayer.extractor.YoutubeCatalog
import java.io.IOException

/**
 * ExoPlayer asks for each playlist item's YouTube page URL. This swaps in the
 * direct audio URL just before the bytes are requested.
 */
@OptIn(UnstableApi::class)
class AudioStreamResolver : ResolvingDataSource.Resolver {
    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val url = dataSpec.uri.toString()
        if (!isYoutubePage(url)) return dataSpec
        return try {
            val audioUrl = YoutubeCatalog.resolvePlayableUrl(url)
            dataSpec.buildUpon().setUri(Uri.parse(audioUrl)).build()
        } catch (error: IOException) {
            throw error
        } catch (error: Exception) {
            throw IOException(error.message ?: "Could not open the audio stream", error)
        }
    }

    private fun isYoutubePage(url: String): Boolean {
        val host = Uri.parse(url).host?.lowercase().orEmpty()
        if (host.contains("googlevideo.com")) return false
        return host == "youtu.be" ||
            host.endsWith("youtube.com") ||
            host.endsWith("youtube-nocookie.com")
    }
}
