package io.github.go0dboy.articlenavigator.core.network

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OkHttpTransportTest {
    @Test
    fun sendsHeadersAndReturnsResponseMetadataAndBody() = runTest {
        withServer { server ->
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
        }
    }

    @Test
    fun contentLengthAboveLimitFailsBeforeBufferingBody() = runTest {
        withServer { server ->
            server.enqueue(MockResponse.Builder().body("123456").build())

            val error = expectTooLarge {
                OkHttpTransport().execute(HttpRequest(server.url("/large").toString(), maxResponseBytes = 5))
            }

            assertEquals(5L, error.maxBytes)
            assertEquals(6L, error.observedBytes)
        }
    }

    @Test
    fun chunkedBodyWithoutContentLengthIsLimitedWhileStreaming() = runTest {
        withServer { server ->
            server.enqueue(MockResponse.Builder().chunkedBody("123456", 2).build())

            val error = expectTooLarge {
                OkHttpTransport().execute(HttpRequest(server.url("/chunked").toString(), maxResponseBytes = 5))
            }

            assertEquals(5L, error.maxBytes)
            assertTrue(error.observedBytes > 5L)
        }
    }

    @Test
    fun responseExactlyAtLimitSucceeds() = runTest {
        withServer { server ->
            server.enqueue(MockResponse.Builder().body("12345").build())

            val response = OkHttpTransport().execute(
                HttpRequest(server.url("/exact").toString(), maxResponseBytes = 5),
            )

            assertEquals("12345", response.body.toString(Charsets.UTF_8))
        }
    }

    @Test
    fun responseBelowLimitSucceeds() = runTest {
        withServer { server ->
            server.enqueue(MockResponse.Builder().chunkedBody("1234", 2).build())

            val response = OkHttpTransport().execute(
                HttpRequest(server.url("/small").toString(), maxResponseBytes = 5),
            )

            assertEquals("1234", response.body.toString(Charsets.UTF_8))
        }
    }

    @Test
    fun cancellingCoroutineCancelsOkHttpCallAndStopsBodyRead() = runTest {
        withServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .body("x".repeat(256 * 1024))
                    .throttleBody(1024, 100, TimeUnit.MILLISECONDS)
                    .build(),
            )
            val callCancelled = AtomicBoolean(false)
            val bodyCompleted = AtomicBoolean(false)
            val client = OkHttpClient.Builder()
                .eventListener(
                    object : EventListener() {
                        override fun canceled(call: Call) {
                            callCancelled.set(true)
                        }

                        override fun responseBodyEnd(call: Call, byteCount: Long) {
                            bodyCompleted.set(true)
                        }
                    },
                )
                .build()
            val transport = OkHttpTransport(client)

            val request = async {
                transport.execute(HttpRequest(server.url("/slow").toString(), maxResponseBytes = 512 * 1024))
            }
            server.takeRequest()
            delay(150)
            request.cancel()

            try {
                request.await()
                fail("Expected CancellationException")
            } catch (_: CancellationException) {
                // Expected: cancellation is never translated into a normal HTTP/source failure.
            }

            withTimeout(2_000) {
                while (!callCancelled.get()) delay(10)
            }
            assertTrue(callCancelled.get())
            assertTrue(request.isCancelled)
            assertFalse("A cancelled slow response must not be fully consumed", bodyCompleted.get())
        }
    }

    private suspend fun expectTooLarge(block: suspend () -> Unit): ResponseTooLargeException = try {
        block()
        error("Expected ResponseTooLargeException")
    } catch (error: ResponseTooLargeException) {
        error
    }

    private suspend fun withServer(block: suspend (MockWebServer) -> Unit) {
        val server = MockWebServer()
        server.start()
        try {
            block(server)
        } finally {
            server.close()
        }
    }
}
