package com.siftalpha.studio.project

/** Pure current-document search logic used by the mobile editor. */
object EditorTextSearch {
    data class Match(
        val start: Int,
        val endExclusive: Int,
    )

    fun findAll(
        text: String,
        query: String,
        caseSensitive: Boolean,
    ): List<Match> {
        if (query.isEmpty() || text.isEmpty() || query.length > text.length) return emptyList()
        val matches = mutableListOf<Match>()
        var fromIndex = 0
        while (fromIndex <= text.length - query.length) {
            val index = text.indexOf(query, fromIndex, ignoreCase = !caseSensitive)
            if (index < 0) break
            matches += Match(index, index + query.length)
            fromIndex = index + query.length.coerceAtLeast(1)
        }
        return matches
    }

    fun indexAtOrAfter(matches: List<Match>, offset: Int): Int {
        if (matches.isEmpty()) return -1
        val normalized = offset.coerceAtLeast(0)
        val index = matches.indexOfFirst { it.start >= normalized }
        return if (index >= 0) index else 0
    }

    fun stepIndex(matchCount: Int, currentIndex: Int, forward: Boolean): Int {
        if (matchCount <= 0) return -1
        if (currentIndex !in 0 until matchCount) return if (forward) 0 else matchCount - 1
        return if (forward) {
            (currentIndex + 1) % matchCount
        } else {
            (currentIndex - 1 + matchCount) % matchCount
        }
    }
}
