package io.github.go0dboy.articlenavigator.core.network

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

class OkHttpTransportTest {
    @Test
    fun sendsHeadersAndReturnsResponseMetadataAndBody() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .addHeader("Content-Type", "application/rss+xml; charset=utf-8")
                    .addHeader("ETag", "\"feed-v2\"")
                    .body("feed-body")
                    .build(),
            )

            val response = OkHttpTransport().execute(
                HttpRequest(
                    url = server.url("/feed.xml").toString(),
                    headers = mapOf("If-None-Match" to "\"feed-v1\""),
                ),
            )

            val request = server.takeRequest()
            assertEquals("\"feed-v1\"", request.headers["If-None-Match"])
            assertEquals(200, response.statusCode)
            assertEquals("\"feed-v2\"", response.header("etag"))
            assertEquals("feed-body", response.body.toString(Charsets.UTF_8))
        } finally {
            server.close()
        }
    }
}
