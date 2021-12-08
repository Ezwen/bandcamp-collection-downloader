package bandcampcollectiondownloader.core

import bandcampcollectiondownloader.util.Util
import com.google.gson.Gson
import org.jsoup.Connection.Method
import org.jsoup.HttpStatusException
import org.jsoup.Jsoup
import java.util.*
import java.util.regex.Pattern

class BandcampAPIConnector constructor(
    private val bandcampUser: String,
    private val cookies: Map<String, String>,
    private val skipHiddenItems: Boolean,
    private val timeout: Int,
    private val retries: Int,
    private val util: Util
) {

    private var bandcampPageName: String? = null
    private val gson = Gson()

    private val saleItemsIDs2saleItemsURLs: MutableMap<String, String> = HashMap<String, String>()
    private val saleItemsIDs2digitalItems: MutableMap<String, DigitalItem?> = HashMap<String, DigitalItem?>()

    private var initialized: Boolean = false

    private data class ParsedFanpageData(
        val fan_data: FanData,
        val collection_data: CollectionData,
        val hidden_data: CollectionData,
        val item_cache: ItemCache
    )

    private data class FanData(
        val fan_id: String
    )

    private data class ItemCache(
        val collection: Map<String, CachedItem>,
        val hidden: Map<String, CachedItem>
    )

    private data class CachedItem(
        val sale_item_id: String,
        val band_name: String,
        val item_title: String
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
        val downloads: Map<String, Map<String, String>>?,
        val package_release_date: String?,
        val title: String,
        val artist: String,
        val download_type: String,
        val download_type_str: String,
        val item_type: String,
        val art_id: String
    )

    private data class ParsedStatDownload(
        val download_url: String?,
        val url: String
    )


    fun init() {

        if (!initialized) {

            // Get collection page with cookies, hence with download links
            val doc =
                util.retry({
                    try {
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
                }, retries)


            // Get the JSON data blob hidden the the collection page, and parse it
            val downloadPageJson = doc!!.select("#pagedata").attr("data-blob")
            val fanPageBlob = gson.fromJson(downloadPageJson, ParsedFanpageData::class.java)

            // Get first set of download pages from the data blob
            // Warning: at this point the "redownload_urls" contain both hidden and non-hidden items !
            val collection = fanPageBlob.collection_data.redownload_urls.toMutableMap()
            if (collection.isEmpty()) {
                throw BandCampDownloaderError("No download links could by found in the collection page. This can be caused by an outdated or invalid cookies file.")
            }

            // To skip hidden items, we need to remove them from redownload_urls
            // We use item_cache for that, which clearly distinguishes hidden from non-hidden items
            if (skipHiddenItems) {
                collection.entries.retainAll { collectionEntry ->
                    !fanPageBlob.item_cache.hidden.values.any { hiddenItem ->
                        collectionEntry.value.contains(hiddenItem.sale_item_id)
                    }
                }
            }

            // Get the rest of the non-hidden collection
            if (fanPageBlob.collection_data.item_count > fanPageBlob.collection_data.batch_size) {
                collection.putAll(
                    retrieveDownloadURLs(
                        fanPageBlob.fan_data.fan_id,
                        fanPageBlob.collection_data,
                        "collection_items"
                    )
                )
            }

            // If needed, get the rest of the hidden collection
            if ((!skipHiddenItems) && (fanPageBlob.hidden_data.item_count > fanPageBlob.hidden_data.batch_size)) {
                collection.putAll(
                    retrieveDownloadURLs(
                        fanPageBlob.fan_data.fan_id,
                        fanPageBlob.hidden_data,
                        "hidden_items"
                    )
                )
            }

            this.saleItemsIDs2saleItemsURLs.putAll(collection)
            this.bandcampPageName = doc.title()
            this.initialized = true
        }
    }

    private fun retrieveDownloadURLs(
        fanID: String,
        collectionData: CollectionData,
        collectionName: String
    ): HashMap<String, String> {
        var lastToken = collectionData.last_token
        var moreAvailable = true
        val collection = HashMap<String, String>()
        while (moreAvailable) {
            // Append download pages from this api endpoint as well
            val theRest =
                util.retry({
                    Jsoup.connect("https://bandcamp.com/api/fancollection/1/${collectionName}")
                        .ignoreContentType(true)
                        .timeout(timeout)
                        .cookies(cookies)
                        .method(Method.POST)
                        .requestBody("{\"fan_id\": $fanID, \"older_than_token\": \"$lastToken\"}")
                        .execute()
                }, retries)

            val parsedCollectionData = gson.fromJson(theRest!!.body(), ParsedCollectionItems::class.java)
            collection.putAll(parsedCollectionData.redownload_urls)

            lastToken = parsedCollectionData.last_token
            moreAvailable = parsedCollectionData.more_available
        }
        return collection
    }

    fun getBandcampPageName(): String? {
        init()
        return bandcampPageName
    }

    fun getAllSaleItemIDs(): Set<String> {
        init()
        return saleItemsIDs2saleItemsURLs.keys
    }


    fun getCoverURL(saleItemID: String): String {
        init()
        val artid = this.retrieveDigitalItemData(saleItemID)!!.art_id
        return "https://f4.bcbits.com/img/a${artid}_10"
    }

    fun retrieveDigitalItemData(saleItemID: String): DigitalItem? {
        init()

        if (!this.saleItemsIDs2digitalItems.containsKey(saleItemID)) {

            val saleItemURL: String = this.saleItemsIDs2saleItemsURLs[saleItemID]!!

            // Get page content
            util.retry({
                try {
                    val downloadPage = Jsoup.connect(saleItemURL)
                        .cookies(cookies)
                        .timeout(timeout).get()

                    // Get data blob
                    val downloadPageJson = downloadPage.select("#pagedata").attr("data-blob")
                    val data = gson.fromJson(downloadPageJson, ParsedBandcampData::class.java)
                    this.saleItemsIDs2digitalItems[saleItemID] = data.digital_items[0]

                } catch (e: HttpStatusException) {

                    // If 404, then the download page is not usable anymore for some reason (eg. a refund was given for the purchase)
                    if (e.statusCode == 404)
                        this.saleItemsIDs2digitalItems[saleItemID] = null
                    else
                        throw e
                }
            }, retries)

        }

        return this.saleItemsIDs2digitalItems[saleItemID]
    }


    fun retrieveRealDownloadURL(saleItemID: String, audioFormat: String): String? {
        init()

        // Some releases have no digital items (eg. vinyl only) or no downloads or no urls, so we return null in such cases
        val digitalItem = this.retrieveDigitalItemData(saleItemID) ?: return null
        val downloads = digitalItem.downloads ?: return null
        val download = downloads[audioFormat] ?: return null
        val downloadUrl = download["url"] ?: return null

        val random = Random()

        // Construct statdownload request URL
        val statdownloadURL: String = downloadUrl
            .replace("/download/", "/statdownload/")
            .replace("http:", "https:") + "&.vrs=1" + "&.rand=" + random.nextInt()

        // Get statdownload JSON
        val statedownloadUglyBody: String = util.retry({
            Jsoup.connect(statdownloadURL)
                .cookies(cookies)
                .timeout(timeout)
                .get().body().select("body")[0].text().toString()
        }, retries)!!

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
