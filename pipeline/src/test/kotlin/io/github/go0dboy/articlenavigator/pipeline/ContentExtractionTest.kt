package io.github.go0dboy.articlenavigator.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test(expected = UnsupportedContentTypeException::class)
    fun `binary media is rejected`() {
        extractor.extract(byteArrayOf(1, 2, 3), "image/png", "https://example.test/image")
    }
}
