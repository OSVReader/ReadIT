package com.example.booklibrary.tts

import java.text.BreakIterator
import java.util.Locale

object SentenceSplitter {

    private const val MAX_LENGTH = 400
    private val COMMON_ABBREVIATIONS = setOf(
        "mr.", "mrs.", "ms.", "dr.", "prof.", "sr.", "jr.", "st.",
        "vs.", "etc.", "e.g.", "i.e."
    )

    data class SentenceSpan(
        val text: String,
        val start: Int,
        val end: Int
    )

    fun split(text: String): List<String> = splitToSpans(text).map { it.text }

    fun splitToSpans(text: String): List<SentenceSpan> {
        if (text.isBlank()) return emptyList()

        val bi = BreakIterator.getSentenceInstance(Locale.getDefault())
        bi.setText(text)

        val rawSpans = mutableListOf<SentenceSpan>()
        var start = bi.first()
        var end = bi.next()

        while (end != BreakIterator.DONE) {
            val trimmedStart = trimStart(text, start, end)
            val trimmedEnd = trimEnd(text, trimmedStart, end)
            if (trimmedStart < trimmedEnd) {
                rawSpans.add(SentenceSpan(text.substring(trimmedStart, trimmedEnd), trimmedStart, trimmedEnd))
            }
            start = end
            end = bi.next()
        }

        val mergedSpans = mergeAbbreviationSpans(text, rawSpans)
        return mergedSpans.flatMap { span ->
            if (span.end - span.start > MAX_LENGTH) {
                splitLongToSpans(text, span.start, span.end)
            } else {
                listOf(span)
            }
        }
    }

    private fun mergeAbbreviationSpans(text: String, spans: List<SentenceSpan>): List<SentenceSpan> {
        if (spans.size < 2) return spans

        val result = mutableListOf<SentenceSpan>()
        var i = 0
        while (i < spans.size) {
            val current = spans[i]
            if (i < spans.lastIndex && isCommonAbbreviation(current.text)) {
                val next = spans[i + 1]
                result.add(SentenceSpan(text.substring(current.start, next.end), current.start, next.end))
                i += 2
            } else {
                result.add(current)
                i++
            }
        }
        return result
    }

    private fun isCommonAbbreviation(text: String): Boolean {
        return text.trim().lowercase(Locale.US) in COMMON_ABBREVIATIONS
    }

    private fun splitLongToSpans(text: String, start: Int, end: Int): List<SentenceSpan> {
        // First try semicolons. They usually represent stronger pauses than commas.
        val semiParts = splitAfterDelimiterWithFollowingWhitespace(text, start, end, ';')
        if (semiParts.size > 1 && semiParts.all { it.end - it.start <= MAX_LENGTH }) {
            return semiParts
        }

        // Then try commas and merge pieces into chunks capped near MAX_LENGTH.
        val commaParts = splitAfterDelimiterWithFollowingWhitespace(text, start, end, ',')
        if (commaParts.size <= 1) {
            return listOf(SentenceSpan(text.substring(start, end), start, end))
        }

        val result = mutableListOf<SentenceSpan>()
        var chunkStart = commaParts.first().start
        var chunkEnd = commaParts.first().end

        for (part in commaParts.drop(1)) {
            if (part.end - chunkStart > MAX_LENGTH && chunkStart < chunkEnd) {
                val safeStart = trimStart(text, chunkStart, chunkEnd)
                val safeEnd = trimEnd(text, safeStart, chunkEnd)
                if (safeStart < safeEnd) {
                    result.add(SentenceSpan(text.substring(safeStart, safeEnd), safeStart, safeEnd))
                }
                chunkStart = part.start
                chunkEnd = part.end
            } else {
                chunkEnd = part.end
            }
        }

        val safeStart = trimStart(text, chunkStart, chunkEnd)
        val safeEnd = trimEnd(text, safeStart, chunkEnd)
        if (safeStart < safeEnd) {
            result.add(SentenceSpan(text.substring(safeStart, safeEnd), safeStart, safeEnd))
        }

        return result.ifEmpty { listOf(SentenceSpan(text.substring(start, end), start, end)) }
    }

    private fun splitAfterDelimiterWithFollowingWhitespace(
        text: String,
        start: Int,
        end: Int,
        delimiter: Char
    ): List<SentenceSpan> {
        val result = mutableListOf<SentenceSpan>()
        var partStart = start
        var i = start

        while (i < end - 1) {
            if (text[i] == delimiter && text[i + 1].isWhitespace()) {
                val rawPartEnd = i + 1 // keep delimiter with previous piece
                val trimmedPartStart = trimStart(text, partStart, rawPartEnd)
                val trimmedPartEnd = trimEnd(text, trimmedPartStart, rawPartEnd)
                if (trimmedPartStart < trimmedPartEnd) {
                    result.add(SentenceSpan(text.substring(trimmedPartStart, trimmedPartEnd), trimmedPartStart, trimmedPartEnd))
                }

                var nextStart = i + 1
                while (nextStart < end && text[nextStart].isWhitespace()) nextStart++
                partStart = nextStart
                i = nextStart
            } else {
                i++
            }
        }

        val trimmedPartStart = trimStart(text, partStart, end)
        val trimmedPartEnd = trimEnd(text, trimmedPartStart, end)
        if (trimmedPartStart < trimmedPartEnd) {
            result.add(SentenceSpan(text.substring(trimmedPartStart, trimmedPartEnd), trimmedPartStart, trimmedPartEnd))
        }

        return result
    }

    private fun trimStart(text: String, start: Int, end: Int): Int {
        var i = start
        while (i < end && text[i].isWhitespace()) i++
        return i
    }

    private fun trimEnd(text: String, start: Int, end: Int): Int {
        var i = end
        while (i > start && text[i - 1].isWhitespace()) i--
        return i
    }
}
