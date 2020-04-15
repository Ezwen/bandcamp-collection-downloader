package bandcampcollectiodownloader.test

import bandcampcollectiondownloader.BandCampDownloaderError
import bandcampcollectiondownloader.downloadAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Paths
import java.util.*

/**
 * Note: bli is a valid bandcamp user (completely randomly chosen),
 * but for which we have no credentials (ie. valid cookies).
 */
class BandcampCollectionDownloaderTests {

    @Test
    fun testErrorCookiesFileNotFound() {
        assertThrows<BandCampDownloaderError> {
            downloadAll(Paths.get("bli"), "bli", "bli",
                    Paths.get("bli"), 0, 5000, false)
        }
    }

    @Test
    fun testErrorCookiesFileInvalidJson() {
        assertThrows<BandCampDownloaderError> {
            downloadAll(Paths.get("./test-data/notjsoncookies.json"), "bli", "bli", Paths.get("bli"), 0, 5000, false)
        }
    }

    @Test
    fun testErrorCookiesFileInvalidContent_wrongkey() {
        assertThrows<BandCampDownloaderError> {
            downloadAll(Paths.get("./test-data/invalidcookies_wrongkeys.json"), "bli", "bli", Paths.get("bli"), 0, 5000, false)
        }
    }

    @Test
    fun testErrorCookiesFileInvalidContent_noarray() {
        assertThrows<BandCampDownloaderError> {
            downloadAll(Paths.get("./test-data/invalidcookies_noarray.json"), "bli", "bli", Paths.get("bli"), 0, 5000, false)
        }
    }

    @Test
    fun testErrorInvalidBandcampUser() {
        assertThrows<BandCampDownloaderError> {
            downloadAll(Paths.get("./test-data/wellformedcookies.json"), "zerz1e3687dfs3df7", "bli", Paths.get("bli"), 0, 5000, false)
        }
    }

    @Test
    fun testErrorCookiesUselessForBandcampUser() {
        assertThrows<BandCampDownloaderError> {
            downloadAll(Paths.get("./test-data/wellformedcookies.json"), "bli", "bli", Paths.get("bli"), 0, 5000, false)
        }
    }

    @Test
    fun testErrorNoCookiesAtAll() {
        addToEnv("HOME", "NOPE")
        assertThrows<BandCampDownloaderError> {
            downloadAll(null, "bli", "bli", Paths.get("bli"), 0, 5000, false)
        }
    }

    @Throws(Exception::class)
    fun addToEnv(key: String, value: String) {
        val classes = Collections::class.java.declaredClasses
        val env = System.getenv()
        for (cl in classes) {
            if ("java.util.Collections\$UnmodifiableMap" == cl.name) {
                val field = cl.getDeclaredField("m")
                field.isAccessible = true
                val obj = field.get(env)
                @Suppress("UNCHECKED_CAST")
                val map = obj as MutableMap<String, String>
                map[key] = value
            }
        }
    }


}