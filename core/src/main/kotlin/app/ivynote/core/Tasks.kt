package app.ivynote.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/**
 * Password-protected notes (guide 8.4): the body is one ```ivy-encrypted
 * fence sealed by the editor page. The app never decrypts it; it only keeps
 * it out of search, excerpts, the agenda and reminders.
 */
object NoteLock {
    private val ENVELOPE = Regex("^\\s*```ivy-encrypted[ \\t]*\\n[\\s\\S]*?\\n```[ \\t]*\\s*$")

    fun isEncrypted(body: String): Boolean =
        body.trimStart().startsWith("```ivy-encrypted") && ENVELOPE.matches(body)
}

/**
 * Tasks with dates (guide 9.4), as the web client's `markdown/tasks.ts` and
 * `agenda.ts`: `- [ ] 交房租 📅 2026-11-15 09:00 🔁 every month`. Dates are
 * local to the device.
 */
object Tasks {
    data class Due(val date: LocalDate, val time: LocalTime?)
    data class Repeat(val every: Int, val unit: String, val text: String)
    data class Task(val line: Int, val text: String, val done: Boolean, val due: Due?, val repeat: Repeat?, val raw: String)

    private val TASK = Regex("^(\\s*(?:[-*+]|\\d+[.)])\\s+\\[)([ xX])(\\]\\s?)(.*)$")
    private val DUE = Regex("📅\\uFE0F?\\s*(\\d{4}-\\d{2}-\\d{2})(?:[ T](\\d{1,2}:\\d{2}))?")
    private val DONE = Regex("✅\\uFE0F?\\s*(\\d{4}-\\d{2}-\\d{2})")
    private val REPEAT = Regex("🔁\\uFE0F?\\s*([^📅✅⏳🛫➕⏫🔼🔽🔺⏬\\n]*)")
    private val RULE = Regex("^every\\s+(?:(\\d{1,3})\\s+)?(day|week|month|year)s?$")
    private val FENCE = Regex("^\\s*(```|~~~)")

    private fun date(s: String): LocalDate? = try { LocalDate.parse(s) } catch (_: DateTimeParseException) { null }

    private fun time(s: String?): LocalTime? {
        val m = Regex("^(\\d{1,2}):(\\d{2})$").find(s ?: return null) ?: return null
        val h = m.groupValues[1].toInt()
        val mi = m.groupValues[2].toInt()
        return if (h in 0..23 && mi in 0..59) LocalTime.of(h, mi) else null
    }

    fun parseRepeat(text: String): Repeat? {
        val t = text.trim().lowercase()
        if (t == "every weekday") return Repeat(1, "weekday", text.trim())
        val m = RULE.find(t) ?: return null
        val n = m.groupValues[1].ifEmpty { "1" }.toInt()
        return if (n >= 1) Repeat(n, m.groupValues[2], text.trim()) else null
    }

    /** The task lines of a body, skipping code. */
    fun parse(body: String): List<Task> {
        val out = ArrayList<Task>()
        var inFence = false
        body.split('\n').forEachIndexed { i, line ->
            if (FENCE.containsMatchIn(line)) inFence = !inFence
            if (inFence) return@forEachIndexed
            val m = TASK.find(line) ?: return@forEachIndexed
            val rest = m.groupValues[4]
            val d = DUE.find(rest)
            val due = d?.let { date(it.groupValues[1])?.let { day -> Due(day, time(it.groupValues[2].ifEmpty { null })) } }
            val rep = REPEAT.find(rest)?.let { parseRepeat(it.groupValues[1]) }
            val text = rest.replace(DUE, "").replace(DONE, "").replace(REPEAT, "").replace(Regex("\\s+"), " ").trim()
            out += Task(i, text, m.groupValues[2] != " ", due, rep, line)
        }
        return out
    }

    private fun live(n: Note) = !n.deleted && !n.isTrashed && n.body.contains("📅") && !NoteLock.isEncrypted(n.body)

    data class Reminder(val key: String, val noteId: String, val noteTitle: String, val text: String, val line: Int, val at: Millis)

    /**
     * Reminders that fire in [from, to). [allDayTime] is when a task with a
     * date but no time reminds; null: such tasks do not remind.
     */
    fun reminders(notes: Collection<Note>, from: Millis, to: Millis, allDayTime: LocalTime?, zone: ZoneId = ZoneId.systemDefault()): List<Reminder> {
        val out = ArrayList<Reminder>()
        for (n in notes) {
            if (!live(n)) continue
            for (t in parse(n.body)) {
                val due = t.due ?: continue
                if (t.done) continue
                val at = LocalDateTime.of(due.date, due.time ?: allDayTime ?: continue).atZone(zone).toInstant().toEpochMilli()
                if (at < from || at >= to) continue
                out += Reminder("${n.id}:${t.text.hashCode().toUInt().toString(36)}:${due.date}${due.time ?: ""}", n.id, n.title, t.text, t.line, at)
            }
        }
        return out.sortedBy { it.at }
    }

    data class AgendaItem(val noteId: String, val noteTitle: String, val task: Task, val days: Long)

    /** Open dated tasks across notes, soonest first (overdue at the top). */
    fun agenda(notes: Collection<Note>, today: LocalDate = LocalDate.now()): List<AgendaItem> {
        val out = ArrayList<AgendaItem>()
        for (n in notes) {
            if (!live(n)) continue
            for (t in parse(n.body)) {
                val due = t.due ?: continue
                if (t.done) continue
                out += AgendaItem(n.id, n.title, t, ChronoUnit.DAYS.between(today, due.date))
            }
        }
        return out.sortedWith(compareBy<AgendaItem> { it.task.due!!.date }.thenBy { it.task.due!!.time ?: LocalTime.MAX }.thenBy { it.noteTitle }.thenBy { it.task.line })
    }

    fun nextDate(date: LocalDate, rule: Repeat): LocalDate = when (rule.unit) {
        "day" -> date.plusDays(rule.every.toLong())
        "week" -> date.plusWeeks(rule.every.toLong())
        "month" -> date.plusMonths(rule.every.toLong())
        "year" -> date.plusYears(rule.every.toLong())
        else -> {
            var d = date.plusDays(1)
            while (d.dayOfWeek.value >= 6) d = d.plusDays(1)
            d
        }
    }

    /**
     * Checks or unchecks the task on a 0-based line, as the editor does:
     * a dated task gets ✅ and the day, and a repeating one its next
     * occurrence on the line above.
     */
    fun toggle(body: String, lineNo: Int, today: LocalDate = LocalDate.now()): String {
        val lines = body.split('\n').toMutableList()
        val line = lines.getOrNull(lineNo) ?: return body
        val m = TASK.find(line) ?: return body
        val (open, mark, close, rest) = m.destructured
        if (mark != " ") {
            lines[lineNo] = "$open $close${rest.replace(Regex("\\s*" + DONE.pattern), "")}".trimEnd()
            return lines.joinToString("\n")
        }
        val t = parse(line).firstOrNull() ?: return body
        if (t.due == null && t.repeat == null) {
            lines[lineNo] = "${open}x$close$rest"
            return lines.joinToString("\n")
        }
        val clean = rest.replace(Regex("\\s*" + DONE.pattern), "").trimEnd()
        lines[lineNo] = "${open}x$close$clean ✅ $today"
        if (t.repeat != null && t.due != null) {
            val next = nextDate(t.due.date, t.repeat)
            val marker = "📅 $next" + (t.due.time?.let { " %02d:%02d".format(it.hour, it.minute) } ?: "")
            lines.add(lineNo, "$open $close" + clean.replace(DUE, marker))
        }
        return lines.joinToString("\n")
    }
}
