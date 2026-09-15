package io.github.go0dboy.articlenavigator.pipeline

fun interface ProcessingStage<I : Any, O : Any> {
    suspend fun process(input: I): StageResult<O>
}

sealed interface StageResult<out T : Any> {
    data class Success<T : Any>(val value: T) : StageResult<T>
    data class Rejected(val reason: String) : StageResult<Nothing>
    data class Failed(val error: Throwable) : StageResult<Nothing>
}
