package bandcampcollectiondownloader.cli

import bandcampcollectiondownloader.core.BandcampCollectionDownloader
import bandcampcollectiondownloader.core.Constants
import bandcampcollectiondownloader.util.DryIO
import bandcampcollectiondownloader.util.IO
import bandcampcollectiondownloader.util.RealIO
import picocli.CommandLine
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.Callable


@CommandLine.Command(
    name = "java -jar bandcamp-collection-downloader.jar",
    description = ["Download items from an existing Bandcamp account collection."],
    mixinStandardHelpOptions = true,
    version = [Constants.VERSION]
)
class Command : Callable<Int> {

    @CommandLine.Parameters(
        arity = "1..1",
        description = ["The Bandcamp user account from which all releases must be downloaded."]
    )
    var bandcampUser: String = ""

    @CommandLine.Option(
        names = ["--cookies-file", "-c"], required = false,
        description = [
            "A file containing valid Bandcamp credential cookies.",
            """The file must either be obtained using the Firefox extension "Cookie Quick Manager" (https://addons.mozilla.org/en-US/firefox/addon/cookie-quick-manager/) or using the Chrome extension "cookies.txt" (https://chrome.google.com/webstore/detail/cookiestxt/njabckikapfpffapmjgojcnbfjonfjfg).""",
            "If no cookies file is provided, cookies from the local Firefox installation are used (Windows and Linux)."
        ]
    )
    var pathToCookiesFile: Path? = null

    @CommandLine.Option(
        names = ["--skip-hidden", "-s"], required = false,
        description = ["Don't download hidden items of the collection."]
    )
    var skipHiddenItems: Boolean = false

    @CommandLine.Option(
        names = ["--audio-format", "-f"], required = false,
        description = ["The chosen audio format of the files to download (default: \${DEFAULT-VALUE}).", "Possible values: flac, wav, aac-hi, mp3-320, aiff-lossless, vorbis, mp3-v0, alac."]
    )
    var audioFormat: String = "vorbis"

    @CommandLine.Option(
        names = ["--download-folder", "-d"], required = false,
        description = ["The folder in which downloaded releases must be extracted.", "The following structure is considered: <pathToDownloadFolder>/<artist>/<year> - <release>.", "(default: current folder)"]
    )
    var pathToDownloadFolder: Path = Paths.get(".")

    @CommandLine.Option(
        names = ["-r", "--retries"],
        usageHelp = false,
        description = ["Amount of retries for each HTTP connection (default: 3)."]
    )
    var retries: Int = 3

    @CommandLine.Option(
        names = ["-t", "--timeout"],
        usageHelp = false,
        description = ["Timeout in ms before giving up an HTTP connection (default: 50000)."]
    )
    var timeout: Int = 50000

    @CommandLine.Option(
        names = ["-j", "--jobs"],
        usageHelp = false,
        description = ["Amount of parallel jobs (threads) to use (default: 4)."]
    )
    var jobs: Int = 4

    @CommandLine.Option(
        names = ["-n", "--dry-run"],
        usageHelp = false,
        description = ["Perform a trial run with no changes made on the filesystem."]
    )
    var dryRun: Boolean = false

    @CommandLine.Option(
        names = ["--debug"],
        usageHelp = false,
        description = ["Print the complete Java stack trace in case of error."]
    )
    var debug: Boolean = false

    override fun call(): Int {
        val io: IO = if (this.dryRun) DryIO() else RealIO()
        val app = BandcampCollectionDownloader(this, io)
        app.downloadAll()
        return 0
    }


}