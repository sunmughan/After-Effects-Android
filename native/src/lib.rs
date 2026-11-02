use effectcraft_project::Project;
use jni::objects::{JClass, JString};
use jni::sys::{jlong, jstring};
use jni::JNIEnv;
use serde_json::json;
use effectcraft_project::{ItemId, LayerId};

fn inspect_project(source: &str) -> Result<String, String> {
    let project: Project = serde_json::from_str(source)
        .map_err(|error| format!("Invalid EffectCraft project: {error}"))?;

    let compositions: Vec<_> = project
        .comps()
        .map(|(id, comp)| {
            json!({
                "id": id.0,
                "width": comp.width,
                "height": comp.height,
                "layers": comp.layers.len()
            })
        })
        .collect();

    Ok(json!({
        "valid": true,
        "schema": project.schema,
        "itemCount": project.items.len(),
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
        assert_eq!(serde_json::from_str::<Project>(&renamed.to_file_json().unwrap()).unwrap(), renamed);
    }

    #[test]
    fn rename_layer_rejects_blank_and_missing_ids() {
        let source = Project::default().to_file_json().unwrap();
        assert!(rename_layer(&source, 1, 1, "  ").is_err());
        assert!(rename_layer(&source, 99, 1, "Name").is_err());
    }

    #[test]
    fn rejects_non_project_json() {
        let result = inspect_project("{}");
        assert!(result.is_err());
    }
}
