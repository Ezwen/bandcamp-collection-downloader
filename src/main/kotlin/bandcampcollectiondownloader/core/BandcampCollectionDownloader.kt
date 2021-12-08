package bandcampcollectiondownloader.core

import bandcampcollectiondownloader.cli.Command
import bandcampcollectiondownloader.util.IO
import bandcampcollectiondownloader.util.Logger
import bandcampcollectiondownloader.util.Util
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.util.*
import java.util.concurrent.*
import java.util.stream.Collectors

class BandcampCollectionDownloader(
    private val args: Command,
    private val io: IO,
    private val util: Util,
    private val logger: Logger,
    private val cookiesManagement: CookiesManagement
) {

    class Cache constructor(private val path: Path, private val io: IO) {
        fun getContent(): List<String> {
            if (!path.toFile().exists()) {
                return emptyList()
            }
            return io.readLines(path).map { line -> line.split("|")[0] }
        }

        fun add(id: String, description: String) {
            if (!Files.exists(path)) {
                io.createFile(path)
            }
            io.append(path, "$id| $description\n")
        }
    }


    /**
     * Core function called from the Main function.
     */
    fun downloadAll() {
        logger.log("Target Bandcamp account: " + args.bandcampUser)
        logger.log("Target download folder: " + args.pathToDownloadFolder.toAbsolutePath().normalize())
        logger.log("Target audio format: " + args.audioFormat)
        logger.logSeparator()

        // Gather cookies
        val cookiesCandidates: MutableList<CookiesManagement.Cookies> = ArrayList()
        if (args.pathToCookiesFile != null) {
            // Parse JSON cookies (obtained with "Cookie Quick Manager" Firefox addon)
            logger.log("Loading provided cookies file…")
            cookiesCandidates.add(cookiesManagement.retrieveCookiesFromFile(args.pathToCookiesFile!!))
        } else {
            // Try to find cookies stored in default firefox profile
            logger.log("No provided cookies file, using Firefox cookies…")
            cookiesCandidates.addAll(cookiesManagement.retrieveFirefoxCookies())
        }

        // Try to connect to bandcamp with each provided cookies
        var connector: BandcampAPIConnector? = null
        for (cookies in cookiesCandidates) {
            try {
                logger.log("Trying cookies from: " + cookies.source)
                logger.logSeparator()

                // Try to connect to bandcamp with the cookies
                logger.log("Connecting to Bandcamp…")
                val candidateConnector =
                    BandcampAPIConnector(
                        args.bandcampUser,
                        cookies.content,
                        args.skipHiddenItems,
                        args.timeout,
                        args.retries,
                        util
                    )
                candidateConnector.init()
                val pageName = candidateConnector.getBandcampPageName()
                logger.log("""Found "$pageName" with ${candidateConnector.getAllSaleItemIDs().size} items.""")

                // If we reach this point then the cookies worked and we have a working connection
                connector = candidateConnector
                break

            } catch (e: Throwable) {
                logger.log("""Cookies from ${cookies.source} did not work.""")
                logger.debug(e)
            }
        }

        if (connector == null) {
            throw BandCampDownloaderError("Could not connect to the Bandcamp API with the provided cookies.")
        }

        // Prepare/load cache file
        val cacheFilePath = args.pathToDownloadFolder.resolve("bandcamp-collection-downloader.cache")
        val cache = Cache(cacheFilePath, io)
        val cacheContent = cache.getContent()

        // Only work on items that have not been downloaded yet
        val itemsToDownload = connector.getAllSaleItemIDs().filter { saleItemId -> saleItemId !in cacheContent }
        val alreadyDownloadedItemsCount = connector.getAllSaleItemIDs().size - itemsToDownload.size
        if (alreadyDownloadedItemsCount > 0) {
            logger.log(
                "Ignoring $alreadyDownloadedItemsCount already downloaded items (based on '${
                    cacheFilePath.toAbsolutePath().normalize()
                }')."
            )
        }

        logger.logSeparator()

        // Prepare for parallel downloads
        val queue = ArrayBlockingQueue<Runnable>(50000)
        val threadPoolExecutor = ThreadPoolExecutor(args.jobs, args.jobs, 0, TimeUnit.HOURS, queue)

        // For each release of the bandcamp account that is yet to be downloaded
        for (saleItemID in itemsToDownload) {

            // Prepare a task to run in a thread or not
            val task = Runnable {
                val itemNumber = itemsToDownload.indexOf(saleItemID) + 1
                logger.log("Managing item $itemNumber/${itemsToDownload.size}")

                try {
                    manageDownloadPage(connector, saleItemID, cache)
                } catch (e: BandCampDownloaderError) {
                    logger.log("Could not download item: " + e.message)
                    logger.debug(e)
                }
            }

            // Use threads only if j is more than 1
            if (args.jobs > 1) {
                threadPoolExecutor.execute(task)
            } else {
                task.run()
            }
        }


        // To make sure we quit once all is done
        threadPoolExecutor.shutdown()
        threadPoolExecutor.awaitTermination(1, TimeUnit.DAYS)
    }

    private fun manageDownloadPage(connector: BandcampAPIConnector, saleItemId: String, cache: Cache) {

        val digitalItem = connector.retrieveDigitalItemData(saleItemId)

        // If null, then the download page is simply invalid and not usable anymore, therefore it can be added to the cache
        if (digitalItem == null) {
            logger.log("Sale Item ID $saleItemId cannot be downloaded anymore (maybe a refund?); skipping")
            cache.add(saleItemId, "UNKNOWN")
            return
        }

        // Get data (1)
        var releasetitle = digitalItem.title
        var artist = digitalItem.artist
        var releaseUTC: ZonedDateTime? = null
        var releaseYear: CharSequence = "0000"

        // Skip preorders
        val dateFormatter = DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("dd MMM yyyy HH:mm:ss zzz")
            .toFormatter(Locale.ENGLISH)

        if (digitalItem.package_release_date != null) {
            releaseUTC = ZonedDateTime.parse(digitalItem.package_release_date, dateFormatter)
            releaseYear = releaseUTC.get(ChronoField.YEAR).toString()
        }

        val printableReleaseName = """"${digitalItem.title}" ($releaseYear) by ${digitalItem.artist}"""

        logger.log("""Found release $printableReleaseName (Bandcamp ID: $saleItemId).""")

        if (releaseUTC != null && releaseUTC.toInstant() > Instant.now()) {
            logger.log("$printableReleaseName is a preorder; skipping.")
            return
        }

        // Get data (2)
        val isSingleTrack: Boolean = (digitalItem.download_type == "t" || digitalItem.download_type_str == "track" ||
                digitalItem.item_type == "track")

        // Exit if no download URL can be found with the chosen audio format
        connector.retrieveRealDownloadURL(saleItemId, args.audioFormat)
            ?: throw BandCampDownloaderError("No URL found for item (maybe the release has no digital item, or the provided download format is invalid)")

        // Replace invalid chars by similar unicode chars
        releasetitle = util.replaceInvalidCharsByUnicode(releasetitle)
        artist = util.replaceInvalidCharsByUnicode(artist)

        // Prepare artist and release folder paths
        val downloadFolderPath = Paths.get("${args.pathToDownloadFolder}")
        val releaseFolderName = "$releaseYear - $releasetitle"
        var artistFolderPath = downloadFolderPath.resolve(artist)
        var releaseFolderPath = artistFolderPath.resolve(releaseFolderName)

        // If one of the folders already exists as a file, throw an error
        val message = "The %s folder already exists as a regular file (%s)"
        when {
            Files.isRegularFile(downloadFolderPath) -> throw BandCampDownloaderError(
                message.format(
                    "download",
                    downloadFolderPath.toAbsolutePath().normalize().toString()
                )
            )
            Files.isRegularFile(artistFolderPath) -> throw BandCampDownloaderError(
                message.format(
                    "artist",
                    artistFolderPath.toAbsolutePath().normalize().toString()
                )
            )
            Files.isRegularFile(releaseFolderPath) -> throw BandCampDownloaderError(
                message.format(
                    "release",
                    releaseFolderPath.toAbsolutePath().normalize().toString()
                )
            )
        }

        // If artist or release folder exists with different case, use it instead of the planned one
        if (Files.isDirectory(downloadFolderPath) && !Files.isDirectory(artistFolderPath)) {
            val candidateArtistFolders = Files.list(downloadFolderPath)
                .filter { f -> Files.isDirectory(f) && f.fileName.toString().endsWith(artist, true) }
                .collect(Collectors.toList())
            if (candidateArtistFolders.isNotEmpty()) {
                artistFolderPath = candidateArtistFolders[0]
                val candidateReleaseFolders = Files.list(artistFolderPath)
                    .filter { f -> Files.isDirectory(f) && f.fileName.toString().endsWith(releaseFolderName, true) }
                    .collect(Collectors.toList())
                releaseFolderPath = if (candidateReleaseFolders.isNotEmpty()) {
                    candidateReleaseFolders[0]
                } else {
                    artistFolderPath.resolve(releaseFolderName)
                }
                logger.log(
                    "Using existing folder found with different case: " + releaseFolderPath.toAbsolutePath().normalize()
                        .toString()
                )
            }
        }

        // Find cover URL
        val coverURL = connector.getCoverURL(saleItemId)

        // Download release, with as many retries as configured
        logger.log("Starting the download of $printableReleaseName.")
        util.retry({
            val downloadUrl = connector.retrieveRealDownloadURL(saleItemId, args.audioFormat)!!
            val downloaded =
                downloadRelease(downloadUrl, artistFolderPath, releaseFolderPath, isSingleTrack, args.timeout, coverURL)
            if (downloaded) {
                logger.log("$printableReleaseName successfully downloaded.")
            } else {
                logger.log("$printableReleaseName already exists on disk, skipping.")
            }
            if (saleItemId !in cache.getContent()) {
                cache.add(saleItemId, printableReleaseName)
            }
        }, args.retries)

    }


    private fun downloadRelease(
        fileURL: String,
        artistFolderPath: Path,
        releaseFolderPath: Path,
        isSingleTrack: Boolean,
        timeout: Int,
        coverURL: String
    ): Boolean {
        // If the artist folder does not exist, we create it
        if (!Files.exists(artistFolderPath)) {
            io.createDirectories(artistFolderPath)
        }

        // If the release folder does not exist, we create it
        if (!Files.exists(releaseFolderPath)) {
            io.createDirectories(releaseFolderPath)
        }

        // If the folder is empty, or if it only contains the zip.part file, we proceed
        val amountFiles =
            if (!args.dryRun || Files.exists(releaseFolderPath)) releaseFolderPath.toFile().listFiles()!!.size
            else 0
        if (amountFiles < 2) {

            // Download content
            val outputFilePath: Path = io.downloadFile(fileURL, releaseFolderPath, timeout = timeout)

            // If this is a zip, we unzip
            if (!isSingleTrack) {
                logger.debug("This is a multiple track release: unzipping the downloaded file")

                // Unzip
                try {
                    io.unzip(outputFilePath, releaseFolderPath)
                } finally {
                    // Delete zip
                    io.delete(outputFilePath)
                }
            }

            // Else if this is a single track, we just fetch the cover
            else {
                logger.debug("This is a single track release: fetching the cover")
                io.downloadFile(coverURL, releaseFolderPath, "cover.jpg", timeout)
            }
            return true
        } else {
            return false
        }
    }
}
