package com.tracewayapp.fieldmart.driver

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.Configurator
import com.tracewayapp.fieldmart.core.Journey
import com.tracewayapp.fieldmart.core.validate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The driver's one entry point (AGENT-4 step 2):
 *
 * ```
 * am instrument -w -e fmRun <id> -e fmScenario S6 -e fmVariant stream-img -e fmEvidence 1 \
 *   com.tracewayapp.fieldmart.driver/androidx.test.runner.AndroidJUnitRunner
 * ```
 *
 * or `-e fmPlanFile /data/local/tmp/fieldtest/plan.json` for several runs. Output goes to
 * `/sdcard/Android/media/com.tracewayapp.fieldmart.driver/fieldtest/<run>/`.
 */
@RunWith(AndroidJUnit4::class)
class JourneyTest {
    @Test
    fun run() {
        val inst = InstrumentationRegistry.getInstrumentation()
        // The Markets screen never goes idle: never wait for idle, find what is there now.
        Configurator.getInstance().waitForIdleTimeout = 0
        Configurator.getInstance().waitForSelectorTimeout = 0
        val shell = Shell(inst.uiAutomation)
        val specs = RunSpec.from(InstrumentationRegistry.getArguments()) { shell.run("cat $it") }
        @Suppress("DEPRECATION")
        val root = File(inst.context.externalMediaDirs.first(), "fieldtest").apply { mkdirs() }
        val journeyText = inst.context.assets.open("fm/journey.json").bufferedReader().use { it.readText() }
        val journey = Journey.parse(journeyText)
        for (spec in specs) {
            when (spec.mode) {
                "startup" -> StartupRun(spec, inst, root).execute()
                "warmup" -> warmUp(spec, inst)
                "validate" -> {
                    val s = journey.scenario(spec.scenario)
                    File(root, "validate-${spec.scenario}.txt").writeText(validate(s).joinToString("\n"))
                }
                else -> JourneyRun(spec, journey.scenario(spec.scenario), inst, root).execute()
            }
        }
    }
}

/**
 * Before the first run of a job: the app once, until UiAutomator sees its screen. Right after the emulator boots, the
 * accessibility window tracking may not be ready ("wait for adding window timeout") and the first run would see no
 * window at all. Not a run: nothing is written.
 */
private fun warmUp(spec: RunSpec, inst: android.app.Instrumentation) {
    val shell = Shell(inst.uiAutomation)
    val device = androidx.test.uiautomator.UiDevice.getInstance(inst)
    shell.run("am start -W -n ${spec.pkg}/${RunSpec.MAIN}")
    val grid = androidx.test.uiautomator.By.res(java.util.regex.Pattern.compile(com.tracewayapp.fieldmart.core.resourcePattern("fm-home-grid")))
    val seen = device.wait(androidx.test.uiautomator.Until.hasObject(grid), 60_000) == true
    device.pressHome()
    SystemClock.sleep(1000)
    shell.run("am force-stop ${spec.pkg}")
    if (!seen) throw IllegalStateException("warm-up: the app's screen was not seen in 60 s")
}

/**
 * Cold starts (03-MEASUREMENT.md §4): `fmRepeat` times, force-stop, 3 s, `am start -W`, FM_READY and FM_FULLY from
 * logcat, 5 s, stop; with `fmClear=1` (default) the app's data is cleared before each launch.
 */
class StartupRun(private val spec: RunSpec, inst: android.app.Instrumentation, root: File) {
    private val shell = Shell(inst.uiAutomation)
    private val dir = File(root, spec.run).apply { mkdirs() }

    fun execute() {
        val clear = spec.clear ?: true
        val results = ArrayList<kotlinx.serialization.json.JsonObject>()
        val settings = spec.launchExtras().entries.joinToString(" ") { (k, v) -> "--es $k $v" }
        for (i in 0 until spec.repeat) {
            shell.run("am force-stop ${spec.pkg}")
            if (clear) shell.run("pm clear ${spec.pkg}")
            shell.run("am start -W -n ${spec.pkg}/${RunSpec.CONFIG} $settings")
            SystemClock.sleep(500)
            shell.run("am force-stop ${spec.pkg}")
            shell.run("logcat -c")
            SystemClock.sleep(3000)
            val before = System.currentTimeMillis()
            val out = shell.run("am start -W -n ${spec.pkg}/${RunSpec.MAIN}")
            SystemClock.sleep(5000)
            val log = shell.run("logcat -d -v epoch -s FM:I")
            val ready = Regex("FM_READY (\\d+)").find(log)?.groupValues?.get(1)?.toLong()
            val fully = Regex("FM_FULLY (\\d+)").find(log)?.groupValues?.get(1)?.toLong()
            results += buildJsonObject {
                put("i", i)
                put("amStartEpochMs", before)
                Regex("TotalTime: (\\d+)").find(out)?.let { put("totalTimeMs", it.groupValues[1].toLong()) }
                Regex("WaitTime: (\\d+)").find(out)?.let { put("waitTimeMs", it.groupValues[1].toLong()) }
                ready?.let { put("readyEpochMs", it); put("readyAfterStartMs", it - before) }
                fully?.let { put("fullyEpochMs", it); put("fullyAfterStartMs", it - before) }
            }
            shell.run("am force-stop ${spec.pkg}")
        }
        File(dir, "startup.json").writeText(
            buildJsonObject {
                put("runId", spec.run)
                put("variant", spec.variant)
                put("package", spec.pkg)
                put("clear", clear)
                put("launches", JsonArray(results))
            }.toString(),
        )
    }
}
