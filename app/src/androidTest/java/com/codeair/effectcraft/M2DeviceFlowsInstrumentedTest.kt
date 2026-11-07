package com.codeair.effectcraft

import com.codeair.effectcraft.data.CanonicalProjectDocument
import com.codeair.effectcraft.data.CanonicalProjectInfo
import com.codeair.effectcraft.data.CanonicalProjectRepository
import com.codeair.effectcraft.data.OfflineAssetStore
import com.codeair.effectcraft.data.ProjectAssetManifestStore
import com.codeair.effectcraft.data.ProjectFileFormats
import com.codeair.effectcraft.data.SafDocumentWriter
import com.codeair.effectcraft.engine.NativeProjectBridge
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * M2 physical-device flow tests: SAF-open → edit → export, SAF copy →
 * native relink → export, and canonical import → add/rename/visibility/
 * reorder/delete → export.
 *
 * Each flow runs its storage legs (SAF-stream read, repository
 * create/update/load, offline-store copy, manifest link, staged export
 * bytes) on every ABI. The legs that call the native project model are
 * ARM64-gated: on a non-ARM64 host (including the x86_64 CI emulator,
 * whose ARM64 .so cannot load there) they skip cleanly via [Assume]
 * instead of failing, and they run for real on a physical ARM64 device.
 * Nothing here fakes device proof: a skipped JNI leg reports as skipped,
 * and the storage legs that did run still assert their own results.
 *
 * The fixture is a real canonical project produced by the pinned upstream
 * model (one 640x360 composition, one missing footage item with a footage
 * layer, two text layers). It is embedded as chunked JSON so the test needs
 * no asset file and no picker UI; the SAF legs drive the same stream,
 * repository and export routines the picker/export launchers use.
 */
class M2DeviceFlowsInstrumentedTest {

    companion object {
        // Canonical project produced by the pinned upstream model
        // (effectcraft rev e71dd629c56c78a82959ee50c271da9209c55b80): one
        // 640x360 composition, one missing footage item with a footage
        // layer, two text layers. Split into parts because a single
        // string constant over 64 KiB cannot live in a class file;
        // the parts join back to the exact pinned-model dump.
        private val FIXTURE_PART_0 = """{
  "savedBy": "0.19.0",
  "schema": 2,
  "settings": {
    "bit_depth": "Bpc8",
    "linearize": false,
    "working_space": null,
    "blend_linear": false,
    "time_display": "Timecode",
    "frame_start": 0,
    "audio_sample_rate": 48000,
    "gpu_acceleration": true,
    "color_engine": "Adobe",
    "hdr": "Clip",
    "output_space": null,
    "working_gamma": 2.2
  },
  "items": {
    "1": {
      "id": 1,
      "name": "Main",
      "label": "Sandstone",
      "comment": "",
      "parent": null,
      "kind": {
        "type": "Comp",
        "width": 640,
        "height": 360,
        "pixel_aspect": 1.0,
        "frame_rate": {
          "num": 30,
          "den": 1
        },
        "duration": 762048000000,
        "display_start": 0,
        "background": [
          0.0,
          0.0,
          0.0
        ],
        "work_area": [
          0,
          762048000000
        ],
        "layers": [
          {
            "id": 3,
            "name": "Clip",
            "source": {
              "type": "Footage",
              "item": 2
            },
            "label": "Sandstone",
            "comment": "",
            "start_time": 0,
            "in_point": 0,
            "out_point": 762048000000,
            "stretch": 100.0,
            "switches": {
              "video": true,
              "audio": true,
              "solo": false,
              "locked": false,
              "shy": false,
              "collapse": false,
              "quality": "Best",
              "sampling": "Bilinear",
              "effects": true,
              "frame_blend": "Off",
              "motion_blur": false,
              "adjustment": false,
              "three_d": false,
              "guide": false
            },
            "blend_mode": "Normal",
            "preserve_transparency": false,
            "track_matte": null,
            "parent": null,
            "markers": [],
            "auto_orient": "Off",
            "props": {
              "uid": 4,
              "match": "layer",
              "name": "Clip",
              "kind": {
                "kind": "Plain"
              },
              "enabled": true,
              "children": [
                {
                  "node": "Group",
                  "uid": 5,
                  "match": "masks",
                  "name": "Masks",
                  "kind": {
                    "kind": "Plain"
                  },
                  "enabled": true,
                  "children": []
                },
                {
                  "node": "Group",
                  "uid": 6,
                  "match": "effects",
                  "name": "Effects",
                  "kind": {
                    "kind": "Plain"
                  },
                  "enabled": true,
                  "children": []
                },
                {
                  "node": "Group",
                  "uid": 15,
                  "match": "transform",
                  "name": "Transform",
                  "kind": {
                    "kind": "Plain"
                  },
                  "enabled": true,
                  "children": [
                    {
                      "node": "Prop",
                      "uid": 7,
                      "match": "anchor",
                      "name": "Anchor Point",
                      "value": {
                        "t": "Vec3",
                        "v": [
                          320.0,
                          180.0,
                          0.0
                        ]
                      },
                      "ui": {
                        "kind": "Point"
                      },
                      "spatial": true,
                      "hold_only": false,
                      "three_d_only": false,
                      "two_d_only": false,
                      "shown_dims": 2,
                      "static_only": false
                    },
                    {
                      "node": "Prop",
                      "uid": 8,
                      "match": "position",
                      "name": "Position",
                      "value": {
                        "t": "Vec3",
                        "v": [
                          320.0,
                          180.0,
                          0.0
                        ]
                      },
                      "ui": {
                        "kind": "Point"
                      },
                      "spatial": true,
                      "hold_only": false,
                      "three_d_only": false,
                      "two_d_only": false,
                      "shown_dims": 2,
                      "static_only": false
                    },
                    {
                      "node": "Prop",
                      "uid": 9,
                      "match": "scale",
                      "name": "Scale",
                      "value": {
                        "t": "Vec3",
                        "v": [
                          100.0,
                          100.0,
                          100.0
                        ]
                      },
                      "ui": {
                        "kind": "Percent"
                      },
                      "spatial": false,
                      "hold_only": false,
                      "three_d_only": false,
                      "two_d_only": false,
                      "shown_dims": 2,
                      "static_only": false
                    },
                    {
                      "node": "Prop",
                      "uid": 10,
                      "match": "orientation",
                      "name": "Orientation",
                      "value": {
                        "t": "Vec3",
                        "v": [
                          0.0,
                          0.0,
                          0.0
                        ]
                      },
                      "ui": {
                        "kind": "Angle"
                      },
                      "spatial": false,
                      "hold_only": false,
                      "three_d_only": true,
                      "two_d_only": false,
                      "shown_dims": 0,
                      "static_only": false
                    },
                    {
                      "node": "Prop",
                      "uid": 11,
                      "match": "rotationX",
                      "name": "X Rotation",
                      "value": {
                        "t": "Scalar",
                        "v": 0.0
                      },
                      "ui": {
                        "kind": "Angle"
                      },
                      "spatial": false,
                      "hold_only": false,
                      "three_d_only": true,
                      "two_d_only": false,
                      "shown_dims": 0,
                      "static_only": false
                    },
                    {
                      "node": "Prop",
                      "uid": 12,
                      "match": "rotationY",
                      "name": "Y Rotation",
                      "value": {
                        "t": "Scalar",
                        "v": 0.0
                      },
                      "ui": {
                        "kind": "Angle"
                      },
                      "spatial": false,
                      "hold_only": false,
                      "three_d_only": true,
                      "two_d_only": false,
                      "shown_dims": 0,
                      "static_only": false
                    },
                    {
                      "node": "Prop",
                      "uid": 13,
                      "match": "rotation",
                      "name": "Rotation",
                      "value": {
                        "t": "Scalar",
                        "v": 0.0
                      },
                      "ui": {
                        "kind": "Angle"
                      },
                      "spatial": false,
                      "hold_only": false,
                      "three_d_only": false,
                      "two_d_only": false,
                      "shown_dims": 0,
                      "static_only": false
                    },
                    {
                      "node": "Prop",
                      "uid": 14,
                      "match": "opacity",
                      "name": "Opacity",
                      "value": {
                        "t": "Scalar",
                        "v": 100.0
                      },
                      "ui": {
                        "kind": "Slider",
                        "min": 0.0,
                        "max": 100.0,
                        "slider_min": 0.0,
                        "slider_max": 100.0,
                        "decimals": 0
                      },
                      "spatial": false,
                      "hold_only": false,
                      "three_d_only": false,
                      "two_d_only": false,
                      "shown_dims": 0,
                      "static_only": false
                    }
                  ]
                },
                {
                  "node": "Group",
                  "uid": 16,
                  "match": "materialOptions",
                  "name": "Material Options",
                  "kind": {
                    "kind": "Plain"
                  },
                  "enabled": true,
                  "children": [
                    {
                      "node": "Prop",
                      "uid": 17,
                      "match": "castsShadows",
                      "name": "Casts Shadows",
                      "value": {
                        "t": "Enum",
                        "v": 0
                      },
                      "ui": {
                        "kind": "Popup",
                        "options": [
                          "Off",
                          "On",
                          "Only"
                        ]
                      },
                      "spatial": false,
                      "hold_only": true,
                      "three_d_only": false,
                      "two_d_only": false,
                      "shown_dims": 0,
                      "static_only": false
                    },
                    {
                      "node": "Prop",
                      "uid": 18,
                      "match": "lightTransmission",
                      "name": "Light Transmission",
                      "value": {
                        "t": "Scalar",
                        "v": 0.0
                      },
                      "ui": {
                        "kind": "Percent"
                      },
                      "spatial": false,
                      "hold_only": false,
                      "three_d_only": false,
                      "two_d_only": false,
                      "shown_dims": 0,
                      "static_only": false
                    },
                    {
                      "node": "Prop",
                      "uid": 19,
                      "match": "acceptsShadows",
                      "name": "Accepts Shadows",
                      "value": {
                        "t": "Enum",
                        "v": 1
                      },
                      "ui": {
                        "kind": "Popup",
                        "options": [
                          "Off",
                          "On",
                          "Only"
                        ]
                      },
                      "spatial": false,
                      "hold_only": true,
                      "three_d_only": false,
                      "two_d_only": false,
                      "shown_dims": 0,
                      "static_only": false
                    },
                    {
                      "node": "Prop",
                      "uid": 20,
                      "match": "acceptsLights",
                      "name": "Accepts Lights",
                      "value": {
                        "t": "Bool",
                        "v": true
                      },
                      "ui": {
                        "kind": "Checkbox"
                      },
                      "spatial": false,
                      "hold_only": true,
                      "three_d_only": false,
                      "two_d_only": false,
                      "shown_dims": 0,
                      "static_only": false
                    },
                    {
                      "node": "Prop",
                      "uid": 21,
                      "match": "ambient",
                      "name": "Ambient",
                      "value": {
                        "t": "Scalar",
                        "v": 100.0
                      },
                      "ui": {
                        "kind": "Percent"
                      },
                      "spatial": false,
                      "hold_only": false,
                      "three_d_only": false,
                      "two_d_only": false,
                      "shown_dims": 0,
                      "static_only": false
                    },
                    {
                      "node": "Prop",
                      "uid": 22,
                      "match": "diffuse",
                      "name": "Diffuse",
                      "value": {
                        "t": "Scalar",
                        "v": 50.0
                      },
                      "ui": {
                        "kind": "Percent"
                      },
                      "spatial": false,
                      "hold_only": false,
                      "three_d_only": false,
                      "two_d_only": false,
                      "shown_dims": 0,
                      "static_only": false
                    },
                    {
                      "node": "Prop",
                      "uid": 23,
                      "match": "specularIntensity",
                      "name": "Specular Intensity",
                      "value": {
                        "t": "Scalar",
                        "v": 50.0
                      },
                      "ui": {
                        "kind": "Percent"
                      },
                      "spatial": false,
                      "hold_only": false,
                      "three_d_only": false,
                      "two_d_only": false,
                      "shown_dims": 0,
                      "static_only": false
                    },
                    {
                      "node": "Prop",
                      "uid": 24,
                      "match": "specularShininess",
                      "name": "Specular Shininess",
"""
        private val FIXTURE_PART_1 = """                      "value": {
                        "t": "Scalar",
                        "v": 5.0
                      },
                      "ui": {
                        "kind": "Percent"
                      },
                      "spatial": false,
                      "hold_only": false,
                      "three_d_only": false,
                      "two_d_only": false,
                      "shown_dims": 0,
                      "static_only": false
                    },
                    {
                      "node": "Prop",
                      "uid": 25,
                      "match": "metal",
                      "name": "Metal",
                      "value": {
                        "t": "Scalar",
                        "v": 100.0
                      },
                      "ui": {
                        "kind": "Percent"
                      },
                      "spatial": false,
                      "hold_only": false,
                      "three_d_only": false,
                      "two_d_only": false,
                      "shown_dims": 0,
                      "static_only": false
                    }
                  ]
                }
              ]
            }
          },
          {
            "id": 26,
            "name": "Title",
            "source": {
              "type": "Text"
            },
            "label": "Red",
            "comment": "",
            "start_time": 0,
            "in_point": 0,
            "out_point": 762048000000,
            "stretch": 100.0,
            "switches": {
              "video": true,
              "audio": true,
              "solo": false,
              "locked": false,
              "shy": false,
              "collapse": false,
              "quality": "Best",
              "sampling": "Bilinear",
              "effects": true,
              "frame_blend": "Off",
              "motion_blur": false,
              "adjustment": false,
              "three_d": false,
              "guide": false
            },
            "blend_mode": "Normal",
            "preserve_transparency": false,
            "track_matte": null,
            "parent": null,
            "markers": [],
            "auto_orient": "Off",
            "props": {
              "uid": 27,
              "match": "layer",
              "name": "Title",
              "kind": {
                "kind": "Plain"
              },
              "enabled": true,
              "children": [
                {
                  "node": "Group",
                  "uid": 42,
                  "match": "text",
                  "name": "Text",
                  "kind": {
                    "kind": "Plain"
                  },
                  "enabled": true,
                  "children": [
                    {
                      "node": "Prop",
                      "uid": 28,
                      "match": "sourceText",
                      "name": "Source Text",
                      "value": {
                        "t": "Text",
                        "v": {
                          "text": "",
                          "font": "Inter",
                          "style": "Regular",
                          "size": 72.0,
                          "fill": [
                            1.0,
                            1.0,
                            1.0,
                            1.0
                          ],
                          "stroke": [
                            0.0,
                            0.0,
                            0.0,
                            1.0
                          ],
                          "stroke_width": 0.0,
                          "apply_fill": true,
                          "apply_stroke": false,
                          "stroke_over_fill": true,
                          "line_join": "Miter",
                          "tracking": 0.0,
                          "leading": null,
                          "justify": "Left",
                          "baseline_shift": 0.0,
                          "h_scale": 100.0,
                          "v_scale": 100.0,
                          "faux_bold": false,
                          "faux_italic": false,
                          "all_caps": false,
                          "small_caps": false,
                          "box_size": null,
                          "box_pos": [
                            0.0,
                            0.0
                          ],
                          "tsume": 0.0,
                          "baseline": "Normal",
                          "kerning": "Metrics",
                          "ligatures": true,
                          "tate_chu_yoko": false,
                          "vertical_roman_upright": false,
                          "indent_left": 0.0,
                          "indent_right": 0.0,
                          "indent_first": 0.0,
                          "space_before": 0.0,
                          "space_after": 0.0,
                          "direction": "Ltr",
                          "composer": "EveryLine",
                          "hanging_punctuation": false,
                          "vertical": false
                        }
                      },
                      "ui": {
                        "kind": "Text"
                      },
                      "spatial": false,
                      "hold_only": true,
                      "three_d_only": false,
                      "two_d_only": false,
                      "shown_dims": 0,
                      "static_only": false
                    },
                    {
                      "node": "Prop",
                      "uid": 29,
                      "match": "perChar3d",
                      "name": "Per-character 3D",
                      "value": {
                        "t": "Bool",
                        "v": false
                      },
                      "ui": {
                        "kind": "Hidden"
                      },
                      "spatial": false,
                      "hold_only": true,
                      "three_d_only": false,
                      "two_d_only": false,
                      "shown_dims": 0,
                      "static_only": true
                    },
                    {
                      "node": "Group",
                      "uid": 31,
                      "match": "pathOptions",
                      "name": "Path Options",
                      "kind": {
                        "kind": "Plain"
                      },
                      "enabled": true,
                      "children": [
                        {
                          "node": "Prop",
                          "uid": 30,
                          "match": "path",
                          "name": "Path",
                          "value": {
                            "t": "Enum",
                            "v": 0
                          },
                          "ui": {
                            "kind": "Popup",
                            "options": [
                              "None"
                            ]
                          },
                          "spatial": false,
                          "hold_only": true,
                          "three_d_only": false,
                          "two_d_only": false,
                          "shown_dims": 0,
                          "static_only": true
                        },
                        {
                          "node": "Prop",
                          "uid": 32,
                          "match": "reversePath",
                          "name": "Reverse Path",
                          "value": {
                            "t": "Bool",
                            "v": false
                          },
                          "ui": {
                            "kind": "Checkbox"
                          },
                          "spatial": false,
                          "hold_only": true,
                          "three_d_only": false,
                          "two_d_only": false,
                          "shown_dims": 0,
                          "static_only": false
                        },
                        {
                          "node": "Prop",
                          "uid": 33,
                          "match": "perpendicular",
                          "name": "Perpendicular To Path",
                          "value": {
                            "t": "Bool",
                            "v": true
                          },
                          "ui": {
                            "kind": "Checkbox"
                          },
                          "spatial": false,
                          "hold_only": true,
                          "three_d_only": false,
                          "two_d_only": false,
                          "shown_dims": 0,
                          "static_only": false
                        },
                        {
                          "node": "Prop",
                          "uid": 34,
                          "match": "forceAlignment",
                          "name": "Force Alignment",
                          "value": {
                            "t": "Bool",
                            "v": false
                          },
                          "ui": {
                            "kind": "Checkbox"
                          },
                          "spatial": false,
                          "hold_only": true,
                          "three_d_only": false,
                          "two_d_only": false,
                          "shown_dims": 0,
                          "static_only": false
                        },
                        {
                          "node": "Prop",
                          "uid": 35,
                          "match": "firstMargin",
                          "name": "First Margin",
                          "value": {
                            "t": "Scalar",
                            "v": 0.0
                          },
                          "ui": {
                            "kind": "Pixels"
                          },
                          "spatial": false,
                          "hold_only": false,
                          "three_d_only": false,
                          "two_d_only": false,
                          "shown_dims": 0,
                          "static_only": false
                        },
                        {
                          "node": "Prop",
                          "uid": 36,
                          "match": "lastMargin",
                          "name": "Last Margin",
                          "value": {
                            "t": "Scalar",
                            "v": 0.0
                          },
                          "ui": {
                            "kind": "Pixels"
                          },
                          "spatial": false,
                          "hold_only": false,
                          "three_d_only": false,
                          "two_d_only": false,
                          "shown_dims": 0,
                          "static_only": false
                        }
                      ]
                    },
                    {
                      "node": "Group",
                      "uid": 37,
                      "match": "moreOptions",
                      "name": "More Options",
                      "kind": {
                        "kind": "Plain"
                      },
                      "enabled": true,
                      "children": [
                        {
                          "node": "Prop",
                          "uid": 38,
                          "match": "anchorGrouping",
                          "name": "Anchor Point Grouping",
                          "value": {
                            "t": "Enum",
                            "v": 0
                          },
                          "ui": {
                            "kind": "Popup",
                            "options": [
                              "Character",
                              "Word",
                              "Line",
                              "All"
                            ]
                          },
                          "spatial": false,
                          "hold_only": true,
                          "three_d_only": false,
                          "two_d_only": false,
                          "shown_dims": 0,
                          "static_only": true
                        },
                        {
                          "node": "Prop",
                          "uid": 39,
                          "match": "groupingAlignment",
                          "name": "Grouping Alignment",
                          "value": {
                            "t": "Vec2",
                            "v": [
                              0.0,
                              0.0
                            ]
                          },
                          "ui": {
                            "kind": "Percent"
                          },
                          "spatial": false,
                          "hold_only": false,
                          "three_d_only": false,
                          "two_d_only": false,
                          "shown_dims": 0,
                          "static_only": false
                        },
                        {
                          "node": "Prop",
                          "uid": 40,
                          "match": "fillStroke",
                          "name": "Fill & Stroke",
                          "value": {
                            "t": "Enum",
                            "v": 0
                          },
                          "ui": {
                            "kind": "Popup",
                            "options": [
                              "Per Character Palette",
                              "All Fills Over All Strokes",
                              "All Strokes Over All Fills"
                            ]
                          },
                          "spatial": false,
                          "hold_only": true,
                          "three_d_only": false,
                          "two_d_only": false,
                          "shown_dims": 0,
                          "static_only": true
                        },
                        {
                          "node": "Prop",
                          "uid": 41,
                          "match": "interCharBlend",
                          "name": "Inter-Character Blending",
                          "value": {
                            "t": "Enum",
                            "v": 0
"""
        private val FIXTURE_PART_2 = """                          },
                          "ui": {
                            "kind": "Popup",
                            "options": [
                              "Normal",
                              "Darken",
                              "Multiply",
                              "Color Burn",
                              "Linear Burn",
                              "Darker Color",
                              "Add",
                              "Lighten",
                              "Screen",
                              "Color Dodge",
                              "Lighter Color",
                              "Overlay",
                              "Soft Light",
                              "Hard Light",
                              "Linear Light",
                              "Vivid Light",
                              "Pin Light",
                              "Hard Mix",
                              "Difference",
                              "Exclusion",
                              "Subtract",
                              "Divide",
                              "Hue",
                              "Saturation",
                              "Color",
                              "Luminosity"
                            ]
                          },
                          "spatial": false,
                          "hold_only": true,
                          "three_d_only": false,
                          "two_d_only": false,
                          "shown_dims": 0,
                          "static_only": true
                        }
                      ]
                    },
                    {
                      "node": "Group",
                      "uid": 43,
                      "match": "animators",
                      "name": "Animators",
                      "kind": {
                        "kind": "Plain"
                      },
                      "enabled": true,
                      "children": []
                    }
                  ]
                },
                {
                  "node": "Group",
                  "uid": 44,
                  "match": "masks",
                  "name": "Masks",
                  "kind": {
                    "kind": "Plain"
                  },
                  "enabled": true,
                  "children": []
                },
                {
                  "node": "Group",
                  "uid": 45,
                  "match": "effects",
                  "name": "Effects",
                  "kind": {
                    "kind": "Plain"
                  },
                  "enabled": true,
                  "children": []
                },
                {
                  "node": "Group",
                  "uid": 54,
                  "match": "transform",
                  "name": "Transform",
                  "kind": {
                    "kind": "Plain"
                  },
                  "enabled": true,
                  "children": [
                    {
                      "node": "Prop",
                      "uid": 46,
                      "match": "anchor",
                      "name": "Anchor Point",
                      "value": {
                        "t": "Vec3",
                        "v": [
                          0.0,
                          0.0,
                          0.0
                        ]
                      },
                      "ui": {
                        "kind": "Point"
                      },
                      "spatial": true,
                      "hold_only": false,
                      "three_d_only": false,
                      "two_d_only": false,
                      "shown_dims": 2,
                      "static_only": false
                    },
                    {
                      "node": "Prop",
                      "uid": 47,
                      "match": "position",
                      "name": "Position",
                      "value": {
                        "t": "Vec3",
                        "v": [
                          320.0,
                          180.0,
                          0.0
                        ]
                      },
                      "ui": {
                        "kind": "Point"
                      },
                      "spatial": true,
                      "hold_only": false,
                      "three_d_only": false,
                      "two_d_only": false,
                      "shown_dims": 2,
                      "static_only": false
                    },
                    {
                      "node": "Prop",
                      "uid": 48,
                      "match": "scale",
                      "name": "Scale",
                      "value": {
                        "t": "Vec3",
                        "v": [
                          100.0,
                          100.0,
                          100.0
                        ]
                      },
                      "ui": {
                        "kind": "Percent"
                      },
                      "spatial": false,
                      "hold_only": false,
                      "three_d_only": false,
                      "two_d_only": false,
                      "shown_dims": 2,
                      "static_only": false
                    },
                    {
                      "node": "Prop",
                      "uid": 49,
                      "match": "orientation",
                      "name": "Orientation",
                      "value": {
                        "t": "Vec3",
                        "v": [
                          0.0,
                          0.0,
                          0.0
                        ]
                      },
                      "ui": {
                        "kind": "Angle"
                      },
                      "spatial": false,
                      "hold_only": false,
                      "three_d_only": true,
                      "two_d_only": false,
                      "shown_dims": 0,
                      "static_only": false
                    },
                    {
                      "node": "Prop",
                      "uid": 50,
                      "match": "rotationX",
                      "name": "X Rotation",
                      "value": {
                        "t": "Scalar",
                        "v": 0.0
                      },
                      "ui": {
                        "kind": "Angle"
                      },
                      "spatial": false,
                      "hold_only": false,
                      "three_d_only": true,
                      "two_d_only": false,
                      "shown_dims": 0,
                      "static_only": false
                    },
                    {
                      "node": "Prop",
                      "uid": 51,
                      "match": "rotationY",
                      "name": "Y Rotation",
                      "value": {
                        "t": "Scalar",
                        "v": 0.0
                      },
                      "ui": {
                        "kind": "Angle"
                      },
                      "spatial": false,
                      "hold_only": false,
                      "three_d_only": true,
                      "two_d_only": false,
                      "shown_dims": 0,
                      "static_only": false
                    },
                    {
                      "node": "Prop",
                      "uid": 52,
                      "match": "rotation",
                      "name": "Rotation",
                      "value": {
                        "t": "Scalar",
                        "v": 0.0
                      },
                      "ui": {
                        "kind": "Angle"
                      },
                      "spatial": false,
                      "hold_only": false,
                      "three_d_only": false,
                      "two_d_only": false,
                      "shown_dims": 0,
                      "static_only": false
                    },
                    {
                      "node": "Prop",
                      "uid": 53,
                      "match": "opacity",
                      "name": "Opacity",
                      "value": {
                        "t": "Scalar",
                        "v": 100.0
                      },
                      "ui": {
                        "kind": "Slider",
                        "min": 0.0,
                        "max": 100.0,
                        "slider_min": 0.0,
                        "slider_max": 100.0,
                        "decimals": 0
                      },
                      "spatial": false,
                      "hold_only": false,
                      "three_d_only": false,
                      "two_d_only": false,
                      "shown_dims": 0,
                      "static_only": false
                    }
                  ]
                },
                {
                  "node": "Group",
                  "uid": 55,
                  "match": "materialOptions",
                  "name": "Material Options",
                  "kind": {
                    "kind": "Plain"
                  },
                  "enabled": true,
                  "children": [
                    {
                      "node": "Prop",
                      "uid": 56,
                      "match": "castsShadows",
                      "name": "Casts Shadows",
                      "value": {
                        "t": "Enum",
                        "v": 0
                      },
                      "ui": {
                        "kind": "Popup",
                        "options": [
                          "Off",
                          "On",
                          "Only"
                        ]
                      },
                      "spatial": false,
                      "hold_only": true,
                      "three_d_only": false,
                      "two_d_only": false,
                      "shown_dims": 0,
                      "static_only": false
                    },
                    {
                      "node": "Prop",
                      "uid": 57,
                      "match": "lightTransmission",
                      "name": "Light Transmission",
                      "value": {
                        "t": "Scalar",
                        "v": 0.0
                      },
                      "ui": {
                        "kind": "Percent"
                      },
                      "spatial": false,
                      "hold_only": false,
                      "three_d_only": false,
                      "two_d_only": false,
                      "shown_dims": 0,
                      "static_only": false
                    },
                    {
                      "node": "Prop",
                      "uid": 58,
                      "match": "acceptsShadows",
                      "name": "Accepts Shadows",
                      "value": {
                        "t": "Enum",
                        "v": 1
                      },
                      "ui": {
                        "kind": "Popup",
                        "options": [
                          "Off",
                          "On",
                          "Only"
                        ]
                      },
                      "spatial": false,
                      "hold_only": true,
                      "three_d_only": false,
                      "two_d_only": false,
                      "shown_dims": 0,
                      "static_only": false
                    },
                    {
                      "node": "Prop",
                      "uid": 59,
                      "match": "acceptsLights",
                      "name": "Accepts Lights",
                      "value": {
                        "t": "Bool",
                        "v": true
                      },
                      "ui": {
                        "kind": "Checkbox"
                      },
                      "spatial": false,
                      "hold_only": true,
                      "three_d_only": false,
                      "two_d_only": false,
                      "shown_dims": 0,
                      "static_only": false
                    },
                    {
                      "node": "Prop",
                      "uid": 60,
                      "match": "ambient",
                      "name": "Ambient",
                      "value": {
                        "t": "Scalar",
                        "v": 100.0
                      },
                      "ui": {
                        "kind": "Percent"
                      },
                      "spatial": false,
                      "hold_only": false,
                      "three_d_only": false,
                      "two_d_only": false,
                      "shown_dims": 0,
                      "static_only": false
                    },
                    {
                      "node": "Prop",
                      "uid": 61,
                      "match": "diffuse",
                      "name": "Diffuse",
                      "value": {
                        "t": "Scalar",
                        "v": 50.0
                      },
                      "ui": {
                        "kind": "Percent"
                      },
                      "spatial": false,
                      "hold_only": false,
                      "three_d_only": false,
                      "two_d_only": false,
                      "shown_dims": 0,
                      "static_only": false
                    },
                    {
                      "node": "Prop",
                      "uid": 62,
                      "match": "specularIntensity",
                      "name": "Specular Intensity",
                      "value": {
                        "t": "Scalar",
                        "v": 50.0
                      },
                      "ui": {
                        "kind": "Percent"
                      },
                      "spatial": false,
                      "hold_only": false,
                      "three_d_only": false,
                      "two_d_only": false,
                      "shown_dims": 0,
                      "static_only": false
                    },
                    {
                      "node": "Prop",
                      "uid": 63,
                      "match": "specularShininess",
                      "name": "Specular Shininess",
                      "value": {
                        "t": "Scalar",
                        "v": 5.0
                      },
                      "ui": {
"""
        private val FIXTURE_PART_3 = """                        "kind": "Percent"
                      },
                      "spatial": false,
                      "hold_only": false,
                      "three_d_only": false,
                      "two_d_only": false,
                      "shown_dims": 0,
                      "static_only": false
                    },
                    {
                      "node": "Prop",
                      "uid": 64,
                      "match": "metal",
                      "name": "Metal",
                      "value": {
                        "t": "Scalar",
                        "v": 100.0
                      },
                      "ui": {
                        "kind": "Percent"
                      },
                      "spatial": false,
                      "hold_only": false,
                      "three_d_only": false,
                      "two_d_only": false,
                      "shown_dims": 0,
                      "static_only": false
                    }
                  ]
                }
              ]
            }
          },
          {
            "id": 65,
            "name": "Subtitle",
            "source": {
              "type": "Text"
            },
            "label": "Red",
            "comment": "",
            "start_time": 0,
            "in_point": 0,
            "out_point": 762048000000,
            "stretch": 100.0,
            "switches": {
              "video": true,
              "audio": true,
              "solo": false,
              "locked": false,
              "shy": false,
              "collapse": false,
              "quality": "Best",
              "sampling": "Bilinear",
              "effects": true,
              "frame_blend": "Off",
              "motion_blur": false,
              "adjustment": false,
              "three_d": false,
              "guide": false
            },
            "blend_mode": "Normal",
            "preserve_transparency": false,
            "track_matte": null,
            "parent": null,
            "markers": [],
            "auto_orient": "Off",
            "props": {
              "uid": 66,
              "match": "layer",
              "name": "Subtitle",
              "kind": {
                "kind": "Plain"
              },
              "enabled": true,
              "children": [
                {
                  "node": "Group",
                  "uid": 81,
                  "match": "text",
                  "name": "Text",
                  "kind": {
                    "kind": "Plain"
                  },
                  "enabled": true,
                  "children": [
                    {
                      "node": "Prop",
                      "uid": 67,
                      "match": "sourceText",
                      "name": "Source Text",
                      "value": {
                        "t": "Text",
                        "v": {
                          "text": "",
                          "font": "Inter",
                          "style": "Regular",
                          "size": 72.0,
                          "fill": [
                            1.0,
                            1.0,
                            1.0,
                            1.0
                          ],
                          "stroke": [
                            0.0,
                            0.0,
                            0.0,
                            1.0
                          ],
                          "stroke_width": 0.0,
                          "apply_fill": true,
                          "apply_stroke": false,
                          "stroke_over_fill": true,
                          "line_join": "Miter",
                          "tracking": 0.0,
                          "leading": null,
                          "justify": "Left",
                          "baseline_shift": 0.0,
                          "h_scale": 100.0,
                          "v_scale": 100.0,
                          "faux_bold": false,
                          "faux_italic": false,
                          "all_caps": false,
                          "small_caps": false,
                          "box_size": null,
                          "box_pos": [
                            0.0,
                            0.0
                          ],
                          "tsume": 0.0,
                          "baseline": "Normal",
                          "kerning": "Metrics",
                          "ligatures": true,
                          "tate_chu_yoko": false,
                          "vertical_roman_upright": false,
                          "indent_left": 0.0,
                          "indent_right": 0.0,
                          "indent_first": 0.0,
                          "space_before": 0.0,
                          "space_after": 0.0,
                          "direction": "Ltr",
                          "composer": "EveryLine",
                          "hanging_punctuation": false,
                          "vertical": false
                        }
                      },
                      "ui": {
                        "kind": "Text"
                      },
                      "spatial": false,
                      "hold_only": true,
                      "three_d_only": false,
                      "two_d_only": false,
                      "shown_dims": 0,
                      "static_only": false
                    },
                    {
                      "node": "Prop",
                      "uid": 68,
                      "match": "perChar3d",
                      "name": "Per-character 3D",
                      "value": {
                        "t": "Bool",
                        "v": false
                      },
                      "ui": {
                        "kind": "Hidden"
                      },
                      "spatial": false,
                      "hold_only": true,
                      "three_d_only": false,
                      "two_d_only": false,
                      "shown_dims": 0,
                      "static_only": true
                    },
                    {
                      "node": "Group",
                      "uid": 70,
                      "match": "pathOptions",
                      "name": "Path Options",
                      "kind": {
                        "kind": "Plain"
                      },
                      "enabled": true,
                      "children": [
                        {
                          "node": "Prop",
                          "uid": 69,
                          "match": "path",
                          "name": "Path",
                          "value": {
                            "t": "Enum",
                            "v": 0
                          },
                          "ui": {
                            "kind": "Popup",
                            "options": [
                              "None"
                            ]
                          },
                          "spatial": false,
                          "hold_only": true,
                          "three_d_only": false,
                          "two_d_only": false,
                          "shown_dims": 0,
                          "static_only": true
                        },
                        {
                          "node": "Prop",
                          "uid": 71,
                          "match": "reversePath",
                          "name": "Reverse Path",
                          "value": {
                            "t": "Bool",
                            "v": false
                          },
                          "ui": {
                            "kind": "Checkbox"
                          },
                          "spatial": false,
                          "hold_only": true,
                          "three_d_only": false,
                          "two_d_only": false,
                          "shown_dims": 0,
                          "static_only": false
                        },
                        {
                          "node": "Prop",
                          "uid": 72,
                          "match": "perpendicular",
                          "name": "Perpendicular To Path",
                          "value": {
                            "t": "Bool",
                            "v": true
                          },
                          "ui": {
                            "kind": "Checkbox"
                          },
                          "spatial": false,
                          "hold_only": true,
                          "three_d_only": false,
                          "two_d_only": false,
                          "shown_dims": 0,
                          "static_only": false
                        },
                        {
                          "node": "Prop",
                          "uid": 73,
                          "match": "forceAlignment",
                          "name": "Force Alignment",
                          "value": {
                            "t": "Bool",
                            "v": false
                          },
                          "ui": {
                            "kind": "Checkbox"
                          },
                          "spatial": false,
                          "hold_only": true,
                          "three_d_only": false,
                          "two_d_only": false,
                          "shown_dims": 0,
                          "static_only": false
                        },
                        {
                          "node": "Prop",
                          "uid": 74,
                          "match": "firstMargin",
                          "name": "First Margin",
                          "value": {
                            "t": "Scalar",
                            "v": 0.0
                          },
                          "ui": {
                            "kind": "Pixels"
                          },
                          "spatial": false,
                          "hold_only": false,
                          "three_d_only": false,
                          "two_d_only": false,
                          "shown_dims": 0,
                          "static_only": false
                        },
                        {
                          "node": "Prop",
                          "uid": 75,
                          "match": "lastMargin",
                          "name": "Last Margin",
                          "value": {
                            "t": "Scalar",
                            "v": 0.0
                          },
                          "ui": {
                            "kind": "Pixels"
                          },
                          "spatial": false,
                          "hold_only": false,
                          "three_d_only": false,
                          "two_d_only": false,
                          "shown_dims": 0,
                          "static_only": false
                        }
                      ]
                    },
                    {
                      "node": "Group",
                      "uid": 76,
                      "match": "moreOptions",
                      "name": "More Options",
                      "kind": {
                        "kind": "Plain"
                      },
                      "enabled": true,
                      "children": [
                        {
                          "node": "Prop",
                          "uid": 77,
                          "match": "anchorGrouping",
                          "name": "Anchor Point Grouping",
                          "value": {
                            "t": "Enum",
                            "v": 0
                          },
                          "ui": {
                            "kind": "Popup",
                            "options": [
                              "Character",
                              "Word",
                              "Line",
                              "All"
                            ]
                          },
                          "spatial": false,
                          "hold_only": true,
                          "three_d_only": false,
                          "two_d_only": false,
                          "shown_dims": 0,
                          "static_only": true
                        },
                        {
                          "node": "Prop",
                          "uid": 78,
                          "match": "groupingAlignment",
                          "name": "Grouping Alignment",
                          "value": {
                            "t": "Vec2",
                            "v": [
                              0.0,
                              0.0
                            ]
                          },
                          "ui": {
                            "kind": "Percent"
                          },
                          "spatial": false,
                          "hold_only": false,
                          "three_d_only": false,
                          "two_d_only": false,
                          "shown_dims": 0,
                          "static_only": false
                        },
                        {
                          "node": "Prop",
                          "uid": 79,
                          "match": "fillStroke",
                          "name": "Fill & Stroke",
                          "value": {
                            "t": "Enum",
                            "v": 0
                          },
                          "ui": {
                            "kind": "Popup",
                            "options": [
                              "Per Character Palette",
                              "All Fills Over All Strokes",
                              "All Strokes Over All Fills"
                            ]
                          },
                          "spatial": false,
                          "hold_only": true,
                          "three_d_only": false,
                          "two_d_only": false,
                          "shown_dims": 0,
                          "static_only": true
                        },
                        {
                          "node": "Prop",
                          "uid": 80,
                          "match": "interCharBlend",
                          "name": "Inter-Character Blending",
                          "value": {
                            "t": "Enum",
                            "v": 0
                          },
                          "ui": {
                            "kind": "Popup",
                            "options": [
"""
        private val FIXTURE_PART_4 = """                              "Normal",
                              "Darken",
                              "Multiply",
                              "Color Burn",
                              "Linear Burn",
                              "Darker Color",
                              "Add",
                              "Lighten",
                              "Screen",
                              "Color Dodge",
                              "Lighter Color",
                              "Overlay",
                              "Soft Light",
                              "Hard Light",
                              "Linear Light",
                              "Vivid Light",
                              "Pin Light",
                              "Hard Mix",
                              "Difference",
                              "Exclusion",
                              "Subtract",
                              "Divide",
                              "Hue",
                              "Saturation",
                              "Color",
                              "Luminosity"
                            ]
                          },
                          "spatial": false,
                          "hold_only": true,
                          "three_d_only": false,
                          "two_d_only": false,
                          "shown_dims": 0,
                          "static_only": true
                        }
                      ]
                    },
                    {
                      "node": "Group",
                      "uid": 82,
                      "match": "animators",
                      "name": "Animators",
                      "kind": {
                        "kind": "Plain"
                      },
                      "enabled": true,
                      "children": []
                    }
                  ]
                },
                {
                  "node": "Group",
                  "uid": 83,
                  "match": "masks",
                  "name": "Masks",
                  "kind": {
                    "kind": "Plain"
                  },
                  "enabled": true,
                  "children": []
                },
                {
                  "node": "Group",
                  "uid": 84,
                  "match": "effects",
                  "name": "Effects",
                  "kind": {
                    "kind": "Plain"
                  },
                  "enabled": true,
                  "children": []
                },
                {
                  "node": "Group",
                  "uid": 93,
                  "match": "transform",
                  "name": "Transform",
                  "kind": {
                    "kind": "Plain"
                  },
                  "enabled": true,
                  "children": [
                    {
                      "node": "Prop",
                      "uid": 85,
                      "match": "anchor",
                      "name": "Anchor Point",
                      "value": {
                        "t": "Vec3",
                        "v": [
                          0.0,
                          0.0,
                          0.0
                        ]
                      },
                      "ui": {
                        "kind": "Point"
                      },
                      "spatial": true,
                      "hold_only": false,
                      "three_d_only": false,
                      "two_d_only": false,
                      "shown_dims": 2,
                      "static_only": false
                    },
                    {
                      "node": "Prop",
                      "uid": 86,
                      "match": "position",
                      "name": "Position",
                      "value": {
                        "t": "Vec3",
                        "v": [
                          320.0,
                          180.0,
                          0.0
                        ]
                      },
                      "ui": {
                        "kind": "Point"
                      },
                      "spatial": true,
                      "hold_only": false,
                      "three_d_only": false,
                      "two_d_only": false,
                      "shown_dims": 2,
                      "static_only": false
                    },
                    {
                      "node": "Prop",
                      "uid": 87,
                      "match": "scale",
                      "name": "Scale",
                      "value": {
                        "t": "Vec3",
                        "v": [
                          100.0,
                          100.0,
                          100.0
                        ]
                      },
                      "ui": {
                        "kind": "Percent"
                      },
                      "spatial": false,
                      "hold_only": false,
                      "three_d_only": false,
                      "two_d_only": false,
                      "shown_dims": 2,
                      "static_only": false
                    },
                    {
                      "node": "Prop",
                      "uid": 88,
                      "match": "orientation",
                      "name": "Orientation",
                      "value": {
                        "t": "Vec3",
                        "v": [
                          0.0,
                          0.0,
                          0.0
                        ]
                      },
                      "ui": {
                        "kind": "Angle"
                      },
                      "spatial": false,
                      "hold_only": false,
                      "three_d_only": true,
                      "two_d_only": false,
                      "shown_dims": 0,
                      "static_only": false
                    },
                    {
                      "node": "Prop",
                      "uid": 89,
                      "match": "rotationX",
                      "name": "X Rotation",
                      "value": {
                        "t": "Scalar",
                        "v": 0.0
                      },
                      "ui": {
                        "kind": "Angle"
                      },
                      "spatial": false,
                      "hold_only": false,
                      "three_d_only": true,
                      "two_d_only": false,
                      "shown_dims": 0,
                      "static_only": false
                    },
                    {
                      "node": "Prop",
                      "uid": 90,
                      "match": "rotationY",
                      "name": "Y Rotation",
                      "value": {
                        "t": "Scalar",
                        "v": 0.0
                      },
                      "ui": {
                        "kind": "Angle"
                      },
                      "spatial": false,
                      "hold_only": false,
                      "three_d_only": true,
                      "two_d_only": false,
                      "shown_dims": 0,
                      "static_only": false
                    },
                    {
                      "node": "Prop",
                      "uid": 91,
                      "match": "rotation",
                      "name": "Rotation",
                      "value": {
                        "t": "Scalar",
                        "v": 0.0
                      },
                      "ui": {
                        "kind": "Angle"
                      },
                      "spatial": false,
                      "hold_only": false,
                      "three_d_only": false,
                      "two_d_only": false,
                      "shown_dims": 0,
                      "static_only": false
                    },
                    {
                      "node": "Prop",
                      "uid": 92,
                      "match": "opacity",
                      "name": "Opacity",
                      "value": {
                        "t": "Scalar",
                        "v": 100.0
                      },
                      "ui": {
                        "kind": "Slider",
                        "min": 0.0,
                        "max": 100.0,
                        "slider_min": 0.0,
                        "slider_max": 100.0,
                        "decimals": 0
                      },
                      "spatial": false,
                      "hold_only": false,
                      "three_d_only": false,
                      "two_d_only": false,
                      "shown_dims": 0,
                      "static_only": false
                    }
                  ]
                },
                {
                  "node": "Group",
                  "uid": 94,
                  "match": "materialOptions",
                  "name": "Material Options",
                  "kind": {
                    "kind": "Plain"
                  },
                  "enabled": true,
                  "children": [
                    {
                      "node": "Prop",
                      "uid": 95,
                      "match": "castsShadows",
                      "name": "Casts Shadows",
                      "value": {
                        "t": "Enum",
                        "v": 0
                      },
                      "ui": {
                        "kind": "Popup",
                        "options": [
                          "Off",
                          "On",
                          "Only"
                        ]
                      },
                      "spatial": false,
                      "hold_only": true,
                      "three_d_only": false,
                      "two_d_only": false,
                      "shown_dims": 0,
                      "static_only": false
                    },
                    {
                      "node": "Prop",
                      "uid": 96,
                      "match": "lightTransmission",
                      "name": "Light Transmission",
                      "value": {
                        "t": "Scalar",
                        "v": 0.0
                      },
                      "ui": {
                        "kind": "Percent"
                      },
                      "spatial": false,
                      "hold_only": false,
                      "three_d_only": false,
                      "two_d_only": false,
                      "shown_dims": 0,
                      "static_only": false
                    },
                    {
                      "node": "Prop",
                      "uid": 97,
                      "match": "acceptsShadows",
                      "name": "Accepts Shadows",
                      "value": {
                        "t": "Enum",
                        "v": 1
                      },
                      "ui": {
                        "kind": "Popup",
                        "options": [
                          "Off",
                          "On",
                          "Only"
                        ]
                      },
                      "spatial": false,
                      "hold_only": true,
                      "three_d_only": false,
                      "two_d_only": false,
                      "shown_dims": 0,
                      "static_only": false
                    },
                    {
                      "node": "Prop",
                      "uid": 98,
                      "match": "acceptsLights",
                      "name": "Accepts Lights",
                      "value": {
                        "t": "Bool",
                        "v": true
                      },
                      "ui": {
                        "kind": "Checkbox"
                      },
                      "spatial": false,
                      "hold_only": true,
                      "three_d_only": false,
                      "two_d_only": false,
                      "shown_dims": 0,
                      "static_only": false
                    },
                    {
                      "node": "Prop",
                      "uid": 99,
                      "match": "ambient",
                      "name": "Ambient",
                      "value": {
                        "t": "Scalar",
                        "v": 100.0
                      },
                      "ui": {
                        "kind": "Percent"
                      },
                      "spatial": false,
                      "hold_only": false,
                      "three_d_only": false,
                      "two_d_only": false,
                      "shown_dims": 0,
                      "static_only": false
                    },
                    {
                      "node": "Prop",
                      "uid": 100,
                      "match": "diffuse",
                      "name": "Diffuse",
                      "value": {
                        "t": "Scalar",
                        "v": 50.0
                      },
                      "ui": {
                        "kind": "Percent"
                      },
                      "spatial": false,
                      "hold_only": false,
                      "three_d_only": false,
                      "two_d_only": false,
                      "shown_dims": 0,
                      "static_only": false
                    },
                    {
                      "node": "Prop",
                      "uid": 101,
                      "match": "specularIntensity",
                      "name": "Specular Intensity",
                      "value": {
                        "t": "Scalar",
                        "v": 50.0
                      },
                      "ui": {
                        "kind": "Percent"
                      },
                      "spatial": false,
                      "hold_only": false,
                      "three_d_only": false,
                      "two_d_only": false,
                      "shown_dims": 0,
                      "static_only": false
                    },
                    {
                      "node": "Prop",
                      "uid": 102,
                      "match": "specularShininess",
                      "name": "Specular Shininess",
                      "value": {
                        "t": "Scalar",
                        "v": 5.0
                      },
                      "ui": {
                        "kind": "Percent"
                      },
                      "spatial": false,
"""
        private val FIXTURE_PART_5 = """                      "hold_only": false,
                      "three_d_only": false,
                      "two_d_only": false,
                      "shown_dims": 0,
                      "static_only": false
                    },
                    {
                      "node": "Prop",
                      "uid": 103,
                      "match": "metal",
                      "name": "Metal",
                      "value": {
                        "t": "Scalar",
                        "v": 100.0
                      },
                      "ui": {
                        "kind": "Percent"
                      },
                      "spatial": false,
                      "hold_only": false,
                      "three_d_only": false,
                      "two_d_only": false,
                      "shown_dims": 0,
                      "static_only": false
                    }
                  ]
                }
              ]
            }
          }
        ],
        "markers": [],
        "shutter_angle": 180.0,
        "shutter_phase": -90.0,
        "motion_blur_samples": 16,
        "motion_blur_adaptive_limit": 128,
        "renderer": "Classic3D",
        "hide_shy": false,
        "enable_motion_blur": true,
        "enable_frame_blending": true,
        "draft_3d": false,
        "preserve_frame_rate": false,
        "preserve_resolution": false,
        "poster_time": 0,
        "global_light": {
          "angle": {
            "uid": 0,
            "match": "globalLightAngle",
            "name": "Global Light Angle",
            "value": {
              "t": "Scalar",
              "v": 120.0
            },
            "ui": {
              "kind": "Angle"
            },
            "spatial": false,
            "hold_only": false,
            "three_d_only": false,
            "two_d_only": false,
            "shown_dims": 0,
            "static_only": false
          },
          "altitude": {
            "uid": 0,
            "match": "globalLightAltitude",
            "name": "Global Light Altitude",
            "value": {
              "t": "Scalar",
              "v": 30.0
            },
            "ui": {
              "kind": "Angle"
            },
            "spatial": false,
            "hold_only": false,
            "three_d_only": false,
            "two_d_only": false,
            "shown_dims": 0,
            "static_only": false
          }
        }
      }
    },
    "2": {
      "id": 2,
      "name": "Clip",
      "label": "Sandstone",
      "comment": "",
      "parent": null,
      "kind": {
        "type": "Footage",
        "path": "missing/old.mp4",
        "kind": "Still",
        "width": 0,
        "height": 0,
        "pixel_aspect": 1.0,
        "frame_rate": {
          "num": 30,
          "den": 1
        },
        "native_rate": null,
        "duration": 0,
        "has_video": false,
        "has_audio": false,
        "alpha": "Straight",
        "premul_color": [
          0.0,
          0.0,
          0.0
        ],
        "loop_count": 1,
        "codec": "",
        "missing": true
      }
    }
  },
  "next_id": 104
}"""
        val FIXTURE_JSON: String = FIXTURE_PART_0 + FIXTURE_PART_1 + FIXTURE_PART_2 + FIXTURE_PART_3 + FIXTURE_PART_4 + FIXTURE_PART_5
        const val COMPOSITION_ID = 1L
        const val FOOTAGE_ITEM_ID = 2L
        const val CLIP_LAYER_ID = 3L
        const val TITLE_LAYER_ID = 26L
        const val SUBTITLE_LAYER_ID = 65L
    }

    private fun assumeNativeArm64() {
        Assume.assumeTrue(
            "Native canonical flows require a physical ARM64 device; " +
                "skipping JNI leg on ${Build.SUPPORTED_ABIS.joinToString()}",
            Build.SUPPORTED_ABIS.contains("arm64-v8a") && NativeProjectBridge.isAvailable()
        )
    }

    private fun testRoot(name: String): File {
        val root = File(
            InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
            "m2-device-flows/$name"
        )
        root.deleteRecursively()
        root.mkdirs()
        return root
    }

    /** The SAF-open leg: bytes arrive as a stream, exactly as a SAF document does. */
    private fun safOpen(source: String): String {
        val read = ByteArrayInputStream(source.toByteArray(Charsets.UTF_8)).use {
            CanonicalProjectDocument.readUtf8(it)
        }
        assertEquals(source, read)
        return CanonicalProjectDocument.toCanonicalJson(read)
    }

    /** The export leg: staged bytes copied through the SAF writer routine. */
    private fun exportBytes(source: String, staging: File): ByteArray {
        staging.writeText(source, Charsets.UTF_8)
        val sink = ByteArrayOutputStream()
        val copied = SafDocumentWriter.copyTo(
            openInput = { staging.inputStream() },
            openOutput = { sink },
            rollbackDestination = { sink.reset() }
        )
        assertEquals(source.toByteArray(Charsets.UTF_8).size.toLong(), copied)
        return sink.toByteArray()
    }

    private fun editedProject(envelope: String?): String {
        val response = JSONObject(envelope ?: error("Native EffectCraft bridge unavailable on this ABI"))
        assertTrue("Native edit must succeed: ${response.optString("error")}", response.optBoolean("valid"))
        return response.getString("project")
    }

    private fun inspect(source: String): CanonicalProjectInfo {
        val metadata = NativeProjectBridge.inspectEcproj(source)
        assertNotNull("Native inspection must succeed", metadata)
        return CanonicalProjectDocument.validateNativeEnvelope(metadata!!)
    }

    private fun layerNames(info: CanonicalProjectInfo): List<String> =
        info.compositions.single().layers.map { it.name }

    private fun jsonEquals(expected: String, actual: String): Boolean =
        jsonValuesEqual(JSONObject(expected), JSONObject(actual))

    private fun jsonValuesEqual(expected: Any?, actual: Any?): Boolean {
        if (expected is JSONObject && actual is JSONObject) {
            if (expected.length() != actual.length()) return false
            val keys = expected.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (!actual.has(key) || !jsonValuesEqual(expected.get(key), actual.get(key))) return false
            }
            return true
        }
        if (expected is JSONArray && actual is JSONArray) {
            if (expected.length() != actual.length()) return false
            for (index in 0 until expected.length()) {
                if (!jsonValuesEqual(expected.get(index), actual.get(index))) return false
            }
            return true
        }
        if (expected === JSONObject.NULL || actual === JSONObject.NULL) return expected === actual
        if (expected is Number && actual is Number) return expected.toDouble() == actual.toDouble()
        return expected == actual
    }

    @Test
    fun safOpenRenameExportRoundTrip() {
        val root = testRoot("saf-open-rename-export")
        try {
            // SAF-open leg (every ABI): format gate, stream read, repository save.
            assertNull(ProjectFileFormats.importError("Project.ecproj"))
            val source = safOpen(FIXTURE_JSON)
            assertTrue(jsonEquals(FIXTURE_JSON, source))
            val repository = CanonicalProjectRepository(File(root, "repo"))
            val entry = repository.create("M2 device flow", source, "ecproj", "Project.ecproj")
            assertEquals(source, repository.load(entry.id))
            assertTrue(repository.list().any { it.id == entry.id })
            // Export leg (every ABI): unedited bytes survive the staged copy.
            assertEquals(
                source,
                exportBytes(source, File(root, "export-original.ecproj")).toString(Charsets.UTF_8)
            )

            // Native rename leg (ARM64 only): rename, re-inspect, persist, export.
            assumeNativeArm64()
            val before = inspect(source)
            assertEquals(listOf("Clip", "Title", "Subtitle"), layerNames(before))
            val renamed = editedProject(
                NativeProjectBridge.renameLayerEcproj(source, COMPOSITION_ID, TITLE_LAYER_ID, "Renamed title")
            )
            assertTrue(renamed != source)
            val after = inspect(renamed)
            assertEquals(listOf("Clip", "Renamed title", "Subtitle"), layerNames(after))
            assertEquals(
                before.compositions.single().layers.map { it.id },
                after.compositions.single().layers.map { it.id }
            )
            repository.update(entry.id, renamed)
            assertEquals(renamed, repository.load(entry.id))

            // A failed edit must leave the repository entry untouched.
            val blank = JSONObject(
                NativeProjectBridge.renameLayerEcproj(renamed, COMPOSITION_ID, TITLE_LAYER_ID, "   ")
                    ?: error("Native EffectCraft bridge unavailable on this ABI")
            )
            assertFalse(blank.optBoolean("valid"))
            assertEquals(renamed, repository.load(entry.id))

            // Export leg: exported bytes re-inspect to the renamed project.
            val exported = exportBytes(renamed, File(root, "export.ecproj")).toString(Charsets.UTF_8)
            assertTrue(jsonEquals(renamed, exported))
            assertEquals(listOf("Clip", "Renamed title", "Subtitle"), layerNames(inspect(exported)))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun safCopyNativeRelinkExport() {
        val root = testRoot("saf-copy-relink-export")
        try {
            // SAF-copy leg (every ABI): stream import, deduped payload, verified bytes.
            val store = OfflineAssetStore(File(root, "media-library"))
            val clipBytes = "M2 device-flow clip payload".toByteArray(Charsets.UTF_8)
            val asset = ByteArrayInputStream(clipBytes).use { input ->
                store.import(input, "clip.mp4", "video/mp4")
            }
            assertEquals("mp4", asset.extension)
            assertEquals(clipBytes.size.toLong(), asset.sizeBytes)
            assertTrue(store.verify(asset.sha256))
            val payload = store.fileFor(asset.sha256)
            assertNotNull(payload)
            assertTrue(payload!!.isFile)
            assertEquals(clipBytes.size.toLong(), payload.length())

            // Repository leg (every ABI): the missing-media project is saved first.
            val source = safOpen(FIXTURE_JSON)
            val repository = CanonicalProjectRepository(File(root, "repo"))
            val entry = repository.create("M2 relink flow", source, "ecproj", "Project.ecproj")
            assertEquals(source, repository.load(entry.id))
            assertEquals(
                source,
                exportBytes(source, File(root, "export-original.ecproj")).toString(Charsets.UTF_8)
            )

            // Native relink leg (ARM64 only): missing flag clears, path moves.
            assumeNativeArm64()
            val missingBefore = inspect(source).items.single { it.id == FOOTAGE_ITEM_ID }
            assertTrue(missingBefore.missing)
            assertEquals("missing/old.mp4", missingBefore.path)
            val relinked = editedProject(
                NativeProjectBridge.relinkFootageEcproj(source, FOOTAGE_ITEM_ID, payload.absolutePath)
            )
            assertTrue(relinked != source)
            val present = inspect(relinked).items.single { it.id == FOOTAGE_ITEM_ID }
            assertFalse(present.missing)
            assertEquals(payload.absolutePath, present.path)

            // Manifest + repository legs: the link is durable, then the edit is saved.
            val manifestStore = ProjectAssetManifestStore(File(root, "asset-manifests"))
            val manifest = manifestStore.load(entry.id)
            manifest.link(FOOTAGE_ITEM_ID, asset.sha256, payload.absolutePath)
            manifestStore.save(manifest)
            val reloadedManifest = manifestStore.load(entry.id)
            assertEquals(asset.sha256, reloadedManifest.entryFor(FOOTAGE_ITEM_ID)?.sha256)
            assertFalse(reloadedManifest.entryFor(FOOTAGE_ITEM_ID)?.missing ?: true)
            repository.update(entry.id, relinked)
            assertEquals(relinked, repository.load(entry.id))

            // Failed relinks must leave repository and manifest untouched.
            val unknown = JSONObject(
                NativeProjectBridge.relinkFootageEcproj(relinked, 999_999L, payload.absolutePath)
                    ?: error("Native EffectCraft bridge unavailable on this ABI")
            )
            assertFalse(unknown.optBoolean("valid"))
            val blank = JSONObject(
                NativeProjectBridge.relinkFootageEcproj(relinked, FOOTAGE_ITEM_ID, "   ")
                    ?: error("Native EffectCraft bridge unavailable on this ABI")
            )
            assertFalse(blank.optBoolean("valid"))
            assertEquals(relinked, repository.load(entry.id))
            assertEquals(asset.sha256, manifestStore.load(entry.id).entryFor(FOOTAGE_ITEM_ID)?.sha256)

            // Export leg: exported bytes still carry the relinked path.
            val exported = exportBytes(relinked, File(root, "export.ecproj")).toString(Charsets.UTF_8)
            val exportedItem = inspect(exported).items.single { it.id == FOOTAGE_ITEM_ID }
            assertFalse(exportedItem.missing)
            assertEquals(payload.absolutePath, exportedItem.path)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun canonicalImportAddRenameVisibilityReorderDeleteExport() {
        val root = testRoot("canonical-edit-export")
        try {
            // Canonical import leg (every ABI).
            val source = safOpen(FIXTURE_JSON)
            val repository = CanonicalProjectRepository(File(root, "repo"))
            val entry = repository.create("M2 edit flow", source, "ecproj", "Project.ecproj")
            assertEquals(source, repository.load(entry.id))
            assertEquals(
                source,
                exportBytes(source, File(root, "export-original.ecproj")).toString(Charsets.UTF_8)
            )

            // Native edit chain (ARM64 only): every step re-inspected and persisted,
            // mirroring the editor's commit-after-each-mutation rule.
            assumeNativeArm64()
            var current = source
            var info = inspect(current)
            assertEquals(listOf("Clip", "Title", "Subtitle"), layerNames(info))
            val originalIds = info.compositions.single().layers.map { it.id }

            // Add: a new Text layer appears with a fresh stable ID.
            current = editedProject(
                NativeProjectBridge.addTextLayerEcproj(current, COMPOSITION_ID, "Inserted")
            )
            info = inspect(current)
            assertEquals(listOf("Clip", "Title", "Subtitle", "Inserted"), layerNames(info))
            val insertedId = info.compositions.single().layers.single { it.name == "Inserted" }.id
            assertTrue(insertedId !in originalIds)
            repository.update(entry.id, current)
            assertEquals(current, repository.load(entry.id))

            // Rename: the added layer keeps its ID under its new name.
            current = editedProject(
                NativeProjectBridge.renameLayerEcproj(current, COMPOSITION_ID, insertedId, "Inserted renamed")
            )
            info = inspect(current)
            assertEquals(listOf("Clip", "Title", "Subtitle", "Inserted renamed"), layerNames(info))
            assertEquals(insertedId, info.compositions.single().layers.single { it.name == "Inserted renamed" }.id)
            repository.update(entry.id, current)

            // Visibility: hiding the footage layer leaves the others visible.
            current = editedProject(
                NativeProjectBridge.setLayerVisibilityEcproj(current, COMPOSITION_ID, CLIP_LAYER_ID, false)
            )
            info = inspect(current)
            val clip = info.compositions.single().layers.single { it.id == CLIP_LAYER_ID }
            assertFalse(clip.visible)
            assertTrue(info.compositions.single().layers.filter { it.id != CLIP_LAYER_ID }.all { it.visible })
            repository.update(entry.id, current)

            // Reorder: the native move swaps with the target slot, so moving
            // Subtitle up two slots puts it first and swaps Clip/Title order.
            current = editedProject(
                NativeProjectBridge.moveLayerEcproj(current, COMPOSITION_ID, SUBTITLE_LAYER_ID, -2)
            )
            info = inspect(current)
            assertEquals(listOf("Subtitle", "Title", "Clip", "Inserted renamed"), layerNames(info))
            assertEquals(
                originalIds.toSet() + insertedId,
                info.compositions.single().layers.map { it.id }.toSet()
            )
            repository.update(entry.id, current)

            // Delete: removing the added layer leaves the original stack, hidden flag kept.
            current = editedProject(
                NativeProjectBridge.deleteLayerEcproj(current, COMPOSITION_ID, insertedId)
            )
            info = inspect(current)
            assertEquals(listOf("Subtitle", "Title", "Clip"), layerNames(info))
            assertEquals(originalIds.toSet(), info.compositions.single().layers.map { it.id }.toSet())
            assertFalse(info.compositions.single().layers.single { it.id == CLIP_LAYER_ID }.visible)
            repository.update(entry.id, current)
            assertEquals(current, repository.load(entry.id))

            // A failed delete must leave the repository entry untouched.
            val unknown = JSONObject(
                NativeProjectBridge.deleteLayerEcproj(current, COMPOSITION_ID, 999_999L)
                    ?: error("Native EffectCraft bridge unavailable on this ABI")
            )
            assertFalse(unknown.optBoolean("valid"))
            assertEquals(current, repository.load(entry.id))

            // Export leg: exported bytes match the final edited project exactly.
            val exported = exportBytes(current, File(root, "export.ecproj")).toString(Charsets.UTF_8)
            assertTrue(jsonEquals(current, exported))
            assertEquals(listOf("Subtitle", "Title", "Clip"), layerNames(inspect(exported)))
        } finally {
            root.deleteRecursively()
        }
    }
}
