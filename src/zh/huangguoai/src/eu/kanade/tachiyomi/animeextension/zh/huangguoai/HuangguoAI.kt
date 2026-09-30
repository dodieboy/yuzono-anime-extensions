package eu.kanade.tachiyomi.animeextension.zh.huangguoai

import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.lib.dataimage.DataImageInterceptor
import keiyoushi.utils.AnimeHttpLegacySource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

private class CategoryFilter :
    AnimeFilter.Select<String>(
        "分类",
        arrayOf("全部", "AI成人短剧", "AI成人漫剧", "AI换脸", "AI魔改"),
    ) {
    override fun toString() = arrayOf("", "/ai-duanju", "/ai-manju", "/ai-huanlian", "/ai-mogai")[state]
}

@Serializable
private data class VideoInitialData(
    val videoSrc: String = "",
    val epPlaySrcs: Map<String, String> = emptyMap(),
)

private const val COVER_PLACEHOLDER = "https://huangguoai.com/static/web/images/cover-placeholder.png"
private const val IMAGE_KEY = "f5d965df75336270"
private const val IMAGE_IV = "97b60394abc2fbe1"

class HuangguoAI : AnimeHttpLegacySource() {
    override val baseUrl = "https://huangguoai.com"
    override val lang = "zh"
    override val name = "黄果AI"
    override val supportsLatest = true

    override val client = network.client.newBuilder()
        .addInterceptor(DataImageInterceptor())
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    override fun headersBuilder() = super.headersBuilder().add("referer", "$baseUrl/")

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            title = document.selectFirst("h1")?.text().orEmpty()
            thumbnail_url = document.selectFirst(".hg-web-detail__poster img")?.let { image ->
                image.attr("abs:data-src").ifBlank { image.attr("data-src") }
            }?.let(::decryptImage).orEmpty().ifBlank { COVER_PLACEHOLDER }
            description = document.selectFirst("meta[name=description]")?.attr("content").orEmpty()
            genre = document.select(".hg-web-detail__hero a.hg-tag").joinToString { it.text() }
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> = response.asJsoup().select("a[href*=/video/]").distinctBy { it.attr("href") }.mapIndexed { index, element ->
        SEpisode.create().apply {
            setUrlWithoutDomain(element.absUrl("href"))
            name = element.text().ifBlank { "%02d".format(index + 1) }
            episode_number = Regex("\\d+").find(name)?.value?.toFloatOrNull() ?: (index + 1).toFloat()
        }
    }

    override fun videoListParse(response: Response): List<Video> {
        val data = response.asJsoup().selectFirst("#videoInitialData")?.data().orEmpty()
        val initialData = json.decodeFromString<VideoInitialData>(data)
        val videoUrl = initialData.videoSrc.ifBlank { initialData.epPlaySrcs.values.firstOrNull().orEmpty() }
        return if (videoUrl.isBlank()) emptyList() else listOf(Video(videoUrl, "黄果AI", videoUrl))
    }

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
        val encrypted = client.newCall(GET(imageUrl, headers)).execute().use { response ->
            response.body?.bytes() ?: return ""
        }
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
        }
        return AnimesPage(items, document.select("a[title*=下一页]:not([disabled])").isNotEmpty())
    }
}
