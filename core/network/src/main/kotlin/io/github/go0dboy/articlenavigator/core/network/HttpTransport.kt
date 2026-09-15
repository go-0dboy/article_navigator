package io.github.go0dboy.articlenavigator.core.network

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

data class HttpRequest(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
)

data class HttpResponse(
    val statusCode: Int,
    val headers: Map<String, List<String>>,
    val contentType: String?,
    val body: ByteArray,
) {
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.lastOrNull()
}

fun interface HttpTransport {
    suspend fun execute(request: HttpRequest): HttpResponse
}

class OkHttpTransport(
    private val client: OkHttpClient = OkHttpClient(),
) : HttpTransport {
    override suspend fun execute(request: HttpRequest): HttpResponse = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(request.url).get()
        request.headers.forEach { (name, value) -> builder.header(name, value) }

        try {
            client.newCall(builder.build()).execute().use { response ->
                HttpResponse(
                    statusCode = response.code,
                    headers = response.headers.toMultimap(),
                    contentType = response.body.contentType()?.toString(),
                    body = response.body.bytes(),
                )
            }
        } catch (error: IOException) {
            throw HttpTransportException("GET ${request.url} failed", error)
        }
    }
}

class HttpTransportException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)
