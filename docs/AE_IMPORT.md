# Importing projects into EffectCraft

## What each import type accepts

The **Import project** button (header, and File > Import project)
offers three options. Each one says honestly what it takes:

| Option | Accepts | Notes |
|---|---|---|
| EffectCraft project (.ecproj / .ecprojx) | A project saved by EffectCraft (JSON `.ecproj` or XML `.ecprojx`) | The native inspector validates it before anything is kept. |
| Project bundle (.zip) | A `.zip` holding `project.json` plus its media files | `project.json` is either an `.ecproj` document or an After Effects export (below). Media is extracted and relinked. |
| After Effects export (.zip) | A `.zip` produced by the included ExtendScript (below) | The real path for genuine After Effects projects. |

## Why there is no direct .aep import

A `.aep` file is a proprietary RIFX binary. Adobe publishes no
specification for it, and no app except After Effects itself can open
it reliably. EffectCraft does not pretend otherwise: picking a `.aep`
explains this and points at the script export instead of failing
with a parse error.

Research note (2026-11-08): reverse-engineered `.aep` readers do
exist (py_aep / aep-parser, MIT, and a Go port). They are Python /
Go libraries built on a reverse-engineered spec, not a supported
format, and porting one into this app is a separate project with its
own sample-corpus validation. It was evaluated and deliberately NOT
attempted in this batch: the script export is Adobe's own supported
route and works end to end today.

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
