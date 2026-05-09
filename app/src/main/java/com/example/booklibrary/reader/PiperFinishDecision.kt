package com.example.booklibrary.reader

object PiperFinishDecision {

    enum class Action {
        IGNORE,
        RESUME_EXISTING_QUEUE,
        REQUEST_CONTINUATION,
        RESUME_WITH_CONTINUATION,
        ADVANCE_CHAPTER
    }

    data class State(
        val expectedSessionId: Long,
        val currentSessionId: Long,
        val playerStillCurrent: Boolean,
        val isTtsPlaying: Boolean,
        val isChapterComplete: Boolean,
        val resumeIndex: Int,
        val locatorCount: Int
    )

    fun decideAfterPrefetch(state: State): Action {
        if (!state.isActiveSession()) return Action.IGNORE
        if (state.isChapterComplete) return Action.ADVANCE_CHAPTER
        return if (state.resumeIndex < state.locatorCount) {
            Action.RESUME_EXISTING_QUEUE
        } else {
            Action.REQUEST_CONTINUATION
        }
    }

    fun decideAfterContinuation(state: State, nextChunkSize: Int): Action {
        if (!state.isActiveSession()) return Action.IGNORE
        return if (nextChunkSize > 0) {
            Action.RESUME_WITH_CONTINUATION
        } else {
            Action.ADVANCE_CHAPTER
        }
    }

    private fun State.isActiveSession(): Boolean {
        return currentSessionId == expectedSessionId && playerStillCurrent && isTtsPlaying
    }
}
