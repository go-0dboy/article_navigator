package io.github.go0dboy.articlenavigator.pipeline

import java.io.ByteArrayInputStream
import org.jsoup.Jsoup

/** Content after transport-specific bytes have been reduced to durable readable text. */
data class ExtractedContent(
    val title: String?,
    val normalizedText: String,
)

fun interface ContentExtractor {
    fun extract(body: ByteArray, contentType: String?, baseUri: String): ExtractedContent
}

class DefaultContentExtractor : ContentExtractor {
    override fun extract(body: ByteArray, contentType: String?, baseUri: String): ExtractedContent {
        require(body.isNotEmpty()) { "Fetched body is empty" }
        val mediaType = contentType?.substringBefore(';')?.trim()?.lowercase()
        return when {
            mediaType == "text/plain" -> ExtractedContent(
                title = null,
                normalizedText = normalizeText(body.toString(Charsets.UTF_8)),
            )
            mediaType == null || mediaType.contains("html") || mediaType.contains("xml") -> extractMarkup(body, baseUri)
            else -> throw UnsupportedContentTypeException(contentType)
        }.also {
            require(it.normalizedText.isNotBlank()) { "Extracted content is blank" }
        }
    }

    private fun extractMarkup(body: ByteArray, baseUri: String): ExtractedContent {
        val document = ByteArrayInputStream(body).use { stream ->
            Jsoup.parse(stream, null, baseUri)
        }
        document.select("script, style, noscript, nav, header, footer, aside, form, svg, canvas, iframe").remove()

        val root = document.selectFirst("article")
            ?: document.selectFirst("main")
            ?: document.body()

        val title = document.selectFirst("meta[property=og:title]")
            ?.attr("content")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: document.title().trim().takeIf { it.isNotEmpty() }
            ?: root.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotEmpty() }

        return ExtractedContent(
            title = title,
            normalizedText = normalizeText(root.wholeText()),
        )
    }

    internal fun normalizeText(value: String): String {
        val normalizedLines = value
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lineSequence()
            .map { line -> line.trim().replace(Regex("[\\t\\u00A0 ]+"), " ") }
            .toList()

        val result = StringBuilder()
        var hasContent = false
        var pendingBlankLine = false

        for (line in normalizedLines) {
            if (line.isBlank()) {
                if (hasContent) pendingBlankLine = true
                continue
            }

            if (hasContent) {
                result.append(if (pendingBlankLine) "\n\n" else "\n")
            }
            result.append(line)
            hasContent = true
            pendingBlankLine = false
        }

        return result.toString()
    }
}

class UnsupportedContentTypeException(contentType: String?) :
    IllegalArgumentException("Unsupported content type: ${contentType ?: "unknown"}")
