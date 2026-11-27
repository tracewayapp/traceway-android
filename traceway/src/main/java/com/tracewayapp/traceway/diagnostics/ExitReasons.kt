package com.tracewayapp.traceway.diagnostics

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import com.tracewayapp.traceway.guarded
import java.io.File
import java.io.InputStream

/** How an earlier run of the process ended, as Android recorded it (`ApplicationExitInfo`). */
internal class ExitRecord(
    /** `ApplicationExitInfo.REASON_*`. */
    val reason: Int,
    /** When the process died, epoch ms. */
    val timestampMs: Long,
    val pid: Int,
    val processName: String?,
    val description: String?,
    /** The trace Android kept: the ANR dump, or the tombstone protobuf on API 31+. Null when there is none. */
    val trace: () -> InputStream? = { null },
)

/** Where the records come from: Android's `ActivityManager`, or a test. Newest first. */
internal fun interface ExitSource {
    fun read(): List<ExitRecord>
}

/**
 * The exits of this app's process (the one named [processName]: another
 * process of the app reports its own) that Android keeps, newest first. API 30+; a binder call, so off the main thread.
 */
@RequiresApi(30)
internal class AndroidExitSource(
    private val context: Context,
    private val processName: String?,
) : ExitSource {
    override fun read(): List<ExitRecord> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return emptyList()
        val infos = am.getHistoricalProcessExitReasons(context.packageName, 0, 0) ?: return emptyList()
        val out = ArrayList<ExitRecord>(infos.size)
        for (info in infos) {
            if (processName != null && info.processName != null && info.processName != processName) continue
            out.add(
                ExitRecord(
                    reason = info.reason,
                    timestampMs = info.timestamp,
                    pid = info.pid,
                    processName = info.processName,
                    description = info.description,
                    trace = { info.traceInputStream },
                ),
            )
        }
        return out
    }
}

/** An issue made of an exit record: what the app's report says. */
internal class ExitIssue(
    val type: String,
    val message: String,
    val stackTrace: String,
    val threadName: String?,
    /** `app.exit.reason`: `crash_native` or `anr`. */
    val reason: String,
)

internal object ExitReasons {
    // ApplicationExitInfo.REASON_*, as numbers: the class exists from API 30 only.
    const val REASON_LOW_MEMORY = 3
    const val REASON_CRASH = 4
    const val REASON_CRASH_NATIVE = 5
    const val REASON_ANR = 6

    fun isReportable(reason: Int) = reason == REASON_LOW_MEMORY || reason == REASON_CRASH_NATIVE || reason == REASON_ANR

    const val TYPE_NATIVE_CRASH = "NativeCrash"
    const val TYPE_ANR = "ApplicationNotResponding"

    /** The most exits reported at one start; older ones are left. */
    const val MAX_REPORTS = 8

    /** The exit source of this device: Android's, from API 30. */
    fun sourceFor(app: Application?, sdkInt: Int = Build.VERSION.SDK_INT): ExitSource? {
        if (app == null || sdkInt < 30) return null
        return AndroidExitSource(app, currentProcessName(app, sdkInt))
    }

    @SuppressLint("NewApi")
    fun currentProcessName(app: Context?, sdkInt: Int = Build.VERSION.SDK_INT): String? = guarded("process name", null) {
        if (sdkInt >= 28) {
            Application.getProcessName()
        } else {
            File("/proc/self/cmdline").readText().substringBefore('\u0000').trim().ifEmpty { null }
        } ?: app?.packageName
    }

    /**
     * The issue for [record], or null when it is not one: a native crash
     * (with the tombstone on API 31+, else Android's description) or an
     * ANR (with the main thread of its dump, else [fallbackFrames], the
     * main thread a watchdog of that run saw blocked).
     */
    fun issueFor(record: ExitRecord, sdkInt: Int = Build.VERSION.SDK_INT, fallbackFrames: List<String>? = null): ExitIssue? =
        when (record.reason) {
            REASON_CRASH_NATIVE -> nativeCrash(record, sdkInt)
            REASON_ANR -> anr(record, fallbackFrames)
            else -> null
        }

    private fun nativeCrash(record: ExitRecord, sdkInt: Int): ExitIssue {
        val tombstone = if (sdkInt >= 31) {
            guarded("tombstone", null) { record.trace()?.let { Tombstone.read(it) } }
        } else {
            null
        }
        if (tombstone != null) {
            return ExitIssue(TYPE_NATIVE_CRASH, tombstone.message, tombstone.stackTrace(TYPE_NATIVE_CRASH), tombstone.threadName, "crash_native")
        }
        val message = record.description?.trim()?.takeIf { it.isNotEmpty() } ?: "Native crash"
        return ExitIssue(TYPE_NATIVE_CRASH, message, "$TYPE_NATIVE_CRASH: $message", null, "crash_native")
    }

    private fun anr(record: ExitRecord, fallbackFrames: List<String>?): ExitIssue {
        val dump = guarded("anr trace", null) { record.trace()?.let { AnrTrace.read(it) } }
        val message = dump?.subject ?: record.description?.trim()?.takeIf { it.isNotEmpty() } ?: "Application Not Responding"
        val frames = dump?.frames?.takeIf { it.isNotEmpty() } ?: fallbackFrames ?: emptyList()
        val sb = StringBuilder(TYPE_ANR).append(": ").append(message)
        for (f in frames) sb.append("\n\t").append(f)
        return ExitIssue(TYPE_ANR, message, sb.toString(), "main", "anr")
    }
}
