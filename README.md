# Traceway Android SDK

Error tracking for native Android apps. Capture exceptions with full stack
traces, plus the last ~10 seconds of logs, HTTP calls, navigation transitions,
and custom breadcrumbs — automatically.

This is the Android-only counterpart to the Flutter
[`traceway`](https://github.com/tracewayapp/traceway-flutter) package. The wire
format is identical, so the same Traceway backend ingests reports from both.

> **No screen recording.** Unlike the Flutter SDK, this library does not
> record video. The screen-capture options on `TracewayOptions` are accepted
> for API parity but have no effect.

## Features

- Automatic capture of all uncaught Java/Kotlin exceptions on every thread
- Full JVM stack traces
- **Logs** — every `println` / `System.out` / `System.err` line from the last ~10 seconds
- **Actions** — HTTP requests (via OkHttp interceptor), activity transitions, and custom breadcrumbs
- Disk persistence — pending exceptions survive app restarts
- Gzip-compressed transport
- Simple one-line setup

## Installation

This SDK is published as a standard Android `aar`. Add it to your app module:

```kotlin
dependencies {
    implementation("com.tracewayapp:traceway:1.0.0")
}
```

Until a published artifact is available, include the `:traceway` module from
this repository directly.

## Quick Start

Wrap your app with `Traceway.init(...)` from your `Application.onCreate()`.
This wraps the whole process — every uncaught exception, on every thread, is
captured automatically.

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

Register the application in `AndroidManifest.xml`:

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
|---|---|---|
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

## Network capture

Add the OkHttp interceptor to any `OkHttpClient` you build:

```kotlin
val client = OkHttpClient.Builder()
    .addInterceptor(TracewayOkHttpInterceptor())
    .build()
```

Every HTTP call routed through that client (Retrofit, Coil, Glide via OkHttp,
your own usage) is recorded as a `network` event with method, URL, status,
duration, and byte counts.

For non-OkHttp HTTP clients, record events manually:

```kotlin
TracewayClient.instance?.recordNetworkEvent(
    NetworkEvent(method = "GET", url = "https://...", durationMs = 42L, statusCode = 200)
)
```

## Custom actions

```kotlin
Traceway.recordAction(
    category = "cart",
    name = "add_item",
    data = mapOf("sku" to "SKU-123", "qty" to 2),
)
```

## Logs

`println(...)` and any other write to `System.out` / `System.err` is mirrored
into the rolling log buffer. Calls that go straight to `android.util.Log`
bypass `System.out` and are not captured automatically. To capture every
Logcat line, use a Timber tree (or similar) that calls
`TracewayClient.instance?.recordLog(message, level)`.

## What gets shipped with each exception

Each captured exception includes a `sessionRecordings[]` entry with:

- `logs[]` — every captured `println` from the last ~10s
- `actions[]` — HTTP requests, activity transitions, custom breadcrumbs from the last ~10s
- `startedAt` / `endedAt` — timestamps spanning the captured window

The wire format is identical to the Flutter SDK's, so the same Traceway
backend handles both without changes.

## Disabling channels

```kotlin
TracewayOptions(
    captureLogs = false,
    captureNetwork = false,
    captureNavigation = false,
)
```

## Running the tests

The library ships with two test suites that mirror the Flutter SDK's setup.

### JVM unit tests

Fast, no device required. Cover wire-format JSON serialization, the rolling
event buffer, connection-string parsing, ISO-8601 timestamps, the disk-backed
exception store, and the full client flow against a fake `ReportSender`.

```bash
gradle wrapper --gradle-version 8.4   # one-time
./gradlew :traceway:testDebugUnitTest
```

CI runs this automatically on every push and pull request via
[`.github/workflows/unit-tests.yml`](.github/workflows/unit-tests.yml).

### Instrumented (on-device) tests

Drive every exception source the SDK is supposed to handle and assert that
the gzipped JSON report posted to a local `MockWebServer` contains the
expected stack trace. Scenarios include:

- `captureException` / `captureMessage` — direct API calls
- Button click — uncaught throw inside `View.OnClickListener`
- Dialog button — uncaught throw inside an `AlertDialog` button handler
- Background thread — uncaught throw caught by `Thread.defaultUncaughtExceptionHandler`
- Main `Handler.post` — uncaught async throw caught by the default UEH
- After navigation — push to a second activity, throw, verify the navigation breadcrumb rides along on the same `sessionRecordings` entry
- `println` — mirrored into the rolling log buffer and attached to the next exception
- OkHttp interceptor — `NetworkEvent` recorded with method, URL, status, duration
- Activity lifecycle — `push` / `pop` `NavigationEvent`s recorded
- Disk persistence — exception captured during a 500 → reloaded and re-sent on next init
- Wire format — full HTTP `POST` with `Content-Encoding: gzip`, `Authorization: Bearer …`, and the exact JSON shape the Flutter SDK emits

To run them on a connected device or local emulator:

```bash
./gradlew :traceway:connectedDebugAndroidTest
```

CI runs the same suite on a real Pixel 8 (FTL `model=shiba,version=34`) via
[`.github/workflows/instrumented-tests.yml`](.github/workflows/instrumented-tests.yml).
The workflow matches the secrets used by the Flutter SDK's benchmark workflow:
`GCP_WORKLOAD_IDENTITY_PROVIDER`, `GCP_SERVICE_ACCOUNT`, `GCP_PROJECT_ID`, and
`GCS_BUCKET`.

Library `androidTest` APKs are self-instrumenting — the workflow passes the
same APK as both `--app` and `--test` to `gcloud firebase test android run`.

## License

MIT
