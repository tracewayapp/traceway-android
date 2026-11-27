package com.tracewayapp.traceway.diagnostics

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowActivityManager.ApplicationExitInfoBuilder
import java.io.ByteArrayInputStream

/** `ApplicationExitInfo` as Android hands it over (Robolectric's `ShadowActivityManager`), with fixture traces. */
@RunWith(RobolectricTestRunner::class)
class AndroidExitSourceTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val am: ActivityManager get() = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    private fun exit(reason: Int, timestamp: Long, pid: Int, process: String, description: String?, trace: ByteArray? = null) {
        val builder = ApplicationExitInfoBuilder.newBuilder()
            .setReason(reason)
            .setTimestamp(timestamp)
            .setPid(pid)
            .setProcessName(process)
            .setDescription(description)
        if (trace != null) builder.setTraceInputStream(ByteArrayInputStream(trace))
        shadowOf(am).addApplicationExitInfo(builder.build())
    }

    @Test
    fun theExitsOfThisProcessWithTheirTraces() {
        val process = app.packageName
        exit(ExitReasons.REASON_CRASH_NATIVE, 1_000L, 4321, process, "crash", fixture("tombstone_segv_arm64.pb"))
        exit(ExitReasons.REASON_ANR, 2_000L, 4400, process, "Input dispatching timed out", fixture("anr_blocked.txt"))
        exit(ExitReasons.REASON_LOW_MEMORY, 3_000L, 4500, process, null)
        // Another process of the app reports its own.
        exit(ExitReasons.REASON_ANR, 4_000L, 4600, "$process:sync", "Broadcast of Intent")

        val records = AndroidExitSource(app, process).read()
        assertEquals(listOf(4321, 4400, 4500), records.map { it.pid }.sorted())
        val byPid = records.associateBy { it.pid }

        val native = ExitReasons.issueFor(byPid.getValue(4321), sdkInt = 35)!!
        assertEquals("NativeCrash", native.type)
        assertEquals("SIGSEGV (SEGV_MAPERR), fault addr 0x0: null pointer dereference", native.message)
        assertEquals(1_000L, byPid.getValue(4321).timestampMs)

        val anr = ExitReasons.issueFor(byPid.getValue(4400), sdkInt = 35)!!
        assertEquals("ApplicationNotResponding", anr.type)
        assertTrue(anr.stackTrace, anr.stackTrace.lines()[1] == "\tat com.example.shop.cart.CartRepository.total(CartRepository.kt:88)")

        assertNull(ExitReasons.issueFor(byPid.getValue(4500)))
        assertEquals(ExitReasons.REASON_LOW_MEMORY, byPid.getValue(4500).reason)
    }

    @Test
    fun noSourceBeforeApi30AndAProcessNameEitherWay() {
        assertNull(ExitReasons.sourceFor(app, sdkInt = 29))
        assertNull(ExitReasons.sourceFor(null, sdkInt = 35))
        assertTrue(ExitReasons.sourceFor(app, sdkInt = 30) is AndroidExitSource)
        assertTrue(!ExitReasons.currentProcessName(app).isNullOrEmpty())
        assertTrue(!ExitReasons.currentProcessName(app, sdkInt = 27).isNullOrEmpty())
        assertEquals(emptyList<ExitRecord>(), AndroidExitSource(app, app.packageName).read())
    }
}
