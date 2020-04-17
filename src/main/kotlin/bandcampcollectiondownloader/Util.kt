package bandcampcollectiondownloader

import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.text.DecimalFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.function.BiPredicate
import javax.mail.internet.ContentDisposition
import kotlin.streams.toList


object Util {

    private const val BUFFER_SIZE = 4096

    val parallelDownloadsProgresses: MutableMap<String, String> = ConcurrentHashMap()

    /**
     * From http://www.codejava.net/java-se/networking/use-httpurlconnection-to-download-file-from-an-http-url
     */
    fun downloadFile(fileURL: String, saveDir: Path, optionalFileName: String = "", timeout: Int): Path {

        log("Downloading $fileURL…")

        // Prepare HTTP connection
        val url = URL(fileURL)
        val httpConn = url.openConnection() as HttpURLConnection
        httpConn.connectTimeout = timeout
        httpConn.readTimeout = timeout
        val responseCode = httpConn.responseCode

        // Check HTTP response code
        if (responseCode == HttpURLConnection.HTTP_OK || responseCode == HttpURLConnection.HTTP_MOVED_TEMP || responseCode == HttpURLConnection.HTTP_MOVED_PERM) {

            // Retrieve information (name, size)
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
            val contentLengthHeaderField = httpConn.getHeaderField("Content-Length")
            val fileSize: Long = contentLengthHeaderField.toLong()

            // opens input stream from the HTTP connection
            val inputStream = httpConn.inputStream
            val saveFilePath = saveDir.resolve(fileName)
            val saveFilePathString = saveFilePath.toAbsolutePath().toString()

            // opens an output stream to save into file
            val outputStream = FileOutputStream(saveFilePathString)
            val buffer = ByteArray(BUFFER_SIZE)

            // Prepare variables to read and register progress
            var bytesRead = inputStream.read(buffer)
            var total: Long = 0

            // Retrieve thread ID for multithreaded progress printing
            val downloadID: String = Thread.currentThread().name

            // While there are bytes to download…
            while (bytesRead != -1) {

                // Prepare progress printing
                val percent = total.toDouble() / fileSize.toDouble() * 100
                val formatter = DecimalFormat("#0.00")
                val percentString = formatter.format(percent) + " %"
                parallelDownloadsProgresses[downloadID] = if (downloadID == "main") percentString else "[$downloadID] $percentString"
                val allProgresses = parallelDownloadsProgresses.values.joinToString(" || ")

                // Print progresses of all download threads
                val prefix =
                        if (parallelDownloadsProgresses.size > 1) {
                            "Progresses: "
                        } else {
                            "Progress: "
                        }
                val message = "$prefix$allProgresses"
                print(fillWithBlanks(message) + "\r")

                // Download a new chunk
                outputStream.write(buffer, 0, bytesRead)
                bytesRead = inputStream.read(buffer)
                total += bytesRead
            }

            // Remove oneself from the progress printing
            parallelDownloadsProgresses.remove(downloadID)

            // Clean the console output if needed
            if (parallelDownloadsProgresses.isEmpty())
                print(fillWithBlanks("") + "\r")

            // Close streams and connections
            outputStream.close()
            inputStream.close()
            httpConn.disconnect()

            // Return path of downloaded file
            return saveFilePath
        } else {
            throw Exception("No file to download. Server replied HTTP code: $responseCode")
        }


    }

    fun isUnix(): Boolean {
        val os = System.getProperty("os.name").toLowerCase()
        return os.indexOf("nix") >= 0 || os.indexOf("nux") >= 0 || os.indexOf("aix") > 0
    }

    fun isWindows(): Boolean {
        val os = System.getProperty("os.name").toLowerCase()
        return os.indexOf("win") >= 0
    }

    fun replaceInvalidCharsByUnicode(s: String): String {
        var result: String = s
        result = result.replace(':', '꞉')
        result = result.replace('/', '／')
        result = result.replace('\\', '⧹')
        return result
    }

    fun fillWithBlanks(message: String): String {
        return message + " ".repeat((Constants.LINESIZE - message.length).coerceAtLeast(0))
    }

    fun log(message: String) {
        var messageWithPrefix: String = message
        if (Thread.currentThread().name != "main") {
            messageWithPrefix = "[${Thread.currentThread().name}] " + messageWithPrefix
        }
        println(fillWithBlanks(messageWithPrefix))
    }

    fun logSeparator() {
        log("------------")
    }


    fun <T> retry(function: () -> T, retries: Int, ignoreFailure: Boolean = false): T? {
        // Download release, with as many retries as configured
        val attempts = retries + 1
        for (i in 1..attempts) {
            if (i > 1) {
                log("Retrying (${i - 1}/${retries}).")
                Thread.sleep(1000)
            }
            try {
                return function()
            } catch (e: Throwable) {
                log("""Error while trying: "${e.javaClass.name}: ${e.message}".""")
            }
        }
        val message = "Could not perform task after $retries retries."
        if (ignoreFailure) {
            log(message)
            return null
        } else {
            throw BandCampDownloaderError(message)
        }
    }


    // WIP
    fun getFileIgnoreCase(path: Path): List<Path> {
        val parentFolder = path.parent
        val fileName = path.fileName.toString()
        return Files.find(parentFolder, 1, BiPredicate { p2, fileAttributes ->
            p2.fileName.toString().toLowerCase().equals(fileName.toLowerCase())
        }).toList()

    }

}