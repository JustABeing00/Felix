package com.felix.app

/**
 * Felix shared logic: command parser, contact matcher and phone utilities.
 * Pure Kotlin with no Android dependencies, so every rule is covered by JVM unit tests.
 */

data class FelixContact(
    val id: String,
    val name: String,
    val phones: List<String>
)

sealed interface ParseResult {
    data class Ok(val name: String) : ParseResult
    data class Err(val reason: String, val message: String) : ParseResult
}

sealed interface MatchResult {
    data class Found(val contact: FelixContact, val phone: String, val bySound: Boolean = false) : MatchResult
    data class Ambiguous(val matches: List<FelixContact>) : MatchResult
    data class NotFound(val query: String) : MatchResult
    data class NoNumber(val contact: FelixContact) : MatchResult
    data class NeedChoice(val contact: FelixContact, val phones: List<String>) : MatchResult
}

object FelixLogic {

    private val validPhone = Regex("^\\+?\\d{7,15}\$")
    private val phoneFormat = Regex("^[+(\\d][\\d\\s\\-().]*\$")
    private val nonNameCharacters = Regex("[^\\p{L}\\p{N}'\\s]")
    private val whitespace = Regex("\\s+")

    /** Words that may come before "call": the wake word and politeness. Never part of a name. */
    private val leadingFillers = setOf(
        "felix", "please", "hey", "hi", "hello", "okay", "ok", "can", "could", "would", "will", "you"
    )

    /** Numbers Felix must never dial automatically. */
    private val emergencyNumbers = setOf("000", "100", "101", "102", "108", "110", "112", "118", "119", "911", "999")

    fun normalizeWhitespace(s: String): String = s.replace(whitespace, " ").trim()

    /**
     * Parses "Felix, call Yash", "Please call Rahul" or "Call Dad." into a contact name.
     * The call action must be the first word after the filler words; everything after it is the name.
     */
    fun parseCommand(raw: String?): ParseResult {
        val text = normalizeWhitespace(raw.orEmpty())
        if (text.isEmpty()) {
            return ParseResult.Err("empty", "I didn't catch a command. Try “Call Yash”.")
        }
        val tokens = normalizeWhitespace(text.replace(nonNameCharacters, " "))
            .split(" ")
            .filter { it.isNotEmpty() }

        var index = 0
        while (index < tokens.size && tokens[index].lowercase() in leadingFillers) index++
        if (index >= tokens.size) {
            return ParseResult.Err("empty", "Who should I call? Try “Call Yash”.")
        }
        if (!tokens[index].equals("call", ignoreCase = true)) {
            return ParseResult.Err("unsupported", "I only understand call commands yet. Try “Call Yash”.")
        }
        val name = tokens.drop(index + 1)
            .filterNot { it.equals("please", ignoreCase = true) }
            .joinToString(" ")
        if (name.isEmpty()) {
            return ParseResult.Err("empty", "Who should I call? Try “Call Yash”.")
        }
        if (!name.any { it.isLetter() }) {
            return ParseResult.Err("unsupported", "That doesn't look like a contact name. Try “Call Yash”.")
        }
        return ParseResult.Ok(name)
    }

    /** Validates a stored or spoken number. Returns digits with an optional leading +, or null. */
    fun normalizePhone(raw: String?): String? {
        if (raw == null || raw.isBlank()) return null
        val trimmed = raw.trim()
        if (!phoneFormat.matches(trimmed)) return null
        val digits = trimmed.filter { it.isDigit() }
        if (digits.length !in 7..15) return null
        return (if (trimmed.startsWith("+")) "+" else "") + digits
    }

    /** Builds a tel: URI only from an already-validated number. */
    fun telUri(normalizedPhone: String?): String? {
        val value = normalizedPhone?.trim() ?: return null
        return if (validPhone.matches(value)) "tel:$value" else null
    }

    /** Numbers Felix refuses to dial automatically: emergency services and short codes. */
    fun isBlockedForAutoCall(normalizedPhone: String): Boolean {
        val digits = normalizedPhone.filter { it.isDigit() }
        return digits.length < 7 || digits in emergencyNumbers
    }

    /**
     * Safe, predictable matching. Never guesses:
     * 1. Exact full-name match always wins, even when another contact sounds alike.
     * 2. Otherwise, when sound matching is on, the unique sound-alike wins;
     *    two or more sound-alikes are ambiguous and never call.
     * 3. With sound matching off, only an exact first-name match counts.
     * A saved preferred number decides multi-number contacts; without one, the user is asked.
     */
    fun matchContact(
        queryName: String?,
        contacts: List<FelixContact>,
        preferredNumbers: Map<String, String> = emptyMap(),
        soundMatching: Boolean = true
    ): MatchResult {
        val query = normalizeWhitespace(queryName.orEmpty())
        if (query.isEmpty() || contacts.isEmpty()) {
            return MatchResult.NotFound(queryName.orEmpty())
        }

        val exact = contacts.filter { normalizeWhitespace(it.name).equals(query, ignoreCase = true) }
        if (exact.size == 1) return resolvePhones(exact[0], preferredNumbers, bySound = false)
        if (exact.size > 1) return MatchResult.Ambiguous(exact)

        if (soundMatching) {
            val sounding = contacts.filter { SoundMatch.changes(query, it.name) != null }
            if (sounding.size == 1) {
                val only = sounding[0]
                val normalizedOnly = normalizeWhitespace(only.name)
                val exactFirstName = !query.contains(' ') &&
                    normalizedOnly.substringBefore(' ').equals(query, ignoreCase = true)
                return resolvePhones(only, preferredNumbers, bySound = !exactFirstName)
            }
            if (sounding.size > 1) return MatchResult.Ambiguous(sounding)
            return MatchResult.NotFound(queryName.orEmpty())
        }

        if (!query.contains(' ')) {
            val firstNameMatches = contacts.filter {
                normalizeWhitespace(it.name).substringBefore(' ').equals(query, ignoreCase = true)
            }
            if (firstNameMatches.size == 1) return resolvePhones(firstNameMatches[0], preferredNumbers)
            if (firstNameMatches.size > 1) return MatchResult.Ambiguous(firstNameMatches)
        }
        return MatchResult.NotFound(queryName.orEmpty())
    }

    /**
     * Note shown when the matched contact is spelled differently from what was
     * heard, e.g. ` (heard “satwinder kumar”)`. Empty when both already agree.
     */
    fun heardNote(spokenName: String, matchedName: String): String {
        val spoken = normalizeWhitespace(spokenName)
        if (spoken.isEmpty() || spoken.equals(normalizeWhitespace(matchedName), ignoreCase = true)) return ""
        return " (heard “$spoken”)"
    }

    private fun resolvePhones(
        contact: FelixContact,
        preferredNumbers: Map<String, String>,
        bySound: Boolean = false
    ): MatchResult {
        val phones = contact.phones.filter { validPhone.matches(it) }
        if (phones.isEmpty()) return MatchResult.NoNumber(contact)
        val preferred = preferredNumbers[contact.id]
        if (preferred != null && preferred in phones) return MatchResult.Found(contact, preferred, bySound)
        if (phones.size == 1) return MatchResult.Found(contact, phones[0], bySound)
        return MatchResult.NeedChoice(contact, phones)
    }
}
