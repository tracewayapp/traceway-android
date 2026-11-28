package com.tracewayapp.fieldmart

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import java.io.File

/**
 * The in-app probe (03-MEASUREMENT.md §3), in every build including `off`, so its cost cancels out. It records each
 * frame Android reports through FrameMetrics (total duration and the vsync it was meant for) and the markers, on its
 * own `FmProbe` thread, and writes `probe.json` to the app's media folder whenever the app goes to the background.
 * The driver reads that file through the shell. Frame numbers for the results come from `dumpsys gfxinfo`; the
 * probe adds the per-frame series (spikes every segment, for instance).
 */
object Probe {
    private const val MAX = 120_000
    private val totalUs = IntArray(MAX)
    private val vsyncMs = LongArray(MAX)
    private var count = 0
    private var dropped = 0
    private val markers = ArrayList<Pair<String, Long>>()
    private var run = "manual"
    private var dir: File? = null

    private val thread by lazy { HandlerThread("FmProbe").apply { start() } }
    private val handler by lazy { Handler(thread.looper) }

    private val listener = Window.OnFrameMetricsAvailableListener { _, metrics, droppedReports ->
        val total = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
        val vsync = metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)
        synchronized(this) {
            dropped += droppedReports
            if (count < MAX) {
                totalUs[count] = (total / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                vsyncMs[count] = vsync / 1_000_000
                count++
            }
        }
    }

    fun init(context: Context, settings: LaunchSettings) {
        run = settings.run
        @Suppress("DEPRECATION")
        dir = (context.externalMediaDirs.firstOrNull() ?: context.getExternalFilesDir(null))?.resolve("fm-probe")
    }

    fun attach(activity: Activity) {
        activity.window.addOnFrameMetricsAvailableListener(listener, handler)
    }

    fun marker(name: String, epochMs: Long) {
        synchronized(this) { markers.add(name to epochMs) }
    }

    /** Writes the probe file off the main thread. */
    fun save() {
        handler.post { write() }
    }

    private fun write() {
        val d = dir ?: return
        val text = synchronized(this) {
            buildString(count * 12 + 512) {
                append("{\"run\":\"").append(run).append("\",\"source\":\"FrameMetrics\",")
                append("\"droppedReports\":").append(dropped).append(',')
                append("\"frames\":[")
                for (i in 0 until count) {
                    if (i > 0) append(',')
                    append(totalUs[i])
                }
                append("],\"vsyncMs\":[")
                for (i in 0 until count) {
                    if (i > 0) append(',')
                    append(vsyncMs[i])
                }
                append("],\"markers\":[")
                markers.forEachIndexed { i, (n, t) ->
                    if (i > 0) append(',')
                    append("{\"name\":\"").append(n.replace("\"", "'")).append("\",\"epochMs\":").append(t).append('}')
                }
                append("]}")
            }
        }
        try {
            d.mkdirs()
            val tmp = File(d, "$run.json.tmp")
            tmp.writeText(text)
            tmp.renameTo(File(d, "$run.json"))
        } catch (_: Throwable) {
        }
    }
}
