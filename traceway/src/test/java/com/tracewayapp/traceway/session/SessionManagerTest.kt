package com.tracewayapp.traceway.session

import com.tracewayapp.traceway.internal.ManualScheduler
import com.tracewayapp.traceway.persistence.StateStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionManagerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scheduler = ManualScheduler(nowMs = 1_000_000)
    private val events = ArrayList<String>()

    private fun manager(store: StateStore, locked: (() -> Unit) -> Unit = { it() }) = SessionManager(
        store = store,
        scheduler = scheduler,
        onStart = { s, resumed -> events.add("${if (resumed) "resume" else "start"}:${s.id}") },
        onEnd = { s, endedAt -> events.add("end:${s.id}@$endedAt") },
        locked = locked,
    )

    @Test
    fun waitsForActivityAfterTimeoutEvenAcrossAnIdleHour() {
        val m = manager(StateStore(null)).apply { start() }
        val first = m.currentId!!
        scheduler.nowMs += 60_000
        m.markActivity()
        val lastActivity = scheduler.nowMs
        scheduler.nowMs += SessionManager.DEFAULT_INACTIVITY_MS
        m.check()
        repeat(120) {
            scheduler.nowMs += 30_000
            m.check()
        }
        assertNull(m.currentId)
        assertEquals(listOf("start:$first", "end:$first@$lastActivity"), events)
        m.markActivity()
        assertNotEquals(first, m.currentId)
        assertEquals(listOf("start:$first", "end:$first@$lastActivity", "start:${m.currentId}"), events)
        m.stop()
    }

    @Test
    fun endsAtTheMaximumDurationEvenWithActivity() {
        val m = manager(StateStore(null)).apply { start() }
        val first = m.currentId!!
        repeat(13) {
            scheduler.nowMs += 5 * 60_000
            m.markActivity()
        }
        assertNotEquals(first, m.currentId)
        assertEquals("end:$first@${1_000_000 + SessionManager.DEFAULT_MAX_DURATION_MS}", events[1])
        m.stop()
    }

    @Test
    fun aRestartInsideTheInactivityWindowResumesTheSession() {
        val store = StateStore(null)
        val a = manager(store).apply { start() }
        val id = a.currentId!!
        assertEquals(0, a.nextSegmentIndex())
        a.stop()
        scheduler.nowMs += 60_000
        val b = manager(store).apply { start() }
        assertEquals(id, b.currentId)
        assertEquals(1, b.nextSegmentIndex())
        assertEquals("resume:$id", events.last())
        b.stop()
        scheduler.nowMs += SessionManager.DEFAULT_INACTIVITY_MS
        val c = manager(store).apply { start() }
        assertNotEquals(id, c.currentId)
        assertEquals(0, c.nextSegmentIndex())
        c.stop()
    }

    @Test
    fun theSessionIsResumedFromDiskAfterAProcessRestart() {
        val dir = tmp.newFolder("state")
        val first = StateStore(dir).apply { load() }
        val a = manager(first).apply { start() }
        val id = a.currentId!!
        a.nextSegmentIndex()
        a.nextSegmentIndex()
        first.flush()
        // The process dies without stop().
        scheduler.nowMs += 120_000
        val second = StateStore(dir).apply { load() }
        val b = manager(second).apply { start() }
        assertEquals(id, b.currentId)
        assertEquals(2, b.nextSegmentIndex())
    }

    @Test
    fun thePeriodicCheckEndsAnIdleSessionThroughTheLock() {
        var lockedRuns = 0
        val m = manager(StateStore(null)) { block ->
            lockedRuns++
            block()
        }.apply { start() }
        val first = m.currentId!!
        scheduler.advance(SessionManager.DEFAULT_INACTIVITY_MS + 30_000)
        assertNull(m.currentId)
        assertEquals("end:$first@1000000", events.last())
        assertEquals(((SessionManager.DEFAULT_INACTIVITY_MS + 30_000) / SessionManager.CHECK_INTERVAL_MS).toInt(), lockedRuns)
        m.stop()
        scheduler.advance(10 * 60_000)
        assertEquals(((SessionManager.DEFAULT_INACTIVITY_MS + 30_000) / SessionManager.CHECK_INTERVAL_MS).toInt(), lockedRuns)
        assertEquals(0, scheduler.pendingTimers)
    }

    @Test
    fun activityBeforeStartIsIgnored() {
        val m = manager(StateStore(null))
        m.markActivity()
        assertNull(m.currentId)
        m.start()
        assertNotNull(m.currentId)
        m.start()
        assertEquals(1, events.size)
        m.stop()
    }
}
