# Wireframe checker

`run.py`, `replay.mjs`, `compare.js` and `dashboard.mjs` check a wireframe recording the way the dashboard
replays it. The same four files are in traceway-ios `CI/wireframe/` (byte-identical; change both together).

## Inputs

- A **gallery** (CONTRACT §5.2): `events.json`, `run.json` (`platform`, `device`, `os`, `width`, `height`,
  `density`, `secrets`, `checkpoints` with `name`, `scenario`, `at`, `shows`, `hides`, `ignore`, `minMutations`,
  `boxes`), `app/<checkpoint>.png`, `tree/<checkpoint>.txt`. `WireframeGalleryTest` writes the Android one to
  `traceway/build/wireframe-gallery/` (`WIREFRAME_GALLERY_OUT`): 28 scenarios of real views and one Compose
  screen, 70 checkpoints, on a 412 × 915 dp xhdpi screen under Robolectric. `boxes` hold every text on screen
  that the replay shows as it is (not masked or typed), in dp from the screen's top-left, where Android drew it:
  a TextView's text layout (inside its padding), a Compose text's node, the box around it when transformed. The
  app pictures are the windows Android drew, composed and scaled to one pixel per dp; the area of the keyboard is
  in `ignore` (the app draws no keyboard, the replay a neutral box).

  ```sh
  ./gradlew :traceway:testDebugUnitTest --tests '*WireframeGalleryTest*'
  WIREFRAME_GALLERY_ONLY=lists,dialogs ./gradlew :traceway:testDebugUnitTest --tests '*WireframeGalleryTest*'
  ```
- A **clip** with `events.json`, `checkpoints.json` (`{"name": epochMs}`) and optional `views/<checkpoint>.png`,
  as `WireframeSessionTest` writes to `traceway/build/wireframe-sessions/<name>/` and `WireframeSampleClipTest` to
  `traceway/build/wireframe-sample/`. It is checked as an Android run without texts or boxes, sampled at each
  checkpoint (rrweb shows the events from before the moment it seeks to, so a frame stamped with the checkpoint's
  very time counts as after it), with the baseline key
  `android-<w>x<h>-<name>`.

## Standalone

```sh
python3 tools/wireframe/run.py --dir traceway/build/wireframe-gallery --out ~/.cache/tw-wireframe/gallery
python3 tools/wireframe/run.py --dir traceway/build/wireframe-gallery --only 'lists*,dialogs'
python3 tools/wireframe/run.py --dir traceway/build/wireframe-gallery --update-baseline   # after reviewing report.html
python3 tools/wireframe/run.py --dir traceway/build/wireframe-sessions/views --out ~/.cache/tw-wireframe/views
python3 tools/wireframe/run.py --gh-run <id> --repo tracewayapp/traceway-android   # artifact wireframe-gallery
```

Checks: event structure; every mutation through a strict model of rrweb's replayer; each checkpoint's `shows`,
`hides`, `minMutations`; no secret anywhere; and in rrweb-player at 1:1 (Playwright + Chromium):

| Check | Passes when |
|---|---|
| visual vs app | SSIM ≥ 0.5, edges ≥ 0.3, colour ≥ 0.6 (`--floor-*`), none more than 0.03 below the baseline's (`--tolerance`) |
| visual vs baseline | ≤ 0.2% of the pixels changed (`--max-change`), anti-aliasing left out |
| geometry | each `boxes` entry is within 1.5 px (position) and 2 px (size) of the `getBoundingClientRect` of the replayed element whose own text is its `text` (`--geometry-position`, `--geometry-size`) |
| size | each scenario's bytes ≤ 1.5 × baseline + 20 KB |

Baselines live in `tools/wireframe/baseline/<platform>-<w>x<h>/` (the Android gallery's in `android-412x915/`: the
accepted rendering of every checkpoint, its fidelity and the bytes of every scenario). A run fails when a
checkpoint's picture changes from it by more than 0.2% of its pixels, so update it, after looking at
`report.html`, whenever a change to the capture or the gallery is meant to change what the replay shows. `report.html` in `--out` shows the app, the
replay, their difference, the baseline and the changes for every checkpoint, the geometry deltas per box, and the
size per scenario; `visual.json` holds the numbers.

Defaults: Playwright from `~/Work/workspace/js-client/node_modules/playwright` (`--playwright` or
`PLAYWRIGHT_DIR`), Chromium from `PATH` (`--chromium`), rrweb-player from
`../traceway/frontend/node_modules/rrweb-player` (`--rrweb-player`).

## Dashboard

`--dashboard <origin>` uploads the run into a running Traceway as a session of the seeded `Android App` project
(`android-dev-token`, from `traceway/examples/devtesting-embedded`; `--project`, `--token`, `--email`,
`--password` change it): an OTLP `session.start` log with scope `traceway-android` `2.0.0-beta.1`, the events
through `POST /api/recordings` (gzip, one segment per Meta + full snapshot), `session.end`. Each segment carries a
custom action `wireframe-gallery/<checkpoint>` at every checkpoint; the session page lists them under Actions and
clicking one seeks its player there. At every checkpoint the dashboard's replay iframe must report no rrweb
errors, show the `shows` and none of the `hides`, hold the `boxes`, have the same texts and element layout (boxes
within 0.5 px) as rrweb-player, and look like it (SSIM ≥ 0.9, ≤ 5% of pixels different at the dashboard's scale).

```sh
# The Android gallery through the dashboard (Traceway on :18182, the dashboard on :15183)
python3 tools/wireframe/run.py --dir traceway/build/wireframe-gallery --out ~/.cache/tw-wireframe/dashboard \
    --dashboard http://127.0.0.1:15183 --traceway http://127.0.0.1:18182

# Traceway on spare ports in a fresh directory, and the dashboard from traceway/frontend
mkdir -p ~/tw-e2e && (cd ../traceway/examples/devtesting-embedded && go build -o ~/tw-e2e/devtesting .)
(cd ~/tw-e2e && DEVTESTING_APP_PORT=18180 DEVTESTING_TRACEWAY_PORT=18182 ./devtesting) &
(cd ../traceway/frontend && TRACEWAY_API_PROXY=http://127.0.0.1:18182 npx vite dev --port 15183) &
python3 tools/wireframe/run.py --dir traceway/build/wireframe-sessions/views \
    --dashboard http://127.0.0.1:15183 --traceway http://127.0.0.1:18182
```
