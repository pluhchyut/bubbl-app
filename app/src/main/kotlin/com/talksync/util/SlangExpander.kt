package com.talksync.util

// common slang expander. cheaper than asking a model every time someone says lol.

import com.talksync.model.ExpandedToken
import java.util.Locale

object SlangExpander {
    private val dictionary = mapOf(
        "dev" to "developer",
        "imo" to "in my opinion",
        "tbh" to "to be honest",
        "lol" to "laughing out loud",
        "omg" to "oh my god",
        "brb" to "be right back",
        "afk" to "away from keyboard",
        "btw" to "by the way",
        "idk" to "I don't know",
        "ngl" to "not gonna lie",
        "smh" to "shaking my head",
        "fyi" to "for your information",
        "asap" to "as soon as possible",
        "aka" to "also known as",
        "eta" to "estimated time of arrival",
        "rn" to "right now",
        "bc" to "because",
        "w/" to "with",
        "w/o" to "without",
        "govt" to "government",
        "approx" to "approximately",
        "dept" to "department",
        "info" to "information",
    )

    fun expand(text: String): List<ExpandedToken> {
        return text.split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .map { token ->
                val stripped = token.trim().trim(',', '.', '!', '?', ';', ':', '"', '\'', '(', ')', '[', ']')
                val expanded = dictionary[stripped.lowercase(Locale.getDefault())]
                ExpandedToken(original = token, expanded = expanded)
            }
    }
}
