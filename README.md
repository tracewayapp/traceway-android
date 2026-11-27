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

Error tracking, sessions, wireframe session replay and app performance metrics for native Android apps, built on the official [OpenTelemetry Java SDK](https://github.com/open-telemetry/opentelemetry-java). Exceptions arrive with full stack traces, the breadcrumbs that led up to them and a replay of the last seconds on screen — automatically.

[Traceway](https://tracewayapp.com) is a completely open-source error tracking platform. You can [self-host](https://docs.tracewayapp.com/server) it or use [Traceway Cloud](https://tracewayapp.com).

> **2.0 is a beta.** It speaks OTLP and OpAMP and needs a Traceway server that supports Android projects on those endpoints. On an older server stay on `com.tracewayapp:traceway:1.0.1`. See [Migrating from 1.x](#migrating-from-1x).

## Features

- Automatic capture of uncaught exceptions on every thread, written to disk before the process dies and sent at once or at the next start
- Full JVM stack traces, deobfuscated server-side for R8/ProGuard builds
- **OpenTelemetry inside** — logs and spans are records of the official OpenTelemetry Java SDK (`opentelemetry-sdk` 1.68) and leave as OTLP/HTTP protobuf, built by its marshalers
- **Ring buffer** — logs, HTTP requests, screens, taps and custom events stay on the device and are uploaded only when something goes wrong
- **Remote config over OpAMP** — change what is collected from the dashboard, per app version, user or device, without a release; or ship the same settings in the app with `collection`
- **Captures on demand** — from the dashboard, or from a recording trigger on the device (a journey of taps, screens and log lines): the buffered lookback goes up, then breadcrumbs and the replay stream until the capture ends
- **Wireframe session replay** — the view tree (and Jetpack Compose screens) recorded as HTML of positioned boxes and text, replayed by the dashboard's rrweb player: a few kilobytes per clip, no pixels read, no video encoded
- **App performance metrics** — cold, warm and hot start, time to initial and full display per screen, slow and frozen frames, hangs, crashes, sessions, users and HTTP requests, aggregated on the device and sent as OTLP metrics
- **Taps that name the view** — every tap is recorded with a CSS-like selector (`MaterialButton#pay`, `ImageButton[label="Delete"]`) that click steps of recording triggers match
- Privacy masking for replays and tap records: `Traceway.mask(view)`, `android:tag="traceway-mask"`, or `Modifier.tracewayMask()` in Compose
- Sessions, with the breadcrumb timeline of each one
- **Any OpenTelemetry backend** — send logs, spans and metrics to an OpenTelemetry Collector, Grafana, Honeycomb or any OTLP/HTTP receiver with `telemetry`, next to Traceway or without it (standalone)
- Telemetry that failed to send is kept on disk and retried; the ring buffer is saved when the app goes to the background, so a session that goes on after the system killed the app still has its breadcrumbs

## Installation

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("com.tracewayapp:traceway:2.0.0-beta.1")
}
```

The SDK needs `minSdk` 21. OpenTelemetry Java uses `java.util.function` and `java.time`, so **apps whose `minSdk` is below 26 must enable core library desugaring**, as every app using OpenTelemetry on Android does:

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

## Quick Start

Initialize Traceway from your `Application.onCreate()`:

```kotlin
import android.app.Application
import com.tracewayapp.traceway.Traceway
import com.tracewayapp.traceway.TracewayOptions

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Traceway.init(
            application = this,
            connectionString = "your-token@https://your-traceway-instance.com",
            options = TracewayOptions(
                version = BuildConfig.VERSION_NAME,
                screenCapture = true, // wireframe session replay
            ),
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

That's it. Uncaught exceptions on every thread are captured automatically, with the breadcrumbs and replay of the seconds before them.

The connection string is the project token and the server URL, as the project's Connection page shows it. The 1.x form ending in `/api/report` works too. An invalid connection string or `telemetry` setting does not take the app down: `Traceway.init` logs the error (tag `Traceway`) and returns `null`, and the app runs without the SDK.

## Manual Capture

```kotlin
// A caught exception, with optional attributes
try {
    riskyOperation()
} catch (e: Exception) {
    Traceway.captureException(e, mapOf("checkout.step" to "pay"))
}

// A message, reported as an issue
Traceway.captureMessage("User completed checkout")

// A named product event; triggers can match it by name
Traceway.track("checkout_failed", mapOf("reason" to "card_declined"))

// A breadcrumb on the session timeline
Traceway.recordAction(category = "cart", name = "add_item", data = mapOf("sku" to "SKU-123", "qty" to 2))

// Attributes on every later record, also usable to target this user or device from the dashboard
Traceway.setAttribute("user.id", user.id)

// Send what is ready now (blocks; call it off the main thread)
Traceway.flush()
```

From Java every method is a static on `Traceway`: `Traceway.captureException(e)`, `Traceway.setAttribute("user.id", id)`.

## How collection works

Every record is an OpenTelemetry log record or span and belongs to a *signal*. Each signal has a mode:

| Mode | Meaning |
|------|---------|
| `stream` | sent as soon as it is produced |
| `buffer` | kept in a ring buffer on the device (2 MB / 10 minutes by default) until a trigger flushes it |
| `off` | dropped |

| Signal | Source | Default |
|--------|--------|---------|
| `errors` | uncaught exceptions, `captureException` | stream |
| `messages` | `captureMessage` | stream |
| `console` | `System.out` / `System.err`, `Traceway.recordLog` | buffer |
| `network` | HTTP requests (client spans) | buffer |
| `navigation` | activities, AndroidX fragments, `Traceway.recordNavigation` | buffer |
| `userActions` | taps (`browser.user_action.click`) | buffer |
| `custom` | `Traceway.recordAction`, `Traceway.track` | buffer |
| replay | wireframe recording | buffer |

The built-in trigger fires on every exception: it uploads the buffered breadcrumbs of the last 10 minutes and the replay clip. An app that never throws sends only its session start and end (and its metrics). Clips are rationed to one per 5 seconds and 20 per session; breadcrumbs are still sent for the exceptions in between.

`recordAllSessions = true` streams every breadcrumb signal and the replay instead. The signal names, modes, triggers and limits are the browser and Flutter SDKs', so one rule can target all of them.

## Remote config

The SDK polls the server over [OpAMP](https://opentelemetry.io/docs/specs/opamp/) (plain HTTP: at start, every 5 minutes by default, when the app returns to the foreground, and about 2 seconds after `Traceway.setAttribute` or a new session, so a rule targeting an attribute applies soon) and applies the collection settings you define under **Project → Data Collection** in the dashboard: signal modes and sample rates, buffer sizes, extra triggers (for example "flush when `checkout_failed` happens twice in a minute") and on-demand captures of one user or device.

- The `capture*` options, `screenCapture` and `sessions` are **ceilings**. Remote settings can lower what is collected but never raise it: with `captureNetwork = false` no rule can turn network capture on, and without `screenCapture = true` nothing is recorded. `sampleRate`, `baselineSampleRate` and `recordAllSessions` are defaults: a rule can change them in either direction.
- The last applied settings are cached, so they are in effect from the start of the next launch.
- Set `remoteConfig = false` to never poll. Standalone (no connection string) the SDK never polls either.

### Local config

`collection` takes the same settings document a rule holds and lays it over the built-in defaults, so an app can define its own policy and triggers without the server. With `remoteConfig = true` the server's rules apply on top of it; with `remoteConfig = false` it is the whole policy.

```kotlin
TracewayOptions(
    screenCapture = true,
    remoteConfig = false,
    collection = mapOf(
        "signals" to mapOf("network" to mapOf("mode" to "stream")),
        "buffer" to mapOf("maxAgeSeconds" to 300),
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

An invalid document is ignored with a logcat warning, and the defaults apply.

## Captures

A capture uploads what the device holds from its lookback window and then streams the selected signals, and the replay when it asks for it, until it ends. Records sent under a capture carry its id (`traceway.capture.ids`), so the dashboard shows when it is collecting.

- **From the dashboard**: the Capture button on a session or issue targets one installation (`service.instance.id`), user (`user.id`) or session. The device starts it at its next poll; cancelling it stops the streaming at the poll after that. Each capture's lookback is uploaded once per installation.
- **From a trigger** (`{"type": "capture", ...}` action): started on the device the moment the trigger fires. It survives app restarts until it ends; firing again only extends it.

While a capture streams replay, the recording leaves as consecutive segments of a few seconds, each starting with a full snapshot. An exception during a streaming capture still gets a clip of its own.

### Recording triggers: journeys of taps, screens and logs

The dashboard's *recording trigger* steps run on Android. A step is one of:

| Step | Matches |
|------|---------|
| `{"event": {"type": "click", "value": "<selector>"}}` | a tap on a view the selector matches, or inside one |
| `{"event": {"type": "hover", "value": "<selector>"}}` | a mouse entering a matching view (ChromeOS, desktop mode; never touch) |
| `{"event": {"type": "navigation", "value": "/orders/{id}"}}` | arriving at a screen; `{id}` is one non-empty segment |
| `{"event": {"type": "log", "value": "Profile loaded", "mode": "contains"}}` | a console line or a `captureMessage` (`exact` by default) |

Steps match in order, each within its `withinSeconds` of the previous one; progress survives an app restart within the session. When a trigger that asks for replay fires, the session lists it as a recording reason with its sequence.

#### Naming views: how selectors work

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

You rarely write one by hand: every tap is recorded as `browser.user_action.click` with the selector of the control that handled it (`browser.css_selector`), shown on the session timeline, and the SDK only records a selector it has checked against the tapped view. The control is the nearest clickable view under the finger (or the Compose node with a click action), else the text or image that was hit. Its name is the most stable one it has, in this order: an id on the control, on a single-child wrapper around it or on a view inside it (`MaterialButton#pay`); its string tag; its text, up to 40 characters (`MaterialButton[text="Pay now"]`); its label, up to 60; a role when its class has no usable name; its class with the nearest ancestor id in front (`#cart MaterialButton`); its class alone. `app.widget.name`, `app.widget.id`, `app.widget.text`, `app.widget.label` and `app.screen.name` are beside it. The shown text and label of a masked view are never sent.

#### Naming screens

A navigation step matches the path of the screen on top:

- an activity is `/` and its class name (`/CheckoutActivity`);
- an AndroidX fragment that shows a screen is `/` and its class name (`/CartFragment`); `NavHostFragment` itself is not a screen;
- anything else reports its route itself, e.g. Compose Navigation:

```kotlin
navController.addOnDestinationChangedListener { _, destination, _ ->
    destination.route?.let { Traceway.recordNavigation("/$it") }
}
```

## Wireframe session replay

```kotlin
TracewayOptions(screenCapture = true)
```

Instead of pictures, the SDK records the view tree. After a frame the app drew (at most `fps` times a second, 10 by default, and not at all while nothing changes) it walks the windows of the app — activities, dialogs and popups — and turns what they show into HTML: boxes with their colors, gradients, borders, corners and shadows, text with its size, weight, color and alignment, clips, fades, transforms and scroll positions, as absolutely positioned elements nested the way Android draws them. What changes from one frame to the next becomes an rrweb mutation, so the dashboard replays the session with the same player as a browser session, taps and swipes included.

- **Small and cheap.** No pixels are read and no video is encoded: a clip is tens of kilobytes of JSON, a segment often less. An unchanged view costs no allocation: its node from the previous frame is reused, and the diff skips it.
- **Views and Compose.** Checkboxes, switches, radio buttons, progress bars, seek and rating bars are drawn from their state. Jetpack Compose screens are recorded from their semantics: text with its style, fields, toggles, sliders, progress, images and buttons. Compose does not expose its surface colors: a button with light text (a filled one) takes the theme's primary color, other buttons and fields a neutral look.
- **Not pixel-exact.** The browser draws text in its own font. Photos and bitmaps are never uploaded: images are placeholders, while solid colors and shapes are drawn as such and small vector icons as their shape. `WebView`, `SurfaceView`, `TextureView`, video and maps are labelled placeholders, and custom-drawn views a dashed outline.
- **Privacy.** Text typed into fields is replaced with `*` unless remote settings set `replay.maskAllInputs: false`; password fields always are.

Clips follow the collection settings: the last frames stay on the device in a ring of `maxBufferFrames` (a frame is taken only when the screen changed, so it covers at least `maxBufferFrames / fps` seconds), an exception uploads the clip, and captures stream the session as segments.

### Privacy masking

Masks change the recording only; the user sees no difference.

```kotlin
// Blur: the layout stays, its text becomes * and its images placeholders
Traceway.mask(cardNumberView)

// Blank: a box of this color, nothing inside it is recorded
Traceway.mask(profileHeader, TracewayMaskMode.blank(Color.BLACK))

Traceway.unmask(view)
```

In XML, `android:tag="traceway-mask"` (blur) or `android:tag="traceway-blank"` (blank). In Jetpack Compose:

```kotlin
import com.tracewayapp.traceway.compose.tracewayMask

Text(address, Modifier.tracewayMask())
Box(Modifier.tracewayMask(TracewayMaskMode.blank())) { ... }
```

`Modifier.testTag("traceway-mask")` and `"traceway-blank"` work too. A masked view's text and label are also left out of tap records.

## App performance metrics

The SDK measures the app on the device and uploads the numbers as OTLP metrics: base-2 exponential histograms and counters (delta temporality), one small payload every five minutes and when the app goes to the background. Nothing is sent per observation, and no user, device or session id is a dimension.

| Metric | What it measures |
|--------|------------------|
| `app.start.duration` (`start_type` `cold` / `warm` / `hot`) | Cold: from the process start (`Process.getStartElapsedRealtime()`, API 24+; earlier, from SDK init) to the first frame, only when the process was started for an activity. Warm: an activity recreated on return. Hot: from the return to the foreground to the next frame |
| `app.screen.ttid` (`screen`) | Time to initial display: from the navigation to the first frame of the screen |
| `app.screen.ttfd` (`screen`) | Time to full display: from the navigation until the app calls `Traceway.reportFullyDisplayed()` |
| `app.frames.slow`, `app.frames.frozen` (with `.total`, by `screen`) | `FrameMetrics` on API 24+ (Choreographer below): slow when a frame missed its deadline at the display's refresh rate, frozen at 700 ms or more |
| `app.hang`, `app.hang.duration` | The main thread ran nothing for 2 s or more while the app was in the foreground |
| `app.crash` | Uncaught exceptions |
| `app.session`, `app.user`, `app.user.affected` (`issue`) | Sessions; users counted once a day per installation, and once a day per problem they met, so no id leaves the device |
| `app.memory.peak` | The process's peak RSS, once per session |
| `app.request.duration`, `app.request.bytes`, `app.request.failed` (`host`) | Every HTTP request the SDK records |

Screens are route names with ids folded (`/orders/8841` → `/orders/:id`). Resource dimensions are the app version, environment, OS and its major version, form factor (`phone`, `tablet`, `desktop`) and a coarse device class from the RAM.

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
    application = this,
    connectionString = "your-token@https://traceway.example.com",
    options = TracewayOptions(serviceName = "shop-app", environment = "production"),
)
```

The app polls Traceway for its collection settings, sends logs, request spans and metrics to Traceway's OTLP endpoints, and uploads replays to the same host. What it collects is changed on the project's **Data Collection** page.

### Issues and replays on Traceway, other telemetry elsewhere

Add `telemetry` to send ordinary logs, request spans and metrics to another OTLP/HTTP receiver, such as an OpenTelemetry Collector in front of a Grafana stack:

```kotlin
TracewayOptions(
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

The shared `url` gets `/v1/logs`, `/v1/traces` and `/v1/metrics` appended. Each signal (`logs`, `traces`, `metrics`) can override it with a `TracewayTelemetryEndpoint(url, headers)` whose URL is used as given. Without a shared `url`, the signals you do not list keep their Traceway destination. URLs must use HTTP(S), without embedded credentials, a query or a fragment. Signal headers override the shared `headers` key by key; the Traceway token is never sent to these receivers.

When logs are redirected, exceptions, `captureMessage` issues and the SDK's session and control records still go to Traceway, and only there. Ordinary logs (console, navigation, taps, `recordAction`, `track`) go to the logs receiver; request spans and metrics use their own destinations. Remote config (OpAMP) and replays keep using the connection string. Both destinations carry `session.id` for correlation. Failed batches are queued on disk per destination, so a changed receiver is never sent what was meant for the previous one.

The receiver must accept OTLP/HTTP with protobuf bodies (`application/x-protobuf`, gzip). Any key built into the app can be read out of it: use an ingestion key that can only write, or a collector that holds the backend credentials.

### Standalone, without a Traceway server

```kotlin
Traceway.init(
    application = this,
    options = TracewayOptions(
        serviceName = "shop-app",
        telemetry = TracewayTelemetry(url = "https://collector.example.com/otlp"),
        collection = mapOf("signals" to mapOf("console" to mapOf("mode" to "stream"))),
    ),
)
```

No Traceway server, account or token is required. `telemetry` must have a shared `url` or all three signal endpoints. Standalone mode turns off remote config, replay uploads and session records, whatever the options say. Exceptions are ordinary OTLP log records with the event name `exception`, and messages `traceway.message`.

## Options

| Option | Default | Description |
|--------|---------|-------------|
| `sampleRate` | `1.0` | Share of exceptions and messages that are reported (0.0 - 1.0) |
| `screenCapture` | `false` | Record the screen as a wireframe: the last frames go up with an exception, and captures can stream it |
| `debug` | `false` | Log what the SDK does to logcat (tag `Traceway`) |
| `version` | `""` | App version, sent as `service.version` (`unknown` when empty) |
| `serviceName` | application id | Sent as `service.name` |
| `environment` | — | Sent as `deployment.environment.name` |
| `attributes` | `{}` | Attributes attached to every record |
| `proguardUuid` | — | The R8 build UUID, `BuildConfig.TRACEWAY_PROGUARD_UUID` (see [Deobfuscating](#deobfuscating-release-crashes-r8proguard)) |
| `maxBufferFrames` | `150` | Wireframe frames held on the device; the upper bound of every clip |
| `fps` | `10` | Most wireframe frames captured per second (1–30); only frames where something was drawn are captured |
| `persistToDisk` | `true` | Keep SDK state and undelivered telemetry on disk across restarts |
| `legacyConnectionString` | — | The connection string a 1.x build used, to deliver its unsent reports (see [Migrating](#migrating-from-1x)) |
| `captureLogs` | `true` | Capture `System.out` / `System.err` and `Traceway.recordLog` |
| `captureNetwork` | `true` | Record HTTP calls made through `TracewayOkHttpInterceptor` (or `startRequest` / `endRequest`) |
| `captureNavigation` | `true` | Record activity and fragment screens and `Traceway.recordNavigation` |
| `captureUserActions` | `true` | Record taps with the selector of the view, and feed taps to recording triggers |
| `captureMetrics` | `true` | Measure app start, screen display times, frames, hangs, crashes, sessions, users and requests, and upload them as metrics |
| `metricDimensions` | `{}` | Up to two app-defined dimensions on every metric (`custom.<key>`) |
| `recordAllSessions` | `false` | Stream breadcrumbs and the replay all the time instead of keeping them for errors |
| `collection` | — | Local collection settings, the same document as a remote-config rule (see [Local config](#local-config)) |
| `sessions` | `true` | Send session records (`session.start` / `session.end`). The session id exists either way |
| `baselineSampleRate` | `0` | Share of sessions whose breadcrumbs are sent continuously, not only around errors |
| `remoteConfig` | `true` | Poll the server for collection settings over OpAMP |
| `exportDelayMs` | `1000` | How long finished records wait to be sent as one batch |
| `telemetry` | — | Send logs, spans and metrics to another OTLP/HTTP receiver (see [Setups](#setups)) |
| `propagateTraceHeaderUrls` | `[]` | URL prefixes whose requests carry a W3C `traceparent` header (see [Connect requests to backend traces](#connect-requests-to-backend-traces)) |
| `propagateTraceHeaderUrlPatterns` | `[]` | The same, as regular expressions |

## Logs & Actions

Breadcrumbs are OpenTelemetry records held in the ring buffer and shown on the session timeline next to the exception that flushed them:

- **Logs** — every `System.out` / `System.err` line (`println`, `printStackTrace`). Calls to `android.util.Log` do not pass through `System.out`: forward them with `Traceway.recordLog(message, "warn")`, e.g. from a Timber tree.
- **Network** — every OkHttp call through `TracewayOkHttpInterceptor` as a client span (method, URL, status, duration, byte counts). The SDK's own requests are never recorded.
- **Screens** — activities and AndroidX fragments automatically; other routers through `Traceway.recordNavigation`.
- **Taps** — every tap in the app's activity windows, with the selector, type, id, shown text and label of the view, and the screen (`browser.user_action.click`, with `app.widget.*` and `app.screen.*` attributes).
- **Custom** — `Traceway.recordAction(...)` and `Traceway.track(...)`.

### Network capture

```kotlin
val client = OkHttpClient.Builder()
    .addInterceptor(TracewayOkHttpInterceptor())
    .build()
```

Add it as an *application* interceptor. Other HTTP stacks record a request with the client's `startRequest(method, url)` and `endRequest(handle, statusCode, requestBytes, responseBytes, error)`:

```kotlin
val handle = Traceway.client?.startRequest("GET", url)
// send the request, adding handle?.traceparent as a `traceparent` header when it is not null
Traceway.client?.endRequest(handle!!, statusCode = 200, requestBytes = null, responseBytes = 512, error = null)
```

### Connect requests to backend traces

`propagateTraceHeaderUrls` (prefixes) and `propagateTraceHeaderUrlPatterns` (regular expressions) list the URLs whose requests carry a W3C `traceparent` header naming the request's client span, so the backend's spans join the app's trace in Traceway or any OpenTelemetry backend:

```kotlin
TracewayOptions(
    propagateTraceHeaderUrls = listOf("https://api.example.com/"),
    propagateTraceHeaderUrlPatterns = listOf(Regex("^https://[a-z]+\\.example\\.com/")),
)
```

It is empty by default, because a header a server does not expect can break a strict CORS or request-signature check. A request that already carries a `traceparent` keeps it. Requests to Traceway and to the `telemetry` receivers never carry it.

### Disable a channel

```kotlin
TracewayOptions(
    captureLogs = false,
    captureNetwork = false,
    captureNavigation = false,
    captureUserActions = false,
)
```

## What Gets Captured Automatically

- **Uncaught exceptions** on every thread, through the default `UncaughtExceptionHandler`. The exception, its breadcrumbs and its replay clip are written to disk before the handler that was installed before (normally the platform's) ends the process; the SDK then tries to send them for up to about two seconds, and sends what is left at the next start. The crash is never swallowed.
- Sessions, screens, taps, `System.out` / `System.err`, OkHttp requests (with the interceptor), and the app's performance metrics.

## OpenTelemetry

The SDK builds its own `SdkLoggerProvider` and `SdkTracerProvider` from the OpenTelemetry Java SDK, with Traceway's processors in front of the official batch processors; the app's own OpenTelemetry setup (`GlobalOpenTelemetry`, its exporters) is left untouched. Records are encoded with the SDK's OTLP marshalers and posted by the SDK's own HTTP sender, so no HTTP library (OkHttp) is pulled into the app. Remote config uses a small built-in OpAMP plain-HTTP client, checked byte for byte against `opamp-go` (see `scripts/opamp-golden`).

## Platform Support

| Platform | Error Tracking | Session Replay | Metrics |
|----------|---------------|----------------|---------|
| Android (API 21+) | Yes | Wireframe (Views and Jetpack Compose) | Yes |

Below API 26, enable core library desugaring (see [Installation](#installation)).

For Flutter apps, use the [`traceway`](https://github.com/tracewayapp/traceway-flutter) package instead.

## Migrating from 1.x

- **Server**: 2.0 sends OTLP to `/api/otel/v1/logs`, `/api/otel/v1/traces` and `/api/otel/v1/metrics`, polls `/api/otel/v1/opamp` and uploads replays to `/api/recordings`. There is no fallback to the 1.x `/api/report` protocol.
- **Connection string**: unchanged. `token@https://host/api/report` still works, as does `token@https://host`. It is optional now: without it the SDK runs [standalone](#standalone-without-a-traceway-server).
- **Unsent 1.x reports**: set `TracewayOptions(legacyConnectionString = previousConnectionString)` to the connection string the old build used. Reports 1.x left on the device are delivered to that project's `/api/report` and expire after 12 hours, the 1.x default. Without it they expire on the device.
- **Desugaring**: apps with `minSdk` below 26 must enable core library desugaring (OpenTelemetry Java).
- **Behaviour**: logs and actions are no longer limited to the last 10 seconds and no longer travel inside the report. They are buffered for up to 10 minutes and sent as their own records with an exception. `screenCapture = true` now records a wireframe replay.
- **Removed options**: `debounceMs`, `retryDelayMs`, `maxPendingExceptions`, `maxLocalFiles`, `localFileMaxAgeHours`, `eventsWindowMs`, `eventsMaxCount`, `capturePixelRatio`. `fps` and `maxBufferFrames` now apply to the wireframe recording.
- **Removed API**: `TracewayClient.addException`, `loadPendingFromDisk`, `setDeviceAttributes`, `bufferedLogs`, `bufferedActions`, `pendingExceptionCount` and the other test accessors, `recordNetworkEvent`, `recordNavigationEvent`, the `ReportSender` interface and the 1.x event and report models. Use `captureException`, `Traceway.setAttribute`, `startRequest` / `endRequest`, `recordNavigation` and `flush()`.
- **Changed API**: `Traceway.init` returns `TracewayClient?` (null when the settings are invalid). `TracewayClient.flush(timeoutMs)` takes a non-null timeout (10 s by default).
- **New API**: `Traceway.track`, `setAttribute(s)`, `removeAttribute`, `clearAttributes`, `recordNavigation`, `recordLog`, `reportFullyDisplayed`, `metric`, `count`, `setMetricDimension`, `mask` / `unmask`, `refreshConfig`, `shutdown`, `getInstanceId`; `TracewayTelemetry`, `TracewayTelemetryEndpoint`, `TracewayMaskMode`, `Modifier.tracewayMask()`.
- **Issues regroup once**: the stack trace now reaches the server as the OpenTelemetry `exception.*` attributes, so the first 2.0 occurrence of an issue may start a new group.

## Deobfuscating release crashes (R8/ProGuard)

Release builds run R8, which renames classes and methods and rewrites line numbers, so crashes arrive obfuscated. Traceway reverses them with the build's `mapping.txt`, matched to the crashing build by a ProGuard UUID that the SDK sends as the `app.debug.proguard_uuid` resource attribute of every record.

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
    id("com.tracewayapp.symbols") version "1.0.1"
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
        version = BuildConfig.VERSION_NAME,
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

## Running the example app

The `:example` module is a small app with a checkout form built from views and a Jetpack Compose screen with Compose Navigation, exercising every capture path: caught and uncaught exceptions, messages, custom events, an OkHttp request with trace propagation, taps, screens, masks and the wireframe replay.

To point it at your own Traceway backend, put your connection string into `local.properties` at the repo root (this file is gitignored):

```properties
traceway.dsn=YOUR_TOKEN@https://your-traceway-instance.com

# Optional — only needed to exercise the com.tracewayapp.symbols plugin:
traceway.upload_token=YOUR_UPLOAD_TOKEN              # project upload token (not the connection-string token)
traceway.upload_url=https://your-traceway-instance.com   # defaults to the connection string's host
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

## Running the tests

**JVM unit tests** — no device required. They cover the settings schema and its merge, the trigger engine and selectors, OpAMP (against golden messages from `opamp-go`), OTLP export and the disk outbox, buffer persistence, sessions, replay upload, metrics, the wireframe diff and clip assembly, and the client end to end against in-memory OpenTelemetry exporters and a fake server.

The wireframe of real views and Compose screens is tested on the JVM too, under [Robolectric](https://robolectric.org) with its native graphics: `ViewWireframeTest` and `ComposeWireframeTest` check every kind of view and composable (boxes, text, rich text, fields and masks, toggles, progress, sliders, images and icon glyphs, Material components, placeholders, dialogs, popups, scrolling lists, transforms and z-order) where the replay shows it, as the Flutter SDK's `wireframe_test.dart` does for widgets. `WireframeSessionTest` drives a session with dispatched touches (taps, a swipe, typing, toggles, a dialog, a second screen), and checks that the recorded rrweb clip rebuilds every frame exactly, that the touches are where the finger was and that each tap is named by the expected selector. It writes the clips, with pictures of the real views at each checkpoint, to `traceway/build/wireframe-sessions/`.

```bash
./gradlew :traceway:testDebugUnitTest
```

`LiveServerTest` runs the real client (its own HTTP stack and threads) against a running Traceway server when `TRACEWAY_E2E_CONNECTION` names one with an Android project, e.g. `TRACEWAY_E2E_CONNECTION=token@http://127.0.0.1:8082`.

**Instrumented (on-device) tests** — hit testing and touch tracking on real views, and the wireframe capture of a real view tree:

```bash
./gradlew :traceway:connectedDebugAndroidTest
```

`tools/wireframe/replay.mjs` plays a wireframe clip in rrweb-player in headless Chromium, the dashboard's own player, to check a capture replays cleanly, and saves a picture of every checkpoint next to the pictures of the real views:

```bash
node tools/wireframe/replay.mjs --events traceway/build/wireframe-sessions/views/events.json \
    --checkpoints traceway/build/wireframe-sessions/views/checkpoints.json --out /tmp/tw-replay
```

CI runs the JVM tests via [`unit-tests.yml`](.github/workflows/unit-tests.yml) and the instrumented tests on a Pixel 8 in Firebase Test Lab via [`instrumented-tests.yml`](.github/workflows/instrumented-tests.yml).

## Publishing

Two artifacts are published to Maven Central, each with its own workflow. Both bump the version (the SDK's is `val sdkVersion` in `traceway/build.gradle.kts`), sign and upload to Sonatype Central, then commit + tag. If publish fails, no commit or tag is created — fix the issue and re-run.

**SDK** (`com.tracewayapp:traceway`) — trigger the **Publish to Maven Central** workflow:

```bash
gh workflow run "Publish to Maven Central" -f version=2.0.0-beta.1
gh run watch
```

**Gradle plugin** (`com.tracewayapp.symbols`) — trigger the **Publish Gradle Plugin to Maven Central** workflow (tagged `plugin-v<version>`, versioned independently of the SDK). It defaults to a dry run that builds, tests, signs, and publishes to a throwaway local repo without touching Central or the repo, so a misfire is harmless. Once the dry run is green, release for real by unchecking `dry_run`:

```bash
# Validate first (default):
gh workflow run "Publish Gradle Plugin to Maven Central" -f version=1.0.2

# Release once the validation run is green:
gh workflow run "Publish Gradle Plugin to Maven Central" -f version=1.0.2 -f dry_run=false
gh run watch
```

Both workflows are thin wrappers. The plugin publish logic lives in [`scripts/publish-plugin.sh`](scripts/publish-plugin.sh), which you can also run locally: `scripts/publish-plugin.sh 1.0.2` publishes to `~/.m2` for a smoke test, `--release` pushes to Central, and `-- --dry-run` validates the task graph without credentials.

Both share the same Sonatype Central account and signing key (`MAVEN_CENTRAL_USERNAME`/`MAVEN_CENTRAL_PASSWORD` and `SIGNING_KEY`/`SIGNING_PASSWORD` repo secrets); the `com.tracewayapp` namespace is already verified, which also covers the plugin's `com.tracewayapp.symbols` marker.

## Links

- [Traceway Website](https://tracewayapp.com)
- [Traceway GitHub](https://github.com/tracewayapp/traceway)
- [Documentation](https://docs.tracewayapp.com/client/android?sdk=android)
- [Flutter SDK](https://github.com/tracewayapp/traceway-flutter)
- [Browser SDK](https://github.com/tracewayapp/js-client)

## License

MIT
