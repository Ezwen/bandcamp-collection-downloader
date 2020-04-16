package bandcampcollectiondownloader

import BandCampDownloaderError
import com.google.gson.Gson
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
    fun downloadAll(cookiesFile: Path?, bandcampUser: String, downloadFormat: String, downloadFolder: Path, retries: Int, timeout: Int, ignoreFailedAlbums: Boolean) {

        val gson = Gson()

        val cookies =

                if (cookiesFile != null) {
                    // Parse JSON cookies (obtained with "Cookie Quick Manager" Firefox addon)
                    println("Loading provided cookies file: $cookiesFile")
                    CookiesManagement.retrieveCookiesFromFile(cookiesFile, gson)
                } else {
                    // Try to find cookies stored in default firefox profile
                    println("No provided cookies file, using Firefox cookies.")
                    CookiesManagement.retrieveFirefoxCookies()
                }

        val collection = BandcampAPIHelper.getCollection(cookies, timeout, bandcampUser, gson)


        // For each download page
        for ((saleItemId, redownloadUrl) in collection) {
            manageDownloadPage(saleItemId, redownloadUrl, cookies, gson, timeout, downloadFormat, downloadFolder, retries, ignoreFailedAlbums)
        }
    }

    private fun manageDownloadPage(saleItemId: String, redownloadUrl: String, cookies: Map<String, String>, gson: Gson, timeout: Int, downloadFormat: String, downloadFolder: Path, retries: Int, ignoreFailedAlbums: Boolean) {

        val cacheFile = downloadFolder.resolve("bandcamp-collection-downloader.cache")
        val cache = loadCache(cacheFile).toMutableList()

        // less Bandcamp-intensive way of checking already downloaded things
        if (saleItemId in cache) {
            println("Sale Item ID $saleItemId is already downloaded; skipping")
            return
        }

        val downloadPageJsonParsed = BandcampAPIHelper.getDataBlobFromDownloadPage(redownloadUrl, cookies, gson, timeout)

        // If null, then the download page is simply invalid and not usable anymore, therefore it can be added to the cache
        if (downloadPageJsonParsed == null) {
            println("Sale Item ID $saleItemId cannot be downloaded anymore (maybe a refund?); skipping")
            cache.add(saleItemId)
            addToCache(cacheFile, saleItemId)
            return
        }

        // Extract data from blob
        val digitalItem = downloadPageJsonParsed.digital_items[0]
        var albumtitle = digitalItem.title
        var artist = digitalItem.artist

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
        val downloadUrl = digitalItem.downloads[downloadFormat]?.get("url").orEmpty()
        if (downloadUrl.isEmpty()) {
            throw BandCampDownloaderError("No URL found (is the download format correct?)")
        }
        val artid = digitalItem.art_id

        // Replace invalid chars by similar unicode chars
        albumtitle = Util.replaceInvalidCharsByUnicode(albumtitle)
        artist = Util.replaceInvalidCharsByUnicode(artist)

        // Prepare artist and album folder
        val albumFolderName = "$releaseYear - $albumtitle"
        val artistFolderPath = Paths.get("$downloadFolder").resolve(artist)
        val albumFolderPath = artistFolderPath.resolve(albumFolderName)

        println("""→ found release "$albumtitle" from $artist ($releaseYear).""")

        // Download album, with as many retries as configured
        val attempts = retries + 1
        for (i in 1..attempts) {
            if (i > 1) {
                println("Retrying download (${i - 1}/$retries).")
                sleep(1000)
            }
            try {
                val downloaded = downloadAlbum(artistFolderPath, albumFolderPath, downloadUrl, cookies, gson, isSingleTrack, artid, timeout)
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
                    if (ignoreFailedAlbums) {
                        println("Could not download release after $retries retries.")
                    } else {
                        throw BandCampDownloaderError("Could not download release after $retries retries.")
                    }
                }
            }
        }
    }


    private fun downloadAlbum(artistFolderPath: Path, albumFolderPath: Path, downloadUrl: String, cookies: Map<String, String>, gson: Gson, isSingleTrack: Boolean, artid: String, timeout: Int): Boolean {
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

            val statdownloadParsed = BandcampAPIHelper.getStatData(downloadUrl, cookies, timeout, gson)

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