package bandcampcollectiondownloader.util

import bandcampcollectiondownloader.core.Constants
import java.io.PrintWriter
import java.io.StringWriter

class Logger(private val debug: Boolean) {

    private fun fillWithBlanks(message: String): String {
        return message + " ".repeat((Constants.LINESIZE - message.length).coerceAtLeast(0))
    }


    private fun printInThread(message: String) {
        var messageWithPrefix: String = message
        if (Thread.currentThread().name != "main") {
            messageWithPrefix = "[${Thread.currentThread().name}] " + messageWithPrefix
        }
        println(fillWithBlanks(messageWithPrefix))
    }

    private fun printSeparator() {
        log("------------")
    }


    fun debug(s: String) {
        if (debug) {
            printInThread("[DEBUG] $s")
        }
    }

    fun debug(t: Throwable) {
        this.debug(this.extractStackTrace(t))
    }

    fun log(s: String) {
        printInThread(s)
    }

    fun logSeparator() {
        printSeparator()
    }

    fun logFullLine(message: String) {
        print(fillWithBlanks(message) + "\r")
    }

    private fun extractStackTrace(t: Throwable): String {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        t.printStackTrace(pw)
        return sw.toString()
    }

}
