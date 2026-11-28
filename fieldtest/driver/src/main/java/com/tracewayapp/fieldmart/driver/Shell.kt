package com.tracewayapp.fieldmart.driver

import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Shell commands as the shell user, through UiAutomation (no adb on the device side). Arguments are split on
 * spaces and nothing is expanded, so commands never rely on quotes, globs or pipes.
 */
class Shell(private val ui: UiAutomation) {
    fun run(command: String): String {
        val pfd = ui.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    /** Starts [command] and returns at once; the returned stream ends when the command does. */
    fun start(command: String): FileInputStream = ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command))
}

/** The gateway's control routes (sdk-field-test AGENT-1 §2.1), on the device through `adb reverse`. */
class Gateway(private val base: String) {
    fun post(path: String, body: String): Pair<Int, String> = call("POST", path, body)

    fun get(path: String): Pair<Int, String> = call("GET", path, null)

    private fun call(method: String, path: String, body: String?): Pair<Int, String> = try {
        val c = URL(base.trimEnd('/') + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 3000
        c.readTimeout = 10000
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        code to text
    } catch (e: Throwable) {
        -1 to (e.toString())
    }
}

internal fun jsonString(s: String): String = buildString {
    append('"')
    for (c in s) when (c) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else -> if (c < ' ') append(String.format("\\u%04x", c.code)) else append(c)
    }
    append('"')
}
