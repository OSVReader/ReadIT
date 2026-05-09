package com.example.booklibrary.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HrefNormalizerTest {

    @Test
    fun normalize_returnsNull_forNullOrBlankHref() {
        // Arrange / Act / Assert
        assertNull(HrefNormalizer.normalize(null))
        assertNull(HrefNormalizer.normalize(""))
        assertNull(HrefNormalizer.normalize("   "))
    }

    @Test
    fun normalize_stripsFragmentAndLeadingSlash_forChapterHrefComparison() {
        // Arrange
        val href = "/OPS/chapter1.xhtml#readium-fragment"

        // Act
        val normalized = HrefNormalizer.normalize(href)

        // Assert
        assertEquals("OPS/chapter1.xhtml", normalized)
    }

    @Test
    fun normalize_trimsWhitespaceAndQueryParameters() {
        // Arrange
        val href = "  /OPS/chapter2.xhtml?cache=123#section  "

        // Act
        val normalized = HrefNormalizer.normalize(href)

        // Assert
        assertEquals("OPS/chapter2.xhtml", normalized)
    }

    @Test
    fun normalize_convertsBackslashesToForwardSlashes() {
        // Arrange
        val href = "\\OPS\\chapter3.xhtml#section"

        // Act
        val normalized = HrefNormalizer.normalize(href)

        // Assert
        assertEquals("OPS/chapter3.xhtml", normalized)
    }

    @Test
    fun equivalent_returnsTrue_whenOnlyFragmentOrLeadingSlashDiffers() {
        // Arrange
        val current = "/OPS/chapter4.xhtml#paragraph-2"
        val element = "OPS/chapter4.xhtml"

        // Act / Assert
        assertTrue(HrefNormalizer.equivalent(current, element))
    }

    @Test
    fun equivalent_returnsFalse_forDifferentChapters() {
        // Arrange / Act / Assert
        assertFalse(HrefNormalizer.equivalent("OPS/chapter4.xhtml", "OPS/chapter5.xhtml"))
    }
}
