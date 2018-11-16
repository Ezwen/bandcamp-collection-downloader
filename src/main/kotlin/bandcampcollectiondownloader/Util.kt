package bandcampcollectiondownloader

import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Path
import java.nio.file.Paths
import javax.mail.internet.ContentDisposition


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

fun isWindows(): Boolean {
    val os = System.getProperty("os.name").toLowerCase()
    return os.indexOf("win") >= 0
}

