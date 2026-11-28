package com.tracewayapp.traceway;

import android.util.Log;
import java.util.ArrayList;
import java.util.List;

/** A stand-in for the SDK's bridge in the plugin's tests: logs through Log first, as the real one does, then keeps the call. */
public final class TracewayLogcat {
    public static final List<String> recorded = new ArrayList<>();

    private TracewayLogcat() {}

    private static int keep(int result, String name, String tag) {
        recorded.add(name + "|" + tag);
        return result;
    }

    public static int v(String tag, String msg) { return keep(Log.v(tag, msg), "v", tag); }
    public static int v(String tag, String msg, Throwable tr) { return keep(Log.v(tag, msg, tr), "v", tag); }
    public static int d(String tag, String msg) { return keep(Log.d(tag, msg), "d", tag); }
    public static int d(String tag, String msg, Throwable tr) { return keep(Log.d(tag, msg, tr), "d", tag); }
    public static int i(String tag, String msg) { return keep(Log.i(tag, msg), "i", tag); }
    public static int i(String tag, String msg, Throwable tr) { return keep(Log.i(tag, msg, tr), "i", tag); }
    public static int w(String tag, String msg) { return keep(Log.w(tag, msg), "w", tag); }
    public static int w(String tag, String msg, Throwable tr) { return keep(Log.w(tag, msg, tr), "w", tag); }
    public static int w(String tag, Throwable tr) { return keep(Log.w(tag, tr), "w", tag); }
    public static int e(String tag, String msg) { return keep(Log.e(tag, msg), "e", tag); }
    public static int e(String tag, String msg, Throwable tr) { return keep(Log.e(tag, msg, tr), "e", tag); }
    public static int wtf(String tag, String msg) { return keep(Log.wtf(tag, msg), "wtf", tag); }
    public static int wtf(String tag, Throwable tr) { return keep(Log.wtf(tag, tr), "wtf", tag); }
    public static int wtf(String tag, String msg, Throwable tr) { return keep(Log.wtf(tag, msg, tr), "wtf", tag); }

    public static int println(int priority, String tag, String msg, int minPriority) {
        int r = Log.println(priority, tag, msg);
        if (priority >= minPriority) recorded.add("println" + priority + "|" + tag);
        return r;
    }
}
