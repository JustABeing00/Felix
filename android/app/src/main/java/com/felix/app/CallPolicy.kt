package com.felix.app

/** Places one phone call. Implemented by TelecomCallExecutor on the phone and by fakes in tests. */
interface CallExecutor {
    fun placeCall(phone: String): CallOutcome
}

sealed interface CallOutcome {
    object Sent : CallOutcome
    object PermissionDenied : CallOutcome
    data class Failed(val reason: String) : CallOutcome
}

enum class BlockReason {
    NO_WAKE_EVENT,
    INCOMPLETE_COMMAND,
    ALREADY_ATTEMPTED,
    CONTACTS_PERMISSION_MISSING,
    NOT_FOUND,
    AMBIGUOUS,
    NO_NUMBER,
    INVALID_NUMBER,
    NEEDS_PREFERRED_NUMBER,
    BLOCKED_NUMBER,
    AUTO_CALL_DISABLED,
    PERMISSION_MISSING
}

sealed interface CallDecision {
    data class Call(val contact: FelixContact, val phone: String) : CallDecision
    data class Blocked(
        val reason: BlockReason,
        val contact: FelixContact? = null,
        val phone: String? = null
    ) : CallDecision
}

/** Decides whether a recognized command may place a call. Every branch that is not a Call is a safe stop. */
object CallPolicy {

    data class Input(
        val wakeEventId: Long?,
        val commandComplete: Boolean,
        val match: MatchResult,
        val autoCallEnabled: Boolean,
        val callPermissionGranted: Boolean,
        val priorAttempts: Int
    )

    fun decide(input: Input): CallDecision {
        if (input.wakeEventId == null) return CallDecision.Blocked(BlockReason.NO_WAKE_EVENT)
        if (!input.commandComplete) return CallDecision.Blocked(BlockReason.INCOMPLETE_COMMAND)
        if (input.priorAttempts > 0) return CallDecision.Blocked(BlockReason.ALREADY_ATTEMPTED)

        val found = when (val match = input.match) {
            is MatchResult.Found -> match
            is MatchResult.NotFound -> return CallDecision.Blocked(BlockReason.NOT_FOUND)
            is MatchResult.Ambiguous -> return CallDecision.Blocked(BlockReason.AMBIGUOUS)
            is MatchResult.NoNumber -> return CallDecision.Blocked(BlockReason.NO_NUMBER, contact = match.contact)
            is MatchResult.NeedChoice ->
                return CallDecision.Blocked(BlockReason.NEEDS_PREFERRED_NUMBER, contact = match.contact)
        }
        val contact = found.contact
        val phone = found.phone
        if (FelixLogic.telUri(phone) == null) {
            return CallDecision.Blocked(BlockReason.INVALID_NUMBER, contact, phone)
        }
        if (FelixLogic.isBlockedForAutoCall(phone)) {
            return CallDecision.Blocked(BlockReason.BLOCKED_NUMBER, contact, phone)
        }
        if (!input.autoCallEnabled) return CallDecision.Blocked(BlockReason.AUTO_CALL_DISABLED, contact, phone)
        if (!input.callPermissionGranted) return CallDecision.Blocked(BlockReason.PERMISSION_MISSING, contact, phone)
        return CallDecision.Call(contact, phone)
    }
}

data class DispatchRequest(
    val commandText: String,
    val wakeEventId: Long?,
    val contacts: List<FelixContact>,
    val contactsPermissionGranted: Boolean,
    val preferredNumbers: Map<String, String>,
    val autoCallEnabled: Boolean,
    val callPermissionGranted: Boolean,
    val soundMatching: Boolean = true,
    /** Lower-ranked recognizer guesses, tried in order when the top guess matches nothing. */
    val alternatives: List<String> = emptyList()
)

sealed interface DispatchResult {
    data class NotACommand(val message: String) : DispatchResult
    data class Blocked(
        val reason: BlockReason,
        val spokenName: String,
        val contact: FelixContact?,
        val phone: String?
    ) : DispatchResult
    data class CallRequested(val contact: FelixContact, val phone: String, val spokenName: String) : DispatchResult
    data class CallFailed(val contact: FelixContact, val phone: String, val outcome: CallOutcome, val spokenName: String) : DispatchResult
}

/**
 * Turns one final recognized command into at most one call attempt.
 * A wake event can never produce a second attempt, and a failed attempt is never retried.
 */
class CommandDispatcher(private val executor: CallExecutor) {

    private val attemptedWakeIds = HashSet<Long>()

    fun dispatch(request: DispatchRequest): DispatchResult {
        val topParsed = FelixLogic.parseCommand(request.commandText)
        val selected = FelixLogic.selectCommand(
            listOf(request.commandText) + request.alternatives,
            request.contacts,
            request.preferredNumbers,
            request.soundMatching
        ) ?: return DispatchResult.NotACommand(
            (topParsed as? ParseResult.Err)?.message ?: "I didn't catch that."
        )
        if (!request.contactsPermissionGranted) {
            return DispatchResult.Blocked(BlockReason.CONTACTS_PERMISSION_MISSING, selected.name, null, null)
        }
        val wakeId = request.wakeEventId
        val priorAttempts = if (wakeId != null && wakeId in attemptedWakeIds) 1 else 0
        val match = selected.match
        val parsed = selected.name
        val input = CallPolicy.Input(
            wakeEventId = wakeId,
            commandComplete = true,
            match = match,
            autoCallEnabled = request.autoCallEnabled,
            callPermissionGranted = request.callPermissionGranted,
            priorAttempts = priorAttempts
        )
        return when (val decision = CallPolicy.decide(input)) {
            is CallDecision.Blocked ->
                DispatchResult.Blocked(decision.reason, parsed, decision.contact, decision.phone)
            is CallDecision.Call -> {
                if (wakeId != null) attemptedWakeIds += wakeId
                when (val outcome = executor.placeCall(decision.phone)) {
                    CallOutcome.Sent -> DispatchResult.CallRequested(decision.contact, decision.phone, parsed)
                    else -> DispatchResult.CallFailed(decision.contact, decision.phone, outcome, parsed)
                }
            }
        }
    }
}
