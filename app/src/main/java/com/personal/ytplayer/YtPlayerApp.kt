package com.personal.ytplayer

import android.app.Application
import com.personal.ytplayer.extractor.DownloaderImpl
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import java.util.Locale

class YtPlayerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        NewPipe.init(
            DownloaderImpl,
            Localization.fromLocale(Locale.US),
            ContentCountry("US"),
        )
    }
}
