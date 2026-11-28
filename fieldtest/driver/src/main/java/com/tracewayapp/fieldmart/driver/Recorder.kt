package com.tracewayapp.fieldmart.driver

import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Everything one run writes, in the driver's media folder (`/sdcard/Android/media/<driver>/fieldtest/<run>/`, which
 * `adb pull` reads and the driver can write without a permission), plus the screen pieces the shell writes to
 * `/data/local/tmp/fieldtest/<run>/`. The host harness turns these raw files into the results format.
 */
class Recorder(val dir: File, val shellDir: String) {
    private val log = FileOutputStream(File(dir.apply { mkdirs() }, "driver.log"), true).bufferedWriter()
    private val t0Lock = Any()
    var t0: Long = 0

    fun log(text: String) {
        val now = System.currentTimeMillis()
        val rel = if (t0 > 0) now - t0 else -1
        synchronized(t0Lock) {
            log.write("$now\t$rel\t$text\n")
            log.flush()
        }
    }

    fun write(name: String, text: String) {
        val f = File(dir, name)
        f.parentFile?.mkdirs()
        f.writeText(text)
    }

    fun file(name: String): File = File(dir, name).also { it.parentFile?.mkdirs() }

    fun close() = log.close()
}

/**
 * The sampler of 03-MEASUREMENT.md §3.3: once a second, the app process's `/proc/<pid>/stat` and `statm`, every
 * thread's `stat` (names from the stat line), and the device's `/proc/stat`, as raw text stamped with the device
 * clock. One `ls` and one `cat` per tick, through the shell user (no glob, so the thread list is read first).
 */
class Sampler(private val shell: Shell, private val out: File, @Volatile var pid: Int, private val intervalMs: Long = 1000) {
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    @Volatile
    var goneAt: Long = 0
        private set

    fun snapshot(tag: String): String {
        val p = pid
        val tasks = shell.run("ls /proc/$p/task").split(Regex("\\s+")).filter { it.isNotEmpty() && it.all(Char::isDigit) }
        val files = StringBuilder("cat /proc/$p/stat /proc/$p/statm /proc/stat")
        for (t in tasks) files.append(" /proc/$p/task/").append(t).append("/stat")
        val text = shell.run(files.toString())
        val now = System.currentTimeMillis()
        if (tasks.isEmpty() && goneAt == 0L) goneAt = now
        val block = "@sample $now $p $tag\n$text\n@end\n"
        synchronized(this) { out.appendText(block) }
        return text
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread({
            var next = SystemClock.uptimeMillis()
            while (running.get()) {
                try {
                    snapshot("tick")
                } catch (_: Throwable) {
                }
                next += intervalMs
                val wait = next - SystemClock.uptimeMillis()
                if (wait > 0) SystemClock.sleep(wait) else next = SystemClock.uptimeMillis()
            }
        }, "FmSampler").apply { isDaemon = true; start() }
    }

    fun stop() {
        running.set(false)
        thread?.join(3000)
    }
}

/**
 * `screenrecord --bit-rate 4000000 --time-limit 170` in a loop for the length of an evidence run (the tool stops at
 * 180 s), each piece stamped with the device clock before it started and after it ended; stopped with SIGINT so the
 * last file is finalized. The host joins the pieces into `screen.mp4` and calibrates each piece's start against the
 * checkpoint screenshots.
 */
class ScreenRecorder(private val shell: Shell, private val shellDir: String, private val recorder: Recorder) {
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    val pieces = ArrayList<String>()

    fun start() {
        running.set(true)
        thread = Thread({
            var i = 0
            while (running.get()) {
                val file = "$shellDir/screen-$i.mp4"
                val before = System.currentTimeMillis()
                val stream = shell.start("screenrecord --bit-rate 4000000 --time-limit 170 $file")
                recorder.log("screenrecord piece $i started $file")
                val output = try {
                    stream.use { it.readBytes().toString(Charsets.UTF_8) }
                } catch (e: Throwable) {
                    e.toString()
                }
                val after = System.currentTimeMillis()
                synchronized(pieces) {
                    pieces += "{\"index\":$i,\"file\":${jsonString("screen-$i.mp4")},\"execEpochMs\":$before,\"endEpochMs\":$after," +
                        "\"output\":${jsonString(output.trim())}}"
                }
                recorder.log("screenrecord piece $i ended after ${after - before} ms")
                i++
                if (after - before < 1000 && running.get()) SystemClock.sleep(500)
            }
        }, "FmScreen").apply { isDaemon = true; start() }
        // Give the first piece time to create its display before the app starts.
        SystemClock.sleep(1500)
    }

    fun stop() {
        running.set(false)
        for (attempt in 0 until 5) {
            val pids = shell.run("pidof screenrecord").trim()
            if (pids.isEmpty()) break
            for (p in pids.split(Regex("\\s+"))) shell.run("kill -2 $p")
            SystemClock.sleep(700)
        }
        thread?.join(15000)
    }

    fun json(): String = synchronized(pieces) { "[" + pieces.joinToString(",") + "]" }
}
