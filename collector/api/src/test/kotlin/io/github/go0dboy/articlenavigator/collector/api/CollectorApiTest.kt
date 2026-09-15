package io.github.go0dboy.articlenavigator.collector.api

import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CollectorApiTest {
    @Test
    fun canonicalizerNormalizesIdentityWithoutDroppingQuery() {
        assertEquals(
            "https://example.com/news/item?a=1&utm_source=x",
            UrlCanonicalizer.canonicalize("HTTPS://Example.COM:443/news/./item?a=1&utm_source=x#section"),
        )
        assertEquals(
            "https://example.com/feed/item",
            UrlCanonicalizer.canonicalize("../item#part", "https://example.com/feed/2026/"),
        )
        assertNull(UrlCanonicalizer.canonicalize("mailto:test@example.com"))
    }

    @Test
    fun retryPolicyUsesExponentialBackoffAndCap() {
        val policy = RetryPolicy(
            maxAttempts = 6,
            initialDelay = Duration.ofSeconds(2),
            maxDelay = Duration.ofSeconds(10),
            multiplier = 2.0,
        )

        assertEquals(Duration.ZERO, policy.delayBeforeAttempt(1))
        assertEquals(Duration.ofSeconds(2), policy.delayBeforeAttempt(2))
        assertEquals(Duration.ofSeconds(4), policy.delayBeforeAttempt(3))
        assertEquals(Duration.ofSeconds(8), policy.delayBeforeAttempt(4))
        assertEquals(Duration.ofSeconds(10), policy.delayBeforeAttempt(5))
        assertEquals(Duration.ofSeconds(10), policy.delayBeforeAttempt(6))
    }
}
