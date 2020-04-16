package bandcampcollectiondownloader

import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.text.DecimalFormat
import java.util.function.BiPredicate
import javax.mail.internet.ContentDisposition
import kotlin.streams.toList


object Util {

    private const val BUFFER_SIZE = 4096

    /**
     * From http://www.codejava.net/java-se/networking/use-httpurlconnection-to-download-file-from-an-http-url
     */
    fun downloadFile(fileURL: String, saveDir: Path, optionalFileName: String = "", timeout: Int): Path {

        println("Downloading from $fileURL…")

        val url = URL(fileURL)
        val httpConn = url.openConnection() as HttpURLConnection
        httpConn.connectTimeout = timeout
        httpConn.readTimeout = timeout
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
            val contentLengthHeaderField = httpConn.getHeaderField("Content-Length")
            val fileSize: Long = contentLengthHeaderField.toLong()

            // opens input stream from the HTTP connection
            val inputStream = httpConn.inputStream
            val saveFilePath = saveDir.resolve(fileName)
            val saveFilePathString = saveFilePath.toAbsolutePath().toString()

            // opens an output stream to save into file
            val outputStream = FileOutputStream(saveFilePathString)

            val buffer = ByteArray(BUFFER_SIZE)
            var bytesRead = inputStream.read(buffer)
            var total: Long = 0
            while (bytesRead != -1) {
                // Print progress
                val percent = total.toDouble() / fileSize.toDouble() * 100
                val formatter = DecimalFormat("#0.00")
                val percentString = formatter.format(percent)
                System.out.print("Progress: $percentString % ($total / $fileSize) \r")

                // Download chunk
                outputStream.write(buffer, 0, bytesRead)
                bytesRead = inputStream.read(buffer)
                total += bytesRead
            }
            System.out.print(" ".repeat(120) + "\r")
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

    // WIP
    fun getFileIgnoreCase(path: Path): List<Path> {
        val parentFolder = path.parent
        val fileName = path.fileName.toString()
        return Files.find(parentFolder, 1, BiPredicate { p2, fileAttributes ->
            p2.fileName.toString().toLowerCase().equals(fileName.toLowerCase())
        }).toList()

    }

}