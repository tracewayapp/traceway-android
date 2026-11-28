package com.tracewayapp.fieldmart.driver

import android.app.Instrumentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.KeyEvent
import android.view.WindowManager
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import com.tracewayapp.fieldmart.core.Scenario
import com.tracewayapp.fieldmart.core.Step
import com.tracewayapp.fieldmart.core.resourcePattern
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.regex.Pattern

/**
 * Plays one scenario of the journey against the app (AGENT-4 step 2): clean state, launch settings, cold start, T0
 * at `FM_READY`, every step at `T0 + t` with real input, checkpoints, the sampler, the standard ending
 * (02-APP-SPEC.md §5.5), and, in evidence runs, the screen recording, screenshots and text positions.
 */
class JourneyRun(private val spec: RunSpec, private val scenario: Scenario, private val inst: Instrumentation, root: File) {
    private val ui = inst.uiAutomation
    private val device = UiDevice.getInstance(inst)
    private val shell = Shell(ui)
    private val input = Input(ui)
    private val gateway = Gateway(spec.gateway)
    private val pkg = spec.pkg
    private val rec = Recorder(File(root, spec.run), "/data/local/tmp/fieldtest/${spec.run}")
    private val metrics = DisplayMetrics().also {
        @Suppress("DEPRECATION")
        (inst.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(it)
    }
    private val density = metrics.density
    private val gestures = Executors.newSingleThreadExecutor { r -> Thread(r, "FmGesture") }
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "FmWriter") }
    /** Text boxes are read after the screenshot on their own thread: a busy screen takes seconds to read. */
    private val boxer = Executors.newSingleThreadExecutor { r -> Thread(r, "FmBoxes") }
    private val boxesAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private var gesture: Future<*>? = null
    private val stepLog = ArrayList<JsonObject>()
    private val checkpoints = ArrayList<JsonObject>()
    private val actions = ArrayList<JsonObject>()
    private val boxes = LinkedHashMap<String, JsonArray>()
    private val marks = ArrayList<JsonObject>()
    private val flags = LinkedHashSet<String>()
    private var t0 = 0L
    private var pid = 0
    private var maxLate = 0L
    private var meminfoCount = 0
    private var sampler: Sampler? = null
    private var screen: ScreenRecorder? = null
    private val launches = ArrayList<JsonObject>()
    @Volatile
    private var released = true
    private val finder = Finder(ui, device, pkg)
    private val reveal by lazy {
        Reveal(device, finder, ui, input, density, { kind, id, x, y, x2, y2, ms -> record(kind, id, x, y, x2, y2, ms) }) { rec.log(it) }
    }

    fun execute() {
        val started = System.currentTimeMillis()
        rec.log("run ${spec.run} scenario=${scenario.id} variant=${spec.variant} package=$pkg evidence=${spec.evidence}")
        val ctl = gateway.post("/ctl/run", "{\"runId\":${jsonString(spec.run)}}")
        rec.log("gateway /ctl/run -> ${ctl.first} ${ctl.second.take(200)}")
        shell.run("mkdir -p ${rec.shellDir}")
        shell.run("logcat -c")
        val clear = spec.clear ?: !scenario.keepsState
        if (clear) rec.log("pm clear -> ${shell.run("pm clear $pkg").trim()}")
        configure()
        if (spec.evidence) screen = ScreenRecorder(shell, rec.shellDir, rec).also { it.start() }

        launch("cold")
        rec.t0 = t0
        shell.run("dumpsys gfxinfo $pkg reset")
        val s = Sampler(shell, rec.file("samples.raw"), pid)
        sampler = s
        // The sampler's first tick is the t0 snapshot, on its own thread, so the first step is not held up.
        s.start()

        try {
            play()
        } catch (e: Throwable) {
            flags += "driver-error"
            rec.log("driver error: ${e.stackTraceToString()}")
        }
        waitGesture()

        // End of the scenario: the cost window closes here.
        val end = System.currentTimeMillis()
        s.snapshot("end")
        rec.write("gfxinfo.txt", shell.run("dumpsys gfxinfo $pkg"))
        rec.write("meminfo-end.txt", shell.run("dumpsys meminfo $pkg"))

        // The standard ending: Home, 5 s, force-stop, as a user leaves.
        input.key(KeyEvent.KEYCODE_HOME)
        val home = System.currentTimeMillis()
        SystemClock.sleep(5000)
        s.snapshot("ending")
        s.stop()
        val probe = shell.run("cat /sdcard/Android/media/$pkg/fm-probe/${spec.run}.json")
        if (probe.startsWith("{")) rec.write("probe.json", probe)
        shell.run("am force-stop $pkg")
        val stopped = System.currentTimeMillis()
        screen?.stop()
        if (spec.relaunchAfterMs > 0 && pkg == RunSpec.SDK_PACKAGE) relaunchOnceOver(home)
        val quiet = waitLedgerQuiet()
        rec.write("app.log", shell.run("logcat -d -v epoch -b main,system,crash"))
        boxer.shutdown()
        boxer.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS)
        writer.shutdown()
        writer.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS)
        gestures.shutdownNow()

        if (s.goneAt in (t0 + 1) until end && launches.size == 1) flags += "crashed"
        if (maxLate > LATE_MS) flags += "late"
        writeMeta(started, end, home, stopped, quiet)
        rec.log("done flags=$flags")
        rec.close()
    }

    /**
     * The app opened again [RunSpec.relaunchAfterMs] after the standard ending's Home, once the run's session is over
     * (a short `fmSessionTimeoutMs`): the SDK ends the session the killed run left at this start, with its counts and
     * memory peak (findings android-003/004), and sends it into this run's ledger. The relaunch has its own run id
     * (`<run>-relaunch`), so its session is not this run's. Home, 5 s, force-stop again.
     */
    private fun relaunchOnceOver(home: Long) {
        val wait = home + spec.relaunchAfterMs - System.currentTimeMillis()
        if (wait > 0) SystemClock.sleep(wait)
        configure("${spec.run}-relaunch")
        val before = System.currentTimeMillis()
        shell.run("am start -W -n $pkg/${RunSpec.MAIN}")
        val ready = waitReady(before)
        marks += buildJsonObject {
            put("name", "relaunch")
            put("epochMs", before)
            put("afterHomeMs", before - home)
            put("run", "${spec.run}-relaunch")
            ready?.let { put("readyEpochMs", it) }
        }
        rec.log("relaunch ${before - home} ms after Home, FM_READY=${ready?.let { it - before }} ms after am start")
        // The SDK starts, ends the earlier session and exports it; then the app leaves as at the standard ending.
        SystemClock.sleep(10_000)
        input.key(KeyEvent.KEYCODE_HOME)
        SystemClock.sleep(5000)
        shell.run("am force-stop $pkg")
        marks += buildJsonObject { put("name", "relaunch-stopped"); put("epochMs", System.currentTimeMillis()) }
    }

    /** Stores the launch settings through ConfigActivity (its own process), then stops the app. */
    private fun configure(runId: String = spec.run) {
        val extras = spec.launchExtras(scenario.launchSettings, runId).entries.joinToString(" ") { (k, v) -> "--es $k $v" }
        val out = shell.run("am start -W -n $pkg/${RunSpec.CONFIG} $extras")
        rec.log("config: ${out.lines().filter { it.startsWith("Status") || it.contains("Error") }.joinToString("; ")}")
        val deadline = SystemClock.uptimeMillis() + 8000
        var ok = false
        while (SystemClock.uptimeMillis() < deadline) {
            val lines = shell.run("logcat -d -v epoch -s FM:I")
            if (lines.contains("FM_CONFIG ok")) {
                ok = true
                break
            }
            SystemClock.sleep(100)
        }
        if (!ok) {
            flags += "config-unconfirmed"
            rec.log("FM_CONFIG ok not seen")
        }
        shell.run("am force-stop $pkg")
        SystemClock.sleep(300)
    }

    /** Starts the launcher activity and waits for `FM_READY`; the first launch sets T0. */
    private fun launch(kind: String) {
        val before = System.currentTimeMillis()
        val out = shell.run("am start -W -n $pkg/${RunSpec.MAIN}")
        val total = Regex("TotalTime: (\\d+)").find(out)?.groupValues?.get(1)?.toLong()
        val ready = waitReady(before)
        pid = shell.run("pidof $pkg").trim().split(Regex("\\s+")).firstOrNull()?.toIntOrNull() ?: 0
        sampler?.pid = pid
        if (t0 == 0L) t0 = ready ?: System.currentTimeMillis()
        launches += buildJsonObject {
            put("kind", kind)
            put("amStartEpochMs", before)
            total?.let { put("totalTimeMs", it) }
            ready?.let { put("readyEpochMs", it) }
            put("pid", pid)
            put("amStart", out.trim())
        }
        rec.log("launch $kind pid=$pid TotalTime=$total FM_READY=${ready?.let { it - before }} ms after am start")
        if (ready == null) flags += "no-ready"
    }

    private fun waitReady(after: Long): Long? {
        val deadline = SystemClock.uptimeMillis() + 30000
        while (SystemClock.uptimeMillis() < deadline) {
            val lines = shell.run("logcat -d -v epoch -s FM:I")
            for (m in Regex("FM_READY (\\d+)").findAll(lines)) {
                val at = m.groupValues[1].toLong()
                if (at >= after - 50) return at
            }
            SystemClock.sleep(40)
        }
        return null
    }

    private fun play() {
        for (step in scenario.stepsFor(PLATFORM)) {
            val due = t0 + step.t
            val wait = due - System.currentTimeMillis()
            if (wait > 0) SystemClock.sleep(wait)
            // A `release` ends the held drag the gesture thread is in (on time: its lateness is when the finger is
            // let go); anything else of input waits for the gesture before it.
            val releasedAt = if (step.action == "release") System.currentTimeMillis().also { released = true } else null
            if (step.action in INPUT || step.action in PROCESS || step.action == "release") waitGesture()
            val startAt = releasedAt ?: System.currentTimeMillis()
            val late = startAt - due
            if (late > maxLate) maxLate = late
            var ok = true
            var detail = ""
            try {
                detail = perform(step)
            } catch (e: Throwable) {
                ok = false
                detail = e.toString()
            }
            stepLog += buildJsonObject {
                put("i", step.index)
                put("do", step.action)
                step.id?.let { put("id", it) }
                step.name?.let { put("name", it) }
                put("t", step.t)
                put("startEpochMs", startAt)
                put("lateMs", late)
                put("ok", ok)
                put("detail", detail)
            }
            rec.log("step ${step.index} t=${step.t} ${step.action} ${step.id ?: step.name ?: ""} late=${late}ms ${if (ok) "" else "FAILED "}$detail")
        }
        val endDue = t0 + scenario.durationMs
        val wait = endDue - System.currentTimeMillis()
        if (wait > 0) SystemClock.sleep(wait)
    }

    private fun perform(s: Step): String = when (s.action) {
        "waitFor" -> {
            val found = device.wait(androidx.test.uiautomator.Until.hasObject(selector(s.id!!)), s.timeoutMs) == true
            if (!found) throw IllegalStateException("${s.id} not on screen after ${s.timeoutMs} ms")
            "found"
        }
        "tap" -> onGesture(s) { tap(s.id!!, s.reveal) }
        "release" -> "released"
        "longPress" -> onGesture(s) {
            val r = bounds(s.id!!, s.reveal)
            action("longPress", s.id, r.exactCenterX(), r.exactCenterY()) { input.longPress(r.exactCenterX(), r.exactCenterY(), s.ms ?: 800) }
        }
        "fling" -> onGesture(s) { fling(s) }
        "drag" -> onGesture(s) { drag(s) }
        "type" -> onGesture(s) { type(s) }
        "paste" -> onGesture(s) { paste(s.text ?: "", s.into) }
        "key" -> onGesture(s) {
            val code = when (s.name ?: s.value) {
                "enter" -> KeyEvent.KEYCODE_ENTER
                "tab" -> KeyEvent.KEYCODE_TAB
                "back" -> KeyEvent.KEYCODE_BACK
                "escape" -> KeyEvent.KEYCODE_ESCAPE
                else -> throw IllegalArgumentException("key ${s.name}")
            }
            input.key(code)
            "key $code"
        }
        "pick" -> onGesture(s) { val id = s.id!!; Picker(device, finder, input, ::tapAt, rec, t0, pkg).pick(id, s.value ?: "", s.durationMs ?: 2500) }
        "idle" -> "idle"
        "checkpoint" -> checkpoint(s)
        "mark" -> {
            marks += buildJsonObject { put("name", s.name ?: ""); put("epochMs", System.currentTimeMillis()) }
            "mark"
        }
        "fault" -> {
            val r = gateway.post("/ctl/fault", "{\"mode\":${jsonString(s.mode ?: "refuse")},\"seconds\":${s.seconds ?: 0}}")
            marks += buildJsonObject { put("name", "fault ${s.mode} ${s.seconds}"); put("epochMs", System.currentTimeMillis()) }
            "fault -> ${r.first}"
        }
        "background" -> {
            input.key(KeyEvent.KEYCODE_HOME)
            marks += buildJsonObject { put("name", "background"); put("epochMs", System.currentTimeMillis()) }
            "home"
        }
        "foreground" -> {
            val out = shell.run("am start -n $pkg/${RunSpec.MAIN}")
            marks += buildJsonObject { put("name", "foreground"); put("epochMs", System.currentTimeMillis()) }
            out.trim()
        }
        "kill" -> {
            val killed = shell.run("kill -9 $pid").trim()
            val viaKill = shell.run("pidof $pkg").trim().isEmpty()
            if (!viaKill) shell.run("am force-stop $pkg")
            marks += buildJsonObject { put("name", "kill"); put("epochMs", System.currentTimeMillis()); put("method", if (viaKill) "kill -9" else "am force-stop") }
            if (viaKill) "kill -9 $pid" else "kill -9 refused (${killed.take(80)}); am force-stop"
        }
        "relaunch" -> {
            launch("relaunch")
            "pid $pid"
        }
        else -> throw IllegalArgumentException("unknown step ${s.action}")
    }

    /** Input runs on the gesture thread, so a checkpoint can be taken while a drag holds the finger down. */
    private fun onGesture(s: Step, block: () -> String): String {
        gesture = gestures.submit {
            try {
                val d = block()
                rec.log("gesture ${s.index} ${s.action} done: $d")
            } catch (e: Throwable) {
                rec.log("gesture ${s.index} ${s.action} FAILED: $e")
                synchronized(flags) { flags += "step-failed" }
            }
        }
        return "started"
    }

    private fun waitGesture() {
        try {
            gesture?.get()
        } catch (_: Throwable) {
        }
    }

    private fun selector(id: String): BySelector = By.res(Pattern.compile(resourcePattern(id)))

    /** The on-screen bounds of [id]: as it is, or brought fully into view first when the step says `reveal`. */
    private fun bounds(id: String, revealFirst: Boolean): Rect {
        if (revealFirst) return reveal.reveal(id)
        return finder.bounds(id) ?: throw IllegalStateException("$id not on screen")
    }

    private fun tapAt(id: String?, x: Float, y: Float): String = action("tap", id, x, y) { input.tap(x, y) }

    private fun tap(id: String, revealFirst: Boolean): String {
        val r = bounds(id, revealFirst)
        return tapAt(id, r.exactCenterX(), r.exactCenterY())
    }

    private fun fling(s: Step): String {
        val r = bounds(s.id!!, s.reveal)
        val (fx, fy) = direction(s.dir ?: "down")
        val dx = r.width() * s.distance.toFloat() / 2 * fx
        val dy = r.height() * s.distance.toFloat() / 2 * fy
        val cx = r.exactCenterX()
        val cy = r.exactCenterY()
        val ms = s.durationMs ?: Step.DEFAULT_FLING_MS
        return action("fling", s.id, cx - dx, cy - dy, cx + dx, cy + dy, ms) { input.swipe(cx - dx, cy - dy, cx + dx, cy + dy, ms, 8) }
    }

    /** The finger's direction for a journey `dir`. */
    private fun direction(dir: String): Pair<Float, Float> {
        val finger = when (dir) {
            "up" -> 0f to -1f
            "down" -> 0f to 1f
            "left" -> -1f to 0f
            "right" -> 1f to 0f
            else -> throw IllegalArgumentException("fling dir $dir")
        }
        // UiAutomator's convention: a fling DOWN shows what is below, so the finger moves up.
        return if (spec.flingDir == "content") -finger.first to -finger.second else finger
    }

    private fun drag(s: Step): String {
        val r = bounds(s.id!!, s.reveal)
        val from = s.from ?: (0.1 to 0.5)
        val to = s.to ?: (0.9 to 0.5)
        val x1 = r.left + r.width() * from.first.toFloat()
        val y1 = r.top + r.height() * from.second.toFloat()
        val x2 = r.left + r.width() * to.first.toFloat()
        val y2 = r.top + r.height() * to.second.toFloat()
        val ms = s.durationMs ?: 1000
        if (s.hold) released = false
        return action("drag", s.id, x1, y1, x2, y2, ms) { input.swipe(x1, y1, x2, y2, ms, 16, 0, if (s.hold) ({ released }) else null) }
    }

    private fun type(s: Step): String {
        val text = s.text ?: ""
        val start = SystemClock.uptimeMillis()
        val epoch = System.currentTimeMillis()
        var failed = 0
        text.forEachIndexed { i, c ->
            val due = start + i * s.perCharMs
            val wait = due - SystemClock.uptimeMillis()
            if (wait > 0) SystemClock.sleep(wait)
            if (!input.type(c)) failed++
        }
        synchronized(actions) {
            actions += buildJsonObject {
                put("do", "type"); s.into?.let { put("id", it); put("into", it) }; put("epochMs", epoch); put("endEpochMs", System.currentTimeMillis())
                put("text", text); put("perCharMs", s.perCharMs); put("failed", failed)
            }
        }
        if (failed > 0) throw IllegalStateException("$failed of ${text.length} characters had no key events")
        return "${text.length} chars"
    }

    /**
     * `paste`: the whole text in one action (02-APP-SPEC.md §5.2), through the field's accessibility "set text" (a
     * clipboard paste would put Android's clipboard overlay on the screen being recorded).
     */
    private fun paste(text: String, into: String?): String {
        val target = (into?.let { device.findObject(selector(it)) } ?: device.findObject(By.focused(true)))
            ?: throw IllegalStateException("no field to paste into")
        val epoch = System.currentTimeMillis()
        target.text = text
        synchronized(actions) {
            actions += buildJsonObject {
                put("do", "paste"); into?.let { put("id", it); put("into", it) }; put("epochMs", epoch)
                put("endEpochMs", System.currentTimeMillis()); put("text", text)
            }
        }
        return "paste ${text.length} chars"
    }

    private fun record(kind: String, id: String?, x: Float, y: Float, x2: Float, y2: Float, ms: Long) {
        val end = System.currentTimeMillis()
        synchronized(actions) {
            actions += buildJsonObject {
                put("do", kind); id?.let { put("id", it) }; put("epochMs", end - ms); put("endEpochMs", end)
                put("x", x / density); put("y", y / density); put("x2", x2 / density); put("y2", y2 / density)
            }
        }
    }

    private fun action(kind: String, id: String?, x: Float, y: Float, x2: Float? = null, y2: Float? = null, ms: Long? = null, run: () -> Unit): String {
        val epoch = System.currentTimeMillis()
        run()
        val endEpoch = System.currentTimeMillis()
        synchronized(actions) {
            actions += buildJsonObject {
                put("do", kind)
                id?.let { put("id", it) }
                put("epochMs", epoch)
                put("endEpochMs", endEpoch)
                put("x", x / density)
                put("y", y / density)
                x2?.let { put("x2", it / density) }
                y2?.let { put("y2", it / density) }
                ms?.let { put("durationMs", it) }
            }
        }
        return "$kind at %.0f,%.0f dp".format(x / density, y / density)
    }

    private fun checkpoint(s: Step): String {
        val stepAt = System.currentTimeMillis()
        SystemClock.sleep(s.settleMs)
        val at = System.currentTimeMillis()
        val name = s.name ?: "cp${s.index}"
        var shot: Pair<Long, Long>? = null
        if (spec.evidence) {
            val before = System.currentTimeMillis()
            val bitmap: Bitmap? = ui.takeScreenshot()
            val after = System.currentTimeMillis()
            shot = before to after
            if (bitmap != null) writer.execute {
                try {
                    rec.file("app/$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } catch (e: Throwable) {
                    rec.log("screenshot $name not written: $e")
                } finally {
                    bitmap.recycle()
                }
            }
            val shows = s.shows
            val masked = s.masked
            val listId = s.list
            boxer.execute {
            // Read while the app keeps changing: any element may go stale; a failure costs this checkpoint's boxes,
            // never the run (an exception on this thread would end the instrumentation).
            try {
            val readAt = System.currentTimeMillis()
            val result = buildJsonArray {
                fun box(o: androidx.test.uiautomator.UiObject2?, extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) {
                    val r = try {
                        o?.visibleBounds
                    } catch (_: Throwable) {
                        null
                    } ?: return
                    if (r.width() <= 0 || r.height() <= 0) return
                    add(buildJsonObject {
                        extra()
                        put("x", r.left / density); put("y", r.top / density)
                        put("w", r.width() / density); put("h", r.height() / density)
                    })
                }
                // Two passes over the tree (ids, then texts) instead of one per id and text: a busy screen is slow to read.
                val ids = (masked + REGIONS).distinct()
                val byId = if (ids.isEmpty()) emptyList() else device.findObjects(
                    By.res(Pattern.compile(ids.joinToString("|") { resourcePattern(it).removePrefix("^").removeSuffix("$") }.let { "^(?:$it)$" })),
                )
                fun idOf(o: androidx.test.uiautomator.UiObject2): String? = try {
                    ids.firstOrNull { Regex(resourcePattern(it)).matches(o.resourceName ?: "") }
                } catch (_: Throwable) {
                    null
                }
                for (o in byId) {
                    val id = idOf(o) ?: continue
                    box(o) { put("id", id); put("role", if (id in masked) "masked" else "region") }
                }
                listId?.let { lid ->
                    val list = device.findObject(selector(lid))
                    val area = list?.visibleBounds
                    val rows = (list?.children ?: emptyList()).filter { c ->
                        val b = c.visibleBounds
                        area != null && b.height() > 0 && b.top >= area.top && b.bottom <= area.bottom
                    }.sortedBy { it.visibleBounds.top }
                    fun label(row: androidx.test.uiautomator.UiObject2): String? =
                        row.text?.takeIf { it.isNotBlank() } ?: row.findObjects(By.text(Pattern.compile("(?s).*\\S.*"))).firstOrNull()?.text
                    rows.firstOrNull()?.let { r -> label(r)?.let { t -> box(r) { put("text", t); put("role", "first-row") } } }
                    rows.lastOrNull()?.takeIf { rows.size > 1 }?.let { r -> label(r)?.let { t -> box(r) { put("text", t); put("role", "last-row") } } }
                }
                // Where each text is drawn (its characters), as the replay's text box is compared with it (R-13).
                for (text in shows) {
                    for (r in finder.drawnText(text)) {
                        add(buildJsonObject {
                            put("text", text)
                            put("x", r.left / density); put("y", r.top / density)
                            put("w", r.width() / density); put("h", r.height() / density)
                        })
                    }
                }
            }
            synchronized(boxes) { boxes[name] = result }
            boxesAt[name] = readAt
            } catch (e: Throwable) {
                rec.log("boxes of $name failed: $e")
            }
            }
        }
        if (s.meminfo) {
            val n = ++meminfoCount
            val text = shell.run("dumpsys meminfo $pkg")
            writer.execute { runCatching { rec.write("meminfo-$n.txt", text) } }
        }
        checkpoints += buildJsonObject {
            put("name", name)
            put("epochMs", at)
            put("tMs", at - t0)
            put("stepEpochMs", stepAt)
            put("settleMs", s.settleMs)
            put("scenario", scenario.id)
            put("shows", JsonArray(s.shows.map { JsonPrimitive(it) }))
            put("hides", JsonArray(s.hides.map { JsonPrimitive(it) }))
            put("masked", JsonArray(s.masked.map { JsonPrimitive(it) }))
            s.overlay?.let { put("overlay", it) }
            s.list?.let { put("list", it) }
            s.screen?.let { put("screen", it) }
            s.during?.let { put("during", it) }
            shot?.let {
                put("shot", "app/$name.png")
                put("shotStartEpochMs", it.first)
                put("shotEndEpochMs", it.second)
            }
        }
        return "checkpoint $name"
    }

    /** Waits until the gateway has had no SDK request of this run for 5 s (at most 30 s): `GET /ctl/quiet`. */
    private fun waitLedgerQuiet(): Long {
        val (code, body) = gateway.get("/ctl/quiet?run=${spec.run}&ms=5000&timeout=30000")
        rec.log("gateway /ctl/quiet -> $code ${body.take(200)}")
        return if (code == 200 && body.contains("\"quiet\":true")) System.currentTimeMillis() else -1
    }

    private fun writeMeta(started: Long, end: Long, home: Long, stopped: Long, quiet: Long) {
        rec.write("steps.json", JsonArray(stepLog).toString())
        rec.write("checkpoints.json", JsonArray(checkpoints.map { c ->
            val at = boxesAt[(c["name"] as? JsonPrimitive)?.content]
            if (at == null) c else JsonObject(c + ("boxesEpochMs" to JsonPrimitive(at)))
        }).toString())
        rec.write("actions.json", synchronized(actions) { JsonArray(actions.toList()) }.toString())
        if (spec.evidence) {
            rec.write("boxes.json", synchronized(boxes) { JsonObject(boxes) }.toString())
            rec.write("screen-pieces.json", screen?.json() ?: "[]")
        }
        val meta = buildJsonObject {
            put("runId", spec.run)
            put("scenario", scenario.id)
            put("variant", spec.variant)
            put("package", pkg)
            put("evidence", spec.evidence)
            put("driverStartEpochMs", started)
            put("t0EpochMs", t0)
            put("endEpochMs", end)
            put("homeEpochMs", home)
            put("stoppedEpochMs", stopped)
            put("ledgerQuietEpochMs", quiet)
            put("durationMs", scenario.durationMs)
            put("maxLateMs", maxLate)
            put("flags", JsonArray(flags.map { JsonPrimitive(it) }))
            put("pid", pid)
            put("processGoneEpochMs", sampler?.goneAt ?: 0)
            put("launches", JsonArray(launches))
            put("marks", JsonArray(marks))
            put("shellDir", rec.shellDir)
            put("settings", JsonObject(spec.launchExtras(scenario.launchSettings).mapValues { JsonPrimitive(it.value) }))
            put("device", buildJsonObject {
                put("model", Build.MODEL)
                put("manufacturer", Build.MANUFACTURER)
                put("product", Build.PRODUCT)
                put("sdk", Build.VERSION.SDK_INT)
                put("release", Build.VERSION.RELEASE)
                put("fingerprint", Build.FINGERPRINT)
                put("widthPx", metrics.widthPixels)
                put("heightPx", metrics.heightPixels)
                put("density", density)
                put("densityDpi", metrics.densityDpi)
                put("widthDp", metrics.widthPixels / density)
                put("heightDp", metrics.heightPixels / density)
            })
        }
        rec.write("run-meta.json", meta.toString())
    }

    companion object {
        const val LATE_MS = 1500L
        const val PLATFORM = "android"
        val REGIONS = listOf("fm-markets-chart", "fm-home-hero")
        val INPUT = setOf("tap", "longPress", "fling", "drag", "type", "paste", "key", "pick")
        val PROCESS = setOf("background", "foreground", "kill", "relaunch", "waitFor")
    }
}
