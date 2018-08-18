package bandcampcollectiondownloader

import picocli.CommandLine
import java.nio.file.Path
import java.nio.file.Paths


data class Args(
        @CommandLine.Parameters(arity = "1..1",
                description = arrayOf("The bandcamp user account from which all albums must be downloaded."))
        var bandcampUser: String = "",

        @CommandLine.Option(names = arrayOf("--cookies-file", "-c"), required = true,
                description = arrayOf("A JSON file with valid bandcamp credential cookies.",
                        """"Cookie Quick Manager" can be used to obtain this file after logging into bandcamp.""",
                        "(visit https://addons.mozilla.org/en-US/firefox/addon/cookie-quick-manager/)."))
        var pathToCookiesFile: Path? = null,

        @CommandLine.Option(names = arrayOf("--audio-format", "-f"), required = false,
                description = arrayOf("The chosen audio format of the files to download (default: \${DEFAULT-VALUE}).",
                        "Possible values: flac, wav, aac-hi, mp3-320, aiff-lossless, vorbis, mp3-v0, alac."))
        var audioFormat: String = "vorbis",

        @CommandLine.Option(names = arrayOf("--download-folder", "-d"), required = false,
                description = arrayOf("The folder in which downloaded albums must be extracted.",
                        "The following structure is considered: <pathToDownloadFolder>/<artist>/<year> - <album>.",
                        "(default: current folder)"))
        var pathToDownloadFolder: Path = Paths.get("."),

        @CommandLine.Option(names = arrayOf("-h", "--help"), usageHelp = true, description = arrayOf("Display this help message."))
        var help: Boolean = false

)


fun main(args: Array<String>) {

    // Parsing args
    System.setProperty("picocli.usage.width", "120")
    val parsedArgs: Args =
            try {
                CommandLine.populateCommand<Args>(Args(), *args)
            }

            // If the wrong arguments are given, show help + error message
            catch (e: CommandLine.MissingParameterException) {
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
        val cookiesFile = parsedArgs.pathToCookiesFile!!
        val downloadFormat = parsedArgs.audioFormat
        val downloadFolder = parsedArgs.pathToDownloadFolder
        try {
            downloadAll(cookiesFile, bandcampUser, downloadFormat, downloadFolder)
        } catch (e : BandCampDownloaderError) {
            System.err.println("ERROR: ${e.message}")
        }

    }

}
