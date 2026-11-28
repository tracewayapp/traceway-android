package android.util;

import java.util.ArrayList;
import java.util.List;

/** A stand-in for Android's Log in the plugin's tests: it keeps what it was called with. */
public final class Log {
    public static final int VERBOSE = 2;
    public static final int DEBUG = 3;
    public static final int INFO = 4;
    public static final int WARN = 5;
    public static final int ERROR = 6;
    public static final int ASSERT = 7;

    public static final List<String> calls = new ArrayList<>();

    private Log() {}

    private static int log(String name, Object tag, Object msg, Throwable tr) {
        calls.add(name + "|" + tag + "|" + msg + "|" + (tr == null ? "-" : tr.getMessage()));
        return 10 + calls.size();
    }

    public static int v(String tag, String msg) { return log("v", tag, msg, null); }
    public static int v(String tag, String msg, Throwable tr) { return log("v", tag, msg, tr); }
    public static int d(String tag, String msg) { return log("d", tag, msg, null); }
    public static int d(String tag, String msg, Throwable tr) { return log("d", tag, msg, tr); }
    public static int i(String tag, String msg) { return log("i", tag, msg, null); }
    public static int i(String tag, String msg, Throwable tr) { return log("i", tag, msg, tr); }
    public static int w(String tag, String msg) { return log("w", tag, msg, null); }
    public static int w(String tag, String msg, Throwable tr) { return log("w", tag, msg, tr); }
    public static int w(String tag, Throwable tr) { return log("w", tag, null, tr); }
    public static int e(String tag, String msg) { return log("e", tag, msg, null); }
    public static int e(String tag, String msg, Throwable tr) { return log("e", tag, msg, tr); }
    public static int wtf(String tag, String msg) { return log("wtf", tag, msg, null); }
    public static int wtf(String tag, Throwable tr) { return log("wtf", tag, null, tr); }
    public static int wtf(String tag, String msg, Throwable tr) { return log("wtf", tag, msg, tr); }
    public static int println(int priority, String tag, String msg) { return log("println" + priority, tag, msg, null); }
    public static boolean isLoggable(String tag, int level) { return true; }
    public static String getStackTraceString(Throwable tr) { return String.valueOf(tr); }
}
