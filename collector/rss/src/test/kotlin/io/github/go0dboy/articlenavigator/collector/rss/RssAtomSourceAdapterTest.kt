package io.github.go0dboy.articlenavigator.collector.rss

import io.github.go0dboy.articlenavigator.core.model.PollPolicy
import io.github.go0dboy.articlenavigator.core.model.Source
import io.github.go0dboy.articlenavigator.core.model.SourceCursor
import io.github.go0dboy.articlenavigator.core.model.SourceId
import io.github.go0dboy.articlenavigator.core.model.SourceType
import io.github.go0dboy.articlenavigator.core.network.HttpRequest
import io.github.go0dboy.articlenavigator.core.network.HttpResponse
import io.github.go0dboy.articlenavigator.core.network.HttpTransport
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RssAtomSourceAdapterTest {
    private val now = Instant.parse("2026-09-15T10:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun discoversRssAndUsesConditionalHeaders() = runTest {
        val transport = FakeTransport(
            HttpResponse(
                statusCode = 200,
                headers = mapOf("ETag" to listOf("\"v2\""), "Last-Modified" to listOf("Tue, 15 Sep 2026 09:00:00 GMT")),
                contentType = "application/rss+xml",
                body = RSS_FIXTURE.toByteArray(),
                finalUrl = "https://example.com/feed.xml",
            ),
        )
        val source = source(SourceType.RSS, "https://Example.com/feed.xml")
        val cursor = SourceCursor(source.id, etag = "\"v1\"", lastModified = "Mon, 14 Sep 2026 09:00:00 GMT")
        val adapter = RssAtomSourceAdapter(transport, clock)

        val result = adapter.discover(source, cursor)

        assertEquals(2, result.items.size)
        assertEquals("First item", result.items[0].title)
        assertEquals("https://example.com/articles/1?a=1", result.items[0].canonicalUrl)
        assertEquals(Instant.parse("2026-09-15T08:30:00Z"), result.items[0].publishedAt)
        assertEquals("\"v2\"", result.nextCursor.etag)
        assertEquals(now, result.nextCursor.lastCheckedAt)
        assertEquals("guid-1", result.nextCursor.lastGuid)
        assertEquals("\"v1\"", transport.requests.single().headers["If-None-Match"])
        assertEquals("Mon, 14 Sep 2026 09:00:00 GMT", transport.requests.single().headers["If-Modified-Since"])
    }

    @Test
    fun repeatedDiscoveryProducesStableIdsForDatabaseIdempotency() = runTest {
        val source = source(SourceType.RSS, "https://example.com/feed.xml")
        val response = HttpResponse(
            statusCode = 200,
            headers = emptyMap(),
            contentType = "application/rss+xml",
            body = RSS_FIXTURE.toByteArray(),
            finalUrl = "https://example.com/feed.xml",
        )
        val transport = FakeTransport(response, response)
        val adapter = RssAtomSourceAdapter(transport, clock)

        val first = adapter.discover(source, null)
        val second = adapter.discover(source, first.nextCursor)

        assertEquals(first.items.map { it.id }, second.items.map { it.id })
        assertEquals(first.items.map { it.canonicalUrl }, second.items.map { it.canonicalUrl })
    }

    @Test
    fun discoversAtomWithRelativeAlternateLink() = runTest {
        val transport = FakeTransport(
            HttpResponse(
                statusCode = 200,
                headers = emptyMap(),
                contentType = "application/atom+xml",
                body = ATOM_FIXTURE.toByteArray(),
                finalUrl = "https://example.com/feeds/main.xml",
            ),
        )
        val source = source(SourceType.ATOM, "https://example.com/feeds/main.xml")

        val result = RssAtomSourceAdapter(transport, clock).discover(source, null)

        assertEquals(1, result.items.size)
        assertEquals("Atom item", result.items.single().title)
        assertEquals("https://example.com/articles/atom-1#comments", result.items.single().url)
        assertEquals("https://example.com/articles/atom-1", result.items.single().canonicalUrl)
        assertEquals(Instant.parse("2026-09-15T09:15:00Z"), result.items.single().publishedAt)
        assertEquals("urn:uuid:atom-1", result.nextCursor.lastGuid)
    }

    @Test
    fun notModifiedReturnsNoItemsAndPreservesCursor() = runTest {
        val transport = FakeTransport(
            HttpResponse(
                statusCode = 304,
                headers = emptyMap(),
                contentType = null,
                body = ByteArray(0),
                finalUrl = "https://example.com/feed.xml",
            ),
        )
        val source = source(SourceType.RSS, "https://example.com/feed.xml")
        val cursor = SourceCursor(source.id, etag = "\"v1\"", lastGuid = "old-guid")

        val result = RssAtomSourceAdapter(transport, clock).discover(source, cursor)

        assertTrue(result.items.isEmpty())
        assertEquals("\"v1\"", result.nextCursor.etag)
        assertEquals("old-guid", result.nextCursor.lastGuid)
        assertEquals(now, result.nextCursor.lastCheckedAt)
    }

    @Test(expected = FeedParseException::class)
    fun malformedOrUnsupportedXmlFailsExplicitly() = runTest {
        val transport = FakeTransport(
            HttpResponse(
                statusCode = 200,
                headers = emptyMap(),
                contentType = "application/xml",
                body = "<html><body>not a feed</body></html>".toByteArray(),
                finalUrl = "https://example.com/feed.xml",
            ),
        )
        RssAtomSourceAdapter(transport, clock).discover(
            source(SourceType.RSS, "https://example.com/feed.xml"),
            null,
        )
    }

    private fun source(type: SourceType, url: String): Source = Source(
        id = SourceId("source-1"),
        name = "Test feed",
        type = type,
        url = url,
        enabled = true,
        pollPolicy = PollPolicy(Duration.ofHours(1)),
        adapterType = "rss-atom",
        createdAt = now,
    )

    private class FakeTransport(vararg responses: HttpResponse) : HttpTransport {
        private val queue = ArrayDeque(responses.toList())
        val requests = mutableListOf<HttpRequest>()

        override suspend fun execute(request: HttpRequest): HttpResponse {
            requests += request
            return queue.removeFirst()
        }
    }

    private companion object {
        val RSS_FIXTURE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0">
              <channel>
                <title>Example</title>
                <item>
                  <guid>guid-1</guid>
                  <title>First item</title>
                  <link>HTTPS://Example.com:443/articles/./1?a=1#fragment</link>
                  <pubDate>Tue, 15 Sep 2026 08:30:00 GMT</pubDate>
                </item>
                <item>
                  <guid>guid-2</guid>
                  <title>Second item</title>
                  <link>https://example.com/articles/2</link>
                </item>
              </channel>
            </rss>
        """.trimIndent()

        val ATOM_FIXTURE = """
            <?xml version="1.0" encoding="utf-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Example Atom</title>
              <entry>
                <id>urn:uuid:atom-1</id>
                <title>Atom item</title>
                <link rel="alternate" href="../articles/atom-1#comments" />
                <updated>2026-09-15T09:15:00Z</updated>
              </entry>
            </feed>
        """.trimIndent()
    }
}
