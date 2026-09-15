package io.github.go0dboy.articlenavigator.collector.rss

import io.github.go0dboy.articlenavigator.collector.api.DiscoveryResult
import io.github.go0dboy.articlenavigator.collector.api.FetchResult
import io.github.go0dboy.articlenavigator.collector.api.SourceAdapter
import io.github.go0dboy.articlenavigator.collector.api.UrlCanonicalizer
import io.github.go0dboy.articlenavigator.core.model.DiscoveredItem
import io.github.go0dboy.articlenavigator.core.model.DiscoveredItemId
import io.github.go0dboy.articlenavigator.core.model.Source
import io.github.go0dboy.articlenavigator.core.model.SourceCursor
import io.github.go0dboy.articlenavigator.core.model.SourceType
import io.github.go0dboy.articlenavigator.core.network.HttpRequest
import io.github.go0dboy.articlenavigator.core.network.HttpResponse
import io.github.go0dboy.articlenavigator.core.network.HttpTransport
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

class RssAtomSourceAdapter(
    private val transport: HttpTransport,
    private val clock: Clock = Clock.systemUTC(),
) : SourceAdapter {
    override val supportedTypes: Set<SourceType> = setOf(SourceType.RSS, SourceType.ATOM)

    override suspend fun discover(source: Source, cursor: SourceCursor?): DiscoveryResult {
        require(supports(source.type)) { "Unsupported source type: ${source.type}" }

        val requestHeaders = linkedMapOf(
            "Accept" to "application/rss+xml, application/atom+xml, application/xml, text/xml;q=0.9, */*;q=0.1",
        )
        cursor?.etag?.let { requestHeaders["If-None-Match"] = it }
        cursor?.lastModified?.let { requestHeaders["If-Modified-Since"] = it }

        val response = transport.execute(HttpRequest(source.url, requestHeaders))
        val now = clock.instant()

        if (response.statusCode == 304) {
            return DiscoveryResult(
                items = emptyList(),
                nextCursor = SourceCursor(
                    sourceId = source.id,
                    etag = response.header("ETag") ?: cursor?.etag,
                    lastModified = response.header("Last-Modified") ?: cursor?.lastModified,
                    opaqueCursor = cursor?.opaqueCursor,
                    lastGuid = cursor?.lastGuid,
                    lastCheckedAt = now,
                ),
            )
        }
        if (response.statusCode !in 200..299) {
            throw FeedHttpException(source.url, response.statusCode)
        }

        val parsed = parseFeed(response.body, source, now)
        return DiscoveryResult(
            items = parsed.items,
            nextCursor = SourceCursor(
                sourceId = source.id,
                etag = response.header("ETag") ?: cursor?.etag,
                lastModified = response.header("Last-Modified") ?: cursor?.lastModified,
                opaqueCursor = cursor?.opaqueCursor,
                lastGuid = parsed.firstStableKey ?: cursor?.lastGuid,
                lastCheckedAt = now,
            ),
        )
    }

    override suspend fun fetch(item: DiscoveredItem): FetchResult {
        val response = transport.execute(
            HttpRequest(
                url = item.url,
                headers = mapOf(
                    "Accept" to "text/html, application/xhtml+xml, application/xml;q=0.9, */*;q=0.8",
                ),
            ),
        )
        return FetchResult(
            item = item,
            statusCode = response.statusCode,
            contentType = response.contentType,
            body = response.body,
            fetchedHeaders = response.headers.mapValues { (_, values) -> values.joinToString(",") },
        )
    }

    private fun parseFeed(bytes: ByteArray, source: Source, discoveredAt: Instant): ParsedFeed {
        val document = runCatching {
            Jsoup.parse(ByteArrayInputStream(bytes), null, source.url, Parser.xmlParser())
        }.getOrElse { throw FeedParseException(source.url, it) }

        val root = document.children().firstOrNull()
            ?: throw FeedParseException(source.url, IllegalArgumentException("Feed has no root element"))

        val entries = when (root.tagName().lowercase()) {
            "rss", "rdf:rdf" -> root.getElementsByTag("item").mapNotNull { parseRssItem(it, source, discoveredAt) }
            "feed" -> root.getElementsByTag("entry").mapNotNull { parseAtomEntry(it, source, discoveredAt) }
            else -> throw FeedParseException(
                source.url,
                IllegalArgumentException("Unsupported feed root: ${root.tagName()}"),
            )
        }

        return ParsedFeed(
            items = entries.map { it.item },
            firstStableKey = entries.firstOrNull()?.stableKey,
        )
    }

    private fun parseRssItem(element: Element, source: Source, discoveredAt: Instant): ParsedEntry? {
        val rawLink = directText(element, "link")
            ?: directText(element, "guid")?.takeIf(::isHttpUrl)
            ?: return null
        val canonical = UrlCanonicalizer.canonicalize(rawLink, source.url) ?: return null
        val stableKey = directText(element, "guid") ?: canonical

        return ParsedEntry(
            stableKey = stableKey,
            item = DiscoveredItem(
                id = stableId(source, canonical),
                sourceId = source.id,
                url = canonical,
                canonicalUrl = canonical,
                title = directText(element, "title"),
                publishedAt = parseInstant(directText(element, "pubDate", "dc:date", "date")),
                discoveredAt = discoveredAt,
            ),
        )
    }

    private fun parseAtomEntry(element: Element, source: Source, discoveredAt: Instant): ParsedEntry? {
        val linkElement = element.children()
            .filter { it.tagName().equals("link", ignoreCase = true) }
            .firstOrNull { it.attr("rel").isBlank() || it.attr("rel").equals("alternate", ignoreCase = true) }
            ?: element.children().firstOrNull { it.tagName().equals("link", ignoreCase = true) }

        val rawLink = linkElement?.attr("href")?.takeIf { it.isNotBlank() } ?: return null
        val canonical = UrlCanonicalizer.canonicalize(rawLink, source.url) ?: return null
        val stableKey = directText(element, "id") ?: canonical

        return ParsedEntry(
            stableKey = stableKey,
            item = DiscoveredItem(
                id = stableId(source, canonical),
                sourceId = source.id,
                url = canonical,
                canonicalUrl = canonical,
                title = directText(element, "title"),
                publishedAt = parseInstant(directText(element, "published", "updated")),
                discoveredAt = discoveredAt,
            ),
        )
    }

    private fun stableId(source: Source, canonicalUrl: String): DiscoveredItemId {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${source.id.value}|$canonicalUrl".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return DiscoveredItemId(digest)
    }

    private fun directText(element: Element, vararg names: String): String? = names.asSequence()
        .mapNotNull { name ->
            element.children()
                .firstOrNull { it.tagName().equals(name, ignoreCase = true) }
                ?.text()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }
        .firstOrNull()

    private fun parseInstant(value: String?): Instant? {
        if (value.isNullOrBlank()) return null
        return runCatching { Instant.parse(value) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
            ?: runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull()
    }

    private fun isHttpUrl(value: String): Boolean =
        value.startsWith("http://", ignoreCase = true) || value.startsWith("https://", ignoreCase = true)

    private data class ParsedEntry(
        val stableKey: String,
        val item: DiscoveredItem,
    )

    private data class ParsedFeed(
        val items: List<DiscoveredItem>,
        val firstStableKey: String?,
    )
}

class FeedHttpException(
    url: String,
    val statusCode: Int,
) : RuntimeException("Feed request failed with HTTP $statusCode: $url")

class FeedParseException(
    url: String,
    cause: Throwable,
) : RuntimeException("Unable to parse feed: $url", cause)
