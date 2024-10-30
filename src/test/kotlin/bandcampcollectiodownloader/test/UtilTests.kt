package bandcampcollectiodownloader.test

import bandcampcollectiondownloader.util.Logger
import bandcampcollectiondownloader.util.Util
import bandcampcollectiondownloader.core.Constants

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class UtilTest {

    private lateinit var logger: Logger
    private lateinit var util: Util

    @BeforeEach
    fun setUp() {
        logger = Logger(true)
        util = Util(logger)
    }


    @Test
    fun testReplaceInvalidCharsByUnicode() {
        for ((old, new) in Constants.UNICODE_CHARS_REPLACEMENTS) {
            val input = "Test${old}String"
            val expected = "Test${new}String"
            assertEquals(expected, util.replaceInvalidCharsByUnicode(input))
        }
        for ((old, new) in Constants.TRAILING_CHAR_REPLACEMENTS) {
            val input = "Test${old}"
            val expected = "Test${new}"
            assertEquals(expected, util.replaceInvalidCharsByUnicode(input))
        }
    }
}