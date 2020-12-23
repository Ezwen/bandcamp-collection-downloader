package bandcampcollectiondownloader.util

import org.zeroturnaround.zip.ZipUtil
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.text.DecimalFormat
import java.util.concurrent.ConcurrentHashMap
import javax.mail.internet.ContentDisposition

class RealIO(dryRun: Boolean = false) : IO {

    private val dryRun : Boolean = dryRun

    override fun createFile(path: Path) {
        Files.createFile(path)
    }

    override fun append(path: Path, content: String) {
        path.toFile().appendText(content)
    }

    override fun createDirectories(path: Path) {
        Files.createDirectories(path)
    }

    private val BUFFER_SIZE = 4096

    private val parallelDownloadsProgresses: MutableMap<String, String> = ConcurrentHashMap()

    /**
     * Initially from http://www.codejava.net/java-se/networking/use-httpurlconnection-to-download-file-from-an-http-url
     */
    override fun downloadFile(fileURL: String, saveDir: Path, optionalFileName: String, timeout: Int): Path {

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
                print(Util.fillWithBlanks(message) + "\r")

                // Download a new chunk
                outputStream.write(buffer, 0, bytesRead)
                bytesRead = inputStream.read(buffer)
                total += bytesRead
            }

            // Remove oneself from the progress printing
            parallelDownloadsProgresses.remove(downloadID)

            // Clean the console output if needed
            if (parallelDownloadsProgresses.isEmpty())
                print(Util.fillWithBlanks("") + "\r")

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

    override fun delete(path: Path) {
        Files.delete(path)
    }

    override fun unzip(zipPath: Path, outputFolderPath: Path) {
        ZipUtil.unpack(zipPath.toFile(), outputFolderPath.toFile())

    }

    override fun readLines(path: Path): List<String> {
        return path.toFile().readLines()
    }
}