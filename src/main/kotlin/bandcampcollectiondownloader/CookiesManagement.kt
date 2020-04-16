package bandcampcollectiondownloader

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

object CookiesManagement {

    private val gson = Gson()

    data class ParsedCookie(
            @SerializedName("Name raw")
            val nameRaw: String?,

            @SerializedName("Content raw")
            val contentRaw: String?
    )


    private fun parsedCookiesToMap(parsedCookies: Array<ParsedCookie>): Map<String, String> {
        val result = java.util.HashMap<String, String>()
        for (parsedCookie in parsedCookies) {
            if (parsedCookie.contentRaw.isNullOrEmpty()) {
                throw BandCampDownloaderError("Missing 'Content raw' field in cookie number ${parsedCookies.indexOf(parsedCookie) + 1}.")
            }
            if (parsedCookie.nameRaw.isNullOrEmpty()) {
                throw BandCampDownloaderError("Missing 'Name raw' field in cookie number ${parsedCookies.indexOf(parsedCookie) + 1}.")
            }
            result[parsedCookie.nameRaw] = parsedCookie.contentRaw
        }
        return result
    }


    fun retrieveCookiesFromFile(cookiesFile: Path): Map<String, String> {
        if (!Files.exists(cookiesFile)) {
            throw BandCampDownloaderError("Cookies file '$cookiesFile' cannot be found.")
        }
        val jsonData = String(Files.readAllBytes(cookiesFile))
        val parsedCookies =
                try {
                    gson.fromJson(jsonData, Array<ParsedCookie>::class.java)
                } catch (e: JsonSyntaxException) {
                    throw BandCampDownloaderError("Cookies file '$cookiesFile' is not well formed: ${e.message}")
                }
        return parsedCookiesToMap(parsedCookies)
    }

    fun retrieveFirefoxCookies(): HashMap<String, String> {
        val result = HashMap<String, String>()

        // Find cookies file path


        val firefoxConfDirPath: Path?
        firefoxConfDirPath = when {
            Util.isUnix() -> {
                val homeDir = Paths.get(System.getenv()["HOME"]!!)
                homeDir.resolve(".mozilla/firefox")
            }
            Util.isWindows() -> {
                val appdata = Paths.get(System.getenv("APPDATA"))
                appdata.resolve("mozilla/firefox")
            }
            else -> throw BandCampDownloaderError("OS not supported, cannot find Firefox cookies!")
        }

        val profilesListPath = firefoxConfDirPath.resolve("profiles.ini")
        val profilesListFile = File(profilesListPath.toUri())
        if (!profilesListFile.exists()) {
            throw BandCampDownloaderError("No Firefox profiles.ini file could be found!")
        }
        val ini = Ini(profilesListFile)
        val default = "Default"
        val defaultProfileSection = ini.keys.find {
            ini[it] != null
                    && ini[it]!!.containsKey(default)
                    && ini[it]!![default] == "1"
        }
        val defaultProfilePath = firefoxConfDirPath.resolve(ini.get(defaultProfileSection, "Path"))
        val cookiesFilePath = defaultProfilePath.resolve("cookies.sqlite")

        // Copy cookies file as tmp file
        val tmpFolder = Files.createTempDirectory("bandcampCollectionDownloader")
        val copiedCookiesPath = Files.copy(cookiesFilePath, tmpFolder.resolve("cookies.json"))
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
                val name = rs.getString("name")
                val value = rs.getString("value")
                val expiry = rs.getString("expiry").toLong()

                // We only keep cookies that have not expired yet
                val now = Instant.now().epochSecond
                val difference = expiry - now
                if (difference > 0)
                    result[name] = value
            }
        } finally {
            connection?.close()
        }
        return result
    }

}