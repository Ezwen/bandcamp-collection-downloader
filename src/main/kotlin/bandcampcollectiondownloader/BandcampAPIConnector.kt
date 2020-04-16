package bandcampcollectiondownloader

import com.google.gson.Gson
import org.jsoup.HttpStatusException
import org.jsoup.Jsoup
import java.util.*
import java.util.regex.Pattern

class BandcampAPIConnector constructor(private val bandcampUser: String, private val cookies: Map<String, String>, private val timeout: Int) {

    private val gson = Gson()

    private val saleItemIDs2saleItemURLs: MutableMap<String, String> = HashMap<String, String>()
    private val saleItemIDs2digitalItems: MutableMap<String, DigitalItem?> = HashMap<String, DigitalItem?>()

    private data class ParsedFanpageData(
            val fan_data: FanData,
            val collection_data: CollectionData
    )

    private data class FanData(
            val fan_id: String
    )

    private data class CollectionData(
            val batch_size: Int,
            val item_count: Int,
            val last_token: String,
            val redownload_urls: Map<String, String>
    )

    private data class ParsedCollectionItems(
            val more_available: Boolean,
            val last_token: String,
            val redownload_urls: Map<String, String>
    )

    private data class ParsedBandcampData(
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

    private data class ParsedStatDownload(
            val download_url: String?,
            val url: String
    )

    fun init() {

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
        println("""Analyzing collection page: "${doc.title()}"""")

        // Get download pages
        val downloadPageJson = doc.select("#pagedata").attr("data-blob")
        val fanPageBlob = gson.fromJson(downloadPageJson, ParsedFanpageData::class.java)

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
                // println("Requesting collection_items API older than token $lastToken")
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

        this.saleItemIDs2saleItemURLs.putAll(collection)
    }


    fun getAllSaleItemIDs(): Set<String> {
        return saleItemIDs2saleItemURLs.keys
    }


    fun getCoverURL(saleItemID: String): String {
        val artid = this.retrieveDigitalItemData(saleItemID)!!.art_id
        return "https://f4.bcbits.com/img/a${artid}_10"
    }

    fun retrieveDigitalItemData(saleItemID: String): DigitalItem? {

        if (!this.saleItemIDs2digitalItems.containsKey(saleItemID)) {

            val saleItemURL = this.saleItemIDs2saleItemURLs[saleItemID]

            // Get page content
            try {
                val downloadPage = Jsoup.connect(saleItemURL)
                        .cookies(cookies)
                        .timeout(timeout).get()

                // Get data blob
                val downloadPageJson = downloadPage.select("#pagedata").attr("data-blob")
                val data = gson.fromJson(downloadPageJson, ParsedBandcampData::class.java)
                this.saleItemIDs2digitalItems[saleItemID] = data.digital_items[0]

            } catch (e: HttpStatusException) {

                // If 404, then the download page is not usable anymore for some reason (eg. a refund was given for the purchase)
                if (e.statusCode == 404)
                    this.saleItemIDs2digitalItems[saleItemID] = null
                else
                    throw e
            }

        }

        return this.saleItemIDs2digitalItems[saleItemID]
    }



    fun retrieveRealDownloadURL(saleItemID: String, audioFormat: String) : String?{
        val digitalItem = this.retrieveDigitalItemData(saleItemID)
        val downloadUrl = digitalItem!!.downloads[audioFormat]?.get("url").orEmpty()

        if (downloadUrl.isEmpty()) {
            return null
        }

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

        val statdownloadParsed = gson.fromJson(statdownloadJSON, ParsedStatDownload::class.java)

        return statdownloadParsed.download_url ?: downloadUrl
    }

}