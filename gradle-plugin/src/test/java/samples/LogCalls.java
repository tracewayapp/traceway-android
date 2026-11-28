package samples;

import android.util.Log;

/** An app class calling Log the ways apps do, compiled by javac like the app's own. */
public class LogCalls {
    /** Every overload, each result summed: the bridge must return what Log returned. */
    public static int everyOverload() {
        Throwable tr = new IllegalStateException("boom");
        int sum = 0;
        sum += Log.v("T", "verbose");
        sum += Log.v("T", "verbose", tr);
        sum += Log.d("T", "debug");
        sum += Log.d("T", "debug", tr);
        sum += Log.i("T", "info");
        sum += Log.i("T", "info", tr);
        sum += Log.w("T", "warn");
        sum += Log.w("T", "warn", tr);
        sum += Log.w("T", tr);
        sum += Log.e("T", "error");
        sum += Log.e("T", "error", tr);
        sum += Log.wtf("T", "wtf");
        sum += Log.wtf("T", tr);
        sum += Log.wtf("T", "wtf", tr);
        return sum;
    }

    /** As Timber's DebugTree logs: println with a priority known when it runs, in a method whose stack is full. */
    public static int timberLike(int priority, String tag, String message) {
        return Log.println(priority, tag, message) + Log.println(priority, tag, message.length() > 2 ? message.substring(0, 2) : message);
    }

    /** Neither a Log call nor one with an unknown overload is touched. */
    public static boolean untouched() {
        return Log.isLoggable("T", Log.DEBUG) && Log.getStackTraceString(null) != null;
    }

    public long[] withLongs(long a, long b) {
        // A wide-stack method around a println: the extra argument must still fit.
        long c = a * b;
        Log.println(Log.WARN, "L", String.valueOf(c + a + b));
        return new long[] {a, b, c};
    }
}
