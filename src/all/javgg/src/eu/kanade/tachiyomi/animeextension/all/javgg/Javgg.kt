package eu.kanade.tachiyomi.animeextension.all.javgg

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.okruextractor.OkruExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.vidhideextractor.VidHideExtractor
import aniyomi.lib.voeextractor.VoeExtractor
import aniyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.AnimeHttpLegacySource
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale

class Javgg :
    AnimeHttpLegacySource(),
    ConfigurableAnimeSource {

    override val name = "JavGG"

    override val baseUrl = "https://javgg.net"

    override val lang = "all"

    override val supportsLatest = true

    override val supportsRelatedAnimes = false

    private val preferences by getPreferencesLazy()

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "StreamWish"

        /**
         * Current servers available on the site:
         * TB: TurboPlay
         * VH: VidHide
         * MD: MixDrop
         * SW: StreamWish
         * VG: JavGuard
         * VO: Voe
         * upjav: upjav
         */
        private val SERVER_LIST = arrayOf(
            "StreamWish",
            "Voe",
            "Okru",
            "YourUpload",
            "FileLions",
            "StreamHideVid",
            "TurboPlay",
        )
        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("MMM. dd,yyyy", Locale.getDefault())
        }
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val animeDetails = SAnime.create().apply {
            status = SAnime.COMPLETED
            description = document.select("#cover p").joinToString { it.text() }
        }

        document.select(".data .boxye2").forEach { element ->
            val category = element.select("[id*=owye2]").text().trim()
            val tags = element.select(".sgeneros3 a").joinToString { it.text() }
            when {
                category.contains("Genres:") -> animeDetails.genre = tags
                category.contains("Cast:") -> animeDetails.artist = tags
                category.contains("Maker:") -> animeDetails.author = tags
            }
        }

        return animeDetails
    }

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/trending/page/$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val elements = document.select("article[id*=post-]")
        val nextPage = document.select("#nextpagination").any()
        val animeList = elements.map { element ->
            SAnime.create().apply {
                setUrlWithoutDomain(element.selectFirst("a")!!.attr("abs:href"))
                title = element.selectFirst(".data h3")!!.text()
                thumbnail_url = element.selectFirst(".poster")?.getImageUrl()
            }
        }
        return AnimesPage(animeList, nextPage)
    }

    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/new-post/page/$page", headers)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        // If a tag filter is selected (not "All"), prefer tag page over query
        val tagSelect = filters.find { it is TagSelect } as? TagSelect
        val selectedTag = tagSelect?.let { if (it.state == 0) "" else it.values[it.state] } ?: ""

        val slugMap = mapOf(
            "Chinese Subtitle" to "chinese-subtitle",
            "English Subtitle" to "english-subtitle",
            "HD Uncensored" to "hd-uncensored",
            "Censored" to "censored",
            "Decensored" to "decensored",
            "Uncensored Leak" to "uncensored-leak",
        )

        if (selectedTag.isNotBlank()) {
            val slug = slugMap[selectedTag]
            if (!slug.isNullOrBlank()) return GET("$baseUrl/tag/$slug/page/$page/?view=today", headers)
        }

        return when {
            query.isNotBlank() -> GET("$baseUrl/jav/page/$page?s=$query", headers)
            else -> popularAnimeRequest(page)
        }
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        TagSelect(),
    )

    private class TagSelect :
        AnimeFilter.Select<String>(
            "Tags",
            arrayOf(
                "All",
                "Chinese Subtitle",
                "English Subtitle",
                "HD Uncensored",
                "Censored",
                "Decensored",
                "Uncensored Leak",
            ),
        )

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val elements = document.select(".result-item article").ifEmpty {
            document.select("article[id*=post-]")
        }
        val nextPage = document.select("#nextpagination").any()
        val animeList = elements.map { element ->
            SAnime.create().apply {
                setUrlWithoutDomain(element.selectFirst("a")!!.attr("abs:href"))
                title = element.selectFirst(".data h3 a")?.text()
                    ?: element.selectFirst("h3 a")?.text()
                    ?: element.selectFirst(".details .title")?.text()
                    ?: element.selectFirst(".data h3")?.text()
                    ?: ""
                thumbnail_url = element.selectFirst(".poster img")?.let { img ->
                    val lazySrc = img.attr("data-lazy-src")
                    if (!lazySrc.isNullOrEmpty()) {
                        lazySrc
                    } else {
                        img.attr("src").takeIf { !it.isNullOrEmpty() }
                            ?: element.selectFirst(".image")?.getImageUrl()
                            ?: element.selectFirst(".poster")?.getImageUrl()
                    }
                } ?: element.selectFirst(".image img")?.let { img ->
                    val lazySrc = img.attr("data-lazy-src")
                    if (!lazySrc.isNullOrEmpty()) {
                        lazySrc
                    } else {
                        img.attr("src").takeIf { !it.isNullOrEmpty() }
                            ?: element.selectFirst(".image")?.getImageUrl()
                            ?: element.selectFirst(".poster")?.getImageUrl()
                    }
                }
            }
        }
        return AnimesPage(animeList, nextPage)
    }

    override fun String.stripKeywordForRelatedAnimes(): List<String> {
        val regexWhitespace = Regex("\\s+")
        val regexSpecialCharacters =
            Regex("([-!~#$%^&*+_|/\\\\,?:;'“”‘’\"<>(){}\\[\\]。・～：—！？、―«»《》〘〙【】「」｜]|\\s-|-\\s|\\s\\.|\\.\\s)")
        val regexNumberOnly = Regex("^\\d+$")

        return replace(regexSpecialCharacters, " ")
            .split(regexWhitespace)
            .map {
                // remove number only
                it.replace(regexNumberOnly, "")
                    .lowercase()
            }
            // exclude single character
            .filter { it.length > 1 }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val ep = document.select(".dooplay_player_option")
        if (!ep.any()) return emptyList()

        val date = document.selectFirst(".extra span.date")?.text()?.parseDate() ?: 0L
        val url = document.location()
        val firstTitle = ep.lastOrNull()?.selectFirst("span.title")?.text()?.trim()
        val partMatch = firstTitle?.let { Regex("Part\\s*(\\d+)", RegexOption.IGNORE_CASE).find(it) }
        if (partMatch != null) {
            val count = partMatch.groupValues.getOrNull(1)?.toIntOrNull() ?: 1
            return (1..count).map { idx ->
                episodeFromElement(url, idx).apply { date_upload = date }
            }
        }
        val template = ep.first() ?: return emptyList()
        return listOf(episodeFromElement(url, 1).apply { date_upload = date })
    }

    private fun episodeFromElement(pageUrl: String, overrideNumber: Int? = null) = SEpisode.create().apply {
        val num = overrideNumber?.toFloat() ?: 1F
        episode_number = num
        name = "Episode ${num.toInt()}"
        val uniqueUrl = if (overrideNumber != null) "$pageUrl#${num.toInt()}" else pageUrl
        setUrlWithoutDomain(uniqueUrl)
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        return document.select("[id*=source-player] iframe").parallelCatchingFlatMapBlocking {
            val numOpt = it.closest(".source-box")?.attr("id")?.replace("source-player-", "")
            val serverName = document.select("[data-nume=\"$numOpt\"] .server").attr("data-text")
            serverVideoResolver(serverName, it.attr("src"))
        }
    }

    private suspend fun serverVideoResolver(server: String, url: String): List<Video> {
        val embedUrl = server.lowercase()
        return when {
            embedUrl.contains("ok.ru") || embedUrl.contains("okru") -> OkruExtractor(client).videosFromUrl(url)

            embedUrl.contains("filelions") || embedUrl.contains("lion") -> StreamWishExtractor(client, headers).videosFromUrl(url, videoNameGen = { "FileLions:$it" })

            embedUrl.contains("wishembed") || embedUrl.contains("streamwish") || embedUrl.contains("strwish") || embedUrl.contains("wish") -> {
                val docHeaders = headers.newBuilder()
                    .add("Origin", "https://streamwish.to")
                    .add("Referer", "https://streamwish.to/")
                    .build()
                StreamWishExtractor(client, docHeaders).videosFromUrl(url, videoNameGen = { "StreamWish:$it" })
            }

            embedUrl.contains("vidhide") || embedUrl.contains("streamhide") || embedUrl.contains("guccihide") || embedUrl.contains("streamvid") -> VidHideExtractor(client, headers).videosFromUrl(url)

            embedUrl.contains("voe") -> VoeExtractor(client, headers).videosFromUrl(url)

            embedUrl.contains("yourupload") || embedUrl.contains("upload") -> YourUploadExtractor(client).videoFromUrl(url, headers = headers)

            embedUrl.contains("turboplay") -> {
                val turboDocument = client.newCall(GET(url)).execute().asJsoup()
                val masterUrl = turboDocument.select("#video_player").attr("data-hash")
                val customHeaders = headers.newBuilder().apply {
                    add("Accept", "*/*")
                    add("Origin", "https://${turboDocument.location().toHttpUrl().host}")
                    add("Referer", "https://${turboDocument.location().toHttpUrl().host}/")
                }.build()

                listOf(Video(masterUrl, "TurboPlay", masterUrl, customHeaders))
            }

            else -> emptyList()
        }
    }

    private fun org.jsoup.nodes.Element.getImageUrl(): String? {
        val imageLinkRegex = """https?://\S+\.(jpg|png|webp)""".toRegex()

        for (link in this.select("[href], [src]")) {
            val href = link.attr("href")
            val src = link.attr("src")
            if (imageLinkRegex.matches(href)) {
                return href
            }
            if (imageLinkRegex.matches(src)) {
                return src
            }
        }

        val textMatches = imageLinkRegex.find(this.text())
        val htmlMatches = imageLinkRegex.find(this.outerHtml())
        return textMatches?.value ?: htmlMatches?.value
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        return this.sortedWith(
            compareBy(
                { it.videoTitle.contains(server, true) },
                { it.videoTitle.contains(quality) },
                { Regex("""(\d+)p""").find(it.videoTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Preferred server"
            entries = SERVER_LIST
            entryValues = SERVER_LIST
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = QUALITY_LIST
            entryValues = QUALITY_LIST
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
    }
    private fun String.parseDate(): Long = runCatching { DATE_FORMATTER.parse(this)?.time }
        .getOrNull() ?: 0L
}
