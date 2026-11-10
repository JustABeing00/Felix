package com.felix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sound-alike matching. Every rule here exists because a recognizer mangles names this way. */
class SoundMatchTest {

    @Test fun canonicalFormCollapsesCommonSpellingVariants() {
        assertEquals("satvinder", SoundMatch.normalizeWord("satwinder"))
        assertEquals("satvinder", SoundMatch.normalizeWord("Satvinder"))
        assertEquals("kumar", SoundMatch.normalizeWord("Kumaar"))
        assertEquals("puja", SoundMatch.normalizeWord("Pooja"))
        assertEquals("kusi", SoundMatch.normalizeWord("Khushi"))
        assertEquals("sreia", SoundMatch.normalizeWord("Shreya"))
        assertEquals("ketan", SoundMatch.normalizeWord("Chetan"))
        assertEquals("bijai", SoundMatch.normalizeWord("Bijay"))
        assertEquals("joia", SoundMatch.normalizeWord("Zoya"))
        assertEquals("moit", SoundMatch.normalizeWord("Mohith"))
        assertEquals("dipak", SoundMatch.normalizeWord("Deepak"))
        assertEquals("barat", SoundMatch.normalizeWord("Bharat"))
        assertEquals("nilam", SoundMatch.normalizeWord("Neelam"))
    }

    @Test fun recognizerConfusionsMatch() {
        assertEquals(0, SoundMatch.changes("satwinder kumar", "Satvinder Kumar"))
        assertEquals(1, SoundMatch.changes("yesh", "Yash"))
        assertEquals(1, SoundMatch.changes("yes", "Yash"))
        assertEquals(1, SoundMatch.changes("ankita karg", "Ankita Kharb"))
        assertEquals(1, SoundMatch.changes("ankita kharv", "Ankita Kharb"))
        assertEquals(0, SoundMatch.changes("mohit", "Mohith"))
        assertEquals(1, SoundMatch.changes("mom", "Mum"))
        assertEquals(1, SoundMatch.changes("rohit", "Rahit"))
        assertEquals(0, SoundMatch.changes("john", "Jon"))
        assertEquals(0, SoundMatch.changes("phil", "Fil"))
    }

    @Test fun unrelatedNamesNeverMatch() {
        assertNull(SoundMatch.changes("dad", "Dan"))
        assertNull(SoundMatch.changes("ankit", "Ankita"))
        assertNull(SoundMatch.changes("sam", "Samir"))
        assertNull(SoundMatch.changes("amit", "Amir"))
        assertNull(SoundMatch.changes("kit", "Bit"))
        assertNull(SoundMatch.changes("neha", "Neh"))
        assertNull(SoundMatch.changes("rahul", "Raj"))
        assertNull(SoundMatch.changes("ravi", "Rahi"))
        assertNull(SoundMatch.changes("rohan", "Ronan"))
        assertNull(SoundMatch.changes("steve", "Stev"))
        assertNull(SoundMatch.changes("mike", "Mick"))
        assertNull(SoundMatch.changes("nobody", "Mom"))
    }

    @Test fun twoIndependentErrorsExceedTheBudget() {
        // e/i and a/e are both tolerated alone, but not together.
        assertNull(SoundMatch.changes("satvendar kumar", "Satvinder Kumar"))
    }

    @Test fun oneWordQueryOnlyComparesAgainstFirstNames() {
        assertNull(SoundMatch.changes("kumar", "Satvinder Kumar"))
        assertEquals(0, SoundMatch.changes("satwinder", "Satvinder Kumar"))
    }

    @Test fun wordCountMismatchNeverMatches() {
        assertNull(SoundMatch.changes("satwinder kumar singh", "Satvinder Kumar"))
        assertNull(SoundMatch.changes("satwinder kumar", "Satvinder"))
    }

    @Test fun emptyInputNeverMatches() {
        assertNull(SoundMatch.changes("", "Yash"))
        assertNull(SoundMatch.changes("yash", ""))
        assertFalse(SoundMatch.soundsLike("  ", "Yash"))
    }

    @Test fun soundsLikeAgreesWithChanges() {
        assertTrue(SoundMatch.soundsLike("satwinder", "Satvinder Kumar"))
        assertFalse(SoundMatch.soundsLike("dad", "Dan"))
    }
}
