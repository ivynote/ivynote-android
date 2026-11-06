package app.ivynote.core

/**
 * Local full-text search (guide 11.5), as the web client's `search.ts`:
 * every term must match (AND), case-insensitive substring (works for
 * Chinese); title hits weigh more than body hits; recent notes rank higher.
 */
object Search {
    data class Hit(val note: Note, val score: Double, val snippet: String, val ranges: List<IntRange>)

    private const val YEAR_MS = 365L * 24 * 3600 * 1000

    fun terms(query: String): List<String> = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }

    fun run(notes: Collection<Note>, query: String, now: Millis = nowMillis(), limit: Int = 50): List<Hit> {
        val terms = terms(query)
        if (terms.isEmpty()) return emptyList()
        val hits = ArrayList<Hit>()
        for (n in notes) {
            if (n.isTrashed || n.deleted) continue
            val title = n.title.lowercase()
            val body = n.body.lowercase()
            var score = 0.0
            var all = true
            for (t in terms) {
                val inTitle = title.contains(t)
                val count = countOf(body, t)
                if (!inTitle && count == 0) { all = false; break }
                if (inTitle) score += when {
                    title == t -> 12.0
                    title.startsWith(t) -> 8.0
                    else -> 5.0
                }
                if (count > 0) score += 1 + minOf(3.0, count / 2.0)
            }
            if (!all) continue
            score += maxOf(0.0, 1 - (now - n.updatedAt).toDouble() / YEAR_MS)
            val (snippet, ranges) = snippet(n.body, terms)
            hits += Hit(n, score, snippet, ranges)
        }
        return hits.sortedWith(compareByDescending<Hit> { it.score }.thenByDescending { it.note.updatedAt }).take(limit)
    }

    private fun countOf(text: String, term: String): Int {
        var i = text.indexOf(term)
        var n = 0
        while (i >= 0) {
            n++
            i = text.indexOf(term, i + term.length)
        }
        return n
    }

    /** 80 characters from 20 before the first hit, with `…` where cut, and the hit ranges in it. */
    fun snippet(body: String, terms: List<String>): Pair<String, List<IntRange>> {
        val flat = body.replace(Regex("\\s+"), " ").trim()
        val lower = flat.lowercase()
        val first = terms.map { lower.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: 0
        val start = maxOf(0, first - 20)
        val end = minOf(flat.length, start + 80)
        val prefix = if (start > 0) "…" else ""
        val text = prefix + flat.substring(start, end) + if (end < flat.length) "…" else ""
        val ranges = ArrayList<IntRange>()
        val lowText = text.lowercase()
        for (t in terms) {
            var i = lowText.indexOf(t)
            while (i >= 0) {
                ranges += i until i + t.length
                i = lowText.indexOf(t, i + t.length)
            }
        }
        return text to ranges.sortedBy { it.first }
    }
}

object Excerpt {
    /** The first words of the body without Markdown syntax noise. */
    fun of(body: String, max: Int = 140): String {
        val out = StringBuilder()
        var fence = false
        for (line in body.lineSequence()) {
            var s = line.trim()
            if (s.startsWith("```") || s.startsWith("~~~")) { fence = !fence; continue }
            if (fence) continue
            s = s.trimStart('#', '>', '-', '*', '+', '`', ' ')
            s = s.replace("[ ] ", "").replace("[x] ", "").replace("[X] ", "")
            s = s.replace(Regex("!\\[[^\\]]*\\]\\([^)]*\\)"), "")
                .replace(Regex("\\[\\[([^\\]|#]+)(?:[#|][^\\]]*)?\\]\\]"), "$1")
                .replace(Regex("\\[([^\\]]*)\\]\\([^)]*\\)"), "$1")
                .replace(Regex("[*_~=]{1,3}"), "")
                .trim()
            if (s.isEmpty()) continue
            if (out.isNotEmpty()) out.append(' ')
            out.append(s)
            if (out.length > max) break
        }
        return out.take(max).toString()
    }

    /** CJK characters count one each, other runs of letters and digits one word (guide 9.3). */
    fun words(body: String): Int {
        val text = of(body, Int.MAX_VALUE)
        var n = 0
        var inWord = false
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            when {
                isCjk(cp) -> { n++; inWord = false }
                Character.isLetterOrDigit(cp) -> { if (!inWord) n++; inWord = true }
                else -> inWord = false
            }
            i += Character.charCount(cp)
        }
        return n
    }

    private fun isCjk(cp: Int): Boolean {
        val s = Character.UnicodeScript.of(cp)
        return s == Character.UnicodeScript.HAN || s == Character.UnicodeScript.HIRAGANA ||
            s == Character.UnicodeScript.KATAKANA || s == Character.UnicodeScript.HANGUL
    }
}
