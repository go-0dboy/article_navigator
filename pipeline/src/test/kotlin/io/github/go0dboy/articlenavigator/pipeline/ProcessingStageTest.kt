package io.github.go0dboy.articlenavigator.pipeline

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessingStageTest {

    @Test
    fun `stage returns success value unchanged`() {
        val stage = ProcessingStage<String, String> { input ->
            StageResult.Success(input.uppercase())
        }

        val result = runSuspend { stage.process("article") }

        assertEquals(StageResult.Success("ARTICLE"), result)
    }

    @Test
    fun `rejection preserves reason`() {
        val stage = ProcessingStage<String, String> {
            StageResult.Rejected("duplicate")
        }

        val result = runSuspend { stage.process("ignored") }

        assertTrue(result is StageResult.Rejected)
        assertEquals("duplicate", (result as StageResult.Rejected).reason)
    }

    @Test
    fun `failure preserves original exception`() {
        val expected = IllegalStateException("broken parser")
        val stage = ProcessingStage<String, String> {
            StageResult.Failed(expected)
        }

        val result = runSuspend { stage.process("ignored") }

        assertTrue(result is StageResult.Failed)
        assertSame(expected, (result as StageResult.Failed).error)
    }

    private fun <T> runSuspend(block: suspend () -> T): T {
        var outcome: Result<T>? = null
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) {
                outcome = result
            }
        })
        return outcome!!.getOrThrow()
    }
}
