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

    private fun frame(screen: VNode?, width: Float = 400f, height: Float = 800f, href: String? = "/home") =
        WireframeFrame(screen, width, height, scheduler.now(), href)

    private fun imageCss(i: Int, base64Chars: Int) =
        ".im.p$i{background:url(data:image/png;base64," + "A".repeat(base64Chars) + ") center/cover no-repeat}"

    private fun gallery(vararg styles: String): VNode {
        val children = styles.mapIndexed { i, css ->
            VNode.element(300 + i, "style", mapOf("_cssText" to css), emptyList())
        } + VNode.element(200, "div", mapOf("class" to "im p0", "style" to "width:140px;height:90px"), emptyList()) +
            VNode.element(201, "div", mapOf("class" to "mk"), listOf(VNode.text(202, "***"))) +
            VNode.text(203, "Checkout")
        return VNode.element(screenNodeId, "div", mapOf("id" to "tw"), children)
    }

    @Test
    fun `the inline image limit counts repeated URLs across full snapshots and resets each clip`() {
        val recorder = recorder()
        val css = imageCss(0, 65_516)
        val screen = gallery(css)
        val first = scheduler.now()
        recorder.push(frame(screen))
        scheduler.advance(100)
        val second = scheduler.now()
        recorder.push(frame(screen, width = 240f))
        scheduler.advance(100)
        val over = recorder.captureRecording("over", first, scheduler.now())!!
        val encoded = Json.stringify(over.events)
        assertFalse(encoded.contains("data:image/"))
        assertEquals(2, over.events.count { it["type"] == 2 })
        val mirror = Mirror().apply { play(over.events) }
        assertEquals("width:140px;height:90px", mirror.byId.getValue(200).attributes["style"])
        assertEquals("mk", mirror.byId.getValue(201).attributes["class"])
        assertEquals(listOf("***", "Checkout"), mirror.texts())
        assertEquals(encoded.length, over.approxBytes)
        val small = recorder.captureRecording("next", second, scheduler.now())!!
        assertTrue(Json.stringify(small.events).contains(css))
    }

    @Test
    fun `the final mutation at exactly the inline limit keeps every image and one byte more falls back`() {
        val firstCss = imageCss(0, 65_512)
        val secondSize = 128 * 1024 - 2 * "data:image/png;base64,".length - 65_512
        fun record(secondCss: String): String {
            val recorder = recorder()
            val start = scheduler.now()
            recorder.push(frame(gallery(firstCss)))
            scheduler.advance(100)
            recorder.push(frame(gallery(secondCss)))
            scheduler.advance(100)
            val clip = recorder.captureRecording("clip", start, scheduler.now())!!
            assertEquals(listOf("***", "Checkout"), Mirror().apply { play(clip.events) }.texts())
            val segment = com.tracewayapp.traceway.replay.RecordingSegment(events = clip.events)
            val encoded = com.tracewayapp.traceway.replay.RecordingsTransport.encode(listOf(segment))
            return com.tracewayapp.traceway.internal.gunzip(encoded).toString(Charsets.UTF_8)
        }
        val exact = record(imageCss(0, secondSize))
        assertTrue(exact.contains(firstCss))
        assertTrue(exact.contains(imageCss(0, secondSize)))
        // The JPEG data URL header is exactly one character longer than the PNG header.
        val oneOver = imageCss(0, secondSize).replace("image/png", "image/jpeg")
        assertFalse(record(oneOver).contains("data:image/"))
    }

    @Test
    fun `a gallery of many small added pictures falls back without losing user events`() {
        val recorder = recorder()
        recorder.start()
        val start = scheduler.now()
        recorder.push(frame(gallery()))
        scheduler.advance(100)
        recorder.push(frame(gallery(*Array(100) { imageCss(it, 1400) })))
        recorder.pointerDown(1, 10f, 20f, true)
        scheduler.advance(100)
        val clip = recorder.captureRecording("gallery", start, scheduler.now())!!
        assertFalse(Json.stringify(clip.events).contains("data:image/"))
        assertTrue(clip.events.any { it["type"] == 3 && data(it)["source"] == 2 })
        assertEquals(listOf("***", "Checkout"), Mirror().apply { play(clip.events) }.texts())
    }

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
        recorder.pointerDown(2, 100f, 0f, touch = true)
        for (i in 0 until 20) {
            recorder.pointerMove(1, i.toFloat(), i * 2f, touch = true)
            recorder.pointerMove(2, 100f + i, 0f, touch = true)
            scheduler.advance(10)
        }
        recorder.pointerUp(1, 20f, 40f, touch = true, tap = false)
        recorder.pointerUp(2, 120f, 0f, touch = true, tap = false)
        val clip = recorder.captureRecording("c")!!
        @Suppress("UNCHECKED_CAST")
        val moves = clip.events.filter { it["type"] == 3 && data(it)["source"] == 6 }
            .map { (data(it)["positions"] as List<Map<String, Any?>>).single() }
        // 200 ms of moves every 10 ms: one every 50 ms for each finger, the
        // other finger's moves in between do not count.
        val first = moves.filter { (it["x"] as Int) < 100 }
        val second = moves.filter { (it["x"] as Int) >= 100 }
        assertEquals(4, first.size)
        assertEquals(mapOf("x" to 5, "y" to 10, "id" to screenNodeId, "timeOffset" to 0), first[1])
        assertEquals(listOf(100, 105, 110, 115), second.map { it["x"] })
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

    @Test
    fun `a clip of a full ring starts at the oldest frame kept and ends at the newest`() {
        val recorder = recorder(maxFrames = 5)
        val start = scheduler.now()
        for (i in 0 until 10) {
            scheduler.advance(100)
            recorder.push(frame(rows.screen(i % 3, label = { "frame $i row $it" })))
        }
        assertEquals(5, recorder.bufferedFrameCount)
        scheduler.advance(100)
        val clip = recorder.captureRecording("c", start, scheduler.now())!!
        // Frames 0 to 4 left the ring: the clip starts at frame 5.
        assertEquals(start + 600, clip.startedAtMs)
        val first = Mirror().apply { play(clip.events.take(2)) }
        assertEquals(listOf("frame 5 row 0", "frame 5 row 1", "frame 5 row 2"), first.texts())
        val mirror = Mirror().apply { play(clip.events) }
        assertEquals(listOf("frame 9 row 0"), mirror.texts())
    }

    @Test
    fun `a clip starts at the first frame inside its window`() {
        val recorder = recorder()
        assertFalse(recorder.hasFrames(null, null))
        assertNull(recorder.captureRecording("none", null, null))
        scheduler.advance(1000)
        val first = scheduler.now()
        recorder.push(frame(rows.screen(0)))
        // From before anything was recorded: the clip starts at the first frame.
        assertEquals(first, recorder.captureRecording("c", first - 5000, first + 10)!!.startedAtMs)
        assertFalse(recorder.hasFrames(first + 10, first + 10))
        assertFalse(recorder.hasFrames(first - 5000, first))
        assertTrue(recorder.hasFrames(first - 5000, first + 1))
        // A clip whose end is the moment of its only frame ends at once.
        assertNull(recorder.captureRecording("e", first - 10, first))
    }

    @Test
    fun `an empty or inverted window has no clip`() {
        val recorder = recorder()
        val first = scheduler.now()
        recorder.push(frame(rows.screen(0)))
        scheduler.advance(1000)
        recorder.push(frame(rows.screen(1)))
        scheduler.advance(1000)
        assertNotNull(recorder.captureRecording("ok", first + 500, first + 1500))
        // A frame shows at both ends, yet the window holds no time.
        assertNull(recorder.captureRecording("empty", first + 500, first + 500))
        assertNull(recorder.captureRecording("inverted", first + 1500, first + 500))
        // Without an end the window runs to now: a start after now is inverted too.
        assertNull(recorder.captureRecording("future", scheduler.now() + 1000, null))
        assertFalse(recorder.hasFrames(first + 1500, first + 500))
    }

    @Test
    fun `a clip without an end runs to now and one without a start from the first frame`() {
        val recorder = recorder()
        val first = scheduler.now()
        recorder.push(frame(rows.screen(0), href = null))
        scheduler.advance(1500)
        val clip = recorder.captureRecording("c", null, null)!!
        assertEquals(first, clip.startedAtMs)
        assertEquals(scheduler.now(), clip.endedAtMs)
        assertEquals("c", clip.clipId)
        assertEquals("a frame without a screen name replays at /", "/", data(clip.events.first())["href"])
        assertEquals(listOf(4, 2, 5), clip.events.map { it["type"] })
        assertEquals(mapOf("left" to 0, "top" to 0), data(clip.events[1])["initialOffset"])
        assertEquals("traceway.end", data(clip.events.last())["tag"])
        assertEquals(scheduler.now(), clip.events.last()["timestamp"])
        assertTrue(recorder.hasFrames(null, null))
    }

    @Test
    fun `pointer events map to rrweb and moves are throttled`() {
        val recorder = recorder()
        // Not recording: pointers are not kept.
        recorder.pointerDown(1, 1f, 1f, touch = true)
        source.screen = rows.screen(0)
        recorder.start()
        scheduler.runDue()
        val start = scheduler.now()
        recorder.pointerDown(1, 1f, 2f, touch = true)
        scheduler.advance(10)
        recorder.pointerMove(1, 3f, 4f, touch = true)
        scheduler.advance(10)
        recorder.pointerMove(1, 5f, 6f, touch = true) // within 50 ms: dropped
        scheduler.advance(60)
        recorder.pointerMove(1, 7f, 8f, touch = true)
        recorder.pointerUp(1, 7f, 8f, touch = true, tap = false)
        scheduler.advance(10)
        recorder.pointerDown(2, 9f, 9f, touch = false)
        recorder.pointerMove(2, 10f, 10f, touch = false)
        recorder.pointerUp(2, 10f, 10f, touch = false, tap = true)
        recorder.pointerDown(3, 1f, 1f, touch = true)
        recorder.pointerCancel(3, 1f, 1f, touch = true)
        scheduler.advance(10)
        val events = recorder.captureRecording("c", start, scheduler.now())!!.events
        val input = events.filter { it["type"] == 3 && data(it)["source"] != 0 }.map { data(it) }
        @Suppress("UNCHECKED_CAST")
        fun describe(d: Map<String, Any?>): String {
            if (d["source"] == 2) return "i${d["type"]}p${d["pointerType"]}"
            val p = (d["positions"] as List<Map<String, Any?>>)[0]
            return "m${d["source"]}@${p["x"]},${p["y"]}"
        }
        assertEquals(
            listOf("i7p2", "m6@3,4", "m6@7,8", "i9p2", "i1p0", "m1@10,10", "i0p0", "i2p0", "i7p2", "i10p2"),
            input.map(::describe),
        )
        @Suppress("UNCHECKED_CAST")
        assertEquals(screenNodeId, (input[1]["positions"] as List<Map<String, Any?>>)[0]["id"])
        assertTrue(input.filter { it["source"] == 2 }.all { it["id"] == screenNodeId })
        recorder.stop()
        assertFalse(recorder.isRunning)
        // Stopping leaves the screen blank from then on.
        scheduler.advance(100)
        assertFalse(recorder.hasFrames(scheduler.now() - 10, scheduler.now()))
    }

    @Test
    fun `only touches inside the clip are replayed`() {
        val recorder = recorder()
        source.screen = rows.screen(0)
        recorder.start()
        scheduler.runDue()
        recorder.pointerDown(1, 1f, 1f, touch = true)
        recorder.pointerUp(1, 1f, 1f, touch = true, tap = true)
        scheduler.advance(1000)
        val since = scheduler.now()
        recorder.pointerDown(2, 2f, 2f, touch = true)
        scheduler.advance(100)
        val until = scheduler.now()
        recorder.pointerUp(2, 2f, 2f, touch = true, tap = true)
        val events = recorder.captureRecording("c", since, until)!!.events
        val touches = events.filter { it["type"] == 3 && data(it)["source"] == 2 }.map { data(it) }
        // The tap before the clip is left out; the finger that lifts after it
        // ends is still down when the replay ends.
        assertEquals(listOf(7), touches.map { it["type"] })
        assertEquals(2, touches[0]["x"])
        assertEquals(until, events.last()["timestamp"])
    }

    @Test
    fun `the frame rate and the ring are clamped`() {
        assertEquals(1, recorder(fps = 0).fps)
        assertEquals(30, recorder(fps = 60).fps)
        assertEquals(10, WireframeRecorder(50, source = source, mainThread = ManualMainThread(scheduler), clock = scheduler::now).fps)
        val tiny = recorder(maxFrames = 0)
        for (i in 0 until 4) {
            scheduler.advance(100)
            tiny.push(frame(rows.screen(i)))
        }
        assertEquals("a ring holds at least two frames", 2, tiny.bufferedFrameCount)
        tiny.start()
        tiny.start()
        assertTrue(tiny.isRunning)
        assertNotNull("one observer", source.onDraw)
        tiny.stop()
        tiny.stop()
        assertFalse(tiny.isRunning)
        assertNull(source.onDraw)
        // The newest frame and, once, the blank screen after it.
        val clip = tiny.captureRecording("c", null, scheduler.now() + 1)!!
        assertEquals(listOf("row 0", "row 1", "row 2", "row 3"), Mirror().apply { play(clip.events.take(2)) }.texts())
        assertEquals(listOf(4, 2, 4, 2, 5), clip.events.map { it["type"] })
    }
}
