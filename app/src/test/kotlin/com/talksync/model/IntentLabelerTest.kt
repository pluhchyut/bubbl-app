package com.talksync.model

// tests the quick intent labels on normal caption phrases.

import org.junit.Assert.assertTrue
import org.junit.Test

class IntentLabelerTest {
    @Test
    fun labelsDirectQuestions() {
        val labels = IntentLabeler.labelsFor("Can you hear me?")

        assertTrue(IntentLabel.QUESTION in labels)
    }

    @Test
    fun labelsInstructions() {
        val labels = IntentLabeler.labelsFor("Please send the notes after class")

        assertTrue(IntentLabel.INSTRUCTION in labels)
    }

    @Test
    fun labelsJokes() {
        val labels = IntentLabeler.labelsFor("That was hilarious lol")

        assertTrue(IntentLabel.JOKE in labels)
    }

    @Test
    fun labelsImportantTurns() {
        val labels = IntentLabeler.labelsFor("Don't forget, the deadline is today")

        assertTrue(IntentLabel.IMPORTANT in labels)
    }
}
