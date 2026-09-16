package io.github.go0dboy.articlenavigator.pipeline

import java.nio.charset.Charset
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentExtractionTest {
    private val extractor = DefaultContentExtractor()

    @Test
    fun `html extraction prefers article and removes navigation and scripts`() {
        val html = """
            <html>
              <head><title>Fallback title</title><meta property="og:title" content="Useful title"></head>
              <body>
                <nav>Navigation noise</nav>
                <article>
                  <h1>Useful title</h1>
                  <p>First paragraph.</p>
                  <script>secretNoise()</script>
                  <p>Second   paragraph.</p>
                </article>
                <footer>Footer noise</footer>
              </body>
            </html>
        """.trimIndent().toByteArray()

        val result = extractor.extract(html, "text/html; charset=utf-8", "https://example.test/a")

        assertEquals("Useful title", result.title)
        assertTrue(result.normalizedText.contains("First paragraph."))
        assertTrue(result.normalizedText.contains("Second paragraph."))
        assertFalse(result.normalizedText.contains("Navigation noise"))
        assertFalse(result.normalizedText.contains("secretNoise"))
        assertFalse(result.normalizedText.contains("Footer noise"))
    }

    @Test
    fun `plain text is normalized without html parser`() {
        val result = extractor.extract(
            "  alpha   beta\r\n\r\n gamma  ".toByteArray(),
            "text/plain; charset=utf-8",
            "https://example.test/plain",
        )
        assertEquals("alpha beta\n\ngamma", result.normalizedText)
    }

    @Test
    fun `utf8 plain text preserves cyrillic exactly`() {
        val result = extractor.extract(
            fixtureBytes("utf8-cyrillic.txt"),
            "text/plain; charset=UTF-8",
            "https://example.test/utf8",
        )

        assertEquals("Привет из UTF-8.\nВторая строка.", result.normalizedText)
    }

    @Test
    fun `plain text honors windows 1251 http charset`() {
        val result = extractor.extract(
            fixtureBase64("plain-windows-1251.b64"),
            "text/plain; charset=windows-1251",
            "https://example.test/plain-1251",
        )

        assertEquals("Привет, мир!\nВторая строка.", result.normalizedText)
    }

    @Test
    fun `html honors windows 1251 charset from http header`() {
        val result = extractor.extract(
            fixtureBase64("html-http-windows-1251.b64"),
            "text/html; charset=windows-1251",
            "https://example.test/html-http-1251",
        )

        assertEquals("Заголовок", result.title)
        assertEquals("Текст статьи.", result.normalizedText)
    }

    @Test
    fun `html detects windows 1251 declared only in document metadata`() {
        val result = extractor.extract(
            fixtureBase64("html-meta-windows-1251.b64"),
            "text/html",
            "https://example.test/html-meta-1251",
        )

        assertEquals("Внутренняя кодировка", result.title)
        assertEquals("Текст из meta.", result.normalizedText)
    }

    @Test
    fun `http charset overrides conflicting document metadata`() {
        val body = """
            <html>
              <head><meta charset="utf-8"><title>HTTP важнее</title></head>
              <body><article>Точный текст CP1251.</article></body>
            </html>
        """.trimIndent().toByteArray(Charset.forName("windows-1251"))

        val result = extractor.extract(
            body,
            "text/html; charset=windows-1251",
            "https://example.test/http-over-meta",
        )

        assertEquals("HTTP важнее", result.title)
        assertEquals("Точный текст CP1251.", result.normalizedText)
    }

    @Test
    fun `bom overrides conflicting http charset`() {
        val utf8 = "Привет с BOM".toByteArray(Charsets.UTF_8)
        val body = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + utf8

        val result = extractor.extract(
            body,
            "text/plain; charset=windows-1251",
            "https://example.test/bom",
        )

        assertEquals("Привет с BOM", result.normalizedText)
    }

    @Test
    fun `unknown declared charset is rejected instead of silently decoding`() {
        assertThrows(IllegalArgumentException::class.java) {
            extractor.extract(
                "hello".toByteArray(),
                "text/plain; charset=x-article-navigator-unknown",
                "https://example.test/unknown-charset",
            )
        }
    }

    @Test
    fun `malformed bytes for declared charset are rejected instead of replacement text`() {
        assertThrows(IllegalArgumentException::class.java) {
            extractor.extract(
                byteArrayOf(0xD0.toByte(), 0x28),
                "text/plain; charset=utf-8",
                "https://example.test/malformed",
            )
        }
    }

    @Test(expected = UnsupportedContentTypeException::class)
    fun `binary media is rejected`() {
        extractor.extract(byteArrayOf(1, 2, 3), "image/png", "https://example.test/image")
    }

    private fun fixtureBytes(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/fixtures/encoding/$name")) {
            "Missing fixture $name"
        }.use { it.readBytes() }

    private fun fixtureBase64(name: String): ByteArray = Base64.getDecoder().decode(
        fixtureBytes(name).toString(Charsets.UTF_8).trim(),
    )
}
