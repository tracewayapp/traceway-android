## 2.0.0-beta.1

Rebuilt on the official OpenTelemetry Java SDK, at parity with the browser SDK 2.x and the Flutter SDK 2.0: OTLP logs, spans and metrics, a ring buffer on the device, remote config over OpAMP, captures, recording triggers, taps, app performance metrics, and wireframe session replay.

- **Breaking**: requires a Traceway server with OTLP/OpAMP support for Android projects; the 1.x `/api/report` protocol is gone. Apps with `minSdk` below 26 must enable core library desugaring. Options `debounceMs`, `retryDelayMs`, `maxPendingExceptions`, `maxLocalFiles`, `localFileMaxAgeHours`, `eventsWindowMs`, `eventsMaxCount` and `capturePixelRatio` were removed, as were the 1.x transport and test methods of `TracewayClient` and the `ReportSender` interface. `Traceway.init` returns `TracewayClient?`: null when the connection string or `telemetry` is invalid, which is logged instead of thrown.
- **OpenTelemetry inside**: logs and spans are records of private `SdkLoggerProvider` / `SdkTracerProvider` instances of `opentelemetry-sdk` 1.68, sent through the official batch processors and encoded with its OTLP marshalers; the app's own OpenTelemetry setup is untouched. Requests go through the SDK's own HTTP sender, gzip-compressed, so no HTTP library is added to the app.
- **Ring buffer**: console lines, HTTP requests, screens, taps and custom events stay on the device (2 MB / 10 minutes) and are uploaded when an exception or a trigger asks for them. The buffer is saved when the app goes to the background and restored when the same session goes on after a restart.
- **Remote config over OpAMP**: signal modes, sample rates, buffer sizes, triggers and on-demand captures from the dashboard's Data Collection page. Init options are ceilings. The last applied settings are cached for the next start. The OpAMP client is checked byte for byte against `opamp-go` (`scripts/opamp-golden`).
- **Local config**: `TracewayOptions.collection` takes a settings document, under the remote rules or on its own with `remoteConfig = false`.
- **Crashes survive the process**: an uncaught exception, the breadcrumbs its trigger flushes and its replay clip are written to disk before the previous handler runs, then sent for up to about two seconds; the rest goes at the next start. Failed batches wait in an on-disk outbox (200 batches, 20 MiB, 24 h).
- **Wireframe session replay** (`screenCapture = true`): the view tree of every app window (activities, dialogs, popups) and Jetpack Compose semantics recorded as rrweb HTML snapshots and mutations, replayed by the dashboard's rrweb player. Captured only after frames that drew, at most `fps` (10) a second; unchanged views are reused between frames; a view whose own code throws while it is read costs its own nodes, not the frame; a window that just opened joins from the next frame, once it is in its place (a Compose menu is first added at the corner of the screen). Material components keep their shapes and colors, and a `TextInputLayout`'s label floats over its field. Small vector icons are drawn as their shape, one picture per shape whatever its tint. Text fields are masked unless `replay.maskAllInputs` is off, password fields always. `Traceway.mask(view, mode)`, `android:tag="traceway-mask"` / `"traceway-blank"` and `Modifier.tracewayMask()` mask a subtree.
- **Recording triggers** (`click`, `hover`, `navigation` and `log` steps) run on Android with the browser SDK's sequence engine. Taps are recorded as `browser.user_action.click` with a CSS-like selector of the view (`MaterialButton#pay`).
- **Screens**: activities and AndroidX fragments are reported automatically as `/ClassName`; `Traceway.recordNavigation` reports Compose Navigation or other routers.
- **App performance metrics**: cold, warm and hot start, time to initial and full display per screen (`Traceway.reportFullyDisplayed()`), slow and frozen frames (`FrameMetrics`), hangs, crashes, sessions, daily users and affected users, peak memory and HTTP requests by host, aggregated on the device as base-2 exponential histograms and counters and uploaded to `/api/otel/v1/metrics`. App-defined metrics with `Traceway.metric` / `Traceway.count` and dimensions with `metricDimensions` / `Traceway.setMetricDimension`.
- **Network**: `TracewayOkHttpInterceptor` records OkHttp calls as client spans; `startRequest` / `endRequest` record any other HTTP stack. `propagateTraceHeaderUrls` / `propagateTraceHeaderUrlPatterns` add a W3C `traceparent` header.
- **Other OpenTelemetry backends**: `TracewayOptions.telemetry` sends logs, spans and metrics to any OTLP/HTTP receiver, next to Traceway or standalone (no connection string).
- **1.x migration**: `legacyConnectionString` delivers reports a 1.x build left on the device to its project's `/api/report`.
- New API: `Traceway.track`, `setAttribute(s)`, `removeAttribute`, `clearAttributes`, `recordNavigation`, `recordLog`, `reportFullyDisplayed`, `metric`, `count`, `setMetricDimension`, `mask` / `unmask`, `refreshConfig`, `shutdown`, `getInstanceId`.
- Build: Android Gradle Plugin 8.7, Kotlin 2.0, compileSdk 35.

## 1.0.1

Symbol uploads and minor fixes.

## 1.0.0

First release: uncaught and caught exceptions with the last ~10 seconds of logs, OkHttp calls, activity transitions and custom breadcrumbs.
