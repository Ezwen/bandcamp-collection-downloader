import com.google.gson.Gson
import org.jsoup.HttpStatusException
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.*
import java.util.regex.Pattern

object BandcampAPIHelper {

    data class ParsedFanpageData(
            val fan_data: FanData,
            val collection_data: CollectionData
    )

    data class FanData(
            val fan_id: String
    )

    data class CollectionData(
            val batch_size: Int,
            val item_count: Int,
            val last_token: String,
            val redownload_urls: Map<String, String>
    )

    data class ParsedCollectionItems(
            val more_available: Boolean,
            val last_token: String,
            val redownload_urls: Map<String, String>
    )

    data class ParsedBandcampData(
            @Suppress("ArrayInDataClass") val digital_items: Array<DigitalItem>
    )

    data class DigitalItem(
            val downloads: Map<String, Map<String, String>>,
            val package_release_date: String,
            val title: String,
            val artist: String,
            val download_type: String,
            val art_id: String
    )

    data class ParsedStatDownload(
            val download_url: String?,
            val url: String
    )


    private fun getDataBlobFromFanPage(doc: Document, gson: Gson): ParsedFanpageData {
        println("Analyzing fan page")

        // Get data blob
        val downloadPageJson = doc.select("#pagedata").attr("data-blob")
        return gson.fromJson(downloadPageJson, ParsedFanpageData::class.java)
    }

    fun getDataBlobFromDownloadPage(downloadPageURL: String?, cookies: Map<String, String>, gson: Gson, timeout: Int): ParsedBandcampData? {
        println("Getting data from item page ($downloadPageURL)")

        // Get page content
        try {
            val downloadPage = Jsoup.connect(downloadPageURL)
                    .cookies(cookies)
                    .timeout(timeout).get()

            // Get data blob
            val downloadPageJson = downloadPage.select("#pagedata").attr("data-blob")
            return gson.fromJson(downloadPageJson, ParsedBandcampData::class.java)

        } catch (e: HttpStatusException) {

            // If 404, then the download page is not usable anymore for some reason (eg. a refund was given for the purchase)
            if (e.statusCode == 404)
                return null
            else
                throw e
        }
    }

    fun getCollection(cookies: Map<String, String>, timeout: Int, bandcampUser: String, gson: Gson): MutableMap<String, String> {


        // Get collection page with cookies, hence with download links
        val doc = try {
            Jsoup.connect("https://bandcamp.com/$bandcampUser")
                    .timeout(timeout)
                    .cookies(cookies)
                    .get()
        } catch (e: HttpStatusException) {
            if (e.statusCode == 404) {
                throw BandCampDownloaderError("The bandcamp user '$bandcampUser' does not exist.")
            } else {
                throw e
            }
        }
        println("""Found collection page: "${doc.title()}"""")

        // Get download pages
        val fanPageBlob = getDataBlobFromFanPage(doc, gson)
        val collection = fanPageBlob.collection_data.redownload_urls.toMutableMap()

        if (collection.isEmpty()) {
            throw BandCampDownloaderError("No download links could by found in the collection page. This can be caused by an outdated or invalid cookies file.")
        }

        // Get the rest of the collection
        if (fanPageBlob.collection_data.item_count > fanPageBlob.collection_data.batch_size) {
            val fanId = fanPageBlob.fan_data.fan_id
            var lastToken = fanPageBlob.collection_data.last_token
            var moreAvailable = true
            while (moreAvailable) {
                // Append download pages from this api endpoint as well
                println("Requesting collection_items API older than token $lastToken")
                val theRest = try {
                    Jsoup.connect("https://bandcamp.com/api/fancollection/1/collection_items")
                            .ignoreContentType(true)
                            .timeout(timeout)
                            .cookies(cookies)
                            .requestBody("{\"fan_id\": $fanId, \"older_than_token\": \"$lastToken\"}")
                            .post()
                } catch (e: HttpStatusException) {
                    throw e
                }

                val parsedCollectionData = gson.fromJson(theRest.wholeText(), ParsedCollectionItems::class.java)
                collection.putAll(parsedCollectionData.redownload_urls)

                lastToken = parsedCollectionData.last_token
                moreAvailable = parsedCollectionData.more_available
            }
        }

        return collection
    }


    fun getCoverURL(artid: String): String {
        return "https://f4.bcbits.com/img/a${artid}_10"
    }

    fun getStatData(downloadUrl: String, cookies: Map<String, String>, timeout: Int, gson: Gson): ParsedStatDownload {
        println("Getting download information from the download URL ($downloadUrl)...")

        val random = Random()

        // Construct statdownload request URL
        val statdownloadURL: String = downloadUrl
                .replace("/download/", "/statdownload/")
                .replace("http:", "https:") + "&.vrs=1" + "&.rand=" + random.nextInt()

        // Get statdownload JSON
        val statedownloadUglyBody: String = Jsoup.connect(statdownloadURL)
                .cookies(cookies)
                .timeout(timeout)
                .get().body().select("body")[0].text().toString()

        val prefixPattern = Pattern.compile("""if\s*\(\s*window\.Downloads\s*\)\s*\{\s*Downloads\.statResult\s*\(\s*""")
        val suffixPattern = Pattern.compile("""\s*\)\s*};""")
        val statdownloadJSON: String =
                prefixPattern.matcher(
                        suffixPattern.matcher(statedownloadUglyBody)
                                .replaceAll("")
                ).replaceAll("")

        // Parse statdownload JSON
        val statdownloadParsed: ParsedStatDownload = gson.fromJson(statdownloadJSON, ParsedStatDownload::class.java)

        return statdownloadParsed
    }

}