package bandcampcollectiondownloader.util

import bandcampcollectiondownloader.core.BandCampDownloaderError
import bandcampcollectiondownloader.core.Constants


object Util {

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

}