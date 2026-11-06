package app.ivynote.core

/** A heading of a note body, for the outline. [line] is 0-based. */
data class Heading(val level: Int, val text: String, val line: Int)

/** The web client's `outline()` in `packages/core/src/markdown/render.ts` (guide 9.3). */
object Outline {
    private val pattern = Regex("^(#{1,6})\\s+(.+?)\\s*#*\\s*$")

    /** ATX headings (`#` to `######`), outside fenced code blocks. */
    fun headings(body: String): List<Heading> {
        val out = ArrayList<Heading>()
        var inFence = false
        for ((i, raw) in body.split('\n').withIndex()) {
            val line = raw.removeSuffix("\r")
            val trimmed = line.trimStart(' ', '\t')
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) inFence = !inFence
            if (inFence) continue
            val m = pattern.matchEntire(line) ?: continue
            out += Heading(m.groupValues[1].length, m.groupValues[2], i)
        }
        return out
    }

    /** The heading whose section holds [top], the line at the top of the view. */
    fun current(headings: List<Heading>, top: Int): Int? = headings.lastOrNull { it.line <= top }?.line
}
