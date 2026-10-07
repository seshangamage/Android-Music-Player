package com.personal.ytplayer.extractor

import okhttp3.OkHttpClient
import okhttp3.RequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.util.concurrent.TimeUnit

/**
 * HTTP client NewPipe Extractor uses to read YouTube pages.
 * Playback uses the same browser user agent, which googlevideo expects.
 */
object DownloaderImpl : Downloader() {
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    override fun execute(request: Request): Response {
        val method = request.httpMethod()
        val payload = request.dataToSend()
        val body = when {
            payload != null -> RequestBody.create(null, payload)
            method == "POST" || method == "PUT" || method == "PATCH" ->
                RequestBody.create(null, ByteArray(0))
            else -> null
        }
        val builder = okhttp3.Request.Builder()
            .url(request.url())
            .method(request.httpMethod(), body)
            .header("User-Agent", USER_AGENT)

        request.headers().forEach { (name, values) ->
            builder.removeHeader(name)
            values.forEach { value -> builder.addHeader(name, value) }
        }

        client.newCall(builder.build()).execute().use { response ->
            if (response.code == 429) {
                throw ReCaptchaException(
                    "YouTube asked for a captcha. Wait a minute and try again.",
                    request.url(),
                )
            }
            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                response.body?.string(),
                response.request.url.toString(),
            )
        }
    }
}
