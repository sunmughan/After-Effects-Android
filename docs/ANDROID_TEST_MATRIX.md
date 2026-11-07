# Android device and workflow test matrix

This matrix is the release gate for the native Android implementation. Passing the Compose shell tests alone is not sufficient for a video editor.

## Device coverage

| Tier | Device class | Form factor | Required checks |
|---|---|---|---|
| Minimum | Android API 26 device or emulator | Compact phone | Launch, save/reopen draft, rotate, low-memory recovery |
| Mainstream | Current mid-range Android device | Phone | Timeline input, thermal behavior, media storage, 1080p preview target |
| High-end | Current flagship Android device | Phone | Sustained playback/rendering, GPU driver recovery, large compositions |
| Large screen | Android tablet | 10–13 inch | Inspector + viewer + timeline layout, split-screen and resize |
| Foldable | Foldable emulator or physical device | Folded/unfolded | Continuous resize, state retention, panel reflow |
| Pen input | Android tablet with stylus | Tablet | Pressure, tilt, pointer identity, palm rejection (when implemented) |

Record device model, Android build, ABI, RAM class, GPU/Vulkan driver, test build SHA, result, and issue link for every physical run.

## Functional cases

- Cold launch without a network connection or account.
- Create a composition, add/select/remove a layer, change opacity, scrub the timeline, save, force-stop, and reopen.
- Save while storage is low; ensure a failed save does not destroy the previous valid file.
- Rotate phone and switch phone/tablet window sizes without losing the selected project or playhead.
- Exercise app background/foreground, process death, permission revocation, and external-media URI expiry.
- Open the largest supported test project and observe memory use, frame pacing, dropped frames, and thermal throttling.
- Validate audio focus, Bluetooth route changes, and synchronization when audio preview is introduced.
- Render and export 720p, 1080p, and 4K fixtures when the native engine/export pipeline is integrated.

## Current bootstrap scope

Only the phone/tablet layout shell, local draft storage, layer interactions, opacity adjustment, and timeline playhead controls exist today. Real rendering, real media/audio, SAF import/export, stylus support, engine project compatibility, and performance acceptance thresholds remain future milestones.
