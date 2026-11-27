package com.tracewayapp.traceway.diagnostics

import com.tracewayapp.traceway.internal.Json
import com.tracewayapp.traceway.internal.ManualScheduler
import com.tracewayapp.traceway.internal.asJsonObject
import com.tracewayapp.traceway.metrics.HangWatchdog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Main-thread hangs as `AppHang` issues (CONTRACT §14.2): the stack while blocked, the file a fatal one leaves. */
class AppHangsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scheduler = ManualScheduler()
    private val reported = ArrayList<Hang>()
    private val later = ArrayList<() -> Unit>()
    private var stackReads = 0

    private val stack = arrayOf(
        StackTraceElement("com.example.shop.cart.CartRepository", "total", "CartRepository.kt", 88),
        StackTraceElement("com.example.shop.CheckoutActivity", "onPay", "CheckoutActivity.kt", 41),
        StackTraceElement("android.os.Looper", "loop", "Looper.java", 294),
    )

    private fun hangs(dir: File? = tmp.root, pid: Int = 4321, mainStack: () -> Array<StackTraceElement>? = { stackReads++; stack }) = AppHangs(
        thresholdMs = 2000,
        directory = dir,
        pid = pid,
        mainStack = mainStack,
        screen = { mapOf("app.screen.name" to "/checkout", "viewport" to "412x915") },
        sessionId = { "session-1" },
        now = { scheduler.nowMs },
        background = { later.add(it) },
        report = { reported.add(it) },
    )

    private fun runLater() {
        val tasks = ArrayList(later)
        later.clear()
        tasks.forEach { it() }
    }

    private fun files(): List<File> = tmp.root.listFiles()?.filter { it.name.endsWith(".json") } ?: emptyList()

    @Test
    fun aHangIsWrittenWhileItLastsAndReportedOnceWhenItEnds() {
        val h = hangs()
        h.stalled(2100)
        assertEquals("the stack is taken while the main thread is still blocked", 1, stackReads)
        val written = Json.parse(files().single().readText()).asJsonObject()!!
        assertEquals(2100L, (written["blockedMs"] as Number).toLong())
        assertEquals(4321L, (written["pid"] as Number).toLong())
        assertEquals(scheduler.nowMs - 2100, (written["startedAtMs"] as Number).toLong())
        assertEquals("session-1", written["sessionId"])

        // Later ticks of the same hang update its length, a write a second at most, the stack once.
        scheduler.advance(500)
        h.stalled(2600)
        scheduler.advance(600)
        h.stalled(3200)
        assertEquals(1, stackReads)
        assertEquals(3200L, (Json.parse(files().single().readText()).asJsonObject()!!["blockedMs"] as Number).toLong())

        h.ended(3240, report = true)
        assertTrue("nothing is reported on the main thread", reported.isEmpty())
        runLater()
        val hang = reported.single()
        assertEquals("Main thread blocked for 3.2 s", hang.message)
        assertEquals(
            "AppHang: Main thread blocked for 3.2 s\n" +
                "\tat com.example.shop.cart.CartRepository.total(CartRepository.kt:88)\n" +
                "\tat com.example.shop.CheckoutActivity.onPay(CheckoutActivity.kt:41)\n" +
                "\tat android.os.Looper.loop(Looper.java:294)",
            hang.stackTrace(),
        )
        assertEquals("/checkout", hang.screen["app.screen.name"])
        assertTrue("the file goes once it is reported", files().isEmpty())
    }

    @Test
    fun aHangUnderADebuggerOrStoppedIsNotReported() {
        val h = hangs()
        h.stalled(2500)
        h.ended(2600, report = false)
        runLater()
        assertTrue(reported.isEmpty())
        assertTrue(files().isEmpty())
        // An end without a stall reports nothing.
        h.ended(5000, report = true)
        runLater()
        assertTrue(reported.isEmpty())
    }

    @Test
    fun aNewHangBeforeTheLastOnesFileWentKeepsItsOwn() {
        val h = hangs()
        h.stalled(2000)
        h.ended(2100, report = true)
        // The disk thread is behind: a second hang starts before the first one's file is removed.
        scheduler.advance(10_000)
        h.stalled(2300)
        runLater()
        assertEquals(1, reported.size)
        assertEquals("the second hang's file stays", 1, files().size)
    }

    @Test
    fun theHangsAnEarlierRunDidNotSurviveAreTakenOnceButNotThisRunsOwn() {
        val earlier = hangs(pid = 100)
        earlier.stalled(4000)
        scheduler.advance(1000)
        val other = hangs(pid = 200)
        other.stalled(2500)
        val now = hangs(pid = 300)
        now.stalled(2500)
        File(tmp.root, "broken.json").writeText("{not json")

        val previous = now.takePrevious()
        assertEquals(listOf(100, 200), previous.map { it.pid })
        assertEquals("oldest first", listOf(4000L, 2500L), previous.map { it.blockedMs })
        assertEquals("Main thread blocked for 2.5 s", previous[1].message)
        assertEquals("at com.example.shop.cart.CartRepository.total(CartRepository.kt:88)", previous[0].frames[0])
        assertEquals("412x915", previous[0].screen["viewport"])
        assertEquals("this run's own hang stays, the rest is gone", 1, files().size)
        assertTrue(now.takePrevious().isEmpty())
        assertTrue(hangs(dir = null).takePrevious().isEmpty())
    }

    @Test
    fun aStackThatCannotBeReadStillGivesAHang() {
        val h = hangs(mainStack = { throw SecurityException("no") })
        h.stalled(3000)
        h.ended(3000, report = true)
        runLater()
        assertEquals("AppHang: Main thread blocked for 3.0 s", reported.single().stackTrace())
    }

    @Test
    fun theWatchdogTellsTheListenerWhileTheMainThreadIsStillBlocked() {
        val main = ArrayDeque<Runnable>()
        val h = hangs()
        val metric = ArrayList<Long>()
        var debugger = false
        val watchdog = HangWatchdog(
            clock = { scheduler.nowMs },
            postToMain = { main.add(it) },
            schedule = { delay, task -> scheduler.schedule(delay, task) },
            thresholdMs = 2000,
            intervalMs = 500,
            excused = { debugger },
            onHang = { metric.add(it) },
            listener = h,
        )
        watchdog.start()
        // The main thread runs nothing for 3.2 s: the probe waits.
        scheduler.advance(1900)
        assertEquals(0, stackReads)
        scheduler.advance(600)
        assertEquals("at the first tick past the threshold", 1, stackReads)
        assertEquals(1, files().size)
        scheduler.advance(700)
        main.removeFirst().run()
        assertEquals(listOf(3200L), metric)
        runLater()
        assertEquals("Main thread blocked for 3.2 s", reported.single().message)
        assertTrue(files().isEmpty())

        // A short block: the metric's threshold and the issue's are the same here; nothing under 2 s.
        scheduler.advance(500)
        scheduler.advance(1000)
        main.removeFirst().run()
        runLater()
        assertEquals(1, reported.size)

        // Under a debugger nothing is taken, nor reported.
        debugger = true
        scheduler.advance(500)
        scheduler.advance(5000)
        main.removeFirst().run()
        runLater()
        assertEquals(1, reported.size)
        assertEquals(1, stackReads)
        debugger = false

        // Stopped (the app went to the background) during a hang: dropped, its file too.
        scheduler.advance(500)
        scheduler.advance(2500)
        assertEquals(2, stackReads)
        watchdog.stop()
        runLater()
        assertEquals(1, reported.size)
        assertTrue(files().isEmpty())
        main.forEach { it.run() }
        runLater()
        assertEquals(1, reported.size)
    }

    @Test
    fun durationsInTheMessage() {
        assertEquals("Main thread blocked for 2.0 s", Hang.message(2000))
        assertEquals("Main thread blocked for 0.9 s", Hang.message(850))
        assertEquals("Main thread blocked for 125.3 s", Hang.message(125_300))
        assertFalse(Hang.message(3200).contains(','))
    }
}
