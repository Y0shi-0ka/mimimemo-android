package jp.mimimemo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/** カレンダーに渡す値（#111） */
class CalendarExportTest {

    @Test
    fun allDayStartsAtUtcMidnight() {
        // 2026-11-23T00:00Z
        assertEquals(1795392000000L, CalendarExport.allDayStartMillis(LocalDate.of(2026, 11, 23)))
    }

    @Test
    fun repeatBecomesRrule() {
        assertEquals("FREQ=WEEKLY", CalendarExport.rrule(Repeat.WEEKLY))
        assertEquals("FREQ=MONTHLY", CalendarExport.rrule(Repeat.MONTHLY))
        assertNull(CalendarExport.rrule(null))
    }
}
