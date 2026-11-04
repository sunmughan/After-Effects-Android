# M3 — Surface presentation, backend reporting, media decoder and cache limits

Scope: the M3 preview/render engine only. This note documents what the
code does today; it is not a PHASES.md status change (the coordinator
owns that file), and it claims nothing about physical ARM64 hardware.

## 1. Surface / Texture presentation path

Rendered native frames are presented in the editor through
`ui/TexturePreview.kt`, a `TextureView` whose `Surface` is drawn onto
with a Canvas — not only displayed as a Bitmap `Image`. The Bitmap
preview stays alongside it; both decode the same RGBA envelope through
`engine/RenderedFrameBitmap.kt`, so they cannot show different pixels
for the same frame.

Lifecycle is a pure state machine, `engine/SurfacePresentation.kt`
(JVM-tested, no Android types):
- Surface available → attach, owe one re-render. The parent answers by
  bumping a nonce that re-runs the render effect; the new bytes flow
  into the TextureView's update block and are drawn.
- Surface destroyed → detach, release the Surface exactly once, clear
  the loss flag; no further draw is attempted.
- Surface resized → a changed size owes one re-render; an unchanged
  size redraws the latest frame as-is.
- Session loss / recovery → the parent drives `onSessionLost` /
  `onSessionRecovered` on the remembered presentation; recovery
  re-attaches at the remembered size and owes a re-render.

Drawing is defensive: a null or malformed envelope draws nothing and
never crashes the surface. The Surface and latest frame live in one
remembered holder because the factory listener and the update block are
separate lambdas — a plain local would give each its own copy.

## 2. Backend status, honestly visible and recorded

`NativeSession.BackendStatus` already came from the native side
(`session_backend_status` in `native/src/lib.rs`); what was missing
was visibility and recording:
- `displayText()` is shown under the editor preview: GPU names its
  adapter (`GPU · Adreno 830`), CPU names its fallback reason, a lost
  device is labelled `· device lost`. The old preview label
  "CPU frame ready" was a lie on GPU-backed sessions and now reads
  "Frame ready".
- `reportLine()` is one greppable log line carrying the actual values
  verbatim (`backend=gpu adapter=… reason=… lost=…`).
- The instrumented test `sessionRecordsActualBackendValue` logs that
  line under tag `EffectCraftBackend` AND asserts it contains the
  actual reported backend/adapter/reason. This closes the Run 37 gap:
  the previous test asserted only the value's shape and never recorded
  which backend ran, so GPU acceleration was never proven.
- The same test covers CPU fallback after simulated loss (render after
  `simulateGpuLoss` still serves the frame at the same size).

Still unproven, stated plainly: GPU on a physical ARM64 device. The CI
emulator is x86_64 with no GPU option configured; a physical ARM64 leg
reporting `backend=gpu` with a named adapter is still required before
any GPU claim. Nothing here changes that gate.

## 3. Media decoder feature / codec support surface

Decoding is the pinned upstream `effectcraft-media::MediaPool`
(rev `e71dd62`), pure-Rust FilmCraft codecs — no platform MediaCodec
is involved, so the codec surface is the same code on every ABI, but
ARM64 decode performance is still unmeasured (see §5).

Containers and codecs, from upstream `crates/media/src/lib.rs`:
- Video containers: MP4/MOV, Matroska/WebM, MXF OP1a / OP-Atom;
  extensions `mp4, m4v, mov, mkv, webm, mxf, avi`.
- Video codecs: H.264, HEVC, VP9, AV1, ProRes, DNxHD, MPEG-2, MJPEG.
- Audio codecs: AAC, Opus, PCM, MP3/FLAC/Vorbis.
- Stills (`image` crate + dedicated parsers): PNG, JPEG, GIF, WebP,
  TIFF, BMP, OpenEXR, Targa, Radiance HDR, PSD/PSB (merged image or
  one layer), SVG (rasterised at any scale), PDF/AI/EPS, DPX/Cineon.
- Audio-only files and image sequences are supported through the same
  pool; 3D models (`gltf, glb, obj, stl, fbx`) are importable items,
  not decoded footage.

Unsupported input fails as a decode/probe error through the pool; it
never produces a fake frame. Cancellation is cooperative (see the
session contract in `NativeSession.kt`): it cannot interrupt an
upstream `comp_frame` already executing.

## 4. Memory cache limits (the actual fix in this change)

The bridge previously created every pool with `MediaPool::new()`,
whose upstream default budget is **1 GiB per pool** — one pool per
session plus one shared stateless pool. That is not a phone budget.

Now, in `native/src/lib.rs`:
- `ANDROID_MEDIA_POOL_BUDGET_BYTES = 256 MiB`, applied to every pool
  the bridge creates (session pools via `with_budget`, and the shared
  stateless pool).
- `session_media_pool_stats` (JNI `mediaPoolStatsSessionNative`)
  reports live `{frames, bytes, budget, hits, misses, opened}` from the
  pool itself; Kotlin parses it in `MediaPoolStats` and shows
  `Media cache · N frames · B bytes / 268435456 budget`, with
  `· OVER BUDGET` surfaced, never hidden, when bytes exceed budget.
- Upstream LRU semantics are preserved and tested: shrinking the
  budget evicts least-recently-used frames, always keeping the newest
  frame. A single frame larger than the budget is still decoded (the
  current frame is needed) but is not retained past it.
- The rendered-frame cache (`RenderedFrameCache`) is separate and
  unchanged: 12 MiB total, 12 entries, oversized frames not retained,
  defensive copies.

Native tests: session pool reports the live 256 MiB budget before any
render; a two-frame pool shrinks to one frame on budget reduction.
JVM tests: stats parsing, over-budget reporting, the 256 MiB constant.
Instrumented test: session stats report the Android budget and stay
within it after renders.

## 5. What still needs device proof (not claimed here)

- GPU with a named adapter on a physical ARM64 device.
- Surface presentation on a physical device (emulator leg covers the
  state machine, the JNI stats/backend paths, and the composable
  wiring; actual TextureView drawing on hardware is not yet observed).
- ARM64 media decode performance, video/audio playback pacing, and
  cancellation timing; 720p/1080p budgets are M5/M6 gates.
- No Android SDK exists in this sandbox, so no local Gradle/emulator
  run was possible; CI on the feature branch after integration is the
  verification gate for the Kotlin/Android and instrumented layers.
