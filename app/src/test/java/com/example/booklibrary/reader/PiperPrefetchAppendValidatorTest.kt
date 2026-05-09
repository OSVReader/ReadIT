package com.example.booklibrary.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PiperPrefetchAppendValidatorTest {

    @Test
    fun appendIfActive_rejectsStaleSession_withoutAppendingToPlayer() {
        // Arrange
        var appendCalls = 0
        val existing = listOf(FakeSentence("old"))
        val chunk = listOf(FakeSentence("new"))

        // Act
        val result = PiperPrefetchAppendValidator.appendIfActive(
            expectedSessionId = 1L,
            currentSessionId = 2L,
            isTtsPlaying = true,
            existingLocators = existing,
            chunk = chunk,
            appendToPlayer = { appendCalls++; it.size },
            textOf = FakeSentence::text
        )

        // Assert
        assertFalse(result.appended)
        assertEquals(PiperPrefetchAppendValidator.RejectReason.STALE_SESSION, result.rejectReason)
        assertEquals(existing, result.locators)
        assertEquals(0, appendCalls)
    }

    @Test
    fun appendIfActive_rejectsWhenNotPlaying_withoutAppendingToPlayer() {
        // Arrange
        var appendCalls = 0
        val existing = listOf(FakeSentence("old"))
        val chunk = listOf(FakeSentence("new"))

        // Act
        val result = PiperPrefetchAppendValidator.appendIfActive(
            expectedSessionId = 3L,
            currentSessionId = 3L,
            isTtsPlaying = false,
            existingLocators = existing,
            chunk = chunk,
            appendToPlayer = { appendCalls++; it.size },
            textOf = FakeSentence::text
        )

        // Assert
        assertFalse(result.appended)
        assertEquals(PiperPrefetchAppendValidator.RejectReason.NOT_PLAYING, result.rejectReason)
        assertEquals(existing, result.locators)
        assertEquals(0, appendCalls)
    }

    @Test
    fun appendIfActive_rejectsMissingPlayer_withoutChangingLocators() {
        // Arrange
        val existing = listOf(FakeSentence("old"))
        val chunk = listOf(FakeSentence("new"))

        // Act
        val result = PiperPrefetchAppendValidator.appendIfActive(
            expectedSessionId = 4L,
            currentSessionId = 4L,
            isTtsPlaying = true,
            existingLocators = existing,
            chunk = chunk,
            appendToPlayer = null,
            textOf = FakeSentence::text
        )

        // Assert
        assertFalse(result.appended)
        assertEquals(PiperPrefetchAppendValidator.RejectReason.MISSING_PLAYER, result.rejectReason)
        assertEquals(existing, result.locators)
    }

    @Test
    fun appendIfActive_rejectsEmptyChunk_withoutAppendingToPlayer() {
        // Arrange
        var appendCalls = 0
        val existing = listOf(FakeSentence("old"))

        // Act
        val result = PiperPrefetchAppendValidator.appendIfActive(
            expectedSessionId = 5L,
            currentSessionId = 5L,
            isTtsPlaying = true,
            existingLocators = existing,
            chunk = emptyList<FakeSentence>(),
            appendToPlayer = { appendCalls++; it.size },
            textOf = FakeSentence::text
        )

        // Assert
        assertFalse(result.appended)
        assertEquals(PiperPrefetchAppendValidator.RejectReason.EMPTY_CHUNK, result.rejectReason)
        assertEquals(existing, result.locators)
        assertEquals(0, appendCalls)
    }

    @Test
    fun appendIfActive_doesNotChangeLocators_whenPlayerAppendFails() {
        // Arrange
        val existing = listOf(FakeSentence("old"))
        val chunk = listOf(FakeSentence("new"))

        // Act
        val result = PiperPrefetchAppendValidator.appendIfActive(
            expectedSessionId = 6L,
            currentSessionId = 6L,
            isTtsPlaying = true,
            existingLocators = existing,
            chunk = chunk,
            appendToPlayer = { throw IllegalStateException("player stopped") },
            textOf = FakeSentence::text
        )

        // Assert
        assertFalse(result.appended)
        assertEquals(PiperPrefetchAppendValidator.RejectReason.PLAYER_APPEND_FAILED, result.rejectReason)
        assertEquals(existing, result.locators)
    }

    @Test
    fun appendIfActive_appendsToPlayerBeforeReturningExpandedLocatorQueue() {
        // Arrange
        val existing = listOf(FakeSentence("old"))
        val chunk = listOf(FakeSentence("new one"), FakeSentence("new two"))
        var locatorsObservedDuringPlayerAppend: List<FakeSentence>? = null
        var textsAppendedToPlayer: List<String>? = null

        // Act
        val result = PiperPrefetchAppendValidator.appendIfActive(
            expectedSessionId = 7L,
            currentSessionId = 7L,
            isTtsPlaying = true,
            existingLocators = existing,
            chunk = chunk,
            appendToPlayer = { texts ->
                locatorsObservedDuringPlayerAppend = existing
                textsAppendedToPlayer = texts
                existing.size + texts.size
            },
            textOf = FakeSentence::text
        )

        // Assert
        assertTrue(result.appended)
        assertNull(result.rejectReason)
        assertEquals(existing, locatorsObservedDuringPlayerAppend)
        assertEquals(listOf("new one", "new two"), textsAppendedToPlayer)
        assertEquals(existing + chunk, result.locators)
        assertEquals(3, result.playerQueueSize)
    }

    private data class FakeSentence(val text: String)
}
