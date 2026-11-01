use effectcraft_project::Project;
use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use serde_json::json;

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
pub extern "system" fn Java_ai_effectcraft_android_engine_NativeProjectBridge_inspectEcproj(
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_non_project_json() {
        let result = inspect_project("{}");
        assert!(result.is_err());
    }
}
