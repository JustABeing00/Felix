package com.felix.app

/** Every state hands-free mode can report. The UI maps these to labels and buttons. */
enum class HandsFreePhase {
    INACTIVE,
    REQUESTING_PERMISSIONS,
    STARTING,
    LISTENING_FOR_WAKE,
    WAKE_DETECTED,
    RECOGNIZING_COMMAND,
    RESOLVING_CONTACT,
    PLACING_CALL,
    CALL_REQUEST_SENT,
    CALL_NOT_PLACED,
    CONTACT_NOT_FOUND,
    AMBIGUOUS_CONTACT,
    COMMAND_NOT_UNDERSTOOD,
    MIC_UNAVAILABLE,
    SPEECH_UNAVAILABLE,
    WAKE_MODEL_NOT_CONFIGURED,
    STOPPED,
    ERROR
}

data class HandsFreeUiState(val phase: HandsFreePhase, val message: String) {
    companion object {
        val INACTIVE = HandsFreeUiState(HandsFreePhase.INACTIVE, "Hands-free mode is off.")
    }
}

/** Raised when hands-free mode cannot start or keep running, with the state to show the user. */
class HandsFreeFailure(val phase: HandsFreePhase, message: String) : Exception(message)

interface Releasable {
    fun release()
}

/**
 * Owns resources that must be released exactly once. Releases run newest first, and a failing
 * release does not stop the others. Anything added after release is released immediately.
 */
class ResourceGroup {

    private val items = ArrayList<Releasable>()
    private var released = false

    @Synchronized
    fun add(resource: Releasable) {
        if (released) {
            runCatching { resource.release() }
        } else {
            items.add(resource)
        }
    }

    @Synchronized
    fun releaseAll(): List<Throwable> {
        if (released) return emptyList()
        released = true
        val failures = ArrayList<Throwable>()
        for (resource in items.asReversed()) {
            try {
                resource.release()
            } catch (t: Throwable) {
                failures.add(t)
            }
        }
        items.clear()
        return failures
    }
}

/** What hands-free mode needs before it may claim to be listening. */
data class SetupReport(
    val speechModelPresent: Boolean,
    val commandRecognizerAvailable: Boolean
) {
    val ready: Boolean
        get() = speechModelPresent && commandRecognizerAvailable

    fun problems(): List<String> = buildList {
        if (!speechModelPresent) {
            add(
                "Wake-word model not configured: the offline speech model is missing from " +
                    "app/src/main/assets. Rebuild from the project, see the README."
            )
        }
        if (!commandRecognizerAvailable) {
            add(
                "Speech recognition isn't available on this phone. Hands-free mode needs the " +
                    "Google app or another speech service."
            )
        }
    }
}
