package eu.kanade.tachiyomi.animeextension.zh.chengguodj

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.AnimeHttpLegacySource
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class ChengguoDj : AnimeHttpLegacySource() {
    override val baseUrl = "https://chengguodj.com"
    override val name = "橙果短剧"
    override val lang = "zh"
    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)

    override fun popularAnimeRequest(page: Int) = browseRequest(page, "hot")

    override fun popularAnimeParse(response: Response) = parseAnimeList(response.asJsoup())

    override fun latestUpdatesRequest(page: Int) = browseRequest(page, "new")

    override fun latestUpdatesParse(response: Response) = parseAnimeList(response.asJsoup())

    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Request {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("search")
            .addQueryParameter("q", query)
        if (page > 1) url.addQueryParameter("page", page.toString())
        return GET(url.build())
    }

    override fun searchAnimeParse(response: Response) = parseAnimeList(response.asJsoup())

    override fun animeDetailsParse(response: Response) = response.asJsoup().let { document ->
        SAnime.create().apply {
            title = document.selectFirst(".detail-info .page-h1")!!.text()
            thumbnail_url = document.selectFirst(".detail-cover img")?.let(::imageUrl)
            genre = document.select(".detail-tags a").joinToString { it.text() }
            description = document.selectFirst(".detail-intro")?.text()
            status = if (document.selectFirst(".detail-meta")?.text()?.contains("连载中") == true) {
                SAnime.ONGOING
            } else {
                SAnime.COMPLETED
            }
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> = response.asJsoup()
        .select(".ep-grid .ep-link")
        .map { element ->
            SEpisode.create().apply {
                setUrlWithoutDomain(element.absUrl("href"))
                name = "第 ${element.text()} 集"
                episode_number = element.text().toFloatOrNull() ?: 0F
            }
        }
        .reversed()

    override fun videoListRequest(episode: SEpisode) = GET(baseUrl + episode.url)

    override fun videoListParse(response: Response): List<Video> {
        val script = response.asJsoup()
            .selectFirst("script:containsData(window.HG_PLAY)")
            ?.data()
            ?: return emptyList()
        val player = script.substringAfter("window.HG_PLAY = ").substringBeforeLast(';').parseAs<PlayerData>()
        val episodeNumber = response.request.url.pathSegments.lastOrNull()?.toIntOrNull()
        val episode = player.episodes.firstOrNull { it.n == episodeNumber } ?: return emptyList()
        return episode.hls?.let { url ->
            listOf(Video(videoUrl = url, videoTitle = "橙果短剧"))
        } ?: emptyList()
    }

    private fun browseRequest(page: Int, sort: String): Request {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("browse")
            .addQueryParameter("line", "")
            .addQueryParameter("tag", "")
            .addQueryParameter("status", "")
            .addQueryParameter("sort", sort)
        if (page > 1) url.addQueryParameter("page", page.toString())
        return GET(url.build())
    }

    private fun parseAnimeList(document: Document): AnimesPage {
        val items = document.select(".card").map { card ->
            SAnime.create().apply {
                setUrlWithoutDomain(card.selectFirst(".card-meta a")!!.absUrl("href"))
                title = card.selectFirst(".card-title")!!.text()
                thumbnail_url = card.selectFirst(".card-cover img")?.let(::imageUrl)
            }
        }
        return AnimesPage(items, document.selectFirst("a.load-more") != null)
    }

    private fun imageUrl(element: Element): String? = element.absUrl("data-cover-fb").ifBlank {
        element.absUrl("src")
    }.ifBlank {
        element.attr("z-image-loader-url")
    }.ifBlank { null }
}

@Serializable
private class PlayerData(
    val episodes: List<PlayerEpisode>,
)

@Serializable
private class PlayerEpisode(
    val n: Int,
    val hls: String? = null,
)
