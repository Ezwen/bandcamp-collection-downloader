package bandcampcollectiodownloader.test

import bandcampcollectiondownloader.core.Args
import bandcampcollectiondownloader.core.BandCampDownloaderError
import bandcampcollectiondownloader.core.BandcampCollectionDownloader
import bandcampcollectiondownloader.util.DryIO
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import java.nio.file.Paths
import java.util.*

/**
 * Note: bli is a valid bandcamp user (completely randomly chosen),
 * but for which we have no credentials (ie. valid cookies).
 */
class SystemTests {

    @Test
    fun testErrorCookiesFileNotFound() {
        val args = Args()
        args.pathToCookiesFile = Paths.get("bli")
        assertThrows<BandCampDownloaderError> {
            BandcampCollectionDownloader(args, DryIO()).downloadAll()
        }
    }

    //
    @Test
    fun testErrorCookiesFileInvalidJson() {
        val args = Args()
        args.pathToCookiesFile = Paths.get("./test-data/notjsoncookies.json")
        assertThrows<BandCampDownloaderError> {
            BandcampCollectionDownloader(args, DryIO()).downloadAll()
        }
    }

    @Test
    fun testErrorCookiesFileInvalidContent_wrongkey() {
        val args = Args()
        args.pathToCookiesFile = Paths.get("./test-data/invalidcookies_wrongkeys.json")
        assertThrows<BandCampDownloaderError> {
            BandcampCollectionDownloader(args, DryIO()).downloadAll()
        }
    }

    @Test
    fun testErrorCookiesFileInvalidContent_noarray() {
        val args = Args()
        args.pathToCookiesFile = Paths.get("./test-data/invalidcookies_noarray.json")
        assertThrows<BandCampDownloaderError> {
            BandcampCollectionDownloader(args, DryIO()).downloadAll()
        }
    }

    @Test
    fun testErrorInvalidBandcampUser() {
        val args = Args()
        args.pathToCookiesFile = Paths.get("./test-data/wellformedcookies.json")
        args.bandcampUser = "zerz1e3687dfs3df7"
        args.timeout = 5000
        assertThrows<BandCampDownloaderError> {
            BandcampCollectionDownloader(args, DryIO()).downloadAll()
        }
    }

    @Test
    fun testErrorCookiesUselessForBandcampUser() {
        val args = Args()
        args.pathToCookiesFile = Paths.get("./test-data/wellformedcookies.json")
        args.bandcampUser = "bli"
        args.timeout = 5000
        assertThrows<BandCampDownloaderError> {
            BandcampCollectionDownloader(args, DryIO()).downloadAll()
        }
    }

    @Test
    fun testErrorNoCookiesAtAll() {
        addToEnv("HOME", "NOPE")
        val args = Args()
        args.pathToCookiesFile = null
        args.bandcampUser = "bli"
        args.timeout = 5000
        assertThrows<BandCampDownloaderError> {
            BandcampCollectionDownloader(args, DryIO()).downloadAll()
        }
    }

    @Test
    fun testOKValidCookiesDryRun() {
        val args = Args()
        args.pathToCookiesFile = Paths.get("./test-data/bcdtestcookies.json")
        args.bandcampUser = "bcdtest"
        val tmpDir = Files.createTempDirectory("bandcamp-collection-downloader-test")
        tmpDir.toFile().deleteOnExit()
        args.pathToDownloadFolder = tmpDir
        args.dryRun = true
        val dryIO = DryIO()
        BandcampCollectionDownloader(args, dryIO).downloadAll()
        assertFalse(dryIO.getUnzippedFiles().isEmpty())
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