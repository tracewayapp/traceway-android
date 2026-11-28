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

Error tracking, sessions, wireframe session replay and app performance metrics for native Android apps, built on the official [OpenTelemetry Java SDK](https://github.com/open-telemetry/opentelemetry-java). Exceptions arrive with full stack traces, the breadcrumbs that led up to them and, with `screenCapture = true`, a replay of the last seconds on screen.

[Traceway](https://tracewayapp.com) is a completely open-source error tracking platform. You can [self-host](https://docs.tracewayapp.com/server) it or use [Traceway Cloud](https://tracewayapp.com).

## Features

- Automatic capture of uncaught exceptions on every thread, written to disk before the process dies and sent at once or at the next start
- **Native crashes and ANRs** from Android's own record of how the process ended (`ApplicationExitInfo`, Android 11+): a native crash with its signal, abort message and frames from the tombstone, an ANR with the main thread's stack, reported at the next start
- **App hangs** — a main thread blocked for 2 s or more is an `AppHang` issue with the stack it was stuck in, taken while it was blocked
- **Logcat lines** — with the Gradle plugin, the app's and its libraries' `android.util.Log` calls (Timber's too) are log lines on the session timeline
- Full JVM stack traces, deobfuscated server-side for R8/ProGuard builds
- **OpenTelemetry inside** — logs and spans are records of the official OpenTelemetry Java SDK (`opentelemetry-sdk` 1.68) and leave as OTLP/HTTP protobuf, built by its marshalers
- **Ring buffer** — logs, HTTP requests, screens, taps and custom events stay on the device and are uploaded only when something goes wrong
- **Remote config over OpAMP** — change what is collected from the dashboard, per app version, user or device, without a release; or ship the same settings in the app with `collection`
- **Captures on demand** — from the dashboard, or from a recording trigger on the device (a journey of taps, screens and log lines): the buffered lookback goes up, then breadcrumbs and the replay stream until the capture ends
- **Wireframe session replay** — the view tree (and Jetpack Compose screens) recorded as HTML of positioned boxes and text, replayed by the dashboard's rrweb player: tens of kilobytes per clip, no screenshots, no video encoded. The pictures image views show can be recorded too, on Wi-Fi by default
- **Interactions** — `Traceway.startInteraction("checkout")` traces one user action: the requests it makes are its child spans, and an exception that fails it links to its trace; `Traceway.tracer` for spans of your own
- **App performance metrics** — cold, warm and hot start, time to initial and full display per screen, slow and frozen frames, hangs, crashes, ANRs, low-memory kills, sessions, users, peak memory and HTTP requests, aggregated on the device and sent as OTLP metrics
- **Taps that name the view** — every tap is recorded with a CSS-like selector (`MaterialButton#pay`, `ImageButton[label="Delete"]`) that click steps of recording triggers match
- Privacy masking for replays and tap records: `view.tracewayMask`, `android:tag="traceway-mask"`, or `Modifier.tracewayMask()` in Compose
- Network noise (a lost connection, a timeout, a 4xx) is not reported as an issue by default; `ignoreErrors` and `beforeCapture` filter the rest
- Sessions, with the breadcrumb timeline of each one
- **Any OpenTelemetry backend** — send logs, spans and metrics to an OpenTelemetry Collector, Grafana, Honeycomb or any OTLP/HTTP receiver with `telemetry`, next to Traceway or without it (standalone)
- Telemetry that failed to send is kept on disk and retried; the ring buffer is saved when the app goes to the background, so a session that goes on after the system killed the app still has its breadcrumbs

## Requirements

- Android `minSdk` 21, `compileSdk` 35 or newer to build against the published artifact.
- OpenTelemetry Java uses `java.util.function` (Android has it from API 24) and `java.time` (from API 26), so **apps whose `minSdk` is below 26 must enable core library desugaring** (see [Installation](#installation)). Without it the app still runs, but the SDK does not work there: on API 21–23 `Traceway.init` logs a warning and returns `null`; on API 24–25 it starts, and what it records through OpenTelemetry (exceptions, logs, spans), which reads the time with `java.time`, is dropped.
- `opentelemetry-api` (1.68) comes with the SDK on the app's compile classpath: `Traceway.tracer` is an OpenTelemetry `Tracer`. The SDK itself stays private to Traceway.
- Network access. The library's manifest declares `INTERNET` and `ACCESS_NETWORK_STATE` (which the `wifi` replay image policy reads); the manifest merger adds both to your app.
- A Traceway project (Traceway Cloud, or a self-hosted server with OTLP/OpAMP support for Android projects), or any OTLP/HTTP receiver for [standalone](#standalone-without-traceway) use.
- The Gradle plugin needs the Android Gradle Plugin 8.0 or newer and JDK 17 (tested with AGP 8.7). The `traceway { }` snippets below assign with `=`, which a Kotlin build script accepts from Gradle 8.2 (the Gradle that AGP 8.2 and newer require); on Gradle 8.0 or 8.1 write `uploadToken.set(…)`, `autoUpload.set(true)` and so on.

## Installation

**1. The SDK**:

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("com.tracewayapp:traceway:2.1.0")
}
```

**2. The Gradle plugin** (recommended): it records `android.util.Log` lines (Timber's too) and makes release crashes readable (it uploads R8's `mapping.txt` and tells the SDK which build it is). The SDK and the plugin share one version. The plugin is on Maven Central:

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()   // resolves com.tracewayapp.symbols
    }
}
```

```kotlin
// app/build.gradle.kts
plugins {
    id("com.android.application")
    id("com.tracewayapp.symbols") version "2.1.0"
}
```

Applied, it rewrites `Log` calls for [logcat capture](#logcat) and puts the build's ProGuard UUID where the SDK finds it; uploading the mapping needs an upload token, see [Crashes and symbolication](#crashes-and-symbolication).

**3. Desugaring**: apps whose `minSdk` is below 26 enable core library desugaring, as every app using OpenTelemetry on Android does:

```kotlin
android {
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
}
```

## Quick start

Initialize Traceway from your `Application.onCreate()` with your project's token, from the project's Connection page:

```kotlin
import android.app.Application
import com.tracewayapp.traceway.Traceway
import com.tracewayapp.traceway.TracewayOptions

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Traceway.init(
            this,
            TracewayOptions(
                token = BuildConfig.TRACEWAY_TOKEN,
                screenCapture = true, // wireframe session replay
            ),
        )
    }
}
```

Keep the token out of the source: put `TRACEWAY_TOKEN=YOUR_TOKEN` in `local.properties` (or the `TRACEWAY_TOKEN` environment variable on CI) and hand it to the app as a `BuildConfig` field:

```kotlin
// app/build.gradle.kts
import java.util.Properties

val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val tracewayToken = System.getenv("TRACEWAY_TOKEN") ?: localProperties.getProperty("TRACEWAY_TOKEN") ?: ""

android {
    buildFeatures { buildConfig = true }
    defaultConfig {
        buildConfigField("String", "TRACEWAY_TOKEN", "\"$tracewayToken\"")
    }
}
```

The server is Traceway Cloud (`https://cloud.tracewayapp.com`) unless you host Traceway yourself: then pass its URL as `baseUrl = "https://traceway.mycompany.com"` (`TRACEWAY_URL` is the usual name for it). The app version is the app's `versionName`; set `version` to report another.

Register the application class in `AndroidManifest.xml`, or `onCreate()` never runs (the `INTERNET` permission comes with the library's manifest):

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:name=".MyApp" ...>
        ...
    </application>
</manifest>
```

That's it. Uncaught exceptions on every thread are captured automatically, with the breadcrumbs and replay of the seconds before them.

- An invalid server URL or `telemetry` setting, or no token and no `telemetry`, does not take the app down: `Traceway.init` logs a warning (tag `Traceway`) and returns `null`, and the app runs without the SDK. Every `Traceway` method is a no-op before `init` (the first such call logs one warning).
- The SDK runs in the app's main process. In another process (a `:sync` service, a `:remote` widget) `Traceway.init` returns `null`; set `allProcesses = true` to run it there too, each process with its own state.
- Without a token the SDK runs [standalone](#standalone-without-traceway), sending everything to another OpenTelemetry receiver.

From Java, build the options with `TracewayOptions.builder()`: one setter per option, and `null` means the default.

```java
public class MyApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Traceway.init(this, TracewayOptions.builder()
                .token(BuildConfig.TRACEWAY_TOKEN)
                .screenCapture(true)
                .build());
    }
}
```

## Manual capture

From Java every method is a static on `Traceway`: `Traceway.captureException(e)`, `Traceway.setAttribute("user.id", id)`. With `debug = true` the SDK logs why an exception or a message was dropped (the `ignoreErrors` pattern it matched, or `beforeCapture`).

### Errors and messages

```kotlin
// A caught exception, with optional attributes
try {
    riskyOperation()
} catch (e: Exception) {
    Traceway.captureException(e, mapOf("checkout.step" to "pay"))
}

// A message, reported as an issue
Traceway.captureMessage("User completed checkout")
```

Exceptions and messages carry the screen they happened on: `app.screen.name`, `app.screen.route` (when [`setRoute`](#naming-screens) named it), `app.screen.previous`, `app.screen.duration_ms` (how long the screen had been shown) and `viewport` (`<width>x<height>` in dp). Attributes you pass win over these.

Before an exception or a message is recorded, `ignoreErrors` and `beforeCapture` may drop it:

```kotlin
TracewayOptions(
    // Matched against the first line "Type: message"; a String is a substring, a Regex finds a match.
    ignoreErrors = TracewayOptions.DEFAULT_IGNORE_ERRORS + listOf("PaymentDeclinedException", Regex("^java\\.io\\.")),
    beforeCapture = { captured -> captured.attributes["checkout.step"] != "probe" },
)
```

- `ignoreErrors = null` (the default) uses `DEFAULT_IGNORE_ERRORS`: `UnknownHostException`, `SocketTimeoutException`, `ConnectException`, `SSLHandshakeException`, `NoRouteToHostException`, `Connection reset`, `/timeout/i`, `/status code (of )?4\d{2}/` and `/failed: 4\d{2}/`. A phone losing its connection, a request timing out or a server saying no is the network's doing, not a bug in the app. An empty list reports everything. Stack frames are never matched: a method named like a pattern does not silence the errors that pass through it.
- `beforeCapture` sees a `TracewayCapturedError` (`error`, `type`, `message`, `isMessage`, `attributes`, `stackTrace`) and returns false to drop it. A callback that throws keeps the record and logs the throwable in debug.
- Crashes (uncaught exceptions that end the process) are always reported and pass through neither; nor do the native crashes and ANRs of earlier runs. An `AppHang` passes through both, the one the process did not survive included.

### Logs

Every `System.out` / `System.err` line (`println`, `printStackTrace`) is a log line on the session timeline, and so is every `Traceway.log` call:

```kotlin
Traceway.log("Payment declined", TracewayLogLevel.WARN, mapOf("order.id" to order.id))
```

Calls to `android.util.Log` do not pass through `System.out`. With the [Gradle plugin](#installation), they are log lines too: the plugin rewrites the calls to `Log.v/d/i/w/e/wtf/println` in your classes and your dependencies' (Timber logs through `Log.println`, so it is covered) to `TracewayLogcat`, which calls the real `Log` first, with the same arguments and result, then records the line. Without the plugin, forward them with `Traceway.log`, from a Timber tree for example.

A log line has no event name (the `console` signal), its level as severity number and text (`TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`, `FATAL`), `log.source` (`api` for `Traceway.log`, `stdout` and `stderr` for printed lines, `logcat` for `Log` calls, with their tag as `log.tag`) and your attributes. Logcat levels: `v` trace, `d` debug, `i` info, `w` warn, `e` error, `wtf` fatal; a throwable's stack follows the message, as logcat prints it. The console's mode, minimum severity (`signals.console.minSeverity`, `info` by default) and sample rate in the collection settings apply to all of them; `captureLogs = false` turns them all off. `Traceway.recordLog(message, severity)` still works and is deprecated.

#### Logcat

```kotlin
// app/build.gradle.kts, with the com.tracewayapp.symbols plugin (see Installation)
traceway {
    logcat {
        enabled = true                // default true
        minLevel = "info"             // default "info": verbose, debug, info, warn, error or assert
        includeDependencies = true    // default true: libraries' calls too (Timber lives in one)
        disabledFor("benchmark")      // variants or build types left as they are
    }
}
```

- Needs the SDK 2.1.0 or newer on the variant's runtime classpath. With an older SDK the plugin leaves `Log` calls alone and the build logs one warning per variant; the SDK's own classes are never rewritten.
- Calls below `minLevel` stay plain `Log` calls, so they cost nothing; `Log.println`'s level is only known when it runs, so `TracewayLogcat` checks it then.
- `includeDependencies = false` rewrites your module's classes only (quicker first builds, no Play services or ad SDK lines), but then Timber, which logs from its own library, is not covered.
- Apps that strip `Log` calls from release builds with R8 (`-assumenosideeffects class android.util.Log { … }`) keep them stripped: the plugin reads the variant's ProGuard files and adds the same rule for `TracewayLogcat`, so the lines you removed are not recorded either.

### Actions and events

```kotlin
// A named product event; triggers can match it by name
Traceway.track("checkout_failed", mapOf("reason" to "card_declined"))

// A breadcrumb on the session timeline
Traceway.recordAction(category = "cart", name = "add_item", data = mapOf("sku" to "SKU-123", "qty" to 2))
```

### Attributes and users

```kotlin
// Attributes on every later record, also usable to target this user or device from the dashboard
Traceway.setAttribute("user.id", user.id)
Traceway.setAttributes(mapOf("plan" to "pro"))
Traceway.removeAttribute("plan")
Traceway.clearAttributes()

// The current session (`session.id` on every record), and this installation (`service.instance.id`;
// null for the moment right after init, until the SDK has read it)
Traceway.currentSessionId
Traceway.getInstanceId()

// Send what is ready now (blocks; call it off the main thread)
Traceway.flush()
```

There is no `setUser`: the user is the `user.id` attribute, as in the browser and Flutter SDKs. At most 64 attributes are kept; each change asks the server to evaluate its rules again about 2 seconds later.

### Interactions

An interaction traces one user action from start to end:

```kotlin
val checkout = Traceway.startInteraction("checkout", mapOf("cart.items" to 3))
try {
    checkout.run { api.placeOrder(cart) }   // requests made here are its child spans
} catch (e: IOException) {
    checkout.captureException(e)            // carries its trace, marks it failed
} finally {
    checkout.end()
}
```

- It is a span named after the action with `traceway.interaction: true` and the attributes you pass; `checkout.traceId` and `checkout.spanId` name it, and `checkout.setAttribute(key, value)` adds to it.
- `run { }` makes it current on the calling thread while the block runs: requests recorded there are its child spans, and exceptions, messages and logs recorded there carry its trace. A request made on another thread (an OkHttp `enqueue`, a coroutine that moved to another dispatcher) is not part of it. An interaction started inside another's `run` is its child.
- `end(failed = false)` ends it; only the first call counts. A failed one (after `captureException`, or `end(failed = true)`) is sent at once with its exception; others follow the `network` signal like request spans.
- Before `Traceway.init`, in the first moments after it (while the SDK starts on its own thread), or after `shutdown`, an interaction records nothing and `run` still runs its block.
- From Java, `try (TracewayScope scope = checkout.makeCurrent()) { … }` makes it current like `run`, in a block that may throw checked exceptions.

### Your own spans

`Traceway.tracer` (Java: `Traceway.getTracer()`) is Traceway's OpenTelemetry `Tracer`. Spans you make with it are sent with Traceway's other spans (the `network` signal decides when), carry the session id (`session.id`), and are children of the interaction whose `run` they start in:

```kotlin
val span = Traceway.tracer?.spanBuilder("decode-image")?.startSpan()
try {
    decode(bytes)
} finally {
    span?.end()
}
```

It is null before `Traceway.init` (until the SDK has started, just after it) and after `shutdown`. Traceway never registers itself as the global OpenTelemetry (`GlobalOpenTelemetry`): an app with its own OpenTelemetry setup keeps it, untouched.

## How collection works

Every record is an OpenTelemetry log record or span and belongs to a *signal*. Each signal has a mode:

| Mode | Meaning |
|------|---------|
| `stream` | sent as soon as it is produced |
| `buffer` | kept in a ring buffer on the device (2 MiB / 10 minutes by default) until a trigger or a capture uploads it |
| `off` | dropped |

| Signal | Source | Default |
|--------|--------|---------|
| `errors` | uncaught exceptions, `captureException` | stream |
| `messages` | `captureMessage` | stream |
| `console` | `System.out` / `System.err`, `Traceway.log` | buffer |
| `network` | HTTP requests (client spans), interactions | buffer |
| `navigation` | activities, AndroidX fragments, `Traceway.recordNavigation` | buffer |
| `userActions` | taps (`browser.user_action.click`) | buffer |
| `custom` | `Traceway.recordAction`, `Traceway.track` | buffer |
| replay | wireframe recording | buffer |

The built-in trigger fires on every exception: it uploads the buffered breadcrumbs of the last 10 minutes and the replay of the last 30 seconds, at most the frames the replay ring holds (`maxBufferFrames`). An app that never throws sends only its session and control records (`session.start`, `session.end`, `traceway.session.update`, `traceway.config.applied`) and its metrics. Clips are rationed to one per 5 seconds and 20 per session; breadcrumbs are still sent for the exceptions in between. The exception is handed to the exporter before the breadcrumbs it brings up, written to disk first as always: a server that takes a limited number of log records from a device at a time then leaves out old breadcrumbs, never the issue.

`recordAllSessions = true` streams every breadcrumb signal and the replay instead. The signal names, modes, triggers and limits are the browser and Flutter SDKs', so one rule can target all of them.

A burst of the same error is collapsed (identical errors within a second count as one, at most 60 errors a minute; the next one reported carries `traceway.error.suppressed_count`). Replay uploads are bounded per session (50 MiB and 10,000 rrweb events). When data is dropped this way, or replay uploads are given up, the SDK sends a `traceway.collection.health` record a second later (then after 2, 4, … up to 300 seconds) with the session's collection counters, `traceway.error.suppressed_total`, `traceway.context.evicted` and `traceway.replay.failed_segments`, and updates the session once.

The SDK builds its own `SdkLoggerProvider` and `SdkTracerProvider` from the OpenTelemetry Java SDK, with Traceway's processors in front of the official batch processors; the app's own OpenTelemetry setup (`GlobalOpenTelemetry`, its exporters) is left untouched. Records are encoded with the SDK's OTLP marshalers and posted by the SDK's own HTTP sender, so no HTTP library (OkHttp) is pulled into the app. Remote config uses a small built-in OpAMP plain-HTTP client, checked byte for byte against `opamp-go` (see `scripts/opamp-golden`).

## Remote config

The SDK polls the server over [OpAMP](https://opentelemetry.io/docs/specs/opamp/) (plain HTTP: at start, at the interval the server sets (5 minutes by default, 60 seconds while a capture targets the device), when the app returns to the foreground, and about 2 seconds after `Traceway.setAttribute` or a new session, so a rule targeting an attribute applies soon) and applies the collection settings you define under **Project → Data Collection** in the dashboard: signal modes and sample rates, buffer sizes, replay settings (`replay.mode`, `replay.sessionSampleRate`, `replay.maskAllInputs`, `replay.images`), extra triggers (for example "flush when `checkout_failed` happens twice in a minute") and on-demand captures of one user or device.

- The `capture*` options, `screenCapture`, `replayImages` and `sessions` are **ceilings**. Remote settings can lower what is collected but never raise it: with `captureNetwork = false` no rule can turn network capture on, and without `screenCapture = true` nothing is recorded. `sampleRate`, `baselineSampleRate` and `recordAllSessions` are defaults: a rule can change them in either direction.
- The last applied settings are cached, so they are in effect from the start of the next launch.
- Set `remoteConfig = false` to never poll. Standalone (no token) the SDK never polls either.

### Local config

`collection` takes the same settings document a rule holds and lays it over the built-in defaults, so an app can define its own policy and triggers without the server. With `remoteConfig = true` the server's rules apply on top of it; with `remoteConfig = false` it is the whole policy.

```kotlin
TracewayOptions(
    screenCapture = true,
    remoteConfig = false,
    collection = mapOf(
        "signals" to mapOf("network" to mapOf("mode" to "stream")),
        "buffer" to mapOf("maxAgeSeconds" to 300),
        "replay" to mapOf("images" to "always"),
        "triggers" to listOf(
            mapOf(
                "id" to "checkout-failed-twice",
                "steps" to listOf(
                    mapOf(
                        "match" to mapOf("key" to "event.name", "op" to "eq", "value" to "checkout_failed"),
                        "count" to 2,
                        "periodSeconds" to 60,
                    ),
                ),
                "actions" to listOf(
                    mapOf("type" to "capture", "durationSeconds" to 300, "lookbackSeconds" to 120, "replay" to true),
                ),
            ),
        ),
    ),
)
```

A value the SDK cannot use (an unknown mode, an out-of-range number, a malformed trigger) is skipped and the rest of the document applies. A document that names a `schemaVersion` other than 1 is ignored with a Logcat warning, and the defaults apply.

## Captures

A capture uploads what the device holds from its lookback window and then streams the selected signals, and the replay when it asks for it, until it ends. Records sent under a dashboard capture carry its id (`traceway.capture.ids`), so the dashboard shows when it is collecting.

- **From the dashboard**: the Capture button on a session or issue targets one installation (`service.instance.id`), user (`user.id`) or session. The device starts it at its next poll; cancelling it stops the streaming at the poll after that. Each capture's lookback is uploaded once per installation.
- **From a trigger** (`{"type": "capture", ...}` action): started on the device the moment the trigger fires. It survives app restarts until it ends; firing again only extends it.

While a capture streams replay, the recording leaves as consecutive segments of up to 10 seconds, each starting with a full snapshot. An exception during a streaming capture still gets a clip of its own.

### Recording triggers

The dashboard's *recording trigger* steps run on Android: journeys of taps, screens and logs. A step is one of:

| Step | Matches |
|------|---------|
| `{"event": {"type": "click", "value": "<selector>"}}` | a tap on a view the selector matches, or inside one |
| `{"event": {"type": "hover", "value": "<selector>"}}` | a mouse entering a matching view (ChromeOS, desktop mode; never touch) |
| `{"event": {"type": "navigation", "value": "/orders/{id}"}}` | arriving at a screen; `{id}` is one non-empty segment |
| `{"event": {"type": "log", "value": "Profile loaded", "mode": "contains"}}` | a console line or a `captureMessage` (`exact` by default) |

Steps match in order, each within its `withinSeconds` of the previous one; progress survives an app restart within the session. When a trigger that asks for replay fires, the session lists it as a recording reason with its sequence.

### Naming views

A click or hover step takes a selector in CSS syntax. When a finger lifts, the SDK hit-tests the point the way Android dispatches touches: front to back (elevation, then drawing order), into every visible view whose bounds contain the point after its scroll and transform, and inside a Jetpack Compose view on through the Compose semantics nodes under the point. That gives the chain of elements from the deepest one up to the window, and the selector runs over it: a tap matches when the element under the finger or any element above it matches, the way a browser click matches its target or an ancestor. Tapping the `TextView` inside a clickable row matches a selector for the row. Hover fires when a mouse pointer enters a matching view (ChromeOS, a tablet with a mouse), never for a finger or a stylus.

| Selector | Browser equivalent | Matches an element that |
|----------|--------------------|-------------------------|
| `MaterialButton` | tag name | is of that class or a subclass, by simple name in any case (`Button` matches a `MaterialButton`). A Compose node's type is its role (`Button`, `Checkbox`, `Switch`, `RadioButton`, `Tab`, `Image`, `DropdownList`) or what it is (`Text`, `TextField`, `Slider`, `ProgressIndicator`). `*` is any element |
| `#pay` | `id` | has `android:id="@+id/pay"` (the id's resource entry name), or `Modifier.testTag("pay")` |
| `.button` | class | has that role: `button`, `link`, `header`, `image`, `textField`, `slider`, `checkbox`, `toggle`, `selected` (from the view's class, its accessibility heading and selected state, a clickable span in its text, or the Compose role and semantics) |
| `[key="42"]`, `[identifier="..."]` | attribute | has that string `android:tag`, or that id or `testTag` |
| `[label="Delete"]` | attribute | has that content description or tooltip: its own, else the first one below it along single children (an `IconButton`'s `Icon`) |
| `[text="Pay"]` | none | shows that text: a `TextView`'s own (an `EditText` offers its hint, never what was typed), else the texts of up to three views under it, joined by a space |
| `[type="com.example.PayButton"]` | none | is of that class, by full or simple name, for a class that cannot be written as a tag |

- Attribute values take `=`, `^=`, `$=`, `*=`, `~=` and `|=`; quote a value that is not a plain name (`[key="42"]`).
- Combinators are descendant (space), child (`>`), adjacent (`+`) and general (`~`) sibling, over the view tree and, inside Compose, the semantics tree. Layout views sit between a container and its controls, so prefer `#cart MaterialButton` to `#cart > MaterialButton`.
- A selector is at most 256 characters without `:`, `,` or `\`: no pseudo-classes or selector lists, and `[text="Total: 5"]` is written `[text^="Total"]`.
- R8 renames classes in a minified build: a renamed class is named by its nearest superclass that kept its name (a minified `PayButton` reads as `MaterialButton`), and classes inflated from layouts keep theirs. Ids, `testTag`s, roles, labels and text are never renamed, and they are what Espresso, UI Automator and Maestro use too: the stable choice.

You rarely write one by hand: every tap is recorded as `browser.user_action.click` with the selector of the control that handled it (`browser.css_selector`), shown on the session timeline, and the SDK only records a selector it has checked against the tapped view. The control is the nearest clickable view under the finger (or the Compose node with a click action), else the text or image that was hit. Its name is the most stable one it has, in this order: an id on the control, on a single-child wrapper around it or on a view inside it (`MaterialButton#pay`); its string tag; its text, up to 40 characters (`MaterialButton[text="Pay now"]`); its label, up to 60; a role when its class has no usable name; its class with the nearest ancestor id in front (`#cart MaterialButton`); its class alone. `app.widget.name`, `app.widget.id`, `app.widget.text`, `app.widget.label`, `app.screen.coordinate.x`/`y` and `app.screen.name` are beside it. The shown text and label of a masked view are never sent, and a mask tag (`traceway-mask`, `traceway-blank`) never names a view.

### Naming screens

A navigation step matches the path of the screen on top:

- an activity is `/` and its class name (`/CheckoutActivity`);
- an AndroidX fragment that shows a screen is `/` and its class name (`/CartFragment`); `NavHostFragment` itself is not a screen;
- a Compose Navigation destination, once you hand its `NavController` to `Traceway.trackNavigation`, is its route with the arguments filled in (`/orders/8841`), with the route template as its route (`/orders/:id`).

**Compose screens.** A single-activity Compose app is one `/MainActivity` screen to Android. Track its `NavController` where you create it, and every destination is a screen of its own, for issues, screen metrics and navigation trigger steps:

```kotlin
import com.tracewayapp.traceway.navigation.trackNavigation

val nav = rememberNavController()
LaunchedEffect(nav) { Traceway.trackNavigation(nav) }   // Java: TracewayNavigation.track(nav)
NavHost(nav, startDestination = "orders") {
    composable("orders") { Orders() }
    composable("orders/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { OrderDetail() }
}
// "orders/8841" is the screen /orders/8841 with the route /orders/:id; going back reports a pop
```

Arguments of any type fill the path; query arguments (`search?query={q}`) are left out; a type-safe route (`@Serializable data class OrderDetail(val id: Long)`) is named by its class's simple name (`/OrderDetail/8841`). Calling it again for the same controller does nothing. A screen that no `NavController` shows (a pager page, a sheet you treat as a screen) names itself with a modifier on its root:

```kotlin
import com.tracewayapp.traceway.compose.tracewayScreen

Column(Modifier.fillMaxSize().tracewayScreen("/orders/$id", route = "/orders/:id")) { … }
```

Any other router reports its screens itself with `Traceway.recordNavigation(path, action = "push", from = null, route = null)`, with the route template the path is an instance of as `route` (or later with `Traceway.setRoute`), e.g. `Traceway.recordNavigation("/orders/8841", route = "/orders/:id")`.

A navigation record is `browser.navigation` with `url.full`, `navigation.action` and `browser.navigation.type` (`push`, `pop`, `replace`, or `remove` for a screen taken away without arriving anywhere), `navigation.from`, `navigation.to`, `app.screen.name`, and `app.screen.route` once a route is set. A route lasts until the user navigates to another screen; `setRoute(null)` removes it. It is trimmed, gets a leading `/` and is cut at 64 characters. Exceptions carry it, and metrics group screens by it instead of by the path with its ids replaced.

## Session replay

### Wireframe

```kotlin
TracewayOptions(screenCapture = true)
```

Instead of pictures of the screen, the SDK records the view tree. After a frame the app drew (at most `fps` times a second, 10 by default, and not at all while nothing changes) it walks the windows of the app — activities, dialogs and popups — and turns what they show into HTML: boxes with their colors, gradients, borders, corners and shadows, text with its size, weight, color and alignment, clips, fades, transforms and scroll positions, as absolutely positioned elements nested the way Android draws them. What changes from one frame to the next becomes an rrweb mutation, so the dashboard replays the session with the same player as a browser session, taps and swipes included. [`docs/wireframe.md`](docs/wireframe.md) describes the pipeline, the node ids, the stylesheet and what each kind of view becomes.

- **Small and cheap.** No screenshot is taken: a clip is tens of kilobytes of JSON, a segment often less. An unchanged view costs no allocation: its node from the previous frame is reused, and the diff skips it.
- **Views and Compose.** Checkboxes, switches, radio buttons, progress bars, seek and rating bars are drawn from their state. Jetpack Compose screens are recorded from their semantics: text with its style, fields, toggles, sliders, progress, images and buttons. Compose does not expose its surface colors: a button with light text (a filled one) takes the theme's primary color, other buttons and fields a neutral look.
- **Not pixel-exact.** The browser draws text in its own font (the screen names Android's stack, `Roboto,"Segoe UI","Helvetica Neue",Arial,sans-serif`). Solid colors and shapes are drawn as such and small vector icons as their shape; photos are placeholders unless [images](#images) are on. `WebView`, `SurfaceView`, `TextureView`, video and maps are labelled placeholders, and custom-drawn views a dashed outline.
- **The keyboard.** The soft keyboard is another app's window: while it shows, the replay draws a neutral box labelled "Keyboard" where it covers the app (the window's IME inset on Android 11+, the visible frame below that), light or dark as the app's theme is. Its keys are never recorded.
- **Background.** While the app is in the background the replay shows a blank "App in the background" screen.

Clips follow the collection settings: the last frames stay on the device in a ring of `maxBufferFrames` (a frame is taken only when the screen changed, so it covers at least `maxBufferFrames / fps` seconds), an exception uploads the clip, and captures stream the session as segments.

### Images

The pictures image views show can be recorded into the wireframe instead of placeholders. The collection settings choose when (`replay.images`), and `TracewayOptions.replayImages` caps it:

| `replay.images` | Pictures |
|-----------------|----------|
| `off` | never: placeholders |
| `wifi` (default) | on an unmetered connection (`ConnectivityManager.isActiveNetworkMetered()` false: Wi-Fi, Ethernet) |
| `always` | on any connection |

- The lower of the setting and `replayImages` (`ALWAYS` by default) applies, `off` < `wifi` < `always`. Without `screenCapture`, or when the plan's limits turn replay off, there are no pictures. Session events report the policy at the start of the session (`traceway.replay.images`) and the encoded bytes used (`traceway.replay.image_bytes`).
- The connection is checked when a frame is captured, following the default network as it changes (Android 7+): a picture recorded on Wi-Fi stays in its clip. A frame captured while pictures are not allowed carries none, the ones recorded a moment earlier included.
- What is recorded: the bitmap of an `ImageView`'s `BitmapDrawable` (the top bitmap of a layer drawable, as a Glide crossfade shows it) and bitmap backgrounds. Never a screenshot of the window, never anything under a mask. Vector drawables, shapes and animated images keep their usual look (a small icon, 64 dp or less, is recorded as its shape alone: a mask of where it is opaque, without its colors, so a small picture in a drawable class the SDK does not know as a bitmap leaves the device as a silhouette at most, whatever the image policy), and Jetpack Compose images stay placeholders: the semantics tree holds no pixels.
- Each picture is copied at the size it shows (one pixel per dp, at most 384 pixels on its longest side) and encoded off the main thread as JPEG at quality 0.45; a picture with transparency is always PNG, at most 192 pixels on its longest side, so it never replays as a black box. One larger than 48 KiB at half that size is left out. The first frame shows the placeholder, a later one the picture.
- Each replay segment or exception clip allows 128 KiB of inline raster data URLs. Repeated URLs in snapshots and mutations count each time they are exported. When a segment exceeds the limit, its pictures and raster icons use layout placeholders throughout. Text, dimensions, masks and interaction events remain available. The next segment gets a fresh allowance.
- The same bitmap at the same size is one picture however many views show it. At most 128 pictures are kept per recorder: past that, the one shown longest ago makes room (shown again, it is recorded again). At most 4 MiB of encoded pictures per session; past it, new pictures are placeholders. A picture's style stays in the recording 30 seconds after its view left the screen.

### Privacy masks

Masks change the recording only; the user sees no difference.

```kotlin
import com.tracewayapp.traceway.tracewayMask

// Blur: the layout stays, its text becomes * and its images placeholders
cardNumberView.tracewayMask = TracewayMaskMode.blur()

// Blank: a box of this color, nothing inside it is recorded
profileHeader.tracewayMask = TracewayMaskMode.blank(Color.BLACK)

cardNumberView.tracewayMask = null
```

From Java: `Traceway.mask(view, TracewayMaskMode.blur())` (blur when the mode is left out) and `Traceway.unmask(view)`. A mask covers the view and everything inside it.

In XML, `android:tag="traceway-mask"` (blur) or `android:tag="traceway-blank"` (blank). In Jetpack Compose:

```kotlin
import com.tracewayapp.traceway.compose.tracewayMask

Text(address, Modifier.tracewayMask())
Box(Modifier.tracewayMask(TracewayMaskMode.blank())) { ... }
```

`Modifier.testTag("traceway-mask")` and `"traceway-blank"` work too. A masked view's text and label are also left out of tap records. Text typed into fields is replaced with `*` unless remote settings set `replay.maskAllInputs: false`; password fields always are, as dots.

When the settings become stricter during a session (`replay.maskAllInputs` back to `true`, or `replay.images` from `always` to `wifi` or `off`, or from `wifi` to `off`), what was recorded under the looser setting does not go up any more: the frames held on the device are dropped, and so are the clips being assembled and the clips not uploaded yet, the ones a crash spooled to disk included. The screen is captured again at once, as the new setting shows it. A setting that becomes looser drops nothing. The browser, Flutter and iOS SDKs do the same.

## Network requests

```kotlin
val client = OkHttpClient.Builder()
    .addInterceptor(TracewayOkHttpInterceptor())
    .build()
```

Add it as an *application* interceptor. Every OkHttp call through it is a client span (`http.request.method`, `url.full`, `server.address`, `http.response.status_code`, request and response body sizes, `error.type` for a failure or a status of 400 and above). The SDK's own requests are never recorded. A request made on the thread running an [interaction](#interactions)'s `run` is its child span; any other starts a trace of its own.

Other HTTP stacks record a request with the client's `startRequest(method, url)` and `endRequest(handle, statusCode, requestBytes, responseBytes, error)`:

```kotlin
val handle = Traceway.client?.startRequest("GET", url)
// send the request, adding handle?.traceparent as a `traceparent` header when it is not null
Traceway.client?.endRequest(handle, statusCode = 200, requestBytes = null, responseBytes = 512, error = null)
```

`startRequest` returns null when the request is not recorded (network capture off, one of the SDK's own); `endRequest` ignores a null handle.

`propagateTraceHeaderUrls` (prefixes) and `propagateTraceHeaderUrlPatterns` (regular expressions) list the URLs whose requests carry a W3C `traceparent` header naming the request's client span, so the backend's spans join the app's trace in Traceway or any OpenTelemetry backend:

```kotlin
TracewayOptions(
    propagateTraceHeaderUrls = listOf("https://api.example.com/"),
    propagateTraceHeaderUrlPatterns = listOf(Regex("^https://[a-z]+\\.example\\.com/")),
)
```

It is empty by default, because a header a server does not expect can break a strict CORS or request-signature check. A request that already carries a `traceparent` keeps it. Requests to Traceway and to the `telemetry` receivers never carry it.

## App performance metrics

The SDK measures the app on the device and uploads the numbers as OTLP metrics: base-2 exponential histograms and counters (delta temporality), one small payload every five minutes and when the app goes to the background. Nothing is sent per observation, and no user, device or session id is a dimension.

| Metric | What it measures |
|--------|------------------|
| `app.start.duration` (`start_type` `cold` / `warm` / `hot`) | Cold: from the process start (`Process.getStartElapsedRealtime()`, API 24+; earlier, from SDK init) to the first frame, only when the process was started for an activity. Warm: an activity recreated on return. Hot: from the return to the foreground to the next frame |
| `app.screen.ttid` (`screen`) | Time to initial display: from the navigation to the first frame of the screen |
| `app.screen.ttfd` (`screen`) | Time to full display: from the navigation until the app calls `Traceway.reportFullyDisplayed()` |
| `app.frames.slow`, `app.frames.frozen` (with `.total`, by `screen`) | `FrameMetrics` on API 24+ (Choreographer below): slow when a frame missed its deadline at the display's refresh rate, frozen at 700 ms or more |
| `app.hang`, `app.hang.duration` | The main thread ran nothing for 2 s or more while the app was in the foreground (not under a debugger) |
| `app.crash` | Uncaught exceptions, and native crashes of earlier runs (Android 11+) |
| `app.anr` | Earlier runs Android killed as not responding (Android 11+, read at the next start) |
| `app.oom` | Earlier runs Android killed for want of memory (`REASON_LOW_MEMORY`, Android 11+, read at the next start) |
| `app.session`, `app.user`, `app.user.affected` (`issue`) | Sessions; users counted once a day per installation, and once a day per problem they met, so no id leaves the device |
| `app.memory.peak` | The process's peak RSS, once per session |
| `app.request.duration`, `app.request.bytes`, `app.request.failed` (`host`) | Every HTTP request the SDK records |

Screens are route templates when the app [names them](#naming-screens), else paths with ids folded (`/orders/8841` → `/orders/:id`). Resource dimensions are the app version, environment, OS and its major version, form factor (`phone`, `tablet`, `desktop`) and a coarse device class from the RAM.

```kotlin
// The screen has what it loads: ends its time to full display.
Traceway.reportFullyDisplayed()

// App-defined metrics, charted like the built-in ones.
Traceway.metric("checkout.duration", 1234.0, mapOf("plan" to "pro"))
Traceway.count("cart.add")
Traceway.setMetricDimension("experiment", "b") // `custom.experiment` on every metric
```

`captureMetrics = false` turns it all off. A rule's `metrics` section switches metrics off (`enabled`), samples sessions (`sampleRate`; counts are weighted back by the server) or changes the upload interval (`flushSeconds`, 30–3600).

## Setups

### Everything on Traceway

```kotlin
Traceway.init(
    this,
    TracewayOptions(token = BuildConfig.TRACEWAY_TOKEN, serviceName = "shop-app", environment = "production"),
)
```

The app polls Traceway for its collection settings, sends logs, request spans and metrics to Traceway's OTLP endpoints, and uploads replays to the same server. What it collects is changed on the project's **Data Collection** page. A self-hosted server adds `baseUrl = "https://traceway.mycompany.com"`.

### Send telemetry to another OpenTelemetry provider

`telemetry` sends logs, spans and metrics to any OTLP/HTTP receiver: an OpenTelemetry Collector, Grafana, Honeycomb, Datadog's or New Relic's OTLP intake.

**Next to Traceway**: with a token, errors, replays, captures and remote config stay on Traceway; ordinary logs, request spans and metrics go to the receiver:

```kotlin
TracewayOptions(
    token = BuildConfig.TRACEWAY_TOKEN,
    serviceName = "shop-app",
    telemetry = TracewayTelemetry(
        url = "https://collector.example.com/otlp",
        headers = mapOf("x-ingest-key" to "app-ingest-key"),
        // Optional overrides are complete signal URLs; no path is appended.
        metrics = TracewayTelemetryEndpoint(url = "https://metrics.example.com/v1/metrics"),
    ),
    collection = mapOf(
        "signals" to mapOf("console" to mapOf("mode" to "stream"), "network" to mapOf("mode" to "stream")),
    ),
)
```

The shared `url` gets `/v1/logs`, `/v1/traces` and `/v1/metrics` appended. Each signal (`logs`, `traces`, `metrics`) can override it with a `TracewayTelemetryEndpoint(url, headers)` whose URL is used as given. Without a shared `url`, the signals you do not list keep their Traceway destination. URLs must use HTTP(S), without embedded credentials, a query or a fragment. Signal headers override the shared `headers` key by key; the Traceway token is never sent to these receivers. From Java, `TracewayTelemetry.builder()` builds one.

When logs are redirected, exceptions, `captureMessage` issues and the SDK's session and control records still go to Traceway, and only there. Ordinary logs (console, navigation, taps, `recordAction`, `track`) go to the logs receiver; request spans and metrics use their own destinations. Remote config (OpAMP) and replays keep going to Traceway with the token. Both destinations carry `session.id` for correlation. Failed batches are queued on disk per destination, so a changed receiver is never sent what was meant for the previous one.

The receiver must accept OTLP/HTTP with protobuf bodies (`application/x-protobuf`, gzip). Any key built into the app can be read out of it: use an ingestion key that can only write, or a collector that holds the backend credentials.

#### Standalone, without Traceway

Without a token everything goes to the receiver:

```kotlin
Traceway.init(
    this,
    TracewayOptions(
        serviceName = "shop-app",
        telemetry = TracewayTelemetry(url = "https://collector.example.com/otlp"),
        collection = mapOf("signals" to mapOf("console" to mapOf("mode" to "stream"))),
    ),
)
```

No Traceway account or token is required. `telemetry` must have a shared `url` or all three signal endpoints. Standalone mode turns off remote config, replay uploads and session records, whatever the options say. Exceptions are ordinary OTLP log records with the event name `exception`, and messages `traceway.message`.

## Options

| Option | Default | Description |
|--------|---------|-------------|
| `token` | — | The project token, from the project's Connection page; without one the SDK runs [standalone](#standalone-without-traceway) |
| `baseUrl` | `https://cloud.tracewayapp.com` | The Traceway server; set it for a self-hosted one (`https://` is added when there is no scheme) |
| `sampleRate` | `1.0` | Share of exceptions and messages that are reported (0.0 - 1.0) |
| `debug` | `false` | Log what the SDK does to logcat (tag `Traceway`) |
| `version` | the app's `versionName` | App version, sent as `service.version` |
| `serviceName` | application id | Sent as `service.name` (`android-app` without an application) |
| `environment` | — | Sent as `deployment.environment.name` |
| `attributes` | `{}` | Attributes attached to every record, and usable to target this app from Data Collection rules |
| `screenCapture` | `false` | Record the screen as a wireframe: the last frames go up with an exception, and captures can stream it. A ceiling for replay |
| `replayImages` | `ALWAYS` | The most `replay.images` may allow: `OFF`, `WIFI` or `ALWAYS` (see [Images](#images)) |
| `fps` | `10` | Most wireframe frames captured per second (1–30); only frames where something was drawn are captured |
| `maxBufferFrames` | `150` | Wireframe frames held on the device (at least 2); the upper bound of every clip |
| `persistToDisk` | `true` | Keep SDK state and undelivered telemetry on disk across restarts (`noBackupFilesDir/traceway_v2`) |
| `captureLogs` | `true` | Capture `System.out` / `System.err`, `Traceway.log` and logcat lines from the Gradle plugin. A ceiling for `console` |
| `captureNetwork` | `true` | Record HTTP calls made through `TracewayOkHttpInterceptor` (or `startRequest` / `endRequest`). A ceiling for `network` |
| `captureNavigation` | `true` | Record activity and fragment screens and `Traceway.recordNavigation`. A ceiling for `navigation` |
| `captureUserActions` | `true` | Record taps with the selector of the view, and feed taps to recording triggers. A ceiling for `userActions` |
| `captureMetrics` | `true` | Measure app start, screen display times, frames, hangs, crashes, sessions, users and requests, and upload them as metrics |
| `metricDimensions` | `{}` | Up to two app-defined dimensions on every metric (`custom.<key>`) |
| `recordAllSessions` | `false` | Stream breadcrumbs and the replay all the time instead of keeping them for errors |
| `collection` | — | Local collection settings, the same document as a remote-config rule (see [Local config](#local-config)) |
| `sessions` | `true` | Send session records (`session.start` / `session.end`). The session id exists either way |
| `baselineSampleRate` | `0` | Share of sessions whose breadcrumbs are sent continuously, not only around errors |
| `remoteConfig` | `true` | Poll the server for collection settings over OpAMP |
| `exportDelayMs` | `1000` | How long finished records wait to be sent as one batch (at least 50 ms) |
| `telemetry` | — | Send logs, spans and metrics to another OTLP/HTTP receiver (see [Send telemetry to another OpenTelemetry provider](#send-telemetry-to-another-opentelemetry-provider)) |
| `propagateTraceHeaderUrls` | `[]` | URL prefixes whose requests carry a W3C `traceparent` header (see [Network requests](#network-requests)) |
| `ignoreErrors` | `null` (`DEFAULT_IGNORE_ERRORS`) | Exceptions and messages not to report: `String`s, `Regex`es and `java.util.regex.Pattern`s matched against `Type: message` (see [Errors and messages](#errors-and-messages)) |
| `beforeCapture` | — | `(TracewayCapturedError) -> Boolean`: return false to drop an exception or a message |
| `proguardUuid` | the Gradle plugin's | The R8 build UUID; the plugin's is read from the app's assets, so set it only to pin your own (see [Crashes and symbolication](#crashes-and-symbolication)) |
| `propagateTraceHeaderUrlPatterns` | `[]` | Like `propagateTraceHeaderUrls`, as regular expressions |
| `captureExitReasons` | `true` | Report how earlier runs ended, from `ApplicationExitInfo` (Android 11+): native crashes and ANRs as issues, low-memory kills as `app.oom` (see [Native crashes, ANRs and hangs](#native-crashes-anrs-and-hangs)) |
| `captureAppHangs` | `true` | Report a main thread blocked for `appHangThresholdMs` in the foreground as an `AppHang` issue with its stack |
| `appHangThresholdMs` | `2000` | How long the main thread must be blocked for an `AppHang` issue (at least 250 ms) |
| `reportHangsInDebug` | `false` | Report hangs (`app.hang` and `AppHang` issues) while a debugger is attached too, to try them from Android Studio's Debug run |
| `allProcesses` | `false` | Run in every process of the app, not only the main one; each keeps its own state |
| `sessionTimeoutMs` | `900000` (15 min) | How long without user activity ends a session (at least 1 s) |

To turn a channel off entirely:

```kotlin
TracewayOptions(
    captureLogs = false,
    captureNetwork = false,
    captureNavigation = false,
    captureUserActions = false,
)
```

## Crashes and symbolication

Uncaught exceptions on every thread are caught through the default `UncaughtExceptionHandler`. The exception (`exception.unhandled = true`, with its thread and screen), its breadcrumbs and its replay clip are written to disk before the handler that was installed before (normally the platform's) ends the process. On any thread but the main one the SDK then tries to send them, holding the crashing thread for at most 2.5 seconds in all; a crash on the main thread is not sent then, so the frozen screen does not wait. What is left goes at the next start. The crash is never swallowed. A crash before the SDK finished starting is kept on disk and reported at the next start (`traceway.crash.before_start = true`). What waits on disk for the next start (that crash, a hang, replay clips a crash spooled) is kept per token and server, and only ever sent there: after the token or the server changed it stays for 24 hours since its last write, in case the app returns to them, and is then deleted unsent.

Release builds run R8, which renames classes and methods and rewrites line numbers, so crashes arrive obfuscated. Traceway reverses them with the build's `mapping.txt`, matched to the crashing build by a ProGuard UUID that the SDK sends as the `app.debug.proguard_uuid` resource attribute of every record.

The `com.tracewayapp.symbols` Gradle plugin (in [`gradle-plugin/`](gradle-plugin), see [Installation](#installation)) does both halves: it makes a UUID per variant and app version, puts it into the app's assets where the SDK reads it (and into `BuildConfig.TRACEWAY_PROGUARD_UUID` when the module builds a `BuildConfig`), and uploads `mapping.txt` to Traceway. Applying it is all the app needs; configure the upload on your app module:

```kotlin
plugins {
    id("com.android.application")
    id("com.tracewayapp.symbols") version "2.1.0"
}

android {
    buildTypes {
        release { isMinifyEnabled = true }
    }
}

traceway {
    // The project's upload token, not the SDK's token: from the environment on CI, else local.properties
    // (`localProperties` as in the Quick start).
    uploadToken = System.getenv("TRACEWAY_UPLOAD_TOKEN") ?: localProperties.getProperty("TRACEWAY_UPLOAD_TOKEN")
    // url = "https://traceway.mycompany.com"   // a self-hosted server; Traceway Cloud by default
    autoUpload = true                           // upload after assembleRelease; default false
    // proguardUuid = "..."                     // optional: pin the build UUID yourself
}
```

Put `TRACEWAY_UPLOAD_TOKEN=YOUR_UPLOAD_TOKEN` in `local.properties` next to `TRACEWAY_TOKEN`, or set the `TRACEWAY_UPLOAD_TOKEN` environment variable on CI.

A minified build without a UUID (no plugin, no `proguardUuid`) logs one warning at start: its crashes arrive obfuscated.

Notes:

- **`uploadToken` is the project _upload token_** from the dashboard (the same token used for JavaScript source maps and iOS dSYMs), **not** the SDK's project token. The wrong token is rejected with a 401.
- The injected UUID is derived from the module path, variant, and app version (`versionName` + `versionCode`), so **bump the version each release** to keep each build's mapping distinct — otherwise a new upload overwrites the previous release's mapping under the same UUID.
- `autoUpload` defaults to `false` so a release build never depends on backend availability. With it off, run the upload explicitly (recommended for CI):

  ```bash
  ./gradlew :app:assembleRelease :app:uploadReleaseTracewaySymbols
  ```

> The plugin currently ships inside this repo; the `:example` module applies it via `includeBuild("gradle-plugin")` in `settings.gradle.kts`.

You can also upload `mapping.txt` yourself and pin the UUID by hand: set the same value in `TracewayOptions(proguardUuid = ...)`:

```bash
curl -X POST https://cloud.tracewayapp.com/api/symbols/upload \
  -H "Authorization: Bearer <your project upload token>" \
  -F "proguard_uuid=<your build uuid>" \
  -F "files=@app/build/outputs/mapping/release/mapping.txt"
```

See the [symbolicator docs](https://docs.tracewayapp.com/symbolicator/android) for the full upload API and how traces are resolved.

The same plugin rewrites `android.util.Log` calls for [logcat capture](#logcat), configured in the same `traceway { }` block (`logcat { enabled; minLevel; includeDependencies; disabledFor(…) }`).

### Native crashes, ANRs and hangs

Android keeps a record of how each process of the app ended (`ApplicationExitInfo`, Android 11 / API 30+). Once per start, on its own threads, the SDK reads the records newer than the last one it saw (`captureExitReasons`, on by default; the first start only notes where Android's history ends):

| How the process ended | Reported as |
|-----------------------|-------------|
| `REASON_CRASH_NATIVE` | issue `NativeCrash`: on Android 12+ the signal, abort message, cause and the crashing thread's frames from the tombstone (`#00 pc 000000000001a2b4  /data/app/…/libshop.so (decode+20) (BuildId: …)`), else Android's description; also counted in `app.crash` |
| `REASON_ANR` | issue `ApplicationNotResponding` with the main thread's stack from the ANR dump (Java `at …` and `native: #00 pc …` frames) and why the system found it not responding, plus an `app.anr` count |
| `REASON_LOW_MEMORY` | an `app.oom` count, no issue |
| `REASON_CRASH` | nothing: the uncaught-exception handler already reported it |

These issues carry `exception.unhandled = true`, `app.exit.reason` (`crash_native`, `anr`), the time the process died and its previous session when known (`session.id`, kept per process id). `traceway.previous_run = true` marks them: this run's breadcrumbs and replay are not their context. They need `persistToDisk`.

**Hangs.** The watchdog behind `app.hang` posts a probe to the main thread every 500 ms. When one has waited `appHangThresholdMs` (2 s) in the foreground, not under a debugger, the watchdog's thread takes the main thread's stack (`Looper.getMainLooper().thread.stackTrace`) while it is still blocked, and keeps the hang on disk. When the main thread runs again the hang is an issue of type `AppHang`, message `Main thread blocked for 3.2 s`, with that stack, the screen it happened on, `app.hang.duration_ms` and `app.hang.fatal = false`; `ignoreErrors` and `beforeCapture` see it like an exception. A hang the process does not survive (the system or the user kills it) is reported at the next start with `app.hang.fatal = true`, through the same two filters, unless that run ended in an ANR or a crash: then the ANR is the issue (with the hang's stack when its dump has none), or the crash. An uncaught exception drops the hang it follows, so a main thread that blocks for 2 s and then throws is one issue, the crash; a leftover hang of a run that Android recorded as a crash (`REASON_CRASH`, `REASON_CRASH_NATIVE`, Android 11+) is dropped at the next start too. `captureAppHangs = false` turns hang issues off (the metric stays).

There is no in-process native signal handler: it would compete with the other native crash reporters an app may have and needs native builds of the SDK. Below Android 11 native crashes and ANRs are not reported.

## What gets captured automatically

- **Uncaught exceptions** on every thread (see [Crashes and symbolication](#crashes-and-symbolication)).
- **Native crashes, ANRs and low-memory kills** of earlier runs, Android 11+, and **app hangs** (see [Native crashes, ANRs and hangs](#native-crashes-anrs-and-hangs)).
- **Sessions**: `session.start`, `session.end` and `traceway.session.update`, with the session's collection counters. An update goes up when a session goes on after a restart, when the app goes to the background and at a flush (when the counters changed), so a session whose app is never opened again still has its counts. A session ends after 15 minutes without activity (`sessionTimeoutMs`) or after 60 minutes. Most apps end by being killed in the background: the next launch then ends the session they left, with `session.end` at its last activity, the counts kept with it and its `app.memory.peak`, before it starts a new one. Activity is any touch on the app's windows (a tap, a scroll, a drag, a long press; also with `captureUserActions = false`, and nothing more is recorded for it), a screen change, `recordAction`, the app coming to the foreground and `TracewayClient.markActivity()`.
- **Screens**: activities and AndroidX fragments, as `browser.navigation`.
- **Taps** in the app's activity windows, with the selector, type, id, shown text and label of the view, and the screen (`browser.user_action.click`, with `app.widget.*` and `app.screen.*` attributes). Taps in dialogs, bottom sheets and menus are not recorded yet (their screens are in the replay).
- **Logs**: `System.out` / `System.err` lines, and `android.util.Log` calls with the Gradle plugin.
- **Network**: OkHttp requests, with the interceptor.
- **Metrics**: the app's performance metrics.
- **Replay**, with `screenCapture = true`.

## Platform support

| Platform | Error Tracking | Session Replay | Metrics |
|----------|---------------|----------------|---------|
| Android (API 21+) | Yes | Wireframe (Views and Jetpack Compose), with the pictures of image views | Yes |

Below API 26, enable core library desugaring (see [Installation](#installation)).

For Flutter apps, use the [`traceway`](https://github.com/tracewayapp/traceway-flutter) package instead.

### Platform differences

The API has the names of the browser, Flutter and iOS SDKs. Where Android differs, it is for these reasons:

- **Start**: `Traceway.init(application, TracewayOptions(token = …, baseUrl = …))` takes the `Application`, which the SDK needs to follow activities and fragments, record taps and the wireframe, keep its files and read the network state, and the token and server inside the options (Flutter and iOS take them as parameters of `Traceway.run` and `Traceway.initialize`). It returns the `TracewayClient`, or `null` when the settings are invalid (logged, not thrown). A second `init` returns the running client; one called while `shutdown` is still flushing on another thread starts a new client, as one called after it does (so do Flutter and iOS).
- **Masks** belong to views: `view.tracewayMask` in Kotlin, `Traceway.mask(view, mode)` / `Traceway.unmask(view)` from Java (an extension property reads poorly there), `Modifier.tracewayMask(mode)` in Compose. The markers that need no code are `android:tag` and Compose `testTag` values (`traceway-mask`, `traceway-blank`), where the browser uses CSS classes and Flutter a `ValueKey`. `TracewayMaskMode.blank(color)` takes an ARGB `Int`.
- **Blocking, in milliseconds**: `flush(timeoutMs)` and `shutdown(timeoutMs)` (10 s by default) block the calling thread instead of returning a promise or future. On the main thread `flush` waits 2 s at most whatever it is asked (an ANR comes at 5 s), and what is not sent by then goes later; off it, the whole timeout. iOS takes seconds (`flush(timeout:)`).
- **Exit reasons** come from Android's `ApplicationExitInfo` (API 30+; tombstones from API 31), read at the next start; iOS reads MetricKit. There is no native signal handler, so native crashes before Android 11 are not reported (iOS has one, on an alternate stack).
- **Logcat** needs the Gradle plugin, which rewrites `android.util.Log` calls at build time (iOS reads `OSLogStore`; the browser wraps `console`, Flutter `print`). Without it, `System.out` and `System.err` only.
- **The tracer** is never registered as the global OpenTelemetry: in OpenTelemetry Java `GlobalOpenTelemetry.set` works once per process, so registering would make an app's own later `buildAndRegisterGlobal()` throw. Use `Traceway.tracer`. No Traceway SDK registers global OpenTelemetry providers.
- **Properties and Java**: `Traceway.currentSessionId` is a property, as in Flutter and iOS (`getCurrentSessionId()` from Java, a function in the browser); `getInstanceId()` is a method, as in the browser and Flutter (iOS: `instanceId`). Every method is a `@JvmStatic` on `Traceway`, with Java overloads for the optional parameters.
- **Enum constants in Kotlin case**: `TracewayLogLevel.WARN` (`"warn"` in the browser, `.warn` in Flutter and iOS), `TracewayImagePolicy.WIFI` for the setting `replay.images: "wifi"`.
- **Throwables carry their stack**: `captureException(error, attributes)` has no stack trace parameter (Flutter's has one), and `log(message, level, attributes)` takes no error.
- **Navigation**: activities and AndroidX fragments are reported without code; Compose Navigation through `Traceway.trackNavigation(navController)` (Flutter has `Traceway.navigatorObserver`, iOS `.tracewayScreen`, which `Modifier.tracewayScreen` mirrors); other routers call `recordNavigation(path, action, from, route)`, positional in that order from Java (Flutter takes them by name).
- **Requests** are recorded by `TracewayOkHttpInterceptor` on the app's `OkHttpClient`, or with `Traceway.client?.startRequest` / `endRequest` for another HTTP stack; nothing is patched.
- **Options**: `TracewayOptions` is a Kotlin constructor; pass its parameters by name, or from Java build them with `TracewayOptions.builder()` (a `null` means the default). `proguardUuid`, `propagateTraceHeaderUrlPatterns` and `allProcesses` exist only on Android; `appHangThresholdMs` is in milliseconds.

## Migrating from 1.x

- **Server**: 2.x sends OTLP to `/api/otel/v1/logs`, `/api/otel/v1/traces` and `/api/otel/v1/metrics`, polls `/api/otel/v1/opamp` and uploads replays to `/api/recordings`. It never uses the 1.x report protocol.
- **Start**: pass the project token, and a self-hosted server's URL, in the options: `Traceway.init(this, TracewayOptions(token = "YOUR_TOKEN"))`, `baseUrl = "https://traceway.mycompany.com"` for a self-hosted server. The 1.x `Traceway.init(application, connectionString, options)` still works and is deprecated: it logs one warning naming the new form. Without a token the SDK runs [standalone](#standalone-without-traceway).
- **Unsent 1.x reports**: 2.x recovers exceptions and messages that a beta SDK associated with the same server and project token. Files expire after 12 hours. Recovered issues carry `traceway.previous_run = true`. The SDK discards older, unscoped files because they have no project identity.
- **Desugaring**: apps with `minSdk` below 26 must enable core library desugaring (OpenTelemetry Java).
- **Behaviour**: logs and actions are no longer limited to the last 10 seconds and no longer travel inside the report. They are buffered for up to 10 minutes and sent as their own records with an exception. `screenCapture = true` now records a wireframe replay. Network errors (`UnknownHostException`, timeouts, 4xx) are no longer reported unless `ignoreErrors` says so.
- **Removed options**: `debounceMs`, `retryDelayMs`, `maxPendingExceptions`, `maxLocalFiles`, `localFileMaxAgeHours`, `eventsWindowMs`, `eventsMaxCount`, `capturePixelRatio`. `fps` and `maxBufferFrames` now apply to the wireframe recording.
- **Removed API**: `TracewayClient.addException`, `loadPendingFromDisk`, `setDeviceAttributes`, `bufferedLogs`, `bufferedActions`, `pendingExceptionCount` and the other test accessors, `recordNetworkEvent`, `recordNavigationEvent`, the `ReportSender` interface and the 1.x event and report models. Use `captureException`, `Traceway.setAttribute`, `startRequest` / `endRequest`, `recordNavigation` and `flush()`.
- **Changed API**: `Traceway.init` returns `TracewayClient?` (null when the settings are invalid). `TracewayClient.flush(timeoutMs)` takes a non-null timeout (10 s by default). `TracewayOptions` lists its parameters in the order the other SDKs do, starting with `token` and `baseUrl`: pass them by name.
- **New API**: `TracewayOptions.token` and `baseUrl`, `TracewayOptions.builder()`, `Traceway.trackNavigation`, `Modifier.tracewayScreen`, `Traceway.track`, `setAttribute(s)`, `removeAttribute`, `clearAttributes`, `recordNavigation`, `setRoute`, `log` with `TracewayLogLevel`, `startInteraction` with `TracewayInteraction`, `currentSessionId`, `reportFullyDisplayed`, `metric`, `count`, `setMetricDimension`, `mask` / `unmask`, `refreshConfig`, `shutdown`, `getInstanceId`; `View.tracewayMask`, `Modifier.tracewayMask()`; options `replayImages`, `ignoreErrors`, `beforeCapture`; `TracewayTelemetry`, `TracewayTelemetryEndpoint`, `TracewayMaskMode`, `TracewayImagePolicy`, `TracewayCapturedError`. `recordLog` remains as a deprecated name.
- **Issues regroup once**: the stack trace now reaches the server as the OpenTelemetry `exception.*` attributes, so the first 2.x occurrence of an issue may start a new group.

## Testing

**JVM unit tests** — no device required. They cover the settings schema and its merge, the trigger engine and selectors, OpAMP (against golden messages from `opamp-go`), OTLP export and the disk outbox, buffer persistence, sessions, replay upload and its budget, metrics, the wireframe stylesheet (byte for byte the one the iOS and Flutter SDKs share), diff and clip assembly, and the client end to end against in-memory OpenTelemetry exporters and a fake server.

The wireframe of real views and Compose screens is tested on the JVM too, under [Robolectric](https://robolectric.org) with its native graphics. `ViewWireframeTest`, `WireframeImagesTest` and `ComposeWireframeTest` hold one test per topic of the wireframe suites every Traceway SDK shares, in the same order as the iOS SDK's: boxes and their positions, lengths in dp, folded layout containers, what is not painted, opacity, clips, transforms and z-order, labels, rich text, right to left, fields and their decorations, privacy masks, images (off, always, Wi-Fi on a metered network, dedup and the budget), toggles, sliders and progress, segmented controls, steppers, page dots, buttons, scrolling, lists, grids, a horizontal list in a vertical one, pushing and popping a screen, tabs, dialogs, bottom sheets, popups, the keyboard, unchanged screens, many and deep views, dark mode, native views and custom drawing, a view that throws, view edits, and eight Compose topics. Each checks where the replay shows a view against where Android drew it. `WireframeSessionTest` drives a session with dispatched touches (taps, a swipe, typing, toggles, a dialog, a second screen), and checks that the recorded rrweb clip rebuilds every frame exactly, that the touches are where the finger was and that each tap is named by the expected selector. It writes the clips, with pictures of the real views at each checkpoint, to `traceway/build/wireframe-sessions/`.

```bash
./gradlew :traceway:testDebugUnitTest
```

`LiveServerTest` runs the real client (its own HTTP stack and threads) against a running Traceway server when `TRACEWAY_E2E_CONNECTION` names one with an Android project, e.g. `TRACEWAY_E2E_CONNECTION=token@http://127.0.0.1:8082`.

**End to end against a server** — `tools/e2e/run.py` runs `E2EScriptTest`, a scripted app: the SDK started with `Traceway.init` and the project's connection string, activities of real views and a Compose screen under Robolectric, touches dispatched to their windows (so taps go through the SDK's touch tracking and the screen through its wireframe recorder), and OkHttp calls through `TracewayOkHttpInterceptor` to a local echo server. The script stops at each phase; the harness checks through the dashboard API what the server holds, then lets it go on:

1. Before an error, breadcrumbs and taps stay on the device; only the session is on the server.
2. A caught exception brings up the issue with its breadcrumbs, the tap with its selector (`MaterialButton#button1`), the HTTP span, the session and a wireframe clip linked to the issue (typed text masked).
3. A recording trigger of the app's local settings (navigation `/users/{id}`, a tap on the Compose button `#open-profile`, a log line) starts a 20 s capture: the session lists it as a recording reason, logs stream while it runs and not after, replay segments are uploaded.
4. A capture created through `POST /api/sdk-config/captures` brings up its lookback, streams with the capture id on every record, and stops when cancelled.
5. A Data Collection rule whose click step is the selector the server recorded for the pay tap matches the next tap, emits its event and records.
6. A rule sets `replay.images` to `off`, then `always`: after a restart of the SDK the session reports `traceway.replay.images`, and the clip of a photo is a placeholder, then carries a `data:image/jpeg` picture.
7. `app.*` metrics and `Traceway.metric` / `Traceway.count` reach the metrics API.

The rules it creates target the run's instance only and are deleted at the start of the next run. It needs Python 3, a JDK in `JAVA_HOME` and a dashboard login; it builds the unit tests with `./gradlew` (`--gradle` takes another command), writes the app's log and `result.json` to `build/e2e` (`--out`), and prints the session and issue ids it created. `E2EScriptTest` is skipped in ordinary unit test runs.

```bash
python3 tools/e2e/run.py --base http://127.0.0.1:8082 --email you@example.com --password … --project "Android App"
```

**Wireframe gallery** — `WireframeGalleryTest` shows the 28 scenarios the iOS SDK's gallery shows (layout, positioning, transforms, clipping, stacks, constraints, text, rich text, buttons, controls, forms, the keyboard, images with replay images on and off, lists, grids, a carousel, navigation, tabs, dialogs, sheets, menus, privacy masks, a Compose screen, native views, motion, right to left, dark mode) on a 412 × 915 dp xhdpi screen. The real `WireframeRecorder` records them on its clock while each scenario taps, types, drags, opens dialogs, sheets and menus and pushes screens, with touches delivered through the windows' callbacks as a device delivers them. At each of its 70 checkpoints the test saves the picture Android drew of its windows (one pixel per dp) and the frames of the texts on screen. It replays the recording through a strict model of rrweb's replayer and fails when a frame does not rebuild exactly, a checkpoint does not show its texts or shows one it must hide, or a typed secret reaches the recording. It writes `events.json`, `run.json`, `app/<checkpoint>.png` and `tree/<checkpoint>.txt` to `traceway/build/wireframe-gallery` (`WIREFRAME_GALLERY_OUT`); `WIREFRAME_GALLERY_ONLY` runs some scenarios:

```bash
./gradlew :traceway:testDebugUnitTest --tests '*WireframeGalleryTest*'
WIREFRAME_GALLERY_ONLY=forms,keyboard ./gradlew :traceway:testDebugUnitTest --tests '*WireframeGalleryTest*'
```

`tools/wireframe/run.py`, the checker the iOS SDK shares, plays the gallery in the dashboard's rrweb-player in headless Chromium and checks every checkpoint: the texts it shows and hides, every text's box against the frame Android drew it in (within 1.5 px), the picture against the app's (SSIM, edges, color) and against the accepted rendering in `tools/wireframe/baseline/android-412x915/`, and the bytes each scenario records. It writes `report.html` with the pictures side by side. With `--dashboard` it uploads the run as a session to a running Traceway and checks the dashboard's own player the same way; see [`tools/wireframe/README.md`](tools/wireframe/README.md).

```bash
python3 tools/wireframe/run.py --dir traceway/build/wireframe-gallery --out build/wireframe-check
python3 tools/wireframe/run.py --dir traceway/build/wireframe-gallery --update-baseline   # after reviewing report.html
python3 tools/wireframe/run.py --dir traceway/build/wireframe-sessions/views                # a session clip
```

**Instrumented (on-device) tests** — hit testing and touch tracking on real views, and the wireframe capture of a real view tree:

```bash
./gradlew :traceway:connectedDebugAndroidTest
```

CI runs the JVM tests, the Gradle plugin's tests (`./gradlew -p gradle-plugin test`) and the release scripts' offline tests via [`unit-tests.yml`](.github/workflows/unit-tests.yml), and the instrumented tests on a Pixel 8 in Firebase Test Lab via [`instrumented-tests.yml`](.github/workflows/instrumented-tests.yml).

### The example app

The `:example` module is a small app with a checkout form built from views and a Jetpack Compose screen with Compose Navigation, exercising every capture path: caught and uncaught exceptions, messages, custom events, an interaction with an OkHttp request and trace propagation, taps, screens and routes, masks and the wireframe replay.

It starts the SDK the recommended way (`ExampleApp.kt`: `TracewayOptions(token = BuildConfig.TRACEWAY_TOKEN)`), tracks its Compose `NavController` with `Traceway.trackNavigation`, logs through `android.util.Log` (rewritten by the Gradle plugin it applies) and makes a span of its own with `Traceway.tracer`. To point it at your Traceway project, put your token into `local.properties` at the repo root (this file is gitignored), or set the same names as environment variables:

```properties
TRACEWAY_TOKEN=YOUR_TOKEN

# Optional: a self-hosted server (Traceway Cloud otherwise)
TRACEWAY_URL=https://traceway.mycompany.com
# Optional: only needed to exercise the plugin's mapping upload
TRACEWAY_UPLOAD_TOKEN=YOUR_UPLOAD_TOKEN
```

Then run from Android Studio (Run ▸ `example`) or from the CLI:

```bash
./gradlew :example:installDebug
adb shell am start -n com.tracewayapp.traceway.example/.MainActivity
```

To build a minified release and upload its mapping in one go:

```bash
./gradlew :example:assembleRelease :example:uploadReleaseTracewaySymbols
```

### Publishing

Two artifacts are published to Maven Central, each with its own workflow, and they share one version: release them together. Both bump the version (the SDK's is `val sdkVersion` in `traceway/build.gradle.kts`, the plugin's the `coordinates` in `gradle-plugin/build.gradle.kts`), sign and upload to Sonatype Central, then commit + tag. If Central accepted the upload but the Git push failed, rerun from the original source revision and supply its full SHA as `published_sha`. The workflow verifies the release coordinates and resumes tagging.

**SDK** (`com.tracewayapp:traceway`) — trigger the **Publish to Maven Central** workflow:

```bash
gh workflow run "Publish to Maven Central" -f version=2.1.0
gh run watch
```

**Gradle plugin** (`com.tracewayapp.symbols`) — trigger the **Publish Gradle Plugin to Maven Central** workflow (tagged `plugin-v<version>`, with the SDK's version). It defaults to a dry run that builds, tests, signs, and publishes to a throwaway local repo without touching Central or the repo, so a misfire is harmless. Once the dry run is green, release for real by unchecking `dry_run`:

```bash
# Validate first (default):
gh workflow run "Publish Gradle Plugin to Maven Central" -f version=2.1.0

# Release once the validation run is green:
gh workflow run "Publish Gradle Plugin to Maven Central" -f version=2.1.0 -f dry_run=false
gh run watch
```

Both workflows run `scripts/release-maven.sh` to release on Central and tag the same revision. Run `scripts/publish-plugin.sh 2.1.0` to publish the plugin to `~/.m2` for a local smoke test. Pass `-- --dry-run` to validate the task graph without credentials.

Both share the same Sonatype Central account and signing key (`MAVEN_CENTRAL_USERNAME`/`MAVEN_CENTRAL_PASSWORD` and `SIGNING_KEY`/`SIGNING_PASSWORD` repo secrets); the `com.tracewayapp` namespace is already verified, which also covers the plugin's `com.tracewayapp.symbols` marker.

## Links

- [Traceway Website](https://tracewayapp.com)
- [Traceway GitHub](https://github.com/tracewayapp/traceway)
- [Documentation](https://docs.tracewayapp.com/client/android?sdk=android)
- [Flutter SDK](https://github.com/tracewayapp/traceway-flutter)
- [Browser SDK](https://github.com/tracewayapp/js-client)

## License

MIT
