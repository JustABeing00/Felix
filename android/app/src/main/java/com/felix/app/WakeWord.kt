package com.felix.app

/** Wake-word rules, kept apart from audio and Android code so they can be unit-tested. */
object WakeWord {

    const val KEYWORD = "felix"

    /** Vosk grammar: the wake recognizer can only output "felix" or the catch-all [unk]. */
    const val GRAMMAR = "[\"felix\", \"[unk]\"]"

    private val nonLetters = Regex("[^a-z]+")

    fun containsWakeWord(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        return text.lowercase().split(nonLetters).any { it == KEYWORD }
    }
}
