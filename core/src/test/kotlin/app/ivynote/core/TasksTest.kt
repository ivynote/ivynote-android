package app.ivynote.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TasksTest {
    private val sealed = "```ivy-encrypted\nThis note is encrypted.\nv: 1\nkdf: scrypt N=32768 r=8 p=1\nsalt: AA==\nnonce: AA==\ndata: AA==\n```\n"

    @Test fun detectsProtectedNotes() {
        assertTrue(NoteLock.isEncrypted(sealed))
        assertFalse(NoteLock.isEncrypted("x\n$sealed"))
        assertFalse(NoteLock.isEncrypted("plain"))
    }

    @Test fun parsesTasks() {
        val ts = Tasks.parse("- [ ] 交房租 📅 2026-11-15 09:00 🔁 every month\n```\n- [ ] code 📅 2026-11-01\n```\n* [x] done ✅ 2026-11-01 📅 2026-10-31\n- [ ] bad 📅 2026-02-30\n- [ ] plain")
        assertEquals(4, ts.size)
        assertEquals("交房租", ts[0].text)
        assertEquals(LocalDate.of(2026, 11, 15), ts[0].due!!.date)
        assertEquals(LocalTime.of(9, 0), ts[0].due!!.time)
        assertEquals(Tasks.Repeat(1, "month", "every month"), ts[0].repeat)
        assertTrue(ts[1].done)
        assertEquals(4, ts[1].line)
        assertNull(ts[2].due)
        assertNull(ts[3].due)
        assertEquals(Tasks.Repeat(2, "week", "every 2 weeks"), Tasks.parseRepeat("every 2 weeks"))
        assertNull(Tasks.parseRepeat("sometimes"))
    }

    @Test fun listsRemindersAndAgenda() {
        val zone = ZoneId.of("Asia/Shanghai")
        val notes = listOf(
            Note("a", title = "生活", body = "- [ ] 早 📅 2026-11-09 08:00\n- [ ] 全天 📅 2026-11-09\n- [ ] 过期 📅 2026-11-01\n- [x] 完成 📅 2026-11-09 ✅ 2026-11-09"),
            Note("b", title = "回收", body = "- [ ] x 📅 2026-11-09 08:00", trashedAt = 5),
            Note("c", title = "秘密", body = sealed),
        )
        val from = LocalDateTime.of(2026, 11, 9, 0, 0).atZone(zone).toInstant().toEpochMilli()
        val to = LocalDateTime.of(2026, 11, 10, 0, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(listOf("早"), Tasks.reminders(notes, from, to, null, zone).map { it.text })
        val all = Tasks.reminders(notes, from, to, LocalTime.of(9, 0), zone)
        assertEquals(listOf("早", "全天"), all.map { it.text })
        assertEquals(all, Tasks.reminders(notes, from, to, LocalTime.of(9, 0), zone))
        val agenda = Tasks.agenda(notes, LocalDate.of(2026, 11, 9))
        assertEquals(listOf("过期" to -8L, "早" to 0L, "全天" to 0L), agenda.map { it.task.text to it.days })
    }

    @Test fun togglesLikeTheEditor() {
        val today = LocalDate.of(2026, 11, 15)
        assertEquals(
            "- [ ] 交房租 📅 2026-12-15 09:00 🔁 every month\n- [x] 交房租 📅 2026-11-15 09:00 🔁 every month ✅ 2026-11-15",
            Tasks.toggle("- [ ] 交房租 📅 2026-11-15 09:00 🔁 every month", 0, today)
        )
        assertEquals("- [ ] a 📅 2026-11-15", Tasks.toggle("- [x] a 📅 2026-11-15 ✅ 2026-11-15", 0, today))
        assertEquals("- [x] plain", Tasks.toggle("- [ ] plain", 0, today))
        assertEquals("text", Tasks.toggle("text", 0, today))
        assertEquals(LocalDate.of(2026, 2, 28), Tasks.nextDate(LocalDate.of(2026, 1, 31), Tasks.Repeat(1, "month", "")))
        assertEquals(LocalDate.of(2026, 11, 16), Tasks.nextDate(LocalDate.of(2026, 11, 13), Tasks.Repeat(1, "weekday", "")))
    }

    @Test fun searchSkipsProtectedBodies() {
        val notes = listOf(Note("s", title = "Secret", body = sealed))
        assertTrue(Search.run(notes, "AA==").isEmpty())
        assertEquals("", Search.run(notes, "secret").single().snippet)
    }
}
