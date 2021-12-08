package bandcampcollectiondownloader.core

import bandcampcollectiondownloader.util.Util
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.annotations.SerializedName
import org.ini4j.Ini
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant

fun parseCookiesText(fileData: String): Map<String, String> {
    val lines = fileData.split('\n')
    val result = java.util.HashMap<String, String>()
    lines.forEach {
        if (!it.startsWith('#')) {
            val columns = it.split('\t').toTypedArray()
            if (columns.size == 7) {
                result[columns[5]] = columns[6]
            }
        }
    }
    return result
}


class CookiesManagement(private val util : Util) {

    private val gson = Gson()

    private data class ParsedCookie(
        @SerializedName("Name raw")
        val nameRaw: String?,

        @SerializedName("Content raw")
        val contentRaw: String?
    )

    data class Cookies(
        val source: Path,
        val content: Map<String, String>
    )


    private fun parsedCookiesToMap(parsedCookies: Array<ParsedCookie>): Map<String, String> {
        val result = java.util.HashMap<String, String>()
        for (parsedCookie in parsedCookies) {
            if (parsedCookie.contentRaw.isNullOrEmpty()) {
                throw BandCampDownloaderError(
                    "Missing 'Content raw' field in cookie number ${
                        parsedCookies.indexOf(
                            parsedCookie
                        ) + 1
                    }."
                )
            }
            if (parsedCookie.nameRaw.isNullOrEmpty()) {
                throw BandCampDownloaderError(
                    "Missing 'Name raw' field in cookie number ${
                        parsedCookies.indexOf(
                            parsedCookie
                        ) + 1
                    }."
                )
            }
            result[parsedCookie.nameRaw] = parsedCookie.contentRaw
        }
        return result
    }


    fun retrieveCookiesFromFile(cookiesFile: Path): Cookies {
        if (!Files.exists(cookiesFile)) {
            throw BandCampDownloaderError("Cookies file '$cookiesFile' cannot be found.")
        }
        val fileData = String(Files.readAllBytes(cookiesFile))
        val parsedCookies =
            try {
                if (cookiesFile.toString().endsWith(".json"))
                    parsedCookiesToMap(gson.fromJson(fileData, Array<ParsedCookie>::class.java)) else
                    parseCookiesText(fileData)
            } catch (e: JsonSyntaxException) {
                throw BandCampDownloaderError("Cookies file '$cookiesFile' is not well formed: ${e.message}")
            }
        return Cookies(cookiesFile, parsedCookies)
    }

    /**
     * Searches in all Firefox profiles for valid Bandcamp cookies, and returns all found sets of cookies.
     */
    fun retrieveFirefoxCookies(): List<Cookies> {

        val allFoundCookies = ArrayList<Cookies>()

        // Find Firefox configuration folder
        val firefoxConfDirPath: Path?
        firefoxConfDirPath = when {
            util.isUnix() -> {
                val homeDir = Paths.get(System.getenv()["HOME"]!!)
                homeDir.resolve(".mozilla/firefox")
            }
            util.isWindows() -> {
                val appdata = Paths.get(System.getenv("APPDATA"))
                appdata.resolve("mozilla/firefox")
            }
            else -> throw BandCampDownloaderError("OS not supported, cannot find Firefox cookies!")
        }

        // Find all firefox profiles
        val profilesListPath = firefoxConfDirPath.resolve("profiles.ini")
        val profilesListFile = File(profilesListPath.toUri())
        if (!profilesListFile.exists()) {
            throw BandCampDownloaderError("No Firefox profiles.ini file could be found!")
        }
        val ini = Ini(profilesListFile)
        val pathKey = "Path"
        val entriesWithPath = ini.keys.filter {
            ini[it] != null && ini[it]!!.containsKey(pathKey)
        }

        // For each profile, look for cookies
        for (entryWithPath in entriesWithPath) {

            val result = HashMap<String, String>()

            val profilePath = firefoxConfDirPath.resolve(ini.get(entryWithPath, pathKey))
            val cookiesFilePath = profilePath.resolve("cookies.sqlite")

            if (Files.exists(cookiesFilePath)) {

                // Copy cookies file as tmp file
                val tmpFolder = Files.createTempDirectory("bandcampCollectionDownloader")
                val copiedCookiesPath = Files.copy(cookiesFilePath, tmpFolder.resolve("cookies.sqlite"))
                copiedCookiesPath.toFile().deleteOnExit()

                // Start reading firefox's  cookies.sqlite
                var connection: Connection? = null
                try {
                    // create a database connection
                    connection = DriverManager.getConnection("jdbc:sqlite:$copiedCookiesPath")
                    val statement = connection!!.createStatement()
                    statement.queryTimeout = 30  // set timeout to 30 sec.
                    val rs = statement.executeQuery("select * from moz_cookies where host = '.bandcamp.com'")
                    // For each resulting row
                    while (rs.next()) {
                        // Extract data from row
                        val name: String = rs.getString("name")
                        val value: String = rs.getString("value")
                        val expiry: Long = rs.getString("expiry").toLong()
                        val originAttributes: String = rs.getString("originAttributes")

                        // We only keep cookies that are not found in container tabs
                        if (!originAttributes.contains("userContextId")) {
                            // We only keep cookies that have not expired yet
                            val now = Instant.now().epochSecond
                            val difference = expiry - now
                            if (difference > 0)
                                result[name] = value
                        }

                    }
                } finally {
                    connection?.close()
                }
                if (result.isNotEmpty()) {
                    allFoundCookies.add(Cookies(cookiesFilePath, result))
                }
            }

        }
        return allFoundCookies
    }


}