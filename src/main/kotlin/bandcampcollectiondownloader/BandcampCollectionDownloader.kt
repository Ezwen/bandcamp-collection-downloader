package bandcampcollectiondownloader

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.annotations.SerializedName
import org.ini4j.Ini
import org.jsoup.HttpStatusException
import org.jsoup.Jsoup
import org.zeroturnaround.zip.ZipUtil
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.*
import java.util.regex.Pattern
import javax.mail.internet.ContentDisposition

data class ParsedCookie(
        @SerializedName("Name raw")
        val nameRaw: String?,

        @SerializedName("Content raw")
        val contentRaw: String?
)

data class ParsedBandcampData(
        @Suppress("ArrayInDataClass") val digital_items: Array<DigitalItem>
)

data class DigitalItem(
        val downloads: Map<String, Map<String, String>>,
        val package_release_date: String,
        val title: String,
        val artist: String,
        val download_type: String,
        val art_id: String
)

data class ParsedStatDownload(
        val download_url: String
)

fun parsedCookiesToMap(parsedCookies: Array<ParsedCookie>): Map<String, String> {
    val result = HashMap<String, String>()
    for (parsedCookie in parsedCookies) {
        if (parsedCookie.contentRaw.isNullOrEmpty()) {
            throw BandCampDownloaderError("Missing 'Content raw' field in cookie number ${parsedCookies.indexOf(parsedCookie) + 1}.")
        }
        if (parsedCookie.nameRaw.isNullOrEmpty()) {
            throw BandCampDownloaderError("Missing 'Name raw' field in cookie number ${parsedCookies.indexOf(parsedCookie) + 1}.")
        }
        result[parsedCookie.nameRaw!!] = parsedCookie.contentRaw!!
    }
    return result
}

const val BUFFER_SIZE = 4096

/**
 * From http://www.codejava.net/java-se/networking/use-httpurlconnection-to-download-file-from-an-http-url
 */
fun downloadFile(fileURL: String, saveDir: Path, optionalFileName: String = ""): Path {

    val url = URL(fileURL)
    val httpConn = url.openConnection() as HttpURLConnection
    val responseCode = httpConn.responseCode

    // always check HTTP response code first
    if (responseCode == HttpURLConnection.HTTP_OK) {
        val disposition = httpConn.getHeaderField("Content-Disposition")

        val fileName: String =
                when {
                    optionalFileName != "" -> optionalFileName
                    disposition != null -> {
                        val parsedDisposition = ContentDisposition(disposition)
                        parsedDisposition.getParameter("filename")
                    }
                    else -> Paths.get(url.file).fileName.toString()
                }

        // opens input stream from the HTTP connection
        val inputStream = httpConn.inputStream
        val saveFilePath = saveDir.resolve(fileName)
        val saveFilePathString = saveFilePath.toAbsolutePath().toString()

        // opens an output stream to save into file
        val outputStream = FileOutputStream(saveFilePathString)

        val buffer = ByteArray(BUFFER_SIZE)
        var bytesRead = inputStream.read(buffer)
        while (bytesRead != -1) {
            outputStream.write(buffer, 0, bytesRead)
            bytesRead = inputStream.read(buffer)
        }

        outputStream.close()
        inputStream.close()
        httpConn.disconnect()
        return saveFilePath
    } else {
        throw Exception("No file to download. Server replied HTTP code: $responseCode")
    }


}

fun isUnix(): Boolean {
    val os = System.getProperty("os.name").toLowerCase()
    return os.indexOf("nix") >= 0 || os.indexOf("nux") >= 0 || os.indexOf("aix") > 0
}

/**
 * Core function called from the main
 */
fun downloadAll(cookiesFile: Path?, bandcampUser: String, downloadFormat: String, downloadFolder: Path) {
    val gson = Gson()
    val cookies =

            when {
                cookiesFile != null -> {
                    // Parse JSON cookies (obtained with "Cookie Quick Manager" Firefox addon)
                    println("Loading provided cookies file: $cookiesFile")
                    retrieveCookiesFromFile(cookiesFile, gson)
                }
                isUnix() -> {
                    // Try to find cookies stored in default firefox profile
                    println("No provided cookies file, using Firefox cookies.")
                    retrieveFirefoxCookies()
                }
                else -> throw BandCampDownloaderError("No available cookies!")
            }

    // Get collection page with cookies, hence with download links
    val doc = try {
        Jsoup.connect("https://bandcamp.com/$bandcampUser")
                .cookies(cookies)
                .get()
    } catch (e: HttpStatusException) {
        if (e.statusCode == 404) {
            throw BandCampDownloaderError("The bandcamp user '$bandcampUser' does not exist.")
        } else {
            throw e
        }
    }
    println("""Found collection page: "${doc.title()}"""")

    // Get download pages
    val collection = doc.select("span.redownload-item a")

    if (collection.isEmpty()) {
        throw BandCampDownloaderError("No download links could by found in the collection page. This can be caused by an outdated or invalid cookies file.")
    }

    // For each download page
    for (item in collection) {
        val downloadPageURL = item.attr("href")
        val downloadPageJsonParsed = getDataBlobFromDownloadPage(downloadPageURL, cookies, gson)

        // Extract data from blob
        val digitalItem = downloadPageJsonParsed.digital_items[0]
        val albumtitle = digitalItem.title
        val artist = digitalItem.artist
        val releaseDate = digitalItem.package_release_date
        val releaseYear = releaseDate.subSequence(7, 11)
        val isSingleTrack: Boolean = digitalItem.download_type == "t"
        val url = digitalItem.downloads[downloadFormat]?.get("url").orEmpty()
        val artid = digitalItem.art_id

        // Prepare artist and album folder
        val albumFolderName = "$releaseYear - $albumtitle"
        val artistFolderPath = Paths.get("$downloadFolder").resolve(artist)
        val albumFolderPath = artistFolderPath.resolve(albumFolderName)

        downloadAlbum(artistFolderPath, albumFolderPath, albumtitle, url, cookies, gson, isSingleTrack, artid)

    }
}

private fun retrieveCookiesFromFile(cookiesFile: Path?, gson: Gson): Map<String, String> {
    if (!Files.exists(cookiesFile)) {
        throw BandCampDownloaderError("Cookies file '$cookiesFile' cannot be found.")
    }
    val jsonData = String(Files.readAllBytes(cookiesFile))
    val parsedCookies =
            try {
                gson.fromJson(jsonData, Array<ParsedCookie>::class.java)
            } catch (e: JsonSyntaxException) {
                throw BandCampDownloaderError("Cookies file '$cookiesFile' is not well formed: ${e.message}")
            }
    return parsedCookiesToMap(parsedCookies)
}

private fun retrieveFirefoxCookies(): HashMap<String, String> {
    val result = HashMap<String, String>()

    // Find cookies file path
    val homeDir = System.getenv()["HOME"]
    val firefoxConfDirPath = "$homeDir/.mozilla/firefox"
    val profilesListPath = "$firefoxConfDirPath/profiles.ini"
    val profilesListFile = File(profilesListPath)
    if (!profilesListFile.exists()) {
        throw BandCampDownloaderError("No Firefox profiles.ini file could be found!")
    }
    val ini = Ini(profilesListFile)
    val default = "Default"
    val defaultProfileSection = ini.keys.find {
        ini[it] != null
                && ini[it]!!.containsKey(default)
                && ini[it]!![default] == "1"
    }
    val defaultProfilePath = firefoxConfDirPath + "/" + ini.get(defaultProfileSection, "Path")
    val cookiesFilePath = "$defaultProfilePath/cookies.sqlite"

    // Copy cookies file as tmp file
    val tmpFolder = Files.createTempDirectory("bandcampCollectionDownloader")
    val copiedCookiesPath = Files.copy(Paths.get(cookiesFilePath), tmpFolder.resolve("cookies.json"))
    copiedCookiesPath.toFile().deleteOnExit()

    // Start reading firefox's  cookies.sqlite
    var connection: Connection? = null
    try {
        // create a database connection
        connection = DriverManager.getConnection("jdbc:sqlite:$copiedCookiesPath")
        val statement = connection!!.createStatement()
        statement.queryTimeout = 30  // set timeout to 30 sec.
        val rs = statement.executeQuery("select * from moz_cookies where baseDomain = 'bandcamp.com'")
        // For each resulting row
        while (rs.next()) {
            // Extract data from row
            val name = rs.getString("name")
            val value = rs.getString("value")
            val expiry = rs.getString("expiry").toLong()

            // We only keep cookies that have not expired yet
            val now = Instant.now().epochSecond
            val difference = expiry - now
            if (difference > 0)
                result[name] = value
        }
    } finally {
        connection?.close()
    }
    return result
}

class BandCampDownloaderError(s: String) : Exception(s)

fun downloadAlbum(artistFolderPath: Path?, albumFolderPath: Path, albumtitle: String, url: String, cookies: Map<String, String>, gson: Gson, isSingleTrack: Boolean, artid: String) {
    // If the artist folder does not exist, we create it
    if (!Files.exists(artistFolderPath)) {
        Files.createDirectory(artistFolderPath)
    }

    // If the album folder does not exist, we create it
    if (!Files.exists(albumFolderPath)) {
        Files.createDirectory(albumFolderPath)
    }

    // If the folder is empty, or if it only contains the zip.part file, we proceed
    val amountFiles = albumFolderPath.toFile().listFiles().size
    if (amountFiles < 2) {

        val outputFilePath: Path = prepareDownload(albumtitle, url, cookies, gson, albumFolderPath)

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
            val coverURL = "https://f4.bcbits.com/img/a${artid}_10"
            println("Downloading cover ($coverURL)...")
            downloadFile(coverURL, albumFolderPath, "cover.jpg")
        }

        println("done.")

    } else {
        println("Album $albumtitle already done, skipping")
    }
}

fun getDataBlobFromDownloadPage(downloadPageURL: String?, cookies: Map<String, String>, gson: Gson): ParsedBandcampData {
    println("Analyzing download page $downloadPageURL")

    // Get page content
    val downloadPage = Jsoup.connect(downloadPageURL)
            .cookies(cookies)
            .timeout(100000).get()

    // Get data blob
    val downloadPageJson = downloadPage.select("#pagedata").attr("data-blob")
    return gson.fromJson(downloadPageJson, ParsedBandcampData::class.java)
}

fun prepareDownload(albumtitle: String, url: String, cookies: Map<String, String>, gson: Gson, albumFolderPath: Path): Path {
    println("Preparing download of $albumtitle ($url)...")

    val random = Random()

    // Construct statdownload request URL
    val statdownloadURL: String = url
            .replace("/download/", "/statdownload/")
            .replace("http", "https") + "&.vrs=1" + "&.rand=" + random.nextInt()

    // Get statdownload JSON
    println("Getting download link ($statdownloadURL)")
    val statedownloadUglyBody: String = Jsoup.connect(statdownloadURL)
            .cookies(cookies)
            .timeout(100000)
            .get().body().select("body")[0].text().toString()

    val prefixPattern = Pattern.compile("""if\s*\(\s*window\.Downloads\s*\)\s*\{\s*Downloads\.statResult\s*\(\s*""")
    val suffixPattern = Pattern.compile("""\s*\)\s*};""")
    val statdownloadJSON: String =
            prefixPattern.matcher(
                    suffixPattern.matcher(statedownloadUglyBody)
                            .replaceAll("")
            ).replaceAll("")

    // Parse statdownload JSON and get real download URL, and retrieve url
    val statdownloadParsed: ParsedStatDownload = gson.fromJson(statdownloadJSON, ParsedStatDownload::class.java)
    val realDownloadURL = statdownloadParsed.download_url

    println("Downloading $albumtitle ($realDownloadURL)")

    // Download content
    return downloadFile(realDownloadURL, albumFolderPath)
}