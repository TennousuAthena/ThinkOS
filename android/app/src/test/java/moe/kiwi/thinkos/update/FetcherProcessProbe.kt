package moe.kiwi.thinkos.update

import kotlinx.coroutines.runBlocking
import java.io.File

/** Child JVM runs the actual production downloader; the parent forcibly kills and then relaunches it. */
internal object FetcherProcessProbe {
    @JvmStatic fun main(args: Array<String>) = runBlocking<Unit> {
        Fetcher("probe", UpdateHttpClient.Timeouts(15_000, 30_000)).use { fetcher ->
            fetcher.download(listOf(args[0]), Fetcher.Artifact(Fetcher.ArtifactKind.APK, args[2].toLong(), args[3]), File(args[1]))
        }
    }
}
