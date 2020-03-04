package bandcampcollectiondownloader

import picocli.CommandLine
import java.nio.file.Path
import java.nio.file.Paths


data class Args(
        @CommandLine.Parameters(arity = "1..1",
                description = ["The bandcamp user account from which all albums must be downloaded."])
        var bandcampUser: String = "",

        @CommandLine.Option(names = ["--cookies-file", "-c"], required = false,
                description = ["A JSON file with valid bandcamp credential cookies.", """"Cookie Quick Manager" can be used to obtain this file after logging into bandcamp.""", "(visit https://addons.mozilla.org/en-US/firefox/addon/cookie-quick-manager/).", "If no cookies file is provided, cookies from the local Firefox installation are used (Windows and Linux only)."])
        var pathToCookiesFile: Path? = null,

        @CommandLine.Option(names = ["--audio-format", "-f"], required = false,
                description = ["The chosen audio format of the files to download (default: \${DEFAULT-VALUE}).", "Possible values: flac, wav, aac-hi, mp3-320, aiff-lossless, vorbis, mp3-v0, alac."])
        var audioFormat: String = "vorbis",

        @CommandLine.Option(names = ["--download-folder", "-d"], required = false,
                description = ["The folder in which downloaded albums must be extracted.", "The following structure is considered: <pathToDownloadFolder>/<artist>/<year> - <album>.", "(default: current folder)"])
        var pathToDownloadFolder: Path = Paths.get("."),

        @CommandLine.Option(names = ["-h", "--help"], usageHelp = true, description = ["Display this help message."])
        var help: Boolean = false,

        @CommandLine.Option(names = ["-r", "--retries"], usageHelp = false, description = ["Amount of retries when downloading an album (default: 3)."])
        var retries: Int = 3,

        @CommandLine.Option(names = ["-t", "--timeout"], usageHelp = false, description = ["Timeout in ms before giving up an HTTP connection (default: 5000)."])
        var timeout: Int = 50000,

        @CommandLine.Option(names = ["-s", "--stop-on-existing-album"], description = ["Stops all downloads as soon as one album pre-exists in the download folder."])
        var stopOnExistingAlbum: Boolean = false,

        @CommandLine.Option(names = ["-e", "--skip-failed-albums"], description = ["Skip albums that fail to download after the specified number of retries."])
        var ignoreFailedAlbums: Boolean = false

)


fun main(args: Array<String>) {

    // Parsing args
    System.setProperty("picocli.usage.width", "130")
    val parsedArgs: Args =
            try {
                CommandLine.populateCommand<Args>(Args(), *args)
            }

            // If the wrong arguments are given, show help + error message
            catch (e: CommandLine.ParameterException) {
                CommandLine.usage(Args(), System.out)
                System.err.println(e.message)
                return
            }

    // If --help, then only show help and quit
    if (parsedArgs.help) {
        CommandLine.usage(Args(), System.out)
    }

    // Else, parse arguments and run
    else {
        val bandcampUser = parsedArgs.bandcampUser
        val cookiesFile = parsedArgs.pathToCookiesFile
        val downloadFormat = parsedArgs.audioFormat
        val downloadFolder = parsedArgs.pathToDownloadFolder
        val retries = parsedArgs.retries
        val timeout = parsedArgs.timeout
        val stopOnExistingAlbum = parsedArgs.stopOnExistingAlbum
        val ignoreFailedAlbums = parsedArgs.ignoreFailedAlbums
        try {
            downloadAll(cookiesFile, bandcampUser, downloadFormat, downloadFolder, retries, timeout, stopOnExistingAlbum, ignoreFailedAlbums)
        } catch (e: BandCampDownloaderError) {
            System.err.println("ERROR: ${e.message}")
        }

    }

}
