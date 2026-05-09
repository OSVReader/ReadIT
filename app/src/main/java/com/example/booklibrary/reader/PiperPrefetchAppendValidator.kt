package com.example.booklibrary.reader

object PiperPrefetchAppendValidator {

    enum class RejectReason {
        STALE_SESSION,
        NOT_PLAYING,
        MISSING_PLAYER,
        EMPTY_CHUNK,
        PLAYER_APPEND_FAILED
    }

    data class AppendResult<T>(
        val appended: Boolean,
        val locators: List<T>,
        val playerQueueSize: Int? = null,
        val rejectReason: RejectReason? = null
    )

    fun <T> appendIfActive(
        expectedSessionId: Long,
        currentSessionId: Long,
        isTtsPlaying: Boolean,
        existingLocators: List<T>,
        chunk: List<T>,
        appendToPlayer: ((List<String>) -> Int)?,
        textOf: (T) -> String
    ): AppendResult<T> {
        if (currentSessionId != expectedSessionId) {
            return AppendResult(false, existingLocators, rejectReason = RejectReason.STALE_SESSION)
        }
        if (!isTtsPlaying) {
            return AppendResult(false, existingLocators, rejectReason = RejectReason.NOT_PLAYING)
        }
        if (appendToPlayer == null) {
            return AppendResult(false, existingLocators, rejectReason = RejectReason.MISSING_PLAYER)
        }
        if (chunk.isEmpty()) {
            return AppendResult(false, existingLocators, rejectReason = RejectReason.EMPTY_CHUNK)
        }

        val texts = chunk.map(textOf)
        val playerQueueSize = try {
            appendToPlayer(texts)
        } catch (_: Exception) {
            return AppendResult(false, existingLocators, rejectReason = RejectReason.PLAYER_APPEND_FAILED)
        }

        return AppendResult(
            appended = true,
            locators = existingLocators + chunk,
            playerQueueSize = playerQueueSize
        )
    }
}
