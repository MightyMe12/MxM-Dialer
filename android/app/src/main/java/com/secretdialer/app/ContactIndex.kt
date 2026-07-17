package com.secretdialer.app

import android.net.Uri
import java.text.Normalizer
import java.util.Locale

data class ContactInfo(
    val id: Long,
    val name: String,
    val number: String,
    val photoUri: Uri?
)

data class IndexedContact(
    val info: ContactInfo,
    val nameNormalized: String,
    val numberDigits: String,
    val t9Tokens: List<String>
)

class ContactIndex private constructor(private val indexed: List<IndexedContact>) {

    val contacts: List<ContactInfo> = indexed.map { it.info }

    private val nameByDigits: Map<String, String> = buildMap {
        indexed.forEach { entry ->
            if (entry.numberDigits.isNotEmpty()) {
                put(entry.numberDigits, entry.info.name)
            }
        }
    }

    fun nameForNumber(rawNumber: String): String? =
        nameByDigits[rawNumber.filter { it.isDigit() }]

    fun filterByText(rawQuery: String): List<ContactInfo> {
        val query = rawQuery.trim()
        if (query.isEmpty()) return contacts

        val queryLower = normalize(query)
        val queryDigits = query.filter { it.isDigit() }
        val queryLetters = queryLower.filter { it.isLetter() }
        val digitsOnly = query.isNotEmpty() && query.all { it.isDigit() || it == '+' || it == ' ' }

        return indexed.asSequence()
            .filter { entry ->
                val nameMatch = queryLetters.isNotEmpty() && entry.nameNormalized.contains(queryLetters)
                val numberMatch = queryDigits.isNotEmpty() && entry.numberDigits.contains(queryDigits)
                val t9Match = digitsOnly && queryDigits.isNotEmpty() &&
                    matchesKeypad(entry, queryDigits)
                nameMatch || numberMatch || t9Match
            }
            .map { it.info }
            .toList()
    }

    fun filterByKeypad(rawDigits: String, limit: Int = 8, frequencies: Map<String, Int> = emptyMap()): List<ContactInfo> {
        val digits = rawDigits.filter { it.isDigit() }
        if (digits.isEmpty()) return emptyList()

        val out = ArrayList<IndexedContact>()
        for (entry in indexed) {
            if (matchesKeypad(entry, digits)) {
                out.add(entry)
            }
        }

        // Sort by frequency count (descending), then alphabetically by name
        out.sortWith(Comparator { a, b ->
            val freqA = frequencies[a.numberDigits] ?: 0
            val freqB = frequencies[b.numberDigits] ?: 0
            if (freqA != freqB) {
                freqB.compareTo(freqA)
            } else {
                a.info.name.compareTo(b.info.name, ignoreCase = true)
            }
        })

        return out.map { it.info }.take(limit)
    }

    private fun matchesKeypad(entry: IndexedContact, digits: String): Boolean {
        if (entry.numberDigits.contains(digits) || entry.numberDigits.startsWith(digits)) {
            return true
        }
        for (token in entry.t9Tokens) {
            if (token.startsWith(digits)) return true
        }
        return false
    }

    companion object {
        private val LETTER_TO_DIGIT = buildMap {
            putAll("abc".map { it to '2' })
            putAll("def".map { it to '3' })
            putAll("ghi".map { it to '4' })
            putAll("jkl".map { it to '5' })
            putAll("mno".map { it to '6' })
            putAll("pqrs".map { it to '7' })
            putAll("tuv".map { it to '8' })
            putAll("wxyz".map { it to '9' })
        }

        val EMPTY = ContactIndex(emptyList())

        fun build(contacts: List<ContactInfo>): ContactIndex {
            val indexed = contacts.map { contact ->
                val nameNorm = normalize(contact.name)
                val numberDigits = contact.number.filter { it.isDigit() }
                val tokens = ArrayList<String>(4)
                contact.name.split(Regex("\\s+")).filter { it.isNotBlank() }.forEach { word ->
                    val t9 = nameWordToT9(word)
                    if (t9.isNotEmpty()) tokens.add(t9)
                }
                val compact = nameWordToT9(contact.name.replace(Regex("\\s+"), ""))
                if (compact.isNotEmpty()) tokens.add(compact)
                IndexedContact(contact, nameNorm, numberDigits, tokens)
            }
            return ContactIndex(indexed)
        }

        private fun nameWordToT9(word: String): String {
            return normalize(word)
                .filter { it.isLetter() }
                .map { LETTER_TO_DIGIT[it] ?: '0' }
                .joinToString("")
        }

        private fun normalize(text: String): String {
            val lowered = text.lowercase(Locale.getDefault())
            val decomposed = Normalizer.normalize(lowered, Normalizer.Form.NFD)
            return decomposed.replace(Regex("\\p{M}+"), "")
        }
    }
}
