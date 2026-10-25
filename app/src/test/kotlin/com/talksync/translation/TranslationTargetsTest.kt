package com.talksync.translation

// checks the list of languages the "Translate into" setting offers, no android needed.

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationTargetsTest {

    @Test
    fun `english is the default and is listed first`() {
        assertEquals("en", TranslationTargets.DEFAULT_CODE)
        assertEquals("en", TranslationTargets.all.first().code)
    }

    @Test
    fun `a code resolves to the language name that goes into the translation prompt`() {
        assertEquals("Spanish", TranslationTargets.forCode("es").englishName)
        assertEquals("Chinese", TranslationTargets.forCode("zh").englishName)
        assertTrue(TranslationTargets.all.map { it.code }.distinct().size == TranslationTargets.all.size)
    }

    @Test
    fun `an unsupported code is an error, not a silent fallback`() {
        assertThrows(IllegalArgumentException::class.java) { TranslationTargets.forCode("xx") }
    }
}
