package app.ivynote.core

/**
 * Wiki links (`[[Title]]`, `[[Title|alias]]`, `[[Title#heading]]`), note
 * references (`ivy://note/<id>`) and attachment references
 * (`ivy://res/<id>`), computed locally so they work offline (guide 9.2).
 * A port of the web client's `packages/core/src/markdown/links.ts`.
 */
object Links {
    private val WIKI = Regex("""\[\[([^\[\]\n]+?)\]\]""")
    private val NOTE_REF = Regex("""ivy://note/([A-Za-z0-9_-]{8,64})""")
    val RES_REF = Regex("""ivy://res/([A-Za-z0-9_-]{8,64})""")
    private val FENCE = Regex("""^\s*(```|~~~)[\s\S]*?^\s*(```|~~~)\s*$""", RegexOption.MULTILINE)
    private val INLINE_CODE = Regex("`[^`\n]*`")
    private val SPACES = Regex("""\s+""")
    private val LINE_MARKS = Regex("""^[\s>#*\-+\d.)\[\]x]+""")

    /** Trimmed, runs of white space as one space, lower case. */
    fun normalizeTitle(t: String): String = t.trim().replace(SPACES, " ").lowercase()

    /** Links inside code do not count (guide 9.2). */
    fun stripCode(body: String): String = body.replace(FENCE, "").replace(INLINE_CODE, "")

    data class WikiLink(val target: String, val alias: String? = null, val heading: String? = null)

    /** `|` first, then `#`, then trim. */
    fun parseWiki(inner: String): WikiLink {
        var target = inner
        var alias: String? = null
        var heading: String? = null
        val bar = target.indexOf('|')
        if (bar >= 0) {
            alias = target.substring(bar + 1).trim()
            target = target.substring(0, bar)
        }
        val hash = target.indexOf('#')
        if (hash >= 0) {
            heading = target.substring(hash + 1).trim()
            target = target.substring(0, hash)
        }
        return WikiLink(target.trim(), alias, heading)
    }

    /** Distinct normalized link targets of a body. */
    fun wikiTargets(body: String): Set<String> =
        WIKI.findAll(stripCode(body)).mapNotNullTo(LinkedHashSet()) { m ->
            normalizeTitle(parseWiki(m.groupValues[1]).target).ifEmpty { null }
        }

    fun noteRefs(body: String): Set<String> = NOTE_REF.findAll(stripCode(body)).mapTo(LinkedHashSet()) { it.groupValues[1] }

    /** Attachment ids; unlike links these count inside code too, as on the server. */
    fun resourceRefs(body: String): Set<String> = RES_REF.findAll(body).mapTo(LinkedHashSet()) { it.groupValues[1] }

    /**
     * The live notes linking to [noteId], most recently updated first. A
     * title shared by several notes points at the most recently updated one;
     * a note never counts as its own backlink; the trash is left out.
     */
    fun backlinks(noteId: String, notes: Collection<Note>): List<Note> {
        val live = notes.filter { !it.isTrashed && !it.deleted }
        val target = live.firstOrNull { it.id == noteId } ?: return emptyList()
        val key = normalizeTitle(target.title)
        // Does [key] resolve to this note, or to a newer note of the same title?
        val owner = if (key.isEmpty()) null else live.filter { normalizeTitle(it.title) == key }.maxByOrNull { it.updatedAt }?.id
        return live.filter { n ->
            n.id != noteId && (
                (owner == noteId && key in wikiTargets(n.body)) || noteId in noteRefs(n.body)
            )
        }.sortedByDescending { it.updatedAt }
    }

    /** The line around a link to [title] in [body], links shown as their text, for backlink previews. */
    fun linkContext(body: String, title: String, noteId: String = "", max: Int = 90): String {
        val key = normalizeTitle(title)
        for (line in body.lineSequence()) {
            val byTitle = key.isNotEmpty() && WIKI.findAll(line).any { normalizeTitle(parseWiki(it.groupValues[1]).target) == key }
            val byId = noteId.isNotEmpty() && line.contains("ivy://note/$noteId")
            if (!byTitle && !byId) continue
            val text = line.replace(WIKI) { m -> parseWiki(m.groupValues[1]).let { it.alias?.ifEmpty { null } ?: it.target } }
                .replace(Regex("""\[([^\]]*)\]\(ivy://note/[^)]*\)"""), "$1")
                .replace(LINE_MARKS, "").trim()
            return if (text.length > max) text.take(max) + "…" else text
        }
        return ""
    }

    /** Replaces attachment ids in a body (an upload replacing its `local-` id, a migration). */
    fun rewriteResources(body: String, ids: Map<String, String>): String {
        if (ids.isEmpty() || !body.contains("ivy://res/")) return body
        return RES_REF.replace(body) { m -> ids[m.groupValues[1]]?.let { "ivy://res/$it" } ?: m.value }
    }

    /**
     * The Markdown that embeds an attachment (guide 7.1): a file card for
     * plain files, an embed for images, video and audio. Brackets leave the name.
     */
    fun embed(id: String, name: String, kind: String): String {
        val label = name.replace("[", "").replace("]", "").replace("\n", " ").trim().ifEmpty { "附件" }
        return if (kind == "file") "[$label](ivy://res/$id)" else "![$label](ivy://res/$id)"
    }
}
