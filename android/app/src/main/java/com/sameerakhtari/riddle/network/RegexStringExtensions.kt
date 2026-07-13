package com.sameerakhtari.riddle.network

/** Returns the text before the first regex match, or the original text when no match exists. */
internal fun String.substringBefore(delimiter: Regex): String {
    val match = delimiter.find(this) ?: return this
    return substring(0, match.range.first)
}
