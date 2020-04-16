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


object BandcampCollectionDownloader {


    /**
     * Core function called from the main
     */
    fun downloadAll(args: Args) {


        val cookies =

                if (args.pathToCookiesFile != null) {
                    // Parse JSON cookies (obtained with "Cookie Quick Manager" Firefox addon)
                    println("Loading provided cookies file: ${args.pathToCookiesFile}")
                    CookiesManagement.retrieveCookiesFromFile(args.pathToCookiesFile!!)
                } else {
                    // Try to find cookies stored in default firefox profile
                    println("No provided cookies file, using Firefox cookies.")
                    CookiesManagement.retrieveFirefoxCookies()
                }

        val collection = BandcampAPIHelper.getCollection(cookies, args.timeout, args.bandcampUser)

        println("Found a collection of ${collection.size} items.")

        val cacheFile = args.pathToDownloadFolder.resolve("bandcamp-collection-downloader.cache")
        val cache = loadCache(cacheFile).toMutableList()

        val itemsToDownload = collection.filter { (saleItemId, _) -> saleItemId !in cache }

        val alreadyDownloadedItemsCount = collection.size - itemsToDownload.size
        if (alreadyDownloadedItemsCount > 0) {
            println("Skipping $alreadyDownloadedItemsCount already downloaded items, based on '${cacheFile.fileName}'.")
        }

        // For each download page
        for (entry in itemsToDownload) {
            val itemNumber = itemsToDownload.entries.indexOf(entry) + 1
            println("Managing item $itemNumber/${itemsToDownload.size}")
            manageDownloadPage(entry.key, entry.value, cookies, args, cache, cacheFile )
        }
    }

    private fun manageDownloadPage(saleItemId: String, redownloadUrl: String, cookies: Map<String, String>, args: Args, cache: MutableList<String>, cacheFile: Path) {

        println("Getting data from item page ($redownloadUrl)…")
        val digitalItem = BandcampAPIHelper.getDataBlobFromDownloadPage(redownloadUrl, cookies, args.timeout)

        // If null, then the download page is simply invalid and not usable anymore, therefore it can be added to the cache
        if (digitalItem == null) {
            println("Sale Item ID $saleItemId cannot be downloaded anymore (maybe a refund?); skipping")
            cache.add(saleItemId)
            addToCache(cacheFile, saleItemId)
            return
        }

        // Extract data from blob
        var albumtitle = digitalItem.title
        var artist = digitalItem.artist
        println("""→ found release "${digitalItem.title}" from ${digitalItem.artist}.""")

        // Skip preorders
        val dateFormatter = DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("dd MMM yyyy HH:mm:ss zzz").toFormatter(Locale.ENGLISH)
        val releaseUTC = ZonedDateTime.parse(digitalItem.package_release_date, dateFormatter).toInstant()
        if (releaseUTC > Instant.now()) {
            println("Sale Item ID $saleItemId ($artist - $albumtitle) is a preorder; skipping")
            return
        }

        val releaseDate = digitalItem.package_release_date
        val releaseYear = releaseDate.subSequence(7, 11)
        val isSingleTrack: Boolean = digitalItem.download_type == "t"
        val downloadUrl = digitalItem.downloads[args.audioFormat]?.get("url").orEmpty()
        if (downloadUrl.isEmpty()) {
            throw BandCampDownloaderError("No URL found (is the download format correct?)")
        }
        val artid = digitalItem.art_id

        // Replace invalid chars by similar unicode chars
        albumtitle = Util.replaceInvalidCharsByUnicode(albumtitle)
        artist = Util.replaceInvalidCharsByUnicode(artist)

        // Prepare artist and album folder
        val albumFolderName = "$releaseYear - $albumtitle"
        val artistFolderPath = Paths.get("${args.pathToDownloadFolder}").resolve(artist)
        val albumFolderPath = artistFolderPath.resolve(albumFolderName)

        // Download album, with as many retries as configured
        val attempts = args.retries + 1
        for (i in 1..attempts) {
            if (i > 1) {
                println("Retrying download (${i - 1}/${args.retries}).")
                sleep(1000)
            }
            try {
                val downloaded = downloadAlbum(artistFolderPath, albumFolderPath, downloadUrl, cookies, isSingleTrack, artid, args.timeout)
                if (downloaded) {
                    println("done.")
                } else {
                    println("Release already exists on disk, skipping.")
                }
                if (saleItemId !in cache) {
                    cache.add(saleItemId)
                    addToCache(cacheFile, saleItemId)
                }
                break
            } catch (e: Throwable) {
                println("""Error while downloading: "${e.javaClass.name}: ${e.message}".""")
                if (i == attempts) {
                    if (args.ignoreFailedAlbums) {
                        println("Could not download release after ${args.retries} retries.")
                    } else {
                        throw BandCampDownloaderError("Could not download release after ${args.retries} retries.")
                    }
                }
            }
        }
    }


    private fun downloadAlbum(artistFolderPath: Path, albumFolderPath: Path, downloadUrl: String, cookies: Map<String, String>, isSingleTrack: Boolean, artid: String, timeout: Int): Boolean {
        // If the artist folder does not exist, we create it
        if (!Files.exists(artistFolderPath)) {
            Files.createDirectories(artistFolderPath)
        }

        // If the album folder does not exist, we create it
        if (!Files.exists(albumFolderPath)) {
            Files.createDirectories(albumFolderPath)
        }

        // If the folder is empty, or if it only contains the zip.part file, we proceed
        val amountFiles = albumFolderPath.toFile().listFiles()!!.size
        if (amountFiles < 2) {

            val statdownloadParsed = BandcampAPIHelper.getStatData(downloadUrl, cookies, timeout)

            // Use real download URL if it exists; otherwise the original URL should hopefully work instead
            val realDownloadURL = statdownloadParsed.download_url ?: downloadUrl

            // Download content
            val outputFilePath: Path = Util.downloadFile(realDownloadURL, albumFolderPath, timeout = timeout)

            // If this is a zip, we unzip
            if (!isSingleTrack) {

                // Unzip
                try {
                    ZipUtil.unpack(outputFilePath.toFile(), albumFolderPath.toFile())
                } finally {
                    // Delete zip
                    Files.delete(outputFilePath)
                }
            }

            // Else if this is a single track, we just fetch the cover
            else {
                val coverURL = BandcampAPIHelper.getCoverURL(artid)
                Util.downloadFile(coverURL, albumFolderPath, "cover.jpg", timeout)
            }
            return true
        } else {
            return false
        }
    }


    private fun loadCache(path: Path): List<String> {
        if (!path.toFile().exists()) {
            return emptyList()
        }

        return path.toFile().readLines()
    }

    private fun addToCache(path: Path, line: String) {
        if (!Files.exists(path)) {
            Files.createFile(path)
        }
        path.toFile().appendText(line + "\n")
    }

}