package bandcampcollectiondownloader

import picocli.CommandLine


fun main(args: Array<String>) {

    // Parsing args
    System.setProperty("picocli.usage.width", Constants.LINESIZE.toString())
    val parsedArgs: Args =
            try {
                CommandLine.populateCommand(Args(), *args)
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

        try {
            BandcampCollectionDownloader.downloadAll(parsedArgs)
        } catch (e: BandCampDownloaderError) {
            System.err.println("ERROR: ${e.message}")
        }

    }

}

