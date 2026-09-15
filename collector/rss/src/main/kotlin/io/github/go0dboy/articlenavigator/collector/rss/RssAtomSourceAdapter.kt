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
import io.github.go0dboy.articlenavigator.core.network.HttpTransport
import java.io.ByteArrayInputStream
import java.net.URI
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
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
    override val adapterType: String = ADAPTER_TYPE
    override val supportedTypes: Set<SourceType> = setOf(SourceType.RSS, SourceType.ATOM)

    override suspend fun discover(source: Source, cursor: SourceCursor?): DiscoveryResult {
        require(supports(source)) {
            "Unsupported source adapter/type: ${source.adapterType}/${source.type}"
        }

        val requestHeaders = linkedMapOf(
            "Accept" to "application/rss+xml, application/atom+xml, application/xml, text/xml;q=0.9, */*;q=0.1",
        )
        cursor?.etag?.let { requestHeaders["If-None-Match"] = it }
        cursor?.lastModified?.let { requestHeaders["If-Modified-Since"] = it }

        val response = transport.execute(
            HttpRequest(
                url = source.url,
                headers = requestHeaders,
                maxResponseBytes = MAX_FEED_BYTES,
            ),
        )
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
            throw FeedHttpException(
                url = source.url,
                statusCode = response.statusCode,
                retryAfter = parseRetryAfter(response.header("Retry-After"), now),
            )
        }

        val parsed = parseFeed(response.body, source, now)
        return DiscoveryResult(
            items = parsed.items,
            nextCursor = SourceCursor(
                sourceId = source.id,
                // A fresh 200 response replaces validator state. Missing validators intentionally clear stale ones.
                etag = response.header("ETag"),
                lastModified = response.header("Last-Modified"),
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
                    "Accept" to "text/html, application/xhtml+xml, application/xml;q=0.9, text/plain;q=0.8, */*;q=0.1",
                ),
                maxResponseBytes = MAX_ARTICLE_BYTES,
            ),
        )
        return FetchResult(
            item = item,
            statusCode = response.statusCode,
            contentType = response.contentType,
            body = response.body,
            resolvedUrl = response.finalUrl,
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
        val sourceUrl = absoluteUrl(rawLink, source.url) ?: return null
        val canonical = UrlCanonicalizer.canonicalize(sourceUrl) ?: return null
        val stableKey = directText(element, "guid") ?: canonical

        return ParsedEntry(
            stableKey = stableKey,
            item = DiscoveredItem(
                id = stableId(source, canonical),
                sourceId = source.id,
                url = sourceUrl,
                canonicalUrl = canonical,
                title = directText(element, "title"),
                publishedAt = parseInstant(directText(element, "pubDate", "dc:date", "date")),
                discoveredAt = discoveredAt,
                lastSeenAt = discoveredAt,
            ),
        )
    }

    private fun parseAtomEntry(element: Element, source: Source, discoveredAt: Instant): ParsedEntry? {
        val linkElement = element.children()
            .filter { it.tagName().equals("link", ignoreCase = true) }
            .firstOrNull { it.attr("rel").isBlank() || it.attr("rel").equals("alternate", ignoreCase = true) }
            ?: element.children().firstOrNull { it.tagName().equals("link", ignoreCase = true) }

        val rawLink = linkElement?.attr("href")?.takeIf { it.isNotBlank() } ?: return null
        val sourceUrl = absoluteUrl(rawLink, source.url) ?: return null
        val canonical = UrlCanonicalizer.canonicalize(sourceUrl) ?: return null
        val stableKey = directText(element, "id") ?: canonical

        return ParsedEntry(
            stableKey = stableKey,
            item = DiscoveredItem(
                id = stableId(source, canonical),
                sourceId = source.id,
                url = sourceUrl,
                canonicalUrl = canonical,
                title = directText(element, "title"),
                publishedAt = parseInstant(directText(element, "published", "updated")),
                discoveredAt = discoveredAt,
                lastSeenAt = discoveredAt,
            ),
        )
    }

    private fun stableId(source: Source, canonicalUrl: String): DiscoveredItemId {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${source.id.value}|$canonicalUrl".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return DiscoveredItemId(digest)
    }

    private fun absoluteUrl(value: String, base: String): String? = runCatching {
        val resolved = URI(base).resolve(value.trim())
        resolved.takeIf { it.scheme.equals("http", true) || it.scheme.equals("https", true) }?.toASCIIString()
    }.getOrNull()

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

    private fun parseRetryAfter(value: String?, now: Instant): Duration? {
        if (value.isNullOrBlank()) return null
        value.trim().toLongOrNull()?.let { seconds ->
            if (seconds >= 0) return Duration.ofSeconds(seconds)
        }
        val instant = runCatching {
            ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
        }.getOrNull() ?: return null
        return Duration.between(now, instant).takeUnless { it.isNegative }
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

    companion object {
        const val ADAPTER_TYPE: String = "rss-atom"
        const val MAX_FEED_BYTES: Long = 2L * 1024L * 1024L
        const val MAX_ARTICLE_BYTES: Long = 5L * 1024L * 1024L
    }
}

class FeedHttpException(
    url: String,
    val statusCode: Int,
    val retryAfter: Duration? = null,
) : RuntimeException("Feed request failed with HTTP $statusCode: $url") {
    val isTransient: Boolean = statusCode == 408 || statusCode == 425 || statusCode == 429 || statusCode in 500..599
}

class FeedParseException(
    url: String,
    cause: Throwable,
) : RuntimeException("Unable to parse feed: $url", cause)
