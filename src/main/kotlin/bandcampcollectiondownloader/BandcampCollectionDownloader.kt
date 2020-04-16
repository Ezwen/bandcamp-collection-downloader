package bandcampcollectiondownloader

import org.zeroturnaround.zip.ZipUtil
import java.lang.Thread.sleep
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
     * Core function called from the main
     */
    fun downloadAll(args: Args) {

        Util.log("Chosen download folder: " + args.pathToDownloadFolder.toAbsolutePath().normalize())

        val cookies =

                if (args.pathToCookiesFile != null) {
                    // Parse JSON cookies (obtained with "Cookie Quick Manager" Firefox addon)
                    Util.log("Loading provided cookies file: ${args.pathToCookiesFile}")
                    CookiesManagement.retrieveCookiesFromFile(args.pathToCookiesFile!!)
                } else {
                    // Try to find cookies stored in default firefox profile
                    Util.log("No provided cookies file, using Firefox cookies…")
                    val cookies = CookiesManagement.retrieveFirefoxCookies()
                    Util.log("Loaded cookies from: " + cookies.source)
                    cookies
                }

        val connector = BandcampAPIConnector(args.bandcampUser, cookies.content, args.timeout)
        val pageName = connector.init()

        Util.log("""Found "$pageName" with ${connector.getAllSaleItemIDs().size} items.""")

        val cacheFilePath = args.pathToDownloadFolder.resolve("bandcamp-collection-downloader.cache")
        val cache = Cache(cacheFilePath)
        val cacheContent = cache.getContent()

        val itemsToDownload = connector.getAllSaleItemIDs().filter { saleItemId -> saleItemId !in cacheContent }

        val alreadyDownloadedItemsCount = connector.getAllSaleItemIDs().size - itemsToDownload.size
        if (alreadyDownloadedItemsCount > 0) {
            Util.log("Skipping $alreadyDownloadedItemsCount already downloaded items (based on '${cacheFilePath.toAbsolutePath().normalize()}').")
        }

        val queue = ArrayBlockingQueue<Runnable>(100)
        val threadPoolExecutor = ThreadPoolExecutor(args.jobs, args.jobs, 1, TimeUnit.HOURS, queue)

        for (saleItemID in itemsToDownload) {
            val task = Runnable() {
                val itemNumber = itemsToDownload.indexOf(saleItemID) + 1
                Util.log("Managing item $itemNumber/${itemsToDownload.size}")
                manageDownloadPage(connector, saleItemID, args, cache)
            }
            if (args.jobs > 1) {
                threadPoolExecutor.execute(task)
            } else {
                task.run()
            }
        }
    }

    private fun manageDownloadPage(connector: BandcampAPIConnector, saleItemId: String, args: Args, cache: Cache) {

        val digitalItem = connector.retrieveDigitalItemData(saleItemId)

        // If null, then the download page is simply invalid and not usable anymore, therefore it can be added to the cache
        if (digitalItem == null) {
            Util.log("Sale Item ID $saleItemId cannot be downloaded anymore (maybe a refund?); skipping")
            cache.add(saleItemId)
            return
        }

        var releasetitle = digitalItem.title
        var artist = digitalItem.artist
        Util.log("""Found release "${digitalItem.title}" from ${digitalItem.artist}.""")

        // Skip preorders
        val dateFormatter = DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("dd MMM yyyy HH:mm:ss zzz").toFormatter(Locale.ENGLISH)
        val releaseUTC = ZonedDateTime.parse(digitalItem.package_release_date, dateFormatter).toInstant()
        if (releaseUTC > Instant.now()) {
            Util.log("Sale Item ID $saleItemId ($artist − $releasetitle) is a preorder; skipping")
            return
        }

        val releaseDate = digitalItem.package_release_date
        val releaseYear = releaseDate.subSequence(7, 11)
        val isSingleTrack: Boolean = digitalItem.download_type == "t"

        val downloadUrl = connector.retrieveRealDownloadURL(saleItemId, args.audioFormat)
                ?: throw BandCampDownloaderError("No URL found (is the download format correct?)")// digitalItem.downloads[args.audioFormat]?.get("url").orEmpty()

        // Replace invalid chars by similar unicode chars
        releasetitle = Util.replaceInvalidCharsByUnicode(releasetitle)
        artist = Util.replaceInvalidCharsByUnicode(artist)

        // Prepare artist and release folder
        val releaseFolderName = "$releaseYear - $releasetitle"
        val artistFolderPath = Paths.get("${args.pathToDownloadFolder}").resolve(artist)
        val releaseFolderPath = artistFolderPath.resolve(releaseFolderName)

        val coverURL = connector.getCoverURL(saleItemId)

        // Download release, with as many retries as configured
        val attempts = args.retries + 1
        for (i in 1..attempts) {
            if (i > 1) {
                Util.log("Retrying download (${i - 1}/${args.retries}).")
                sleep(1000)
            }
            try {
                val downloaded = downloadRelease(downloadUrl, artistFolderPath, releaseFolderPath, isSingleTrack, args.timeout, coverURL)

                if (downloaded) {
                    Util.log("done.")
                } else {
                    Util.log("Release already exists on disk, skipping.")
                }
                if (saleItemId !in cache.getContent()) {
                    cache.add(saleItemId)
                }
                break
            } catch (e: Throwable) {
                Util.log("""Error while downloading: "${e.javaClass.name}: ${e.message}".""")
                if (i == attempts) {
                    if (args.ignoreFailedReleases) {
                        Util.log("Could not download release after ${args.retries} retries.")
                    } else {
                        throw BandCampDownloaderError("Could not download release after ${args.retries} retries.")
                    }
                }
            }
        }
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