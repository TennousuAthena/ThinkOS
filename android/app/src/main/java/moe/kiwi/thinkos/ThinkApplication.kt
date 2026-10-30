package moe.kiwi.thinkos

import android.app.Application
import android.util.Log
import moe.kiwi.thinkos.shell.CrashRecovery
import moe.kiwi.thinkos.tz.AssetTzSource
import moe.kiwi.thinkos.tz.TzDatabase
import moe.kiwi.thinkos.tz.Zones

class ThinkApplication : Application() {
    val runtime: RuntimeHost by lazy { RuntimeHost(this) }
    override fun onCreate() {
        super.onCreate()
        CrashRecovery.install(this)
        moe.kiwi.thinkos.i18n.Formats.env = moe.kiwi.thinkos.i18n.AndroidFormatEnv(this)
        // The ROM's tzdata is frozen at build time (2021a here); rules come from the bundled IANA copy.
        runCatching { Zones.install(TzDatabase(AssetTzSource(assets))) }
            .onSuccess { Log.i("ThinkOS", "tzdata ${Zones.database?.version} (${Zones.database?.zones?.size} zones)") }
            .onFailure { Log.e("ThinkOS", "bundled tzdata unavailable; using the platform's", it) }
    }
}
