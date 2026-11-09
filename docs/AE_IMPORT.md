# Importing projects into EffectCraft

## What each import type accepts

The **Import project** button (header, and File > Import project)
offers four options. Each one says honestly what it takes:

| Option | Accepts | Notes |
|---|---|---|
| EffectCraft project (.ecproj / .ecprojx) | A project saved by EffectCraft (JSON `.ecproj` or XML `.ecprojx`) | The native inspector validates it before anything is kept. |
| After Effects project (.aep / .aepx) | A raw After Effects project file | Direct import (below). Shows its loss report first; nothing is dropped silently. |
| Project bundle (.zip) | A `.zip` holding `project.json` plus its media files | `project.json` is either an `.ecproj` document or an After Effects export (below). Media is extracted and relinked. |
| After Effects export (.zip) | A `.zip` produced by the included ExtendScript (below) | Interim route for projects the direct `.aep` import cannot fully read. |

## Direct .aep import: in this build (v1, honest coverage)

CORRECTION (2026-11-08): an earlier version of this doc claimed a
raw `.aep` binary cannot be imported at all. That was wrong. The
official EffectCraft project (storytold/effectcraft) imports
`.aep` / `.aepx` files partially since v0.21.1 (Nov 5, 2026), and
open-source parsers (py_aep / aep-parser) have reverse-engineered
the RIFX format. This app now imports raw `.aep` / `.aepx` directly
with a port of the official importer (see Attribution below).

Picking a `.aep` / `.aepx` runs a dry run first and shows the loss
report: what is kept, what is lost, and which media files are
missing. The import proceeds only when the user chooses "Import
with losses". A file with no losses imports straight away. The
original file is never modified.

### What v1 imports

- The first composition: size, frame rate, duration, work area,
  background, pixel aspect, display start, shutter, renderer.
- AV, null, text (as an empty text layer) and shape (as an empty
  shape layer) layers, with parent links, stretch, blend mode and
  the layer switches (video, audio, solo, locked, shy, collapse,
  quality, effects, motion blur, adjustment, 3D, guide).
- Footage (still / video / audio) and solids, with the stored file
  path. A missing media file does not fail the import; that footage
  is marked missing.
- Transform: anchor point, position, scale, rotation (Z), opacity,
  X/Y rotation, and the static orientation value — with keyframes
  (linear, bezier with eases, hold, spatial tangents, roving,
  labels) and expressions kept as source text.
- The project version (file / saved-by) and a loss report for
  everything else.

### What v1 loses (always reported, never silent)

- Effects (including Levels, Glow and every other effect).
- Layer styles (only when a style has values set).
- Text contents, shape contents, cameras, lights, precompositions,
  track mattes, masks, markers, time remapping.
- Keyframed orientation and orientation expressions.
- Compositions after the first, and folders (their contents still
  import).
- Project settings (working space, colour depth, time display).
- `.aep` export, expression evaluation, Lumetri and third-party
  plug-ins: not in v1.

Tested with After Effects 24.x, 25.x and 26.x files. The fixture
`synthetic.aep` (one comp, an image sequence, a solid, a folder,
Levels + Glow) imports with exactly 5 losses: 2 effects, 2 folders,
1 project-settings note.

### Attribution

The `.aep` / `.aepx` reader and importer
(`app/src/main/java/com/codeair/effectcraft/data/aep/`) are a port
of the official EffectCraft project (github.com/storytold/effectcraft,
MIT OR Apache-2.0), crates `aep` and `keyframe` at revision e71dd62,
with the fixture `synthetic.aep` from its test corpus (see
`app/src/test/resources/aep/synthetic.aep.attribution`). No Adobe
code, artwork or file-format documentation was used.

## Exporting from After Effects (exact steps)

1. Open your project in After Effects.
2. Select the composition to export (or leave it active — the
   script uses the active comp, otherwise the first comp).
3. File > Scripts > Run Script File...
4. Choose `docs/ae-export/ExportToEffectCraft.jsx` from this repo.
5. The script writes a folder next to your `.aep` named
   `<Project>-effectcraft` containing `project.json`, a `media/`
   folder with the footage files, and a `README.txt`.
6. Zip that whole folder.
7. In EffectCraft: Import project > After Effects export (.zip) >
   choose the `.zip`.

## What the export carries

- Compositions: size, frame rate, duration, background colour.
- Text layers: text, font, size, colour, and transform keyframes.
- Solid layers: colour and size.
- Footage layers: video / still / audio, with the media file copied
  into the bundle and relinked on import. A missing media file does
  not fail the import; that footage is marked missing.
- Shape layers: first rectangle + fill (best effort).
- Null layers, parent links, and keyframes for position, anchor
  point, scale, rotation, opacity.

Not exported: effects, masks, cameras, lights, 3D layers,
expressions. Anything skipped is listed in `project.json`
`"notes"` (and in the bundle's `README.txt`) so nothing is
silently lost.

## The interchange format (version 1)

`project.json` is plain JSON:

```json
{
  "format": "effectcraft-ae-interchange",
  "version": 1,
  "projectName": "My Project",
  "comps": [{
    "name": "Main", "width": 1920, "height": 1080,
    "frameRate": 30, "duration": 10.0,
    "background": [0, 0, 0],
    "layers": [
      {"name": "Title", "type": "text", "text": "Hello",
       "font": "Arial", "fontSize": 72, "color": [1, 1, 1, 1],
       "position": [960, 540],
       "keyframes": {"position": [
         {"time": 0, "value": [960, 540]},
         {"time": 2, "value": [960, 300]}]}},
      {"name": "BG", "type": "solid", "color": [0.1, 0.2, 0.8],
       "width": 1920, "height": 1080},
      {"name": "Clip.mp4", "type": "footage", "file": "media/Clip.mp4",
       "width": 1280, "height": 720, "duration": 8.0, "kind": "video"}
    ]
  }]
}
```

Layer types: `text`, `solid`, `footage`, `shape`, `null`.
Times are seconds; colours are 0..1 RGBA (solids RGB). Footage
`file` paths are relative to the bundle root (`media/...`).
`kind` is `video`, `still` or `audio` (defaulted from the file
extension when absent). Keyframe properties: `position`,
`anchorPoint`, `scale`, `rotation`, `opacity`. Unknown layer types
are skipped with a warning, never a crash; a document with no
compositions, bad dimensions or a bad frame rate is rejected with
a precise error.

## How import is verified

The converter output is a canonical schema-2 `.ecproj` document.
The app runs it through the native inspector
(`NativeProjectBridge.inspectEcproj`) before keeping anything —
the same check as a plain `.ecproj` import — so a bad conversion
fails loudly at import time instead of corrupting the project.
