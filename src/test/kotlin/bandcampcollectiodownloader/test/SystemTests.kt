package bandcampcollectiodownloader.test

import bandcampcollectiondownloader.cli.Command
import bandcampcollectiondownloader.core.BandCampDownloaderError
import bandcampcollectiondownloader.core.BandcampCollectionDownloader
import bandcampcollectiondownloader.util.DryIO
import org.junit.jupiter.api.Assertions.assertFalse
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
        val Command = Command()
        Command.pathToCookiesFile = Paths.get("bli")
        assertThrows<BandCampDownloaderError> {
            BandcampCollectionDownloader(Command, DryIO()).downloadAll()
        }
    }

    //
    @Test
    fun testErrorCookiesFileInvalidJson() {
        val Command = Command()
        Command.pathToCookiesFile = Paths.get("./test-data/notjsoncookies.json")
        assertThrows<BandCampDownloaderError> {
            BandcampCollectionDownloader(Command, DryIO()).downloadAll()
        }
    }

    @Test
    fun testErrorCookiesFileInvalidContent_wrongkey() {
        val Command = Command()
        Command.pathToCookiesFile = Paths.get("./test-data/invalidcookies_wrongkeys.json")
        assertThrows<BandCampDownloaderError> {
            BandcampCollectionDownloader(Command, DryIO()).downloadAll()
        }
    }

    @Test
    fun testErrorCookiesFileInvalidContent_noarray() {
        val Command = Command()
        Command.pathToCookiesFile = Paths.get("./test-data/invalidcookies_noarray.json")
        assertThrows<BandCampDownloaderError> {
            BandcampCollectionDownloader(Command, DryIO()).downloadAll()
        }
    }

    @Test
    fun testErrorInvalidBandcampUser() {
        val Command = Command()
        Command.pathToCookiesFile = Paths.get("./test-data/wellformedcookies.json")
        Command.bandcampUser = "zerz1e3687dfs3df7"
        Command.timeout = 5000
        assertThrows<BandCampDownloaderError> {
            BandcampCollectionDownloader(Command, DryIO()).downloadAll()
        }
    }

    @Test
    fun testErrorCookiesUselessForBandcampUser() {
        val Command = Command()
        Command.pathToCookiesFile = Paths.get("./test-data/wellformedcookies.json")
        Command.bandcampUser = "bli"
        Command.timeout = 5000
        assertThrows<BandCampDownloaderError> {
            BandcampCollectionDownloader(Command, DryIO()).downloadAll()
        }
    }

    @Test
    fun testErrorNoCookiesAtAll() {
        addToEnv("HOME", "NOPE")
        val Command = Command()
        Command.pathToCookiesFile = null
        Command.bandcampUser = "bli"
        Command.timeout = 5000
        assertThrows<BandCampDownloaderError> {
            BandcampCollectionDownloader(Command, DryIO()).downloadAll()
        }
    }

    @Test
    fun testOKValidCookiesDryRun() {
        val Command = Command()
        Command.pathToCookiesFile = Paths.get("./test-data/bcdtestcookies.json")
        Command.bandcampUser = "bcdtest"
        val tmpDir = Files.createTempDirectory("bandcamp-collection-downloader-test")
        tmpDir.toFile().deleteOnExit()
        Command.pathToDownloadFolder = tmpDir
        Command.dryRun = true
        val dryIO = DryIO()
        BandcampCollectionDownloader(Command, dryIO).downloadAll()
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