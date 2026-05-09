package com.example.booklibrary.tts

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

class SentenceSplitterTest {

    private lateinit var previousLocale: Locale

    @Before
    fun setUp() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun tearDown() {
        Locale.setDefault(previousLocale)
    }

    @Test
    fun splitToSpans_returnsEmptyList_forBlankText() {
        // Arrange
        val text = "  \n\t  "

        // Act
        val spans = SentenceSplitter.splitToSpans(text)

        // Assert
        assertTrue(spans.isEmpty())
    }

    @Test
    fun splitToSpans_returnsSentencesWithExactOffsets_afterTrimmingOuterWhitespace() {
        // Arrange
        val text = "  First sentence.  Second sentence! \n Third sentence?  "

        // Act
        val spans = SentenceSplitter.splitToSpans(text)

        // Assert
        assertEquals(listOf("First sentence.", "Second sentence!", "Third sentence?"), spans.map { it.text })
        spans.forEach { span ->
            assertEquals(span.text, text.substring(span.start, span.end))
            assertTrue("span should not start with whitespace", !span.text.first().isWhitespace())
            assertTrue("span should not end with whitespace", !span.text.last().isWhitespace())
        }
        assertEquals(text.indexOf("First sentence."), spans[0].start)
        assertEquals(text.indexOf("Second sentence!"), spans[1].start)
        assertEquals(text.indexOf("Third sentence?"), spans[2].start)
    }

    @Test
    fun split_returnsOnlySentenceText_forSimpleParagraph() {
        // Arrange
        val text = "Alpha starts here. Beta follows. Gamma ends."

        // Act
        val sentences = SentenceSplitter.split(text)

        // Assert
        assertEquals(listOf("Alpha starts here.", "Beta follows.", "Gamma ends."), sentences)
    }

    @Test
    fun splitToSpans_preservesOffsets_forRepeatedSentences() {
        // Arrange
        val text = "Repeat me. Repeat me. Repeat me."

        // Act
        val spans = SentenceSplitter.splitToSpans(text)

        // Assert
        assertEquals(3, spans.size)
        assertEquals(0, spans[0].start)
        assertEquals(10, spans[0].end)
        assertEquals(11, spans[1].start)
        assertEquals(21, spans[1].end)
        assertEquals(22, spans[2].start)
        assertEquals(32, spans[2].end)
        spans.forEach { span -> assertEquals("Repeat me.", span.text) }
    }

    @Test
    fun splitToSpans_splitsLongCommaSeparatedSentenceIntoBoundedChunks() {
        // Arrange
        val text = (1..90).joinToString(separator = ", ") { "word$it" }

        // Act
        val spans = SentenceSplitter.splitToSpans(text)

        // Assert
        assertTrue("expected long comma-separated text to split into multiple chunks", spans.size > 1)
        spans.forEach { span ->
            assertTrue("chunk should stay near player paragraph limit", span.text.length <= 400)
            assertEquals(span.text, text.substring(span.start, span.end))
            assertTrue("span should not start with whitespace", !span.text.first().isWhitespace())
            assertTrue("span should not end with whitespace", !span.text.last().isWhitespace())
        }
        assertEquals("combined chunks should preserve original non-whitespace text", text, spans.joinToString(separator = " ") { it.text })
    }

    @Test
    fun splitToSpans_keepsClosingQuoteWithQuotedSentence() {
        // Arrange
        val text = "\"Hello there.\" She waved."

        // Act
        val spans = SentenceSplitter.splitToSpans(text)

        // Assert
        assertEquals(listOf("\"Hello there.\"", "She waved."), spans.map { it.text })
        spans.forEach { span -> assertEquals(span.text, text.substring(span.start, span.end)) }
    }

    @Test
    fun splitToSpans_handlesCommonAbbreviation_withoutSplittingInsideTitle() {
        // Arrange
        val text = "Dr. Smith arrived. He sat down."

        // Act
        val spans = SentenceSplitter.splitToSpans(text)

        // Assert
        assertEquals(listOf("Dr. Smith arrived.", "He sat down."), spans.map { it.text })
        spans.forEach { span -> assertEquals(span.text, text.substring(span.start, span.end)) }
    }

    @Test
    fun splitToSpans_handlesEllipsisAsPartOfSentence() {
        // Arrange
        val text = "Wait... are you sure? Yes."

        // Act
        val spans = SentenceSplitter.splitToSpans(text)

        // Assert
        assertEquals(listOf("Wait... are you sure?", "Yes."), spans.map { it.text })
        spans.forEach { span -> assertEquals(span.text, text.substring(span.start, span.end)) }
    }

    @Test
    fun splitToSpans_handlesNewlineHeavyEpubText_andTrimsSentenceBoundaries() {
        // Arrange
        val text = "\n\nChapter One\n\nThis is the first sentence.\n\nThis is the second sentence.\n  This is the third sentence.\n"

        // Act
        val spans = SentenceSplitter.splitToSpans(text)

        // Assert
        assertEquals(
            listOf(
                "Chapter One\n\nThis is the first sentence.",
                "This is the second sentence.",
                "This is the third sentence."
            ),
            spans.map { it.text }
        )
        spans.forEach { span ->
            assertEquals(span.text, text.substring(span.start, span.end))
            assertTrue("span should not start with whitespace", !span.text.first().isWhitespace())
            assertTrue("span should not end with whitespace", !span.text.last().isWhitespace())
        }
    }
}
