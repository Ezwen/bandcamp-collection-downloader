package bandcampcollectiondownloader.util

import bandcampcollectiondownloader.core.BandCampDownloaderError
import bandcampcollectiondownloader.core.Constants

import java.io.PrintWriter

import java.io.StringWriter
import java.util.*

object Util {

    fun isUnix(): Boolean {
        val os = System.getProperty("os.name").lowercase(Locale.getDefault())
        return os.indexOf("nix") >= 0 || os.indexOf("nux") >= 0 || os.indexOf("aix") > 0
    }

    fun isWindows(): Boolean {
        val os = System.getProperty("os.name").lowercase(Locale.getDefault())
        return os.indexOf("win") >= 0
    }

    fun replaceInvalidCharsByUnicode(s: String): String {
        var result: String = s
        for ((old, new) in Constants.UNICODE_CHARS_REPLACEMENTS) {
            result = result.replace(old, new)
        }
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

    fun log(t: Throwable) {
        this.log(this.extractStackTrace(t))
    }

    private fun extractStackTrace(t: Throwable): String {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        t.printStackTrace(pw)
        return sw.toString()
    }

    fun <T> retry(function: () -> T, retries: Int, debug: Boolean): T? {
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
                if (debug) {
                    log(e)
                }
            }
        }
        val message = "Could not perform task after $retries retries."
        throw BandCampDownloaderError(message)
    }

}