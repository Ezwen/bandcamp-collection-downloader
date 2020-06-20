package bandcampcollectiondownloader

import org.zeroturnaround.zip.ZipUtil
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatterBuilder
import java.util.*
import java.util.concurrent.*

object BandcampCollectionDownloader {

    class Cache constructor(private val path: Path) {
        fun getContent(): List<String> {
            if (!path.toFile().exists()) {
                return emptyList()
            }
            return path.toFile().readLines()
        }

        fun add(line: String) {
            if (!Files.exists(path)) {
                Files.createFile(path)
            }
            path.toFile().appendText(line + "\n")
        }
    }


    /**
     * Core function called from the Main function.
     */
    fun downloadAll(args: Args) {
        Util.log("Target bandcamp account: " + args.bandcampUser)
        Util.log("Target download folder: " + args.pathToDownloadFolder.toAbsolutePath().normalize())
        Util.log("Target audio format: " + args.audioFormat)
        Util.logSeparator()

        val cookies =

                if (args.pathToCookiesFile != null) {
                    // Parse JSON cookies (obtained with "Cookie Quick Manager" Firefox addon)
                    Util.log("Loading provided cookies file…")
                    CookiesManagement.retrieveCookiesFromFile(args.pathToCookiesFile!!)
                } else {
                    // Try to find cookies stored in default firefox profile
                    Util.log("No provided cookies file, using Firefox cookies…")
                    CookiesManagement.retrieveFirefoxCookies()
                }
        Util.log("Loaded cookies from: " + cookies.source)
        Util.logSeparator()

        // Connect to bandcamp
        Util.log("Connecting to Bandcamp…")
        val connector = BandcampAPIConnector(args.bandcampUser, cookies.content, args.timeout, args.retries)
        connector.init()
        val pageName = connector.getBandcampPageName()
        Util.log("""Found "$pageName" with ${connector.getAllSaleItemIDs().size} items.""")

        // Prepare/load cache file
        val cacheFilePath = args.pathToDownloadFolder.resolve("bandcamp-collection-downloader.cache")
        val cache = Cache(cacheFilePath)
        val cacheContent = cache.getContent()

        // Only work on items that have not been downloaded yet
        val itemsToDownload = connector.getAllSaleItemIDs().filter { saleItemId -> saleItemId !in cacheContent }
        val alreadyDownloadedItemsCount = connector.getAllSaleItemIDs().size - itemsToDownload.size
        if (alreadyDownloadedItemsCount > 0) {
            Util.log("Ignoring $alreadyDownloadedItemsCount already downloaded items (based on '${cacheFilePath.toAbsolutePath().normalize()}').")
        }

        Util.logSeparator()

        // Prepare for parallel downloads
        val queue = ArrayBlockingQueue<Runnable>(50000)
        val threadPoolExecutor = ThreadPoolExecutor(args.jobs, args.jobs, 0, TimeUnit.HOURS, queue)

        // For each release of the bandcamp account that is yet to be downloaded
        for (saleItemID in itemsToDownload) {

            // Prepare a task to run in a thread or not
            val task = Runnable {
                val itemNumber = itemsToDownload.indexOf(saleItemID) + 1
                Util.log("Managing item $itemNumber/${itemsToDownload.size}")
                manageDownloadPage(connector, saleItemID, args, cache)
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
    }

    private fun manageDownloadPage(connector: BandcampAPIConnector, saleItemId: String, args: Args, cache: Cache) {

        val digitalItem = connector.retrieveDigitalItemData(saleItemId)

        // If null, then the download page is simply invalid and not usable anymore, therefore it can be added to the cache
        if (digitalItem == null) {
            Util.log("Sale Item ID $saleItemId cannot be downloaded anymore (maybe a refund?); skipping")
            cache.add(saleItemId)
            return
        }

        // Get data (1)
        var releasetitle = digitalItem.title
        var artist = digitalItem.artist
        val printableReleaseName = """"${digitalItem.title}" by ${digitalItem.artist}"""
        Util.log("""Found release $printableReleaseName.""")

        // Handle null release dates
        val releaseDate = digitalItem.package_release_date
        var releaseYear = "unknown" as CharSequence

        if (releaseDate != null) {
            releaseYear = releaseDate.subSequence(7, 11)

            // Skip preorders
            val dateFormatter = DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("dd MMM yyyy HH:mm:ss zzz").toFormatter(Locale.ENGLISH)
            val releaseUTC = ZonedDateTime.parse(digitalItem.package_release_date, dateFormatter).toInstant()
            if (releaseUTC > Instant.now()) {
                Util.log("$printableReleaseName is a preorder; skipping for now.")
                return
            }
        }        
        
        // Get data (2)
        val isSingleTrack: Boolean = digitalItem.download_type == "t"

        // Exit if no download URL can be found with the chosen audio format
        connector.retrieveRealDownloadURL(saleItemId, args.audioFormat)
                ?: throw BandCampDownloaderError("No URL found (is the download format correct?)")

        // Replace invalid chars by similar unicode chars
        releasetitle = Util.replaceInvalidCharsByUnicode(releasetitle)
        artist = Util.replaceInvalidCharsByUnicode(artist)

        // Prepare artist and release folder
        val releaseFolderName = "$releaseYear - $releasetitle"
        val artistFolderPath = Paths.get("${args.pathToDownloadFolder}").resolve(artist)
        val releaseFolderPath = artistFolderPath.resolve(releaseFolderName)
        val coverURL = connector.getCoverURL(saleItemId)

        Util.log("Starting the download of $printableReleaseName.")

        // Download release, with as many retries as configured
        Util.retry({
            val downloadUrl = connector.retrieveRealDownloadURL(saleItemId, args.audioFormat)!!
            val downloaded = downloadRelease(downloadUrl, artistFolderPath, releaseFolderPath, isSingleTrack, args.timeout, coverURL)
            if (downloaded) {
                Util.log("$printableReleaseName successfully downloaded.")
            } else {
                Util.log("$printableReleaseName already exists on disk, skipping.")
            }
            if (saleItemId !in cache.getContent()) {
                cache.add(saleItemId)
            }
        }, args.retries, args.ignoreFailedReleases)

    }


    private fun downloadRelease(fileURL: String, artistFolderPath: Path, releaseFolderPath: Path, isSingleTrack: Boolean, timeout: Int, coverURL: String): Boolean {
        // If the artist folder does not exist, we create it
        if (!Files.exists(artistFolderPath)) {
            Files.createDirectories(artistFolderPath)
        }

        // If the release folder does not exist, we create it
        if (!Files.exists(releaseFolderPath)) {
            Files.createDirectories(releaseFolderPath)
        }

        // If the folder is empty, or if it only contains the zip.part file, we proceed
        val amountFiles = releaseFolderPath.toFile().listFiles()!!.size
        if (amountFiles < 2) {

            // Download content
            val outputFilePath: Path = Util.downloadFile(fileURL, releaseFolderPath, timeout = timeout)

            // If this is a zip, we unzip
            if (!isSingleTrack) {

                // Unzip
                try {
                    ZipUtil.unpack(outputFilePath.toFile(), releaseFolderPath.toFile())
                } finally {
                    // Delete zip
                    Files.delete(outputFilePath)
                }
            }

            // Else if this is a single track, we just fetch the cover
            else {
                Util.downloadFile(coverURL, releaseFolderPath, "cover.jpg", timeout)
            }
            return true
        } else {
            return false
        }
    }
}