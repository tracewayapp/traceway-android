package com.tracewayapp.traceway.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.Random

/** Reads a fixture of `src/test/resources/exits/`. */
internal fun fixture(name: String): ByteArray =
    DiagnosticsParsersTest::class.java.classLoader!!.getResourceAsStream("exits/$name")!!.use { it.readBytes() }

/**
 * The tombstone protobuf and ANR dump readers, on fixtures shaped like what
 * `ApplicationExitInfo.getTraceInputStream()` gives (the tombstones written
 * after `tombstone.proto` by `scripts/make-tombstone-fixtures.py`, unknown fields included).
 */
class DiagnosticsParsersTest {
    // ---- tombstones --------------------------------------------------------------

    @Test
    fun aSegfaultTombstoneGivesItsSignalCauseAndTheCrashingThreadsFrames() {
        val t = Tombstone.parse(fixture("tombstone_segv_arm64.pb"))!!
        assertEquals(11, t.signalNumber)
        assertEquals("SIGSEGV", t.signalName)
        assertEquals("SEGV_MAPERR", t.codeName)
        assertEquals("a fault address of 0 is left out of the protobuf, and read back", 0L, t.faultAddress)
        assertEquals("null pointer dereference", t.cause)
        assertNull(t.abortMessage)
        assertEquals("the crashing thread (tid 4330), not the first one", "ImageDecoder", t.threadName)
        assertEquals(5, t.frames.size)
        assertEquals("SIGSEGV (SEGV_MAPERR), fault addr 0x0: null pointer dereference", t.message)
        val lines = t.stackTrace("NativeCrash").lines()
        assertEquals("NativeCrash: SIGSEGV (SEGV_MAPERR), fault addr 0x0: null pointer dereference", lines[0])
        assertEquals(
            "    #00 pc 000000000001a2b4  /data/app/~~kXy1Qw==/com.example.shop-Qp9zR2==/lib/arm64/libshop.so " +
                "(Java_com_example_shop_NativeImages_decode+20) (BuildId: 8e7f1c2a9b3d4e5f6a7b8c9d0e1f2a3b)",
            lines[1],
        )
        assertEquals("    #01 pc 000000000001a0f0  /data/app/~~kXy1Qw==/com.example.shop-Qp9zR2==/lib/arm64/libshop.so " +
            "(shop::Decoder::run(shop::Image const&)+112) (BuildId: 8e7f1c2a9b3d4e5f6a7b8c9d0e1f2a3b)", lines[2])
        assertEquals("a stripped frame in the APK", "    #02 pc 00000000000022a4  /data/app/~~kXy1Qw==/com.example.shop-Qp9zR2==/base.apk (offset 0x1000000)", lines[3])
        assertTrue(lines[5], lines[5].endsWith("boot-framework.oat (android.view.View.performClick+124) (BuildId: f0e1d2c3b4a5968778695a4b3c2d1e0f)"))
        // The server reads each frame line with this pattern (app_issue_fingerprint.go).
        val nativeFrame = Regex("^\\s*(?:native:\\s*)?#\\d+\\s+pc\\s+([0-9a-fA-F]+)\\s+(.+?)\\s*$")
        assertTrue(lines.drop(1).all { nativeFrame.matches(it) })
    }

    @Test
    fun anAbortTombstoneGivesItsAbortMessageAnd32BitFrames() {
        val t = Tombstone.read(ByteArrayInputStream(fixture("tombstone_abort_arm.pb")))!!
        assertEquals("SIGABRT", t.signalName)
        assertEquals("SIGABRT: FORTIFY: pthread_mutex_lock called on a destroyed mutex (0xe8b2c3d0)", t.message)
        assertEquals("main", t.threadName)
        val lines = t.stackTrace("NativeCrash").lines()
        assertEquals("    #00 pc 0006f5c8  /apex/com.android.runtime/lib/bionic/libc.so (abort+164) (BuildId: 61bf89c2fc8b7bdf)", lines[1])
        assertEquals("    #02 pc 00001234  /data/app/~~a==/com.example.shop-b==/lib/arm/libshop.so (shop::Cache::lock()+16)", lines[3])
    }

    @Test
    fun aTombstoneCutShortKeepsWhatCameBefore() {
        val bytes = fixture("tombstone_segv_arm64.pb")
        // Cut inside the memory maps, after the threads: everything that matters is there.
        val late = Tombstone.parse(bytes.copyOf(bytes.size - 60))!!
        assertEquals(5, late.frames.size)
        // Cut inside the signal: nothing to report.
        var signalAt = 0
        while (!(bytes[signalAt].toInt() == (10 shl 3 or 2))) signalAt++
        assertNull(Tombstone.parse(bytes.copyOf(signalAt + 4)))
        assertNull(Tombstone.parse(ByteArray(0)))
    }

    @Test
    fun garbageNeverThrowsAndIsBoundedToo() {
        val random = Random(42)
        val sample = fixture("tombstone_segv_arm64.pb")
        repeat(2000) {
            val bytes = if (it % 2 == 0) {
                ByteArray(random.nextInt(512)).also { b -> random.nextBytes(b) }
            } else {
                // The real thing with a few bytes changed: lengths and tags that lie.
                sample.copyOf().also { b -> repeat(4) { b[random.nextInt(b.size)] = random.nextInt(256).toByte() } }
            }
            Tombstone.parse(bytes)
        }
        // A length that claims gigabytes, a varint that never ends, a group, field number 0.
        for (hostile in listOf(
            byteArrayOf(0x52, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x0F),
            ByteArray(20) { 0xFF.toByte() },
            byteArrayOf(0x53, 0x01),
            byteArrayOf(0x02, 0x00),
        )) {
            assertNull(Tombstone.parse(hostile))
        }
        // An endless stream is read up to the limit, not to the end.
        var served = 0L
        val endless = object : InputStream() {
            override fun read(): Int = 0.also { served++ }
            override fun read(b: ByteArray, off: Int, len: Int): Int = len.also { served += it }
        }
        Tombstone.read(endless)
        assertEquals(Tombstone.MAX_BYTES.toLong(), served)
    }

    // ---- ANR dumps --------------------------------------------------------------------

    @Test
    fun anAnrDumpGivesTheMainThreadsFramesAndTheSubject() {
        val dump = AnrTrace.read(ByteArrayInputStream(fixture("anr_blocked.txt")))!!
        assertTrue(dump.subject!!, dump.subject!!.startsWith("Input dispatching timed out (8d3c1a2 com.example.shop/com.example.shop.CheckoutActivity (server) is not responding."))
        assertEquals("Blocked", dump.mainThreadState)
        assertEquals(14, dump.frames.size)
        assertEquals("at com.example.shop.cart.CartRepository.total(CartRepository.kt:88)", dump.frames[0])
        assertEquals("at com.example.shop.CheckoutActivity.onPay(CheckoutActivity.kt:41)", dump.frames[1])
        assertEquals("at com.android.internal.os.ZygoteInit.main(ZygoteInit.java:971)", dump.frames.last())
        assertTrue("other threads are not the main thread's", dump.frames.none { "not.the.main" in it || "CartSync" in it })
        // As the server reads Java frames.
        val javaFrame = Regex("^\\s*at\\s+([\\w$.<>-]+)\\(([^)]*)\\)\\s*$")
        assertTrue(dump.frames.all { javaFrame.matches(it) })
    }

    @Test
    fun aMainThreadInNativeCodeKeepsItsNativeFramesInOrder() {
        val dump = AnrTrace.parse(String(fixture("anr_native.txt")))!!
        assertNull(dump.subject)
        assertEquals("Native", dump.mainThreadState)
        assertEquals(7, dump.frames.size)
        assertTrue(dump.frames[0], dump.frames[0].startsWith("native: #00 pc 000000000009e3c8  /apex/com.android.runtime/lib64/bionic/libc.so (__epoll_pwait+8)"))
        assertEquals("at android.os.MessageQueue.nativePollOnce(Native method)", dump.frames[3])
    }

    @Test
    fun aDumpWithoutAMainThreadOrAnythingElse() {
        assertNull(AnrTrace.parse(""))
        assertNull(AnrTrace.parse("garbage\n\u0000\u0001\n\"main"))
        val subjectOnly = AnrTrace.parse("Subject: Broadcast of Intent { act=android.intent.action.SCREEN_ON }\n")!!
        assertEquals("Broadcast of Intent { act=android.intent.action.SCREEN_ON }", subjectOnly.subject)
        assertTrue(subjectOnly.frames.isEmpty())
        // A huge line is cut, an endless dump read up to the limit.
        val long = AnrTrace.parse("\"main\" prio=5 tid=1 Runnable\n  at " + "x".repeat(100_000) + "(A.kt:1)\n")!!
        assertEquals(1, long.frames.size)
        assertTrue(long.frames[0].length <= 1024)
        var served = 0L
        val endless = object : InputStream() {
            override fun read(): Int = 'a'.code.also { served++ }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                for (i in off until off + len) b[i] = 'a'.code.toByte()
                served += len
                return len
            }
        }
        assertNull(AnrTrace.read(endless))
        assertTrue(served <= AnrTrace.MAX_BYTES + 16 * 1024)
    }

    // ---- what an exit record becomes -----------------------------------------------

    @Test
    fun exitRecordsBecomeTheIssuesTheServerGroups() {
        val crash = ExitRecord(ExitReasons.REASON_CRASH_NATIVE, 1L, 4321, "com.example.shop", "crash", trace = {
            ByteArrayInputStream(fixture("tombstone_segv_arm64.pb"))
        })
        val native = ExitReasons.issueFor(crash, sdkInt = 31)!!
        assertEquals("NativeCrash", native.type)
        assertEquals("SIGSEGV (SEGV_MAPERR), fault addr 0x0: null pointer dereference", native.message)
        assertEquals("ImageDecoder", native.threadName)
        assertEquals("crash_native", native.reason)
        assertTrue(native.stackTrace.startsWith("NativeCrash: SIGSEGV"))

        // API 30 keeps no tombstone for the app: Android's description.
        val api30 = ExitReasons.issueFor(crash, sdkInt = 30)!!
        assertEquals("crash", api30.message)
        assertEquals("NativeCrash: crash", api30.stackTrace)
        val blank = ExitRecord(ExitReasons.REASON_CRASH_NATIVE, 1L, 1, null, " ")
        assertEquals("Native crash", ExitReasons.issueFor(blank, sdkInt = 34)!!.message)

        val anr = ExitRecord(ExitReasons.REASON_ANR, 2L, 4321, null, "user request after error: Input dispatching timed out", trace = {
            ByteArrayInputStream(fixture("anr_blocked.txt"))
        })
        val issue = ExitReasons.issueFor(anr)!!
        assertEquals("ApplicationNotResponding", issue.type)
        assertTrue(issue.message.startsWith("Input dispatching timed out (8d3c1a2"))
        val lines = issue.stackTrace.lines()
        assertEquals("ApplicationNotResponding: ${issue.message}", lines[0])
        assertEquals("\tat com.example.shop.cart.CartRepository.total(CartRepository.kt:88)", lines[1])
        assertEquals("main", issue.threadName)

        // No dump: the description, and the frames a watchdog of that run saw.
        val bare = ExitRecord(ExitReasons.REASON_ANR, 3L, 1, null, "Input dispatching timed out")
        val withHang = ExitReasons.issueFor(bare, fallbackFrames = listOf("at a.B.c(B.kt:1)"))!!
        assertEquals("ApplicationNotResponding: Input dispatching timed out\n\tat a.B.c(B.kt:1)", withHang.stackTrace)
        assertEquals("ApplicationNotResponding: Application Not Responding", ExitReasons.issueFor(ExitRecord(6, 4L, 1, null, null))!!.stackTrace)

        // Not issues: a JVM crash (its handler reported it), a low-memory kill (a count), anything else.
        for (reason in listOf(ExitReasons.REASON_CRASH, ExitReasons.REASON_LOW_MEMORY, 1, 10, 13)) {
            assertNull(ExitReasons.issueFor(ExitRecord(reason, 5L, 1, null, "x")))
        }
    }

    @Test
    fun aTraceThatCannotBeReadFallsBackToTheDescription() {
        val failing = ExitRecord(ExitReasons.REASON_CRASH_NATIVE, 1L, 1, null, "signal 6", trace = { throw java.io.IOException("gone") })
        assertEquals("signal 6", ExitReasons.issueFor(failing, sdkInt = 33)!!.message)
        val garbage = ExitRecord(ExitReasons.REASON_CRASH_NATIVE, 1L, 1, null, "signal 11", trace = { ByteArrayInputStream(ByteArray(64) { 0x7F }) })
        assertEquals("signal 11", ExitReasons.issueFor(garbage, sdkInt = 33)!!.message)
        val anr = ExitRecord(ExitReasons.REASON_ANR, 1L, 1, null, "ANR", trace = { throw OutOfMemoryError("trace") })
        assertNotNull(ExitReasons.issueFor(anr))
    }
}
