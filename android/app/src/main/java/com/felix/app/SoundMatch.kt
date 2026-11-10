package com.felix.app

import java.text.Normalizer
import java.util.Locale

/**
 * Decides whether two names sound alike, for contact matching only.
 *
 * Speech recognizers regularly return a plausible spelling of what was said
 * ("satwinder" for Satvinder, "yesh" for Yash), so an exact string match rejects
 * the right contact. This compares a canonical sound form instead, with a strict
 * budget: total cost across the whole name must stay within [MAX_CHANGES].
 *
 * Deliberate limits, all tested:
 * - Only substitutions from a documented confusion table count; anything else rejects.
 * - Insertions and deletions always reject (Ankit is not Ankita, Sam is not Samir).
 * - A one-word query only compares against first names, and only when word
 *   counts line up ("Kumar" never matches a surname).
 * - Short skeletons still collide on vowels alone ("Sam"/"Sum"), so a sound
 *   match that is not unique is ambiguous and never calls.
 */
object SoundMatch {

    const val MAX_CHANGES = 1

    private val VOWELS = setOf('a', 'e', 'i', 'o', 'u')

    /**
     * Single-letter swaps that recognizers and transliteration mix up.
     * Anything not listed here (d/n, k/b, t/r, …) rejects the pair outright.
     */
    private val ANYWHERE_SWAPS = setOf(
        setOf('b', 'v'), // Vikram/Bikram, kharb/kharv
        setOf('b', 'g'), // kharb/karg
        setOf('v', 'g'),
        setOf('s', 'z'),
        setOf('j', 'z'), // Zaheer/Jaheer
        setOf('k', 'g'),
        setOf('d', 't'),
        setOf('b', 'p')
    )

    /** Split a name into normalized sound words. */
    fun words(name: String): List<String> {
        val ascii = Normalizer.normalize(name.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        return ascii.split(Regex("[\\s\\-._/]+")).map { normalizeWord(it) }.filter { it.isNotEmpty() }
    }

    /**
     * Canonical sound form of one word: digraphs collapsed (ph→f, sh→s, ch/kh→k,
     * aspirates→plain, ck→k, x→ks, qu→kv), c→s before e/i/y else c→k, w→v, z→j,
     * non-initial y→i, double consonants and double vowels reduced, leftover h dropped.
     */
    fun normalizeWord(word: String): String {
        val lower = word.lowercase(Locale.ROOT)
        val out = StringBuilder()
        var i = 0
        fun emit(ch: Char) {
            if (out.isNotEmpty() && out[out.length - 1] == ch && ch !in VOWELS) return
            out.append(ch)
        }
        fun emitVowelPair(ch: Char) {
            out.append(
                when (ch) {
                    'e', 'i' -> 'i'
                    'o', 'u' -> 'u'
                    else -> 'a'
                }
            )
        }
        while (i < lower.length) {
            val two = if (i + 1 < lower.length) lower.substring(i, i + 2) else ""
            if (two.length == 2 && two[0] == two[1] && two[0] in VOWELS) {
                emitVowelPair(two[0])
                i += 2
                continue
            }
            when (two) {
                "ph" -> { emit('f'); i += 2; continue }
                "sh" -> { emit('s'); i += 2; continue }
                "ch", "kh" -> { emit('k'); i += 2; continue }
                "th" -> { emit('t'); i += 2; continue }
                "dh" -> { emit('d'); i += 2; continue }
                "bh" -> { emit('b'); i += 2; continue }
                "gh" -> { emit('g'); i += 2; continue }
                "wh" -> { emit('v'); i += 2; continue }
                "ck" -> { emit('k'); i += 2; continue }
                "qu" -> { emit('k'); emit('v'); i += 2; continue }
            }
            when (val c = lower[i]) {
                'x' -> { emit('k'); emit('s') }
                'q' -> emit('k')
                'c' -> emit(if (i + 1 < lower.length && lower[i + 1] in "eiy") 's' else 'k')
                'w' -> emit('v')
                'z' -> emit('j')
                'y' -> if (i == 0) emit('y') else emit('i')
                'h' -> if (i == 0) emit('h') // other h's are silent once digraphs are consumed
                else -> if (c.isLetter()) emit(c)
            }
            i++
        }
        return out.toString()
    }

    /** Sound cost between two normalized words, or null when they cannot be the same name. */
    fun wordChanges(a: String, b: String): Int? {
        if (a.length != b.length) return null
        var cost = 0
        for (index in a.indices) {
            val x = a[index]
            val y = b[index]
            if (x == y) continue
            val vowelSwap = x in VOWELS && y in VOWELS
            val tableSwap = setOf(x, y) in ANYWHERE_SWAPS
            if (!vowelSwap && !tableSwap) return null
            cost++
            if (cost > MAX_CHANGES) return null
        }
        return cost
    }

    /**
     * Sound cost between a spoken name and a contact name, or null when they
     * cannot be the same person. A one-word query compares against the first
     * name only; multi-word queries need the same word count.
     */
    fun changes(spoken: String, candidate: String): Int? {
        val spokenWords = words(spoken)
        val candidateWords = words(candidate)
        if (spokenWords.isEmpty() || candidateWords.isEmpty()) return null
        if (spokenWords.size == 1 && candidateWords.size > 1) {
            val first = spokenWords[0]
            if (first.length < 2) return null
            val cost = wordChanges(first, candidateWords[0]) ?: return null
            return if (cost <= MAX_CHANGES) cost else null
        }
        if (spokenWords.size != candidateWords.size) return null
        if (spokenWords.sumOf { it.length } < 2) return null
        var total = 0
        for ((spokenWord, candidateWord) in spokenWords.zip(candidateWords)) {
            total += wordChanges(spokenWord, candidateWord) ?: return null
            if (total > MAX_CHANGES) return null
        }
        return total
    }

    fun soundsLike(spoken: String, candidate: String): Boolean = changes(spoken, candidate) != null
}
