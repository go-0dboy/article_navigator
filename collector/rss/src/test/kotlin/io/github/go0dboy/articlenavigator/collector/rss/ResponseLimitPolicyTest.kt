package io.github.go0dboy.articlenavigator.collector.rss

import io.github.go0dboy.articlenavigator.core.model.DiscoveredItem
import io.github.go0dboy.articlenavigator.core.model.DiscoveredItemId
import io.github.go0dboy.articlenavigator.core.model.PollPolicy
import io.github.go0dboy.articlenavigator.core.model.Source
import io.github.go0dboy.articlenavigator.core.model.SourceId
import io.github.go0dboy.articlenavigator.core.model.SourceType
import io.github.go0dboy.articlenavigator.core.network.HttpRequest
import io.github.go0dboy.articlenavigator.core.network.HttpResponse
import io.github.go0dboy.articlenavigator.core.network.HttpTransport
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseLimitPolicyTest {
    @Test
    fun feedAndArticleUseDistinctBoundedResponseLimits() = runTest {
        val transport = CapturingTransport()
        val adapter = RssAtomSourceAdapter(transport)
        val source = Source(
            id = SourceId("source"),
            name = "Source",
            type = SourceType.RSS,
            url = "https://example.test/feed.xml",
            enabled = true,
            pollPolicy = PollPolicy(Duration.ofHours(1)),
            adapterType = RssAtomSourceAdapter.ADAPTER_TYPE,
            createdAt = Instant.EPOCH,
        )

        adapter.discover(source, null)
        adapter.fetch(
            DiscoveredItem(
                id = DiscoveredItemId("article"),
                sourceId = source.id,
                url = "https://example.test/article",
                discoveredAt = Instant.EPOCH,
            ),
        )

        assertEquals(RssAtomSourceAdapter.MAX_FEED_BYTES, transport.requests[0].maxResponseBytes)
        assertEquals(RssAtomSourceAdapter.MAX_ARTICLE_BYTES, transport.requests[1].maxResponseBytes)
        assertTrue(RssAtomSourceAdapter.MAX_FEED_BYTES < RssAtomSourceAdapter.MAX_ARTICLE_BYTES)
        assertTrue(RssAtomSourceAdapter.MAX_ARTICLE_BYTES <= 5L * 1024L * 1024L)
    }

    private class CapturingTransport : HttpTransport {
        val requests = mutableListOf<HttpRequest>()

        override suspend fun execute(request: HttpRequest): HttpResponse {
            requests += request
            return if (requests.size == 1) {
                HttpResponse(
                    statusCode = 304,
                    headers = emptyMap(),
                    contentType = null,
                    body = ByteArray(0),
                    finalUrl = request.url,
                )
            } else {
                HttpResponse(
                    statusCode = 200,
                    headers = emptyMap(),
                    contentType = "text/html",
                    body = "<article>body</article>".toByteArray(),
                    finalUrl = request.url,
                )
            }
        }
    }
}
