package com.tracewayapp.traceway.wireframe

import com.tracewayapp.traceway.internal.Json
import com.tracewayapp.traceway.internal.ManualScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WireframeRecorderTest {
    private val scheduler = ManualScheduler()
    private val source = FakeSource()
    private val rows = RowScreens()

    private fun recorder(maxFrames: Int = 50, fps: Int = 10) =
        WireframeRecorder(maxFrames, fps, source, ManualMainThread(scheduler), scheduler::now)

    @Suppress("UNCHECKED_CAST")
    private fun data(event: Map<String, Any?>) = event["data"] as Map<String, Any?>

    @Test
    fun `a clip replays to the last screen, touches included`() {
        source.screen = rows.screen(0)
        val recorder = recorder()
        recorder.screenName = { "/home" }
        val start = scheduler.now()
        recorder.start()
        scheduler.runDue()
        assertEquals(1, recorder.bufferedFrameCount)
        for (i in 1..3) {
            scheduler.advance(500)
            source.screen = rows.screen(i)
            source.draw()
            scheduler.advance(200)
        }
        recorder.pointerDown(1, 10f, 20f, touch = true)
        recorder.pointerUp(1, 10f, 20f, touch = true, tap = true)
        assertEquals(4, recorder.bufferedFrameCount)
        scheduler.advance(2000)
        val end = scheduler.now()

        val clip = recorder.captureRecording("c", start, end)!!
        val events = clip.events
        assertEquals(listOf(4, 2, 3, 3, 3, 3, 3, 3, 5), events.map { it["type"] })
        assertEquals(mapOf("href" to "/home", "width" to 400, "height" to 800), events.first()["data"])
        val times = events.map { it["timestamp"] as Long }
        assertEquals(times.sorted(), times)
        assertEquals(start, times.first())
        assertEquals(end, times.last())
        assertEquals(start, clip.startedAtMs)
        assertEquals(end, clip.endedAtMs)
        assertEquals(Json.stringify(events).length, clip.approxBytes)
        val touches = events.filter { it["type"] == 3 && data(it)["source"] == 2 }.map { data(it)["type"] }
        assertEquals(listOf(7, 9, 2), touches)
        assertEquals(2, data(events.first { it["type"] == 3 && data(it)["source"] == 2 })["pointerType"])

        val mirror = Mirror()
        mirror.play(events)
        assertEquals(listOf("row 0", "row 1", "row 2", "row 3"), mirror.texts())

        // A window that starts after the last change still shows the screen.
        val later = end - 1000
        assertTrue(recorder.hasFrames(later, end))
        val tail = recorder.captureRecording("t", later, end)!!
        assertEquals(listOf(4, 2, 5), tail.events.map { it["type"] })
        assertEquals(later, tail.startedAtMs)
        Mirror().apply { play(tail.events) }.also { assertEquals(4, it.texts().size) }

        // In the background nothing is on screen.
        recorder.setForeground(false)
        scheduler.advance(5000)
        assertFalse(recorder.hasFrames(scheduler.now() - 1000, scheduler.now()))
        recorder.stop()
        assertFalse(recorder.isRunning)
    }

    @Test
    fun `captures after draws, at most fps a second, and not while nothing draws`() {
        source.screen = rows.screen(0)
        val recorder = recorder(maxFrames = 500, fps = 10)
        recorder.start()
        scheduler.runDue()
        assertEquals(1, source.captures)
        // Nothing draws: nothing is captured.
        scheduler.advance(3000)
        assertEquals(1, source.captures)
        // Two seconds of 60 fps drawing.
        for (i in 0 until 120) {
            source.screen = rows.screen(i % 7)
            source.draw()
            scheduler.advance(16)
        }
        val captured = source.captures - 1
        assertTrue("captured $captured times", captured in 18..21)
        assertNotNull(recorder.captureRecording("c"))
    }

    @Test
    fun `an unchanged screen is held once and the ring keeps the last frames`() {
        val recorder = recorder(maxFrames = 5)
        recorder.start()
        val same = rows.screen(2)
        recorder.push(WireframeFrame(same, 400f, 800f, scheduler.now()))
        recorder.push(WireframeFrame(same, 400f, 800f, scheduler.now() + 10))
        assertEquals(1, recorder.bufferedFrameCount)
        for (i in 0 until 10) {
            scheduler.advance(100)
            recorder.push(WireframeFrame(rows.screen(i, label = { "frame $i row $it" }), 400f, 800f, scheduler.now()))
        }
        assertEquals(5, recorder.bufferedFrameCount)
        scheduler.advance(100)
        val clip = recorder.captureRecording("c")!!
        Mirror().apply { play(clip.events) }.also { assertEquals("frame 9 row 0", it.texts().first()) }
    }

    @Test
    fun `the background is a screen of its own, and touches there are not recorded`() {
        source.screen = rows.screen(1)
        val recorder = recorder()
        recorder.start()
        scheduler.runDue()
        scheduler.advance(1000)
        recorder.setForeground(false)
        scheduler.advance(1000)
        recorder.pointerDown(1, 5f, 5f, touch = true)
        source.draw()
        scheduler.advance(1000)
        assertEquals(1, source.captures)
        recorder.setForeground(true)
        scheduler.runDue()
        assertEquals(2, source.captures)
        scheduler.advance(1000)

        val clip = recorder.captureRecording("c", null, scheduler.now())!!
        // The screen, the background (a full snapshot), the screen again.
        assertEquals(listOf(4, 2, 4, 2, 4, 2, 5), clip.events.map { it["type"] })
        assertTrue(clip.events.none { it["type"] == 3 })
        val mirror = Mirror()
        mirror.play(clip.events.take(4))
        assertEquals(listOf("App in the background"), mirror.texts())
        mirror.play(clip.events)
        assertEquals(listOf("row 0", "row 1"), mirror.texts())
    }

    @Test
    fun `a new screen size starts over with a full snapshot`() {
        source.screen = rows.screen(1)
        val recorder = recorder()
        recorder.start()
        scheduler.runDue()
        scheduler.advance(500)
        source.width = 800f
        source.height = 400f
        source.screen = rows.screen(2)
        source.draw()
        scheduler.advance(500)
        val clip = recorder.captureRecording("c")!!
        assertEquals(listOf(4, 2, 4, 2, 5), clip.events.map { it["type"] })
        assertEquals(800, data(clip.events[2])["width"])
    }

    @Test
    fun `moves are thinned to one per pointer every 50 ms`() {
        source.screen = rows.screen(0)
        val recorder = recorder()
        recorder.start()
        scheduler.runDue()
        recorder.pointerDown(1, 0f, 0f, touch = true)
        for (i in 0 until 20) {
            recorder.pointerMove(1, i.toFloat(), i * 2f, touch = true)
            scheduler.advance(10)
        }
        recorder.pointerUp(1, 20f, 40f, touch = true, tap = false)
        val clip = recorder.captureRecording("c")!!
        val moves = clip.events.filter { it["type"] == 3 && data(it)["source"] == 6 }
        assertEquals(4, moves.size)
        @Suppress("UNCHECKED_CAST")
        val position = (data(moves[1])["positions"] as List<Map<String, Any?>>).single()
        assertEquals(mapOf("x" to 5, "y" to 10, "id" to screenNodeId, "timeOffset" to 0), position)
    }

    @Test
    fun `no clip before the first frame or for an empty window`() {
        val recorder = recorder()
        assertNull(recorder.captureRecording("c"))
        assertFalse(recorder.hasFrames())
        source.screen = rows.screen(0)
        recorder.start()
        scheduler.runDue()
        assertTrue(recorder.hasFrames())
        assertFalse(recorder.hasFrames(scheduler.now() + 10, scheduler.now() + 10))
        assertNull(recorder.captureRecording("c", scheduler.now() + 10, scheduler.now() + 5))
        recorder.clearBuffer()
        assertEquals(0, recorder.bufferedFrameCount)
        assertEquals(1, source.resets)
    }

    @Test
    fun `a capture error costs one frame`() {
        var fail = true
        val failing = object : WireframeFrameSource by source {
            override fun capture(timestampMs: Long, href: String?): WireframeFrame? {
                if (fail) {
                    fail = false
                    throw IllegalStateException("unexpected view")
                }
                return source.capture(timestampMs, href)
            }
        }
        source.screen = rows.screen(0)
        val recorder = WireframeRecorder(10, 10, failing, ManualMainThread(scheduler), scheduler::now)
        recorder.start()
        scheduler.runDue()
        assertEquals(0, recorder.bufferedFrameCount)
        assertEquals(1, source.resets)
        source.draw()
        scheduler.advance(200)
        assertEquals(1, recorder.bufferedFrameCount)
    }
}
