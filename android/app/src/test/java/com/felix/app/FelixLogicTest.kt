package com.felix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parser, phone rules and contact matching. The first block mirrors the website's tests/parser.test.js. */
class FelixLogicTest {

    private fun okName(raw: String): String {
        val result = FelixLogic.parseCommand(raw)
        assertTrue("expected a name from \"$raw\" but got $result", result is ParseResult.Ok)
        return (result as ParseResult.Ok).name
    }

    private fun rejectedReason(raw: String?): String {
        val result = FelixLogic.parseCommand(raw)
        assertTrue("expected rejection for \"$raw\" but got $result", result is ParseResult.Err)
        return (result as ParseResult.Err).reason
    }

    @Test fun felixCommaCallYashExtractsYash() = assertEquals("Yash", okName("Felix, call Yash"))

    @Test fun callYashWithFullStopExtractsYash() = assertEquals("Yash", okName("Call Yash."))

    @Test fun felixCallMomExtractsMom() = assertEquals("Mom", okName("Felix call Mom."))

    @Test fun pleaseCallRahulExtractsRahul() = assertEquals("Rahul", okName("Please call Rahul."))

    @Test fun canYouCallDadExtractsDad() = assertEquals("Dad", okName("Felix, can you call Dad?"))

    @Test fun capitalizationIsIgnoredForTheAction() {
        assertEquals("YASH", okName("FELIX, CALL YASH"))
        assertEquals("rahul", okName("please CALL rahul"))
    }

    @Test fun extraWhitespaceAndPunctuationAreNormalized() =
        assertEquals("Yash Kumar", okName("   felix ,,  call   Yash   Kumar!!! "))

    @Test fun greetingsAndPolitenessAreDropped() =
        assertEquals("Mom", okName("Okay, hey felix, could you please call Mom"))

    @Test fun multiWordNamesArePreserved() = assertEquals("Rahul Sharma", okName("Call Rahul Sharma"))

    @Test fun felixAsAContactNameAfterCallIsKept() = assertEquals("Felix", okName("call Felix"))

    @Test fun emptyCommandsAreRejected() {
        assertEquals("empty", rejectedReason(""))
        assertEquals("empty", rejectedReason("   "))
        assertEquals("empty", rejectedReason(null))
        assertEquals("empty", rejectedReason("Felix, please"))
    }

    @Test fun callWithoutANameIsRejected() = assertEquals("empty", rejectedReason("Felix, call"))

    @Test fun unsupportedActionsAreRejected() {
        assertEquals("unsupported", rejectedReason("Felix, text Yash"))
        assertEquals("unsupported", rejectedReason("I want to call Yash"))
    }

    @Test fun numericOnlyNamesAreRejected() = assertEquals("unsupported", rejectedReason("Call 911"))

    @Test fun validIndianNumberBuildsTelUri() {
        val normalized = FelixLogic.normalizePhone("+911234567890")
        assertEquals("+911234567890", normalized)
        assertEquals("tel:+911234567890", FelixLogic.telUri(normalized))
    }

    @Test fun phoneNumbersAreNormalizedFromCommonFormats() {
        assertEquals("+911234567890", FelixLogic.normalizePhone("+91 12345-67890"))
        assertEquals("08012345678", FelixLogic.normalizePhone("(080) 1234 5678"))
        assertNull(FelixLogic.normalizePhone("call me"))
        assertNull(FelixLogic.normalizePhone("123"))
    }

    @Test fun telUriIsOnlyBuiltFromValidatedNumbers() {
        assertNull(FelixLogic.telUri("abc"))
        assertNull(FelixLogic.telUri(null))
        assertNull(FelixLogic.telUri(""))
    }

    @Test fun emergencyAndShortNumbersAreBlockedFromAutomaticCalls() {
        assertTrue(FelixLogic.isBlockedForAutoCall("112"))
        assertTrue(FelixLogic.isBlockedForAutoCall("911"))
        assertTrue(FelixLogic.isBlockedForAutoCall("123456"))
        assertFalse(FelixLogic.isBlockedForAutoCall("+911234567890"))
    }

    // ---------------- Contact matching ----------------

    private val contacts = listOf(
        FelixContact("1", "Yash Kumar", listOf("+911111111111")),
        FelixContact("2", "Rahul Sharma", listOf("+912222222222")),
        FelixContact("3", "Rahul Verma", listOf("+913333333333")),
        FelixContact("4", "Mom", emptyList()),
        FelixContact("5", "Dad", listOf("+914444444444", "+915555555555"))
    )

    @Test fun uniqueExactMatchIsSelected() {
        val m = FelixLogic.matchContact("Rahul Sharma", contacts)
        assertTrue(m is MatchResult.Found)
        assertEquals("+912222222222", (m as MatchResult.Found).phone)
    }

    @Test fun exactMatchIsCaseInsensitive() =
        assertTrue(FelixLogic.matchContact("yash kumar", contacts) is MatchResult.Found)

    @Test fun uniqueFirstNameMatchIsSelected() {
        val m = FelixLogic.matchContact("Yash", contacts)
        assertTrue(m is MatchResult.Found)
        assertEquals("Yash Kumar", (m as MatchResult.Found).contact.name)
    }

    @Test fun multipleFirstNameMatchesAreAmbiguous() {
        val m = FelixLogic.matchContact("Rahul", contacts)
        assertTrue(m is MatchResult.Ambiguous)
        assertEquals(2, (m as MatchResult.Ambiguous).matches.size)
    }

    @Test fun twoDifferentPeopleWithTheSameNameAreAmbiguous() {
        val twins = listOf(
            FelixContact("a", "Priya", listOf("+919000000001")),
            FelixContact("b", "Priya", listOf("+919000000002"))
        )
        assertTrue(FelixLogic.matchContact("Priya", twins) is MatchResult.Ambiguous)
    }

    @Test fun noMatchIsNotFound() = assertTrue(FelixLogic.matchContact("Nobody", contacts) is MatchResult.NotFound)

    @Test fun contactWithoutANumberCannotBeCalled() {
        assertTrue(FelixLogic.matchContact("Mom", contacts) is MatchResult.NoNumber)
    }

    @Test fun invalidStoredNumbersAreIgnored() {
        val withJunk = listOf(FelixContact("x", "Ravi", listOf("abc", "+919812345678")))
        assertEquals("+919812345678", (FelixLogic.matchContact("Ravi", withJunk) as MatchResult.Found).phone)
    }

    @Test fun multiNumberContactNeedsAChoiceWithoutAPreference() {
        val m = FelixLogic.matchContact("Dad", contacts)
        assertTrue(m is MatchResult.NeedChoice)
        assertEquals(2, (m as MatchResult.NeedChoice).phones.size)
    }

    @Test fun savedPreferredNumberResolvesMultiNumberContact() {
        val m = FelixLogic.matchContact("Dad", contacts, mapOf("5" to "+915555555555"))
        assertEquals("+915555555555", (m as MatchResult.Found).phone)
    }

    @Test fun preferredNumberNotOnTheContactIsIgnored() {
        val m = FelixLogic.matchContact("Dad", contacts, mapOf("5" to "+910000000000"))
        assertTrue(m is MatchResult.NeedChoice)
    }

    @Test fun misheardFullNameFindsTheContactBySound() {
        val list = contacts + FelixContact("9", "Satvinder Kumar", listOf("+916666666666"))
        val m = FelixLogic.matchContact("Satwinder Kumar", list)
        assertTrue(m is MatchResult.Found)
        m as MatchResult.Found
        assertEquals("Satvinder Kumar", m.contact.name)
        assertEquals("+916666666666", m.phone)
        assertTrue(m.bySound)
    }

    @Test fun spellingVariantsOfAShortNameFindTheContact() {
        val m = FelixLogic.matchContact("yes", listOf(FelixContact("1", "Yash", listOf("+911234567890"))))
        assertTrue((m as MatchResult.Found).bySound)
    }

    @Test fun twoContactsThatSoundAlikeAreAmbiguous() {
        val twins = listOf(
            FelixContact("a", "Yash", listOf("+919000000001")),
            FelixContact("b", "Yesh", listOf("+919000000002"))
        )
        val m = FelixLogic.matchContact("yes", twins)
        assertTrue(m is MatchResult.Ambiguous)
        assertEquals(2, (m as MatchResult.Ambiguous).matches.size)
    }

    @Test fun exactMatchBeatsASoundAlikeTwin() {
        val twins = listOf(
            FelixContact("a", "Yash", listOf("+919000000001")),
            FelixContact("b", "Yesh", listOf("+919000000002"))
        )
        val m = FelixLogic.matchContact("Yash", twins)
        assertTrue(m is MatchResult.Found)
        m as MatchResult.Found
        assertEquals("a", m.contact.id)
        assertFalse(m.bySound)
    }

    @Test fun consonantChangesDoNotMatch() {
        val list = listOf(FelixContact("1", "Dad", listOf("+911234567890")))
        assertTrue(FelixLogic.matchContact("Dan", list) is MatchResult.NotFound)
    }

    @Test fun addedOrMissingLettersDoNotMatch() {
        assertTrue(FelixLogic.matchContact("Ankit", contacts) is MatchResult.NotFound)
    }

    @Test fun twoSoundErrorsDoNotMatch() {
        val list = contacts + FelixContact("9", "Satvinder Kumar", listOf("+916666666666"))
        assertTrue(FelixLogic.matchContact("Satvendar Kumar", list) is MatchResult.NotFound)
    }

    @Test fun surnameAloneDoesNotMatch() {
        val list = contacts + FelixContact("9", "Satvinder Kumar", listOf("+916666666666"))
        assertTrue(FelixLogic.matchContact("Kumar", list) is MatchResult.NotFound)
    }

    @Test fun soundMatchingOffFallsBackToExactOnly() {
        val list = contacts + FelixContact("9", "Satvinder Kumar", listOf("+916666666666"))
        assertTrue(FelixLogic.matchContact("Satwinder Kumar", list, soundMatching = false) is MatchResult.NotFound)
        assertTrue(FelixLogic.matchContact("Yash", contacts, soundMatching = false) is MatchResult.Found)
    }

    @Test fun heardNoteIsEmptyWhenNamesAgree() {
        assertEquals("", FelixLogic.heardNote("Yash Kumar", "Yash Kumar"))
        assertEquals("", FelixLogic.heardNote("yash kumar", "Yash Kumar"))
        assertEquals("", FelixLogic.heardNote("", "Yash Kumar"))
    }

    @Test fun heardNoteQuotesWhatWasHeard() {
        assertEquals(" (heard “satwinder kumar”)", FelixLogic.heardNote("satwinder kumar", "Satvinder Kumar"))
    }
}
