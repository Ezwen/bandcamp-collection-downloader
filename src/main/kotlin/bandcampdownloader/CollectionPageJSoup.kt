package bandcampdownloader

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import org.jsoup.Jsoup
import org.zeroturnaround.zip.ZipUtil
import picocli.CommandLine
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import java.util.regex.Pattern
import javax.mail.internet.ContentDisposition

data class ParsedCookie(
        @SerializedName("Name raw")
        val nameRaw: String,

        @SerializedName("Content raw")
        val contentRaw: String
)

data class ParsedBandcampData(
        val digital_items: Array<DigitalItem>
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
        result.put(parsedCookie.nameRaw, parsedCookie.contentRaw)
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
    val responseCode = httpConn.getResponseCode()

    // always check HTTP response code first
    if (responseCode == HttpURLConnection.HTTP_OK) {
        val disposition = httpConn.getHeaderField("Content-Disposition")

        val fileName: String =
                if (optionalFileName != "") {
                    optionalFileName
                } else if (disposition != null) {
                    val parsedDisposition = ContentDisposition(disposition)
                    parsedDisposition.getParameter("filename")
                } else {
                    Paths.get(url.file).fileName.toString()
                }

        // opens input stream from the HTTP connection
        val inputStream = httpConn.getInputStream()
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


data class Args(
        @CommandLine.Parameters(arity = "1..1",
                description = arrayOf("The bandcamp user account from which all albums must be downloaded."))
        var bandcampUser: String = "",

        @CommandLine.Option(names = arrayOf("--cookies-file", "-c"), required = true,
                description = arrayOf("A JSON file with valid bandcamp credential cookies.",
                        """"Cookie Quick Manager" can be used to obtain this file after logging into bandcamp.""",
                        "(visit https://addons.mozilla.org/en-US/firefox/addon/cookie-quick-manager/)."))
        var pathToCookiesFile: String? = null,

        @CommandLine.Option(names = arrayOf("--audio-format", "-f"), required = false,
                description = arrayOf("The chosen audio format of the files to download (default: \${DEFAULT-VALUE}).",
                        "Possible values: flac, wav, aac-hi, mp3-320, aiff-lossless, vorbis, mp3-v0, alac."))
        var audioFormat: String = "vorbis",

        @CommandLine.Option(names = arrayOf("--download-folder", "-d"), required = false,
                description = arrayOf("The folder in which downloaded albums must be extracted.",
                        "The following structure is considered: <pathToDownloadFolder>/<artist>/<year> - <album>."))
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
            } catch (e: CommandLine.MissingParameterException) {
                CommandLine.usage(Args(), System.out)
                System.err.println(e.message)
                return
            }
    if (parsedArgs.help) {
        CommandLine.usage(Args(), System.out)
    }
    val bandcampUser = parsedArgs.bandcampUser
    val cookiesFile = parsedArgs.pathToCookiesFile
    val downloadFormat = parsedArgs.audioFormat
    val downloadFolder = parsedArgs.pathToDownloadFolder

    // Parse JSON cookies (obtained with some Firefox addon)
    val gson = Gson()
    val jsonData = String(Files.readAllBytes(Paths.get(cookiesFile)))
    val parsedCookies = gson.fromJson(jsonData, Array<ParsedCookie>::class.java)
    val cookies = parsedCookiesToMap(parsedCookies)

    // Get collection page with cookies, hence with download links
    val doc = Jsoup.connect("https://bandcamp.com/$bandcampUser")
            .cookies(cookies)
            .get()
    println(doc.title())
    if (!doc.toString().contains("buy-now")) {
        println("Cookies appear to be working!")
    }

    // Get download pages
    val collection = doc.select("span.redownload-item a")

    // For each download page
    for (item in collection) {
        val downloadPageURL = item.attr("href")
        println("Analyzing download page $downloadPageURL")

        // Get page content
        val downloadPage = Jsoup.connect(downloadPageURL)
                .cookies(cookies)
                .timeout(100000).get()

        // Get data blob
        val downloadPageJson = downloadPage.select("#pagedata").attr("data-blob")
        val downloadPageJsonParsed = gson.fromJson(downloadPageJson, ParsedBandcampData::class.java)

        // Extract data from blob
        val digitalItem = downloadPageJsonParsed.digital_items.get(0)
        val albumtitle = digitalItem.title
        val artist = digitalItem.artist
        val releaseDate = digitalItem.package_release_date
        val releaseYear = releaseDate.subSequence(7, 11)
        val isSingleTrack: Boolean = digitalItem.download_type == "t"
        val url = digitalItem.downloads.get(downloadFormat)?.get("url").orEmpty()
        val artid = digitalItem.art_id

        // Prepare artist and album folder
        val albumFolderName = "$releaseYear - $albumtitle"
        val artistFolderPath = Paths.get("$downloadFolder").resolve(artist)
        val albumFolderPath = artistFolderPath.resolve(albumFolderName)

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
                    .get().body().select("body").get(0).text().toString()

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
            val outputFilePath: Path = downloadFile(realDownloadURL, albumFolderPath)

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


}