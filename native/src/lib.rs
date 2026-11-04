use effectcraft_project::Project;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jlong, jstring};
use jni::JNIEnv;
use serde_json::json;
use effectcraft_project::{ItemId, ItemKind, LayerId, Node, Value};
use effectcraft_project::keyframe::Interp;
use effectcraft_gpu::Gpu;
use effectcraft_media::MediaPool;
use effectcraft_render::{Accelerator, Backend, RenderOpts, Renderer};
use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, OnceLock, RwLock};
use effectcraft_time::Tick;

static MEDIA_POOL: OnceLock<MediaPool> = OnceLock::new();

/// Android media-pool frame-cache budget: 256 MiB per pool, replacing the
/// upstream 1 GiB default, which is not a phone budget. Every pool the
/// bridge creates — one per session plus the shared stateless pool — uses
/// this value, and [`session_media_pool_stats`] reports it so the Kotlin
/// side can assert the live budget instead of assuming it. The upstream
/// LRU evicts least-recently-used frames when the budget shrinks, always
/// keeping the newest frame; oversized single frames are still decoded
/// (they are needed for the current frame) but are not retained past it.
const ANDROID_MEDIA_POOL_BUDGET_BYTES: usize = 256 * 1024 * 1024;

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
    /// This session's GPU attachment (see the backend section below): a
    /// cheap clone of the shared wgpu device, or Lost after a device loss.
    gpu_state: Mutex<SessionGpu>,
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
        footage: MediaPool::with_budget(ANDROID_MEDIA_POOL_BUDGET_BYTES),
        render_lock: Mutex::new(()),
        cancelled: AtomicBool::new(false),
        max_side: RwLock::new(max_side),
        gpu_state: Mutex::new(SessionGpu::Unattached),
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

/// Render backend: the upstream wgpu GPU compositor when a usable device
/// exists, the upstream CPU compositor otherwise — never a fake frame.
///
/// One decision lives in one place: [`choose_backend`] is the only mapping
/// from an accelerator to a (Backend, accel) pair, and every render path
/// (session and stateless) goes through [`render_parsed_frame`], which
/// takes that pair. Upstream `Renderer::comp_frame` already falls back to
/// the CPU for any frame the GPU declines, so a GPU that declines a frame
/// still yields real CPU pixels; a GPU that reports unavailable before a
/// render is never asked at all.
///
/// Device model:
/// - The process holds at most one shared wgpu device ([`SHARED_GPU`]).
///   Sessions cache a cheap clone of it; clones share the upstream
///   context, so a real driver device loss retires every clone at once.
/// - Probing is lazy: it happens on first render/status/recover, never at
///   session open, so opening a session stays cheap and a missing Vulkan
///   loader costs nothing until a frame is actually wanted. A failed probe
///   is cached with its reason, so renders never pay for a fresh device
///   init on every frame.
/// - Loss: a cached device that reports unavailable is dropped, the
///   session is marked Lost, and renders continue on the CPU. Recovery is
///   explicit ([`recover_session_gpu`]): it re-probes the shared device
///   and clears the session's Lost flag, so the next render re-attaches.
///   A brand-new session also recovers on its own, because its first
///   render re-probes a shared slot whose device died.
/// - Lock order is render_lock, then session gpu_state, then the shared
///   slot; status/recover never take render_lock, so no cycle exists.
///   A poisoned lock fails closed to the CPU path, never to a crash.
enum SessionGpu {
    /// Never resolved for this session; the first render resolves it.
    Unattached,
    /// A live clone of the shared device.
    Attached(Gpu),
    /// A device loss was observed (or simulated); CPU-only until recovery.
    Lost(String),
}

enum SharedGpu {
    /// No probe has run yet.
    Unprobed,
    /// A live shared device.
    Live(Gpu),
    /// The last probe failed, with why (for status reporting).
    Dead(String),
}

static SHARED_GPU: std::sync::LazyLock<Mutex<SharedGpu>> =
    std::sync::LazyLock::new(|| Mutex::new(SharedGpu::Unprobed));

/// The only accelerator-to-backend mapping. A live accelerator renders on
/// the GPU; anything else (none, or retired) renders on the CPU.
fn choose_backend(accel: Option<&dyn Accelerator>) -> (Backend, Option<&dyn Accelerator>) {
    match accel {
        Some(accel) if accel.available() => (Backend::Gpu, Some(accel)),
        _ => (Backend::Cpu, None),
    }
}

/// Resolve the shared device: a live one is cloned, a dead one is reported
/// without re-probing (a failed probe stays cached), and an unprobed or
/// died-live slot is probed once under the slot lock.
fn resolve_shared_gpu() -> Option<Gpu> {
    let mut slot = match SHARED_GPU.lock() {
        Ok(slot) => slot,
        Err(_) => return None,
    };
    match &*slot {
        SharedGpu::Live(device) if device.available() => Some(device.clone()),
        SharedGpu::Dead(_) => None,
        _ => match Gpu::try_headless() {
            Ok(device) => {
                let clone = device.clone();
                *slot = SharedGpu::Live(device);
                Some(clone)
            }
            Err(reason) => {
                *slot = SharedGpu::Dead(reason);
                None
            }
        },
    }
}

/// The shared slot's last probe failure, if any, for status reporting.
fn shared_gpu_failure() -> Option<String> {
    match SHARED_GPU.lock() {
        Ok(slot) => match &*slot {
            SharedGpu::Dead(reason) => Some(reason.clone()),
            _ => None,
        },
        Err(_) => None,
    }
}

/// Force a fresh device probe regardless of the cached slot state.
fn recover_shared_gpu() -> bool {
    let mut slot = match SHARED_GPU.lock() {
        Ok(slot) => slot,
        Err(_) => return false,
    };
    match Gpu::try_headless() {
        Ok(device) => {
            *slot = SharedGpu::Live(device);
            true
        }
        Err(reason) => {
            *slot = SharedGpu::Dead(reason);
            false
        }
    }
}

/// Resolve this session's GPU handle: an attached live device is reused, an
/// attached dead one is dropped and the session is marked Lost, a Lost
/// session stays CPU-only, and an Unattached session attaches from the
/// shared slot (probing it if it was never probed).
fn session_gpu(session: &NativeSession) -> Option<Gpu> {
    let mut state = match session.gpu_state.lock() {
        Ok(state) => state,
        Err(_) => return None,
    };
    match &*state {
        SessionGpu::Attached(device) if device.available() => Some(device.clone()),
        SessionGpu::Attached(_) => {
            *state = SessionGpu::Lost(
                "GPU device lost (wgpu reports the device unavailable)".to_owned(),
            );
            None
        }
        SessionGpu::Lost(_) => None,
        SessionGpu::Unattached => match resolve_shared_gpu() {
            Some(device) => {
                let clone = device.clone();
                *state = SessionGpu::Attached(device);
                Some(clone)
            }
            None => None,
        },
    }
}

/// Clear a Lost flag — or a dead Attached handle, which is what a real
/// mid-session device loss leaves behind — without forcing a shared
/// re-probe, then resolve from the shared slot. True when the session ends
/// up GPU-backed. Without the dead-Attached reset, recovery after a real
/// loss would take two recover calls instead of one.
fn reattach_session_gpu(session: &NativeSession) -> bool {
    if let Ok(mut state) = session.gpu_state.lock() {
        match &*state {
            SessionGpu::Lost(_) => *state = SessionGpu::Unattached,
            SessionGpu::Attached(device) if !device.available() => {
                *state = SessionGpu::Unattached;
            }
            _ => {}
        }
    }
    session_gpu(session).is_some()
}

/// Full recovery: re-probe the shared device, then re-attach this session.
/// A healthy attached device short-circuits without a wasted re-probe.
fn recover_session_gpu(session: &NativeSession) -> bool {
    if let Ok(state) = session.gpu_state.lock() {
        if let SessionGpu::Attached(device) = &*state {
            if device.available() {
                return true;
            }
        }
    }
    recover_shared_gpu();
    reattach_session_gpu(session)
}

/// Drop this session's GPU handle and mark it Lost. This is the diagnostic
/// hook behind the JNI simulate-loss call: it models a driver device loss
/// for the session without touching the shared device, so recovery can
/// re-attach from the still-live shared slot.
fn simulate_session_gpu_loss(session: &NativeSession) {
    if let Ok(mut state) = session.gpu_state.lock() {
        *state = SessionGpu::Lost("Simulated device loss (diagnostic hook)".to_owned());
    }
}

/// Mark an Attached session Lost when its device died mid-render. The
/// pixels for that render already fell back to the CPU upstream; this only
/// stops the next render from retrying a dead device.
fn mark_session_gpu_lost(session: &NativeSession, reason: &str) {
    if let Ok(mut state) = session.gpu_state.lock() {
        if matches!(*state, SessionGpu::Attached(_)) {
            *state = SessionGpu::Lost(reason.to_owned());
        }
    }
}

/// JSON status for one session: `backend` is "gpu" with the adapter name
/// when a live device is attached, otherwise "cpu" with the fallback reason
/// and whether the session is in the Lost state.
fn session_backend_status(session: &NativeSession) -> String {
    match session_gpu(session) {
        Some(device) => json!({
            "backend": "gpu",
            "adapter": device.name(),
            "reason": null,
            "lost": false,
        })
        .to_string(),
        None => {
            let (lost, reason) = match session.gpu_state.lock() {
                Ok(state) => match &*state {
                    SessionGpu::Lost(reason) => (true, reason.clone()),
                    _ => (
                        false,
                        shared_gpu_failure()
                            .unwrap_or_else(|| "No GPU device available".to_owned()),
                    ),
                },
                Err(_) => (false, "Session GPU state lock poisoned".to_owned()),
            };
            json!({
                "backend": "cpu",
                "adapter": null,
                "reason": reason,
                "lost": lost,
            })
            .to_string()
        }
    }
}

/// JSON accounting for one session's media pool: cached decoded frames
/// and bytes against the Android budget, plus cache hits/misses. The
/// Kotlin side parses this where it enters and displays/asserts it; the
/// budget value here is the live one, not a documented guess.
fn session_media_pool_stats(session: &NativeSession) -> String {
    let stats = session.footage.stats();
    json!({
        "frames": stats.frames,
        "bytes": stats.bytes,
        "budget": session.footage.budget(),
        "hits": stats.hits,
        "misses": stats.misses,
        "opened": stats.opened,
    })
    .to_string()
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
    let gpu = session_gpu(session);
    let bytes = render_parsed_frame(
        &project,
        &session.footage,
        composition_id,
        frame,
        max_side,
        gpu.as_ref().map(|device| device as &dyn Accelerator),
    )?;
    // A device that died during this render already fell back to the CPU
    // upstream for these pixels; retire the handle so the next render does
    // not retry a dead device.
    if let Some(device) = &gpu {
        if !device.available() {
            mark_session_gpu_lost(session, "GPU device lost during render");
        }
    }
    if session.cancelled.load(Ordering::SeqCst) {
        return Err("Session render discarded after cancellation".into());
    }
    Ok(bytes)
}

fn render_parsed_frame(project: &Project, footage: &MediaPool, composition_id: u64, frame: i64, max_side: u32, accel: Option<&dyn Accelerator>) -> Result<Vec<u8>, String> {
    if frame < 0 { return Err("Frame must be non-negative".into()); }
    let comp = project.comp(ItemId(composition_id))
        .ok_or_else(|| format!("Composition {composition_id} was not found"))?;
    let long = comp.width.max(comp.height).max(1) as f64;
    let scale = if max_side == 0 { 1.0 } else { (max_side as f64 / long).min(1.0) };
    let time = Tick::from_rational(frame, comp.frame_rate.den, comp.frame_rate.num);
    let (backend, accel) = choose_backend(accel);
    let renderer = Renderer::new(project, footage, RenderOpts { scale, backend, ..Default::default() })
        .with_accel(accel);
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
    let footage = MEDIA_POOL.get_or_init(|| MediaPool::with_budget(ANDROID_MEDIA_POOL_BUDGET_BYTES));
    let gpu = resolve_shared_gpu();
    render_parsed_frame(&project, footage, composition_id, frame, max_side, gpu.as_ref().map(|device| device as &dyn Accelerator))
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

#[no_mangle]
pub extern "system" fn Java_ai_effectcraft_android_engine_NativeSession_backendStatusSessionNative(
    env: JNIEnv, _class: JClass, handle: jlong,
) -> jstring {
    let result = session_handle(handle)
        .and_then(lookup_session)
        .map(|session| session_backend_status(&session));
    match result {
        Ok(status) => env.new_string(status).map_or(std::ptr::null_mut(), |v| v.into_raw()),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_ai_effectcraft_android_engine_NativeSession_mediaPoolStatsSessionNative(
    env: JNIEnv, _class: JClass, handle: jlong,
) -> jstring {
    let result = session_handle(handle)
        .and_then(lookup_session)
        .map(|session| session_media_pool_stats(&session));
    match result {
        Ok(status) => env.new_string(status).map_or(std::ptr::null_mut(), |v| v.into_raw()),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_ai_effectcraft_android_engine_NativeSession_recoverGpuSessionNative(
    _env: JNIEnv, _class: JClass, handle: jlong,
) -> jboolean {
    session_handle(handle)
        .and_then(lookup_session)
        .map(|session| recover_session_gpu(&session))
        .unwrap_or(false) as jboolean
}

#[no_mangle]
pub extern "system" fn Java_ai_effectcraft_android_engine_NativeSession_simulateGpuLossSessionNative(
    _env: JNIEnv, _class: JClass, handle: jlong,
) {
    if let Ok(session) = session_handle(handle).and_then(lookup_session) {
        simulate_session_gpu_loss(&session);
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

fn layer_anchor(layer: &effectcraft_project::Layer) -> [f64; 2] {
    let mut anchor = [0.0, 0.0];
    if let Some(Node::Group(transform)) = layer.props.children.iter().find(|node| node.match_id() == "transform") {
        transform.walk("", &mut |_, property| {
            if property.match_id == "anchor" {
                if let Value::Vec3(value) = &property.value { anchor = [value[0], value[1]]; }
            }
        });
    }
    anchor
}

fn layer_orientation(layer: &effectcraft_project::Layer) -> [f64; 3] {
    let mut orientation = [0.0, 0.0, 0.0];
    if let Some(Node::Group(transform)) = layer.props.children.iter().find(|node| node.match_id() == "transform") {
        transform.walk("", &mut |_, property| {
            if property.match_id == "orientation" {
                if let Value::Vec3(value) = &property.value { orientation = *value; }
            }
        });
    }
    orientation
}

fn layer_rotation_axis(layer: &effectcraft_project::Layer, match_id: &str) -> f64 {
    let mut rotation = 0.0;
    if let Some(Node::Group(transform)) = layer.props.children.iter().find(|node| node.match_id() == "transform") {
        transform.walk("", &mut |_, property| {
            if property.match_id == match_id {
                if let Value::Scalar(value) = &property.value { rotation = *value; }
            }
        });
    }
    rotation
}

/// Direct scalar and vector parameters of one parameter group, in
/// depth-first order. Color, path, gradient, popup and layer-reference
/// parameters are not editable through the generic uid setter and are
/// omitted here, so metadata never promises an edit the UI cannot make.
fn collect_param_items(
    group: &effectcraft_project::PropGroup,
    prefix: &str,
    out: &mut Vec<serde_json::Value>,
) {
    for child in &group.children {
        let Node::Group(child_group) = child else { continue };
        let name = if prefix.is_empty() {
            child_group.name.clone()
        } else {
            format!("{prefix} / {}", child_group.name)
        };
        let mut scalars = Vec::new();
        let mut vecs = Vec::new();
        for prop_child in &child_group.children {
            let Node::Prop(property) = prop_child else { continue };
            match &property.value {
                Value::Scalar(value) => scalars.push(json!({
                    "uid": property.uid,
                    "name": property.name,
                    "value": value
                })),
                Value::Vec2(value) => vecs.push(json!({
                    "uid": property.uid,
                    "name": property.name,
                    "components": value
                })),
                Value::Vec3(value) => vecs.push(json!({
                    "uid": property.uid,
                    "name": property.name,
                    "components": value
                })),
                _ => {}
            }
        }
        if !scalars.is_empty() || !vecs.is_empty() {
            out.push(json!({
                "name": name,
                "kind": child_group.match_id,
                "scalars": scalars,
                "vecs": vecs
            }));
        }
        collect_param_items(child_group, &name, out);
    }
}

fn layer_param_items(layer: &effectcraft_project::Layer, group_match_id: &str) -> Vec<serde_json::Value> {
    let mut items = Vec::new();
    if let Some(group) = layer.props.sub(group_match_id) {
        collect_param_items(group, "", &mut items);
    }
    items
}

fn layer_text_params(layer: &effectcraft_project::Layer) -> Option<serde_json::Value> {
    let text_group = layer.props.sub("text")?;
    let property = text_group.prop("sourceText")?;
    if let Value::Text(doc) = &property.value {
        Some(json!({
            "text": doc.text,
            "font": doc.font,
            "size": doc.size,
            "tracking": doc.tracking
        }))
    } else {
        None
    }
}

/// A locked layer rejects every layer edit except unlocking itself.
fn require_layer_unlocked(layer: &effectcraft_project::Layer) -> Result<(), String> {
    if layer.switches.locked {
        Err(format!("Layer {} is locked", layer.id.0))
    } else {
        Ok(())
    }
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

/// Canonical opacity keys as (frame, percent) pairs in composition frame
/// units, using the same frame<->tick conversion as the renderer
/// (`Tick::from_rational(frame, den, num)`), so metadata frames and rendered
/// frames can never disagree about which tick a key sits on.
fn layer_opacity_keys(layer: &effectcraft_project::Layer, frame_rate: effectcraft_time::FrameRate) -> Vec<serde_json::Value> {
    let mut keys = Vec::new();
    if let Some(Node::Group(transform)) = layer.props.children.iter().find(|node| node.match_id() == "transform") {
        transform.walk("", &mut |_, property| {
            if property.match_id == "opacity" {
                for key in &property.keys {
                    if let Value::Scalar(value) = key.value {
                        keys.push(json!({
                            "frame": key.time.to_rational_floor(frame_rate.den, frame_rate.num),
                            "value": value
                        }));
                    }
                }
            }
        });
    }
    keys.sort_by_key(|key| key["frame"].as_i64().unwrap_or(0));
    keys
}

/// Transform keyframe frame indices exposed to the Android timeline. Frames use
/// the same rational Tick conversion as the renderer, and each property is
/// sorted/deduplicated so UI markers remain stable after same-frame upserts.
fn layer_transform_keyframes(layer: &effectcraft_project::Layer, frame_rate: effectcraft_time::FrameRate) -> serde_json::Value {
    let mut tracks = serde_json::Map::new();
    if let Some(Node::Group(transform)) = layer.props.children.iter().find(|node| node.match_id() == "transform") {
        for property_name in ["position", "scale", "rotation"] {
            if let Some(property) = transform.prop(property_name) {
                let mut frames = property.keys.iter()
                    .map(|key| key.time.to_rational_floor(frame_rate.den, frame_rate.num))
                    .collect::<Vec<_>>();
                frames.sort_unstable();
                frames.dedup();
                tracks.insert(property_name.to_owned(), json!(frames));
            }
        }
    }
    serde_json::Value::Object(tracks)
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
                "opacityKeys": layer_opacity_keys(layer, comp.frame_rate),
                "transformKeys": layer_transform_keyframes(layer, comp.frame_rate),
                "position": layer_position(layer),
                "scale": layer_scale(layer),
                "rotationDegrees": layer_rotation(layer),
                "anchor": layer_anchor(layer),
                "orientation": layer_orientation(layer),
                "rotationXDegrees": layer_rotation_axis(layer, "rotationX"),
                "rotationYDegrees": layer_rotation_axis(layer, "rotationY"),
                "locked": layer.switches.locked,
                "threeD": layer.switches.three_d,
                "text": layer_text_params(layer),
                "shapeItems": layer_param_items(layer, "contents"),
                "effects": layer_param_items(layer, "effects")
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
    require_layer_unlocked(layer)?;
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
    require_layer_unlocked(layer)?;
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
    require_layer_unlocked(&comp.layers[index])?;
    let last = comp.layers.len().saturating_sub(1) as i64;
    let target = (index as i64 + offset).clamp(0, last) as usize;
    comp.layers.swap(index, target);
    project.to_file_json().map_err(|error| format!("Unable to serialize project: {error}"))
}

/// Removes a native Transform Position, Scale or Rotation key at a composition frame.
/// Removing the final key restores the property's static value; absent keys are a no-op.
fn remove_layer_transform_keyframe(
    source: &str, composition_id: u64, layer_id: u64, property_name: &str, frame: i64,
) -> Result<(String, bool), String> {
    if frame < 0 { return Err("Keyframe frame must be non-negative".into()); }
    if !matches!(property_name, "position" | "scale" | "rotation") {
        return Err("Only position, scale and rotation keyframes are supported".into());
    }
    let mut project: Project = serde_json::from_str(source)
        .map_err(|error| format!("Invalid EffectCraft project: {error}"))?;
    let comp = project.comp_mut(ItemId(composition_id))
        .ok_or_else(|| format!("Composition {composition_id} was not found"))?;
    let frame_rate = comp.frame_rate;
    let layer = comp.layers.find_mut(|layer| layer.id == LayerId(layer_id))
        .ok_or_else(|| format!("Layer {layer_id} was not found in composition {composition_id}"))?;
    require_layer_unlocked(layer)?;
    let transform = layer.props.sub_mut("transform")
        .ok_or_else(|| format!("Layer {layer_id} has no Transform group"))?;
    let property = transform.prop_mut(property_name)
        .ok_or_else(|| format!("Layer {layer_id} has no {property_name} property"))?;
    match (property_name, &property.value) {
        ("position" | "scale", Value::Vec3(_)) | ("rotation", Value::Scalar(_)) => {},
        _ => return Err(format!("Layer {layer_id} Transform {property_name} has an incompatible value type")),
    }
    let tick = Tick::from_rational(frame, frame_rate.den, frame_rate.num);
    let removed = property.keys.iter().any(|key| key.time == tick);
    if removed {
        if property.keys.len() == 1 {
            property.set_animated(false, tick);
        } else {
            property.keys.retain(|key| key.time != tick);
        }
    }
    let json = project.to_file_json().map_err(|error| format!("Unable to serialize project: {error}"))?;
    Ok((json, removed))
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
    require_layer_unlocked(layer)?;
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
    require_layer_unlocked(layer)?;
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
    require_layer_unlocked(layer)?;
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

fn set_layer_anchor(source: &str, composition_id: u64, layer_id: u64, x: f64, y: f64) -> Result<String, String> {
    if !x.is_finite() || !y.is_finite() || x.abs() > 1_000_000.0 || y.abs() > 1_000_000.0 {
        return Err("Anchor Point must be finite and within ±1,000,000 pixels".into());
    }
    let mut project: Project = serde_json::from_str(source)
        .map_err(|error| format!("Invalid EffectCraft project: {error}"))?;
    let comp = project.comp_mut(ItemId(composition_id))
        .ok_or_else(|| format!("Composition {composition_id} was not found"))?;
    let layer = comp.layers.find_mut(|layer| layer.id == LayerId(layer_id))
        .ok_or_else(|| format!("Layer {layer_id} was not found in composition {composition_id}"))?;
    require_layer_unlocked(layer)?;
    let transform = layer.props.children.iter_mut().find_map(|node| match node {
        Node::Group(group) if group.match_id == "transform" => Some(group),
        _ => None,
    }).ok_or_else(|| format!("Layer {layer_id} has no Transform group"))?;
    let mut found = false;
    transform.walk_mut(&mut |property| {
        if property.match_id == "anchor" {
            if let Value::Vec3(current) = &property.value {
                property.set_value_at(Tick::ZERO, Value::Vec3([x, y, current[2]]));
                found = true;
            }
        }
    });
    if !found { return Err(format!("Layer {layer_id} has no 3D Anchor Point property")); }
    project.to_file_json().map_err(|error| format!("Unable to serialize project: {error}"))
}

fn set_layer_orientation(source: &str, composition_id: u64, layer_id: u64, x: f64, y: f64, z: f64) -> Result<String, String> {
    if !x.is_finite() || !y.is_finite() || !z.is_finite() || x.abs() > 360_000.0 || y.abs() > 360_000.0 || z.abs() > 360_000.0 {
        return Err("Orientation must be finite and within ±360,000 degrees".into());
    }
    let mut project: Project = serde_json::from_str(source)
        .map_err(|error| format!("Invalid EffectCraft project: {error}"))?;
    let comp = project.comp_mut(ItemId(composition_id))
        .ok_or_else(|| format!("Composition {composition_id} was not found"))?;
    let layer = comp.layers.find_mut(|layer| layer.id == LayerId(layer_id))
        .ok_or_else(|| format!("Layer {layer_id} was not found in composition {composition_id}"))?;
    require_layer_unlocked(layer)?;
    let transform = layer.props.sub_mut("transform")
        .ok_or_else(|| format!("Layer {layer_id} has no Transform group"))?;
    let property = transform.prop_mut("orientation")
        .ok_or_else(|| format!("Layer {layer_id} has no Orientation property"))?;
    if !matches!(&property.value, Value::Vec3(_)) {
        return Err(format!("Layer {layer_id} Orientation is not a 3D vector"));
    }
    property.set_value_at(Tick::ZERO, Value::Vec3([x, y, z]));
    project.to_file_json().map_err(|error| format!("Unable to serialize project: {error}"))
}

fn set_layer_transform_scalar(
    source: &str,
    composition_id: u64,
    layer_id: u64,
    match_id: &str,
    name: &str,
    degrees: f64,
) -> Result<String, String> {
    if !degrees.is_finite() || degrees.abs() > 360_000.0 {
        return Err(format!("{name} must be finite and within ±360,000 degrees"));
    }
    let mut project: Project = serde_json::from_str(source)
        .map_err(|error| format!("Invalid EffectCraft project: {error}"))?;
    let comp = project.comp_mut(ItemId(composition_id))
        .ok_or_else(|| format!("Composition {composition_id} was not found"))?;
    let layer = comp.layers.find_mut(|layer| layer.id == LayerId(layer_id))
        .ok_or_else(|| format!("Layer {layer_id} was not found in composition {composition_id}"))?;
    require_layer_unlocked(layer)?;
    let transform = layer.props.sub_mut("transform")
        .ok_or_else(|| format!("Layer {layer_id} has no Transform group"))?;
    let property = transform.prop_mut(match_id)
        .ok_or_else(|| format!("Layer {layer_id} has no {name} property"))?;
    if !matches!(&property.value, Value::Scalar(_)) {
        return Err(format!("Layer {layer_id} {name} is not scalar"));
    }
    property.set_value_at(Tick::ZERO, Value::Scalar(degrees));
    project.to_file_json().map_err(|error| format!("Unable to serialize project: {error}"))
}

fn set_layer_rotation_x(
    source: &str,
    composition_id: u64,
    layer_id: u64,
    degrees: f64,
) -> Result<String, String> {
    set_layer_transform_scalar(source, composition_id, layer_id, "rotationX", "X Rotation", degrees)
}

fn set_layer_rotation_y(
    source: &str,
    composition_id: u64,
    layer_id: u64,
    degrees: f64,
) -> Result<String, String> {
    set_layer_transform_scalar(source, composition_id, layer_id, "rotationY", "Y Rotation", degrees)
}

fn set_layer_locked(source: &str, composition_id: u64, layer_id: u64, locked: bool) -> Result<String, String> {
    let mut project: Project = serde_json::from_str(source)
        .map_err(|error| format!("Invalid EffectCraft project: {error}"))?;
    let comp = project.comp_mut(ItemId(composition_id))
        .ok_or_else(|| format!("Composition {composition_id} was not found"))?;
    let layer = comp.layers.find_mut(|layer| layer.id == LayerId(layer_id))
        .ok_or_else(|| format!("Layer {layer_id} was not found in composition {composition_id}"))?;
    layer.switches.locked = locked;
    project.to_file_json().map_err(|error| format!("Unable to serialize project: {error}"))
}

/// Applies `edit` to the cloned Source Text document and writes it back with
/// hold semantics at frame zero, mirroring the other canonical setters.
fn edit_text_doc(
    source: &str,
    composition_id: u64,
    layer_id: u64,
    edit: impl FnOnce(&mut effectcraft_project::keyframe::TextDoc) -> Result<(), String>,
) -> Result<String, String> {
    let mut project: Project = serde_json::from_str(source)
        .map_err(|error| format!("Invalid EffectCraft project: {error}"))?;
    let comp = project.comp_mut(ItemId(composition_id))
        .ok_or_else(|| format!("Composition {composition_id} was not found"))?;
    let layer = comp.layers.find_mut(|layer| layer.id == LayerId(layer_id))
        .ok_or_else(|| format!("Layer {layer_id} was not found in composition {composition_id}"))?;
    require_layer_unlocked(layer)?;
    let text_group = layer.props.sub_mut("text")
        .ok_or_else(|| format!("Layer {layer_id} has no Text group"))?;
    let property = text_group.prop_mut("sourceText")
        .ok_or_else(|| format!("Layer {layer_id} has no Source Text property"))?;
    let mut doc = match &property.value {
        Value::Text(doc) => (**doc).clone(),
        _ => return Err(format!("Layer {layer_id} Source Text is not a text document")),
    };
    edit(&mut doc)?;
    property.set_value_at(Tick::ZERO, Value::Text(Box::new(doc)));
    project.to_file_json().map_err(|error| format!("Unable to serialize project: {error}"))
}

fn set_text_layer_text(source: &str, composition_id: u64, layer_id: u64, text: &str) -> Result<String, String> {
    if text.chars().count() > 20_000 {
        return Err("Source Text must be at most 20,000 characters".into());
    }
    let owned = text.to_owned();
    edit_text_doc(source, composition_id, layer_id, |doc| {
        doc.text = owned;
        Ok(())
    })
}

fn set_text_layer_size(source: &str, composition_id: u64, layer_id: u64, size: f64) -> Result<String, String> {
    if !size.is_finite() || size <= 0.0 || size > 10_000.0 {
        return Err("Text size must be finite and between 0 and 10,000".into());
    }
    edit_text_doc(source, composition_id, layer_id, |doc| {
        doc.size = size;
        Ok(())
    })
}

fn set_text_layer_tracking(source: &str, composition_id: u64, layer_id: u64, tracking: f64) -> Result<String, String> {
    if !tracking.is_finite() || tracking.abs() > 10_000.0 {
        return Err("Text tracking must be finite and within ±10,000".into());
    }
    edit_text_doc(source, composition_id, layer_id, |doc| {
        doc.tracking = tracking;
        Ok(())
    })
}

fn set_layer_scalar_property(source: &str, composition_id: u64, layer_id: u64, uid: u64, value: f64) -> Result<String, String> {
    if !value.is_finite() || value.abs() > 1_000_000.0 {
        return Err("Parameter value must be finite and within ±1,000,000".into());
    }
    let mut project: Project = serde_json::from_str(source)
        .map_err(|error| format!("Invalid EffectCraft project: {error}"))?;
    let comp = project.comp_mut(ItemId(composition_id))
        .ok_or_else(|| format!("Composition {composition_id} was not found"))?;
    let layer = comp.layers.find_mut(|layer| layer.id == LayerId(layer_id))
        .ok_or_else(|| format!("Layer {layer_id} was not found in composition {composition_id}"))?;
    require_layer_unlocked(layer)?;
    let property = layer.props.prop_mut(&format!("@{uid}"))
        .ok_or_else(|| format!("Layer {layer_id} has no parameter with uid {uid}"))?;
    if !matches!(&property.value, Value::Scalar(_)) {
        return Err(format!("Parameter {uid} is not scalar"));
    }
    property.set_value_at(Tick::ZERO, Value::Scalar(value));
    project.to_file_json().map_err(|error| format!("Unable to serialize project: {error}"))
}

fn set_layer_vec_property(source: &str, composition_id: u64, layer_id: u64, uid: u64, x: f64, y: f64, z: f64) -> Result<String, String> {
    if !x.is_finite() || !y.is_finite() || !z.is_finite() || x.abs() > 1_000_000.0 || y.abs() > 1_000_000.0 || z.abs() > 1_000_000.0 {
        return Err("Parameter value must be finite and within ±1,000,000".into());
    }
    let mut project: Project = serde_json::from_str(source)
        .map_err(|error| format!("Invalid EffectCraft project: {error}"))?;
    let comp = project.comp_mut(ItemId(composition_id))
        .ok_or_else(|| format!("Composition {composition_id} was not found"))?;
    let layer = comp.layers.find_mut(|layer| layer.id == LayerId(layer_id))
        .ok_or_else(|| format!("Layer {layer_id} was not found in composition {composition_id}"))?;
    require_layer_unlocked(layer)?;
    let property = layer.props.prop_mut(&format!("@{uid}"))
        .ok_or_else(|| format!("Layer {layer_id} has no parameter with uid {uid}"))?;
    match &property.value {
        Value::Vec2(_) => property.set_value_at(Tick::ZERO, Value::Vec2([x, y])),
        Value::Vec3(_) => property.set_value_at(Tick::ZERO, Value::Vec3([x, y, z])),
        _ => return Err(format!("Parameter {uid} is not a vector")),
    }
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
pub extern "system" fn Java_ai_effectcraft_android_engine_NativeProjectBridge_setLayerAnchorEcprojNative(
    mut env: JNIEnv, _class: JClass, source: JString, composition_id: jlong, layer_id: jlong, x: jni::sys::jdouble, y: jni::sys::jdouble,
) -> jstring {
    let source: String = match env.get_string(&source) {
        Ok(value) => value.into(),
        Err(error) => return env.new_string(json!({"valid": false, "error": error.to_string()}).to_string()).map_or(std::ptr::null_mut(), |v| v.into_raw()),
    };
    let result = if composition_id < 0 || layer_id < 0 { Err("Composition and layer IDs must be non-negative".to_owned()) }
        else { set_layer_anchor(&source, composition_id as u64, layer_id as u64, x as f64, y as f64) };
    let output = match result { Ok(project_json) => json!({"valid": true, "project": project_json}).to_string(), Err(error) => json!({"valid": false, "error": error}).to_string() };
    env.new_string(output).map_or(std::ptr::null_mut(), |value| value.into_raw())
}

#[no_mangle]
pub extern "system" fn Java_ai_effectcraft_android_engine_NativeProjectBridge_setLayerOrientationEcprojNative(
    mut env: JNIEnv, _class: JClass, source: JString, composition_id: jlong, layer_id: jlong, x: jni::sys::jdouble, y: jni::sys::jdouble, z: jni::sys::jdouble,
) -> jstring {
    let source: String = match env.get_string(&source) {
        Ok(value) => value.into(),
        Err(error) => return env.new_string(json!({"valid": false, "error": error.to_string()}).to_string()).map_or(std::ptr::null_mut(), |v| v.into_raw()),
    };
    let result = if composition_id < 0 || layer_id < 0 { Err("Composition and layer IDs must be non-negative".to_owned()) }
        else { set_layer_orientation(&source, composition_id as u64, layer_id as u64, x as f64, y as f64, z as f64) };
    let output = match result { Ok(project_json) => json!({"valid": true, "project": project_json}).to_string(), Err(error) => json!({"valid": false, "error": error}).to_string() };
    env.new_string(output).map_or(std::ptr::null_mut(), |value| value.into_raw())
}

#[no_mangle]
pub extern "system" fn Java_ai_effectcraft_android_engine_NativeProjectBridge_setLayerRotationXEcprojNative(
    mut env: JNIEnv, _class: JClass, source: JString, composition_id: jlong, layer_id: jlong, degrees: jni::sys::jdouble,
) -> jstring {
    let source: String = match env.get_string(&source) {
        Ok(value) => value.into(),
        Err(error) => return env.new_string(json!({"valid": false, "error": error.to_string()}).to_string()).map_or(std::ptr::null_mut(), |v| v.into_raw()),
    };
    let result = if composition_id < 0 || layer_id < 0 { Err("Composition and layer IDs must be non-negative".to_owned()) }
        else { set_layer_rotation_x(&source, composition_id as u64, layer_id as u64, degrees as f64) };
    let output = match result { Ok(project_json) => json!({"valid": true, "project": project_json}).to_string(), Err(error) => json!({"valid": false, "error": error}).to_string() };
    env.new_string(output).map_or(std::ptr::null_mut(), |value| value.into_raw())
}

#[no_mangle]
pub extern "system" fn Java_ai_effectcraft_android_engine_NativeProjectBridge_setLayerRotationYEcprojNative(
    mut env: JNIEnv, _class: JClass, source: JString, composition_id: jlong, layer_id: jlong, degrees: jni::sys::jdouble,
) -> jstring {
    let source: String = match env.get_string(&source) {
        Ok(value) => value.into(),
        Err(error) => return env.new_string(json!({"valid": false, "error": error.to_string()}).to_string()).map_or(std::ptr::null_mut(), |v| v.into_raw()),
    };
    let result = if composition_id < 0 || layer_id < 0 { Err("Composition and layer IDs must be non-negative".to_owned()) }
        else { set_layer_rotation_y(&source, composition_id as u64, layer_id as u64, degrees as f64) };
    let output = match result { Ok(project_json) => json!({"valid": true, "project": project_json}).to_string(), Err(error) => json!({"valid": false, "error": error}).to_string() };
    env.new_string(output).map_or(std::ptr::null_mut(), |value| value.into_raw())
}

#[no_mangle]
pub extern "system" fn Java_ai_effectcraft_android_engine_NativeProjectBridge_setLayerLockedEcprojNative(
    mut env: JNIEnv, _class: JClass, source: JString, composition_id: jlong, layer_id: jlong, locked: jni::sys::jboolean,
) -> jstring {
    let source: String = match env.get_string(&source) {
        Ok(value) => value.into(),
        Err(error) => return env.new_string(json!({"valid": false, "error": error.to_string()}).to_string()).map_or(std::ptr::null_mut(), |v| v.into_raw()),
    };
    let result = if composition_id < 0 || layer_id < 0 { Err("Composition and layer IDs must be non-negative".to_owned()) }
        else { set_layer_locked(&source, composition_id as u64, layer_id as u64, locked != 0) };
    let output = match result { Ok(project_json) => json!({"valid": true, "project": project_json}).to_string(), Err(error) => json!({"valid": false, "error": error}).to_string() };
    env.new_string(output).map_or(std::ptr::null_mut(), |value| value.into_raw())
}

#[no_mangle]
pub extern "system" fn Java_ai_effectcraft_android_engine_NativeProjectBridge_setTextLayerTextEcprojNative(
    mut env: JNIEnv, _class: JClass, source: JString, composition_id: jlong, layer_id: jlong, text: JString,
) -> jstring {
    let source: String = match env.get_string(&source) {
        Ok(value) => value.into(),
        Err(error) => return env.new_string(json!({"valid": false, "error": error.to_string()}).to_string()).map_or(std::ptr::null_mut(), |v| v.into_raw()),
    };
    let text: String = match env.get_string(&text) {
        Ok(value) => value.into(),
        Err(error) => return env.new_string(json!({"valid": false, "error": error.to_string()}).to_string()).map_or(std::ptr::null_mut(), |v| v.into_raw()),
    };
    let result = if composition_id < 0 || layer_id < 0 { Err("Composition and layer IDs must be non-negative".to_owned()) }
        else { set_text_layer_text(&source, composition_id as u64, layer_id as u64, &text) };
    let output = match result { Ok(project_json) => json!({"valid": true, "project": project_json}).to_string(), Err(error) => json!({"valid": false, "error": error}).to_string() };
    env.new_string(output).map_or(std::ptr::null_mut(), |value| value.into_raw())
}

#[no_mangle]
pub extern "system" fn Java_ai_effectcraft_android_engine_NativeProjectBridge_setTextLayerSizeEcprojNative(
    mut env: JNIEnv, _class: JClass, source: JString, composition_id: jlong, layer_id: jlong, size: jni::sys::jdouble,
) -> jstring {
    let source: String = match env.get_string(&source) {
        Ok(value) => value.into(),
        Err(error) => return env.new_string(json!({"valid": false, "error": error.to_string()}).to_string()).map_or(std::ptr::null_mut(), |v| v.into_raw()),
    };
    let result = if composition_id < 0 || layer_id < 0 { Err("Composition and layer IDs must be non-negative".to_owned()) }
        else { set_text_layer_size(&source, composition_id as u64, layer_id as u64, size as f64) };
    let output = match result { Ok(project_json) => json!({"valid": true, "project": project_json}).to_string(), Err(error) => json!({"valid": false, "error": error}).to_string() };
    env.new_string(output).map_or(std::ptr::null_mut(), |value| value.into_raw())
}

#[no_mangle]
pub extern "system" fn Java_ai_effectcraft_android_engine_NativeProjectBridge_setTextLayerTrackingEcprojNative(
    mut env: JNIEnv, _class: JClass, source: JString, composition_id: jlong, layer_id: jlong, tracking: jni::sys::jdouble,
) -> jstring {
    let source: String = match env.get_string(&source) {
        Ok(value) => value.into(),
        Err(error) => return env.new_string(json!({"valid": false, "error": error.to_string()}).to_string()).map_or(std::ptr::null_mut(), |v| v.into_raw()),
    };
    let result = if composition_id < 0 || layer_id < 0 { Err("Composition and layer IDs must be non-negative".to_owned()) }
        else { set_text_layer_tracking(&source, composition_id as u64, layer_id as u64, tracking as f64) };
    let output = match result { Ok(project_json) => json!({"valid": true, "project": project_json}).to_string(), Err(error) => json!({"valid": false, "error": error}).to_string() };
    env.new_string(output).map_or(std::ptr::null_mut(), |value| value.into_raw())
}

#[no_mangle]
pub extern "system" fn Java_ai_effectcraft_android_engine_NativeProjectBridge_setLayerScalarPropertyEcprojNative(
    mut env: JNIEnv, _class: JClass, source: JString, composition_id: jlong, layer_id: jlong, uid: jlong, value: jni::sys::jdouble,
) -> jstring {
    let source: String = match env.get_string(&source) {
        Ok(value) => value.into(),
        Err(error) => return env.new_string(json!({"valid": false, "error": error.to_string()}).to_string()).map_or(std::ptr::null_mut(), |v| v.into_raw()),
    };
    let result = if composition_id < 0 || layer_id < 0 || uid < 0 { Err("Composition, layer and parameter IDs must be non-negative".to_owned()) }
        else { set_layer_scalar_property(&source, composition_id as u64, layer_id as u64, uid as u64, value as f64) };
    let output = match result { Ok(project_json) => json!({"valid": true, "project": project_json}).to_string(), Err(error) => json!({"valid": false, "error": error}).to_string() };
    env.new_string(output).map_or(std::ptr::null_mut(), |value| value.into_raw())
}

#[no_mangle]
pub extern "system" fn Java_ai_effectcraft_android_engine_NativeProjectBridge_setLayerVecPropertyEcprojNative(
    mut env: JNIEnv, _class: JClass, source: JString, composition_id: jlong, layer_id: jlong, uid: jlong, x: jni::sys::jdouble, y: jni::sys::jdouble, z: jni::sys::jdouble,
) -> jstring {
    let source: String = match env.get_string(&source) {
        Ok(value) => value.into(),
        Err(error) => return env.new_string(json!({"valid": false, "error": error.to_string()}).to_string()).map_or(std::ptr::null_mut(), |v| v.into_raw()),
    };
    let result = if composition_id < 0 || layer_id < 0 || uid < 0 { Err("Composition, layer and parameter IDs must be non-negative".to_owned()) }
        else { set_layer_vec_property(&source, composition_id as u64, layer_id as u64, uid as u64, x as f64, y as f64, z as f64) };
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
    require_layer_unlocked(layer)?;
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

/// Authors one canonical Transform/Opacity keyframe at `frame`.
/// The first key seeds frame 0 with the layer's current opacity, so a key
/// authored mid-timeline never rewrites earlier frames; `set_value_at` then
/// inserts or replaces the key in sorted order. Keys are linear by default,
/// so the renderer interpolates between them and holds past the last key —
/// the same semantics as the Kotlin `PropertyTrackEditor`.
fn set_layer_opacity_keyframe(source: &str, composition_id: u64, layer_id: u64, frame: i64, opacity_percent: f64) -> Result<String, String> {
    if !opacity_percent.is_finite() || !(0.0..=100.0).contains(&opacity_percent) {
        return Err("Opacity must be between 0 and 100".into());
    }
    if frame < 0 {
        return Err("Keyframe frame must be non-negative".into());
    }
    let mut project: Project = serde_json::from_str(source)
        .map_err(|error| format!("Invalid EffectCraft project: {error}"))?;
    let comp = project.comp_mut(ItemId(composition_id))
        .ok_or_else(|| format!("Composition {composition_id} was not found"))?;
    let frame_rate = comp.frame_rate;
    let layer = comp.layers.find_mut(|layer| layer.id == LayerId(layer_id))
        .ok_or_else(|| format!("Layer {layer_id} was not found in composition {composition_id}"))?;
    require_layer_unlocked(layer)?;
    let transform = layer.props.children.iter_mut().find_map(|node| match node {
        Node::Group(group) if group.match_id == "transform" => Some(group),
        _ => None,
    }).ok_or_else(|| format!("Layer {layer_id} has no Transform group"))?;
    let mut found = false;
    let tick = Tick::from_rational(frame, frame_rate.den, frame_rate.num);
    transform.walk_mut(&mut |property| {
        if property.match_id == "opacity" {
            // `set_value_at` only keys when the property is already animated;
            // seeding frame 0 first is what turns a static opacity into a track.
            if property.keys.is_empty() {
                property.set_animated(true, Tick::ZERO);
            }
            property.set_value_at(tick, Value::Scalar(opacity_percent));
            found = true;
        }
    });
    if !found { return Err(format!("Layer {layer_id} has no Transform opacity property")); }
    project.to_file_json().map_err(|error| format!("Unable to serialize project: {error}"))
}

/// Authors a native Transform Position, Scale or Rotation keyframe.
/// Vector properties preserve Z; scalar Rotation uses X. A static property
/// is seeded at frame zero before the requested key is inserted, so the
/// upstream renderer evaluates the animated value at every composition tick.
fn set_layer_transform_keyframe(
    source: &str, composition_id: u64, layer_id: u64, property_name: &str,
    frame: i64, x: f64, y: f64, z: f64,
) -> Result<String, String> {
    if frame < 0 { return Err("Keyframe frame must be non-negative".into()); }
    if !x.is_finite() || !y.is_finite() || !z.is_finite() {
        return Err("Transform keyframe values must be finite".into());
    }
    let valid_property = matches!(property_name, "position" | "scale" | "rotation");
    if !valid_property { return Err("Only position, scale and rotation keyframes are supported".into()); }
    let limit = match property_name { "position" => 1_000_000.0, "scale" => 100_000.0, _ => 360_000.0 };
    if x.abs() > limit || (property_name != "rotation" && y.abs() > limit) {
        return Err("Transform keyframe value exceeds its property limit".into());
    }
    let mut project: Project = serde_json::from_str(source)
        .map_err(|error| format!("Invalid EffectCraft project: {error}"))?;
    let comp = project.comp_mut(ItemId(composition_id))
        .ok_or_else(|| format!("Composition {composition_id} was not found"))?;
    let frame_rate = comp.frame_rate;
    let layer = comp.layers.find_mut(|layer| layer.id == LayerId(layer_id))
        .ok_or_else(|| format!("Layer {layer_id} was not found in composition {composition_id}"))?;
    require_layer_unlocked(layer)?;
    let transform = layer.props.sub_mut("transform")
        .ok_or_else(|| format!("Layer {layer_id} has no Transform group"))?;
    let property = transform.prop_mut(property_name)
        .ok_or_else(|| format!("Layer {layer_id} has no {property_name} property"))?;
    let value = match (property_name, &property.value) {
        ("position", Value::Vec3(current)) => Value::Vec3([x, y, current[2]]),
        ("scale", Value::Vec3(current)) => Value::Vec3([x, y, current[2]]),
        ("rotation", Value::Scalar(_)) => Value::Scalar(x),
        _ => return Err(format!("Layer {layer_id} Transform {property_name} has an incompatible value type")),
    };
    if property.keys.is_empty() {
        property.set_animated(true, Tick::ZERO);
    }
    property.set_value_at(Tick::from_rational(frame, frame_rate.den, frame_rate.num), value);
    project.to_file_json().map_err(|error| format!("Unable to serialize project: {error}"))
}

/// Applies a real upstream temporal interpolation preset to a transform key at an exact frame.
fn set_layer_transform_keyframe_interpolation(
    source: &str, composition_id: u64, layer_id: u64, property_name: &str,
    frame: i64, preset: &str,
) -> Result<String, String> {
    if frame < 0 { return Err("Keyframe frame must be non-negative".into()); }
    if !matches!(property_name, "position" | "scale" | "rotation") {
        return Err("Only position, scale and rotation interpolation is supported".into());
    }
    if !matches!(preset, "linear" | "easy_ease" | "hold") {
        return Err("Interpolation preset must be linear, easy_ease or hold".into());
    }
    let mut project: Project = serde_json::from_str(source)
        .map_err(|error| format!("Invalid EffectCraft project: {error}"))?;
    let comp = project.comp_mut(ItemId(composition_id))
        .ok_or_else(|| format!("Composition {composition_id} was not found"))?;
    let tick = Tick::from_rational(frame, comp.frame_rate.den, comp.frame_rate.num);
    let layer = comp.layers.find_mut(|layer| layer.id == LayerId(layer_id))
        .ok_or_else(|| format!("Layer {layer_id} was not found in composition {composition_id}"))?;
    require_layer_unlocked(layer)?;
    let transform = layer.props.sub_mut("transform")
        .ok_or_else(|| format!("Layer {layer_id} has no Transform group"))?;
    let property = transform.prop_mut(property_name)
        .ok_or_else(|| format!("Layer {layer_id} has no {property_name} property"))?;
    let key = property.keys.iter_mut().find(|key| key.time == tick)
        .ok_or_else(|| format!("No {property_name} keyframe exists at frame {frame}"))?;
    match preset {
        "linear" => {
            key.in_interp = Interp::Linear;
            key.out_interp = Interp::Linear;
            key.in_ease.clear();
            key.out_ease.clear();
            key.auto_bezier = false;
            key.continuous = false;
        }
        "easy_ease" => {
            *key = key.clone().eased();
            key.auto_bezier = false;
            key.continuous = false;
        }
        "hold" => {
            key.in_interp = Interp::Hold;
            key.out_interp = Interp::Hold;
            key.in_ease.clear();
            key.out_ease.clear();
            key.auto_bezier = false;
            key.continuous = false;
        }
        _ => return Err("Unsupported interpolation preset".into()),
    }
    project.to_file_json().map_err(|error| format!("Unable to serialize project: {error}"))
}

/// Removes the canonical Transform/Opacity keyframe at `frame`.
/// Returns `(project_json, removed)`; `removed` is false when no key existed
/// at that frame and the project is returned unchanged. Removing the last key
/// collapses the property back to its static value at that frame.
fn remove_layer_opacity_keyframe(source: &str, composition_id: u64, layer_id: u64, frame: i64) -> Result<(String, bool), String> {
    if frame < 0 {
        return Err("Keyframe frame must be non-negative".into());
    }
    let mut project: Project = serde_json::from_str(source)
        .map_err(|error| format!("Invalid EffectCraft project: {error}"))?;
    let comp = project.comp_mut(ItemId(composition_id))
        .ok_or_else(|| format!("Composition {composition_id} was not found"))?;
    let frame_rate = comp.frame_rate;
    let layer = comp.layers.find_mut(|layer| layer.id == LayerId(layer_id))
        .ok_or_else(|| format!("Layer {layer_id} was not found in composition {composition_id}"))?;
    require_layer_unlocked(layer)?;
    let transform = layer.props.children.iter_mut().find_map(|node| match node {
        Node::Group(group) if group.match_id == "transform" => Some(group),
        _ => None,
    }).ok_or_else(|| format!("Layer {layer_id} has no Transform group"))?;
    let mut found = false;
    let mut removed = false;
    let tick = Tick::from_rational(frame, frame_rate.den, frame_rate.num);
    transform.walk_mut(&mut |property| {
        if property.match_id == "opacity" {
            found = true;
            if property.keys.iter().any(|key| key.time == tick) {
                if property.keys.len() == 1 {
                    // Keep the value the timeline showed at this frame.
                    property.set_animated(false, tick);
                } else {
                    property.keys.retain(|key| key.time != tick);
                }
                removed = true;
            }
        }
    });
    if !found { return Err(format!("Layer {layer_id} has no Transform opacity property")); }
    let json = project.to_file_json().map_err(|error| format!("Unable to serialize project: {error}"))?;
    Ok((json, removed))
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
    require_layer_unlocked(&comp.layers[index])?;
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
pub extern "system" fn Java_ai_effectcraft_android_engine_NativeProjectBridge_setLayerOpacityKeyframeEcprojNative(
    mut env: JNIEnv, _class: JClass, source: JString, composition_id: jlong, layer_id: jlong, frame: jlong, opacity: jni::sys::jdouble,
) -> jstring {
    let source: String = match env.get_string(&source) {
        Ok(value) => value.into(),
        Err(error) => return env.new_string(json!({"valid": false, "error": error.to_string()}).to_string()).map_or(std::ptr::null_mut(), |v| v.into_raw()),
    };
    let result = if composition_id < 0 || layer_id < 0 || frame < 0 { Err("Composition, layer and frame must be non-negative".to_owned()) }
        else { set_layer_opacity_keyframe(&source, composition_id as u64, layer_id as u64, frame, opacity as f64) };
    let output = match result { Ok(project_json) => json!({"valid": true, "project": project_json}).to_string(), Err(error) => json!({"valid": false, "error": error}).to_string() };
    env.new_string(output).map_or(std::ptr::null_mut(), |value| value.into_raw())
}

#[no_mangle]
pub extern "system" fn Java_ai_effectcraft_android_engine_NativeProjectBridge_setLayerTransformKeyframeEcprojNative(
    mut env: JNIEnv, _class: JClass, source: JString, composition_id: jlong, layer_id: jlong,
    property: JString, frame: jlong, x: jni::sys::jdouble, y: jni::sys::jdouble, z: jni::sys::jdouble,
) -> jstring {
    let source: String = match env.get_string(&source) {
        Ok(value) => value.into(),
        Err(error) => return env.new_string(json!({"valid": false, "error": error.to_string()}).to_string()).map_or(std::ptr::null_mut(), |v| v.into_raw()),
    };
    let property: String = match env.get_string(&property) {
        Ok(value) => value.into(),
        Err(error) => return env.new_string(json!({"valid": false, "error": error.to_string()}).to_string()).map_or(std::ptr::null_mut(), |v| v.into_raw()),
    };
    let result = if composition_id < 0 || layer_id < 0 || frame < 0 {
        Err("Composition, layer and frame must be non-negative".to_owned())
    } else {
        set_layer_transform_keyframe(&source, composition_id as u64, layer_id as u64, &property, frame, x as f64, y as f64, z as f64)
    };
    let output = match result { Ok(project_json) => json!({"valid": true, "project": project_json}).to_string(), Err(error) => json!({"valid": false, "error": error}).to_string() };
    env.new_string(output).map_or(std::ptr::null_mut(), |value| value.into_raw())
}

#[no_mangle]
pub extern "system" fn Java_ai_effectcraft_android_engine_NativeProjectBridge_setLayerTransformKeyframeInterpolationEcprojNative(
    mut env: JNIEnv, _class: JClass, source: JString, composition_id: jlong, layer_id: jlong,
    property: JString, frame: jlong, preset: JString,
) -> jstring {
    let source: String = match env.get_string(&source) {
        Ok(value) => value.into(),
        Err(error) => return env.new_string(json!({"valid": false, "error": error.to_string()}).to_string()).map_or(std::ptr::null_mut(), |v| v.into_raw()),
    };
    let property: String = match env.get_string(&property) {
        Ok(value) => value.into(),
        Err(error) => return env.new_string(json!({"valid": false, "error": error.to_string()}).to_string()).map_or(std::ptr::null_mut(), |v| v.into_raw()),
    };
    let preset: String = match env.get_string(&preset) {
        Ok(value) => value.into(),
        Err(error) => return env.new_string(json!({"valid": false, "error": error.to_string()}).to_string()).map_or(std::ptr::null_mut(), |v| v.into_raw()),
    };
    let result = if composition_id < 0 || layer_id < 0 || frame < 0 {
        Err("Composition, layer and frame must be non-negative".to_owned())
    } else {
        set_layer_transform_keyframe_interpolation(&source, composition_id as u64, layer_id as u64, &property, frame, &preset)
    };
    let output = match result { Ok(project_json) => json!({"valid": true, "project": project_json}).to_string(), Err(error) => json!({"valid": false, "error": error}).to_string() };
    env.new_string(output).map_or(std::ptr::null_mut(), |value| value.into_raw())
}

#[no_mangle]
pub extern "system" fn Java_ai_effectcraft_android_engine_NativeProjectBridge_removeLayerTransformKeyframeEcprojNative(
    mut env: JNIEnv, _class: JClass, source: JString, composition_id: jlong, layer_id: jlong,
    property: JString, frame: jlong,
) -> jstring {
    let source: String = match env.get_string(&source) {
        Ok(value) => value.into(),
        Err(error) => return env.new_string(json!({"valid": false, "error": error.to_string()}).to_string()).map_or(std::ptr::null_mut(), |v| v.into_raw()),
    };
    let property: String = match env.get_string(&property) {
        Ok(value) => value.into(),
        Err(error) => return env.new_string(json!({"valid": false, "error": error.to_string()}).to_string()).map_or(std::ptr::null_mut(), |v| v.into_raw()),
    };
    let result = if composition_id < 0 || layer_id < 0 || frame < 0 {
        Err("Composition, layer and frame must be non-negative".to_owned())
    } else {
        remove_layer_transform_keyframe(&source, composition_id as u64, layer_id as u64, &property, frame)
    };
    let output = match result {
        Ok((project_json, removed)) => json!({"valid": true, "project": project_json, "removed": removed}).to_string(),
        Err(error) => json!({"valid": false, "error": error}).to_string(),
    };
    env.new_string(output).map_or(std::ptr::null_mut(), |value| value.into_raw())
}

#[no_mangle]
pub extern "system" fn Java_ai_effectcraft_android_engine_NativeProjectBridge_removeLayerOpacityKeyframeEcprojNative(
    mut env: JNIEnv, _class: JClass, source: JString, composition_id: jlong, layer_id: jlong, frame: jlong,
) -> jstring {
    let source: String = match env.get_string(&source) {
        Ok(value) => value.into(),
        Err(error) => return env.new_string(json!({"valid": false, "error": error.to_string()}).to_string()).map_or(std::ptr::null_mut(), |v| v.into_raw()),
    };
    let result = if composition_id < 0 || layer_id < 0 || frame < 0 { Err("Composition, layer and frame must be non-negative".to_owned()) }
        else { remove_layer_opacity_keyframe(&source, composition_id as u64, layer_id as u64, frame) };
    let output = match result { Ok((project_json, removed)) => json!({"valid": true, "project": project_json, "removed": removed}).to_string(), Err(error) => json!({"valid": false, "error": error}).to_string() };
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

    fn opacity_property(project: &Project, comp_id: u64, layer_id: u64) -> &effectcraft_project::Property {
        let layer = project.comp(ItemId(comp_id)).unwrap().layers.iter().find(|l| l.id.0 == layer_id).unwrap();
        layer.props.sub("transform").unwrap().get("opacity").unwrap()
    }

    fn text_layer_project() -> (String, u64, u64) {
        use effectcraft_project::{build, Comp, ItemKind, LayerSource};
        use effectcraft_color::Label;
        use effectcraft_time::{FrameRate, Tick};

        let mut project = Project::default();
        let comp = Comp::new(640, 360, FrameRate::FPS_30, Tick::from_seconds_f64(3.0));
        let comp_id = project.add_item("Main", Label::Sandstone, None, ItemKind::Comp(comp.clone().into()));
        let layer = build::layer(&mut project, &comp, "Text", LayerSource::Text, (640, 360), None);
        let layer_id = layer.id.0;
        project.comp_mut(comp_id).unwrap().layers.push(layer);
        (project.to_file_json().unwrap(), comp_id.0, layer_id)
    }

    #[test]
    fn canonical_opacity_keyframes_interpolate_hold_and_roundtrip() {
        use effectcraft_project::Value;
        use effectcraft_time::Tick;

        let (source, comp_id, layer_id) = text_layer_project();
        let before: Project = serde_json::from_str(&source).unwrap();
        let static_opacity = match opacity_property(&before, comp_id, layer_id).value {
            Value::Scalar(value) => value,
            _ => panic!("opacity property must be scalar"),
        };
        let tick = |frame: i64| Tick::from_rational(frame, 1, 30);

        let keyed = set_layer_opacity_keyframe(&source, comp_id, layer_id, 30, 0.0).unwrap();
        let updated: Project = serde_json::from_str(&keyed).unwrap();
        let property = opacity_property(&updated, comp_id, layer_id);
        assert_eq!(property.keys.len(), 2);
        assert_eq!(property.keys[0].time, Tick::ZERO);
        assert_eq!(property.keys[0].value, Value::Scalar(static_opacity));
        assert_eq!(property.keys[1].time, tick(30));
        assert_eq!(property.keys[1].value, Value::Scalar(0.0));
        // Linear interpolation at the midpoint, endpoint hold past the last key.
        let mid = match property.value_at(tick(15)) {
            Value::Scalar(value) => value,
            _ => panic!("interpolated opacity must be scalar"),
        };
        assert!((mid - static_opacity / 2.0).abs() < 1e-9, "midpoint opacity {mid} != {}", static_opacity / 2.0);
        assert_eq!(property.value_at(tick(0)), Value::Scalar(static_opacity));
        assert_eq!(property.value_at(tick(30)), Value::Scalar(0.0));
        assert_eq!(property.value_at(tick(60)), Value::Scalar(0.0));
        // Canonical serialization round-trips the keys, and metadata exposes them per frame.
        let reparsed: Project = serde_json::from_str(&updated.to_file_json().unwrap()).unwrap();
        assert_eq!(opacity_property(&reparsed, comp_id, layer_id).keys.len(), 2);
        let metadata: serde_json::Value = serde_json::from_str(&inspect_project(&keyed).unwrap()).unwrap();
        assert_eq!(
            metadata["compositions"][0]["layers"][0]["opacityKeys"],
            json!([{"frame": 0, "value": static_opacity}, {"frame": 30, "value": 0.0}])
        );
    }

    #[test]
    fn canonical_opacity_keyframe_upsert_remove_and_rejects_invalid() {
        use effectcraft_project::Value;

        let (source, comp_id, layer_id) = text_layer_project();
        let keyed = set_layer_opacity_keyframe(&source, comp_id, layer_id, 30, 0.0).unwrap();
        let replaced = set_layer_opacity_keyframe(&keyed, comp_id, layer_id, 30, 25.0).unwrap();
        let updated: Project = serde_json::from_str(&replaced).unwrap();
        assert_eq!(opacity_property(&updated, comp_id, layer_id).keys.len(), 2);
        assert_eq!(opacity_property(&updated, comp_id, layer_id).keys[1].value, Value::Scalar(25.0));

        let (removed, was_removed) = remove_layer_opacity_keyframe(&replaced, comp_id, layer_id, 30).unwrap();
        assert!(was_removed);
        let after_remove: Project = serde_json::from_str(&removed).unwrap();
        assert_eq!(opacity_property(&after_remove, comp_id, layer_id).keys.len(), 1);
        let (unchanged, was_removed_again) = remove_layer_opacity_keyframe(&removed, comp_id, layer_id, 30).unwrap();
        assert!(!was_removed_again);
        let still: Project = serde_json::from_str(&unchanged).unwrap();
        assert_eq!(opacity_property(&still, comp_id, layer_id).keys.len(), 1);

        // Removing the last key collapses back to the static value at that frame.
        let (collapsed, was_removed_last) = remove_layer_opacity_keyframe(&removed, comp_id, layer_id, 0).unwrap();
        assert!(was_removed_last);
        let collapsed_project: Project = serde_json::from_str(&collapsed).unwrap();
        let property = opacity_property(&collapsed_project, comp_id, layer_id);
        assert!(property.keys.is_empty());
        assert_eq!(property.value, Value::Scalar(100.0));

        assert!(set_layer_opacity_keyframe(&source, comp_id, layer_id, 30, 101.0).is_err());
        assert!(set_layer_opacity_keyframe(&source, comp_id, layer_id, 30, -1.0).is_err());
        assert!(set_layer_opacity_keyframe(&source, comp_id, layer_id, -1, 50.0).is_err());
        assert!(set_layer_opacity_keyframe(&source, comp_id, 999, 30, 50.0).is_err());
        assert!(set_layer_opacity_keyframe(&source, 999, layer_id, 30, 50.0).is_err());
        assert!(remove_layer_opacity_keyframe(&source, comp_id, 999, 0).is_err());
    }

    fn red_pixel_footage_project() -> (String, u64, String) {
        use effectcraft_project::{build, Comp, Footage, FootageKind, ItemKind, LayerSource};
        use effectcraft_color::Label;
        use effectcraft_time::{FrameRate, Tick};
        use image::ImageEncoder;

        let mut png = Vec::new();
        image::codecs::png::PngEncoder::new(&mut png)
            .write_image(&[255, 0, 0, 255], 1, 1, image::ExtendedColorType::Rgba8)
            .unwrap();
        let path = std::env::temp_dir().join("effectcraft-keyframe-red.png");
        std::fs::write(&path, png).unwrap();

        let mut project = Project::default();
        let comp = Comp::new(32, 32, FrameRate::FPS_30, Tick::from_seconds_f64(3.0));
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
        (project.to_file_json().unwrap(), comp_id.0, path.to_string_lossy().into_owned())
    }

    fn has_strong_red(bytes: &[u8]) -> bool {
        bytes.len() >= 8 && bytes[8..].chunks_exact(4).any(|pixel| pixel[0] > 200 && pixel[1] < 20 && pixel[2] < 20 && pixel[3] > 200)
    }

    #[test]
    fn canonical_opacity_keyframes_change_rendered_frame() {
        let (source, comp_id, path) = red_pixel_footage_project();
        let layer_id = {
            let project: Project = serde_json::from_str(&source).unwrap();
            project.comp(ItemId(comp_id)).unwrap().layers[0].id.0
        };
        let keyed = set_layer_opacity_keyframe(&source, comp_id, layer_id, 30, 0.0).unwrap();
        let frame0 = render_frame_rgba(&keyed, comp_id, 0, 32).unwrap();
        let frame15 = render_frame_rgba(&keyed, comp_id, 15, 32).unwrap();
        let frame30 = render_frame_rgba(&keyed, comp_id, 30, 32).unwrap();
        assert!(has_strong_red(&frame0), "frame 0 must render the footage at full opacity");
        assert!(!has_strong_red(&frame30), "frame 30 must render the footage faded out");
        assert_ne!(frame15, frame0, "midpoint frame must differ from the full-opacity frame");
        assert_ne!(frame15, frame30, "midpoint frame must differ from the faded-out frame");
        std::fs::remove_file(path).unwrap();
    }

    #[test]
    fn session_renders_keyframed_opacity_at_playhead_frames() {
        let (source, comp_id, path) = red_pixel_footage_project();
        let layer_id = {
            let project: Project = serde_json::from_str(&source).unwrap();
            project.comp(ItemId(comp_id)).unwrap().layers[0].id.0
        };
        let keyed = set_layer_opacity_keyframe(&source, comp_id, layer_id, 30, 0.0).unwrap();
        let handle = create_session(&keyed, 32).unwrap();
        let session = lookup_session(handle).unwrap();
        let frame0 = render_session_frame(&session, comp_id, 0).unwrap();
        let frame30 = render_session_frame(&session, comp_id, 30).unwrap();
        assert!(has_strong_red(&frame0));
        assert!(!has_strong_red(&frame30));
        assert!(close_session(handle));
        std::fs::remove_file(path).unwrap();
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

    /// Test-only accelerator: counts how often the renderer asks it for a
    /// frame and always declines (returns None), so the CPU must render.
    /// `available` models a live device (true) or a lost/retired one (false).
    struct CountingAccel {
        asked: std::sync::atomic::AtomicUsize,
        available: bool,
    }

    impl effectcraft_render::Accelerator for CountingAccel {
        fn name(&self) -> String {
            "test-accelerator".to_owned()
        }

        fn comp_frame(
            &self,
            _renderer: &effectcraft_render::Renderer,
            _comp: ItemId,
            _time: Tick,
        ) -> Option<effectcraft_render::Image> {
            self.asked
                .fetch_add(1, std::sync::atomic::Ordering::SeqCst);
            None
        }

        fn supports_effect(&self, _id: &str) -> bool {
            false
        }

        fn effects(
            &self,
            _chain: &[effectcraft_render::FxStep],
            _buf: &effectcraft_effects::Buf,
            _levels: Option<f32>,
        ) -> Option<effectcraft_effects::Buf> {
            None
        }

        fn available(&self) -> bool {
            self.available
        }
    }

    #[test]
    fn gpu_backend_choice_prefers_live_accelerator_and_cpu_on_loss() {
        use effectcraft_render::Backend;

        let live = CountingAccel {
            asked: std::sync::atomic::AtomicUsize::new(0),
            available: true,
        };
        let (backend, accel) = choose_backend(Some(&live));
        assert_eq!(backend, Backend::Gpu);
        assert!(accel.is_some());

        let retired = CountingAccel {
            asked: std::sync::atomic::AtomicUsize::new(0),
            available: false,
        };
        let (backend, accel) = choose_backend(Some(&retired));
        assert_eq!(backend, Backend::Cpu);
        assert!(accel.is_none());

        let (backend, accel) = choose_backend(None);
        assert_eq!(backend, Backend::Cpu);
        assert!(accel.is_none());
    }

    #[test]
    fn declining_accelerator_falls_back_to_identical_cpu_pixels_and_retired_one_is_never_asked() {
        let (source, comp_id) = text_comp_project(320, 180);
        let project: Project = serde_json::from_str(&source).unwrap();
        let footage = MediaPool::new();
        let cpu = render_parsed_frame(&project, &footage, comp_id, 0, 160, None).unwrap();

        let live = CountingAccel {
            asked: std::sync::atomic::AtomicUsize::new(0),
            available: true,
        };
        let via_live =
            render_parsed_frame(&project, &footage, comp_id, 0, 160, Some(&live)).unwrap();
        assert_eq!(live.asked.load(std::sync::atomic::Ordering::SeqCst), 1);
        assert_eq!(via_live, cpu);

        let retired = CountingAccel {
            asked: std::sync::atomic::AtomicUsize::new(0),
            available: false,
        };
        let via_retired =
            render_parsed_frame(&project, &footage, comp_id, 0, 160, Some(&retired)).unwrap();
        assert_eq!(retired.asked.load(std::sync::atomic::Ordering::SeqCst), 0);
        assert_eq!(via_retired, cpu);
    }

    #[test]
    fn session_gpu_loss_falls_back_to_cpu_and_reattach_keeps_rendering() {
        let (source, comp_id) = text_comp_project(320, 180);
        let handle = create_session(&source, 160).unwrap();
        let session = lookup_session(handle).unwrap();

        let before = render_session_frame(&session, comp_id, 0).unwrap();
        assert_eq!(frame_dimensions(&before), (160, 90));
        let status: serde_json::Value =
            serde_json::from_str(&session_backend_status(&session)).unwrap();
        assert!(status["backend"] == "gpu" || status["backend"] == "cpu");
        if status["backend"] == "gpu" {
            assert!(!status["adapter"].as_str().unwrap_or("").is_empty());
        } else {
            assert!(!status["reason"].as_str().unwrap_or("").is_empty());
        }

        simulate_session_gpu_loss(&session);
        let lost: serde_json::Value =
            serde_json::from_str(&session_backend_status(&session)).unwrap();
        assert_eq!(lost["backend"], "cpu");
        assert_eq!(lost["lost"], true);
        assert!(!lost["reason"].as_str().unwrap_or("").is_empty());

        // Loss must not crash, leak the session, or fake a frame: the same
        // composition still renders at the same size from the CPU path.
        let after_loss = render_session_frame(&session, comp_id, 0).unwrap();
        assert_eq!(frame_dimensions(&after_loss), (160, 90));

        // Re-attach re-resolves from the shared slot without forcing a
        // fresh device probe; rendering must keep working either way.
        reattach_session_gpu(&session);
        let after_reattach = render_session_frame(&session, comp_id, 0).unwrap();
        assert_eq!(frame_dimensions(&after_reattach), (160, 90));
        let final_status: serde_json::Value =
            serde_json::from_str(&session_backend_status(&session)).unwrap();
        assert!(final_status["backend"] == "gpu" || final_status["backend"] == "cpu");
        assert!(close_session(handle));
    }

    #[test]
    fn session_media_pool_uses_android_budget_and_reports_live_stats() {
        let (source, comp_id) = text_comp_project(320, 180);
        let handle = create_session(&source, 160).unwrap();
        let session = lookup_session(handle).unwrap();
        // The live budget is the Android value, not the upstream 1 GiB
        // default — and it is read from the pool itself, not restated.
        let stats: serde_json::Value =
            serde_json::from_str(&session_media_pool_stats(&session)).unwrap();
        assert_eq!(stats["budget"], ANDROID_MEDIA_POOL_BUDGET_BYTES as u64);
        assert_eq!(stats["budget"], 256 * 1024 * 1024);
        assert_eq!(stats["frames"], 0);
        assert_eq!(stats["bytes"], 0);
        assert!(close_session(handle));
    }

    #[test]
    fn media_pool_budget_shrink_evicts_all_but_newest_decoded_frame() {
        use effectcraft_project::{Footage, FootageKind};
        use effectcraft_time::Tick;
        use image::ImageEncoder;

        // Two distinct 1x1 stills decode into two cached frames; shrinking
        // the budget below their combined size must evict the older one
        // while keeping the newest (the upstream LRU contract).
        let pool = MediaPool::with_budget(1 << 20);
        let mut paths = Vec::new();
        for index in 0..2 {
            let path = std::env::temp_dir()
                .join(format!("effectcraft-budget-{}-{}.png", std::process::id(), index));
            let mut png = Vec::new();
            image::codecs::png::PngEncoder::new(&mut png)
                .write_image(&[255, 0, 0, 255], 1, 1, image::ExtendedColorType::Rgba8)
                .unwrap();
            std::fs::write(&path, png).unwrap();
            let footage = Footage {
                path: path.to_string_lossy().into_owned(),
                kind: FootageKind::Still,
                width: 1,
                height: 1,
                has_video: true,
                missing: false,
                ..Footage::default()
            };
            pool.frame_at(&footage, Tick::from_seconds_f64(0.0)).unwrap();
            paths.push(path);
        }
        let before = pool.stats();
        assert_eq!(before.frames, 2);
        assert!(before.bytes > 0);
        pool.set_budget(before.bytes - 1);
        let after = pool.stats();
        assert_eq!(after.frames, 1);
        assert!(after.bytes < before.bytes);
        for path in paths {
            std::fs::remove_file(path).unwrap();
        }
    }

    #[test]
    fn canonical_anchor_and_3d_rotation_update_real_properties_and_metadata() {
        use effectcraft_project::Value;

        let (source, comp_id, layer_id) = text_layer_project();
        let anchored = set_layer_anchor(&source, comp_id, layer_id, 120.5, 80.25).unwrap();
        let oriented = set_layer_orientation(&anchored, comp_id, layer_id, 10.0, 20.0, 30.0).unwrap();
        let rotated_x = set_layer_rotation_x(&oriented, comp_id, layer_id, 45.0).unwrap();
        let rotated = set_layer_rotation_y(&rotated_x, comp_id, layer_id, -30.0).unwrap();
        let updated: Project = serde_json::from_str(&rotated).unwrap();
        let transform = updated.comp(ItemId(comp_id)).unwrap().layers[0].props.sub("transform").unwrap();
        assert_eq!(transform.prop("anchor").unwrap().value, Value::Vec3([120.5, 80.25, 0.0]));
        assert_eq!(transform.prop("orientation").unwrap().value, Value::Vec3([10.0, 20.0, 30.0]));
        assert_eq!(transform.prop("rotationX").unwrap().value, Value::Scalar(45.0));
        assert_eq!(transform.prop("rotationY").unwrap().value, Value::Scalar(-30.0));
        let metadata: serde_json::Value = serde_json::from_str(&inspect_project(&rotated).unwrap()).unwrap();
        let layer_meta = &metadata["compositions"][0]["layers"][0];
        assert_eq!(layer_meta["anchor"], json!([120.5, 80.25]));
        assert_eq!(layer_meta["orientation"], json!([10.0, 20.0, 30.0]));
        assert_eq!(layer_meta["rotationXDegrees"], 45.0);
        assert_eq!(layer_meta["rotationYDegrees"], -30.0);
        assert_eq!(layer_meta["locked"], false);
        assert!(set_layer_anchor(&source, comp_id, layer_id, f64::NAN, 0.0).is_err());
        assert!(set_layer_orientation(&source, comp_id, layer_id, 0.0, f64::INFINITY, 0.0).is_err());
        assert!(set_layer_rotation_x(&source, comp_id, 999, 10.0).is_err());
        assert!(set_layer_rotation_y(&source, comp_id, layer_id, 360_001.0).is_err());
    }

    #[test]
    fn canonical_text_params_update_source_text_size_and_tracking() {
        use effectcraft_project::Value;

        let (source, comp_id, layer_id) = text_layer_project();
        let texted = set_text_layer_text(&source, comp_id, layer_id, "Hello mobile").unwrap();
        let sized = set_text_layer_size(&texted, comp_id, layer_id, 48.0).unwrap();
        let tracked = set_text_layer_tracking(&sized, comp_id, layer_id, 12.5).unwrap();
        let updated: Project = serde_json::from_str(&tracked).unwrap();
        let text_group = updated.comp(ItemId(comp_id)).unwrap().layers[0].props.sub("text").unwrap();
        let doc = match &text_group.prop("sourceText").unwrap().value {
            Value::Text(doc) => doc,
            other => panic!("sourceText must be a Text value, got {other:?}"),
        };
        assert_eq!(doc.text, "Hello mobile");
        assert_eq!(doc.size, 48.0);
        assert_eq!(doc.tracking, 12.5);
        let metadata: serde_json::Value = serde_json::from_str(&inspect_project(&tracked).unwrap()).unwrap();
        let text_meta = &metadata["compositions"][0]["layers"][0]["text"];
        assert_eq!(text_meta["text"], "Hello mobile");
        assert_eq!(text_meta["size"], 48.0);
        assert_eq!(text_meta["tracking"], 12.5);
        assert_eq!(text_meta["font"], "Inter");
        assert!(set_text_layer_size(&source, comp_id, layer_id, 0.0).is_err());
        assert!(set_text_layer_tracking(&source, comp_id, layer_id, f64::NAN).is_err());
        assert!(set_text_layer_text(&source, comp_id, 999, "Nope").is_err());
    }

    #[test]
    fn canonical_shape_and_effect_params_update_by_property_uid() {
        use effectcraft_project::{build, Comp, ItemKind, LayerSource, Value};
        use effectcraft_color::Label;
        use effectcraft_time::{FrameRate, Tick};

        let mut project = Project::default();
        let comp = Comp::new(640, 360, FrameRate::FPS_30, Tick::from_seconds_f64(3.0));
        let comp_id = project.add_item("Main", Label::Sandstone, None, ItemKind::Comp(comp.clone().into()));
        let mut shape_layer = build::layer(&mut project, &comp, "Shape", LayerSource::Shape, (640, 360), None);
        let shape_id = shape_layer.id.0;
        let mut next_id = 10_000u64;
        let mut ids = build::Ids(&mut next_id);
        let rect = build::shape_rect(&mut ids, [200.0, 100.0], [0.0, 0.0], 0.0);
        shape_layer.props.sub_mut("contents").unwrap().children.push(rect.into());
        project.comp_mut(comp_id).unwrap().layers.push(shape_layer);
        let mut null_layer = build::layer(&mut project, &comp, "Null", LayerSource::Null, (640, 360), None);
        let null_id = null_layer.id.0;
        let mut effect = ids.group("gaussianBlur", "Gaussian Blur");
        effect = effect.with(ids.prop("blurriness", "Blurriness", Value::Scalar(0.0)));
        null_layer.props.sub_mut("effects").unwrap().children.push(effect.into());
        project.comp_mut(comp_id).unwrap().layers.push(null_layer);
        let source = project.to_file_json().unwrap();

        let metadata: serde_json::Value = serde_json::from_str(&inspect_project(&source).unwrap()).unwrap();
        let shape_meta = &metadata["compositions"][0]["layers"][0];
        assert_eq!(shape_meta["source"], "Shape");
        let rect_item = shape_meta["shapeItems"].as_array().unwrap().iter()
            .find(|item| item["kind"] == "rect").expect("rect shape item must be exposed");
        let size_uid = rect_item["vecs"].as_array().unwrap().iter()
            .find(|param| param["name"] == "Size").expect("rect Size param must be exposed")["uid"].as_u64().unwrap();
        let roundness_uid = rect_item["scalars"].as_array().unwrap().iter()
            .find(|param| param["name"] == "Roundness").expect("rect Roundness param must be exposed")["uid"].as_u64().unwrap();
        let resized = set_layer_vec_property(&source, comp_id.0, shape_id, size_uid, 320.0, 160.0, 0.0).unwrap();
        let rounded = set_layer_scalar_property(&resized, comp_id.0, shape_id, roundness_uid, 24.0).unwrap();
        let updated_meta: serde_json::Value = serde_json::from_str(&inspect_project(&rounded).unwrap()).unwrap();
        let updated_rect = updated_meta["compositions"][0]["layers"][0]["shapeItems"].as_array().unwrap().iter()
            .find(|item| item["kind"] == "rect").unwrap();
        assert_eq!(updated_rect["vecs"].as_array().unwrap().iter().find(|p| p["name"] == "Size").unwrap()["components"], json!([320.0, 160.0]));
        assert_eq!(updated_rect["scalars"].as_array().unwrap().iter().find(|p| p["name"] == "Roundness").unwrap()["value"], 24.0);

        let effect_meta = &metadata["compositions"][0]["layers"][1];
        let blur_item = effect_meta["effects"].as_array().unwrap().iter()
            .find(|item| item["name"] == "Gaussian Blur").expect("effect group must be exposed");
        let blur_uid = blur_item["scalars"].as_array().unwrap().iter()
            .find(|param| param["name"] == "Blurriness").expect("Blurriness param must be exposed")["uid"].as_u64().unwrap();
        let blurred = set_layer_scalar_property(&rounded, comp_id.0, null_id, blur_uid, 15.0).unwrap();
        let blurred_meta: serde_json::Value = serde_json::from_str(&inspect_project(&blurred).unwrap()).unwrap();
        let blurred_item = blurred_meta["compositions"][0]["layers"][1]["effects"].as_array().unwrap().iter()
            .find(|item| item["name"] == "Gaussian Blur").unwrap();
        assert_eq!(blurred_item["scalars"].as_array().unwrap().iter().find(|p| p["name"] == "Blurriness").unwrap()["value"], 15.0);
        assert!(set_layer_scalar_property(&source, comp_id.0, shape_id, size_uid, 10.0).is_err());
        assert!(set_layer_vec_property(&source, comp_id.0, shape_id, roundness_uid, 1.0, 2.0, 0.0).is_err());
        assert!(set_layer_scalar_property(&source, comp_id.0, shape_id, 999_999, 10.0).is_err());
    }

    #[test]
    fn canonical_transform_keyframes_seed_and_interpolate_native_tracks() {
        use effectcraft_project::Value;
        use effectcraft_time::Tick;

        let (source, comp_id, layer_id) = text_layer_project();
        let at_zero = set_layer_transform_keyframe(&source, comp_id, layer_id, "position", 0, 10.0, 20.0, 0.0).unwrap();
        let at_fifteen = set_layer_transform_keyframe(&at_zero, comp_id, layer_id, "position", 15, 100.0, 200.0, 0.0).unwrap();
        let scaled = set_layer_transform_keyframe(&at_fifteen, comp_id, layer_id, "scale", 0, 50.0, 75.0, 0.0).unwrap();
        let rotated = set_layer_transform_keyframe(&scaled, comp_id, layer_id, "rotation", 0, 45.0, 0.0, 0.0).unwrap();
        let project: Project = serde_json::from_str(&rotated).unwrap();
        let transform = project.comp(ItemId(comp_id)).unwrap().layers[0].props.sub("transform").unwrap();
        let position = transform.prop("position").unwrap();
        assert_eq!(position.keys.len(), 2);
        assert_eq!(position.keys[0].time, Tick::ZERO);
        assert_eq!(position.keys[0].value, Value::Vec3([10.0, 20.0, 0.0]));
        assert_eq!(position.keys[1].time, Tick::from_rational(15, 1, 30));
        assert_eq!(position.keys[1].value, Value::Vec3([100.0, 200.0, 0.0]));
        assert_eq!(transform.prop("scale").unwrap().keys.len(), 1);
        assert_eq!(transform.prop("rotation").unwrap().keys.len(), 1);
        let metadata = serde_json::from_str::<serde_json::Value>(&inspect_project(&rotated).unwrap()).unwrap();
        let tracks = &metadata["compositions"][0]["layers"][0]["transformKeys"];
        assert_eq!(tracks["position"], json!([0, 15]));
        assert_eq!(tracks["scale"], json!([0]));
        assert_eq!(tracks["rotation"], json!([0]));
        assert!(set_layer_transform_keyframe(&source, comp_id, layer_id, "position", 2, f64::NAN, 0.0, 0.0).is_err());
        assert!(set_layer_transform_keyframe(&source, comp_id, layer_id, "opacity", 2, 1.0, 0.0, 0.0).is_err());
        assert!(set_layer_transform_keyframe(&source, comp_id, 999, "position", 2, 1.0, 0.0, 0.0).is_err());
    }

    #[test]
    fn canonical_transform_keyframe_interpolation_presets() {
        let (source, comp_id, layer_id) = text_layer_project();
        let at_zero = set_layer_transform_keyframe(&source, comp_id, layer_id, "position", 0, 10.0, 20.0, 0.0).unwrap();
        let at_fifteen = set_layer_transform_keyframe(&at_zero, comp_id, layer_id, "position", 15, 100.0, 200.0, 0.0).unwrap();
        let eased = set_layer_transform_keyframe_interpolation(&at_fifteen, comp_id, layer_id, "position", 15, "easy_ease").unwrap();
        let eased_project: Project = serde_json::from_str(&eased).unwrap();
        let eased_key = &eased_project.comp(ItemId(comp_id)).unwrap().layers[0].props.sub("transform").unwrap().prop("position").unwrap().keys[1];
        assert_eq!(eased_key.in_interp, Interp::Bezier);
        assert_eq!(eased_key.out_interp, Interp::Bezier);
        assert_eq!(eased_key.in_ease[0].influence, 1.0 / 3.0);
        assert_eq!(eased_key.out_ease[0].influence, 1.0 / 3.0);

        let held = set_layer_transform_keyframe_interpolation(&eased, comp_id, layer_id, "position", 15, "hold").unwrap();
        let held_project: Project = serde_json::from_str(&held).unwrap();
        let held_key = &held_project.comp(ItemId(comp_id)).unwrap().layers[0].props.sub("transform").unwrap().prop("position").unwrap().keys[1];
        assert_eq!(held_key.in_interp, Interp::Hold);
        assert_eq!(held_key.out_interp, Interp::Hold);
        assert!(held_key.in_ease.is_empty() && held_key.out_ease.is_empty());

        let linear = set_layer_transform_keyframe_interpolation(&held, comp_id, layer_id, "position", 15, "linear").unwrap();
        let linear_project: Project = serde_json::from_str(&linear).unwrap();
        let linear_key = &linear_project.comp(ItemId(comp_id)).unwrap().layers[0].props.sub("transform").unwrap().prop("position").unwrap().keys[1];
        assert_eq!(linear_key.in_interp, Interp::Linear);
        assert_eq!(linear_key.out_interp, Interp::Linear);
        assert!(linear_key.in_ease.is_empty() && linear_key.out_ease.is_empty());
        assert!(set_layer_transform_keyframe_interpolation(&linear, comp_id, layer_id, "position", 14, "easy_ease").is_err());
        assert!(set_layer_transform_keyframe_interpolation(&linear, comp_id, layer_id, "position", 15, "unknown").is_err());
    }

    #[test]
    fn canonical_transform_keyframe_remove_is_idempotent_and_restores_static_value() {
        use effectcraft_project::Value;
        use effectcraft_time::Tick;
        let (source, comp_id, layer_id) = text_layer_project();
        let keyed = set_layer_transform_keyframe(&source, comp_id, layer_id, "position", 0, 10.0, 20.0, 0.0).unwrap();
        let keyed = set_layer_transform_keyframe(&keyed, comp_id, layer_id, "position", 15, 100.0, 200.0, 0.0).unwrap();
        let (without_mid, removed_mid) = remove_layer_transform_keyframe(&keyed, comp_id, layer_id, "position", 15).unwrap();
        assert!(removed_mid);
        let project: Project = serde_json::from_str(&without_mid).unwrap();
        let position = project.comp(ItemId(comp_id)).unwrap().layers[0].props.sub("transform").unwrap().prop("position").unwrap();
        assert_eq!(position.keys.len(), 1);
        assert_eq!(position.keys[0].time, Tick::ZERO);
        assert_eq!(position.keys[0].value, Value::Vec3([10.0, 20.0, 0.0]));
        let (unchanged, removed_again) = remove_layer_transform_keyframe(&without_mid, comp_id, layer_id, "position", 15).unwrap();
        assert!(!removed_again);
        assert_eq!(unchanged, without_mid);
        let (static_json, removed_zero) = remove_layer_transform_keyframe(&without_mid, comp_id, layer_id, "position", 0).unwrap();
        assert!(removed_zero);
        let static_project: Project = serde_json::from_str(&static_json).unwrap();
        let static_position = static_project.comp(ItemId(comp_id)).unwrap().layers[0].props.sub("transform").unwrap().prop("position").unwrap();
        assert!(static_position.keys.is_empty());
        assert_eq!(static_position.value, Value::Vec3([10.0, 20.0, 0.0]));
        assert!(remove_layer_transform_keyframe(&keyed, comp_id, layer_id, "opacity", 15).is_err());
        assert!(remove_layer_transform_keyframe(&keyed, comp_id, layer_id, "rotation", -1).is_err());
    }

    #[test]
    fn canonical_layer_lock_blocks_edits_and_unlock_restores_them() {
        let (source, comp_id, layer_id) = text_layer_project();
        let locked_json = set_layer_locked(&source, comp_id, layer_id, true).unwrap();
        let locked_project: Project = serde_json::from_str(&locked_json).unwrap();
        assert!(locked_project.comp(ItemId(comp_id)).unwrap().layers[0].switches.locked);
        let metadata: serde_json::Value = serde_json::from_str(&inspect_project(&locked_json).unwrap()).unwrap();
        assert_eq!(metadata["compositions"][0]["layers"][0]["locked"], true);

        assert!(rename_layer(&locked_json, comp_id, layer_id, "Renamed").is_err());
        assert!(set_layer_visibility(&locked_json, comp_id, layer_id, false).is_err());
        assert!(move_layer(&locked_json, comp_id, layer_id, 1).is_err());
        assert!(delete_layer(&locked_json, comp_id, layer_id).is_err());
        assert!(set_layer_position(&locked_json, comp_id, layer_id, 10.0, 10.0).is_err());
        assert!(set_layer_scale(&locked_json, comp_id, layer_id, 50.0, 50.0).is_err());
        assert!(set_layer_rotation(&locked_json, comp_id, layer_id, 10.0).is_err());
        assert!(set_layer_opacity(&locked_json, comp_id, layer_id, 50.0).is_err());
        assert!(set_layer_anchor(&locked_json, comp_id, layer_id, 1.0, 1.0).is_err());
        assert!(set_layer_orientation(&locked_json, comp_id, layer_id, 1.0, 0.0, 0.0).is_err());
        assert!(set_layer_rotation_x(&locked_json, comp_id, layer_id, 10.0).is_err());
        assert!(set_layer_rotation_y(&locked_json, comp_id, layer_id, 10.0).is_err());
        assert!(set_text_layer_text(&locked_json, comp_id, layer_id, "Blocked").is_err());
        assert!(set_layer_opacity_keyframe(&locked_json, comp_id, layer_id, 10, 50.0).is_err());

        let unlocked_json = set_layer_locked(&locked_json, comp_id, layer_id, false).unwrap();
        let moved = set_layer_position(&unlocked_json, comp_id, layer_id, 10.0, 20.0).unwrap();
        let moved_project: Project = serde_json::from_str(&moved).unwrap();
        assert!(!moved_project.comp(ItemId(comp_id)).unwrap().layers[0].switches.locked);
        assert!(set_layer_locked(&source, comp_id, 999, true).is_err());
    }
}
