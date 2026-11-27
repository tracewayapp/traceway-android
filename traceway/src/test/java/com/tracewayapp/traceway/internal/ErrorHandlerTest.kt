package com.tracewayapp.traceway.internal

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.junit.Before
import org.junit.Test

class ErrorHandlerTest {
    private var original: Thread.UncaughtExceptionHandler? = null
    private val chained = ArrayList<Throwable>()

    @Before
    fun setUp() {
        original = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> synchronized(chained) { chained.add(e) } }
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(original)
    }

    @Test
    fun reportsTheCrashThenHandsItToThePreviousHandler() {
        val seen = ArrayList<Throwable>()
        ErrorHandler(onCrash = { _, e -> seen.add(e) }).install()
        val error = RuntimeException("boom")
        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), error)
        assertEquals(listOf<Throwable>(error), seen)
        assertEquals(listOf<Throwable>(error), chained)
    }

    @Test
    fun aFailureInTheReportStillReachesThePreviousHandler() {
        ErrorHandler(onCrash = { _, _ -> throw OutOfMemoryError("ours") }).install()
        val error = RuntimeException("app's")
        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), error)
        assertSame(error, chained.single())
    }

    @Test
    fun aCrashInsideTheReportGoesStraightToThePlatform() {
        var calls = 0
        lateinit var handler: Thread.UncaughtExceptionHandler
        ErrorHandler(onCrash = { t, _ ->
            calls++
            handler.uncaughtException(t, IllegalStateException("inside"))
        }).install()
        handler = Thread.getDefaultUncaughtExceptionHandler()!!
        handler.uncaughtException(Thread.currentThread(), RuntimeException("outer"))
        assertEquals(1, calls)
        assertEquals(listOf("inside", "outer"), chained.map { it.message })
    }

    @Test
    fun anInterruptedThreadIsStillReportedAndKeepsItsFlag() {
        var reported = false
        ErrorHandler(onCrash = { _, _ -> reported = !Thread.currentThread().isInterrupted }).install()
        Thread.currentThread().interrupt()
        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), RuntimeException("x"))
        assertTrue(reported)
        assertTrue(Thread.interrupted())
    }

    @Test
    fun theSameHandlerReportsLaterCrashesToo() {
        var calls = 0
        ErrorHandler(onCrash = { _, _ -> calls++ }).install()
        val handler = Thread.getDefaultUncaughtExceptionHandler()!!
        handler.uncaughtException(Thread.currentThread(), RuntimeException("1"))
        handler.uncaughtException(Thread.currentThread(), RuntimeException("2"))
        assertEquals(2, calls)
    }

    @Test
    fun withNoHandlerBeforeTheCrashIsStillPrintedAsTheJvmWould() {
        Thread.setDefaultUncaughtExceptionHandler(null)
        var reported = 0
        ErrorHandler(onCrash = { _, _ -> reported++ }).install()
        val err = System.err
        val printed = ByteArrayOutputStream()
        System.setErr(PrintStream(printed, true))
        try {
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), RuntimeException("lost?"))
        } finally {
            System.setErr(err)
        }
        assertEquals(1, reported)
        val text = printed.toString()
        assertTrue(text, text.startsWith("Exception in thread \"${Thread.currentThread().name}\" java.lang.RuntimeException: lost?"))
        assertTrue(text, text.contains("\tat "))
    }

    @Test
    fun uninstallGivesTheDefaultHandlerBackOrOnlyPassesCrashesOn() {
        val before = Thread.getDefaultUncaughtExceptionHandler()
        var reported = 0
        val handler = ErrorHandler(onCrash = { _, _ -> reported++ })
        handler.install()
        handler.uninstall()
        assertSame("still on top: the previous one is back", before, Thread.getDefaultUncaughtExceptionHandler())

        // The app put its own handler on top of ours: ours stays in its chain, reporting nothing, passing it on.
        handler.install()
        val ours = Thread.getDefaultUncaughtExceptionHandler()!!
        Thread.setDefaultUncaughtExceptionHandler { t, e -> ours.uncaughtException(t, e) }
        handler.uninstall()
        val error = RuntimeException("after shutdown")
        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), error)
        assertEquals(0, reported)
        assertSame(error, chained.single())
    }
}
