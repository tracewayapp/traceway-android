# Wireframe session replay

How the Android SDK records the screen as an rrweb wireframe. The iOS SDK (`Docs/Wireframe.md`) and the Flutter SDK (`docs/wireframe.md`) follow the same pipeline, node ids, stylesheet and event shapes, so the dashboard replays all three with one player.

## Overview

The SDK never takes a screenshot and never encodes video. With `screenCapture = true` it walks the app's windows after a frame was drawn and turns the view tree (and Jetpack Compose semantics) into an HTML document of absolutely positioned boxes and text. What changes from frame to frame becomes an rrweb mutation. A clip is an rrweb event list: a Meta and a FullSnapshot, then mutations and pointer events, then a `traceway.end` custom event. The dashboard plays it in `rrweb-player`, the player it uses for browser sessions.

The files, in `traceway/src/main/java/com/tracewayapp/traceway/wireframe/`:

| File | Role |
|------|------|
| `WireframeRoots.kt` | `WireframeRoots` (the windows on screen), `WindowList`, `ViewFrameSource` (draw listeners, polling), `ForegroundTracker` |
| `WireframeCapture.kt` | `WireframeCapture`: the view walk and the per-kind converters |
| `WireframeComposeCapture.kt` | `WireframeComposeCapture`: Jetpack Compose semantics to nodes |
| `VNode.kt` | `VNode`, `WireframeFrame`, the fixed ids, `serializeDocument`, `ClipImageBudget` (the inline image allowance of one clip) |
| `WireframeCss.kt` | `wireframeCss` and the inline-style builders |
| `WireframeDiff.kt` | `diffScreens`: two frames to an rrweb mutation |
| `WireframeGlyphs.kt` | `WireframeGlyphs`: vector icons as PNG masks (coverage only, no colors) |
| `WireframeImages.kt` | `WireframeImages`: the pictures image views show |
| `WireframeRecorder.kt` | `WireframeRecorder` (cadence, frame ring, `WireframePointer` log, clips) |

The recorder implements `replay/ReplaySource.kt`; `replay/ReplayController.kt` decides when clips are cut (buffer, stream, segments) and `replay/RecordingsTransport.kt` uploads them to `/api/recordings`.

## Pipeline

roots → walk → convert → VNodes (stable ids, shared unchanged subtrees) → CSS → diff → rrweb events

1. **Roots.** `WireframeRoots.current` lists the visible windows of the process (`WindowManagerGlobal`'s views, read by reflection; the registered activities' decor views where that fails), bottom first: application windows under system ones, a sub-window (popup, menu) right above its parent. Each has its type, the dim it casts behind it (`FLAG_DIM_BEHIND`), its screen position and, for application windows, how much the keyboard covers.
2. **Cadence.** `ViewFrameSource` puts an `OnDrawListener` on every window; the recorder captures on the main thread after a draw, at most `fps` times a second (10 by default, 1–30), at once on start and when the app returns to the foreground, never while nothing draws or the app is in the background. A new window is found by the next capture or a check every 500 ms.
3. **Walk.** `WireframeCapture.capture` visits each window's views in drawing order (by `z`, then as listed). Views that are not `VISIBLE`, have alpha ≤ 0.002, no size, or lie fully outside what their clipping ancestors show are skipped. Geometry is accumulated in dp from the screen's top-left while walking; no `getLocationOnScreen` per view.
4. **Convert.** One converter per kind of view (see [What each kind of view becomes](#what-each-kind-of-view-becomes)). A view whose own code throws while it is read is left out with its subtree; the frame goes on.
5. **VNodes.** Every view that draws something, clips, scrolls, fades or is transformed gets an element, nested the way views draw; layout-only groups are folded into their children's offsets. A view keeps its node ids for as long as it lives (`Rec`, in a `WeakHashMap`), and a view that shows the same as last frame returns last frame's node object: frames share unchanged subtrees, an unchanged frame is the same object as the previous one and is not stored again.
6. **CSS.** Inline styles carry geometry (`left`, `top`, `width`, `height` in px = dp, rounded to 0.1), colors (`#rrggbb` or `rgba()`), radii, shadows, gradients, `transform:matrix(…)`, `opacity`. Shared rules come from the stylesheet.
7. **Diff.** `diffScreens(before, after)` returns `{source:0, texts, attributes, removes, adds}`, skipping subtrees that are the same object. Children that moved out of the longest increasing subsequence of kept children are removed and added back; adds are parent first, siblings last to first so each `nextId` exists already.
8. **rrweb events.** `WireframeRecorder.captureRecording` assembles a clip (see [Clips and segments](#clips-and-segments)).

## Node ids

| Id | Node |
|----|------|
| 1 | document |
| 2 | doctype |
| 3 | `html` |
| 4 | `head` |
| 5 | `meta charset` |
| 6 | `style` (the shared stylesheet, `_cssText`) |
| 7 | `body` |
| 8 | the screen, `div#tw`; pointer events target it |
| 9, 10 | the "App in the background" box and its text |
| 12, 13, 14 | the keyboard box, its label box (reserved), its text |
| 16 and up | captured nodes |

The same ids are used by the iOS and Flutter SDKs. `WireframeCssTest` asserts them.

## Stylesheet

`wireframeCss` (`WireframeCss.kt`) is byte for byte the shared `wireframe.css` of the three SDKs, and `WireframeCssTest` compares it with the copy in `traceway/src/test/resources/wireframe.css`. Change it only together with the other SDKs.

| Class | Look |
|-------|------|
| `t` | text: `pre-wrap`, line height 1.17 unless set inline |
| `t n` | one line that spills out of its box the way it is aligned |
| `e`, `k` | text the app cut: ellipsized, clipped |
| `im` | image placeholder: a grey box with a diagonal cross |
| `im p<N>` | a recorded picture (see [Images](#images)) |
| `ic`, `ic g<N>` | icon: a tinted rounded square, or its glyph mask |
| `cp` | a view that draws itself: dashed outline |
| `mk` | a blank mask (its color inline) |
| `sk`, `sk sm` | skeleton text (iOS) |
| `pv` | native view placeholder (web view, map, video, camera) with its label |

The body's font in the stylesheet is the Roboto stack. Each platform names its own stack inline on the screen node; Android's is `font-family:Roboto,"Segoe UI","Helvetica Neue",Arial,sans-serif`.

## What each kind of view becomes

The converters use the same words on every platform.

| Converter | Android views | Becomes |
|-----------|---------------|---------|
| `text` | `TextView`, buttons, `CompoundButton`s | the text's box where its layout draws it, with size, line height, color, weight, italic, underline, strike-through, letter spacing, monospace, direction and alignment; spans as `<span>` runs (up to 60); compound drawables as icons |
| `field` | `EditText` (and `TextInputEditText`) | like `text`, with typed text masked, passwords as dots, the hint shown, a Material field's label floated over its top edge when focused or filled; an underline background as an inset shadow |
| `image` | `ImageView` | a `ColorDrawable` as a fill, a `GradientDrawable` as its shape, a small (≤ 64 dp) non-picture drawable as an `ic` icon with its glyph (a PNG that is black at the drawing's alpha: the player uses it as a CSS `mask`, which reads alpha only, over the icon's `color`, so no pixel of the drawable's colors is recorded), anything else as `im` or a recorded picture; scale type, image matrix and `cropToPadding` give its place |
| `toggle` | `CheckBox`, `RadioButton`, `Switch`, `SwitchCompat`, `MaterialSwitch` | drawn from its checked and enabled state |
| `slider` | `SeekBar` (`AbsSeekBar`) | track, filled part and thumb at its value |
| `progress` | `ProgressBar` | a bar when wider than 2.5 times its height, a ring otherwise; indeterminate ones as a third of a bar or a partial ring |
| `ratingBar` | `RatingBar` | its stars as `★☆` text |
| `nativePlaceholder` | `WebView`, maps (`*MapView`, Google Maps, Mapbox), `SurfaceView`, `TextureView` | a `pv` box labelled from the class names ("Web view", "Map", "Video", "Camera", "Native view") |
| `customDrawing` | a view of no known kind that overrides `onDraw` | its background, marked `cp` |
| `group` | `ViewGroup` | an element only when it draws, clips, scrolls, fades, is transformed or clips overflowing children; else its children go into the parent's element |
| `scroll` | scrolled groups, `ScrollView`, `HorizontalScrollView`, padding clips | a clip box holding the content shifted by the scroll |
| `dim` | a dialog window's dim | a full-screen `rgba(0,0,0,<dim>)` box between the windows |
| `keyboard` | the IME | a neutral "Keyboard" box (see [Windows, dialogs and the keyboard](#windows-dialogs-and-the-keyboard)) |

Backgrounds: `ColorDrawable` (with its tint), `GradientDrawable` (fill, radii, oval, stroke, linear, radial and sweep gradients); anything else (`MaterialShapeDrawable`, nine-patches) is read by drawing a few pixels of it into an 8 × 8 bitmap. A ripple without content draws nothing. Elevation is a `box-shadow`, `clipToOutline` an `overflow:hidden` with the outline's radius. A foreground that draws an outline (an outlined `MaterialCardView`'s stroke) becomes a box over the children with that outline as an inset `box-shadow`; the rest of a foreground (a ripple's press, a scrim) is left out.

Text weights are written as the app sets them, as the iOS and Flutter SDKs do, so a weight means the same in every SDK's replay. A viewer without Roboto Medium draws medium (500) text with its regular face.

Compose (`WireframeComposeCapture`) walks `semanticsOwner.unmergedRootSemanticsNode` (depth ≤ 200) and classifies each node as a field, slider, progress, toggle, text, image, button, selected tab, chip, pane or scroll container; nodes of none of these are folded. A Material 3 `FilterChip` or `InputChip` (`Role.Checkbox` with `Selected` and a label) is a chip: 8 dp corners, tinted when selected, outlined when not. A pane (a node with a `paneTitle`: a `ModalBottomSheet`'s sheet, a dialog, a drawer) is an opaque surface (Material 3's surface container, light or dark), rounded at the top when it sits at the bottom; the scrim beside it (a childless node that only dismisses, over the whole view) dims the screen at 32 % instead of being a picture. Text style comes from the `GetTextLayoutResult` action, read again only when the text or its box changes. Compose keeps surface colors to itself: a wide button with light text takes the theme's primary color, other buttons and fields a neutral look. Compose `graphicsLayer` alpha and transforms are not read. A Compose view that fails becomes one `cp` box.

## Images

Pictures are recorded when the replay image policy allows them: the setting `replay.images` (`off`, `wifi`, `always`; `wifi` by default) under the ceiling `TracewayOptions.replayImages` (`ALWAYS` by default), the lower of the two. `wifi` means an unmetered connection (`NetworkState`: `ConnectivityManager.isActiveNetworkMetered()` false, followed with a default-network callback on Android 7+, read at most every 5 s below). The policy is decided when a frame is captured: a frame captured while pictures are not allowed has no picture style at all, those of pictures recorded in the last 30 s included.

- What: the bitmap of an `ImageView`'s `BitmapDrawable` (the top bitmap of a layer drawable), and `BitmapDrawable` backgrounds. Never a screenshot, never under a blur or blank mask. Compose images stay placeholders: the semantics tree holds no pixels.
- Encoding: copied at the size it shows × 1 (px per dp), the longest side ≤ 384 px (≤ 192 px for a bitmap with alpha), never larger than the bitmap. The copy is drawn on the main thread, during the capture: image loaders recycle and reuse their pooled bitmaps at any time, and a bitmap recycled while another thread reads it aborts the process, so the encoder only ever reads the SDK's own copy. A hardware bitmap is drawn through a `Picture` at the copy's size (API 28+; on API 26–27 it is left out). At most 8 new copies per frame and 4 MiB of copies waiting for the encoder; past either, a picture waits for a later frame. JPEG at quality 0.45; always PNG when the bitmap has alpha (JPEG would replay its clear pixels as black). An image whose encoding is > 48 KiB at that size and at half of it is left out, as is one that cannot be copied (out of memory). The encoding runs on a `TracewayImages` thread; the first frame shows `im`, the frame after the encoder finished shows the picture.
- DOM: one `style` node per picture first in the screen (after the glyph styles), `.im.p<N>{background:url(data:image/jpeg;base64,…) center/cover no-repeat}` (`image/png` for a picture with alpha). The image node gets the classes `im p<N>` and inline `background-size` and `background-position` that put the whole picture where the view draws it (fill, fit, center and crop alike; a cropped picture has a negative position). A bitmap background is a child node `im p<N>` at the background's bounds with `background-size:100% 100%`, and its placeholder tint is dropped.
- Dedup: by bitmap identity, its generation id (a pooled bitmap that holds another picture is a new one) and the copy's size.
- Limits: ≤ 128 pictures kept; past that, pictures not shown for 30 s (whose styles are off the page) make room, else the one shown longest ago (not one on the screen being captured, nor one still encoding; its style leaves the page, and shown again it is a new picture), and a new session starts the set again. 4 MiB of encoded data per session, counted in the replay budget (`CollectionBudget`, persisted under `budget`); past it, new pictures are placeholders and pictures already recorded keep showing. A picture's style stays 30 s after its view left the screen. Class numbers (`p<N>`) are never reused within a recorder.
- Per clip: each exception clip or streamed segment carries at most 128 KiB (131,072 characters) of inline image data URLs, pictures and icon glyphs together (`ClipImageBudget`). The characters of every `data:image/…;base64,…` URL count each time a FullSnapshot or a mutation writes its style, so a picture in two snapshots counts twice. A clip that would go over it is written with every picture and glyph style empty: pictures show the `im` placeholder and icons their square for the whole clip, while text, layout, masks and pointer events stay. The frames in the ring are not changed, and the next clip starts with a fresh allowance.
- Reported: `traceway.replay.images` (the policy at the session's start) and `traceway.replay.image_bytes` on session events.

## Privacy

- `view.tracewayMask = TracewayMaskMode.blur()` (or `Traceway.mask(view, mode)` from Java, or `android:tag="traceway-mask"`): the layout stays, every non-space character becomes `*`, images are placeholders, icons get no glyph, pictures are never recorded.
- `TracewayMaskMode.blank(color)` (or `android:tag="traceway-blank"`): one `mk` box of that color; nothing inside is walked.
- Compose: `Modifier.tracewayMask(mode)` or `Modifier.testTag("traceway-mask" | "traceway-blank")`.
- A mask covers its subtree. Tap records leave out the text and label of a masked view, and a mask tag never names one.
- Typed text is masked while `replay.maskAllInputs` is on (the default); passwords (`PasswordTransformationMethod`, password input types, Compose `Password`) are always shown as `•`. Hints are not masked as input.
- A stricter setting applies to what was already recorded. When `replay.maskAllInputs` goes from false to true, or `replay.images` to a stricter value (`always` to `wifi` or `off`, `wifi` to `off`), `ReplayController.apply` drops the recorder's frames and pointers (`clearBuffer`: the capture is reset, a frame still being captured is not kept, and the screen is captured again at once without waiting for a draw), the clips being assembled and the clips waiting in `RecordingsTransport`, spooled ones included. A looser setting drops nothing. The browser SDK and the Flutter and iOS wireframe recorders do the same.
- An icon glyph holds the icon's coverage only. A small picture that the capture takes for an icon (a drawable class it does not know as a bitmap, not opaque, ≤ 64 dp) is therefore recorded as a silhouette, never as its pixels, whatever the image policy; a mask leaves it out.

## Scrolling

A group that is scrolled (`scrollX`/`scrollY` not 0), a `ScrollView` or `HorizontalScrollView`, or a group whose children overflow a padding it clips to, gets a sticky wrapper: role 1 is the clip box (`overflow:hidden`, inset by the padding when `clipToPadding`), role 2 the content shifted by `left:-scrollX;top:-scrollY`. A scroll is then one attribute mutation; rows that leave the visible area are culled and come back as adds. `RecyclerView` rows keep their nodes while they are bound to the same views. Compose scroll containers are `overflow:hidden` boxes; their children move.

## Windows, dialogs and the keyboard

- Every window of the process is captured: activities, dialogs, popups, menus, toasts. A dialog's dim is a full-screen box between its window and the ones below. Popups sit right above their parent window.
- The screen is the base application window's extent. A window above the first that no capture has seen yet joins from the next frame (50 ms later), once it is in its place: a Compose menu is first added at the corner of the screen.
- The keyboard is a window of the input method's process, not in the list. The base application window's IME inset (`WindowInsets.Type.ime()`, Android 11+; below, what its visible display frame leaves out at the bottom beyond the navigation bar when that is more than 15 % of the window) gives where it is. The capture draws a box there, from where the inset puts its top to the bottom of the screen, right over the topmost window that may take the keyboard (`WindowManager.LayoutParams.mayUseInputMethod`), as Android places the IME over its target: a dialog without a text field (`FLAG_ALT_FOCUSABLE_IM`, which `AlertDialog` sets then) stays over it, one that types stays under it. Id 12, `background-color:#d1d3d9;color:#6d6d72` (dark mode `#2c2c2e`/`#8e8e93`), `display:flex;align-items:center;justify-content:center;font-size:13px`, with the text "Keyboard" (id 14), the same box the iOS SDK draws. Its keys are never recorded.
- In the background the screen is the "App in the background" box (ids 9 and 10, `#1e1e1e`).

## Pointers

The `TouchTracker` wraps the `Window.Callback` of every activity window and of the windows the app opens over them, which it finds in the window list after each gesture, when a window loses the focus and while windows draw: dialogs, bottom sheets and Compose dialogs (their `Window` read from the `DecorView`). Below Android 9 it wraps popups' touch interceptor too; from Android 9 the framework refuses apps the link from a popup's view to its `PopupWindow` (`PopupDecorView.this$0` is a blocked non-SDK field), so taps in popups and dropdowns (`PopupWindow`, `ListPopupWindow`, `AutoCompleteTextView`, `Spinner` menus) are not recorded there; the popups themselves are drawn. It hands pointer events to the recorder in dp from the screen's top-left. The recorder keeps them as `WireframePointer` entries (at most 4000; moves thinned to one per pointer per 50 ms) and turns them into rrweb events on node 8:

| Pointer | Touch | Mouse |
|---------|-------|-------|
| down | MouseInteraction type 7 (TouchStart) | type 1 (MouseDown) |
| move | source 6 (TouchMove) `positions` | source 1 (MouseMove) |
| up | type 9 (TouchEnd) | type 0 (MouseUp) |
| tap | type 9 + type 2 (Click) | type 0 + type 2 |
| cancel | type 10 (TouchCancel) | type 0 |

A tap is an up within the touch slop (4 dp for a mouse) and before `ViewConfiguration.getLongPressTimeout()`. Nothing is recorded while the app is in the background.

## Clips and segments

- A clip starts with Meta (type 4) `{href, width, height}` in dp and a FullSnapshot (type 2) with `initialOffset {left:0, top:0}` of the frame showing at its start, then one Mutation (type 3, source 0) per changed frame, the pointer events, and a Custom (type 5) `traceway.end` at its end when the last event is earlier. A new Meta + FullSnapshot follows whenever the screen size changes or the app goes to or comes back from the background. `href` is the screen's path (`/CheckoutActivity`).
- The recorder keeps the last `maxBufferFrames` frames (150 by default); an identical frame is not stored again.
- `ReplayController` cuts clips: in buffer mode the default trigger uploads the last 30 seconds with an exception (at most one clip per 5 s, 20 per session); in stream mode (captures, `recordAllSessions`, `replay.mode: stream`) consecutive segments of `0.8 × maxBufferFrames / fps` seconds, at most 10 s, each starting with Meta + FullSnapshot. Segments are gzipped JSON `{"segments":[…]}` posted to `/api/recordings`.
- Budget: 50 MiB and 10,000 rrweb events per session, then nothing more is uploaded for it; the counts are on the session events.
- Images: 128 KiB of inline image data URLs per clip or segment; one that would carry more is written with placeholders instead of its pictures and icon glyphs (see [Images](#images)).

## Limits

- At most `fps` captures a second, only after draws. A capture walks at most 3000 views, 128 deep (the iOS SDK's budget); the rest of the screen is left out. A frame shares every unchanged subtree with the previous one, so a still screen costs nothing.
- 300 icon glyphs (vector icons up to 256 × 256 px at 2 px per dp), 128 pictures, 4 MiB of pictures per session, 128 KiB of inline image data URLs (pictures and glyphs) per clip or segment.
- The browser draws text in its own font: line breaks can differ from the device.
- Not read: Compose `graphicsLayer` alpha and transforms, Compose surface colors, `WebView` contents, `SurfaceView`/`TextureView` pixels (video, camera, maps, games), custom `onDraw` output, animated drawables.

## Testing

- `WireframeCssTest`: number, color, shape, gradient and text builders; the stylesheet equals the shared `wireframe.css`; the fixed node ids.
- `WireframeDiffTest`: random trees and edits replayed through `Mirror`, a strict model of rrweb's replayer.
- `WireframeRecorderTest`, `WireframeSampleClipTest`: cadence, the frame ring, background, pointers, clip assembly.
- `ViewWireframeTest`, `ComposeWireframeTest` (Robolectric, native graphics, 400 × 800 dp): one test per topic of the shared wireframe suites (1–40 views, 41–48 Compose), each where the replay shows it against where Android drew it.
- `WireframeImagesTest` (Robolectric): images off, always, Wi-Fi on a metered connection, masks, dedup, the budget.
- `WireframeSessionTest` (Robolectric): a session with dispatched touches, replayed frame by frame; writes `traceway/build/wireframe-sessions/`.
- `WireframeGalleryTest` (Robolectric, 412 × 915 dp, xhdpi): the 28 scenarios of the gallery every SDK shares, recorded by the real recorder while they are driven by touches through the windows' callbacks; every frame and checkpoint replayed through `Mirror`; writes `traceway/build/wireframe-gallery/` for `tools/wireframe/run.py`, which renders it in rrweb-player and checks texts, geometry and pictures against the app and the baseline (`tools/wireframe/baseline/android-412x915/`), standalone or through a running dashboard (`--dashboard`).
- `ViewWireframeInstrumentedTest` (device): the capture of real views at the device's density.

Run them with `./gradlew :traceway:testDebugUnitTest` (and `:traceway:connectedDebugAndroidTest` on a device).
