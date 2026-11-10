package com.felix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordTest {

    @Test fun wakeWordIsDetectedInPlainAndPunctuatedText() {
        assertTrue(WakeWord.containsWakeWord("felix"))
        assertTrue(WakeWord.containsWakeWord("Felix!"))
        assertTrue(WakeWord.containsWakeWord("okay felix call yash"))
    }

    @Test fun similarWordsAreNotWakeWords() {
        assertFalse(WakeWord.containsWakeWord("fix"))
        assertFalse(WakeWord.containsWakeWord("felt"))
        assertFalse(WakeWord.containsWakeWord("felixs"))
        assertFalse(WakeWord.containsWakeWord("[unk] [unk]"))
    }

    @Test fun emptyInputNeverTriggers() {
        assertFalse(WakeWord.containsWakeWord(null))
        assertFalse(WakeWord.containsWakeWord(""))
        assertFalse(WakeWord.containsWakeWord("   "))
    }

    @Test fun grammarOnlyAllowsTheWakeWordOrTheCatchAllToken() =
        assertEquals("[\"felix\", \"[unk]\"]", WakeWord.GRAMMAR)
}
