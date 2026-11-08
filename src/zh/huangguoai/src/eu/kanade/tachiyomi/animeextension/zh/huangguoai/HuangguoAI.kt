package eu.kanade.tachiyomi.animeextension.zh.huangguoai

import android.util.Base64
import aniyomi.lib.m3u8server.M3u8Integration
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.lib.dataimage.DataImageInterceptor
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.utils.jsonInstance
import keiyoushi.utils.toJsonRequestBody
import kotlinx.serialization.Serializable
import okhttp3.CacheControl
import okhttp3.Request
import okhttp3.Response
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.max
import kotlin.math.min

private class CategoryFilter :
    AnimeFilter.Select<String>(
        "分类",
        arrayOf("全部", "AI成人短剧", "AI成人漫剧", "AI换脸", "AI魔改"),
    ) {
    override fun toString() = arrayOf("", "/ai-duanju", "/ai-manju", "/ai-huanlian", "/ai-mogai")[state]
}

private const val COVER_PLACEHOLDER = "https://huangguoai.com/static/web/images/cover-placeholder.png"
private const val IMAGE_KEY = "f5d965df75336270"
private const val IMAGE_IV = "97b60394abc2fbe1"

@Serializable
private data class PlaybackRequest(val dummy: Unit = Unit)

@Serializable
private data class PlaybackResponse(
    val status: Int,
    val data: PlaybackData?,
    val msg: String?,
)

@Serializable
private data class PlaybackData(
    val streams: Streams,
    val cdn: CdnInfo,
)

@Serializable
private data class Streams(
    val main: String,
    val preview: String?,
    val hevc: String?,
)

@Serializable
private data class CdnInfo(
    val m3u8Enabled: Boolean,
    val authKey: String,
    val authValue: String,
    val imgAuthKey: String,
    val imgAuthValue: String,
)

@Source
abstract class HuangguoAI : AnimeHttpSource() {

    override val supportsLatest = true

    override val client = network.client.newBuilder()
        .addInterceptor(DataImageInterceptor())
        .build()

    private val m3u8Integration by lazy { M3u8Integration(client) }

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            title = document.selectFirst("h1")?.text().orEmpty()
            description = document.selectFirst("meta[name=description]")?.attr("content").orEmpty()
            genre = document.select(".hg-web-detail__hero a.hg-tag").joinToString { it.text() }
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeLinks = document.select("a[data-ep-id][href*='/video/']").reversed()
        val currentUrl = response.request.url.toString().takeIf { "/video/" in it }
        val urls = buildList {
            episodeLinks.forEach { element ->
                add(element.absUrl("href") to element.text().ifBlank { "" })
            }
            if (currentUrl != null && none { it.first == currentUrl }) {
                add(0, currentUrl to "01")
            }
        }.distinctBy { it.first }

        return urls.mapIndexed { index, (url, label) ->
            SEpisode.create().apply {
                setUrlWithoutDomain(url)
                name = label.ifBlank { "%02d".format(index + 1) }
                episode_number = Regex("\\d+").find(name)?.value?.toFloatOrNull() ?: (index + 1).toFloat()
            }
        }
    }

    // ============================ Video Links (Lib 16) ============================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val episodeUrl = baseUrl + episode.url
        val episodeHeaders = headers.newBuilder()
            .set("Referer", "$baseUrl/")
            .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
            .build()
        val episodeResponse = client.get(episodeUrl, episodeHeaders, cacheControl = CacheControl.FORCE_NETWORK)
        return episodeResponse.use { response ->
            parseHosterList(response)
        }
    }

    private suspend fun parseHosterList(response: Response): List<Hoster> {
        val document = response.asJsoup()
        val episodePageUrl = response.request.url.toString()

        // Extract video ID from the URL first (format: /video/12/ep-21/)
        val videoId = extractVideoIdFromUrl(episodePageUrl) ?: run {
            // Fallback: try extracting from page
            val fromPage = extractVideoId(document)
            if (fromPage == null) return emptyList()
            fromPage
        }

        // Extract episode number from URL
        // URL format: .../ep-21/ -> episode ID is 21
        val episodeId = extractEpisodeId(episodePageUrl) ?: return emptyList()

        // Call the playback API to get the m3u8 URL
        val playbackUrl = "$baseUrl/api/videos/$videoId/episodes/$episodeId/playback"
        val playbackRequest = PlaybackRequest()
        val apiResponse = client.post(playbackUrl, playbackRequest.toJsonRequestBody())

        return apiResponse.use { apiResponse ->
            val bodyString = apiResponse.body.string()
            val playbackResponse = jsonInstance.decodeFromString<PlaybackResponse>(bodyString)

            if (playbackResponse.status == 1 && playbackResponse.data?.streams?.main?.isNotBlank() == true) {
                val videoUrl = playbackResponse.data.streams.main
                val cdn = playbackResponse.data.cdn
                val internalData = buildString {
                    append(videoUrl)
                    append("|headers|")
                    append(cdn.authKey.orEmpty())
                    append("|")
                    append(cdn.authValue.orEmpty())
                }
                listOf(
                    Hoster(
                        hosterUrl = episodePageUrl,
                        hosterName = "黄果AI",
                        internalData = internalData,
                    ),
                )
            } else {
                emptyList()
            }
        }
    }

    private fun extractVideoIdFromUrl(url: String): String? {
        // URL format: https://huangguoai.com/video/12/ep-21/ -> video ID is 12
        val match = Regex("/video/(\\d+)/ep-\\d+/").find(url)
        return match?.groupValues?.get(1)
    }

    private fun extractVideoId(document: org.jsoup.nodes.Document): String? {
        // Try to find video ID in various places
        // 1. From data attributes on video element
        val videoEl = document.selectFirst("video.hg-web-play__video")
        val dataVideoId = videoEl?.attr("data-video-id") ?: videoEl?.attr("data-id")
        if (dataVideoId?.isNotBlank() == true) return dataVideoId

        // 2. From script tags (JSON data) - look for videoId specifically
        val scripts = document.select("script")
        for (script in scripts) {
            val html = script.html()
            if (html.isBlank()) continue
            val videoIdMatch = Regex("\"videoId\"\\s*:\\s*[\"']?(\\d+)[\"']?").find(html)
            if (videoIdMatch != null) return videoIdMatch.groupValues[1]
        }

        // 3. From generic "id" in script tags with video context
        for (script in scripts) {
            val html = script.html()
            if (html.isBlank()) continue
            val idMatch = Regex("\"id\"\\s*:\\s*[\"']?(\\d+)[\"']?").find(html)
            if (idMatch != null) {
                // Check if this looks like a video ID by context
                val context = html.substring(max(0, idMatch.range.first - 50), min(html.length, idMatch.range.last + 50))
                if (context.contains("video", ignoreCase = true)) return idMatch.groupValues[1]
            }
        }

        return null
    }

    private fun extractEpisodeId(url: String): String? {
        // URL pattern: /ep-21/ or /episodes/21/
        val epMatch = Regex("/ep-(\\d+)/|/episodes/(\\d+)/").find(url)
        return epMatch?.groupValues?.get(1) ?: epMatch?.groupValues?.get(2)
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val internalData = hoster.internalData
        return if (internalData.isBlank()) {
            emptyList()
        } else {
            // Parse internalData format: videoUrl|headers|authKey|authValue
            val parts = internalData.split("|headers|")
            val videoUrl = parts[0]
            val videoHeaders = headers.newBuilder()
                .set("Referer", baseUrl)
                .apply {
                    if (parts.size > 1) {
                        val headerParts = parts[1].split("|")
                        if (headerParts.size == 2 && headerParts[0].isNotBlank() && headerParts[1].isNotBlank()) {
                            set(headerParts[0], headerParts[1])
                        }
                    }
                }
                .build()
            val videos = listOf(
                Video(
                    videoUrl = videoUrl,
                    videoTitle = hoster.hosterName,
                    headers = videoHeaders,
                ),
            )
            // Process through m3u8 server if it's an HLS stream
            return m3u8Integration.processVideoList(videos)
        }
    }

    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()

    override fun videoListParse(response: Response, hoster: Hoster): List<Video> = throw UnsupportedOperationException()

    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()

    // ============================ Lists ============================

    override fun latestUpdatesParse(response: Response) = parseAnimeList(response)

    override fun latestUpdatesRequest(page: Int): Request = GET(pageRequest("/newest", page))

    override fun popularAnimeParse(response: Response) = parseAnimeList(response)

    override fun popularAnimeRequest(page: Int): Request = GET(pageRequest("/recommend", page))

    override fun searchAnimeParse(response: Response) = parseAnimeList(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val category = filters.filterIsInstance<CategoryFilter>().firstOrNull()?.toString().orEmpty()
        val path = if (category.isNotBlank()) category else "/search/video/${query.trim()}/"
        return GET(pageRequest(path, page))
    }

    override fun getFilterList() = AnimeFilterList(CategoryFilter())

    private fun pageRequest(path: String, page: Int): String {
        val normalizedPath = path.trimEnd('/')
        return if (page == 1) "$baseUrl$normalizedPath/" else "$baseUrl$normalizedPath/$page/"
    }

    private fun decryptImage(imageUrl: String): String = runCatching {
        val request = GET(imageUrl, headers)
        val response = client.newCall(request).execute()
        val encrypted = response.use { it.body.bytes() }
        val cipher = Cipher.getInstance("AES/CBC/NoPadding").apply {
            init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(IMAGE_KEY.toByteArray(), "AES"),
                IvParameterSpec(IMAGE_IV.toByteArray()),
            )
        }
        val extension = imageUrl.substringBefore('?').substringAfterLast('.').lowercase().let {
            if (it == "jpg") "jpeg" else it.ifBlank { "jpeg" }
        }
        "https://127.0.0.1/?image/$extension;base64,${Base64.encodeToString(cipher.doFinal(encrypted), Base64.NO_WRAP)}"
    }.getOrDefault("")

    private fun parseAnimeList(response: Response): AnimesPage {
        val document = response.asJsoup()
        val items = document.select(".hg-list-page .hg-drama-card").mapNotNull { card ->
            val link = card.selectFirst("a.hg-drama-card__cover-link") ?: return@mapNotNull null
            SAnime.create().apply {
                setUrlWithoutDomain(link.absUrl("href"))
                title = card.selectFirst("h3.hg-drama-card__title a")?.ownText()
                    ?.ifBlank { card.selectFirst("h3.hg-drama-card__title a")?.text() }
                    .orEmpty()
                thumbnail_url = card.selectFirst("img")?.let { image ->
                    image.attr("abs:data-src").ifBlank { image.attr("data-src") }
                }?.let(::decryptImage).orEmpty().ifBlank { COVER_PLACEHOLDER }
            }
        }.distinctBy { it.url }
        return AnimesPage(items, document.select("a[title*=下一页]:not([disabled])").isNotEmpty())
    }
}
