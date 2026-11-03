use effectcraft_project::Project;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jlong, jstring};
use jni::JNIEnv;
use serde_json::json;
use effectcraft_project::{ItemId, ItemKind, LayerId, Node, Value};
use effectcraft_media::MediaPool;
use effectcraft_render::{RenderOpts, Renderer};
use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, OnceLock, RwLock};
use effectcraft_time::Tick;

static MEDIA_POOL: OnceLock<MediaPool> = OnceLock::new();

/// One native render session: the parsed project, its own MediaPool, the
/// preview size, and a cancellation flag, owned by the registry below.
///
/// Ownership/threading contract:
/// - A session is created once and referenced by a nonzero handle; the
///   registry owns one `Arc`, and each in-flight render holds another, so
///   closing a session never frees memory under a running render.
/// - Renders serialize on the per-session `render_lock`. Project replacement
///   (update) and resize take the same lock first, so no render ever reads a
///   half-swapped project or a half-applied preview size.
/// - Cancellation is cooperative: the flag is checked before a render starts,
///   after it acquires the render lock, and after the CPU frame finishes. It
///   cannot interrupt a `comp_frame` already executing, and it is sticky: a
///   cancelled session renders nothing again until it is closed. Closing a
///   session also marks it cancelled, so a render that grabbed its `Arc`
///   before close discards its result instead of delivering a stale frame.
struct NativeSession {
    project: RwLock<Project>,
    footage: MediaPool,
    render_lock: Mutex<()>,
    cancelled: AtomicBool,
    max_side: RwLock<u32>,
}

struct SessionRegistry {
    sessions: Mutex<HashMap<u64, Arc<NativeSession>>>,
    next_id: AtomicU64,
}

static SESSIONS: std::sync::LazyLock<SessionRegistry> = std::sync::LazyLock::new(|| SessionRegistry {
    sessions: Mutex::new(HashMap::new()),
    next_id: AtomicU64::new(1),
});

fn create_session(source: &str, max_side: u32) -> Result<u64, String> {
    if max_side > 4096 {
        return Err("Preview size must be between 0 and 4096".into());
    }
    let project: Project = serde_json::from_str(source)
        .map_err(|error| format!("Invalid EffectCraft project: {error}"))?;
    let session = Arc::new(NativeSession {
        project: RwLock::new(project),
        footage: MediaPool::new(),
        render_lock: Mutex::new(()),
        cancelled: AtomicBool::new(false),
        max_side: RwLock::new(max_side),
    });
    let id = SESSIONS.next_id.fetch_add(1, Ordering::SeqCst);
    SESSIONS
        .sessions
        .lock()
        .map_err(|_| "Session registry lock poisoned".to_string())?
        .insert(id, session);
    Ok(id)
}

fn lookup_session(id: u64) -> Result<Arc<NativeSession>, String> {
    SESSIONS
        .sessions
        .lock()
        .map_err(|_| "Session registry lock poisoned".to_string())?
        .get(&id)
        .cloned()
        .ok_or_else(|| format!("Unknown native session {id}"))
}

fn close_session(id: u64) -> bool {
    // Cancelling here (not only in the JNI wrapper) is what makes the
    // ownership contract hold for every caller: a render holding an Arc
    // past close discards its result instead of delivering a stale frame.
    match SESSIONS.sessions.lock() {
        Ok(mut sessions) => match sessions.remove(&id) {
            Some(session) => {
                cancel_session(&session);
                true
            }
            None => false,
        },
        Err(_) => false,
    }
}

fn cancel_session(session: &NativeSession) {
    session.cancelled.store(true, Ordering::SeqCst);
}

fn resize_session(session: &NativeSession, max_side: u32) -> Result<(), String> {
    if max_side > 4096 {
        return Err("Preview size must be between 0 and 4096".into());
    }
    if session.cancelled.load(Ordering::SeqCst) {
        return Err("Session is cancelled".into());
    }
    let _render_guard = session
        .render_lock
        .lock()
        .map_err(|_| "Session render lock poisoned".to_string())?;
    if session.cancelled.load(Ordering::SeqCst) {
        return Err("Session is cancelled".into());
    }
    let mut size = session
        .max_side
        .write()
        .map_err(|_| "Session preview-size lock poisoned".to_string())?;
    *size = max_side;
    Ok(())
}

fn update_session_project(session: &NativeSession, source: &str) -> Result<(), String> {
    let next: Project = serde_json::from_str(source)
        .map_err(|error| format!("Invalid EffectCraft project: {error}"))?;
    let _render_guard = session
        .render_lock
        .lock()
        .map_err(|_| "Session render lock poisoned".to_string())?;
    if session.cancelled.load(Ordering::SeqCst) {
        return Err("Session is cancelled".into());
    }
    let mut project = session
        .project
        .write()
        .map_err(|_| "Session project lock poisoned".to_string())?;
    *project = next;
    Ok(())
}

fn render_session_frame(
    session: &NativeSession,
    composition_id: u64,
    frame: i64,
) -> Result<Vec<u8>, String> {
    if session.cancelled.load(Ordering::SeqCst) {
        return Err("Session is cancelled".into());
    }
    let _render_guard = session
        .render_lock
        .lock()
        .map_err(|_| "Session render lock poisoned".to_string())?;
    if session.cancelled.load(Ordering::SeqCst) {
        return Err("Session is cancelled".into());
    }
    let max_side = *session
        .max_side
        .read()
        .map_err(|_| "Session preview-size lock poisoned".to_string())?;
    let project = session
        .project
        .read()
        .map_err(|_| "Session project lock poisoned".to_string())?;
    let bytes = render_parsed_frame(&project, &session.footage, composition_id, frame, max_side)?;
    if session.cancelled.load(Ordering::SeqCst) {
        return Err("Session render discarded after cancellation".into());
    }
    Ok(bytes)
}

fn render_parsed_frame(project: &Project, footage: &MediaPool, composition_id: u64, frame: i64, max_side: u32) -> Result<Vec<u8>, String> {
    if frame < 0 { return Err("Frame must be non-negative".into()); }
    let comp = project.comp(ItemId(composition_id))
        .ok_or_else(|| format!("Composition {composition_id} was not found"))?;
    let long = comp.width.max(comp.height).max(1) as f64;
    let scale = if max_side == 0 { 1.0 } else { (max_side as f64 / long).min(1.0) };
    let time = Tick::from_rational(frame, comp.frame_rate.den, comp.frame_rate.num);
    let renderer = Renderer::new(project, footage, RenderOpts { scale, ..Default::default() });
    let image = renderer.comp_frame(ItemId(composition_id), time);
    let rgba = image.to_rgba8_over(comp.background);
    let mut output = Vec::with_capacity(8 + rgba.len());
    output.extend_from_slice(&image.width.to_be_bytes());
    output.extend_from_slice(&image.height.to_be_bytes());
    output.extend_from_slice(&rgba);
    Ok(output)
}

fn render_frame_rgba(source: &str, composition_id: u64, frame: i64, max_side: u32) -> Result<Vec<u8>, String> {
    let project: Project = serde_json::from_str(source)
        .map_err(|error| format!("Invalid EffectCraft project: {error}"))?;
    let footage = MEDIA_POOL.get_or_init(MediaPool::new);
    render_parsed_frame(&project, footage, composition_id, frame, max_side)
}

#[no_mangle]
pub extern "system" fn Java_ai_effectcraft_android_engine_NativeProjectBridge_renderEcprojFrameNative(
    mut env: JNIEnv, _class: JClass, source: JString, composition_id: jlong, frame: jlong, max_side: jlong,
) -> jni::sys::jbyteArray {
    let source: String = match env.get_string(&source) {
        Ok(value) => value.into(),
        Err(_) => return env.byte_array_from_slice(&[]).map_or(std::ptr::null_mut(), |v| v.into_raw()),
    };
    let result = if composition_id < 0 || max_side < 0 {
        Err("Composition ID and preview size must be non-negative".to_owned())
    } else {
        render_frame_rgba(&source, composition_id as u64, frame as i64, max_side as u32)
    };
    match result {
        Ok(bytes) => env.byte_array_from_slice(&bytes).map_or(std::ptr::null_mut(), |v| v.into_raw()),
        Err(_) => env.byte_array_from_slice(&[]).map_or(std::ptr::null_mut(), |v| v.into_raw()),
    }
}

fn session_handle(id: jlong) -> Result<u64, String> {
    if id <= 0 {
        Err("Native session handle must be positive".into())
    } else {
        Ok(id as u64)
    }
}

#[no_mangle]
pub extern "system" fn Java_ai_effectcraft_android_engine_NativeSession_createSessionNative(
    mut env: JNIEnv, _class: JClass, source: JString, max_side: jlong,
) -> jlong {
    let source: String = match env.get_string(&source) {
        Ok(value) => value.into(),
        Err(_) => return 0,
    };
    if max_side < 0 || max_side > 4096 {
        return 0;
    }
    create_session(&source, max_side as u32).map_or(0, |id| id as jlong)
}

#[no_mangle]
pub extern "system" fn Java_ai_effectcraft_android_engine_NativeSession_renderSessionFrameNative(
    env: JNIEnv, _class: JClass, handle: jlong, composition_id: jlong, frame: jlong,
) -> jni::sys::jbyteArray {
    let result = session_handle(handle)
        .and_then(lookup_session)
        .and_then(|session| {
            if composition_id < 0 {
                Err("Composition ID must be non-negative".to_owned())
            } else {
                render_session_frame(&session, composition_id as u64, frame as i64)
            }
        });
    match result {
        Ok(bytes) => env.byte_array_from_slice(&bytes).map_or(std::ptr::null_mut(), |v| v.into_raw()),
        Err(_) => env.byte_array_from_slice(&[]).map_or(std::ptr::null_mut(), |v| v.into_raw()),
    }
}

#[no_mangle]
pub extern "system" fn Java_ai_effectcraft_android_engine_NativeSession_updateSessionProjectNative(
    mut env: JNIEnv, _class: JClass, handle: jlong, source: JString,
) -> jboolean {
    let source: String = match env.get_string(&source) {
        Ok(value) => value.into(),
        Err(_) => return 0,
    };
    let ok = session_handle(handle)
        .and_then(lookup_session)
        .and_then(|session| update_session_project(&session, &source))
        .is_ok();
    ok as jboolean
}

#[no_mangle]
pub extern "system" fn Java_ai_effectcraft_android_engine_NativeSession_resizeSessionNative(
    _env: JNIEnv, _class: JClass, handle: jlong, max_side: jlong,
) -> jboolean {
    let ok = session_handle(handle)
        .and_then(lookup_session)
        .and_then(|session| {
            if max_side < 0 || max_side > 4096 {
                Err("Preview size must be between 0 and 4096".to_owned())
            } else {
                resize_session(&session, max_side as u32)
            }
        })
        .is_ok();
    ok as jboolean
}

#[no_mangle]
pub extern "system" fn Java_ai_effectcraft_android_engine_NativeSession_cancelSessionNative(
    _env: JNIEnv, _class: JClass, handle: jlong,
) {
    if let Ok(session) = session_handle(handle).and_then(lookup_session) {
        cancel_session(&session);
    }
}

#[no_mangle]
pub extern "system" fn Java_ai_effectcraft_android_engine_NativeSession_closeSessionNative(
    _env: JNIEnv, _class: JClass, handle: jlong,
) -> jboolean {
    // close_session itself cancels, so a render holding the Arc past close
    // discards its result instead of delivering a stale frame.
    match session_handle(handle) {
        Ok(id) => close_session(id) as jboolean,
        Err(_) => 0,
    }
}

fn layer_position(layer: &effectcraft_project::Layer) -> [f64; 2] {
    let mut position = [0.0, 0.0];
    if let Some(Node::Group(transform)) = layer.props.children.iter().find(|node| node.match_id() == "transform") {
        transform.walk("", &mut |_, property| {
            if property.match_id == "position" {
                if let Value::Vec3(value) = &property.value { position = [value[0], value[1]]; }
            }
        });
    }
    position
}

fn layer_scale(layer: &effectcraft_project::Layer) -> [f64; 2] {
    let mut scale = [100.0, 100.0];
    if let Some(Node::Group(transform)) = layer.props.children.iter().find(|node| node.match_id() == "transform") {
        transform.walk("", &mut |_, property| {
            if property.match_id == "scale" {
                if let Value::Vec3(value) = &property.value { scale = [value[0], value[1]]; }
            }
        });
    }
    scale
}

fn layer_rotation(layer: &effectcraft_project::Layer) -> f64 {
    let mut rotation = 0.0;
    if let Some(Node::Group(transform)) = layer.props.children.iter().find(|node| node.match_id() == "transform") {
        transform.walk("", &mut |_, property| {
            if property.match_id == "rotation" {
                if let Value::Scalar(value) = &property.value { rotation = *value; }
            }
        });
    }
    rotation
}

fn layer_opacity_percent(layer: &effectcraft_project::Layer) -> f64 {
    let mut opacity = None;
    if let Some(Node::Group(transform)) = layer.props.children.iter().find(|node| node.match_id() == "transform") {
        transform.walk("", &mut |_, property| {
            if property.match_id == "opacity" {
                if let Value::Scalar(value) = &property.value { opacity = Some(*value); }
            }
        });
    }
    opacity.unwrap_or(100.0)
}

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
                "visible": layer.switches.video,
                "opacityPercent": layer_opacity_percent(layer),
                "position": layer_position(layer),
                "scale": layer_scale(layer),
                "rotationDegrees": layer_rotation(layer)
            })).collect();
            json!({
                "id": id.0,
                "name": project.item(*id).map(|item| item.name.as_str()).unwrap_or("Composition"),
                "width": comp.width,
                "height": comp.height,
                "frameRateNum": comp.frame_rate.num,
                "frameRateDen": comp.frame_rate.den,
                "durationSeconds": comp.duration.seconds(),
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

fn set_layer_position(source: &str, composition_id: u64, layer_id: u64, x: f64, y: f64) -> Result<String, String> {
    if !x.is_finite() || !y.is_finite() || x.abs() > 1_000_000.0 || y.abs() > 1_000_000.0 {
        return Err("Position must be finite and within ±1,000,000 pixels".into());
    }
    let mut project: Project = serde_json::from_str(source)
        .map_err(|error| format!("Invalid EffectCraft project: {error}"))?;
    let comp = project.comp_mut(ItemId(composition_id))
        .ok_or_else(|| format!("Composition {composition_id} was not found"))?;
    let layer = comp.layers.find_mut(|layer| layer.id == LayerId(layer_id))
        .ok_or_else(|| format!("Layer {layer_id} was not found in composition {composition_id}"))?;
    let transform = layer.props.children.iter_mut().find_map(|node| match node {
        Node::Group(group) if group.match_id == "transform" => Some(group),
        _ => None,
    }).ok_or_else(|| format!("Layer {layer_id} has no Transform group"))?;
    let mut found = false;
    transform.walk_mut(&mut |property| {
        if property.match_id == "position" {
            if let Value::Vec3(current) = &property.value {
                property.set_value_at(Tick::ZERO, Value::Vec3([x, y, current[2]]));
                found = true;
            }
        }
    });
    if !found { return Err(format!("Layer {layer_id} has no 3D Position property")); }
    project.to_file_json().map_err(|error| format!("Unable to serialize project: {error}"))
}

fn set_layer_scale(source: &str, composition_id: u64, layer_id: u64, x: f64, y: f64) -> Result<String, String> {
    if !x.is_finite() || !y.is_finite() || x.abs() > 100_000.0 || y.abs() > 100_000.0 {
        return Err("Scale must be finite and within ±100,000 percent".into());
    }
    let mut project: Project = serde_json::from_str(source)
        .map_err(|error| format!("Invalid EffectCraft project: {error}"))?;
    let comp = project.comp_mut(ItemId(composition_id))
        .ok_or_else(|| format!("Composition {composition_id} was not found"))?;
    let layer = comp.layers.find_mut(|layer| layer.id == LayerId(layer_id))
        .ok_or_else(|| format!("Layer {layer_id} was not found in composition {composition_id}"))?;
    let transform = layer.props.sub_mut("transform")
        .ok_or_else(|| format!("Layer {layer_id} has no Transform group"))?;
    let property = transform.prop_mut("scale")
        .ok_or_else(|| format!("Layer {layer_id} has no Scale property"))?;
    let z = match &property.value {
        Value::Vec3(current) => current[2],
        _ => return Err(format!("Layer {layer_id} Scale is not a 3D vector")),
    };
    property.set_value_at(Tick::ZERO, Value::Vec3([x, y, z]));
    project.to_file_json().map_err(|error| format!("Unable to serialize project: {error}"))
}

fn set_layer_rotation(source: &str, composition_id: u64, layer_id: u64, degrees: f64) -> Result<String, String> {
    if !degrees.is_finite() || degrees.abs() > 360_000.0 {
        return Err("Rotation must be finite and within ±360,000 degrees".into());
    }
    let mut project: Project = serde_json::from_str(source)
        .map_err(|error| format!("Invalid EffectCraft project: {error}"))?;
    let comp = project.comp_mut(ItemId(composition_id))
        .ok_or_else(|| format!("Composition {composition_id} was not found"))?;
    let layer = comp.layers.find_mut(|layer| layer.id == LayerId(layer_id))
        .ok_or_else(|| format!("Layer {layer_id} was not found in composition {composition_id}"))?;
    let transform = layer.props.sub_mut("transform")
        .ok_or_else(|| format!("Layer {layer_id} has no Transform group"))?;
    let property = transform.prop_mut("rotation")
        .ok_or_else(|| format!("Layer {layer_id} has no Rotation property"))?;
    if !matches!(&property.value, Value::Scalar(_)) {
        return Err(format!("Layer {layer_id} Rotation is not scalar"));
    }
    property.set_value_at(Tick::ZERO, Value::Scalar(degrees));
    project.to_file_json().map_err(|error| format!("Unable to serialize project: {error}"))
}

#[no_mangle]
pub extern "system" fn Java_ai_effectcraft_android_engine_NativeProjectBridge_setLayerScaleEcprojNative(
    mut env: JNIEnv, _class: JClass, source: JString, composition_id: jlong, layer_id: jlong, x: jni::sys::jdouble, y: jni::sys::jdouble,
) -> jstring {
    let source: String = match env.get_string(&source) {
        Ok(value) => value.into(),
        Err(error) => return env.new_string(json!({"valid": false, "error": error.to_string()}).to_string()).map_or(std::ptr::null_mut(), |v| v.into_raw()),
    };
    let result = if composition_id < 0 || layer_id < 0 { Err("Composition and layer IDs must be non-negative".to_owned()) }
        else { set_layer_scale(&source, composition_id as u64, layer_id as u64, x as f64, y as f64) };
    let output = match result { Ok(project_json) => json!({"valid": true, "project": project_json}).to_string(), Err(error) => json!({"valid": false, "error": error}).to_string() };
    env.new_string(output).map_or(std::ptr::null_mut(), |value| value.into_raw())
}

#[no_mangle]
pub extern "system" fn Java_ai_effectcraft_android_engine_NativeProjectBridge_setLayerRotationEcprojNative(
    mut env: JNIEnv, _class: JClass, source: JString, composition_id: jlong, layer_id: jlong, degrees: jni::sys::jdouble,
) -> jstring {
    let source: String = match env.get_string(&source) {
        Ok(value) => value.into(),
        Err(error) => return env.new_string(json!({"valid": false, "error": error.to_string()}).to_string()).map_or(std::ptr::null_mut(), |v| v.into_raw()),
    };
    let result = if composition_id < 0 || layer_id < 0 { Err("Composition and layer IDs must be non-negative".to_owned()) }
        else { set_layer_rotation(&source, composition_id as u64, layer_id as u64, degrees as f64) };
    let output = match result { Ok(project_json) => json!({"valid": true, "project": project_json}).to_string(), Err(error) => json!({"valid": false, "error": error}).to_string() };
    env.new_string(output).map_or(std::ptr::null_mut(), |value| value.into_raw())
}

#[no_mangle]
pub extern "system" fn Java_ai_effectcraft_android_engine_NativeProjectBridge_setLayerPositionEcprojNative(
    mut env: JNIEnv, _class: JClass, source: JString, composition_id: jlong, layer_id: jlong, x: jni::sys::jdouble, y: jni::sys::jdouble,
) -> jstring {
    let source: String = match env.get_string(&source) {
        Ok(value) => value.into(),
        Err(error) => return env.new_string(json!({"valid": false, "error": error.to_string()}).to_string()).map_or(std::ptr::null_mut(), |v| v.into_raw()),
    };
    let result = if composition_id < 0 || layer_id < 0 { Err("Composition and layer IDs must be non-negative".to_owned()) }
        else { set_layer_position(&source, composition_id as u64, layer_id as u64, x as f64, y as f64) };
    let output = match result { Ok(project_json) => json!({"valid": true, "project": project_json}).to_string(), Err(error) => json!({"valid": false, "error": error}).to_string() };
    env.new_string(output).map_or(std::ptr::null_mut(), |value| value.into_raw())
}

fn set_layer_opacity(source: &str, composition_id: u64, layer_id: u64, opacity_percent: f64) -> Result<String, String> {
    if !opacity_percent.is_finite() || !(0.0..=100.0).contains(&opacity_percent) {
        return Err("Opacity must be between 0 and 100".into());
    }
    let mut project: Project = serde_json::from_str(source)
        .map_err(|error| format!("Invalid EffectCraft project: {error}"))?;
    let comp = project.comp_mut(ItemId(composition_id))
        .ok_or_else(|| format!("Composition {composition_id} was not found"))?;
    let layer = comp.layers.find_mut(|layer| layer.id == LayerId(layer_id))
        .ok_or_else(|| format!("Layer {layer_id} was not found in composition {composition_id}"))?;
    let transform = layer.props.children.iter_mut().find_map(|node| match node {
        Node::Group(group) if group.match_id == "transform" => Some(group),
        _ => None,
    }).ok_or_else(|| format!("Layer {layer_id} has no Transform group"))?;
    let mut found = false;
    transform.walk_mut(&mut |property| {
        if property.match_id == "opacity" {
            property.set_value_at(Tick::ZERO, Value::Scalar(opacity_percent));
            found = true;
        }
    });
    if !found { return Err(format!("Layer {layer_id} has no Transform opacity property")); }
    project.to_file_json().map_err(|error| format!("Unable to serialize project: {error}"))
}

fn relink_footage(source: &str, item_id: u64, path: &str) -> Result<String, String> {
    let normalized = path.trim();
    if normalized.is_empty() { return Err("Media path cannot be blank".into()); }
    let mut project: Project = serde_json::from_str(source)
        .map_err(|error| format!("Invalid EffectCraft project: {error}"))?;
    let item = project.item_mut(ItemId(item_id))
        .ok_or_else(|| format!("Project item {item_id} was not found"))?;
    let footage = match &mut item.kind {
        ItemKind::Footage(footage) => footage,
        _ => return Err(format!("Project item {item_id} is not footage")),
    };
    footage.path = normalized.to_owned();
    footage.missing = false;
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
pub extern "system" fn Java_ai_effectcraft_android_engine_NativeProjectBridge_setLayerOpacityEcprojNative(
    mut env: JNIEnv, _class: JClass, source: JString, composition_id: jlong, layer_id: jlong, opacity: jni::sys::jdouble,
) -> jstring {
    let source: String = match env.get_string(&source) {
        Ok(value) => value.into(),
        Err(error) => return env.new_string(json!({"valid": false, "error": error.to_string()}).to_string()).map_or(std::ptr::null_mut(), |v| v.into_raw()),
    };
    let result = if composition_id < 0 || layer_id < 0 { Err("Composition and layer IDs must be non-negative".to_owned()) }
        else { set_layer_opacity(&source, composition_id as u64, layer_id as u64, opacity as f64) };
    let output = match result { Ok(project_json) => json!({"valid": true, "project": project_json}).to_string(), Err(error) => json!({"valid": false, "error": error}).to_string() };
    env.new_string(output).map_or(std::ptr::null_mut(), |value| value.into_raw())
}

#[no_mangle]
pub extern "system" fn Java_ai_effectcraft_android_engine_NativeProjectBridge_relinkFootageEcprojNative(
    mut env: JNIEnv, _class: JClass, source: JString, item_id: jlong, path: JString,
) -> jstring {
    let source: String = match env.get_string(&source) {
        Ok(value) => value.into(),
        Err(error) => return env.new_string(json!({"valid": false, "error": error.to_string()}).to_string()).map_or(std::ptr::null_mut(), |v| v.into_raw()),
    };
    let path: String = match env.get_string(&path) {
        Ok(value) => value.into(),
        Err(error) => return env.new_string(json!({"valid": false, "error": error.to_string()}).to_string()).map_or(std::ptr::null_mut(), |v| v.into_raw()),
    };
    let result = if item_id < 0 { Err("Project item ID must be non-negative".to_owned()) }
        else { relink_footage(&source, item_id as u64, &path) };
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
        assert_eq!(reordered.comp(comp_id).unwrap().layers.iter().map(|l| l.name.as_str()).collect::<Vec<_>>(), vec!["C", "B", "A"]);

        let hidden = set_layer_visibility(&moved, comp_id.0, ids[2], false).unwrap();
        let hidden_project: Project = serde_json::from_str(&hidden).unwrap();
        assert!(!hidden_project.comp(comp_id).unwrap().layers[0].switches.video);

        let deleted = delete_layer(&hidden, comp_id.0, ids[0]).unwrap();
        let final_project: Project = serde_json::from_str(&deleted).unwrap();
        assert_eq!(final_project.comp(comp_id).unwrap().layers.iter().map(|l| l.name.as_str()).collect::<Vec<_>>(), vec!["C", "B"]);
        assert_eq!(serde_json::from_str::<Project>(&final_project.to_file_json().unwrap()).unwrap(), final_project);
    }

    #[test]
    fn canonical_transform_position_updates_real_property_and_preserves_z() {
        use effectcraft_project::{build, Comp, ItemKind, LayerSource, Value};
        use effectcraft_color::Label;
        use effectcraft_time::{FrameRate, Tick};

        let mut project = Project::default();
        let comp = Comp::new(640, 360, FrameRate::FPS_30, Tick::from_seconds_f64(3.0));
        let comp_id = project.add_item("Main", Label::Sandstone, None, ItemKind::Comp(comp.clone().into()));
        let layer = build::layer(&mut project, &comp, "Text", LayerSource::Text, (640, 360), None);
        let layer_id = layer.id.0;
        project.comp_mut(comp_id).unwrap().layers.push(layer);
        let source = project.to_file_json().unwrap();

        let updated_json = set_layer_position(&source, comp_id.0, layer_id, 123.5, 456.25).unwrap();
        let updated: Project = serde_json::from_str(&updated_json).unwrap();
        let layer = &updated.comp(comp_id).unwrap().layers[0];
        let transform = layer.props.children.iter().find_map(|node| match node {
            Node::Group(group) if group.match_id == "transform" => Some(group),
            _ => None,
        }).unwrap();
        let position = transform.children.iter().find_map(|node| match node {
            Node::Prop(property) if property.match_id == "position" => Some(&property.value),
            _ => None,
        }).unwrap();
        assert_eq!(position, &Value::Vec3([123.5, 456.25, 0.0]));
        assert!(set_layer_position(&source, comp_id.0, layer_id, f64::NAN, 0.0).is_err());
        assert!(set_layer_position(&source, comp_id.0, 999, 0.0, 0.0).is_err());
    }

    #[test]
    fn canonical_transform_scale_and_rotation_update_real_properties() {
        use effectcraft_project::{build, Comp, ItemKind, LayerSource, Node, Value};
        use effectcraft_color::Label;
        use effectcraft_time::{FrameRate, Tick};

        let mut project = Project::default();
        let comp = Comp::new(640, 360, FrameRate::FPS_30, Tick::from_seconds_f64(3.0));
        let comp_id = project.add_item("Main", Label::Sandstone, None, ItemKind::Comp(comp.clone().into()));
        let layer = build::layer(&mut project, &comp, "Text", LayerSource::Text, (640, 360), None);
        let layer_id = layer.id.0;
        project.comp_mut(comp_id).unwrap().layers.push(layer);
        let source = project.to_file_json().unwrap();

        let updated_json = set_layer_scale(&source, comp_id.0, layer_id, 125.0, 80.0).unwrap();
        let rotated_json = set_layer_rotation(&updated_json, comp_id.0, layer_id, 42.5).unwrap();
        let updated: Project = serde_json::from_str(&rotated_json).unwrap();
        let transform = updated.comp(comp_id).unwrap().layers[0].props.sub("transform").unwrap();
        assert_eq!(transform.prop("scale").unwrap().value, Value::Vec3([125.0, 80.0, 100.0]));
        assert_eq!(transform.prop("rotation").unwrap().value, Value::Scalar(42.5));
        let metadata: serde_json::Value = serde_json::from_str(&inspect_project(&rotated_json).unwrap()).unwrap();
        assert_eq!(metadata["compositions"][0]["layers"][0]["scale"], json!([125.0, 80.0]));
        assert_eq!(metadata["compositions"][0]["layers"][0]["rotationDegrees"], 42.5);
        assert!(set_layer_scale(&source, comp_id.0, layer_id, f64::NAN, 100.0).is_err());
        assert!(set_layer_scale(&source, comp_id.0, layer_id, 100_001.0, 100.0).is_err());
        assert!(set_layer_rotation(&source, comp_id.0, layer_id, f64::INFINITY).is_err());
        assert!(set_layer_rotation(&source, comp_id.0, 999, 0.0).is_err());
    }

    #[test]
    fn canonical_transform_opacity_updates_real_property_and_roundtrips() {
        use effectcraft_project::{build, Comp, ItemKind, LayerSource, Node, Value};
        use effectcraft_color::Label;
        use effectcraft_time::{FrameRate, Tick};

        let mut project = Project::default();
        let comp = Comp::new(640, 360, FrameRate::FPS_30, Tick::from_seconds_f64(3.0));
        let comp_id = project.add_item("Main", Label::Sandstone, None, ItemKind::Comp(comp.clone().into()));
        let layer = build::layer(&mut project, &comp, "Text", LayerSource::Text, (640, 360), None);
        let layer_id = layer.id.0;
        project.comp_mut(comp_id).unwrap().layers.push(layer);
        let source = project.to_file_json().unwrap();

        let updated_json = set_layer_opacity(&source, comp_id.0, layer_id, 35.0).unwrap();
        let updated: Project = serde_json::from_str(&updated_json).unwrap();
        let layer = &updated.comp(comp_id).unwrap().layers[0];
        let transform = layer.props.children.iter().find_map(|node| match node {
            Node::Group(group) if group.match_id == "transform" => Some(group),
            _ => None,
        }).unwrap();
        let opacity = transform.children.iter().find_map(|node| match node {
            Node::Prop(property) if property.match_id == "opacity" => Some(&property.value),
            _ => None,
        }).unwrap();
        assert_eq!(opacity, &Value::Scalar(35.0));
        assert!(set_layer_opacity(&source, comp_id.0, layer_id, 101.0).is_err());
        assert!(set_layer_opacity(&source, comp_id.0, 999, 50.0).is_err());
    }

    #[test]
    fn relink_footage_updates_path_and_clears_missing_flag() {
        use effectcraft_project::{Footage, ItemKind};
        use effectcraft_color::Label;

        let mut project = Project::default();
        let mut footage = Footage::default();
        footage.path = "missing/old.mp4".into();
        footage.missing = true;
        let item_id = project.add_item("Clip", Label::Sandstone, None, ItemKind::Footage(footage));
        let source = project.to_file_json().unwrap();
        let relinked_json = relink_footage(&source, item_id.0, "/data/user/0/app/files/assets/clip.mp4").unwrap();
        let relinked: Project = serde_json::from_str(&relinked_json).unwrap();
        let item = relinked.item(item_id).unwrap();
        assert!(matches!(&item.kind, ItemKind::Footage(_)));
        if let ItemKind::Footage(media) = &item.kind {
            assert_eq!(media.path, "/data/user/0/app/files/assets/clip.mp4");
            assert!(!media.missing);
        }
        assert!(relink_footage(&source, 999, "/tmp/clip.mp4").is_err());
        assert!(relink_footage(&source, item_id.0, " ").is_err());
    }

    #[test]
    fn cpu_renderer_returns_bounded_rgba_frame_for_canonical_composition() {
        use effectcraft_project::{build, Comp, ItemKind, LayerSource};
        use effectcraft_color::Label;
        use effectcraft_time::{FrameRate, Tick};

        let mut project = Project::default();
        let comp = Comp::new(320, 180, FrameRate::FPS_30, Tick::from_seconds_f64(3.0));
        let comp_id = project.add_item("Preview", Label::Sandstone, None, ItemKind::Comp(comp.clone().into()));
        let layer = build::layer(&mut project, &comp, "Text", LayerSource::Text, (320, 180), None);
        project.comp_mut(comp_id).unwrap().layers.push(layer);
        let source = project.to_file_json().unwrap();

        let bytes = render_frame_rgba(&source, comp_id.0, 0, 160).unwrap();
        let width = u32::from_be_bytes(bytes[0..4].try_into().unwrap());
        let height = u32::from_be_bytes(bytes[4..8].try_into().unwrap());
        assert_eq!((width, height), (160, 90));
        assert_eq!(bytes.len(), 8 + (width * height * 4) as usize);
        assert!(render_frame_rgba(&source, 999, 0, 160).is_err());
        assert!(render_frame_rgba(&source, comp_id.0, -1, 160).is_err());
    }

    #[test]
    fn cpu_renderer_decodes_offline_png_footage_from_app_private_path() {
        use effectcraft_project::{build, Comp, Footage, FootageKind, ItemKind, LayerSource};
        use effectcraft_color::Label;
        use effectcraft_time::{FrameRate, Tick};
        use image::ImageEncoder;

        let path = std::env::temp_dir().join(format!("effectcraft-preview-{}.png", std::process::id()));
        let mut png = Vec::new();
        image::codecs::png::PngEncoder::new(&mut png)
            .write_image(&[255, 0, 0, 255], 1, 1, image::ExtendedColorType::Rgba8)
            .unwrap();
        std::fs::write(&path, png).unwrap();

        let mut project = Project::default();
        let comp = Comp::new(32, 32, FrameRate::FPS_30, Tick::from_seconds_f64(1.0));
        let comp_id = project.add_item("Media preview", Label::Sandstone, None, ItemKind::Comp(comp.clone().into()));
        let footage = Footage {
            path: path.to_string_lossy().into_owned(),
            kind: FootageKind::Still,
            width: 1,
            height: 1,
            has_video: true,
            missing: false,
            ..Footage::default()
        };
        let item_id = project.add_item("Red pixel", Label::Sandstone, None, ItemKind::Footage(footage));
        let layer = build::layer(&mut project, &comp, "Red pixel", LayerSource::Footage { item: item_id }, (32, 32), None);
        project.comp_mut(comp_id).unwrap().layers.push(layer);

        let bytes = render_frame_rgba(&project.to_file_json().unwrap(), comp_id.0, 0, 32).unwrap();
        assert!(bytes[8..].chunks_exact(4).any(|pixel| pixel[0] > 200 && pixel[1] < 20 && pixel[2] < 20 && pixel[3] > 200));
        std::fs::remove_file(path).unwrap();
    }

    #[test]
    fn rejects_non_project_json() {
        let result = inspect_project("{}");
        assert!(result.is_err());
    }

    fn text_comp_project(width: u32, height: u32) -> (String, u64) {
        use effectcraft_project::{build, Comp, ItemKind, LayerSource};
        use effectcraft_color::Label;
        use effectcraft_time::{FrameRate, Tick};

        let mut project = Project::default();
        let comp = Comp::new(width, height, FrameRate::FPS_30, Tick::from_seconds_f64(3.0));
        let comp_id = project.add_item("Preview", Label::Sandstone, None, ItemKind::Comp(comp.clone().into()));
        let layer = build::layer(&mut project, &comp, "Text", LayerSource::Text, (width, height), None);
        project.comp_mut(comp_id).unwrap().layers.push(layer);
        (project.to_file_json().unwrap(), comp_id.0)
    }

    fn frame_dimensions(bytes: &[u8]) -> (u32, u32) {
        assert!(bytes.len() >= 8, "frame envelope must carry a dimension header");
        (
            u32::from_be_bytes(bytes[0..4].try_into().unwrap()),
            u32::from_be_bytes(bytes[4..8].try_into().unwrap()),
        )
    }

    #[test]
    fn session_renders_without_reparsing_and_rejects_bad_handles() {
        let (source, comp_id) = text_comp_project(320, 180);
        let handle = create_session(&source, 160).unwrap();
        let session = lookup_session(handle).unwrap();
        let bytes = render_session_frame(&session, comp_id, 0).unwrap();
        assert_eq!(frame_dimensions(&bytes), (160, 90));
        assert_eq!(bytes.len(), 8 + 160 * 90 * 4);
        // A second render through the same session is stable and independent.
        let again = render_session_frame(&session, comp_id, 0).unwrap();
        assert_eq!(again, bytes);
        assert!(render_session_frame(&session, 999, 0).is_err());
        assert!(render_session_frame(&session, comp_id, -1).is_err());
        assert!(lookup_session(handle + 1_000_000).is_err());
        assert!(close_session(handle));
    }

    #[test]
    fn session_create_rejects_bad_json_and_oversized_preview() {
        assert!(create_session("{}", 160).is_err());
        assert!(create_session("not json", 160).is_err());
        let (source, _) = text_comp_project(320, 180);
        assert!(create_session(&source, 4097).is_err());
    }

    #[test]
    fn session_resize_changes_output_dimensions() {
        let (source, comp_id) = text_comp_project(320, 180);
        let handle = create_session(&source, 160).unwrap();
        let session = lookup_session(handle).unwrap();
        assert_eq!(frame_dimensions(&render_session_frame(&session, comp_id, 0).unwrap()), (160, 90));
        assert!(resize_session(&session, 80).is_ok());
        assert_eq!(frame_dimensions(&render_session_frame(&session, comp_id, 0).unwrap()), (80, 45));
        assert!(resize_session(&session, 4097).is_err());
        assert!(resize_session(&session, 0).is_ok());
        assert_eq!(frame_dimensions(&render_session_frame(&session, comp_id, 0).unwrap()), (320, 180));
        assert!(close_session(handle));
    }

    #[test]
    fn session_update_swaps_project_and_rejects_bad_replacement() {
        let (first, first_comp) = text_comp_project(320, 180);
        let (second, second_comp) = text_comp_project(200, 100);
        let handle = create_session(&first, 160).unwrap();
        let session = lookup_session(handle).unwrap();
        assert_eq!(frame_dimensions(&render_session_frame(&session, first_comp, 0).unwrap()), (160, 90));
        assert!(update_session_project(&session, &second).is_ok());
        assert_eq!(frame_dimensions(&render_session_frame(&session, second_comp, 0).unwrap()), (160, 80));
        // The old project's frame must be gone: the old ID now renders the
        // new project's composition (IDs restart per project) or errors if
        // the ID schemes differ — but never the old 160x90 frame.
        match render_session_frame(&session, first_comp, 0) {
            Ok(bytes) => assert_ne!(frame_dimensions(&bytes), (160, 90)),
            Err(_) => {}
        }
        assert!(update_session_project(&session, "not json").is_err());
        // A failed replacement leaves the current project rendering.
        assert_eq!(frame_dimensions(&render_session_frame(&session, second_comp, 0).unwrap()), (160, 80));
        assert!(close_session(handle));
    }

    #[test]
    fn session_cancel_is_sticky_and_close_is_idempotent() {
        let (source, comp_id) = text_comp_project(320, 180);
        let handle = create_session(&source, 160).unwrap();
        let session = lookup_session(handle).unwrap();
        assert!(render_session_frame(&session, comp_id, 0).is_ok());
        cancel_session(&session);
        assert!(render_session_frame(&session, comp_id, 0).is_err());
        assert!(update_session_project(&session, &source).is_err());
        assert!(resize_session(&session, 80).is_err());
        cancel_session(&session);
        assert!(close_session(handle));
        assert!(!close_session(handle));
        assert!(lookup_session(handle).is_err());
        assert!(render_session_frame(&session, comp_id, 0).is_err());
    }

    #[test]
    fn session_close_cancels_in_flight_holders_and_isolates_sessions() {
        let (first, first_comp) = text_comp_project(320, 180);
        let (second, second_comp) = text_comp_project(200, 100);
        let first_handle = create_session(&first, 160).unwrap();
        let second_handle = create_session(&second, 160).unwrap();
        assert_ne!(first_handle, second_handle);
        let first_session = lookup_session(first_handle).unwrap();
        let second_session = lookup_session(second_handle).unwrap();
        assert!(close_session(first_handle));
        // The held Arc still exists, but close cancelled it: no stale frame.
        assert!(render_session_frame(&first_session, first_comp, 0).is_err());
        assert_eq!(
            frame_dimensions(&render_session_frame(&second_session, second_comp, 0).unwrap()),
            (160, 80)
        );
        assert!(close_session(second_handle));
    }

    #[test]
    fn session_renders_concurrently_from_many_threads() {
        let (source, comp_id) = text_comp_project(320, 180);
        let handle = create_session(&source, 160).unwrap();
        let session = lookup_session(handle).unwrap();
        let results: Vec<_> = std::thread::scope(|scope| {
            let mut workers = Vec::new();
            for _ in 0..8 {
                let session = Arc::clone(&session);
                workers.push(scope.spawn(move || render_session_frame(&session, comp_id, 0)));
            }
            workers.into_iter().map(|worker| worker.join().unwrap()).collect()
        });
        assert_eq!(results.len(), 8);
        for result in &results {
            let bytes = result.as_ref().unwrap();
            assert_eq!(frame_dimensions(bytes), (160, 90));
        }
        assert!(results.windows(2).all(|pair| pair[0].as_ref().unwrap() == pair[1].as_ref().unwrap()));
        assert!(close_session(handle));
    }

    #[test]
    fn session_decodes_offline_png_footage_through_own_media_pool() {
        use effectcraft_project::{build, Comp, Footage, FootageKind, ItemKind, LayerSource};
        use effectcraft_color::Label;
        use effectcraft_time::{FrameRate, Tick};
        use image::ImageEncoder;

        let path = std::env::temp_dir().join(format!("effectcraft-session-{}.png", std::process::id()));
        let mut png = Vec::new();
        image::codecs::png::PngEncoder::new(&mut png)
            .write_image(&[255, 0, 0, 255], 1, 1, image::ExtendedColorType::Rgba8)
            .unwrap();
        std::fs::write(&path, png).unwrap();

        let mut project = Project::default();
        let comp = Comp::new(32, 32, FrameRate::FPS_30, Tick::from_seconds_f64(1.0));
        let comp_id = project.add_item("Media preview", Label::Sandstone, None, ItemKind::Comp(comp.clone().into()));
        let footage = Footage {
            path: path.to_string_lossy().into_owned(),
            kind: FootageKind::Still,
            width: 1,
            height: 1,
            has_video: true,
            missing: false,
            ..Footage::default()
        };
        let item_id = project.add_item("Red pixel", Label::Sandstone, None, ItemKind::Footage(footage));
        let layer = build::layer(&mut project, &comp, "Red pixel", LayerSource::Footage { item: item_id }, (32, 32), None);
        project.comp_mut(comp_id).unwrap().layers.push(layer);

        let handle = create_session(&project.to_file_json().unwrap(), 32).unwrap();
        let session = lookup_session(handle).unwrap();
        let bytes = render_session_frame(&session, comp_id.0, 0).unwrap();
        assert!(bytes[8..].chunks_exact(4).any(|pixel| pixel[0] > 200 && pixel[1] < 20 && pixel[2] < 20 && pixel[3] > 200));
        assert!(close_session(handle));
        std::fs::remove_file(path).unwrap();
    }
}
