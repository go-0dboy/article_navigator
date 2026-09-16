package io.github.go0dboy.articlenavigator.pipeline

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.IllegalCharsetNameException
import java.nio.charset.StandardCharsets
import java.nio.charset.UnsupportedCharsetException
import org.jsoup.Jsoup

/** Content after transport-specific bytes have been reduced to durable readable text. */
data class ExtractedContent(
    val title: String?,
    val normalizedText: String,
)

fun interface ContentExtractor {
    fun extract(body: ByteArray, contentType: String?, baseUri: String): ExtractedContent
}

/**
 * Decoding policy, in descending precedence:
 * 1. a recognized BOM;
 * 2. HTTP Content-Type charset;
 * 3. HTML/XML in-document charset metadata;
 * 4. UTF-8.
 *
 * This mirrors jsoup's documented BOM/meta/UTF-8 behavior while adding the transport charset that
 * is already persisted with RawContent. All selected charsets are decoded strictly: malformed or
 * unmappable bytes are rejected instead of being replaced with U+FFFD and saved as valid content.
 */
class DefaultContentExtractor : ContentExtractor {
    override fun extract(body: ByteArray, contentType: String?, baseUri: String): ExtractedContent {
        require(body.isNotEmpty()) { "Fetched body is empty" }
        val mediaType = contentType?.substringBefore(';')?.trim()?.lowercase()
        return when {
            mediaType == "text/plain" -> ExtractedContent(
                title = null,
                normalizedText = normalizeText(decodeText(body, contentType)),
            )
            mediaType == null || mediaType.contains("html") || mediaType.contains("xml") ->
                extractMarkup(body, contentType, baseUri)
            else -> throw UnsupportedContentTypeException(contentType)
        }.also {
            require(it.normalizedText.isNotBlank()) { "Extracted content is blank" }
        }
    }

    private fun extractMarkup(body: ByteArray, contentType: String?, baseUri: String): ExtractedContent {
        val decoded = decodeMarkup(body, contentType)
        val document = Jsoup.parse(decoded, baseUri)
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

    private fun decodeText(body: ByteArray, contentType: String?): String {
        val bom = detectBom(body)
        if (bom != null) return decodeStrict(body, bom.charset, bom.length)

        val charset = charsetFromContentType(contentType) ?: StandardCharsets.UTF_8
        return decodeStrict(body, charset, 0)
    }

    private fun decodeMarkup(body: ByteArray, contentType: String?): String {
        val bom = detectBom(body)
        if (bom != null) return decodeStrict(body, bom.charset, bom.length)

        val httpCharset = charsetFromContentType(contentType)
        if (httpCharset != null) return decodeStrict(body, httpCharset, 0)

        val metadataCharset = charsetFromMarkupMetadata(body)
        return decodeStrict(body, metadataCharset ?: StandardCharsets.UTF_8, 0)
    }

    private fun charsetFromContentType(contentType: String?): Charset? {
        if (contentType == null) return null
        val raw = contentType
            .split(';')
            .drop(1)
            .firstNotNullOfOrNull { parameter ->
                val parts = parameter.trim().split('=', limit = 2)
                if (parts.size == 2 && parts[0].trim().equals("charset", ignoreCase = true)) {
                    parts[1].trim().trim('"', '\'').takeIf { it.isNotEmpty() }
                } else {
                    null
                }
            }
            ?: return null
        return resolveCharset(raw, "HTTP Content-Type")
    }

    private fun charsetFromMarkupMetadata(body: ByteArray): Charset? {
        // Charset declarations are ASCII syntax. ISO-8859-1 preserves the first bytes one-to-one,
        // allowing detection before the document charset itself is known.
        val probe = body.copyOfRange(0, minOf(body.size, METADATA_PROBE_BYTES))
            .toString(StandardCharsets.ISO_8859_1)
        val raw = META_CHARSET.find(probe)?.groupValues?.get(1)
            ?: XML_ENCODING.find(probe)?.groupValues?.get(1)
            ?: return null
        return resolveCharset(raw, "document metadata")
    }

    private fun resolveCharset(raw: String, source: String): Charset = try {
        Charset.forName(raw)
    } catch (error: IllegalCharsetNameException) {
        throw ContentDecodingException("Invalid charset '$raw' declared by $source", error)
    } catch (error: UnsupportedCharsetException) {
        throw ContentDecodingException("Unsupported charset '$raw' declared by $source", error)
    }

    private fun detectBom(body: ByteArray): Bom? = when {
        body.startsWith(0x00, 0x00, 0xFE, 0xFF) -> Bom(resolveCharset("UTF-32BE", "BOM"), 4)
        body.startsWith(0xFF, 0xFE, 0x00, 0x00) -> Bom(resolveCharset("UTF-32LE", "BOM"), 4)
        body.startsWith(0xEF, 0xBB, 0xBF) -> Bom(StandardCharsets.UTF_8, 3)
        body.startsWith(0xFE, 0xFF) -> Bom(StandardCharsets.UTF_16BE, 2)
        body.startsWith(0xFF, 0xFE) -> Bom(StandardCharsets.UTF_16LE, 2)
        else -> null
    }

    private fun decodeStrict(body: ByteArray, charset: Charset, offset: Int): String = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(body, offset, body.size - offset))
            .toString()
    } catch (error: CharacterCodingException) {
        throw ContentDecodingException("Body is not valid ${charset.name()} text", error)
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

    private data class Bom(val charset: Charset, val length: Int)

    private companion object {
        const val METADATA_PROBE_BYTES = 8192
        val META_CHARSET = Regex(
            """(?is)<meta\\b[^>]*\\bcharset\\s*=\\s*[\"']?\\s*([A-Za-z0-9._:+-]+)""",
        )
        val XML_ENCODING = Regex(
            """(?is)<\\?xml\\b[^>]*\\bencoding\\s*=\\s*[\"']\\s*([A-Za-z0-9._:+-]+)""",
        )
    }
}

private fun ByteArray.startsWith(vararg prefix: Int): Boolean =
    size >= prefix.size && prefix.indices.all { index -> (this[index].toInt() and 0xFF) == prefix[index] }

class ContentDecodingException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

class UnsupportedContentTypeException(contentType: String?) :
    IllegalArgumentException("Unsupported content type: ${contentType ?: "unknown"}")
