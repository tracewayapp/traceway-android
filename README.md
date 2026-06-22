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
| `proguardUuid` | `null` | R8/ProGuard build UUID for deobfuscating release crashes. Set to `BuildConfig.TRACEWAY_PROGUARD_UUID` (injected by the `com.tracewayapp.symbols` plugin). See [Deobfuscating release crashes](#deobfuscating-release-crashes-r8proguard) |
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

## Deobfuscating release crashes (R8/ProGuard)

Release builds run R8, which renames classes and methods and rewrites line numbers, so crashes arrive obfuscated. Traceway reverses them with the build's `mapping.txt`, matched to the crashing build by a ProGuard UUID that the SDK sends with every report.

The `com.tracewayapp.symbols` Gradle plugin (in [`gradle-plugin/`](gradle-plugin)) automates both halves: it injects a per-build UUID into `BuildConfig.TRACEWAY_PROGUARD_UUID` and uploads `mapping.txt` to your Traceway instance. It's published to Maven Central, so add `mavenCentral()` to your `pluginManagement` repositories in `settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()   // resolves com.tracewayapp.symbols
    }
}
```

Then apply and configure it on your app module:

```kotlin
plugins {
    id("com.android.application")
    id("com.tracewayapp.symbols") version "0.0.1"
}

android {
    buildFeatures { buildConfig = true }   // required for the injected UUID field
    buildTypes {
        release { isMinifyEnabled = true }
    }
}

traceway {
    uploadToken = "<your project upload token>"   // NOT the DSN/connection-string token
    url = "https://your-traceway-instance.com"    // instance base URL
    autoUpload = true                             // upload after assembleRelease; default false
    // proguardUuid = "..."                        // optional: pin the build UUID yourself
}
```

Then pass the injected UUID to the SDK so reported crashes carry the matching UUID:

```kotlin
Traceway.init(
    application = this,
    connectionString = BuildConfig.TRACEWAY_DSN,
    options = TracewayOptions(
        version = "1.0.0",
        proguardUuid = BuildConfig.TRACEWAY_PROGUARD_UUID,
    ),
)
```

Notes:

- **`uploadToken` is the project _upload token_** from the dashboard (the same token used for JavaScript source maps and iOS dSYMs), **not** the DSN/connection-string token. The wrong token is rejected with a 401.
- The injected UUID is derived from the module path, variant, and app version (`versionName` + `versionCode`), so **bump the version each release** to keep each build's mapping distinct — otherwise a new upload overwrites the previous release's mapping under the same UUID.
- `autoUpload` defaults to `false` so a release build never depends on backend availability. With it off, run the upload explicitly (recommended for CI):

  ```bash
  ./gradlew :app:assembleRelease :app:uploadReleaseTracewaySymbols
  ```

> The plugin currently ships inside this repo; the `:example` module applies it via `includeBuild("gradle-plugin")` in `settings.gradle.kts`.

### Manual upload (no plugin)

You can upload `mapping.txt` yourself and pin the UUID by hand — set the same value in `TracewayOptions(proguardUuid = ...)`:

```bash
curl -X POST https://your-traceway-instance.com/api/symbols/upload \
  -H "Authorization: Bearer <your project upload token>" \
  -F "proguard_uuid=<your build uuid>" \
  -F "files=@app/build/outputs/mapping/release/mapping.txt"
```

See the [symbolicator docs](https://docs.tracewayapp.com/symbolicator/android) for the full upload API and how traces are resolved.

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

# Optional — only needed to exercise the com.tracewayapp.symbols plugin:
traceway.upload_token=YOUR_UPLOAD_TOKEN              # project upload token (not the DSN token)
traceway.upload_url=https://your-traceway-instance.com   # defaults to the DSN host if omitted
```

Then run from Android Studio (Run ▸ `example`) or from the CLI:

```bash
./gradlew :example:installDebug
adb shell am start -n com.tracewayapp.traceway.example/.MainActivity
```

The `:example` module also applies the `com.tracewayapp.symbols` plugin (via `includeBuild("gradle-plugin")`), so you can build a minified release and upload its mapping in one go to verify deobfuscation end to end:

```bash
./gradlew :example:assembleRelease :example:uploadReleaseTracewaySymbols
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

Two artifacts are published to Maven Central, each with its own workflow. Both bump the version, sign and upload to Sonatype Central, then commit + tag. If publish fails, no commit or tag is created — fix the issue and re-run.

**SDK** (`com.tracewayapp:traceway`) — trigger the **Publish to Maven Central** workflow:

```bash
gh workflow run "Publish to Maven Central" -f version=1.0.1
gh run watch
```

**Gradle plugin** (`com.tracewayapp.symbols`) — trigger the **Publish Gradle Plugin to Maven Central** workflow (tagged `plugin-v<version>`, versioned independently of the SDK). It defaults to a dry run that builds, tests, signs, and publishes to a throwaway local repo without touching Central or the repo, so a misfire is harmless. Once the dry run is green, release for real by unchecking `dry_run`:

```bash
# Validate first (default):
gh workflow run "Publish Gradle Plugin to Maven Central" -f version=0.0.2

# Release once the validation run is green:
gh workflow run "Publish Gradle Plugin to Maven Central" -f version=0.0.2 -f dry_run=false
gh run watch
```

Both workflows are thin wrappers. The plugin publish logic lives in [`scripts/publish-plugin.sh`](scripts/publish-plugin.sh), which you can also run locally: `scripts/publish-plugin.sh 0.0.2` publishes to `~/.m2` for a smoke test, `--release` pushes to Central, and `-- --dry-run` validates the task graph without credentials.

Both share the same Sonatype Central account and signing key (`MAVEN_CENTRAL_USERNAME`/`MAVEN_CENTRAL_PASSWORD` and `SIGNING_KEY`/`SIGNING_PASSWORD` repo secrets); the `com.tracewayapp` namespace is already verified, which also covers the plugin's `com.tracewayapp.symbols` marker.

## Links

- [Traceway Website](https://tracewayapp.com)
- [Traceway GitHub](https://github.com/tracewayapp/traceway)
- [Documentation](https://docs.tracewayapp.com/client/android?sdk=android)
- [Flutter SDK](https://github.com/tracewayapp/traceway-flutter)

## License

MIT
