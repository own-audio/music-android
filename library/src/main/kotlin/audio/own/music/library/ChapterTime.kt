package audio.own.music.library

import audio.own.music.library.model.SongChapter
import java.util.Locale

/** A chapter's start as people write it: `9:26`, `1:02:03`, or plain seconds. Ported from music-ios ChapterTime.swift. */
object ChapterTime {
    fun format(ms: Long): String {
        val total = maxOf(0, ms / 1000)
        val (h, m, s) = Triple(total / 3600, total / 60 % 60, total % 60)
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
    }

    /** Milliseconds; null for anything that isn't a time. */
    fun parse(text: String): Long? {
        val parts = text.trim().split(':')
        if (parts.size !in 1..3) return null
        var secs = 0.0
        for ((i, part) in parts.withIndex()) {
            val value = part.toDoubleOrNull() ?: return null
            if (value < 0 || value.isNaN() || (i > 0 && value >= 60)) return null
            secs = secs * 60 + value
        }
        return Math.round(secs * 1000)
    }
}

/** The concert editor's rows, checked before saving. */
object ChapterDraft {
    data class Row(val title: String, val time: String)

    sealed interface Outcome {
        /** In order, ready for [ConcertSource.saveChapters]. */
        data class Ready(val chapters: List<SongChapter>) : Outcome

        /** "‹time› is not a time. Write it as 9:26 or 1:02:03." */
        data class NotATime(val time: String) : Outcome

        /** "Every song needs a name." */
        data object Unnamed : Outcome

        /** "Two songs start at the same time." */
        data object SameStart : Outcome
    }

    fun check(rows: List<Row>): Outcome {
        val chapters = ArrayList<SongChapter>()
        for (row in rows) {
            val start = ChapterTime.parse(row.time) ?: return Outcome.NotATime(row.time)
            val title = row.title.trim()
            if (title.isEmpty()) return Outcome.Unnamed
            chapters += SongChapter(title, start)
        }
        chapters.sortBy { it.startMs }
        if (chapters.zipWithNext().any { (a, b) -> a.startMs == b.startMs }) return Outcome.SameStart
        return Outcome.Ready(chapters)
    }
}
