package io.github.go0dboy.articlenavigator.collector.api

import java.time.Duration
import kotlin.math.pow

/** Policy data is kept separate from Android/WorkManager so the same collector can run on a server. */
data class RetryPolicy(
    val maxAttempts: Int = 4,
    val initialDelay: Duration = Duration.ofSeconds(5),
    val maxDelay: Duration = Duration.ofMinutes(5),
    val multiplier: Double = 2.0,
) {
    init {
        require(maxAttempts >= 1)
        require(!initialDelay.isNegative)
        require(!maxDelay.isNegative)
        require(maxDelay >= initialDelay)
        require(multiplier >= 1.0)
    }

    /** Delay before [attemptNumber], where the first attempt has no delay. */
    fun delayBeforeAttempt(attemptNumber: Int): Duration {
        require(attemptNumber in 1..maxAttempts)
        if (attemptNumber == 1) return Duration.ZERO

        val factor = multiplier.pow((attemptNumber - 2).toDouble())
        val candidateMillis = (initialDelay.toMillis() * factor).toLong()
        return Duration.ofMillis(candidateMillis.coerceAtMost(maxDelay.toMillis()))
    }
}

/**
 * Source-local failure metadata understood by the scheduler without depending on a concrete adapter.
 * [retryAfter] is a server-requested minimum delay (for example HTTP Retry-After).
 */
open class SourceCollectionException(
    message: String,
    cause: Throwable? = null,
    val retryable: Boolean = true,
    val retryAfter: Duration? = null,
) : Exception(message, cause)

data class RateLimitPolicy(
    val minimumInterval: Duration = Duration.ofSeconds(1),
) {
    init {
        require(!minimumInterval.isNegative)
    }
}
