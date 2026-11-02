use effectcraft_project::Project;
use jni::objects::{JClass, JString};
use jni::sys::{jlong, jstring};
use jni::JNIEnv;
use serde_json::json;
use effectcraft_project::{ItemId, ItemKind, LayerId};

fn inspect_project(source: &str) -> Result<String, String> {
    let project: Project = serde_json::from_str(source)
        .map_err(|error| format!("Invalid EffectCraft project: {error}"))?;

    let compositions: Vec<_> = project
        .comps()
        .map(|(id, comp)| {
            let layers: Vec<_> = comp.layers.iter().map(|layer| json!({
                "id": layer.id.0,
                "name": layer.name,
                "source": layer.source.type_name(),
                "visible": layer.switches.video
            })).collect();
            json!({
                "id": id.0,
                "name": project.item(*id).map(|item| item.name.as_str()).unwrap_or("Composition"),
                "width": comp.width,
                "height": comp.height,
                "layerCount": layers.len(),
                "layers": layers
            })
        })
        .collect();

    let items: Vec<_> = project.items.values().map(|item| {
        let media = match &item.kind {
            ItemKind::Footage(footage) => json!({
                "path": footage.path,
                "width": footage.width,
                "height": footage.height,
                "hasVideo": footage.has_video,
                "hasAudio": footage.has_audio,
                "missing": footage.missing,
                "sequence": footage.sequence
            }),
            ItemKind::Solid(solid) => json!({"width": solid.width, "height": solid.height}),
            _ => json!({}),
        };
        json!({
            "id": item.id.0,
            "name": item.name,
            "type": item.type_name(),
            "parentId": item.parent.map(|parent| parent.0),
            "media": media
        })
    }).collect();

    Ok(json!({
        "valid": true,
        "schema": project.schema,
        "itemCount": project.items.len(),
        "items": items,
        "compositionCount": compositions.len(),
        "compositions": compositions
    })
    .to_string())
}

#[no_mangle]
pub extern "system" fn Java_ai_effectcraft_android_engine_NativeProjectBridge_inspectEcprojNative(
    mut env: JNIEnv,
    _class: JClass,
    source: JString,
) -> jstring {
    let source: String = match env.get_string(&source) {
        Ok(value) => value.into(),
        Err(error) => format!("{{\"valid\":false,\"error\":{}}}", json!(error.to_string())),
    };

    let result = inspect_project(&source).unwrap_or_else(|error| {
        json!({ "valid": false, "error": error }).to_string()
    });

    match env.new_string(result) {
        Ok(value) => value.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}


fn rename_layer(source: &str, composition_id: u64, layer_id: u64, name: &str) -> Result<String, String> {
    let normalized = name.trim();
    if normalized.is_empty() {
        return Err("Layer name cannot be blank".into());
    }
    let mut project: Project = serde_json::from_str(source)
        .map_err(|error| format!("Invalid EffectCraft project: {error}"))?;
    let comp = project.comp_mut(ItemId(composition_id))
        .ok_or_else(|| format!("Composition {composition_id} was not found"))?;
    let layer = comp.layers.find_mut(|layer| layer.id == LayerId(layer_id))
        .ok_or_else(|| format!("Layer {layer_id} was not found in composition {composition_id}"))?;
    layer.name = normalized.to_owned();
    project.to_file_json().map_err(|error| format!("Unable to serialize project: {error}"))
}

#[no_mangle]
pub extern "system" fn Java_ai_effectcraft_android_engine_NativeProjectBridge_renameLayerEcprojNative(
    mut env: JNIEnv,
    _class: JClass,
    source: JString,
    composition_id: jlong,
    layer_id: jlong,
    name: JString,
) -> jstring {
    let source: String = match env.get_string(&source) {
        Ok(value) => value.into(),
        Err(error) => return env.new_string(json!({"valid": false, "error": error.to_string()}).to_string()).map_or(std::ptr::null_mut(), |v| v.into_raw()),
    };
    let name: String = match env.get_string(&name) {
        Ok(value) => value.into(),
        Err(error) => return env.new_string(json!({"valid": false, "error": error.to_string()}).to_string()).map_or(std::ptr::null_mut(), |v| v.into_raw()),
    };
    let result = if composition_id < 0 || layer_id < 0 {
        Err("Composition and layer IDs must be non-negative".to_owned())
    } else {
        rename_layer(&source, composition_id as u64, layer_id as u64, &name)
    };
    let output = match result {
        Ok(project_json) => json!({"valid": true, "project": project_json}).to_string(),
        Err(error) => json!({"valid": false, "error": error}).to_string(),
    };
    env.new_string(output).map_or(std::ptr::null_mut(), |value| value.into_raw())
}



fn add_text_layer(source: &str, composition_id: u64, name: &str) -> Result<String, String> {
    use effectcraft_project::{build, LayerSource};
    let normalized = name.trim();
    if normalized.is_empty() { return Err("Layer name cannot be blank".into()); }
    let mut project: Project = serde_json::from_str(source)
        .map_err(|error| format!("Invalid EffectCraft project: {error}"))?;
    let comp = project.comp(ItemId(composition_id))
        .cloned().ok_or_else(|| format!("Composition {composition_id} was not found"))?;
    let layer = build::layer(&mut project, &comp, normalized, LayerSource::Text,
        (comp.width, comp.height), None);
    project.comp_mut(ItemId(composition_id))
        .ok_or_else(|| format!("Composition {composition_id} disappeared during layer creation"))?
        .layers.push(layer);
    project.to_file_json().map_err(|error| format!("Unable to serialize project: {error}"))
}

#[no_mangle]
pub extern "system" fn Java_ai_effectcraft_android_engine_NativeProjectBridge_addTextLayerEcprojNative(
    mut env: JNIEnv, _class: JClass, source: JString, composition_id: jlong, name: JString,
) -> jstring {
    let source: String = match env.get_string(&source) {
        Ok(value) => value.into(),
        Err(error) => return env.new_string(json!({"valid": false, "error": error.to_string()}).to_string()).map_or(std::ptr::null_mut(), |v| v.into_raw()),
    };
    let name: String = match env.get_string(&name) {
        Ok(value) => value.into(),
        Err(error) => return env.new_string(json!({"valid": false, "error": error.to_string()}).to_string()).map_or(std::ptr::null_mut(), |v| v.into_raw()),
    };
    let result = if composition_id < 0 { Err("Composition ID must be non-negative".to_owned()) }
        else { add_text_layer(&source, composition_id as u64, &name) };
    let output = match result {
        Ok(project_json) => json!({"valid": true, "project": project_json}).to_string(),
        Err(error) => json!({"valid": false, "error": error}).to_string(),
    };
    env.new_string(output).map_or(std::ptr::null_mut(), |value| value.into_raw())
}

fn set_layer_visibility(source: &str, composition_id: u64, layer_id: u64, visible: bool) -> Result<String, String> {
    let mut project: Project = serde_json::from_str(source)
        .map_err(|error| format!("Invalid EffectCraft project: {error}"))?;
    let comp = project.comp_mut(ItemId(composition_id))
        .ok_or_else(|| format!("Composition {composition_id} was not found"))?;
    let layer = comp.layers.find_mut(|layer| layer.id == LayerId(layer_id))
        .ok_or_else(|| format!("Layer {layer_id} was not found in composition {composition_id}"))?;
    layer.switches.video = visible;
    project.to_file_json().map_err(|error| format!("Unable to serialize project: {error}"))
}

fn move_layer(source: &str, composition_id: u64, layer_id: u64, offset: i64) -> Result<String, String> {
    let mut project: Project = serde_json::from_str(source)
        .map_err(|error| format!("Invalid EffectCraft project: {error}"))?;
    let comp = project.comp_mut(ItemId(composition_id))
        .ok_or_else(|| format!("Composition {composition_id} was not found"))?;
    let index = comp.layers.iter().position(|layer| layer.id == LayerId(layer_id))
        .ok_or_else(|| format!("Layer {layer_id} was not found in composition {composition_id}"))?;
    let last = comp.layers.len().saturating_sub(1) as i64;
    let target = (index as i64 + offset).clamp(0, last) as usize;
    comp.layers.swap(index, target);
    project.to_file_json().map_err(|error| format!("Unable to serialize project: {error}"))
}

fn delete_layer(source: &str, composition_id: u64, layer_id: u64) -> Result<String, String> {
    let mut project: Project = serde_json::from_str(source)
        .map_err(|error| format!("Invalid EffectCraft project: {error}"))?;
    let comp = project.comp_mut(ItemId(composition_id))
        .ok_or_else(|| format!("Composition {composition_id} was not found"))?;
    let index = comp.layers.iter().position(|layer| layer.id == LayerId(layer_id))
        .ok_or_else(|| format!("Layer {layer_id} was not found in composition {composition_id}"))?;
    comp.layers.remove(index);
    project.to_file_json().map_err(|error| format!("Unable to serialize project: {error}"))
}

#[no_mangle]
pub extern "system" fn Java_ai_effectcraft_android_engine_NativeProjectBridge_setLayerVisibilityEcprojNative(
    mut env: JNIEnv, _class: JClass, source: JString, composition_id: jlong, layer_id: jlong, visible: jni::sys::jboolean,
) -> jstring {
    let source: String = match env.get_string(&source) {
        Ok(value) => value.into(),
        Err(error) => return env.new_string(json!({"valid": false, "error": error.to_string()}).to_string()).map_or(std::ptr::null_mut(), |v| v.into_raw()),
    };
    let result = if composition_id < 0 || layer_id < 0 { Err("Composition and layer IDs must be non-negative".to_owned()) }
        else { set_layer_visibility(&source, composition_id as u64, layer_id as u64, visible != 0) };
    let output = match result { Ok(project_json) => json!({"valid": true, "project": project_json}).to_string(), Err(error) => json!({"valid": false, "error": error}).to_string() };
    env.new_string(output).map_or(std::ptr::null_mut(), |value| value.into_raw())
}

#[no_mangle]
pub extern "system" fn Java_ai_effectcraft_android_engine_NativeProjectBridge_moveLayerEcprojNative(
    mut env: JNIEnv, _class: JClass, source: JString, composition_id: jlong, layer_id: jlong, offset: jlong,
) -> jstring {
    let source: String = match env.get_string(&source) {
        Ok(value) => value.into(),
        Err(error) => return env.new_string(json!({"valid": false, "error": error.to_string()}).to_string()).map_or(std::ptr::null_mut(), |v| v.into_raw()),
    };
    let result = if composition_id < 0 || layer_id < 0 { Err("Composition and layer IDs must be non-negative".to_owned()) }
        else { move_layer(&source, composition_id as u64, layer_id as u64, offset as i64) };
    let output = match result { Ok(project_json) => json!({"valid": true, "project": project_json}).to_string(), Err(error) => json!({"valid": false, "error": error}).to_string() };
    env.new_string(output).map_or(std::ptr::null_mut(), |value| value.into_raw())
}

#[no_mangle]
pub extern "system" fn Java_ai_effectcraft_android_engine_NativeProjectBridge_deleteLayerEcprojNative(
    mut env: JNIEnv, _class: JClass, source: JString, composition_id: jlong, layer_id: jlong,
) -> jstring {
    let source: String = match env.get_string(&source) {
        Ok(value) => value.into(),
        Err(error) => return env.new_string(json!({"valid": false, "error": error.to_string()}).to_string()).map_or(std::ptr::null_mut(), |v| v.into_raw()),
    };
    let result = if composition_id < 0 || layer_id < 0 { Err("Composition and layer IDs must be non-negative".to_owned()) }
        else { delete_layer(&source, composition_id as u64, layer_id as u64) };
    let output = match result { Ok(project_json) => json!({"valid": true, "project": project_json}).to_string(), Err(error) => json!({"valid": false, "error": error}).to_string() };
    env.new_string(output).map_or(std::ptr::null_mut(), |value| value.into_raw())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn canonical_upstream_project_file_roundtrips_and_inspects() {
        let original = Project::default();
        let source = original.to_file_json().unwrap();
        let decoded: Project = serde_json::from_str(&source).unwrap();
        assert_eq!(decoded, original);

        let metadata = inspect_project(&source).unwrap();
        let metadata: serde_json::Value = serde_json::from_str(&metadata).unwrap();
        assert_eq!(metadata["valid"], true);
        assert_eq!(metadata["schema"], original.schema);
        assert_eq!(metadata["itemCount"], original.items.len());
        assert_eq!(metadata["compositionCount"], 0);
        assert!(metadata["compositions"].as_array().is_some());
        assert!(metadata["items"].as_array().is_some());
    }


    #[test]
    fn rename_layer_updates_non_empty_canonical_project_and_roundtrips() {
        use effectcraft_project::{build, Comp, ItemKind, LayerSource};
        use effectcraft_color::Label;
        use effectcraft_time::{FrameRate, Tick};

        let mut project = Project::default();
        let comp = Comp::new(1280, 720, FrameRate::FPS_30, Tick::from_seconds_f64(5.0));
        let comp_id = project.add_item("Main", Label::Sandstone, None, ItemKind::Comp(comp.clone().into()));
        let layer = build::layer(&mut project, &comp, "Original", LayerSource::Text, (1280, 720), None);
        let layer_id = layer.id.0;
        project.comp_mut(comp_id).unwrap().layers.push(layer);
        let source = project.to_file_json().unwrap();

        let renamed_json = rename_layer(&source, comp_id.0, layer_id, "  Mobile title  ").unwrap();
        let renamed: Project = serde_json::from_str(&renamed_json).unwrap();
        assert_eq!(renamed.comp(comp_id).unwrap().layers[0].name, "Mobile title");
        assert_eq!(renamed.comp(comp_id).unwrap().layers[0].id.0, layer_id);
        let metadata: serde_json::Value = serde_json::from_str(&inspect_project(&renamed_json).unwrap()).unwrap();
        assert_eq!(metadata["compositions"][0]["id"], comp_id.0);
        assert_eq!(metadata["compositions"][0]["layers"][0]["id"], layer_id);
        assert_eq!(metadata["compositions"][0]["layers"][0]["name"], "Mobile title");
        assert_eq!(serde_json::from_str::<Project>(&renamed.to_file_json().unwrap()).unwrap(), renamed);
    }

    #[test]
    fn rename_layer_rejects_blank_and_missing_ids() {
        let source = Project::default().to_file_json().unwrap();
        assert!(rename_layer(&source, 1, 1, "  ").is_err());
        assert!(rename_layer(&source, 99, 1, "Name").is_err());
    }

    #[test]
    fn add_text_layer_mutates_real_canonical_composition_and_roundtrips() {
        use effectcraft_project::{Comp, ItemKind};
        use effectcraft_color::Label;
        use effectcraft_time::{FrameRate, Tick};
        let mut project = Project::default();
        let comp = Comp::new(640, 360, FrameRate::FPS_30, Tick::from_seconds_f64(3.0));
        let comp_id = project.add_item("Main", Label::Sandstone, None, ItemKind::Comp(comp.into()));
        let source = project.to_file_json().unwrap();
        let updated_json = add_text_layer(&source, comp_id.0, "  Headline  ").unwrap();
        let updated: Project = serde_json::from_str(&updated_json).unwrap();
        let layers = &updated.comp(comp_id).unwrap().layers;
        assert_eq!(layers.len(), 1);
        assert_eq!(layers[0].name, "Headline");
        assert_eq!(layers[0].source.type_name(), "Text");
        assert_eq!(updated.comp(comp_id).unwrap().width, 640);
        assert_eq!(updated.comp(comp_id).unwrap().height, 360);
        assert_eq!(serde_json::from_str::<Project>(&updated.to_file_json().unwrap()).unwrap(), updated);
        let metadata: serde_json::Value = serde_json::from_str(&inspect_project(&updated_json).unwrap()).unwrap();
        assert_eq!(metadata["compositions"][0]["layerCount"], 1);
    }

    #[test]
    fn add_text_layer_rejects_blank_name_and_missing_composition() {
        let source = Project::default().to_file_json().unwrap();
        assert!(add_text_layer(&source, 1, " ").is_err());
        assert!(add_text_layer(&source, 999, "Text").is_err());
    }

    #[test]
    fn canonical_layer_stack_actions_roundtrip_visibility_order_and_delete() {
        use effectcraft_project::{build, Comp, ItemKind, LayerSource};
        use effectcraft_color::Label;
        use effectcraft_time::{FrameRate, Tick};

        let mut project = Project::default();
        let comp = Comp::new(640, 360, FrameRate::FPS_30, Tick::from_seconds_f64(3.0));
        let comp_id = project.add_item("Main", Label::Sandstone, None, ItemKind::Comp(comp.clone().into()));
        let mut ids = Vec::new();
        for name in ["A", "B", "C"] {
            let layer = build::layer(&mut project, &comp, name, LayerSource::Text, (640, 360), None);
            ids.push(layer.id.0);
            project.comp_mut(comp_id).unwrap().layers.push(layer);
        }
        let source = project.to_file_json().unwrap();
        let moved = move_layer(&source, comp_id.0, ids[2], -2).unwrap();
        let reordered: Project = serde_json::from_str(&moved).unwrap();
        assert_eq!(reordered.comp(comp_id).unwrap().layers.iter().map(|l| l.name.as_str()).collect::<Vec<_>>(), vec!["C", "A", "B"]);

        let hidden = set_layer_visibility(&moved, comp_id.0, ids[2], false).unwrap();
        let hidden_project: Project = serde_json::from_str(&hidden).unwrap();
        assert!(!hidden_project.comp(comp_id).unwrap().layers[0].switches.video);

        let deleted = delete_layer(&hidden, comp_id.0, ids[0]).unwrap();
        let final_project: Project = serde_json::from_str(&deleted).unwrap();
        assert_eq!(final_project.comp(comp_id).unwrap().layers.iter().map(|l| l.name.as_str()).collect::<Vec<_>>(), vec!["C", "B"]);
        assert_eq!(serde_json::from_str::<Project>(&final_project.to_file_json().unwrap()).unwrap(), final_project);
    }

    #[test]
    fn rejects_non_project_json() {
        let result = inspect_project("{}");
        assert!(result.is_err());
    }
}
