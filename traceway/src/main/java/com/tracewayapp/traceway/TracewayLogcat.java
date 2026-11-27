package com.tracewayapp.traceway;

import android.util.Log;

/**
 * Where the {@code com.tracewayapp.symbols} Gradle plugin's {@code logcat}
 * rewrite sends the app's calls to {@code android.util.Log} ({@code v},
 * {@code d}, {@code i}, {@code w}, {@code e}, {@code wtf}, {@code println}),
 * in the app's classes and its dependencies'. Each method calls the real
 * {@code Log} method first, with the same arguments, and returns its result
 * (an exception it throws reaches the caller as before); then the line is
 * recorded as a console log record with {@code log.source = "logcat"} and
 * {@code log.tag}, which the collection settings ({@code signals.console}
 * mode, minimum severity, sample rate) filter like any log line. Before
 * {@code Traceway.init}, after {@code shutdown} or with
 * {@code captureLogs = false} it only logs. Not meant to be called by hand.
 *
 * <p>Levels: {@code v} trace, {@code d} debug, {@code i} info, {@code w}
 * warn, {@code e} error, {@code wtf} (assert) fatal.
 *
 * <p>Written in Java so that what the app passes reaches {@code Log} as it
 * is, a null included.
 */
public final class TracewayLogcat {
    private TracewayLogcat() {
    }

    public static int v(String tag, String msg) {
        int r = Log.v(tag, msg);
        LogcatSink.record(Log.VERBOSE, tag, msg, null);
        return r;
    }

    public static int v(String tag, String msg, Throwable tr) {
        int r = Log.v(tag, msg, tr);
        LogcatSink.record(Log.VERBOSE, tag, msg, tr);
        return r;
    }

    public static int d(String tag, String msg) {
        int r = Log.d(tag, msg);
        LogcatSink.record(Log.DEBUG, tag, msg, null);
        return r;
    }

    public static int d(String tag, String msg, Throwable tr) {
        int r = Log.d(tag, msg, tr);
        LogcatSink.record(Log.DEBUG, tag, msg, tr);
        return r;
    }

    public static int i(String tag, String msg) {
        int r = Log.i(tag, msg);
        LogcatSink.record(Log.INFO, tag, msg, null);
        return r;
    }

    public static int i(String tag, String msg, Throwable tr) {
        int r = Log.i(tag, msg, tr);
        LogcatSink.record(Log.INFO, tag, msg, tr);
        return r;
    }

    public static int w(String tag, String msg) {
        int r = Log.w(tag, msg);
        LogcatSink.record(Log.WARN, tag, msg, null);
        return r;
    }

    public static int w(String tag, String msg, Throwable tr) {
        int r = Log.w(tag, msg, tr);
        LogcatSink.record(Log.WARN, tag, msg, tr);
        return r;
    }

    public static int w(String tag, Throwable tr) {
        int r = Log.w(tag, tr);
        LogcatSink.record(Log.WARN, tag, null, tr);
        return r;
    }

    public static int e(String tag, String msg) {
        int r = Log.e(tag, msg);
        LogcatSink.record(Log.ERROR, tag, msg, null);
        return r;
    }

    public static int e(String tag, String msg, Throwable tr) {
        int r = Log.e(tag, msg, tr);
        LogcatSink.record(Log.ERROR, tag, msg, tr);
        return r;
    }

    public static int wtf(String tag, String msg) {
        int r = Log.wtf(tag, msg);
        LogcatSink.record(Log.ASSERT, tag, msg, null);
        return r;
    }

    public static int wtf(String tag, Throwable tr) {
        int r = Log.wtf(tag, tr);
        LogcatSink.record(Log.ASSERT, tag, null, tr);
        return r;
    }

    public static int wtf(String tag, String msg, Throwable tr) {
        int r = Log.wtf(tag, msg, tr);
        LogcatSink.record(Log.ASSERT, tag, msg, tr);
        return r;
    }

    /**
     * {@code Log.println(priority, tag, msg)}; {@code minPriority} is the
     * plugin's {@code logcat.minLevel}: a line below it is only logged.
     */
    public static int println(int priority, String tag, String msg, int minPriority) {
        int r = Log.println(priority, tag, msg);
        if (priority >= minPriority) LogcatSink.record(priority, tag, msg, null);
        return r;
    }
}
