package audio.own.music.library.db

/** What the catalogue's searches are asked: a LIKE pattern, or the search table's query. */
object CatalogueSearch {
    /** Fewer letters than this are looked for anywhere (LIKE); more, as word prefixes. */
    const val MIN_WORD_SEARCH = 3

    private val NOT_WORD = Regex("[^\\p{L}\\p{M}\\p{N}]+")

    /** "%text%", with LIKE's own characters escaped by '\'. */
    fun like(text: String): String = "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

    /**
     * Every word typed as a prefix ("night ra" → `night* ra*`), all of them required; null when
     * the text is too short or has no letters or digits, for [like] instead. Only letters and
     * digits are kept, so nothing typed reads as the query syntax's operators or quotes; single
     * letters beside longer words are left out.
     */
    fun match(text: String): String? {
        if (text.trim().length < MIN_WORD_SEARCH) return null
        val all = text.lowercase().split(NOT_WORD).filter { it.isNotEmpty() }
        // A one-letter prefix reads most of the index; beside longer words it only narrows a little.
        val words = all.filter { it.length > 1 }.ifEmpty { return null }
        return words.joinToString(" ") { "$it*" }
    }
}
