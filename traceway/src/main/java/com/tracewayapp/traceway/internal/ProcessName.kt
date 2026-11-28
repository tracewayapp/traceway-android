package com.tracewayapp.traceway.internal

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import com.tracewayapp.traceway.guarded

/** Which process of the app this is, told without disk I/O (a StrictMode policy may watch `Application.onCreate`). */
internal object ProcessName {
    /** Replaces the platform's answer in tests. */
    @Volatile
    internal var override: String? = null

    /** This process's name (`com.example.app`, `com.example.app:sync`), or null when it cannot be told. */
    fun of(app: Context): String? = override ?: guarded("processName", null) {
        if (Build.VERSION.SDK_INT >= 28) {
            Application.getProcessName()
        } else {
            val pid = Process.myPid()
            (app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
                ?.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName
        }
    }

    /** Whether this is the app's main process, the one named after its package; true when that cannot be told. */
    fun isMain(app: Context): Boolean {
        val name = of(app) ?: return true
        return name == guarded("packageName", name) { app.packageName }
    }

    /**
     * The name of this process's own state directory: `base` in the main
     * process, `base-sync` in `com.example.app:sync`.
     */
    fun stateDirectoryName(app: Context, base: String): String {
        if (isMain(app)) return base
        val name = of(app) ?: return base
        val suffix = name.substringAfter(':', name).replace(Regex("[^A-Za-z0-9._-]"), "_").take(64)
        return if (suffix.isEmpty()) base else "$base-$suffix"
    }
}
