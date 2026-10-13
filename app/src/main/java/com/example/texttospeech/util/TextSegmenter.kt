package com.example.texttospeech.util

import java.text.BreakIterator
import java.util.Locale

object TextSegmenter {
    data class Segment(val text: String, val start: Int, val endExclusive: Int)

    fun split(text: String, maxLength: Int): List<String> {
        require(maxLength >= 2)
        if (text.isBlank()) return emptyList()

        val iterator = BreakIterator.getSentenceInstance(Locale.CHINA)
        iterator.setText(text)
        val result = mutableListOf<String>()
        val pending = StringBuilder()

        fun flush() {
            val part = pending.toString().trim()
            if (part.isNotEmpty()) result.add(part)
            pending.clear()
        }

        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            var sentence = text.substring(start, end)
            while (sentence.isNotEmpty()) {
                val room = maxLength - pending.length
                if (room == 0) {
                    flush()
                    continue
                }
                var take = minOf(room, sentence.length)
                if (take < sentence.length && take > 0 &&
                    Character.isHighSurrogate(sentence[take - 1]) &&
                    Character.isLowSurrogate(sentence[take])
                ) take--
                if (take == 0) {
                    flush()
                    continue
                }
                pending.append(sentence, 0, take)
                sentence = sentence.substring(take)
                if (sentence.isNotEmpty()) flush()
            }
            start = end
            end = iterator.next()
        }
        flush()
        return result
    }

    fun splitWithRanges(text: String, maxLength: Int): List<Segment> {
        var searchFrom = 0
        return split(text, maxLength).map { piece ->
            val found = text.indexOf(piece, searchFrom)
            val start = if (found >= 0) found else searchFrom.coerceAtMost(text.length)
            val end = (start + piece.length).coerceAtMost(text.length)
            searchFrom = end
            Segment(piece, start, end)
        }
    }
}
