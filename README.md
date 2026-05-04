<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/tracewayapp/traceway/main/Traceway%20Logo%20White.png" />
    <source media="(prefers-color-scheme: light)" srcset="https://raw.githubusercontent.com/tracewayapp/traceway/main/Traceway%20Logo.png" />
    <img src="https://raw.githubusercontent.com/tracewayapp/traceway/main/Traceway%20Logo.png" alt="Traceway" width="200" />
  </picture>
</p>

<p align="center">
  <a href="https://central.sonatype.com/artifact/com.tracewayapp/traceway"><img src="https://img.shields.io/maven-central/v/com.tracewayapp/traceway.svg" alt="Maven Central"></a>
  <a href="https://github.com/tracewayapp/traceway-android/blob/main/LICENSE"><img src="https://img.shields.io/badge/license-MIT-blue.svg" alt="License"></a>
</p>

# Traceway Android SDK

Error tracking for native Android apps. Capture exceptions with full stack traces, plus the last ~10 seconds of logs, HTTP calls, navigation transitions, and custom breadcrumbs — automatically.

[Traceway](https://tracewayapp.com) is a completely open-source error tracking platform. You can [self-host](https://docs.tracewayapp.com/server) it or use [Traceway Cloud](https://tracewayapp.com).

This is the Android-only counterpart to the Flutter [`traceway`](https://github.com/tracewayapp/traceway-flutter) package. The wire format is identical, so the same Traceway backend ingests reports from both.

> **No screen recording.** Unlike the Flutter SDK, this library does not record video. The screen-capture options on `TracewayOptions` are accepted for API parity but have no effect.

## Features

- Automatic capture of all uncaught Java/Kotlin exceptions on every thread
- Full JVM stack traces
- **Logs** — every `println` / `System.out` / `System.err` line from the last ~10 seconds
- **Actions** — HTTP requests (via OkHttp interceptor), activity transitions, and custom breadcrumbs
- Disk persistence — pending exceptions survive app restarts
- Gzip-compressed transport
- Simple one-line setup

## Installation

Add the dependency to your app module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.tracewayapp:traceway:1.0.0")
}
```

The artifact is published to Maven Central, which is enabled by default in modern Android projects — no extra `repositories { ... }` entry required.

## Quick Start

Wrap your app with `Traceway.init(...)` from your `Application.onCreate()`. This wraps the whole process — every uncaught exception, on every thread, is captured automatically.

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Traceway.init(
            application = this,
            connectionString = "your-token@https://your-traceway-instance.com/api/report",
            options = TracewayOptions(version = "1.0.0"),
        )
    }
}
```

Register the application in `AndroidManifest.xml` and add the `INTERNET` permission:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <application android:name=".MyApp" ...>
        ...
    </application>
</manifest>
```

That's it. All uncaught exceptions are captured automatically.

## Manual Capture

```kotlin
// Capture a caught exception
try {
    riskyOperation()
} catch (e: Throwable) {
    Traceway.captureException(e)
}

// Capture a message
Traceway.captureMessage("User completed checkout")

// Force send pending events
Traceway.flush()
```

## Options

All field names mirror the Flutter SDK so existing config can be ported as-is.

| Option | Default | Description |
|--------|---------|-------------|
| `sampleRate` | `1.0` | Error sampling rate (0.0 - 1.0) |
| `debug` | `false` | Print debug info to logcat |
| `version` | `""` | App version string |
| `debounceMs` | `1500` | Milliseconds before sending batched events |
| `retryDelayMs` | `10000` | Retry delay on failed uploads |
| `maxPendingExceptions` | `5` | Max exceptions held in memory before oldest is dropped |
| `persistToDisk` | `true` | Persist pending exceptions to disk so they survive app restarts |
| `maxLocalFiles` | `5` | Max exception files stored on disk |
| `localFileMaxAgeHours` | `12` | Hours after which unsynced local files are deleted |
| `captureLogs` | `true` | Mirror every `println` / `System.out` / `System.err` line into the rolling log buffer |
| `captureNetwork` | `true` | Record network events sent through `TracewayOkHttpInterceptor` |
| `captureNavigation` | `true` | Record activity transitions |
| `eventsWindowMs` | `10000` | Rolling window kept in the log/action buffers (ms) |
| `eventsMaxCount` | `200` | Hard cap applied independently to logs and actions |
| `screenCapture` | `false` | **No effect on Android** — accepted for API parity |
| `capturePixelRatio` | `0.75` | **No effect on Android** |
| `maxBufferFrames` | `150` | **No effect on Android** |
| `fps` | `15` | **No effect on Android** |

## Logs & Actions

Every captured exception ships with the last ~10 seconds of session context, attached to the same `sessionRecordings[]` entry the Flutter SDK uses:

- **Logs** — every `println` / `System.out` / `System.err` line. Calls that go straight to `android.util.Log` bypass `System.out` and are not captured automatically. To capture every Logcat line, use a Timber tree (or similar) that calls `TracewayClient.instance?.recordLog(message, level)`.
- **Actions** are split into three channels:
  - **Network** — every HTTP request via the OkHttp interceptor (method, URL, status, duration, byte counts).
  - **Navigation** — activity push / pop transitions, recorded automatically once `Traceway.init(...)` runs.
  - **Custom** — anything you call `Traceway.recordAction(...)` with.

Logs and actions are kept in two separate rolling buffers, each capped at 200 entries / 10 seconds. They ship inside `sessionRecordings[].logs` and `sessionRecordings[].actions` on the wire, with `startedAt` / `endedAt` ISO 8601 timestamps spanning the captured window.

### Network capture

Add the OkHttp interceptor to any `OkHttpClient` you build:

```kotlin
val client = OkHttpClient.Builder()
    .addInterceptor(TracewayOkHttpInterceptor())
    .build()
```

Every HTTP call routed through that client (Retrofit, Coil, Glide via OkHttp, your own usage) is recorded as a `network` event.

For non-OkHttp HTTP clients, record events manually:

```kotlin
TracewayClient.instance?.recordNetworkEvent(
    NetworkEvent(method = "GET", url = "https://...", durationMs = 42L, statusCode = 200)
)
```

### Record a custom action

```kotlin
Traceway.recordAction(
    category = "cart",
    name = "add_item",
    data = mapOf("sku" to "SKU-123", "qty" to 2),
)
```

### Disable a channel

Each channel can be turned off individually via `TracewayOptions`:

```kotlin
TracewayOptions(
    captureLogs = false,
    captureNetwork = false,
    captureNavigation = false,
)
```

## What Gets Captured Automatically

- **Uncaught Java/Kotlin exceptions** on every thread via `Thread.setDefaultUncaughtExceptionHandler`
- **View click handler throws** — uncaught exceptions inside `View.OnClickListener`, `AlertDialog` button handlers, etc.
- **Background thread throws** — anything that escapes a `Thread`'s `run()` body
- **Main `Handler.post` throws** — async exceptions on the main looper
- **Activity lifecycle transitions** — `push` / `pop` via `Application.ActivityLifecycleCallbacks`

## Platform Support

| Platform | Error Tracking | Screen Recording |
|----------|---------------|------------------|
| Android (API 21+) | Yes | No |

For Flutter apps, use the [`traceway`](https://github.com/tracewayapp/traceway-flutter) package instead — it captures the same wire format plus screen recording and works across iOS, Android, and macOS.

## Running the example app

The `:example` module is a tiny throwaway app with buttons that exercise every capture path (uncaught throw, caught throw, custom action, OkHttp request).

To point it at your own Traceway backend, drop your DSN into `local.properties` at the repo root (this file is gitignored, so the token never lands in source control):

```properties
traceway.dsn=YOUR_TOKEN@https://your-traceway-instance.com/api/report
```

Then run from Android Studio (Run ▸ `example`) or from the CLI:

```bash
./gradlew :example:installDebug
adb shell am start -n com.tracewayapp.traceway.example/.MainActivity
```

If `local.properties` is missing or has no `traceway.dsn` key, the app falls back to a placeholder DSN that won't reach any real server.

## Running the tests

Two test suites mirror the Flutter SDK's setup.

**JVM unit tests** — fast, no device required. Cover wire-format JSON serialization, the rolling event buffer, connection-string parsing, ISO-8601 timestamps, the disk-backed exception store, and the full client flow against a fake `ReportSender`.

```bash
gradle wrapper --gradle-version 8.7   # one-time
./gradlew :traceway:testDebugUnitTest
```

**Instrumented (on-device) tests** — drive every exception source the SDK is supposed to handle and assert that the gzipped JSON report posted to a local `MockWebServer` contains the expected stack trace.

```bash
./gradlew :traceway:connectedDebugAndroidTest
```

CI runs both on every push and PR — JVM tests via [`unit-tests.yml`](.github/workflows/unit-tests.yml), instrumented tests on a real Pixel 8 in Firebase Test Lab via [`instrumented-tests.yml`](.github/workflows/instrumented-tests.yml).

## Publishing

Releases are cut by manually triggering the **Publish to Maven Central** workflow with the desired version — the workflow handles the version bump, Maven Central upload, commit, and tag.

```bash
gh workflow run "Publish to Maven Central" -f version=1.0.1
gh run watch
```

If publish fails, no commit or tag is created — fix the issue and re-run.

## Links

- [Traceway Website](https://tracewayapp.com)
- [Traceway GitHub](https://github.com/tracewayapp/traceway)
- [Documentation](https://docs.tracewayapp.com/client/android?sdk=android)
- [Flutter SDK](https://github.com/tracewayapp/traceway-flutter)

## License

MIT
