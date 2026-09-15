package io.github.go0dboy.articlenavigator.core.model

import org.junit.Assert.assertNotEquals
import org.junit.Test

class IdsTest {
    @Test
    fun generatedSourceIdsAreUnique() {
        assertNotEquals(SourceId.new(), SourceId.new())
    }
}
