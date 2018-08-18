package bandcampcollectiodownloader.test

import bandcampcollectiondownloader.BandCampDownloaderError
import bandcampcollectiondownloader.downloadAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Paths

/**
 * Note: bli is a valid bandcamp user (completely randomly chosen),
 * but for which we have no credentials (ie. valid cookies).
 */
class BandcampCollectionDownloaderTests {

    @Test
    fun testErrorCookiesFileNotFound() {
        assertThrows<BandCampDownloaderError> {
            downloadAll(Paths.get("bli"), "bli", "bli", Paths.get("bli"))
        }
    }

    @Test
    fun testErrorCookiesFileInvalidJson() {
        assertThrows<BandCampDownloaderError> {
            downloadAll(Paths.get("./test-data/notjsoncookies.json"), "bli", "bli", Paths.get("bli"))
        }
    }

    @Test
    fun testErrorCookiesFileInvalidContent_wrongkey() {
        assertThrows<BandCampDownloaderError> {
            downloadAll(Paths.get("./test-data/invalidcookies_wrongkeys.json"), "bli", "bli", Paths.get("bli"))
        }
    }

    @Test
    fun testErrorCookiesFileInvalidContent_noarray() {
        assertThrows<BandCampDownloaderError> {
            downloadAll(Paths.get("./test-data/invalidcookies_noarray.json"), "bli", "bli", Paths.get("bli"))
        }
    }

    @Test
    fun testErrorInvalidBandcampUser() {
        assertThrows<BandCampDownloaderError> {
            downloadAll(Paths.get("./test-data/wellformedcookies.json"), "zerz1e3687dfs3df7", "bli", Paths.get("bli"))
        }
    }
}