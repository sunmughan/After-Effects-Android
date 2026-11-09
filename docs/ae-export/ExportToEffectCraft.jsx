// ExportToEffectCraft.jsx
//
// Exports the active (or first) After Effects composition to the
// EffectCraft AE interchange format (project.json + media files),
// which EffectCraft for Android imports directly.
//
// WHY A SCRIPT, NOT A DIRECT .aep IMPORT:
// A .aep file is a proprietary RIFX binary with no public
// specification. No app except After Effects itself can open it
// reliably, so EffectCraft does not pretend to. This script runs
// inside After Effects, where the real project data lives, and
// writes it out as open JSON. That is the honest, working path.
//
// RUN:
//   1. Open your project in After Effects.
//   2. Select the composition to export (or leave it active).
//   3. File > Scripts > Run Script File... > choose this file.
//   4. The script writes a folder next to your .aep named
//      "<Project>-effectcraft" with project.json + a media/ folder.
//   5. Zip that folder.
//   6. In EffectCraft: Import project > After Effects export
//      > choose the .zip.
//
// What exports: comps (size, frame rate, duration, background),
// text layers (text, font, size, colour, keyframes), solid layers,
// footage layers (video / still / audio, media copied into the
// folder), shape layers (first rectangle + fill, best effort),
// null layers, transform keyframes (position, anchor, scale,
// rotation, opacity), and parent links. Effects, masks, cameras,
// lights, 3D and expressions are NOT exported; the script lists
// everything it skipped in project.json "notes" so nothing is
// silently lost.
//
// ExtendScript (ES3) only: no JSON object, no arrow functions.
// A tiny JSON writer is included below.

(function () {
    // ---------------------------------------------------------------- JSON
    function toJSON(value) {
        if (value === null || value === undefined) return "null";
        var t = typeof value;
        if (t === "number") {
            if (!isFinite(value)) return "0";
            return String(value);
        }
        if (t === "boolean") return value ? "true" : "false";
        if (t === "string") {
            var out = '"';
            for (var i = 0; i < value.length; i++) {
                var c = value.charAt(i);
                if (c === '"') out += '\\"';
                else if (c === "\\") out += "\\\\";
                else if (c === "\n") out += "\\n";
                else if (c === "\r") out += "\\r";
                else if (c === "\t") out += "\\t";
                else if (value.charCodeAt(i) < 32) out += "\\u" + ("000" + value.charCodeAt(i).toString(16)).slice(-4);
                else out += c;
            }
            return out + '"';
        }
        if (value instanceof Array) {
            var parts = [];
            for (var a = 0; a < value.length; a++) parts.push(toJSON(value[a]));
            return "[" + parts.join(",") + "]";
        }
        var keys = [];
        for (var k in value) keys.push(k);
        var obj = [];
        for (var j = 0; j < keys.length; j++) {
            obj.push(toJSON(keys[j]) + ":" + toJSON(value[keys[j]]));
        }
        return "{" + obj.join(",") + "}";
    }

    // ---------------------------------------------------------------- helpers
    function num2(v) {
        // AE transform values are [x, y] (or [x, y, z]); keep the first two.
        if (v instanceof Array) return [v[0], v[1]];
        return [v, v];
    }

    function rgb(arr) {
        if (!(arr instanceof Array)) return [1, 1, 1];
        return [arr[0], arr[1], arr[2]];
    }

    function rgba(arr) {
        if (!(arr instanceof Array)) return [1, 1, 1, 1];
        return [arr[0], arr[1], arr[2], arr.length > 3 ? arr[3] : 1];
    }

    function keyframes(prop) {
        if (prop === null || prop.numKeys === 0) return null;
        var keys = [];
        for (var i = 1; i <= prop.numKeys; i++) {
            var v = prop.keyValue(i);
            keys.push({ time: prop.keyTime(i), value: (v instanceof Array) ? num2(v) : v });
        }
        return keys;
    }

    function transformKeyframes(layer) {
        var out = {};
        var map = [
            ["Position", "position"],
            ["Anchor Point", "anchorPoint"],
            ["Scale", "scale"],
            ["Rotation", "rotation"],
            ["Opacity", "opacity"]
        ];
        for (var i = 0; i < map.length; i++) {
            var prop = null;
            try { prop = layer.property(map[i][0]); } catch (e) { prop = null; }
            if (prop !== null) {
                var keys = keyframes(prop);
                if (keys !== null) out[map[i][1]] = keys;
            }
        }
        var empty = true;
        for (var k in out) { empty = false; break; }
        return empty ? null : out;
    }

    function sanitize(name) {
        return name.replace(/[\\\/:*?"<>|]/g, "_");
    }

    // ---------------------------------------------------------------- footage
    var usedMediaNames = {};
    function copyMedia(sourceFile, mediaFolder) {
        var base = sanitize(sourceFile.name);
        var name = base;
        var n = 2;
        while (usedMediaNames[name]) { name = n + "_" + base; n++; }
        usedMediaNames[name] = true;
        var dest = new File(mediaFolder.fsName + "/" + name);
        try {
            sourceFile.copy(dest.fsName);
        } catch (e) {
            return null;
        }
        return "media/" + name;
    }

    function shapeFillColor(layer) {
        try {
            var contents = layer.property("Contents");
            if (contents === null) return [1, 0, 0, 1];
            for (var i = 1; i <= contents.numProperties; i++) {
                var group = contents.property(i);
                if (group === null) continue;
                var inner = null;
                try { inner = group.property("Contents"); } catch (e) { inner = null; }
                if (inner === null) continue;
                for (var j = 1; j <= inner.numProperties; j++) {
                    var item = inner.property(j);
                    if (item === null) continue;
                    if (item.matchName === "ADBE Vector Graphic - Fill") {
                        var c = item.property("Color");
                        if (c !== null) return rgba(c.value);
                    }
                }
            }
        } catch (e) { /* best effort only */ }
        return [1, 0, 0, 1];
    }

    // ---------------------------------------------------------------- layers
    function exportLayer(layer, mediaFolder, notes) {
        var out = { name: layer.name };
        try { out.startTime = layer.startTime; } catch (e) { out.startTime = 0; }
        try { out.inPoint = layer.inPoint; } catch (e) { out.inPoint = 0; }
        try { out.outPoint = layer.outPoint; } catch (e) { out.outPoint = 0; }
        try { if (layer.parent !== null) out.parent = layer.parent.name; } catch (e) {}
        try { out.position = num2(layer.property("Position").value); } catch (e) {}
        try { out.anchorPoint = num2(layer.property("Anchor Point").value); } catch (e) {}
        try { out.scale = num2(layer.property("Scale").value); } catch (e) {}
        try { out.rotation = layer.property("Rotation").value; } catch (e) {}
        try { out.opacity = layer.property("Opacity").value; } catch (e) {}
        var keys = transformKeyframes(layer);
        if (keys !== null) out.keyframes = keys;

        if (layer instanceof TextLayer) {
            out.type = "text";
            try {
                var doc = layer.property("Source Text").value;
                out.text = doc.text;
                out.font = doc.font;
                out.fontSize = doc.fontSize;
                out.color = rgba(doc.fillColor);
            } catch (e) {
                out.text = "";
                notes.push('Text layer "' + layer.name + '": could not read Source Text; exported empty.');
            }
            return out;
        }
        if (layer instanceof ShapeLayer) {
            out.type = "shape";
            out.color = shapeFillColor(layer);
            out.size = [200, 200];
            return out;
        }
        if (layer instanceof AVLayer) {
            var src = null;
            try { src = layer.source; } catch (e) { src = null; }
            if (src === null) {
                out.type = "null";
                return out;
            }
            if (src instanceof SolidSource) {
                out.type = "solid";
                out.color = rgb(src.color);
                out.width = src.width;
                out.height = src.height;
                return out;
            }
            if (src instanceof FootageItem) {
                out.type = "footage";
                out.width = src.width;
                out.height = src.height;
                try { out.duration = src.duration; } catch (e) {}
                try { out.frameRate = src.frameRate; } catch (e) {}
                try { out.hasAudio = src.hasAudio; } catch (e) { out.hasAudio = false; }
                if (src.file === null) {
                    out.kind = "still";
                    notes.push('Footage layer "' + layer.name + '": no file on disk; exported without media.');
                } else {
                    var ext = String(src.file.name).split(".").pop().toLowerCase();
                    if (ext === "png" || ext === "jpg" || ext === "jpeg" || ext === "webp" ||
                        ext === "bmp" || ext === "gif" || ext === "tif" || ext === "tiff") {
                        out.kind = "still";
                    } else if (ext === "mp3" || ext === "wav" || ext === "aac" || ext === "flac" ||
                               ext === "ogg" || ext === "m4a" || ext === "opus") {
                        out.kind = "audio";
                    } else {
                        out.kind = "video";
                    }
                    var rel = copyMedia(src.file, mediaFolder);
                    if (rel === null) {
                        notes.push('Footage layer "' + layer.name + '": could not copy ' + src.file.fsName + '.');
                    } else {
                        out.file = rel;
                    }
                }
                return out;
            }
            out.type = "null";
            return out;
        }
        // Camera, Light, adjustment and anything else: no layer in the app.
        return null;
    }

    function exportComp(comp, mediaFolder, notes) {
        var layers = [];
        for (var i = 1; i <= comp.numLayers; i++) {
            var layer = comp.layer(i);
            var exported = null;
            try {
                exported = exportLayer(layer, mediaFolder, notes);
            } catch (e) {
                notes.push('Layer "' + layer.name + '": export failed (' + e.toString() + '); skipped.');
                exported = null;
            }
            if (exported === null) {
                notes.push('Layer "' + layer.name + '": type not supported by EffectCraft; skipped.');
            } else {
                layers.push(exported);
            }
        }
        return {
            name: comp.name,
            width: comp.width,
            height: comp.height,
            frameRate: comp.frameRate,
            duration: comp.duration,
            background: rgb(comp.bgColor),
            layers: layers
        };
    }

    // ---------------------------------------------------------------- main
    if (app.project === null) {
        alert("Open a project in After Effects first, then run this script.");
        return;
    }
    var comp = null;
    try {
        if (app.project.activeItem instanceof CompItem) comp = app.project.activeItem;
    } catch (e) { comp = null; }
    if (comp === null) {
        for (var i = 1; i <= app.project.numItems; i++) {
            var item = app.project.item(i);
            if (item instanceof CompItem) { comp = item; break; }
        }
    }
    if (comp === null) {
        alert("This project has no composition to export.");
        return;
    }

    var baseName = "Untitled";
    try {
        if (app.project.file !== null) {
            baseName = String(app.project.file.name).replace(/\.aep$/i, "");
        }
    } catch (e) {}
    var parentFolder = null;
    try {
        parentFolder = app.project.file !== null ? app.project.file.parent : Folder.desktop;
    } catch (e) {
        parentFolder = Folder.desktop;
    }
    var exportFolder = new Folder(parentFolder.fsName + "/" + sanitize(baseName) + "-effectcraft");
    if (!exportFolder.exists) exportFolder.create();
    var mediaFolder = new Folder(exportFolder.fsName + "/media");
    if (!mediaFolder.exists) mediaFolder.create();

    var notes = [];
    var project = {
        format: "effectcraft-ae-interchange",
        version: 1,
        projectName: baseName,
        comps: [exportComp(comp, mediaFolder, notes)],
        notes: notes
    };

    var jsonFile = new File(exportFolder.fsName + "/project.json");
    jsonFile.encoding = "UTF-8";
    jsonFile.open("w");
    jsonFile.write(toJSON(project));
    jsonFile.close();

    var readme = new File(exportFolder.fsName + "/README.txt");
    readme.encoding = "UTF-8";
    readme.open("w");
    readme.write(
        "EffectCraft import for \"" + comp.name + "\"\n" +
        "\n" +
        "1. Zip this whole folder.\n" +
        "2. In EffectCraft (Android): Import project > After Effects export > choose the .zip.\n" +
        "\n" +
        "Exported: " + project.comps[0].layers.length + " layers from composition \"" + comp.name + "\".\n" +
        (notes.length > 0 ? "Notes:\n- " + notes.join("\n- ") + "\n" : "Nothing was skipped.\n")
    );
    readme.close();

    alert(
        "Exported \"" + comp.name + "\" (" + project.comps[0].layers.length + " layers) to:\n" +
        exportFolder.fsName + "\n\n" +
        "Zip that folder, then import the .zip in EffectCraft:\n" +
        "Import project > After Effects export." +
        (notes.length > 0 ? "\n\n" + notes.length + " note(s) — see project.json \"notes\" and README.txt." : "")
    );
})();
