package com.example.booklibrary.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class PiperFinishDecisionTest {

    @Test
    fun decideAfterPrefetch_ignoresStaleSession() {
        // Arrange
        val state = activeState().copy(currentSessionId = 2L)

        // Act
        val action = PiperFinishDecision.decideAfterPrefetch(state)

        // Assert
        assertEquals(PiperFinishDecision.Action.IGNORE, action)
    }

    @Test
    fun decideAfterPrefetch_ignoresReplacedPlayer() {
        // Arrange
        val state = activeState().copy(playerStillCurrent = false)

        // Act
        val action = PiperFinishDecision.decideAfterPrefetch(state)

        // Assert
        assertEquals(PiperFinishDecision.Action.IGNORE, action)
    }

    @Test
    fun decideAfterPrefetch_ignoresWhenPlaybackNoLongerActive() {
        // Arrange
        val state = activeState().copy(isTtsPlaying = false)

        // Act
        val action = PiperFinishDecision.decideAfterPrefetch(state)

        // Assert
        assertEquals(PiperFinishDecision.Action.IGNORE, action)
    }

    @Test
    fun decideAfterPrefetch_advancesChapter_whenChunkerSaysChapterComplete() {
        // Arrange
        val state = activeState().copy(isChapterComplete = true, resumeIndex = 10, locatorCount = 10)

        // Act
        val action = PiperFinishDecision.decideAfterPrefetch(state)

        // Assert
        assertEquals(PiperFinishDecision.Action.ADVANCE_CHAPTER, action)
    }

    @Test
    fun decideAfterPrefetch_resumesExistingQueue_whenPrefetchExtendedQueueBeforeFinish() {
        // Arrange
        val state = activeState().copy(isChapterComplete = false, resumeIndex = 40, locatorCount = 65)

        // Act
        val action = PiperFinishDecision.decideAfterPrefetch(state)

        // Assert
        assertEquals(PiperFinishDecision.Action.RESUME_EXISTING_QUEUE, action)
    }

    @Test
    fun decideAfterPrefetch_requestsContinuation_whenQueueIsExhaustedButChapterIncomplete() {
        // Arrange
        val state = activeState().copy(isChapterComplete = false, resumeIndex = 40, locatorCount = 40)

        // Act
        val action = PiperFinishDecision.decideAfterPrefetch(state)

        // Assert
        assertEquals(PiperFinishDecision.Action.REQUEST_CONTINUATION, action)
    }

    @Test
    fun decideAfterContinuation_resumesWithContinuation_whenNextChunkExists() {
        // Arrange
        val state = activeState().copy(resumeIndex = 40, locatorCount = 40)

        // Act
        val action = PiperFinishDecision.decideAfterContinuation(state, nextChunkSize = 30)

        // Assert
        assertEquals(PiperFinishDecision.Action.RESUME_WITH_CONTINUATION, action)
    }

    @Test
    fun decideAfterContinuation_advancesChapter_whenNoContinuationChunkExists() {
        // Arrange
        val state = activeState().copy(resumeIndex = 40, locatorCount = 40)

        // Act
        val action = PiperFinishDecision.decideAfterContinuation(state, nextChunkSize = 0)

        // Assert
        assertEquals(PiperFinishDecision.Action.ADVANCE_CHAPTER, action)
    }

    @Test
    fun decideAfterContinuation_ignoresStaleSession_evenWhenContinuationChunkExists() {
        // Arrange
        val state = activeState().copy(currentSessionId = 2L)

        // Act
        val action = PiperFinishDecision.decideAfterContinuation(state, nextChunkSize = 30)

        // Assert
        assertEquals(PiperFinishDecision.Action.IGNORE, action)
    }

    private fun activeState(): PiperFinishDecision.State = PiperFinishDecision.State(
        expectedSessionId = 1L,
        currentSessionId = 1L,
        playerStillCurrent = true,
        isTtsPlaying = true,
        isChapterComplete = false,
        resumeIndex = 0,
        locatorCount = 1
    )
}
