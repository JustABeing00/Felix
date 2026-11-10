package com.felix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Call safety rules. No test here places a real call: the executor is a fake that only records numbers.
 */
class CallPolicyTest {

    private val yash = FelixContact("1", "Yash", listOf("+911234567890"))
    private val dad = FelixContact("5", "Dad", listOf("+914444444444", "+915555555555"))
    private val rahulSharma = FelixContact("r1", "Rahul Sharma", listOf("+912222222222"))
    private val rahulVerma = FelixContact("r2", "Rahul Verma", listOf("+913333333333"))

    private fun input(
        wakeEventId: Long? = 1L,
        commandComplete: Boolean = true,
        match: MatchResult = MatchResult.Found(yash, "+911234567890"),
        autoCallEnabled: Boolean = true,
        callPermissionGranted: Boolean = true,
        priorAttempts: Int = 0
    ) = CallPolicy.Input(wakeEventId, commandComplete, match, autoCallEnabled, callPermissionGranted, priorAttempts)

    private fun blockedReason(decision: CallDecision): BlockReason? =
        (decision as? CallDecision.Blocked)?.reason

    @Test fun uniqueContactWithWakeEventAndAutoCallIsAllowed() =
        assertEquals(CallDecision.Call(yash, "+911234567890"), CallPolicy.decide(input()))

    @Test fun noCallWithoutAWakeWordEvent() =
        assertEquals(BlockReason.NO_WAKE_EVENT, blockedReason(CallPolicy.decide(input(wakeEventId = null))))

    @Test fun noCallForAnIncompleteRecognitionResult() =
        assertEquals(BlockReason.INCOMPLETE_COMMAND, blockedReason(CallPolicy.decide(input(commandComplete = false))))

    @Test fun noCallForAnAmbiguousContact() {
        val ambiguous = MatchResult.Ambiguous(listOf(rahulSharma, rahulVerma))
        assertEquals(BlockReason.AMBIGUOUS, blockedReason(CallPolicy.decide(input(match = ambiguous))))
    }

    @Test fun noCallForAnUnknownContact() =
        assertEquals(
            BlockReason.NOT_FOUND,
            blockedReason(CallPolicy.decide(input(match = MatchResult.NotFound("Nobody"))))
        )

    @Test fun noCallForAContactWithoutANumber() =
        assertEquals(
            BlockReason.NO_NUMBER,
            blockedReason(CallPolicy.decide(input(match = MatchResult.NoNumber(yash))))
        )

    @Test fun noCallUntilTheUserPicksANumberForAMultiNumberContact() {
        val needChoice = MatchResult.NeedChoice(dad, dad.phones)
        assertEquals(BlockReason.NEEDS_PREFERRED_NUMBER, blockedReason(CallPolicy.decide(input(match = needChoice))))
    }

    @Test fun noCallWhileAutomaticCallingIsDisabledButTheNumberIsOfferedForADial() {
        val decision = CallPolicy.decide(input(autoCallEnabled = false)) as CallDecision.Blocked
        assertEquals(BlockReason.AUTO_CALL_DISABLED, decision.reason)
        assertEquals("+911234567890", decision.phone)
    }

    @Test fun noCallWithoutThePhonePermission() =
        assertEquals(BlockReason.PERMISSION_MISSING, blockedReason(CallPolicy.decide(input(callPermissionGranted = false))))

    @Test fun noSecondAttemptForTheSameCommand() =
        assertEquals(BlockReason.ALREADY_ATTEMPTED, blockedReason(CallPolicy.decide(input(priorAttempts = 1))))

    @Test fun emergencyNumbersAreRefusedEvenIfAContactHasOne() {
        val gate = FelixContact("9", "Gate", listOf("112"))
        assertEquals(
            BlockReason.INVALID_NUMBER,
            blockedReason(CallPolicy.decide(input(match = MatchResult.Found(gate, "112"))))
        )
    }

    // ---------------- Dispatcher ----------------

    private class FakeExecutor(var outcome: CallOutcome = CallOutcome.Sent) : CallExecutor {
        val placed = mutableListOf<String>()

        override fun placeCall(phone: String): CallOutcome {
            placed += phone
            return outcome
        }
    }

    private fun request(
        text: String,
        wakeId: Long? = 1L,
        contacts: List<FelixContact> = listOf(yash, dad),
        contactsGranted: Boolean = true,
        preferred: Map<String, String> = emptyMap(),
        autoCall: Boolean = true,
        callGranted: Boolean = true,
        sound: Boolean = true
    ) = DispatchRequest(text, wakeId, contacts, contactsGranted, preferred, autoCall, callGranted, sound)

    @Test fun dispatchPlacesExactlyOneCallForAValidCommand() {
        val executor = FakeExecutor()
        val result = CommandDispatcher(executor).dispatch(request("Call Yash"))
        assertEquals(DispatchResult.CallRequested(yash, "+911234567890", "Yash"), result)
        assertEquals(listOf("+911234567890"), executor.placed)
    }

    @Test fun secondDispatchForTheSameWakeEventNeverCallsAgain() {
        val executor = FakeExecutor()
        val dispatcher = CommandDispatcher(executor)
        dispatcher.dispatch(request("Call Yash", wakeId = 7L))
        val second = dispatcher.dispatch(request("Call Yash", wakeId = 7L))
        assertEquals(BlockReason.ALREADY_ATTEMPTED, (second as DispatchResult.Blocked).reason)
        assertEquals(1, executor.placed.size)
    }

    @Test fun aFailedAttemptIsNotRetriedForTheSameCommand() {
        val executor = FakeExecutor(CallOutcome.PermissionDenied)
        val dispatcher = CommandDispatcher(executor)
        assertTrue(dispatcher.dispatch(request("Call Yash", wakeId = 3L)) is DispatchResult.CallFailed)
        dispatcher.dispatch(request("Call Yash", wakeId = 3L))
        assertEquals(1, executor.placed.size)
    }

    @Test fun unrelatedSpeechNeverReachesTheExecutor() {
        val executor = FakeExecutor()
        val result = CommandDispatcher(executor).dispatch(request("Felix, text Yash"))
        assertTrue(result is DispatchResult.NotACommand)
        assertTrue(executor.placed.isEmpty())
    }

    @Test fun ambiguousNameNeverReachesTheExecutor() {
        val executor = FakeExecutor()
        val result = CommandDispatcher(executor).dispatch(
            request("Call Rahul", contacts = listOf(rahulSharma, rahulVerma))
        )
        assertEquals(BlockReason.AMBIGUOUS, (result as DispatchResult.Blocked).reason)
        assertTrue(executor.placed.isEmpty())
    }

    @Test fun noWakeEventMeansNoCall() {
        val executor = FakeExecutor()
        val result = CommandDispatcher(executor).dispatch(request("Call Yash", wakeId = null))
        assertEquals(BlockReason.NO_WAKE_EVENT, (result as DispatchResult.Blocked).reason)
        assertTrue(executor.placed.isEmpty())
    }

    @Test fun missingContactsPermissionMeansNoCall() {
        val executor = FakeExecutor()
        val result = CommandDispatcher(executor).dispatch(request("Call Yash", contactsGranted = false))
        assertEquals(BlockReason.CONTACTS_PERMISSION_MISSING, (result as DispatchResult.Blocked).reason)
        assertTrue(executor.placed.isEmpty())
    }

    @Test fun autoCallingOffMeansNoCall() {
        val executor = FakeExecutor()
        val result = CommandDispatcher(executor).dispatch(request("Call Yash", autoCall = false))
        assertEquals(BlockReason.AUTO_CALL_DISABLED, (result as DispatchResult.Blocked).reason)
        assertTrue(executor.placed.isEmpty())
    }

    @Test fun preferredNumberIsUsedForAMultiNumberContact() {
        val executor = FakeExecutor()
        val result = CommandDispatcher(executor).dispatch(
            request("Call Dad", preferred = mapOf("5" to "+915555555555"))
        )
        assertEquals(DispatchResult.CallRequested(dad, "+915555555555", "Dad"), result)
        assertEquals(listOf("+915555555555"), executor.placed)
    }

    @Test fun soundMatchedCommandCallsOnceAndKeepsWhatWasHeard() {
        val executor = FakeExecutor()
        val result = CommandDispatcher(executor).dispatch(request("Call Yesh"))
        assertEquals(DispatchResult.CallRequested(yash, "+911234567890", "Yesh"), result)
        assertEquals(listOf("+911234567890"), executor.placed)
    }

    @Test fun soundMatchingOffNeverCallsForAMisheardName() {
        val executor = FakeExecutor()
        val result = CommandDispatcher(executor).dispatch(request("Call Yesh", sound = false))
        assertTrue(result is DispatchResult.Blocked)
        assertTrue(executor.placed.isEmpty())
    }
}
