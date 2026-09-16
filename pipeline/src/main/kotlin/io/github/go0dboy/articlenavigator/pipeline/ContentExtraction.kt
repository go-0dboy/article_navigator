package io.github.go0dboy.articlenavigator.pipeline

import io.github.go0dboy.articlenavigator.core.model.ContentFormats
import io.github.go0dboy.articlenavigator.core.model.ContentParserVersions
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.IllegalCharsetNameException
import java.nio.charset.StandardCharsets
import java.nio.charset.UnsupportedCharsetException
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.safety.Safelist

/** Content after transport-specific bytes have been reduced to durable readable representations. */
data class ExtractedContent(
    val title: String?,
    val normalizedText: String,
    val structuredContentFormat: String?,
    val structuredContent: String?,
)

/** Every extractor must declare the durable parser identity persisted with the content it produces. */
interface ContentExtractor {
    val parserVersion: String

    fun extract(body: ByteArray, contentType: String?, baseUri: String): ExtractedContent
}

/**
 * Decoding policy, in descending precedence:
 * 1. a recognized BOM;
 * 2. HTTP Content-Type charset;
 * 3. HTML/XML in-document charset metadata;
 * 4. UTF-8.
 *
 * The selected charset is decoded strictly. Unsupported or malformed input is rejected instead of
 * silently storing replacement characters. Markup is additionally reduced to the passive
 * `safe-html-v1` subset from ADR 0007; source scripts/forms/embedded active content are never
 * persisted as executable reading content.
 */
class DefaultContentExtractor : ContentExtractor {
    override val parserVersion: String = ContentParserVersions.DEFAULT_EXTRACTOR_V3

    override fun extract(body: ByteArray, contentType: String?, baseUri: String): ExtractedContent {
        require(body.isNotEmpty()) { "Fetched body is empty" }
        val mediaType = contentType?.substringBefore(';')?.trim()?.lowercase()
        return when {
            mediaType == "text/plain" -> extractPlainText(body, contentType)
            mediaType == null || mediaType.contains("html") || mediaType.contains("xml") ->
                extractMarkup(body, contentType, baseUri)
            else -> throw UnsupportedContentTypeException(contentType)
        }.also {
            require(it.normalizedText.isNotBlank()) { "Extracted content is blank" }
            require(
                (it.structuredContentFormat == null) == (it.structuredContent == null),
            ) { "Structured content format and payload must either both exist or both be absent" }
        }
    }

    private fun extractPlainText(body: ByteArray, contentType: String?): ExtractedContent {
        val normalized = normalizeText(decodeText(body, contentType))
        return ExtractedContent(
            title = null,
            normalizedText = normalized,
            structuredContentFormat = ContentFormats.SAFE_HTML_V1,
            structuredContent = plainTextToSafeHtml(normalized),
        )
    }

    private fun extractMarkup(body: ByteArray, contentType: String?, baseUri: String): ExtractedContent {
        val decoded = decodeMarkup(body, contentType)
        val document = Jsoup.parse(decoded, baseUri)
        document.outputSettings().prettyPrint(false)
        document.select("script, style, noscript, nav, header, footer, aside, form, input, button, svg, canvas, iframe, object, embed").remove()

        val root = document.selectFirst("article")
            ?: document.selectFirst("main")
            ?: document.body()

        val title = document.selectFirst("meta[property=og:title]")
            ?.attr("content")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: document.title().trim().takeIf { it.isNotEmpty() }
            ?: root.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotEmpty() }

        // Plain text is derived before image placeholders are inserted so search text is not
        // polluted with UI fallback labels that were not present in the article itself.
        val normalizedText = normalizeText(root.wholeText())

        // Prevent source-authored inert metadata from masquerading as extractor-owned image data.
        root.select("[data-an-image-url]").removeAttr("data-an-image-url")
        root.select("a[href]").forEach { link ->
            val absolute = httpUrlOrNull(link.absUrl("href"))
            if (absolute == null) link.removeAttr("href") else link.attr("href", absolute)
        }
        root.select("img").forEach { image ->
            val sourceUrl = httpUrlOrNull(image.absUrl("src"))
            val label = image.attr("alt").trim()
                .ifBlank { image.attr("title").trim() }
                .ifBlank { "Изображение недоступно офлайн" }
            image.tagName("figure")
            image.empty()
            if (sourceUrl != null) image.attr("data-an-image-url", sourceUrl)
            image.appendElement("figcaption").text(label)
        }

        val outputSettings = Document.OutputSettings().prettyPrint(false)
        val safeHtml = Jsoup.clean(root.html(), baseUri, SAFE_HTML, outputSettings)
            .trim()
            .takeIf { it.isNotEmpty() }

        return ExtractedContent(
            title = title,
            normalizedText = normalizedText,
            structuredContentFormat = safeHtml?.let { ContentFormats.SAFE_HTML_V1 },
            structuredContent = safeHtml,
        )
    }

    private fun plainTextToSafeHtml(value: String): String {
        val document = Jsoup.parseBodyFragment("")
        document.outputSettings().prettyPrint(false)
        val body = document.body()
        value.split(Regex("\\n{2,}")).forEach { paragraph ->
            val element = body.appendElement("p")
            paragraph.split('\n').forEachIndexed { index, line ->
                if (index > 0) element.appendElement("br")
                element.appendText(line)
            }
        }
        return body.html().trim()
    }

    private fun httpUrlOrNull(value: String): String? {
        if (value.isBlank()) return null
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (uri.host.isNullOrBlank()) return null
        return uri.toASCIIString()
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

        val SAFE_HTML: Safelist = Safelist.none()
            .addTags(
                "h1", "h2", "h3", "h4", "h5", "h6",
                "p", "br", "hr",
                "ul", "ol", "li",
                "blockquote",
                "strong", "b", "em", "i",
                "a", "code", "pre",
                "table", "thead", "tbody", "tfoot", "tr", "th", "td", "caption",
                "figure", "figcaption",
            )
            .addAttributes("a", "href", "title")
            .addProtocols("a", "href", "http", "https")
            .addAttributes("th", "colspan", "rowspan")
            .addAttributes("td", "colspan", "rowspan")
            .addAttributes("figure", "data-an-image-url")

        val META_CHARSET = Regex(
            """(?is)<meta\b[^>]*\bcharset\s*=\s*["']?\s*([A-Za-z0-9._:+-]+)""",
        )
        val XML_ENCODING = Regex(
            """(?is)<\?xml\b[^>]*\bencoding\s*=\s*["']\s*([A-Za-z0-9._:+-]+)""",
        )
    }
}

private fun ByteArray.startsWith(vararg prefix: Int): Boolean =
    size >= prefix.size && prefix.indices.all { index -> (this[index].toInt() and 0xFF) == prefix[index] }

class ContentDecodingException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

class UnsupportedContentTypeException(contentType: String?) :
    IllegalArgumentException("Unsupported content type: ${contentType ?: "unknown"}")
