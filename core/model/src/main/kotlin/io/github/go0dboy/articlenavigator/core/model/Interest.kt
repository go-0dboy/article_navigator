package io.github.go0dboy.articlenavigator.core.model

import java.time.Instant

data class Interest(
    val id: InterestId,
    val name: String,
    val description: String,
    val positiveExamples: List<String> = emptyList(),
    val negativeExamples: List<String> = emptyList(),
    val enabled: Boolean = true,
    val createdAt: Instant,
    val updatedAt: Instant,
)
