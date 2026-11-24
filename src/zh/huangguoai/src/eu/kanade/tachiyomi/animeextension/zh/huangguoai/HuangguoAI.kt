package eu.kanade.tachiyomi.animeextension.zh.huangguoai

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
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.utils.jsonInstance
import keiyoushi.utils.toJsonRequestBody
import kotlinx.serialization.Serializable
import okhttp3.CacheControl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
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

// Fragment marker appended to encrypted cover URLs so HuangguoImageInterceptor can identify them.
// The interceptor strips the marker before making the real network request.
private const val ENCRYPTED_COVER_MARKER = "#huangguo-encrypted"

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

/**
 * Interceptor that decrypts HuangguoAI's encrypted cover images.
 *
 * Only handles requests explicitly marked with the [ENCRYPTED_COVER_MARKER] fragment. HTML, API,
 * JSON, placeholder, and other image requests pass through untouched.
 *
 * For a marked request it:
 *   1. Strips the fragment marker from the URL.
 *   2. Proceeds with the cleaned request to fetch the encrypted image bytes.
 *   3. Decrypts the body with AES/CBC/NoPadding using [IMAGE_KEY] and [IMAGE_IV].
 *   4. Returns a synthetic response whose body is the decrypted image bytes with the appropriate
 *      Content-Type so Coil can render it.
 *
 * On decryption failure it returns the original response so list loading is not broken.
 */
private class HuangguoImageInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        if (url.fragment != "huangguo-encrypted") {
            return chain.proceed(request)
        }

        val cleanedUrl = url.newBuilder().fragment(null).build()
        val cleanedRequest = request.newBuilder().url(cleanedUrl).build()

        return chain.proceed(cleanedRequest).use { originalResponse ->
            val body = originalResponse.body
            val encryptedBytes = body.bytes()

            val decryptedBytes = try {
                val cipher = Cipher.getInstance("AES/CBC/NoPadding").apply {
                    init(
                        Cipher.DECRYPT_MODE,
                        SecretKeySpec(IMAGE_KEY.toByteArray(), "AES"),
                        IvParameterSpec(IMAGE_IV.toByteArray()),
                    )
                }
                cipher.doFinal(encryptedBytes)
            } catch (e: Exception) {
                return@use originalResponse
            }

            val path = url.encodedPath
            val contentType = when {
                path.endsWith(".jpg", ignoreCase = true) || path.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
                path.endsWith(".png", ignoreCase = true) -> "image/png"
                path.endsWith(".webp", ignoreCase = true) -> "image/webp"
                else -> "application/octet-stream"
            }

            originalResponse.newBuilder()
                .removeHeader("Content-Length")
                .removeHeader("Content-Encoding")
                .header("Content-Type", contentType)
                .body(decryptedBytes.toResponseBody(contentType.toMediaTypeOrNull()))
                .build()
        }
    }
}

@Source
abstract class HuangguoAI : AnimeHttpSource() {

    override val supportsLatest = true

    override val client = network.client.newBuilder()
        .addInterceptor(HuangguoImageInterceptor())
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
            .set("Origin", "$baseUrl/")
            .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
            .build()
        val episodeResponse = client.get(episodeUrl, episodeHeaders, cacheControl = CacheControl.FORCE_NETWORK)
        return episodeResponse.use { response ->
            parseHosterList(response, episode.episode_number.toInt().toString())
        }
    }

    private suspend fun parseHosterList(response: Response, episodeIdFallback: String): List<Hoster> {
        val document = response.asJsoup()
        val episodePageUrl = response.request.url.toString()

        // Extract video ID from the URL first (format: /video/12/ep-21/)
        val videoId = extractVideoIdFromUrl(episodePageUrl) ?: run {
            // Fallback: try extracting from page
            val fromPage = extractVideoId(document)
            if (fromPage == null) return emptyList()
            fromPage
        }

        // Extract episode number from URL (/ep-21/) or fall back to the parsed episode_number
        val episodeId = extractEpisodeId(episodePageUrl) ?: episodeIdFallback

        // Call the playback API to get the m3u8 URL.
        // The site may require Origin/Referer headers for CORS.
        val apiHeaders = headers.newBuilder()
            .set("Origin", baseUrl)
            .set("Referer", episodePageUrl)
            .set("Accept", "application/json, text/plain, */*")
            .set("Content-Type", "application/json; charset=utf-8")
            .build()

        val playbackRequest = PlaybackRequest()

        // Try multiple API path patterns in case the site changed its URL structure
        val apiPaths = listOf(
            "$baseUrl/api/videos/$videoId/episodes/$episodeId/playback",
            "$baseUrl/api/videos/$videoId/playback",
        )

        for (playbackUrl in apiPaths) {
            try {
                val apiResponse = client.post(playbackUrl, apiHeaders, playbackRequest.toJsonRequestBody())
                val hosters = apiResponse.use { apiResponse ->
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
                if (hosters.isNotEmpty()) return hosters
            } catch (e: Exception) {
                // Try next API path
                continue
            }
        }

        return emptyList<Hoster>()
    }

    private fun extractVideoIdFromUrl(url: String): String? {
        // URL format: https://huangguoai.com/video/12/ep-21/ -> video ID is 12
        // First episode: https://huangguoai.com/video/12/ -> video ID is 12
        val match = Regex("/video/(\\d+)(?:/ep-\\d+)?/").find(url)
        return match?.groupValues?.get(1)
    }

    // Extract video ID from the page (fallback when URL parsing fails)
    // Tries multiple patterns to be robust against site changes
    private fun extractVideoId(document: org.jsoup.nodes.Document): String? {
        // Try multiple patterns in order of likelihood:
        // 1. data-video-id / data-id attributes on video element
        // 2. "videoId": in script tags
        // 3. Any id in script tags near "video" context
        val scripts = document.select("script")

        // Check data attributes first
        val videoEl = document.selectFirst("video.hg-web-play__video")
        val dataVideoId = videoEl?.attr("data-video-id") ?: videoEl?.attr("data-id")
        if (dataVideoId?.isNotBlank() == true) return dataVideoId

        // Collect all script HTML once
        val scriptHtmls = scripts.map { it.html() }.filter { it.isNotBlank() }

        // 2. From script tags (JSON data) - look for videoId specifically
        for (html in scriptHtmls) {
            val videoIdMatch = Regex("\"videoId\"\\s*:\\s*[\"']?(\\d+)[\"']?").find(html)
            if (videoIdMatch != null) return videoIdMatch.groupValues[1]
        }

        // 3. From generic "id" in script tags with video context
        for (html in scriptHtmls) {
            val idMatch = Regex("\"id\"\\s*:\\s*[\"']?(\\d+)[\"']?").find(html)
            if (idMatch != null) {
                val context = html.substring(max(0, idMatch.range.first - 80), min(html.length, idMatch.range.last + 80))
                if (context.contains("video", ignoreCase = true)) return idMatch.groupValues[1]
            }
        }

        return null
    }

    private fun extractEpisodeId(url: String): String? {
        // 1) Explicit episode segment: /ep-21/ or /episodes/21/
        Regex("/(?:ep-|episodes/)(\\d+)(?:/|$)")
            .find(url)?.let { return it.groupValues[1] }

        // 2) Bare video URL (/video/7022/ or /video/7022) => first episode
        if (Regex("/video/\\d+(?:/|$)").containsMatchIn(url)) return "1"

        // 3) Anything else: unknown
        return null
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
                    val src = image.attr("abs:data-src").ifBlank { image.attr("data-src") }
                    if (src.isBlank()) COVER_PLACEHOLDER else "$src$ENCRYPTED_COVER_MARKER"
                } ?: COVER_PLACEHOLDER
            }
        }.distinctBy { it.url }
        return AnimesPage(items, document.select("a[title*=下一页]:not([disabled])").isNotEmpty())
    }
}
