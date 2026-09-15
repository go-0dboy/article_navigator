package io.github.go0dboy.articlenavigator.core.network

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.Duration
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

data class HttpRequest(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val maxResponseBytes: Long = DEFAULT_MAX_RESPONSE_BYTES,
) {
    init {
        require(maxResponseBytes > 0)
    }

    companion object {
        const val DEFAULT_MAX_RESPONSE_BYTES: Long = 5L * 1024L * 1024L
    }
}

data class HttpResponse(
    val statusCode: Int,
    val headers: Map<String, List<String>>,
    val contentType: String?,
    val body: ByteArray,
    val finalUrl: String,
) {
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.lastOrNull()
}

fun interface HttpTransport {
    suspend fun execute(request: HttpRequest): HttpResponse
}

class OkHttpTransport(
    private val client: OkHttpClient = defaultClient(),
) : HttpTransport {
    override suspend fun execute(request: HttpRequest): HttpResponse = suspendCancellableCoroutine { continuation ->
        val builder = Request.Builder().url(request.url).get()
        request.headers.forEach { (name, value) -> builder.header(name, value) }
        val call = client.newCall(builder.build())

        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isActive) return
                    continuation.resumeWithException(HttpTransportException("GET ${request.url} failed", e))
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!continuation.isActive) return
                        try {
                            val body = readAtMost(response, request.maxResponseBytes)
                            if (continuation.isActive) {
                                continuation.resume(
                                    HttpResponse(
                                        statusCode = response.code,
                                        headers = response.headers.toMultimap(),
                                        contentType = response.body.contentType()?.toString(),
                                        body = body,
                                        finalUrl = response.request.url.toString(),
                                    ),
                                )
                            }
                        } catch (error: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(error)
                        }
                    }
                }
            },
        )
    }

    private fun readAtMost(response: Response, maxBytes: Long): ByteArray {
        val body = response.body
        val advertised = body.contentLength()
        if (advertised > maxBytes) {
            throw ResponseTooLargeException(maxBytes, advertised)
        }

        val output = ByteArrayOutputStream(minOf(maxBytes, 64L * 1024L).toInt())
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        body.byteStream().use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > maxBytes) throw ResponseTooLargeException(maxBytes, total)
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }

    companion object {
        private fun defaultClient(): OkHttpClient {
            val dispatcher = Dispatcher().apply {
                maxRequests = 8
                maxRequestsPerHost = 2
            }
            return OkHttpClient.Builder()
                .dispatcher(dispatcher)
                .callTimeout(Duration.ofSeconds(30))
                .build()
        }
    }
}

class HttpTransportException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

class ResponseTooLargeException(
    val maxBytes: Long,
    val observedBytes: Long,
) : IOException("HTTP response exceeded $maxBytes bytes (observed $observedBytes)")
