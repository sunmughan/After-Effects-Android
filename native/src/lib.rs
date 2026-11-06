use effectcraft_project::props::ParamUi;
use effectcraft_project::Project;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jlong, jstring};
use jni::JNIEnv;
use serde_json::json;
use effectcraft_project::{ItemId, ItemKind, LayerId, Node, Value};
use effectcraft_project::keyframe::{retime_roving, Ease, Gradient, Interp, ShapePath};
use effectcraft_gpu::Gpu;
use effectcraft_media::MediaPool;
use effectcraft_render::{Accelerator, Backend, LayerCache, RenderOpts, Renderer};
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
/// Persistent processed-layer cache shared across preview frames in one session.
/// Content-hashed entries are invalidated naturally when evaluated layer inputs change.
const ANDROID_LAYER_CACHE_BUDGET_BYTES: usize = 64 * 1024 * 1024;

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
    layer_cache: LayerCache,
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
        layer_cache: LayerCache::new(ANDROID_LAYER_CACHE_BUDGET_BYTES),
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
    let layer_cache = session.layer_cache.stats();
    json!({
        "frames": stats.frames,
        "bytes": stats.bytes,
        "budget": session.footage.budget(),
        "hits": stats.hits,
        "misses": stats.misses,
        "opened": stats.opened,
        "layerCacheEntries": layer_cache.entries,
        "layerCacheBytes": layer_cache.bytes,
        "layerCacheHits": layer_cache.hits,
        "layerCacheMisses": layer_cache.misses,
        "layerCacheBudget": ANDROID_LAYER_CACHE_BUDGET_BYTES,
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
        Some(&session.layer_cache),
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

fn render_parsed_frame(project: &Project, footage: &MediaPool, composition_id: u64, frame: i64, max_side: u32, accel: Option<&dyn Accelerator>, layer_cache: Option<&LayerCache>) -> Result<Vec<u8>, String> {
    if frame < 0 { return Err("Frame must be non-negative".into()); }
    let comp = project.comp(ItemId(composition_id))
        .ok_or_else(|| format!("Composition {composition_id} was not found"))?;
    let long = comp.width.max(comp.height).max(1) as f64;
    let scale = if max_side == 0 { 1.0 } else { (max_side as f64 / long).min(1.0) };
    let time = Tick::from_rational(frame, comp.frame_rate.den, comp.frame_rate.num);
    let (backend, accel) = choose_backend(accel);
    let mut renderer = Renderer::new(project, footage, RenderOpts { scale, backend, ..Default::default() })
        .with_accel(accel);
    renderer.cache = layer_cache;
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
    render_parsed_frame(&project, footage, composition_id, frame, max_side, gpu.as_ref().map(|device| device as &dyn Accelerator), None)
}

#[no_mangle]
pub extern "system" fn Java_com_codeair_effectcraft_engine_NativeProjectBridge_renderEcprojFrameNative(
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
pub extern "system" fn Java_com_codeair_effectcraft_engine_NativeSession_createSessionNative(
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
pub extern "system" fn Java_com_codeair_effectcraft_engine_NativeSession_renderSessionFrameNative(
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
pub extern "system" fn Java_com_codeair_effectcraft_engine_NativeSession_updateSessionProjectNative(
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
pub extern "system" fn Java_com_codeair_effectcraft_engine_NativeSession_resizeSessionNative(
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
pub extern "system" fn Java_com_codeair_effectcraft_engine_NativeSession_cancelSessionNative(
    _env: JNIEnv, _class: JClass, handle: jlong,
) {
    if let Ok(session) = session_handle(handle).and_then(lookup_session) {
        cancel_session(&session);
    }
}

#[no_mangle]
pub extern "system" fn Java_com_codeair_effectcraft_engine_NativeSession_closeSessionNative(
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
pub extern "system" fn Java_com_codeair_effectcraft_engine_NativeSession_backendStatusSessionNative(
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
pub extern "system" fn Java_com_codeair_effectcraft_engine_NativeSession_mediaPoolStatsSessionNative(
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
pub extern "system" fn Java_com_codeair_effectcraft_engine_NativeSession_recoverGpuSessionNative(
    _env: JNIEnv, _class: JClass, handle: jlong,
) -> jboolean {
    session_handle(handle)
        .and_then(lookup_session)
        .map(|session| recover_session_gpu(&session))
        .unwrap_or(false) as jboolean
}

#[no_mangle]
pub extern "system" fn Java_com_codeair_effectcraft_engine_NativeSession_simulateGpuLossSessionNative(
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

/// Sorted, deduplicated keyframe frame indices of one property, in
/// composition frame units — the same frame<->tick conversion as the
/// renderer, so metadata frames and rendered frames agree.
fn hold_keyframes(
    property: &effectcraft_project::props::Property,
    frame_rate: effectcraft_time::FrameRate,
) -> Vec<i64> {
    let mut keyframes = property.keys.iter()
        .map(|key| key.time.to_rational_floor(frame_rate.den, frame_rate.num))
        .collect::<Vec<_>>();
    keyframes.sort_unstable();
    keyframes.dedup();
    keyframes
}

/// Direct scalar, vector, color, checkbox-bool and popup-enum parameters
/// of one parameter group, in depth-first order. Path, gradient and
/// layer-reference parameters are not editable through the generic uid
/// setter and are omitted here, so metadata never promises an edit the UI
/// cannot make. A bool is exposed only with a Checkbox UI and an enum only
/// with a Popup UI (both non-static-only); Hidden popups such as Fill
/// Blend Mode stay omitted.
fn collect_param_items(
    group: &effectcraft_project::PropGroup,
    prefix: &str,
    out: &mut Vec<serde_json::Value>,
    frame_rate: effectcraft_time::FrameRate,
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
        let mut colors = Vec::new();
        let mut gradients = Vec::new();
        let mut paths = Vec::new();
        let mut bools = Vec::new();
        let mut popups = Vec::new();
        let mut layer_refs = Vec::new();
        for prop_child in &child_group.children {
            let Node::Prop(property) = prop_child else { continue };
            match &property.value {
                Value::Scalar(value) => scalars.push(json!({
                    "uid": property.uid,
                    "name": property.name,
                    "value": value,
                    "keys": curve_keys_json(property, frame_rate)
                })),
                Value::Vec2(value) => vecs.push(json!({
                    "uid": property.uid,
                    "name": property.name,
                    "components": value,
                    "keys": curve_keys_json(property, frame_rate)
                })),
                Value::Vec3(value) => vecs.push(json!({
                    "uid": property.uid,
                    "name": property.name,
                    "components": value,
                    "keys": curve_keys_json(property, frame_rate)
                })),
                Value::Color(value) => {
                    let mut keyframes = property.keys.iter()
                        .map(|key| key.time.to_rational_floor(frame_rate.den, frame_rate.num))
                        .collect::<Vec<_>>();
                    keyframes.sort_unstable();
                    keyframes.dedup();
                    colors.push(json!({
                        "uid": property.uid,
                        "name": property.name,
                        "components": value,
                        "keys": curve_keys_json(property, frame_rate),
                        "keyframes": keyframes
                    }));
                },
                Value::Gradient(value) => {
                    let mut keyframes = property.keys.iter()
                        .map(|key| key.time.to_rational_floor(frame_rate.den, frame_rate.num))
                        .collect::<Vec<_>>();
                    keyframes.sort_unstable();
                    keyframes.dedup();
                    gradients.push(json!({
                        "uid": property.uid,
                        "name": property.name,
                        "colors": value.colors.iter().map(|(position, rgba)| json!([position, rgba])).collect::<Vec<_>>(),
                        "opacities": value.opacities.iter().map(|(position, opacity)| json!([position, opacity])).collect::<Vec<_>>(),
                        "keys": curve_keys_json(property, frame_rate),
                        "keyframes": keyframes
                    }));
                },
                Value::Path(value) if matches!(&property.ui, ParamUi::Path) => {
                    paths.push(json!({
                        "uid": property.uid,
                        "name": property.name,
                        "path": value,
                        "keys": curve_keys_json(property, frame_rate),
                        "keyframes": hold_keyframes(property, frame_rate)
                    }));
                },
                Value::Bool(value) if matches!(&property.ui, ParamUi::Checkbox) && !property.static_only => {
                    bools.push(json!({
                        "uid": property.uid,
                        "name": property.name,
                        "value": value,
                        "keys": curve_keys_json(property, frame_rate),
                        "keyframes": hold_keyframes(property, frame_rate)
                    }));
                },
                Value::Enum(value) if matches!(&property.ui, ParamUi::Popup { .. }) && !property.static_only => {
                    let options = match &property.ui {
                        ParamUi::Popup { options } => options.clone(),
                        _ => Vec::new(),
                    };
                    popups.push(json!({
                        "uid": property.uid,
                        "name": property.name,
                        "value": value,
                        "options": options,
                        "keys": curve_keys_json(property, frame_rate),
                        "keyframes": hold_keyframes(property, frame_rate)
                    }));
                },
                Value::Layer(value) if matches!(&property.ui, ParamUi::Layer) => {
                    let mut keys = property.keys.iter().filter_map(|key| match &key.value {
                        Value::Layer(target) => Some(json!({
                            "frame": key.time.to_rational_floor(frame_rate.den, frame_rate.num),
                            "layerId": target
                        })),
                        _ => None,
                    }).collect::<Vec<_>>();
                    keys.sort_by_key(|key| key["frame"].as_i64().unwrap_or(0));
                    layer_refs.push(json!({
                        "uid": property.uid, "name": property.name, "value": value,
                        "keys": keys, "keyframes": hold_keyframes(property, frame_rate)
                    }));
                },
                _ => {}
            }
        }
        if !scalars.is_empty() || !vecs.is_empty() || !colors.is_empty() || !gradients.is_empty() || !paths.is_empty() || !bools.is_empty() || !popups.is_empty() || !layer_refs.is_empty() {
            out.push(json!({
                "name": name,
                "kind": child_group.match_id,
                "scalars": scalars,
                "vecs": vecs,
                "colors": colors,
                "gradients": gradients,
                "paths": paths,
                "bools": bools,
                "popups": popups,
                "layerRefs": layer_refs
            }));
        }
        collect_param_items(child_group, &name, out, frame_rate);
    }
}

fn layer_param_items(
    layer: &effectcraft_project::Layer,
    group_match_id: &str,
    frame_rate: effectcraft_time::FrameRate,
) -> Vec<serde_json::Value> {
    let mut items = Vec::new();
    if let Some(group) = layer.props.sub(group_match_id) {
        collect_param_items(group, "", &mut items, frame_rate);
    }
    items
}

/// Shape-item groups for the inspector: the "contents" groups plus the
/// "masks" groups, so mask paths (Value::Path) are exposed and editable
/// in the same list. Masks live in their own top-level group upstream,
/// so contents alone never sees them; without this merge the mask path
/// test finds no mask group in shapeItems at all.
fn layer_shape_items(
    layer: &effectcraft_project::Layer,
    frame_rate: effectcraft_time::FrameRate,
) -> Vec<serde_json::Value> {
    let mut items = layer_param_items(layer, "contents", frame_rate);
    items.extend(layer_param_items(layer, "masks", frame_rate));
    items
}

fn layer_text_params(layer: &effectcraft_project::Layer, frame_rate: effectcraft_time::FrameRate) -> Option<serde_json::Value> {
    let text_group = layer.props.sub("text")?;
    let property = text_group.prop("sourceText")?;
    if let Value::Text(doc) = &property.value {
        let mut keys = property.keys.iter().filter_map(|key| match &key.value {
            Value::Text(key_doc) => Some(json!({
                "frame": key.time.to_rational_floor(frame_rate.den, frame_rate.num),
                "text": key_doc.text,
                "font": key_doc.font,
                "tracking": key_doc.tracking
            })),
            _ => None,
        }).collect::<Vec<_>>();
        keys.sort_by_key(|key| key["frame"].as_i64().unwrap_or(0));
        Some(json!({
            "uid": property.uid,
            "text": doc.text,
            "font": doc.font,
            "size": doc.size,
            "tracking": doc.tracking,
            "keys": keys
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
        for property_name in ["position", "scale", "rotation", "anchor", "orientation", "rotationX", "rotationY"] {
            if let Some(property) = find_property_ref(transform, property_name) {
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

/// Full curve data for one property: frame, first-component value, per-side
/// interpolation labels and per-dimension ease entries, sorted by frame.
/// The Kotlin graph plots the first component for vector properties.
fn curve_keys_json(
    property: &effectcraft_project::props::Property,
    frame_rate: effectcraft_time::FrameRate,
) -> serde_json::Value {
    let mut keys = Vec::new();
    for key in &property.keys {
        let value = match &key.value {
            Value::Scalar(value) => json!(value),
            Value::Vec2(value) => json!(value),
            Value::Vec3(value) => json!(value),
            // A color key rides as its full RGBA array so the timeline
            // color lane can draw a swatch at each keyframe from the
            // key's own value, not the layer's current color.
            Value::Color(value) => json!(value),
            // Hold-only values have no numeric tween: a bool rides as a
            // JSON boolean and an enum as its 0-based option index, and
            // the Kotlin parser maps both onto the curve value (1.0/0.0
            // for bools, the index for enums).
            Value::Bool(value) => json!(value),
            Value::Enum(value) => json!(value),
            _ => continue,
        };
        let ease_json = |ease: &Vec<Ease>| ease.iter()
            .map(|entry| json!({"speed": entry.speed, "influence": entry.influence}))
            .collect::<Vec<_>>();
        keys.push(json!({
            "frame": key.time.to_rational_floor(frame_rate.den, frame_rate.num),
            "value": value,
            "inInterp": key.in_interp.label(),
            "outInterp": key.out_interp.label(),
            "inEase": ease_json(&key.in_ease),
            "outEase": ease_json(&key.out_ease),
            "roving": key.roving,
            "spatialIn": key.spatial_in,
            "spatialOut": key.spatial_out,
            "spatialAuto": key.spatial_auto,
        }));
    }
    keys.sort_by_key(|key| key["frame"].as_i64().unwrap_or(0));
    json!(keys)
}

fn find_property_ref<'a>(
    group: &'a effectcraft_project::props::PropGroup,
    match_id: &str,
) -> Option<&'a effectcraft_project::props::Property> {
    for child in &group.children {
        match child {
            Node::Prop(property) if property.match_id == match_id => return Some(property),
            Node::Group(child_group) => {
                if let Some(found) = find_property_ref(child_group, match_id) {
                    return Some(found);
                }
            }
            _ => {}
        }
    }
    None
}

/// Curve data for every Transform property, including opacity, keyed by
/// property name. The frame-only transformKeys field is unchanged.
fn layer_transform_curves(
    layer: &effectcraft_project::Layer,
    frame_rate: effectcraft_time::FrameRate,
) -> serde_json::Value {
    let mut tracks = serde_json::Map::new();
    if let Some(Node::Group(transform)) = layer.props.children.iter().find(|node| node.match_id() == "transform") {
        for property_name in ["position", "scale", "rotation", "opacity", "anchor", "orientation", "rotationX", "rotationY"] {
            if let Some(property) = find_property_ref(transform, property_name) {
                tracks.insert(property_name.to_owned(), curve_keys_json(property, frame_rate));
            }
        }
    }
    serde_json::Value::Object(tracks)
}

fn layer_opacity_curves(
    layer: &effectcraft_project::Layer,
    frame_rate: effectcraft_time::FrameRate,
) -> serde_json::Value {
    if let Some(Node::Group(transform)) = layer.props.children.iter().find(|node| node.match_id() == "transform") {
        if let Some(property) = find_property_ref(transform, "opacity") {
            return curve_keys_json(property, frame_rate);
        }
    }
    json!([])
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
                "opacityCurves": layer_opacity_curves(layer, comp.frame_rate),
                "transformKeys": layer_transform_keyframes(layer, comp.frame_rate),
                "transformCurves": layer_transform_curves(layer, comp.frame_rate),
                "position": layer_position(layer),
                "scale": layer_scale(layer),
                "rotationDegrees": layer_rotation(layer),
                "anchor": layer_anchor(layer),
                "orientation": layer_orientation(layer),
                "rotationXDegrees": layer_rotation_axis(layer, "rotationX"),
                "rotationYDegrees": layer_rotation_axis(layer, "rotationY"),
                "locked": layer.switches.locked,
                "threeD": layer.switches.three_d,
                "text": layer_text_params(layer, comp.frame_rate),
                "shapeItems": layer_shape_items(layer, comp.frame_rate),
                "effects": layer_param_items(layer, "effects", comp.frame_rate)
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
pub extern "system" fn Java_com_codeair_effectcraft_engine_NativeProjectBridge_inspectEcprojNative(
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
pub extern "system" fn Java_com_codeair_effectcraft_engine_NativeProjectBridge_renameLayerEcprojNative(
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
pub extern "system" fn Java_com_codeair_effectcraft_engine_NativeProjectBridge_addTextLayerEcprojNative(
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
    if !matches!(property_name, "position" | "scale" | "rotation" | "anchor" | "orientation" | "rotationX" | "rotationY") {
        return Err("Unsupported Transform keyframe removal property".into());
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
    let property = find_property_recursive_mut(transform, property_name)
        .ok_or_else(|| format!("Layer {layer_id} has no {property_name} property"))?;
    match (property_name, &property.value) {
        ("position" | "scale" | "anchor" | "orientation", Value::Vec3(_)) | ("rotation" | "rotationX" | "rotationY", Value::Scalar(_)) => {},
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
    retime_roving_run(property, frame_rate);
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

/// Authors a hold-only Source Text key at the composition frame. The first
/// key seeds frame zero with the current document; each key preserves base
/// character/paragraph styles and changes only the text payload.
fn set_text_layer_text_keyframe(source: &str, composition_id: u64, layer_id: u64, frame: i64, text: &str) -> Result<String, String> {
    if frame < 0 { return Err("Keyframe frame must be non-negative".into()); }
    if text.chars().count() > 20_000 { return Err("Source Text must be at most 20,000 characters".into()); }
    let mut project: Project = serde_json::from_str(source)
        .map_err(|error| format!("Invalid EffectCraft project: {error}"))?;
    let comp = project.comp_mut(ItemId(composition_id))
        .ok_or_else(|| format!("Composition {composition_id} was not found"))?;
    let tick = Tick::from_rational(frame, comp.frame_rate.den, comp.frame_rate.num);
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
    if property.static_only { return Err("Source Text cannot be keyframed".into()); }
    doc.text = text.to_owned();
    if property.keys.is_empty() { property.set_animated(true, Tick::ZERO); }
    property.set_value_at(tick, Value::Text(Box::new(doc)));
    project.to_file_json().map_err(|error| format!("Unable to serialize project: {error}"))
}

/// Whole-layer Source Text edit (owner pick 2026-11-06, keyframes OFF):
/// drops every Source Text key and sets the static document text, so the
/// edit changes the whole layer everywhere. Clearing first matters: a
/// plain static set on a keyed property would only upsert a frame-0 key
/// through set_value_at, leaving the other keys in charge of their frames.
fn set_text_layer_text_whole_layer(source: &str, composition_id: u64, layer_id: u64, text: &str) -> Result<String, String> {
    if text.chars().count() > 20_000 {
        return Err("Source Text must be at most 20,000 characters".into());
    }
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
    doc.text = text.to_owned();
    property.set_animated(false, Tick::ZERO);
    property.set_value_at(Tick::ZERO, Value::Text(Box::new(doc)));
    project.to_file_json().map_err(|error| format!("Unable to serialize project: {error}"))
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

/// Sets the canonical TextDoc font family/name without discarding its text or styling.
fn set_text_layer_font(source: &str, composition_id: u64, layer_id: u64, font: &str) -> Result<String, String> {
    let font = font.trim();
    if font.is_empty() || font.chars().count() > 128 || font.chars().any(char::is_control) {
        return Err("Text font must be non-empty, at most 128 characters, and contain no control characters".into());
    }
    let owned = font.to_owned();
    edit_text_doc(source, composition_id, layer_id, |doc| {
        doc.font = owned;
        Ok(())
    })
}

/// Authors a hold key for the TextDoc font family at a composition frame.
fn set_text_layer_font_keyframe(source: &str, composition_id: u64, layer_id: u64, frame: i64, font: &str) -> Result<String, String> {
    if frame < 0 { return Err("Keyframe frame must be non-negative".into()); }
    let font = font.trim();
    if font.is_empty() || font.chars().count() > 128 || font.chars().any(char::is_control) {
        return Err("Text font must be non-empty, at most 128 characters, and contain no control characters".into());
    }
    let mut project: Project = serde_json::from_str(source)
        .map_err(|error| format!("Invalid EffectCraft project: {error}"))?;
    let comp = project.comp_mut(ItemId(composition_id))
        .ok_or_else(|| format!("Composition {composition_id} was not found"))?;
    let tick = Tick::from_rational(frame, comp.frame_rate.den, comp.frame_rate.num);
    let layer = comp.layers.find_mut(|layer| layer.id == LayerId(layer_id))
        .ok_or_else(|| format!("Layer {layer_id} was not found in composition {composition_id}"))?;
    require_layer_unlocked(layer)?;
    let property = layer.props.sub_mut("text")
        .and_then(|group| group.prop_mut("sourceText"))
        .ok_or_else(|| format!("Layer {layer_id} has no Source Text property"))?;
    if property.static_only { return Err("Source Text font cannot be keyframed".into()); }
    // Text/font keys share one canonical TextDoc property. Start from the
    // document active at this frame so a font change never rewinds text or styling.
    let mut doc = property.keys.iter()
        .filter(|key| key.time <= tick)
        .max_by_key(|key| key.time)
        .and_then(|key| match &key.value { Value::Text(doc) => Some((**doc).clone()), _ => None })
        .or_else(|| match &property.value { Value::Text(doc) => Some((**doc).clone()), _ => None })
        .ok_or_else(|| format!("Layer {layer_id} Source Text is not a text document"))?;
    doc.font = font.to_owned();
    if property.keys.is_empty() { property.set_animated(true, Tick::ZERO); }
    property.set_value_at(tick, Value::Text(Box::new(doc)));
    project.to_file_json().map_err(|error| format!("Unable to serialize project: {error}"))
}

/// Authors a Source Text tracking hold key at the composition frame. Text,
/// font and styling are taken from the document active at that frame because
/// tracking shares the canonical TextDoc property with other text attributes.
fn set_text_layer_tracking_keyframe(source: &str, composition_id: u64, layer_id: u64, frame: i64, tracking: f64) -> Result<String, String> {
    if frame < 0 { return Err("Keyframe frame must be non-negative".into()); }
    if !tracking.is_finite() || tracking.abs() > 10_000.0 {
        return Err("Text tracking must be finite and within ±10,000".into());
    }
    let mut project: Project = serde_json::from_str(source)
        .map_err(|error| format!("Invalid EffectCraft project: {error}"))?;
    let comp = project.comp_mut(ItemId(composition_id))
        .ok_or_else(|| format!("Composition {composition_id} was not found"))?;
    let tick = Tick::from_rational(frame, comp.frame_rate.den, comp.frame_rate.num);
    let layer = comp.layers.find_mut(|layer| layer.id == LayerId(layer_id))
        .ok_or_else(|| format!("Layer {layer_id} was not found in composition {composition_id}"))?;
    require_layer_unlocked(layer)?;
    let property = layer.props.sub_mut("text")
        .and_then(|group| group.prop_mut("sourceText"))
        .ok_or_else(|| format!("Layer {layer_id} has no Source Text property"))?;
    if property.static_only { return Err("Source Text tracking cannot be keyframed".into()); }
    let mut doc = property.keys.iter()
        .filter(|key| key.time <= tick)
        .max_by_key(|key| key.time)
        .and_then(|key| match &key.value { Value::Text(doc) => Some((**doc).clone()), _ => None })
        .or_else(|| match &property.value { Value::Text(doc) => Some((**doc).clone()), _ => None })
        .ok_or_else(|| format!("Layer {layer_id} Source Text is not a text document"))?;
    doc.tracking = tracking;
    if property.keys.is_empty() { property.set_animated(true, Tick::ZERO); }
    property.set_value_at(tick, Value::Text(Box::new(doc)));
    project.to_file_json().map_err(|error| format!("Unable to serialize project: {error}"))
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

fn set_layer_color_property(
    source: &str, composition_id: u64, layer_id: u64, uid: u64,
    red: f64, green: f64, blue: f64, alpha: f64,
) -> Result<String, String> {
    let rgba = [red, green, blue, alpha];
    if rgba.iter().any(|component| !component.is_finite() || !(0.0..=1.0).contains(component)) {
        return Err("Color components must be finite and between 0 and 1".into());
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
    if !matches!(&property.value, Value::Color(_)) {
        return Err(format!("Parameter {uid} is not a color"));
    }
    property.set_value_at(Tick::ZERO, Value::Color(rgba));
    project.to_file_json().map_err(|error| format!("Unable to serialize project: {error}"))
}

fn set_layer_color_keyframe(
    source: &str, composition_id: u64, layer_id: u64, uid: u64, frame: i64,
    red: f64, green: f64, blue: f64, alpha: f64,
) -> Result<String, String> {
    if frame < 0 { return Err("Keyframe frame must be non-negative".into()); }
    let rgba = [red, green, blue, alpha];
    if rgba.iter().any(|component| !component.is_finite() || !(0.0..=1.0).contains(component)) {
        return Err("Color components must be finite and between 0 and 1".into());
    }
    let mut project: Project = serde_json::from_str(source)
        .map_err(|error| format!("Invalid EffectCraft project: {error}"))?;
    let comp = project.comp_mut(ItemId(composition_id))
        .ok_or_else(|| format!("Composition {composition_id} was not found"))?;
    let frame_rate = comp.frame_rate;
    let layer = comp.layers.find_mut(|layer| layer.id == LayerId(layer_id))
        .ok_or_else(|| format!("Layer {layer_id} was not found in composition {composition_id}"))?;
    require_layer_unlocked(layer)?;
    let property = layer.props.prop_mut(&format!("@{uid}"))
        .ok_or_else(|| format!("Layer {layer_id} has no parameter with uid {uid}"))?;
    if !matches!(&property.value, Value::Color(_)) {
        return Err(format!("Parameter {uid} is not a color"));
    }
    let tick = Tick::from_rational(frame, frame_rate.den, frame_rate.num);
    if property.keys.is_empty() { property.set_animated(true, Tick::ZERO); }
    property.set_value_at(tick, Value::Color(rgba));
    project.to_file_json().map_err(|error| format!("Unable to serialize project: {error}"))
}

/// Parses a gradient JSON object `{"colors": [[position, [r, g, b, a]], ...],
/// "opacities": [[position, opacity], ...]}` into an upstream `Gradient`.
/// Positions and opacities must be finite and within 0..=1, color channels
/// finite and within 0..=1, and both stop lists non-empty. Stops are sorted
/// by position so the stored gradient is deterministic.
fn parse_gradient_json(value: &serde_json::Value) -> Result<Gradient, String> {
    let colors_value = value.get("colors").and_then(|v| v.as_array())
        .ok_or_else(|| "Gradient must have a \"colors\" array".to_owned())?;
    if colors_value.is_empty() {
        return Err("Gradient must have at least one color stop".into());
    }
    let mut colors: Vec<(f64, [f32; 4])> = Vec::with_capacity(colors_value.len());
    for stop in colors_value {
        let pair = stop.as_array().ok_or_else(|| "Gradient color stop must be [position, [r, g, b, a]]".to_owned())?;
        if pair.len() != 2 {
            return Err("Gradient color stop must be [position, [r, g, b, a]]".into());
        }
        let position = pair[0].as_f64().ok_or_else(|| "Gradient stop position must be a number".to_owned())?;
        if !position.is_finite() || !(0.0..=1.0).contains(&position) {
            return Err("Gradient stop position must be finite and between 0 and 1".into());
        }
        let channels = pair[1].as_array().ok_or_else(|| "Gradient color stop must be [position, [r, g, b, a]]".to_owned())?;
        if channels.len() != 4 {
            return Err("Gradient color stop must carry four channels".into());
        }
        let mut rgba = [0.0f32; 4];
        for (index, channel) in channels.iter().enumerate() {
            let component = channel.as_f64().ok_or_else(|| "Gradient color channels must be numbers".to_owned())?;
            if !component.is_finite() || !(0.0..=1.0).contains(&component) {
                return Err("Gradient color components must be finite and between 0 and 1".into());
            }
            rgba[index] = component as f32;
        }
        colors.push((position, rgba));
    }
    let opacities_value = value.get("opacities").and_then(|v| v.as_array())
        .ok_or_else(|| "Gradient must have an \"opacities\" array".to_owned())?;
    if opacities_value.is_empty() {
        return Err("Gradient must have at least one opacity stop".into());
    }
    let mut opacities: Vec<(f64, f32)> = Vec::with_capacity(opacities_value.len());
    for stop in opacities_value {
        let pair = stop.as_array().ok_or_else(|| "Gradient opacity stop must be [position, opacity]".to_owned())?;
        if pair.len() != 2 {
            return Err("Gradient opacity stop must be [position, opacity]".into());
        }
        let position = pair[0].as_f64().ok_or_else(|| "Gradient stop position must be a number".to_owned())?;
        let opacity = pair[1].as_f64().ok_or_else(|| "Gradient opacity must be a number".to_owned())?;
        if !position.is_finite() || !(0.0..=1.0).contains(&position) {
            return Err("Gradient stop position must be finite and between 0 and 1".into());
        }
        if !opacity.is_finite() || !(0.0..=1.0).contains(&opacity) {
            return Err("Gradient opacity must be finite and between 0 and 1".into());
        }
        opacities.push((position, opacity as f32));
    }
    colors.sort_by(|a, b| a.0.total_cmp(&b.0));
    opacities.sort_by(|a, b| a.0.total_cmp(&b.0));
    Ok(Gradient { colors, opacities })
}

/// Sets the static value of a gradient (Gradient) parameter from its JSON
/// object (see `parse_gradient_json`). Mirrors `set_layer_color_property`:
/// the whole gradient is replaced at frame zero.
fn set_layer_gradient_property(
    source: &str, composition_id: u64, layer_id: u64, uid: u64,
    gradient_json: &str,
) -> Result<String, String> {
    let parsed: serde_json::Value = serde_json::from_str(gradient_json)
        .map_err(|error| format!("Invalid gradient: {error}"))?;
    let gradient = parse_gradient_json(&parsed)?;
    let mut project: Project = serde_json::from_str(source)
        .map_err(|error| format!("Invalid EffectCraft project: {error}"))?;
    let property = load_param_property_mut(&mut project, composition_id, layer_id, uid)?;
    if !matches!(&property.value, Value::Gradient(_)) {
        return Err(format!("Parameter {uid} is not a gradient"));
    }
    property.set_value_at(Tick::ZERO, Value::Gradient(gradient));
    project.to_file_json().map_err(|error| format!("Unable to serialize project: {error}"))
}

/// Replaces a Path parameter from its canonical ShapePath JSON. The path is
/// bounded and validated before mutating the project so malformed geometry
/// cannot poison the native render session.
fn set_layer_path_property(
    source: &str, composition_id: u64, layer_id: u64, uid: u64,
    path_json: &str,
) -> Result<String, String> {
    let mut path: ShapePath = serde_json::from_str(path_json)
        .map_err(|error| format!("Invalid path: {error}"))?;
    if path.vertices.is_empty() || path.vertices.len() > 4096 {
        return Err("Path must contain between 1 and 4096 vertices".into());
    }
    if path.in_tangents.len() > path.vertices.len() || path.out_tangents.len() > path.vertices.len() {
        return Err("Path tangent count cannot exceed vertex count".into());
    }
    if path.vertices.iter().flatten().chain(path.in_tangents.iter().flatten()).chain(path.out_tangents.iter().flatten()).any(|v| !v.is_finite()) {
        return Err("Path vertices and tangents must be finite".into());
    }
    path.fit_tangents();
    let segment_count = path.segment_count();
    for feather in &path.feather {
        if feather.segment >= segment_count || !feather.t.is_finite() || !(0.0..=1.0).contains(&feather.t)
            || !feather.radius.is_finite() || !feather.tension.is_finite() || !(0.0..=1.0).contains(&feather.tension) {
            return Err("Path feather points contain an invalid segment, parameter, radius or tension".into());
        }
    }
    let mut project: Project = serde_json::from_str(source)
        .map_err(|error| format!("Invalid EffectCraft project: {error}"))?;
    let property = load_param_property_mut(&mut project, composition_id, layer_id, uid)?;
    if !matches!(&property.ui, ParamUi::Path) || !matches!(&property.value, Value::Path(_)) {
        return Err(format!("Parameter {uid} is not a Path"));
    }
    property.set_value_at(Tick::ZERO, Value::Path(path));
    project.to_file_json().map_err(|error| format!("Unable to serialize project: {error}"))
}

/// Authors a ShapePath key at a composition frame. Path values are kept as native
/// Value::Path keys (rather than flattening to a static property), so timeline
/// edits survive canonical serialization and can be evaluated by the renderer.
fn set_layer_path_keyframe(
    source: &str, composition_id: u64, layer_id: u64, uid: u64, frame: i64, path_json: &str,
) -> Result<String, String> {
    if frame < 0 { return Err("Keyframe frame must be non-negative".into()); }
    let mut path: ShapePath = serde_json::from_str(path_json)
        .map_err(|error| format!("Invalid path: {error}"))?;
    if path.vertices.is_empty() || path.vertices.len() > 4096 {
        return Err("Path must contain between 1 and 4096 vertices".into());
    }
    if path.in_tangents.len() > path.vertices.len() || path.out_tangents.len() > path.vertices.len() {
        return Err("Path tangent count cannot exceed vertex count".into());
    }
    if path.vertices.iter().flatten().chain(path.in_tangents.iter().flatten()).chain(path.out_tangents.iter().flatten()).any(|v| !v.is_finite()) {
        return Err("Path vertices and tangents must be finite".into());
    }
    path.fit_tangents();
    for feather in &path.feather {
        if feather.segment >= path.segment_count() || !feather.t.is_finite() || !(0.0..=1.0).contains(&feather.t)
            || !feather.radius.is_finite() || !feather.tension.is_finite() || !(0.0..=1.0).contains(&feather.tension) {
            return Err("Path feather points contain an invalid segment, parameter, radius or tension".into());
        }
    }
    let mut project: Project = serde_json::from_str(source)
        .map_err(|error| format!("Invalid EffectCraft project: {error}"))?;
    let frame_rate = project.comp(ItemId(composition_id))
        .ok_or_else(|| format!("Composition {composition_id} was not found"))?.frame_rate;
    let property = load_param_property_mut(&mut project, composition_id, layer_id, uid)?;
    if property.static_only { return Err(format!("Parameter {uid} cannot be keyframed")); }
    if !matches!(&property.ui, ParamUi::Path) || !matches!(&property.value, Value::Path(_)) {
        return Err(format!("Parameter {uid} is not a Path"));
    }
    if property.keys.is_empty() { property.set_animated(true, Tick::ZERO); }
    property.set_value_at(Tick::from_rational(frame, frame_rate.den, frame_rate.num), Value::Path(path));
    project.to_file_json().map_err(|error| format!("Unable to serialize project: {error}"))
}

/// Sets a static Layer Control reference. None clears the control; a non-null
/// target must be a layer in the same composition. Animated Layer controls
/// are rejected rather than silently replacing their keyframes.
fn set_layer_reference_property(source: &str, composition_id: u64, layer_id: u64, uid: u64, target_layer_id: Option<u64>) -> Result<String, String> {
    let mut project: Project = serde_json::from_str(source).map_err(|error| format!("Invalid EffectCraft project: {error}"))?;
    {
        let comp = project.comp_mut(ItemId(composition_id)).ok_or_else(|| format!("Composition {composition_id} was not found"))?;
        if let Some(target) = target_layer_id {
            if !comp.layers.iter().any(|layer| layer.id.0 == target) { return Err(format!("Layer reference target {target} is not in composition {composition_id}")); }
        }
    }
    let property = load_param_property_mut(&mut project, composition_id, layer_id, uid)?;
    if !matches!(&property.value, Value::Layer(_)) { return Err(format!("Parameter {uid} is not a layer reference")); }
    if !property.keys.is_empty() { return Err(format!("Parameter {uid} already has keyframes; animated Layer Control editing is not supported yet")); }
    property.set_value_at(Tick::ZERO, Value::Layer(target_layer_id));
    project.to_file_json().map_err(|error| format!("Unable to serialize project: {error}"))
}

/// Authors a discrete Layer Control keyframe at a composition frame.
fn set_layer_reference_keyframe(source: &str, composition_id: u64, layer_id: u64, uid: u64, frame: i64, target_layer_id: Option<u64>) -> Result<String, String> {
    if frame < 0 { return Err("Keyframe frame must be non-negative".into()); }
    let mut project: Project = serde_json::from_str(source).map_err(|error| format!("Invalid EffectCraft project: {error}"))?;
    let frame_rate = project.comp(ItemId(composition_id)).ok_or_else(|| format!("Composition {composition_id} was not found"))?.frame_rate;
    if let Some(target) = target_layer_id {
        let comp = project.comp(ItemId(composition_id)).ok_or_else(|| format!("Composition {composition_id} was not found"))?;
        if !comp.layers.iter().any(|layer| layer.id.0 == target) { return Err(format!("Layer reference target {target} is not in composition {composition_id}")); }
    }
    let property = load_param_property_mut(&mut project, composition_id, layer_id, uid)?;
    if property.static_only { return Err(format!("Parameter {uid} cannot be keyframed")); }
    if !matches!(&property.value, Value::Layer(_)) { return Err(format!("Parameter {uid} is not a layer reference")); }
    property.hold_only = true;
    if property.keys.is_empty() { property.set_animated(true, Tick::ZERO); }
    property.set_value_at(Tick::from_rational(frame, frame_rate.den, frame_rate.num), Value::Layer(target_layer_id));
    project.to_file_json().map_err(|error| format!("Unable to serialize project: {error}"))
}

/// Loads one shape-item or effect parameter for mutation, addressed by
/// its native uid. Shared by the bool/popup setters so the lookup (and
/// the locked-layer rejection) lives in exactly one place.
fn load_param_property_mut<'a>(
    project: &'a mut Project,
    composition_id: u64,
    layer_id: u64,
    uid: u64,
) -> Result<&'a mut effectcraft_project::props::Property, String> {
    let comp = project.comp_mut(ItemId(composition_id))
        .ok_or_else(|| format!("Composition {composition_id} was not found"))?;
    let layer = comp.layers.find_mut(|layer| layer.id == LayerId(layer_id))
        .ok_or_else(|| format!("Layer {layer_id} was not found in composition {composition_id}"))?;
    require_layer_unlocked(layer)?;
    let property = layer.props.prop_mut(&format!("@{uid}"))
        .ok_or_else(|| format!("Layer {layer_id} has no parameter with uid {uid}"))?;
    // Bool/Enum values never interpolate by construction, so a checkbox or
    // popup is hold-only whatever the file says: hand-built or older files
    // may omit the `hold_only` flag (serde defaults it to false), and
    // trusting it would author Linear keys for a value that cannot tween.
    // Deriving it here matches upstream `Property::new`, and the repaired
    // flag is written back when the edit is saved.
    if matches!(&property.value, Value::Bool(_) | Value::Enum(_)) {
        property.hold_only = true;
    }
    Ok(property)
}

/// Number of options of a popup parameter, when its UI carries one.
fn popup_option_count(property: &effectcraft_project::props::Property) -> Option<usize> {
    match &property.ui {
        ParamUi::Popup { options } => Some(options.len()),
        _ => None,
    }
}

/// Sets the static value of a checkbox (Bool) parameter. Refuses when
/// the property already has keys: a static write must never silently
/// upsert a frame-0 key — change the value with a hold keyframe instead.
fn set_layer_bool_property(source: &str, composition_id: u64, layer_id: u64, uid: u64, value: bool) -> Result<String, String> {
    let mut project: Project = serde_json::from_str(source)
        .map_err(|error| format!("Invalid EffectCraft project: {error}"))?;
    let property = load_param_property_mut(&mut project, composition_id, layer_id, uid)?;
    if !matches!(&property.value, Value::Bool(_)) {
        return Err(format!("Parameter {uid} is not a checkbox"));
    }
    if !property.keys.is_empty() {
        return Err(format!("Parameter {uid} already has keyframes; change its value with a hold keyframe"));
    }
    property.set_value_at(Tick::ZERO, Value::Bool(value));
    project.to_file_json().map_err(|error| format!("Unable to serialize project: {error}"))
}

/// Sets the static value of a popup (Enum) parameter: the 0-based index
/// into the popup's options. Refuses out-of-range indices and refuses
/// when the property already has keys (see `set_layer_bool_property`).
fn set_layer_popup_property(source: &str, composition_id: u64, layer_id: u64, uid: u64, index: i64) -> Result<String, String> {
    if index < 0 {
        return Err("Popup option index must be non-negative".into());
    }
    let mut project: Project = serde_json::from_str(source)
        .map_err(|error| format!("Invalid EffectCraft project: {error}"))?;
    let property = load_param_property_mut(&mut project, composition_id, layer_id, uid)?;
    if !matches!(&property.value, Value::Enum(_)) {
        return Err(format!("Parameter {uid} is not a popup"));
    }
    if let Some(count) = popup_option_count(property) {
        if index as usize >= count {
            return Err(format!("Popup option {index} is out of range (0..{count})"));
        }
    }
    if !property.keys.is_empty() {
        return Err(format!("Parameter {uid} already has keyframes; change its value with a hold keyframe"));
    }
    property.set_value_at(Tick::ZERO, Value::Enum(index as u32));
    project.to_file_json().map_err(|error| format!("Unable to serialize project: {error}"))
}

/// Authors one hold keyframe for a checkbox (Bool) parameter at `frame`.
/// The first key seeds frame 0 with the parameter's current value, so a
/// key authored mid-timeline never rewrites earlier frames, matching the
/// scalar/vector/color keyframe setters. Upstream `hold_only` forces Hold
/// interpolation on both sides, so the value holds (never tweens) until
/// the next key — the generic hold-key path Gap 4 (Source Text) reuses.
fn set_layer_bool_keyframe(source: &str, composition_id: u64, layer_id: u64, uid: u64, frame: i64, value: bool) -> Result<String, String> {
    if frame < 0 { return Err("Keyframe frame must be non-negative".into()); }
    let mut project: Project = serde_json::from_str(source)
        .map_err(|error| format!("Invalid EffectCraft project: {error}"))?;
    let frame_rate = project.comp(ItemId(composition_id))
        .ok_or_else(|| format!("Composition {composition_id} was not found"))?.frame_rate;
    let property = load_param_property_mut(&mut project, composition_id, layer_id, uid)?;
    if property.static_only {
        return Err(format!("Parameter {uid} cannot be keyframed"));
    }
    if !matches!(&property.value, Value::Bool(_)) {
        return Err(format!("Parameter {uid} is not a checkbox"));
    }
    let tick = Tick::from_rational(frame, frame_rate.den, frame_rate.num);
    // Bool tracks are discrete: force Hold on both sides of every key.
    property.hold_only = true;
    if property.keys.is_empty() { property.set_animated(true, Tick::ZERO); }
    property.set_value_at(tick, Value::Bool(value));
    project.to_file_json().map_err(|error| format!("Unable to serialize project: {error}"))
}

/// Authors one hold keyframe for a popup (Enum) parameter at `frame`,
/// with the same seeding and hold semantics as `set_layer_bool_keyframe`.
fn set_layer_popup_keyframe(source: &str, composition_id: u64, layer_id: u64, uid: u64, frame: i64, index: i64) -> Result<String, String> {
    if frame < 0 { return Err("Keyframe frame must be non-negative".into()); }
    if index < 0 {
        return Err("Popup option index must be non-negative".into());
    }
    let mut project: Project = serde_json::from_str(source)
        .map_err(|error| format!("Invalid EffectCraft project: {error}"))?;
    let frame_rate = project.comp(ItemId(composition_id))
        .ok_or_else(|| format!("Composition {composition_id} was not found"))?.frame_rate;
    let property = load_param_property_mut(&mut project, composition_id, layer_id, uid)?;
    if property.static_only {
        return Err(format!("Parameter {uid} cannot be keyframed"));
    }
    if !matches!(&property.value, Value::Enum(_)) {
        return Err(format!("Parameter {uid} is not a popup"));
    }
    if let Some(count) = popup_option_count(property) {
        if index as usize >= count {
            return Err(format!("Popup option {index} is out of range (0..{count})"));
        }
    }
    let tick = Tick::from_rational(frame, frame_rate.den, frame_rate.num);
    // Popup indices are discrete: force Hold on both sides of every key.
    property.hold_only = true;
    if property.keys.is_empty() { property.set_animated(true, Tick::ZERO); }
    property.set_value_at(tick, Value::Enum(index as u32));
    project.to_file_json().map_err(|error| format!("Unable to serialize project: {error}"))
}

#[no_mangle]
pub extern "system" fn Java_com_codeair_effectcraft_engine_NativeProjectBridge_setLayerBoolPropertyEcprojNative(
    mut env: JNIEnv, _class: JClass, source: JString, composition_id: jlong, layer_id: jlong, uid: jlong, value: jni::sys::jboolean,
) -> jstring {
    let source: String = match env.get_string(&source) {
        Ok(value) => value.into(),
        Err(error) => return env.new_string(json!({"valid": false, "error": error.to_string()}).to_string()).map_or(std::ptr::null_mut(), |v| v.into_raw()),
    };
    let result = if composition_id < 0 || layer_id < 0 || uid < 0 {
        Err("Composition, layer and parameter IDs must be non-negative".to_owned())
    } else {
        set_layer_bool_property(&source, composition_id as u64, layer_id as u64, uid as u64, value != 0)
    };
    let output = match result {
        Ok(project_json) => json!({"valid": true, "project": project_json}).to_string(),
        Err(error) => json!({"valid": false, "error": error}).to_string(),
    };
    env.new_string(output).map_or(std::ptr::null_mut(), |value| value.into_raw())
}

#[no_mangle]
pub extern "system" fn Java_com_codeair_effectcraft_engine_NativeProjectBridge_setLayerPopupPropertyEcprojNative(
    mut env: JNIEnv, _class: JClass, source: JString, composition_id: jlong, layer_id: jlong, uid: jlong, index: jni::sys::jint,
) -> jstring {
    let source: String = match env.get_string(&source) {
        Ok(value) => value.into(),
        Err(error) => return env.new_string(json!({"valid": false, "error": error.to_string()}).to_string()).map_or(std::ptr::null_mut(), |v| v.into_raw()),
    };
    let result = if composition_id < 0 || layer_id < 0 || uid < 0 || index < 0 {
        Err("Composition, layer and parameter IDs and popup index must be non-negative".to_owned())
    } else {
        set_layer_popup_property(&source, composition_id as u64, layer_id as u64, uid as u64, index as i64)
    };
    let output = match result {
        Ok(project_json) => json!({"valid": true, "project": project_json}).to_string(),
        Err(error) => json!({"valid": false, "error": error}).to_string(),
    };
    env.new_string(output).map_or(std::ptr::null_mut(), |value| value.into_raw())
}

#[no_mangle]
pub extern "system" fn Java_com_codeair_effectcraft_engine_NativeProjectBridge_setLayerBoolKeyframeEcprojNative(
    mut env: JNIEnv, _class: JClass, source: JString, composition_id: jlong, layer_id: jlong, uid: jlong, frame: jlong, value: jni::sys::jboolean,
) -> jstring {
    let source: String = match env.get_string(&source) {
        Ok(value) => value.into(),
        Err(error) => return env.new_string(json!({"valid": false, "error": error.to_string()}).to_string()).map_or(std::ptr::null_mut(), |v| v.into_raw()),
    };
    let result = if composition_id < 0 || layer_id < 0 || uid < 0 || frame < 0 {
        Err("Composition, layer, parameter IDs and frame must be non-negative".to_owned())
    } else {
        set_layer_bool_keyframe(&source, composition_id as u64, layer_id as u64, uid as u64, frame as i64, value != 0)
    };
    let output = match result {
        Ok(project_json) => json!({"valid": true, "project": project_json}).to_string(),
        Err(error) => json!({"valid": false, "error": error}).to_string(),
    };
    env.new_string(output).map_or(std::ptr::null_mut(), |value| value.into_raw())
}

#[no_mangle]
pub extern "system" fn Java_com_codeair_effectcraft_engine_NativeProjectBridge_setLayerPopupKeyframeEcprojNative(
    mut env: JNIEnv, _class: JClass, source: JString, composition_id: jlong, layer_id: jlong, uid: jlong, frame: jlong, index: jni::sys::jint,
) -> jstring {
    let source: String = match env.get_string(&source) {
        Ok(value) => value.into(),
        Err(error) => return env.new_string(json!({"valid": false, "error": error.to_string()}).to_string()).map_or(std::ptr::null_mut(), |v| v.into_raw()),
    };
    let result = if composition_id < 0 || layer_id < 0 || uid < 0 || frame < 0 || index < 0 {
        Err("Composition, layer, parameter IDs, frame and popup index must be non-negative".to_owned())
    } else {
        set_layer_popup_keyframe(&source, composition_id as u64, layer_id as u64, uid as u64, frame as i64, index as i64)
    };
    let output = match result {
        Ok(project_json) => json!({"valid": true, "project": project_json}).to_string(),
        Err(error) => json!({"valid": false, "error": error}).to_string(),
    };
    env.new_string(output).map_or(std::ptr::null_mut(), |value| value.into_raw())
}

#[no_mangle]
pub extern "system" fn Java_com_codeair_effectcraft_engine_NativeProjectBridge_setLayerColorKeyframeEcprojNative(
    mut env: JNIEnv, _class: JClass, source: JString, composition_id: jlong, layer_id: jlong, uid: jlong, frame: jlong,
    red: jni::sys::jdouble, green: jni::sys::jdouble, blue: jni::sys::jdouble, alpha: jni::sys::jdouble,
) -> jstring {
    let source: String = match env.get_string(&source) {
        Ok(value) => value.into(),
        Err(error) => return env.new_string(json!({"valid": false, "error": error.to_string()}).to_string()).map_or(std::ptr::null_mut(), |v| v.into_raw()),
    };
    let result = if composition_id < 0 || layer_id < 0 || uid < 0 || frame < 0 {
        Err("Composition, layer, parameter IDs and frame must be non-negative".to_owned())
    } else {
        set_layer_color_keyframe(&source, composition_id as u64, layer_id as u64, uid as u64, frame as i64,
            red as f64, green as f64, blue as f64, alpha as f64)
    };
    let output = match result {
        Ok(project_json) => json!({"valid": true, "project": project_json}).to_string(),
        Err(error) => json!({"valid": false, "error": error}).to_string(),
    };
    env.new_string(output).map_or(std::ptr::null_mut(), |value| value.into_raw())
}

#[no_mangle]
pub extern "system" fn Java_com_codeair_effectcraft_engine_NativeProjectBridge_setLayerScaleEcprojNative(
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
pub extern "system" fn Java_com_codeair_effectcraft_engine_NativeProjectBridge_setLayerRotationEcprojNative(
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
pub extern "system" fn Java_com_codeair_effectcraft_engine_NativeProjectBridge_setLayerAnchorEcprojNative(
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
pub extern "system" fn Java_com_codeair_effectcraft_engine_NativeProjectBridge_setLayerOrientationEcprojNative(
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
pub extern "system" fn Java_com_codeair_effectcraft_engine_NativeProjectBridge_setLayerRotationXEcprojNative(
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
pub extern "system" fn Java_com_codeair_effectcraft_engine_NativeProjectBridge_setLayerRotationYEcprojNative(
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
pub extern "system" fn Java_com_codeair_effectcraft_engine_NativeProjectBridge_setLayerLockedEcprojNative(
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
pub extern "system" fn Java_com_codeair_effectcraft_engine_NativeProjectBridge_setTextLayerTextEcprojNative(
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
pub extern "system" fn Java_com_codeair_effectcraft_engine_NativeProjectBridge_setTextLayerTextWholeLayerEcprojNative(
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
        else { set_text_layer_text_whole_layer(&source, composition_id as u64, layer_id as u64, &text) };
    let output = match result { Ok(project_json) => json!({"valid": true, "project": project_json}).to_string(), Err(error) => json!({"valid": false, "error": error}).to_string() };
    env.new_string(output).map_or(std::ptr::null_mut(), |value| value.into_raw())
}

#[no_mangle]
pub extern "system" fn Java_com_codeair_effectcraft_engine_NativeProjectBridge_setTextLayerTextKeyframeEcprojNative(
    mut env: JNIEnv, _class: JClass, source: JString, composition_id: jlong, layer_id: jlong, frame: jlong, text: JString,
) -> jstring {
    let source: String = match env.get_string(&source) {
        Ok(value) => value.into(),
        Err(error) => return env.new_string(json!({"valid": false, "error": error.to_string()}).to_string()).map_or(std::ptr::null_mut(), |v| v.into_raw()),
    };
    let text: String = match env.get_string(&text) {
        Ok(value) => value.into(),
        Err(error) => return env.new_string(json!({"valid": false, "error": error.to_string()}).to_string()).map_or(std::ptr::null_mut(), |v| v.into_raw()),
    };
    let result = if composition_id < 0 || layer_id < 0 || frame < 0 {
        Err("Composition, layer and frame must be non-negative".to_owned())
    } else {
        set_text_layer_text_keyframe(&source, composition_id as u64, layer_id as u64, frame as i64, &text)
    };
    let output = match result { Ok(project_json) => json!({"valid": true, "project": project_json}).to_string(), Err(error) => json!({"valid": false, "error": error}).to_string() };
    env.new_string(output).map_or(std::ptr::null_mut(), |value| value.into_raw())
}

#[no_mangle]
pub extern "system" fn Java_com_codeair_effectcraft_engine_NativeProjectBridge_setTextLayerSizeEcprojNative(
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
pub extern "system" fn Java_com_codeair_effectcraft_engine_NativeProjectBridge_setTextLayerTrackingEcprojNative(
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
pub extern "system" fn Java_com_codeair_effectcraft_engine_NativeProjectBridge_setTextLayerFontEcprojNative(
    mut env: JNIEnv, _class: JClass, source: JString, composition_id: jlong, layer_id: jlong, font: JString,
) -> jstring {
    let source: String = match env.get_string(&source) {
        Ok(value) => value.into(),
        Err(error) => return env.new_string(json!({"valid": false, "error": error.to_string()}).to_string()).map_or(std::ptr::null_mut(), |v| v.into_raw()),
    };
    let font: String = match env.get_string(&font) {
        Ok(value) => value.into(),
        Err(error) => return env.new_string(json!({"valid": false, "error": error.to_string()}).to_string()).map_or(std::ptr::null_mut(), |v| v.into_raw()),
    };
    let result = if composition_id < 0 || layer_id < 0 { Err("Composition and layer IDs must be non-negative".to_owned()) }
        else { set_text_layer_font(&source, composition_id as u64, layer_id as u64, &font) };
    let output = match result { Ok(project_json) => json!({"valid": true, "project": project_json}).to_string(), Err(error) => json!({"valid": false, "error": error}).to_string() };
    env.new_string(output).map_or(std::ptr::null_mut(), |value| value.into_raw())
}

#[no_mangle]
pub extern "system" fn Java_com_codeair_effectcraft_engine_NativeProjectBridge_setTextLayerFontKeyframeEcprojNative(
    mut env: JNIEnv, _class: JClass, source: JString, composition_id: jlong, layer_id: jlong, frame: jlong, font: JString,
) -> jstring {
    let source: String = match env.get_string(&source) {
        Ok(value) => value.into(),
        Err(error) => return env.new_string(json!({"valid": false, "error": error.to_string()}).to_string()).map_or(std::ptr::null_mut(), |v| v.into_raw()),
    };
    let font: String = match env.get_string(&font) {
        Ok(value) => value.into(),
        Err(error) => return env.new_string(json!({"valid": false, "error": error.to_string()}).to_string()).map_or(std::ptr::null_mut(), |v| v.into_raw()),
    };
    let result = if composition_id < 0 || layer_id < 0 || frame < 0 {
        Err("Composition, layer and frame must be non-negative".to_owned())
    } else {
        set_text_layer_font_keyframe(&source, composition_id as u64, layer_id as u64, frame as i64, &font)
    };
    let output = match result { Ok(project_json) => json!({"valid": true, "project": project_json}).to_string(), Err(error) => json!({"valid": false, "error": error}).to_string() };
    env.new_string(output).map_or(std::ptr::null_mut(), |value| value.into_raw())
}

#[no_mangle]
pub extern "system" fn Java_com_codeair_effectcraft_engine_NativeProjectBridge_setTextLayerTrackingKeyframeEcprojNative(
    mut env: JNIEnv, _class: JClass, source: JString, composition_id: jlong, layer_id: jlong, frame: jlong, tracking: jni::sys::jdouble,
) -> jstring {
    let source: String = match env.get_string(&source) {
        Ok(value) => value.into(),
        Err(error) => return env.new_string(json!({"valid": false, "error": error.to_string()}).to_string()).map_or(std::ptr::null_mut(), |v| v.into_raw()),
    };
    let result = if composition_id < 0 || layer_id < 0 || frame < 0 {
        Err("Composition, layer and frame must be non-negative".to_owned())
    } else {
        set_text_layer_tracking_keyframe(&source, composition_id as u64, layer_id as u64, frame as i64, tracking as f64)
    };
    let output = match result { Ok(project_json) => json!({"valid": true, "project": project_json}).to_string(), Err(error) => json!({"valid": false, "error": error}).to_string() };
    env.new_string(output).map_or(std::ptr::null_mut(), |value| value.into_raw())
}

#[no_mangle]
pub extern "system" fn Java_com_codeair_effectcraft_engine_NativeProjectBridge_setLayerScalarPropertyEcprojNative(
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
pub extern "system" fn Java_com_codeair_effectcraft_engine_NativeProjectBridge_setLayerVecPropertyEcprojNative(
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
pub extern "system" fn Java_com_codeair_effectcraft_engine_NativeProjectBridge_setLayerColorPropertyEcprojNative(
    mut env: JNIEnv, _class: JClass, source: JString, composition_id: jlong, layer_id: jlong, uid: jlong,
    red: jni::sys::jdouble, green: jni::sys::jdouble, blue: jni::sys::jdouble, alpha: jni::sys::jdouble,
) -> jstring {
    let source: String = match env.get_string(&source) {
        Ok(value) => value.into(),
        Err(error) => return env.new_string(json!({"valid": false, "error": error.to_string()}).to_string()).map_or(std::ptr::null_mut(), |v| v.into_raw()),
    };
    let result = if composition_id < 0 || layer_id < 0 || uid < 0 {
        Err("Composition, layer and parameter IDs must be non-negative".to_owned())
    } else {
        set_layer_color_property(&source, composition_id as u64, layer_id as u64, uid as u64,
            red as f64, green as f64, blue as f64, alpha as f64)
    };
    let output = match result {
        Ok(project_json) => json!({"valid": true, "project": project_json}).to_string(),
        Err(error) => json!({"valid": false, "error": error}).to_string(),
    };
    env.new_string(output).map_or(std::ptr::null_mut(), |value| value.into_raw())
}

#[no_mangle]
pub extern "system" fn Java_com_codeair_effectcraft_engine_NativeProjectBridge_setLayerGradientPropertyEcprojNative(
    mut env: JNIEnv, _class: JClass, source: JString, composition_id: jlong, layer_id: jlong, uid: jlong,
    gradient_json: JString,
) -> jstring {
    let source: String = match env.get_string(&source) {
        Ok(value) => value.into(),
        Err(error) => return env.new_string(json!({"valid": false, "error": error.to_string()}).to_string()).map_or(std::ptr::null_mut(), |v| v.into_raw()),
    };
    let gradient_json: String = match env.get_string(&gradient_json) {
        Ok(value) => value.into(),
        Err(error) => return env.new_string(json!({"valid": false, "error": error.to_string()}).to_string()).map_or(std::ptr::null_mut(), |v| v.into_raw()),
    };
    let result = if composition_id < 0 || layer_id < 0 || uid < 0 {
        Err("Composition, layer and parameter IDs must be non-negative".to_owned())
    } else {
        set_layer_gradient_property(&source, composition_id as u64, layer_id as u64, uid as u64, &gradient_json)
    };
    let output = match result {
        Ok(project_json) => json!({"valid": true, "project": project_json}).to_string(),
        Err(error) => json!({"valid": false, "error": error}).to_string(),
    };
    env.new_string(output).map_or(std::ptr::null_mut(), |value| value.into_raw())
}

#[no_mangle]
pub extern "system" fn Java_com_codeair_effectcraft_engine_NativeProjectBridge_setLayerPathPropertyEcprojNative(
    mut env: JNIEnv, _class: JClass, source: JString, composition_id: jlong, layer_id: jlong, uid: jlong,
    path_json: JString,
) -> jstring {
    let source: String = match env.get_string(&source) {
        Ok(value) => value.into(),
        Err(error) => return env.new_string(json!({"valid": false, "error": error.to_string()}).to_string()).map_or(std::ptr::null_mut(), |v| v.into_raw()),
    };
    let path_json: String = match env.get_string(&path_json) {
        Ok(value) => value.into(),
        Err(error) => return env.new_string(json!({"valid": false, "error": error.to_string()}).to_string()).map_or(std::ptr::null_mut(), |v| v.into_raw()),
    };
    let result = if composition_id < 0 || layer_id < 0 || uid < 0 {
        Err("Composition, layer and parameter IDs must be non-negative".to_owned())
    } else {
        set_layer_path_property(&source, composition_id as u64, layer_id as u64, uid as u64, &path_json)
    };
    let output = match result {
        Ok(project_json) => json!({"valid": true, "project": project_json}).to_string(),
        Err(error) => json!({"valid": false, "error": error}).to_string(),
    };
    env.new_string(output).map_or(std::ptr::null_mut(), |value| value.into_raw())
}

#[no_mangle]
pub extern "system" fn Java_com_codeair_effectcraft_engine_NativeProjectBridge_setLayerPathKeyframeEcprojNative(
    mut env: JNIEnv, _class: JClass, source: JString, composition_id: jlong, layer_id: jlong, uid: jlong, frame: jlong, path_json: JString,
) -> jstring {
    let source: String = match env.get_string(&source) {
        Ok(value) => value.into(),
        Err(error) => return env.new_string(json!({"valid": false, "error": error.to_string()}).to_string()).map_or(std::ptr::null_mut(), |v| v.into_raw()),
    };
    let path_json: String = match env.get_string(&path_json) {
        Ok(value) => value.into(),
        Err(error) => return env.new_string(json!({"valid": false, "error": error.to_string()}).to_string()).map_or(std::ptr::null_mut(), |v| v.into_raw()),
    };
    let result = if composition_id < 0 || layer_id < 0 || uid < 0 || frame < 0 {
        Err("Composition, layer, uid and frame must be non-negative".to_owned())
    } else {
        set_layer_path_keyframe(&source, composition_id as u64, layer_id as u64, uid as u64, frame as i64, &path_json)
    };
    let output = match result {
        Ok(project_json) => json!({"valid": true, "project": project_json}).to_string(),
        Err(error) => json!({"valid": false, "error": error}).to_string(),
    };
    env.new_string(output).map_or(std::ptr::null_mut(), |value| value.into_raw())
}

#[no_mangle]
pub extern "system" fn Java_com_codeair_effectcraft_engine_NativeProjectBridge_setLayerReferencePropertyEcprojNative(
    mut env: JNIEnv, _class: JClass, source: JString, composition_id: jlong, layer_id: jlong, uid: jlong, target_layer_id: jlong,
) -> jstring {
    let source: String = match env.get_string(&source) {
        Ok(value) => value.into(),
        Err(error) => return env.new_string(json!({"valid": false, "error": error.to_string()}).to_string()).map_or(std::ptr::null_mut(), |v| v.into_raw()),
    };
    let result = if composition_id < 0 || layer_id < 0 || uid < 0 || target_layer_id < -1 {
        Err("Composition, layer and parameter IDs must be non-negative; target must be -1 (None) or a layer ID".to_owned())
    } else { set_layer_reference_property(&source, composition_id as u64, layer_id as u64, uid as u64, if target_layer_id == -1 { None } else { Some(target_layer_id as u64) }) };
    let output = match result {
        Ok(project_json) => json!({"valid": true, "project": project_json}).to_string(),
        Err(error) => json!({"valid": false, "error": error}).to_string(),
    };
    env.new_string(output).map_or(std::ptr::null_mut(), |value| value.into_raw())
}

#[no_mangle]
pub extern "system" fn Java_com_codeair_effectcraft_engine_NativeProjectBridge_setLayerReferenceKeyframeEcprojNative(
    mut env: JNIEnv, _class: JClass, source: JString, composition_id: jlong, layer_id: jlong, uid: jlong, frame: jlong, target_layer_id: jlong,
) -> jstring {
    let source: String = match env.get_string(&source) {
        Ok(value) => value.into(),
        Err(error) => return env.new_string(json!({"valid": false, "error": error.to_string()}).to_string()).map_or(std::ptr::null_mut(), |v| v.into_raw()),
    };
    let result = if composition_id < 0 || layer_id < 0 || uid < 0 || frame < 0 || target_layer_id < -1 {
        Err("Composition, layer, uid and frame must be non-negative; target must be -1 (None) or a layer ID".to_owned())
    } else {
        set_layer_reference_keyframe(&source, composition_id as u64, layer_id as u64, uid as u64, frame as i64, if target_layer_id == -1 { None } else { Some(target_layer_id as u64) })
    };
    let output = match result { Ok(project_json) => json!({"valid": true, "project": project_json}).to_string(), Err(error) => json!({"valid": false, "error": error}).to_string() };
    env.new_string(output).map_or(std::ptr::null_mut(), |value| value.into_raw())
}

#[no_mangle]
pub extern "system" fn Java_com_codeair_effectcraft_engine_NativeProjectBridge_setLayerPositionEcprojNative(
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
    let valid_property = matches!(property_name, "position" | "scale" | "rotation" | "anchor" | "orientation" | "rotationX" | "rotationY");
    if !valid_property { return Err("Unsupported Transform keyframe property".into()); }
    let limit = match property_name { "position" | "anchor" => 1_000_000.0, "scale" => 100_000.0, _ => 360_000.0 };
    if x.abs() > limit || (matches!(property_name, "position" | "scale" | "anchor") && y.abs() > limit) ||
        (property_name == "orientation" && (y.abs() > limit || z.abs() > limit)) {
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
        ("position" | "scale" | "anchor", Value::Vec3(current)) => Value::Vec3([x, y, current[2]]),
        ("orientation", Value::Vec3(_)) => Value::Vec3([x, y, z]),
        ("rotation" | "rotationX" | "rotationY", Value::Scalar(_)) => Value::Scalar(x),
        _ => return Err(format!("Layer {layer_id} Transform {property_name} has an incompatible value type")),
    };
    // First keyframe must land at the authored frame, not at zero: seeding a
    // zero key here would leave a phantom key the user never authored, so the
    // authored key would sit at keys[1], timeline tracks would report [0, 15]
    // instead of [15], and interpolation lookups by authored frame would miss
    // the first key. Seeding at the authored tick makes set_value_at replace
    // that seed with the authored value, leaving exactly one key.
    let tick = Tick::from_rational(frame, frame_rate.den, frame_rate.num);
    let was_static = property.keys.is_empty();
    if was_static {
        // Upstream set_animated seeds the static value at the requested tick.
        // Seed at zero so set_value_at can create the authored key at any
        // frame, then remove only that synthetic seed when the authored key
        // is later than zero. This preserves exactly the key the user asked
        // for and avoids a phantom frame-zero marker/value.
        property.set_animated(true, Tick::ZERO);
    }
    property.set_value_at(tick, value);
    if was_static && tick != Tick::ZERO {
        property.keys.retain(|key| key.time != Tick::ZERO);
    }
    // A new fixed key splits a roving run (or a new key lands inside one),
    // so the survivors re-space by path length.
    retime_roving_run(property, frame_rate);
    project.to_file_json().map_err(|error| format!("Unable to serialize project: {error}"))
}

/// Applies a real upstream interpolation preset to one or both sides of a transform key.
fn set_layer_transform_keyframe_interpolation(
    source: &str, composition_id: u64, layer_id: u64, property_name: &str,
    frame: i64, preset: &str,
) -> Result<String, String> {
    if frame < 0 { return Err("Keyframe frame must be non-negative".into()); }
    if !matches!(property_name, "position" | "scale" | "rotation" | "anchor" | "orientation" | "rotationX" | "rotationY") {
        return Err("Unsupported Transform keyframe interpolation property".into());
    }
    // Legacy names continue to edit both sides; side-qualified names preserve
    // the opposite tangent/interpolation when editing one side in the inspector.
    // Directional presets ignore the qualifier: their direction IS their side
    // (ease_in eases the outgoing side, ease_out the incoming side), so a
    // qualifier can neither redirect nor silence them.
    let (incoming, outgoing, preset) = if let Some(preset) = preset.strip_prefix("incoming_") {
        (true, false, preset)
    } else if let Some(preset) = preset.strip_prefix("outgoing_") {
        (false, true, preset)
    } else {
        (true, true, preset)
    };
    if !matches!(preset, "linear" | "easy_ease" | "ease_in" | "ease_out" | "ease_in_out" | "bezier" | "hold") {
        return Err("Interpolation preset must be linear, easy_ease, ease_in, ease_out, ease_in_out, bezier or hold (optionally incoming_/outgoing_)".into());
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
    let dims = key.value.dims().max(1);
    match preset {
        "linear" => {
            if incoming { key.in_interp = Interp::Linear; key.in_ease.clear(); }
            if outgoing { key.out_interp = Interp::Linear; key.out_ease.clear(); }
        }
        "easy_ease" | "ease_in_out" => {
            let eased = key.clone().eased();
            if incoming { key.in_interp = eased.in_interp; key.in_ease = eased.in_ease; }
            if outgoing { key.out_interp = eased.out_interp; key.out_ease = eased.out_ease; }
        }
        "ease_in" => {
            key.out_interp = Interp::Bezier;
            key.out_ease = vec![Ease::EASY; dims];
        }
        "ease_out" => {
            key.in_interp = Interp::Bezier;
            key.in_ease = vec![Ease::EASY; dims];
        }
        "bezier" => {
            if incoming { key.in_interp = Interp::Bezier; key.in_ease = vec![Ease { speed: 0.0, influence: 0.25 }; dims]; }
            if outgoing { key.out_interp = Interp::Bezier; key.out_ease = vec![Ease { speed: 0.0, influence: 0.75 }; dims]; }
        }
        "hold" => {
            if incoming { key.in_interp = Interp::Hold; key.in_ease.clear(); }
            if outgoing { key.out_interp = Interp::Hold; key.out_ease.clear(); }
        }
        _ => unreachable!("preset validated above"),
    }
    key.auto_bezier = false;
    key.continuous = false;
    project.to_file_json().map_err(|error| format!("Unable to serialize project: {error}"))
}

/// Resolves a curve-graph property for Bezier and drag edits. Selectors are
/// "transform:position", "transform:scale", "transform:rotation",
/// "transform:opacity", or "param:<uid>" for a shape-item or effect scalar or
/// vector parameter. Transform properties are found recursively inside the
/// Transform group, matching the walk used by the opacity editor.
fn resolve_curve_property_mut<'a>(
    layer: &'a mut effectcraft_project::Layer,
    selector: &str,
) -> Result<&'a mut effectcraft_project::props::Property, String> {
    if let Some(name) = selector.strip_prefix("transform:") {
        if !matches!(name, "position" | "scale" | "rotation" | "opacity" | "anchor" | "orientation" | "rotationX" | "rotationY") {
            return Err(format!("Unsupported transform curve property: {name}"));
        }
        let transform = layer.props.sub_mut("transform")
            .ok_or_else(|| format!("Layer {} has no Transform group", layer.id.0))?;
        return find_property_recursive_mut(transform, name)
            .ok_or_else(|| format!("Layer {} has no Transform {name} property", layer.id.0));
    }
    if let Some(uid_text) = selector.strip_prefix("param:") {
        let uid: u64 = uid_text.parse()
            .map_err(|_| format!("Invalid parameter curve selector: {selector}"))?;
        return layer.props.prop_mut(&format!("@{uid}"))
            .ok_or_else(|| format!("Layer {} has no parameter with uid {uid}", layer.id.0));
    }
    Err(format!("Invalid curve property selector: {selector}"))
}

fn find_property_recursive_mut<'a>(
    group: &'a mut effectcraft_project::props::PropGroup,
    match_id: &str,
) -> Option<&'a mut effectcraft_project::props::Property> {
    for child in &mut group.children {
        match child {
            Node::Prop(property) if property.match_id == match_id => return Some(property),
            Node::Group(child_group) => {
                if let Some(found) = find_property_recursive_mut(child_group, match_id) {
                    return Some(found);
                }
            }
            _ => {}
        }
    }
    None
}

/// Applies one custom Bezier handle (speed in value units per second,
/// influence as a 0..1 fraction of the adjacent segment) to one or both
/// sides of an existing curve key. The opposite side is preserved, unlike
/// the preset setter which rewrites both sides for an unqualified preset.
/// Speed may be negative on a falling segment; influence outside 0..1,
/// a missing key, a hold-only property or a non-animatable value rejects.
fn set_keyframe_bezier(
    source: &str, composition_id: u64, layer_id: u64, selector: &str,
    frame: i64, side: &str, speed: f64, influence: f64,
) -> Result<String, String> {
    if frame < 0 { return Err("Keyframe frame must be non-negative".into()); }
    if !matches!(side, "incoming" | "outgoing" | "both") {
        return Err("Curve handle side must be incoming, outgoing or both".into());
    }
    if !speed.is_finite() { return Err("Curve handle speed must be finite".into()); }
    if !(0.0..=1.0).contains(&influence) {
        return Err("Curve handle influence must be between 0 and 1".into());
    }
    let mut project: Project = serde_json::from_str(source)
        .map_err(|error| format!("Invalid EffectCraft project: {error}"))?;
    let comp = project.comp_mut(ItemId(composition_id))
        .ok_or_else(|| format!("Composition {composition_id} was not found"))?;
    let frame_rate = comp.frame_rate;
    let layer = comp.layers.find_mut(|layer| layer.id == LayerId(layer_id))
        .ok_or_else(|| format!("Layer {layer_id} was not found in composition {composition_id}"))?;
    require_layer_unlocked(layer)?;
    let property = resolve_curve_property_mut(layer, selector)?;
    match &property.value {
        Value::Scalar(_) | Value::Vec2(_) | Value::Vec3(_) => {},
        _ => return Err(format!("Curve property {selector} is not animatable")),
    }
    if property.static_only {
        return Err(format!("Curve property {selector} cannot be keyframed"));
    }
    if property.hold_only {
        return Err(format!("Curve property {selector} only holds keyframes"));
    }
    let tick = Tick::from_rational(frame, frame_rate.den, frame_rate.num);
    let key = property.keys.iter_mut().find(|key| key.time == tick)
        .ok_or_else(|| format!("No curve keyframe exists at frame {frame} for {selector}"))?;
    let dims = key.value.dims().max(1);
    let ease = Ease { speed, influence };
    if side != "outgoing" {
        key.in_interp = Interp::Bezier;
        key.in_ease = vec![ease; dims];
    }
    if side != "incoming" {
        key.out_interp = Interp::Bezier;
        key.out_ease = vec![ease; dims];
    }
    key.auto_bezier = false;
    key.continuous = false;
    project.to_file_json().map_err(|error| format!("Unable to serialize project: {error}"))
}

/// Re-times a roving run after an edit that changed key times or path
/// shape. A no-op when no key roves; upstream `retime_roving` clears
/// roving on the first/last keys and on tracks with fewer than three keys.
///
/// The bridge addresses keys by frame, so a retimed key must land on an
/// exact frame tick or later exact-tick lookups (move/remove/Bezier) miss
/// it — upstream times are sub-frame precise. Each moved key is therefore
/// snapped to its nearest frame tick, but only when that tick is still
/// free: a run squeezed inside one frame has no free frame tick, and
/// snapping there would collide with a neighbour or reorder the track,
/// so such a key keeps its upstream time.
fn retime_roving_run(property: &mut effectcraft_project::props::Property, frame_rate: effectcraft_time::FrameRate) {
    if !property.keys.iter().any(|key| key.roving) {
        return;
    }
    let before: Vec<Tick> = property.keys.iter().map(|key| key.time).collect();
    retime_roving(&mut property.keys, property.spatial);
    for index in 0..property.keys.len() {
        if property.keys[index].time == before[index] {
            continue;
        }
        let frame = (property.keys[index].time.seconds() * frame_rate.num as f64 / frame_rate.den as f64).round() as i64;
        if frame < 0 {
            continue;
        }
        let snapped = Tick::from_rational(frame, frame_rate.den, frame_rate.num);
        let prev_ok = index == 0 || snapped > property.keys[index - 1].time;
        let next_ok = index + 1 >= property.keys.len() || snapped < property.keys[index + 1].time;
        if prev_ok && next_ok {
            property.keys[index].time = snapped;
        }
    }
}

/// Resolves a curve property for a spatial edit, rejecting anything that
/// cannot carry a motion path (non-spatial properties and non-vector values).
fn resolve_spatial_property_mut<'a>(
    layer: &'a mut effectcraft_project::Layer,
    selector: &str,
) -> Result<&'a mut effectcraft_project::props::Property, String> {
    let property = resolve_curve_property_mut(layer, selector)?;
    if !property.spatial {
        return Err(format!("Curve property {selector} is not spatial, so it has no motion path"));
    }
    match &property.value {
        Value::Vec2(_) | Value::Vec3(_) => Ok(property),
        _ => Err(format!("Curve property {selector} is not spatial, so it has no motion path")),
    }
}

fn load_project_layer_mut<'a>(
    project: &'a mut Project,
    composition_id: u64,
    layer_id: u64,
) -> Result<&'a mut effectcraft_project::Layer, String> {
    let comp = project.comp_mut(ItemId(composition_id))
        .ok_or_else(|| format!("Composition {composition_id} was not found"))?;
    let layer = comp.layers.find_mut(|layer| layer.id == LayerId(layer_id))
        .ok_or_else(|| format!("Layer {layer_id} was not found in composition {composition_id}"))?;
    require_layer_unlocked(layer)?;
    Ok(layer)
}

/// Toggles roving ("Rove Across Time") on one key of a spatial property.
/// The run is re-timed by path length afterwards, so the toggle takes
/// effect immediately; upstream clears roving on first/last keys and on
/// tracks with fewer than three keys.
fn set_keyframe_roving(
    source: &str, composition_id: u64, layer_id: u64, selector: &str,
    frame: i64, roving: bool,
) -> Result<String, String> {
    if frame < 0 { return Err("Keyframe frame must be non-negative".into()); }
    let mut project: Project = serde_json::from_str(source)
        .map_err(|error| format!("Invalid EffectCraft project: {error}"))?;
    let frame_rate = project.comp(ItemId(composition_id))
        .ok_or_else(|| format!("Composition {composition_id} was not found"))?.frame_rate;
    let layer = load_project_layer_mut(&mut project, composition_id, layer_id)?;
    let property = resolve_spatial_property_mut(layer, selector)?;
    let tick = Tick::from_rational(frame, frame_rate.den, frame_rate.num);
    let key = property.keys.iter_mut().find(|key| key.time == tick)
        .ok_or_else(|| format!("No curve keyframe exists at frame {frame} for {selector}"))?;
    key.roving = roving;
    retime_roving_run(property, frame_rate);
    project.to_file_json().map_err(|error| format!("Unable to serialize project: {error}"))
}

/// Sets the spatial (motion-path) tangents of one key of a spatial
/// property, relative to the key value. `spatial_auto` selects the upstream
/// Catmull-Rom auto-Bezier tangents instead of the stored ones; passing
/// zero tangents with `spatial_auto` false is the straight (linear-path)
/// preset. Tangents change path length, so the roving run is re-timed.
fn set_keyframe_spatial_tangents(
    source: &str, composition_id: u64, layer_id: u64, selector: &str,
    frame: i64, spatial_in: [f64; 3], spatial_out: [f64; 3], spatial_auto: bool,
) -> Result<String, String> {
    if frame < 0 { return Err("Keyframe frame must be non-negative".into()); }
    if !spatial_in.iter().chain(spatial_out.iter()).all(|component| component.is_finite()) {
        return Err("Spatial tangents must be finite".into());
    }
    if spatial_in.iter().chain(spatial_out.iter()).any(|component| component.abs() > 1_000_000.0) {
        return Err("Spatial tangent exceeds its property limit".into());
    }
    let mut project: Project = serde_json::from_str(source)
        .map_err(|error| format!("Invalid EffectCraft project: {error}"))?;
    let frame_rate = project.comp(ItemId(composition_id))
        .ok_or_else(|| format!("Composition {composition_id} was not found"))?.frame_rate;
    let layer = load_project_layer_mut(&mut project, composition_id, layer_id)?;
    let property = resolve_spatial_property_mut(layer, selector)?;
    let tick = Tick::from_rational(frame, frame_rate.den, frame_rate.num);
    let key = property.keys.iter_mut().find(|key| key.time == tick)
        .ok_or_else(|| format!("No curve keyframe exists at frame {frame} for {selector}"))?;
    key.spatial_in = spatial_in;
    key.spatial_out = spatial_out;
    key.spatial_auto = spatial_auto;
    retime_roving_run(property, frame_rate);
    project.to_file_json().map_err(|error| format!("Unable to serialize project: {error}"))
}

/// Re-times the roving run on one curve property without changing any key,
/// for callers that edited the track in bulk (for example after a drag that
/// bypassed the single-key entry points). Returns the project unchanged
/// when nothing roves.
fn retime_roving_keys(
    source: &str, composition_id: u64, layer_id: u64, selector: &str,
) -> Result<String, String> {
    let mut project: Project = serde_json::from_str(source)
        .map_err(|error| format!("Invalid EffectCraft project: {error}"))?;
    let frame_rate = project.comp(ItemId(composition_id))
        .ok_or_else(|| format!("Composition {composition_id} was not found"))?.frame_rate;
    let layer = load_project_layer_mut(&mut project, composition_id, layer_id)?;
    let property = resolve_curve_property_mut(layer, selector)?;
    retime_roving_run(property, frame_rate);
    project.to_file_json().map_err(|error| format!("Unable to serialize project: {error}"))
}

/// Drags a curve key from one frame to another, keeping its value and both
/// Bezier sides intact. Dragging past a neighbour reorders the track;
/// dragging exactly onto a neighbour replaces that neighbour, matching
/// same-frame upsert semantics. The moved key is inserted directly rather
/// than through the upstream set-key helper, which would overwrite the
/// moved key's interpolation with the replaced neighbour's.
fn move_keyframe(
    source: &str, composition_id: u64, layer_id: u64, selector: &str,
    from_frame: i64, to_frame: i64,
) -> Result<String, String> {
    if from_frame < 0 || to_frame < 0 {
        return Err("Keyframe frames must be non-negative".into());
    }
    let mut project: Project = serde_json::from_str(source)
        .map_err(|error| format!("Invalid EffectCraft project: {error}"))?;
    let comp = project.comp_mut(ItemId(composition_id))
        .ok_or_else(|| format!("Composition {composition_id} was not found"))?;
    let frame_rate = comp.frame_rate;
    let layer = comp.layers.find_mut(|layer| layer.id == LayerId(layer_id))
        .ok_or_else(|| format!("Layer {layer_id} was not found in composition {composition_id}"))?;
    require_layer_unlocked(layer)?;
    let property = resolve_curve_property_mut(layer, selector)?;
    let from_tick = Tick::from_rational(from_frame, frame_rate.den, frame_rate.num);
    let to_tick = Tick::from_rational(to_frame, frame_rate.den, frame_rate.num);
    let index = property.keys.iter().position(|key| key.time == from_tick)
        .ok_or_else(|| format!("No curve keyframe exists at frame {from_frame} for {selector}"))?;
    if from_frame == to_frame {
        return project.to_file_json().map_err(|error| format!("Unable to serialize project: {error}"));
    }
    let mut moved = property.keys.remove(index);
    moved.time = to_tick;
    property.keys.retain(|key| key.time != to_tick);
    property.keys.push(moved);
    property.keys.sort_by_key(|key| key.time);
    // The moved key keeps its roving flag and spatial tangents (the whole
    // struct moves), and a roving run re-spaces by path length afterwards.
    retime_roving_run(property, frame_rate);
    project.to_file_json().map_err(|error| format!("Unable to serialize project: {error}"))
}

/// Removes a key from any editable canonical curve selector (Transform or param UID).
/// If the last key is removed, the property's static value is restored at that tick.
fn remove_curve_keyframe(
    source: &str, composition_id: u64, layer_id: u64, selector: &str, frame: i64,
) -> Result<(String, bool), String> {
    if frame < 0 { return Err("Keyframe frame must be non-negative".into()); }
    let mut project: Project = serde_json::from_str(source)
        .map_err(|error| format!("Invalid EffectCraft project: {error}"))?;
    let comp = project.comp_mut(ItemId(composition_id))
        .ok_or_else(|| format!("Composition {composition_id} was not found"))?;
    let frame_rate = comp.frame_rate;
    let layer = comp.layers.find_mut(|layer| layer.id == LayerId(layer_id))
        .ok_or_else(|| format!("Layer {layer_id} was not found in composition {composition_id}"))?;
    require_layer_unlocked(layer)?;
    let property = resolve_curve_property_mut(layer, selector)?;
    if property.static_only {
        return Err(format!("Curve property {selector} cannot be keyframed"));
    }
    if !matches!(&property.value, Value::Scalar(_) | Value::Vec2(_) | Value::Vec3(_) | Value::Color(_) | Value::Bool(_) | Value::Enum(_) | Value::Text(_) | Value::Layer(_)) {
        return Err(format!("Curve property {selector} is not animatable"));
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
    retime_roving_run(property, frame_rate);
    let json = project.to_file_json().map_err(|error| format!("Unable to serialize project: {error}"))?;
    Ok((json, removed))
}

/// Authors one shape-item or effect scalar/vector parameter keyframe at
/// `frame`, addressed by its native uid. The first key seeds frame 0 with
/// the parameter's current value, so a key authored mid-timeline never
/// rewrites earlier frames, matching the Transform keyframe setters.
fn set_layer_param_keyframe(
    source: &str, composition_id: u64, layer_id: u64, uid: u64,
    frame: i64, x: f64, y: f64, z: f64,
) -> Result<String, String> {
    if frame < 0 { return Err("Keyframe frame must be non-negative".into()); }
    if !x.is_finite() || !y.is_finite() || !z.is_finite() {
        return Err("Parameter keyframe values must be finite".into());
    }
    let mut project: Project = serde_json::from_str(source)
        .map_err(|error| format!("Invalid EffectCraft project: {error}"))?;
    let comp = project.comp_mut(ItemId(composition_id))
        .ok_or_else(|| format!("Composition {composition_id} was not found"))?;
    let frame_rate = comp.frame_rate;
    let layer = comp.layers.find_mut(|layer| layer.id == LayerId(layer_id))
        .ok_or_else(|| format!("Layer {layer_id} was not found in composition {composition_id}"))?;
    require_layer_unlocked(layer)?;
    let property = layer.props.prop_mut(&format!("@{uid}"))
        .ok_or_else(|| format!("Layer {layer_id} has no parameter with uid {uid}"))?;
    if property.static_only {
        return Err(format!("Parameter {uid} cannot be keyframed"));
    }
    let value = match &property.value {
        Value::Scalar(_) => Value::Scalar(x),
        Value::Vec2(_) => Value::Vec2([x, y]),
        Value::Vec3(_) => Value::Vec3([x, y, z]),
        _ => return Err(format!("Parameter {uid} is not animatable")),
    };
    if property.keys.is_empty() {
        property.set_animated(true, Tick::ZERO);
    }
    property.set_value_at(Tick::from_rational(frame, frame_rate.den, frame_rate.num), value);
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
pub extern "system" fn Java_com_codeair_effectcraft_engine_NativeProjectBridge_setLayerVisibilityEcprojNative(
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
pub extern "system" fn Java_com_codeair_effectcraft_engine_NativeProjectBridge_moveLayerEcprojNative(
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
pub extern "system" fn Java_com_codeair_effectcraft_engine_NativeProjectBridge_setLayerOpacityEcprojNative(
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
pub extern "system" fn Java_com_codeair_effectcraft_engine_NativeProjectBridge_setLayerOpacityKeyframeEcprojNative(
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
pub extern "system" fn Java_com_codeair_effectcraft_engine_NativeProjectBridge_setLayerTransformKeyframeEcprojNative(
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
pub extern "system" fn Java_com_codeair_effectcraft_engine_NativeProjectBridge_setLayerTransformKeyframeInterpolationEcprojNative(
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
pub extern "system" fn Java_com_codeair_effectcraft_engine_NativeProjectBridge_removeLayerTransformKeyframeEcprojNative(
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
pub extern "system" fn Java_com_codeair_effectcraft_engine_NativeProjectBridge_setLayerKeyframeBezierEcprojNative(
    mut env: JNIEnv, _class: JClass, source: JString, composition_id: jlong, layer_id: jlong,
    selector: JString, frame: jlong, side: JString, speed: jni::sys::jdouble, influence: jni::sys::jdouble,
) -> jstring {
    let source: String = match env.get_string(&source) {
        Ok(value) => value.into(),
        Err(error) => return env.new_string(json!({"valid": false, "error": error.to_string()}).to_string()).map_or(std::ptr::null_mut(), |v| v.into_raw()),
    };
    let selector: String = match env.get_string(&selector) {
        Ok(value) => value.into(),
        Err(error) => return env.new_string(json!({"valid": false, "error": error.to_string()}).to_string()).map_or(std::ptr::null_mut(), |v| v.into_raw()),
    };
    let side: String = match env.get_string(&side) {
        Ok(value) => value.into(),
        Err(error) => return env.new_string(json!({"valid": false, "error": error.to_string()}).to_string()).map_or(std::ptr::null_mut(), |v| v.into_raw()),
    };
    let result = if composition_id < 0 || layer_id < 0 || frame < 0 {
        Err("Composition, layer and frame must be non-negative".to_owned())
    } else {
        set_keyframe_bezier(&source, composition_id as u64, layer_id as u64, &selector, frame, &side, speed as f64, influence as f64)
    };
    let output = match result { Ok(project_json) => json!({"valid": true, "project": project_json}).to_string(), Err(error) => json!({"valid": false, "error": error}).to_string() };
    env.new_string(output).map_or(std::ptr::null_mut(), |value| value.into_raw())
}

#[no_mangle]
pub extern "system" fn Java_com_codeair_effectcraft_engine_NativeProjectBridge_moveLayerKeyframeEcprojNative(
    mut env: JNIEnv, _class: JClass, source: JString, composition_id: jlong, layer_id: jlong,
    selector: JString, from_frame: jlong, to_frame: jlong,
) -> jstring {
    let source: String = match env.get_string(&source) {
        Ok(value) => value.into(),
        Err(error) => return env.new_string(json!({"valid": false, "error": error.to_string()}).to_string()).map_or(std::ptr::null_mut(), |v| v.into_raw()),
    };
    let selector: String = match env.get_string(&selector) {
        Ok(value) => value.into(),
        Err(error) => return env.new_string(json!({"valid": false, "error": error.to_string()}).to_string()).map_or(std::ptr::null_mut(), |v| v.into_raw()),
    };
    let result = if composition_id < 0 || layer_id < 0 || from_frame < 0 || to_frame < 0 {
        Err("Composition, layer and frames must be non-negative".to_owned())
    } else {
        move_keyframe(&source, composition_id as u64, layer_id as u64, &selector, from_frame, to_frame)
    };
    let output = match result { Ok(project_json) => json!({"valid": true, "project": project_json}).to_string(), Err(error) => json!({"valid": false, "error": error}).to_string() };
    env.new_string(output).map_or(std::ptr::null_mut(), |value| value.into_raw())
}

#[no_mangle]
pub extern "system" fn Java_com_codeair_effectcraft_engine_NativeProjectBridge_setLayerKeyframeRovingEcprojNative(
    mut env: JNIEnv, _class: JClass, source: JString, composition_id: jlong, layer_id: jlong,
    selector: JString, frame: jlong, roving: jboolean,
) -> jstring {
    let source: String = match env.get_string(&source) {
        Ok(value) => value.into(),
        Err(error) => return env.new_string(json!({"valid": false, "error": error.to_string()}).to_string()).map_or(std::ptr::null_mut(), |v| v.into_raw()),
    };
    let selector: String = match env.get_string(&selector) {
        Ok(value) => value.into(),
        Err(error) => return env.new_string(json!({"valid": false, "error": error.to_string()}).to_string()).map_or(std::ptr::null_mut(), |v| v.into_raw()),
    };
    let result = if composition_id < 0 || layer_id < 0 || frame < 0 {
        Err("Composition, layer and frame must be non-negative".to_owned())
    } else {
        set_keyframe_roving(&source, composition_id as u64, layer_id as u64, &selector, frame, roving != 0)
    };
    let output = match result { Ok(project_json) => json!({"valid": true, "project": project_json}).to_string(), Err(error) => json!({"valid": false, "error": error}).to_string() };
    env.new_string(output).map_or(std::ptr::null_mut(), |value| value.into_raw())
}

#[no_mangle]
pub extern "system" fn Java_com_codeair_effectcraft_engine_NativeProjectBridge_setLayerKeyframeSpatialTangentsEcprojNative(
    mut env: JNIEnv, _class: JClass, source: JString, composition_id: jlong, layer_id: jlong,
    selector: JString, frame: jlong,
    in_x: jni::sys::jdouble, in_y: jni::sys::jdouble, in_z: jni::sys::jdouble,
    out_x: jni::sys::jdouble, out_y: jni::sys::jdouble, out_z: jni::sys::jdouble,
    spatial_auto: jboolean,
) -> jstring {
    let source: String = match env.get_string(&source) {
        Ok(value) => value.into(),
        Err(error) => return env.new_string(json!({"valid": false, "error": error.to_string()}).to_string()).map_or(std::ptr::null_mut(), |v| v.into_raw()),
    };
    let selector: String = match env.get_string(&selector) {
        Ok(value) => value.into(),
        Err(error) => return env.new_string(json!({"valid": false, "error": error.to_string()}).to_string()).map_or(std::ptr::null_mut(), |v| v.into_raw()),
    };
    let result = if composition_id < 0 || layer_id < 0 || frame < 0 {
        Err("Composition, layer and frame must be non-negative".to_owned())
    } else {
        set_keyframe_spatial_tangents(&source, composition_id as u64, layer_id as u64, &selector, frame,
            [in_x as f64, in_y as f64, in_z as f64], [out_x as f64, out_y as f64, out_z as f64], spatial_auto != 0)
    };
    let output = match result { Ok(project_json) => json!({"valid": true, "project": project_json}).to_string(), Err(error) => json!({"valid": false, "error": error}).to_string() };
    env.new_string(output).map_or(std::ptr::null_mut(), |value| value.into_raw())
}

#[no_mangle]
pub extern "system" fn Java_com_codeair_effectcraft_engine_NativeProjectBridge_retimeLayerRovingKeysEcprojNative(
    mut env: JNIEnv, _class: JClass, source: JString, composition_id: jlong, layer_id: jlong,
    selector: JString,
) -> jstring {
    let source: String = match env.get_string(&source) {
        Ok(value) => value.into(),
        Err(error) => return env.new_string(json!({"valid": false, "error": error.to_string()}).to_string()).map_or(std::ptr::null_mut(), |v| v.into_raw()),
    };
    let selector: String = match env.get_string(&selector) {
        Ok(value) => value.into(),
        Err(error) => return env.new_string(json!({"valid": false, "error": error.to_string()}).to_string()).map_or(std::ptr::null_mut(), |v| v.into_raw()),
    };
    let result = if composition_id < 0 || layer_id < 0 {
        Err("Composition and layer must be non-negative".to_owned())
    } else {
        retime_roving_keys(&source, composition_id as u64, layer_id as u64, &selector)
    };
    let output = match result { Ok(project_json) => json!({"valid": true, "project": project_json}).to_string(), Err(error) => json!({"valid": false, "error": error}).to_string() };
    env.new_string(output).map_or(std::ptr::null_mut(), |value| value.into_raw())
}

#[no_mangle]
pub extern "system" fn Java_com_codeair_effectcraft_engine_NativeProjectBridge_setLayerParamKeyframeEcprojNative(
    mut env: JNIEnv, _class: JClass, source: JString, composition_id: jlong, layer_id: jlong,
    uid: jlong, frame: jlong, x: jni::sys::jdouble, y: jni::sys::jdouble, z: jni::sys::jdouble,
) -> jstring {
    let source: String = match env.get_string(&source) {
        Ok(value) => value.into(),
        Err(error) => return env.new_string(json!({"valid": false, "error": error.to_string()}).to_string()).map_or(std::ptr::null_mut(), |v| v.into_raw()),
    };
    let result = if composition_id < 0 || layer_id < 0 || uid < 0 || frame < 0 {
        Err("Composition, layer, parameter and frame must be non-negative".to_owned())
    } else {
        set_layer_param_keyframe(&source, composition_id as u64, layer_id as u64, uid as u64, frame, x as f64, y as f64, z as f64)
    };
    let output = match result { Ok(project_json) => json!({"valid": true, "project": project_json}).to_string(), Err(error) => json!({"valid": false, "error": error}).to_string() };
    env.new_string(output).map_or(std::ptr::null_mut(), |value| value.into_raw())
}

#[no_mangle]
pub extern "system" fn Java_com_codeair_effectcraft_engine_NativeProjectBridge_removeLayerCurveKeyframeEcprojNative(
    mut env: JNIEnv, _class: JClass, source: JString, composition_id: jlong, layer_id: jlong,
    selector: JString, frame: jlong,
) -> jstring {
    let source: String = match env.get_string(&source) {
        Ok(value) => value.into(),
        Err(error) => return env.new_string(json!({"valid": false, "error": error.to_string()}).to_string()).map_or(std::ptr::null_mut(), |v| v.into_raw()),
    };
    let selector: String = match env.get_string(&selector) {
        Ok(value) => value.into(),
        Err(error) => return env.new_string(json!({"valid": false, "error": error.to_string()}).to_string()).map_or(std::ptr::null_mut(), |v| v.into_raw()),
    };
    let result = if composition_id < 0 || layer_id < 0 || frame < 0 {
        Err("Composition, layer and frame must be non-negative".to_owned())
    } else {
        remove_curve_keyframe(&source, composition_id as u64, layer_id as u64, &selector, frame)
    };
    let output = match result { Ok((project_json, removed)) => json!({"valid": true, "project": project_json, "removed": removed}).to_string(), Err(error) => json!({"valid": false, "error": error}).to_string() };
    env.new_string(output).map_or(std::ptr::null_mut(), |value| value.into_raw())
}

#[no_mangle]
pub extern "system" fn Java_com_codeair_effectcraft_engine_NativeProjectBridge_removeLayerOpacityKeyframeEcprojNative(
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
pub extern "system" fn Java_com_codeair_effectcraft_engine_NativeProjectBridge_relinkFootageEcprojNative(
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
pub extern "system" fn Java_com_codeair_effectcraft_engine_NativeProjectBridge_deleteLayerEcprojNative(
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
        let cpu = render_parsed_frame(&project, &footage, comp_id, 0, 160, None, None).unwrap();

        let live = CountingAccel {
            asked: std::sync::atomic::AtomicUsize::new(0),
            available: true,
        };
        let via_live =
            render_parsed_frame(&project, &footage, comp_id, 0, 160, Some(&live), None).unwrap();
        assert_eq!(live.asked.load(std::sync::atomic::Ordering::SeqCst), 1);
        assert_eq!(via_live, cpu);

        let retired = CountingAccel {
            asked: std::sync::atomic::AtomicUsize::new(0),
            available: false,
        };
        let via_retired =
            render_parsed_frame(&project, &footage, comp_id, 0, 160, Some(&retired), None).unwrap();
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
    fn canonical_text_font_edit_preserves_source_and_validates_input() {
        use effectcraft_project::Value;

        let (source, comp_id, layer_id) = text_layer_project();
        let texted = set_text_layer_text(&source, comp_id, layer_id, "Brand title").unwrap();
        let fonted = set_text_layer_font(&texted, comp_id, layer_id, "  Inter Display  ").unwrap();
        let project: Project = serde_json::from_str(&fonted).unwrap();
        let doc = match &project.comp(ItemId(comp_id)).unwrap().layers[0].props.sub("text").unwrap().prop("sourceText").unwrap().value {
            Value::Text(doc) => doc,
            other => panic!("sourceText must be Text, got {other:?}"),
        };
        assert_eq!(doc.text, "Brand title");
        assert_eq!(doc.font, "Inter Display");
        let metadata: serde_json::Value = serde_json::from_str(&inspect_project(&fonted).unwrap()).unwrap();
        assert_eq!(metadata["compositions"][0]["layers"][0]["text"]["font"], "Inter Display");
        assert!(set_text_layer_font(&source, comp_id, layer_id, " ").is_err());
        assert!(set_text_layer_font(&source, comp_id, layer_id, &"F".repeat(129)).is_err());
        assert!(set_text_layer_font(&source, comp_id, layer_id, "Bad\nFont").is_err());
        assert!(set_text_layer_font(&source, comp_id, 999, "Inter").is_err());
    }

    #[test]
    fn canonical_source_text_font_keyframe_preserves_text_and_serializes_font() {
        use effectcraft_project::Value;
        let (source, comp_id, layer_id) = text_layer_project();
        let keyed_text = set_text_layer_text_keyframe(&source, comp_id, layer_id, 15, "Frame fifteen").unwrap();
        let keyed_font = set_text_layer_font_keyframe(&keyed_text, comp_id, layer_id, 30, "  Noto Sans  ").unwrap();
        let project: Project = serde_json::from_str(&keyed_font).unwrap();
        let property = project.comp(ItemId(comp_id)).unwrap().layers[0].props.sub("text").unwrap().prop("sourceText").unwrap();
        let font_key = property.keys.iter().find(|key| key.time.to_rational_floor(1, 30) == 30).unwrap();
        match &font_key.value {
            Value::Text(doc) => { assert_eq!(doc.font, "Noto Sans"); assert_eq!(doc.text, "Frame fifteen"); }
            other => panic!("font key must preserve TextDoc, got {other:?}"),
        }
        let metadata: serde_json::Value = serde_json::from_str(&inspect_project(&keyed_font).unwrap()).unwrap();
        let keys = metadata["compositions"][0]["layers"][0]["text"]["keys"].as_array().unwrap();
        assert!(keys.iter().any(|key| key["frame"] == 30 && key["font"] == "Noto Sans"));
        assert!(set_text_layer_font_keyframe(&source, comp_id, layer_id, -1, "Inter").is_err());
        assert!(set_text_layer_font_keyframe(&source, comp_id, layer_id, 15, " ").is_err());
        assert!(set_text_layer_font_keyframe(&source, comp_id, 999, 15, "Inter").is_err());
    }

    #[test]
    fn canonical_source_text_tracking_keyframe_preserves_document_and_serializes_value() {
        use effectcraft_project::Value;
        let (source, comp_id, layer_id) = text_layer_project();
        let tracked = set_text_layer_tracking(&source, comp_id, layer_id, 12.5).unwrap();
        let keyed_text = set_text_layer_text_keyframe(&tracked, comp_id, layer_id, 10, "Frame ten").unwrap();
        let keyed_tracking = set_text_layer_tracking_keyframe(&keyed_text, comp_id, layer_id, 15, 42.5).unwrap();
        let project: Project = serde_json::from_str(&keyed_tracking).unwrap();
        let property = project.comp(ItemId(comp_id)).unwrap().layers[0].props.sub("text").unwrap().prop("sourceText").unwrap();
        let tracking_key = property.keys.iter().find(|key| key.time.to_rational_floor(1, 30) == 15).unwrap();
        match &tracking_key.value {
            Value::Text(doc) => { assert_eq!(doc.text, "Frame ten"); assert_eq!(doc.tracking, 42.5); assert_eq!(doc.font, "Inter"); }
            other => panic!("tracking key must preserve TextDoc, got {other:?}"),
        }
        let metadata: serde_json::Value = serde_json::from_str(&inspect_project(&keyed_tracking).unwrap()).unwrap();
        let keys = metadata["compositions"][0]["layers"][0]["text"]["keys"].as_array().unwrap();
        assert!(keys.iter().any(|key| key["frame"] == 15 && key["tracking"] == 42.5 && key["text"] == "Frame ten"));
        assert!(keys.iter().any(|key| key["frame"] == 10 && key["tracking"] == 12.5));
        assert!(set_text_layer_tracking_keyframe(&source, comp_id, layer_id, -1, 2.0).is_err());
        assert!(set_text_layer_tracking_keyframe(&source, comp_id, layer_id, 15, f64::NAN).is_err());
        assert!(set_text_layer_tracking_keyframe(&source, 999, layer_id, 15, 2.0).is_err());
    }

    #[test]
    fn canonical_source_text_hold_keys_seed_metadata_and_remove() {
        use effectcraft_project::Value;

        let (source, comp_id, layer_id) = text_layer_project();
        let keyed = set_text_layer_text_keyframe(&source, comp_id, layer_id, 15, "Frame fifteen").unwrap();
        let project: Project = serde_json::from_str(&keyed).unwrap();
        let comp = project.comp(ItemId(comp_id)).unwrap();
        let property = comp.layers[0].props.sub("text").unwrap().prop("sourceText").unwrap();
        assert_eq!(property.keys.len(), 2, "frame 0 seed + frame 15 hold key");
        assert_eq!(property.keys[0].time, Tick::ZERO);
        assert_eq!(property.keys[1].time, Tick::from_rational(15, comp.frame_rate.den, comp.frame_rate.num));
        assert!(matches!(&property.keys[1].value, Value::Text(doc) if doc.text == "Frame fifteen"));
        let metadata: serde_json::Value = serde_json::from_str(&inspect_project(&keyed).unwrap()).unwrap();
        assert_eq!(metadata["compositions"][0]["layers"][0]["text"]["keys"][1]["frame"], 15);
        assert_eq!(metadata["compositions"][0]["layers"][0]["text"]["keys"][1]["text"], "Frame fifteen");
        let seed_text = match &property.keys[0].value {
            Value::Text(doc) => doc.text.clone(),
            other => panic!("frame-0 seed must be a Text value, got {other:?}"),
        };
        let selector = format!("param:{}", property.uid);
        let (removed_json, removed) = remove_curve_keyframe(&keyed, comp_id, layer_id, &selector, 15).unwrap();
        assert!(removed);
        let removed_project: Project = serde_json::from_str(&removed_json).unwrap();
        let removed_property = removed_project.comp(ItemId(comp_id)).unwrap().layers[0].props
            .sub("text").unwrap().prop("sourceText").unwrap();
        assert_eq!(removed_property.keys.len(), 1, "removing the frame-15 key leaves the frame-0 seed");
        assert_eq!(removed_property.keys[0].time, Tick::ZERO);
        // Removing the last (seed) key collapses back to the static value.
        let (static_json, removed_zero) = remove_curve_keyframe(&removed_json, comp_id, layer_id, &selector, 0).unwrap();
        assert!(removed_zero);
        let static_project: Project = serde_json::from_str(&static_json).unwrap();
        let static_property = static_project.comp(ItemId(comp_id)).unwrap().layers[0].props
            .sub("text").unwrap().prop("sourceText").unwrap();
        assert!(static_property.keys.is_empty(), "last text key removal restores static Source Text");
        assert!(matches!(&static_property.value, Value::Text(doc) if doc.text == seed_text));
        // The whole-layer edit (keyframes OFF) drops every key and sets the
        // static text, so the new text holds at every frame.
        let whole = set_text_layer_text_whole_layer(&keyed, comp_id, layer_id, "Everywhere").unwrap();
        let whole_project: Project = serde_json::from_str(&whole).unwrap();
        let whole_property = whole_project.comp(ItemId(comp_id)).unwrap().layers[0].props
            .sub("text").unwrap().prop("sourceText").unwrap();
        assert!(whole_property.keys.is_empty(), "whole-layer edit must drop all Source Text keys");
        assert!(matches!(&whole_property.value, Value::Text(doc) if doc.text == "Everywhere"));
        let whole_meta: serde_json::Value = serde_json::from_str(&inspect_project(&whole).unwrap()).unwrap();
        assert_eq!(whole_meta["compositions"][0]["layers"][0]["text"]["keys"].as_array().map(|a| a.len()), Some(0));
        assert_eq!(whole_meta["compositions"][0]["layers"][0]["text"]["text"], "Everywhere");
        assert!(set_text_layer_text_whole_layer(&source, comp_id, layer_id, &"x".repeat(20_001)).is_err());
        assert!(set_text_layer_text_keyframe(&source, comp_id, layer_id, -1, "bad").is_err());
        assert!(set_text_layer_text_keyframe(&source, comp_id, layer_id, 0, &"x".repeat(20_001)).is_err());
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
        let mut color_effect = ids.group("colorTint", "Color Tint");
        color_effect = color_effect.with(ids.prop("tint", "Tint", Value::Color([0.1, 0.2, 0.3, 1.0])));
        null_layer.props.sub_mut("effects").unwrap().children.push(color_effect.into());
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
        let tint_item = effect_meta["effects"].as_array().unwrap()
            .iter().find(|item| item["name"] == "Color Tint").expect("color effect group must be exposed");
        let tint = tint_item["colors"].as_array().unwrap()
            .iter().find(|param| param["name"] == "Tint").expect("color parameter must be exposed");
        let tint_uid = tint["uid"].as_u64().unwrap();
        assert_eq!(tint["components"], json!([0.1, 0.2, 0.3, 1.0]));
        let blurred = set_layer_scalar_property(&rounded, comp_id.0, null_id, blur_uid, 15.0).unwrap();
        let blurred_meta: serde_json::Value = serde_json::from_str(&inspect_project(&blurred).unwrap()).unwrap();
        let blurred_item = blurred_meta["compositions"][0]["layers"][1]["effects"].as_array().unwrap().iter()
            .find(|item| item["name"] == "Gaussian Blur").unwrap();
        assert_eq!(blurred_item["scalars"].as_array().unwrap().iter().find(|p| p["name"] == "Blurriness").unwrap()["value"], 15.0);
        let tinted = set_layer_color_property(&blurred, comp_id.0, null_id, tint_uid, 0.4, 0.5, 0.6, 0.7).unwrap();
        let tinted_meta: serde_json::Value = serde_json::from_str(&inspect_project(&tinted).unwrap()).unwrap();
        let edited_tint = tinted_meta["compositions"][0]["layers"][1]["effects"].as_array().unwrap()
            .iter().find(|item| item["name"] == "Color Tint").unwrap()["colors"].as_array().unwrap()
            .iter().find(|param| param["name"] == "Tint").unwrap();
        assert_eq!(edited_tint["components"], json!([0.4, 0.5, 0.6, 0.7]));
        let keyed_tint = set_layer_color_keyframe(&tinted, comp_id.0, null_id, tint_uid, 15, 0.8, 0.25, 0.5, 1.0).unwrap();
        let keyed_meta: serde_json::Value = serde_json::from_str(&inspect_project(&keyed_tint).unwrap()).unwrap();
        let keyed_color = keyed_meta["compositions"][0]["layers"][1]["effects"].as_array().unwrap()
            .iter().find(|item| item["name"] == "Color Tint").unwrap()["colors"].as_array().unwrap()
            .iter().find(|param| param["name"] == "Tint").unwrap();
        assert_eq!(keyed_color["keyframes"], json!([0, 15]));
        // The curve keys carry each color key's own RGBA so the timeline
        // color lane can draw a swatch per keyframe.
        let key_values: Vec<serde_json::Value> = keyed_color["keys"].as_array().unwrap().iter()
            .map(|key| key["value"].clone()).collect();
        assert_eq!(key_values, vec![json!([0.4, 0.5, 0.6, 0.7]), json!([0.8, 0.25, 0.5, 1.0])]);
        let key_frames: Vec<i64> = keyed_color["keys"].as_array().unwrap().iter()
            .map(|key| key["frame"].as_i64().unwrap()).collect();
        assert_eq!(key_frames, vec![0, 15]);
        let mut keyed_project: Project = serde_json::from_str(&keyed_tint).unwrap();
        let keyed_property = keyed_project.comp_mut(ItemId(comp_id.0)).unwrap()
            .layers.find_mut(|layer| layer.id == LayerId(null_id)).unwrap()
            .props.prop_mut(&format!("@{tint_uid}")).unwrap();
        assert_eq!(keyed_property.keys.len(), 2);
        assert_eq!(keyed_property.keys[1].value, Value::Color([0.8, 0.25, 0.5, 1.0]));
        assert!(set_layer_color_keyframe(&source, comp_id.0, null_id, tint_uid, -1, 0.0, 0.0, 0.0, 1.0).is_err());
        assert!(set_layer_color_property(&source, comp_id.0, null_id, tint_uid, 1.1, 0.0, 0.0, 1.0).is_err());
        assert!(set_layer_color_property(&source, comp_id.0, null_id, blur_uid, 0.0, 0.0, 0.0, 1.0).is_err());
        assert!(set_layer_scalar_property(&source, comp_id.0, shape_id, size_uid, 10.0).is_err());
        assert!(set_layer_vec_property(&source, comp_id.0, shape_id, roundness_uid, 1.0, 2.0, 0.0).is_err());
        assert!(set_layer_scalar_property(&source, comp_id.0, shape_id, 999_999, 10.0).is_err());
    }

    #[test]
    fn canonical_gradient_params_exposed_and_updated_by_property_uid() {
        use effectcraft_project::{build, Comp, ItemKind, LayerSource};
        use effectcraft_project::keyframe::Gradient;
        use effectcraft_color::Label;
        use effectcraft_time::{FrameRate, Tick};

        let mut project = Project::default();
        let comp = Comp::new(640, 360, FrameRate::FPS_30, Tick::from_seconds_f64(3.0));
        let comp_id = project.add_item("Main", Label::Sandstone, None, ItemKind::Comp(comp.clone().into()));
        let mut shape_layer = build::layer(&mut project, &comp, "Shape", LayerSource::Shape, (640, 360), None);
        let shape_id = shape_layer.id.0;
        let mut next_id = 10_000u64;
        let mut ids = build::Ids(&mut next_id);
        let gradient = Gradient {
            colors: vec![(0.0, [1.0, 0.0, 0.0, 1.0]), (1.0, [0.0, 0.0, 1.0, 1.0])],
            opacities: vec![(0.0, 1.0), (1.0, 0.5)],
        };
        let gfill = build::shape_gradient_fill(&mut ids, false, [0.0, 0.0], [200.0, 0.0], gradient);
        shape_layer.props.sub_mut("contents").unwrap().children.push(gfill.into());
        project.comp_mut(comp_id).unwrap().layers.push(shape_layer);
        let source = project.to_file_json().unwrap();

        let metadata: serde_json::Value = serde_json::from_str(&inspect_project(&source).unwrap()).unwrap();
        let shape_meta = &metadata["compositions"][0]["layers"][0];
        let gfill_item = shape_meta["shapeItems"].as_array().unwrap().iter()
            .find(|item| item["kind"] == "gfill").expect("gradient fill shape item must be exposed");
        let colors_param = gfill_item["gradients"].as_array().unwrap().iter()
            .find(|param| param["name"] == "Colors").expect("gradient Colors param must be exposed");
        let colors_uid = colors_param["uid"].as_u64().unwrap();
        assert_eq!(colors_param["colors"], json!([[0.0, [1.0, 0.0, 0.0, 1.0]], [1.0, [0.0, 0.0, 1.0, 1.0]]]));
        assert_eq!(colors_param["opacities"], json!([[0.0, 1.0], [1.0, 0.5]]));

        let updated = set_layer_gradient_property(&source, comp_id.0, shape_id, colors_uid,
            r#"{"colors": [[0.0, [0.0, 1.0, 0.0, 1.0]], [0.5, [1.0, 1.0, 0.0, 1.0]], [1.0, [0.0, 0.0, 0.0, 1.0]]], "opacities": [[0.0, 1.0], [1.0, 1.0]]}"#).unwrap();
        let updated_meta: serde_json::Value = serde_json::from_str(&inspect_project(&updated).unwrap()).unwrap();
        let updated_param = updated_meta["compositions"][0]["layers"][0]["shapeItems"].as_array().unwrap().iter()
            .find(|item| item["kind"] == "gfill").unwrap()["gradients"].as_array().unwrap().iter()
            .find(|param| param["name"] == "Colors").unwrap();
        assert_eq!(updated_param["colors"], json!([[0.0, [0.0, 1.0, 0.0, 1.0]], [0.5, [1.0, 1.0, 0.0, 1.0]], [1.0, [0.0, 0.0, 0.0, 1.0]]]));
        assert_eq!(updated_param["opacities"], json!([[0.0, 1.0], [1.0, 1.0]]));

        assert!(set_layer_gradient_property(&source, comp_id.0, shape_id, colors_uid, "not json").is_err());
        assert!(set_layer_gradient_property(&source, comp_id.0, shape_id, colors_uid, r#"{"colors": [], "opacities": []}"#).is_err());
        assert!(set_layer_gradient_property(&source, comp_id.0, shape_id, colors_uid, r#"{"colors": [[0.0, [1.5, 0.0, 0.0, 1.0]]], "opacities": [[0.0, 1.0]]}"#).is_err());
        assert!(set_layer_gradient_property(&source, comp_id.0, shape_id, colors_uid, r#"{"colors": [[2.0, [0.0, 0.0, 0.0, 1.0]]], "opacities": [[0.0, 1.0]]}"#).is_err());
        assert!(set_layer_gradient_property(&source, comp_id.0, shape_id, colors_uid, r#"{"colors": [[0.0, [0.0, 0.0, 0.0, 1.0]]], "opacities": [[0.0, 2.0]]}"#).is_err());
        assert!(set_layer_gradient_property(&source, comp_id.0, shape_id, 999_999, r#"{"colors": [[0.0, [0.0, 0.0, 0.0, 1.0]]], "opacities": [[0.0, 1.0]]}"#).is_err());
    }

    #[test]
    fn canonical_path_parameter_is_exposed_and_updated_with_validated_geometry() {
        use effectcraft_project::{build, Comp, ItemKind, LayerSource};
        use effectcraft_project::keyframe::ShapePath;
        use effectcraft_project::props::MaskMode;
        use effectcraft_color::Label;
        use effectcraft_time::{FrameRate, Tick};

        let mut project = Project::default();
        let comp = Comp::new(640, 360, FrameRate::FPS_30, Tick::from_seconds_f64(3.0));
        let comp_id = project.add_item("Main", Label::Sandstone, None, ItemKind::Comp(comp.clone().into()));
        let mut layer = build::layer(&mut project, &comp, "Shape", LayerSource::Shape, (640, 360), None);
        let layer_id = layer.id.0;
        let mut next_id = 20_000u64;
        let mut ids = build::Ids(&mut next_id);
        let mask = build::mask(&mut ids, "Mask 1", ShapePath::rect([100.0, 80.0], 40.0, 30.0), MaskMode::Add, [0xb1, 0xb4, 0x4f]);
        let path_uid = mask.children.iter().find_map(|node| match node {
            Node::Prop(property) if property.match_id == "path" => Some(property.uid),
            _ => None,
        }).unwrap();
        layer.props.sub_mut("masks").unwrap().children.push(mask.into());
        project.comp_mut(comp_id).unwrap().layers.push(layer);
        let source = project.to_file_json().unwrap();
        let metadata: serde_json::Value = serde_json::from_str(&inspect_project(&source).unwrap()).unwrap();
        let groups = metadata["compositions"][0]["layers"][0]["shapeItems"].as_array().unwrap();
        let mask_meta = groups.iter().find(|group| group["kind"] == "mask").unwrap();
        let path_meta = mask_meta["paths"].as_array().unwrap().iter().find(|path| path["uid"] == path_uid).unwrap();
        assert_eq!(path_meta["path"]["vertices"].as_array().unwrap().len(), 4);
        assert_eq!(path_meta["path"]["closed"], true);

        let replacement = ShapePath::polygon(&[[10.0, 20.0], [40.0, 20.0], [25.0, 60.0]], false);
        let replacement_json = serde_json::to_string(&replacement).unwrap();
        let updated = set_layer_path_property(&source, comp_id.0, layer_id, path_uid, &replacement_json).unwrap();
        let updated_meta: serde_json::Value = serde_json::from_str(&inspect_project(&updated).unwrap()).unwrap();
        let updated_path = updated_meta["compositions"][0]["layers"][0]["shapeItems"].as_array().unwrap()
            .iter().find(|group| group["kind"] == "mask").unwrap()["paths"].as_array().unwrap()
            .iter().find(|path| path["uid"] == path_uid).unwrap();
        assert_eq!(updated_path["path"]["vertices"], json!([[10.0, 20.0], [40.0, 20.0], [25.0, 60.0]]));
        assert_eq!(updated_path["path"]["in_tangents"].as_array().unwrap().len(), 3);
        assert_eq!(updated_path["path"]["closed"], false);

        let mut invalid = replacement.clone();
        invalid.vertices[1][0] = f64::NAN;
        assert!(set_layer_path_property(&source, comp_id.0, layer_id, path_uid, &serde_json::to_string(&invalid).unwrap()).is_err());
        assert!(set_layer_path_property(&source, comp_id.0, layer_id, path_uid, r#"{"vertices":[],"in_tangents":[],"out_tangents":[],"closed":false}"#).is_err());
        assert!(set_layer_path_property(&source, comp_id.0, layer_id, 999_999, &replacement_json).is_err());
        assert!(set_layer_path_property(&source, comp_id.0, layer_id, path_uid, r#"{"vertices":[[0,0]],"in_tangents":[],"out_tangents":[],"closed":false,"feather":[{"segment":0,"t":2,"radius":1,"tension":0}]}"#).is_err());
    }

    /// Vertices of the frame-15 ShapePath key for the path property with
    /// `uid`, read back from the serialized project file JSON. The inspect
    /// metadata exposes only keyframe times for paths (curve keys skip
    /// Path values), so the stored key value is verified here instead.
    fn path_key_vertices(project_json: &str, uid: u64) -> serde_json::Value {
        fn find_path_property(value: &serde_json::Value, uid: u64) -> Option<&serde_json::Value> {
            match value {
                serde_json::Value::Object(map) => {
                    if map.get("uid").and_then(|id| id.as_u64()) == Some(uid)
                        && map.get("match").and_then(|m| m.as_str()) == Some("path")
                    {
                        return Some(value);
                    }
                    value.as_object().unwrap().values().find_map(|child| find_path_property(child, uid))
                }
                serde_json::Value::Array(items) => items.iter().find_map(|child| find_path_property(child, uid)),
                _ => None,
            }
        }
        let project: serde_json::Value = serde_json::from_str(project_json).unwrap();
        let property = find_path_property(&project, uid).expect("mask path property must survive serialization");
        let keys = property["keys"].as_array().expect("path property must carry its keys");
        assert_eq!(keys.len(), 2);
        keys[1]["value"]["v"]["vertices"].clone()
    }

    #[test]
    fn canonical_shape_path_keyframe_preserves_static_and_timeline_values() {
        use effectcraft_project::{build, Comp, ItemKind, LayerSource, Node};
        use effectcraft_project::props::MaskMode;
        use effectcraft_color::Label;
        use effectcraft_time::{FrameRate, Tick};
        use effectcraft_project::keyframe::ShapePath;

        let mut project = Project::default();
        let comp = Comp::new(640, 360, FrameRate::FPS_30, Tick::from_seconds_f64(3.0));
        let comp_id = project.add_item("Main", Label::Sandstone, None, ItemKind::Comp(comp.clone().into()));
        let mut layer = build::layer(&mut project, &comp, "Shape", LayerSource::Shape, (640, 360), None);
        let layer_id = layer.id.0;
        let mut next_id = 30_000u64;
        let mut ids = build::Ids(&mut next_id);
        let mask = build::mask(&mut ids, "Mask 1", ShapePath::rect([100.0, 80.0], 40.0, 30.0), MaskMode::Add, [0xb1, 0xb4, 0x4f]);
        let uid = mask.children.iter().find_map(|node| match node {
            Node::Prop(property) if property.match_id == "path" => Some(property.uid), _ => None,
        }).unwrap();
        layer.props.sub_mut("masks").unwrap().children.push(mask.into());
        project.comp_mut(comp_id).unwrap().layers.push(layer);
        let source = project.to_file_json().unwrap();
        let replacement = ShapePath::polygon(&[[10.0, 20.0], [40.0, 20.0], [25.0, 60.0]], false);
        let keyed = set_layer_path_keyframe(&source, comp_id.0, layer_id, uid, 15, &serde_json::to_string(&replacement).unwrap()).unwrap();
        let meta: serde_json::Value = serde_json::from_str(&inspect_project(&keyed).unwrap()).unwrap();
        let path = meta["compositions"][0]["layers"][0]["shapeItems"].as_array().unwrap()
            .iter().find(|group| group["kind"] == "mask").unwrap()["paths"].as_array().unwrap()
            .iter().find(|entry| entry["uid"] == uid).unwrap();
        assert_eq!(path["keyframes"], json!([0, 15]));
        // The static slot keeps the mask's original rect: keying frame 15
        // seeds a frame-0 key with it and stores the triangle at frame 15,
        // exactly like the scalar keyframe setter. The triangle lives in
        // the property's keys, not in the static value.
        assert_eq!(path["path"]["vertices"], json!([[80.0, 65.0], [120.0, 65.0], [120.0, 95.0], [80.0, 95.0]]));
        assert_eq!(path_key_vertices(&keyed, uid), json!([[10.0, 20.0], [40.0, 20.0], [25.0, 60.0]]));
        assert!(set_layer_path_keyframe(&source, comp_id.0, layer_id, uid, -1, &serde_json::to_string(&replacement).unwrap()).is_err());
        assert!(set_layer_path_keyframe(&source, comp_id.0, layer_id, 999_999, 15, &serde_json::to_string(&replacement).unwrap()).is_err());
        let mut invalid = replacement.clone(); invalid.vertices[0][0] = f64::NAN;
        assert!(set_layer_path_keyframe(&source, comp_id.0, layer_id, uid, 15, &serde_json::to_string(&invalid).unwrap()).is_err());
    }

    #[test]
    fn canonical_layer_reference_control_updates_and_validates_target() {
        use effectcraft_project::{build, Comp, ItemKind, LayerSource, Node};
        use effectcraft_project::props::{GroupKind, ParamUi, PropGroup, Property};
        use effectcraft_color::Label;
        use effectcraft_time::{FrameRate, Tick};
        let mut project = Project::default();
        let comp = Comp::new(640, 360, FrameRate::FPS_30, Tick::from_seconds_f64(3.0));
        let comp_id = project.add_item("Main", Label::Sandstone, None, ItemKind::Comp(comp.clone().into()));
        let mut source_layer = build::layer(&mut project, &comp, "Source", LayerSource::Shape, (640, 360), None);
        let source_id = source_layer.id.0;
        let target_layer = build::layer(&mut project, &comp, "Target", LayerSource::Shape, (640, 360), None);
        let target_id = target_layer.id.0;
        source_layer.props.sub_mut("effects").unwrap().children.push(Node::Group(PropGroup {
            uid: 900_001, match_id: "layerControl".into(), name: "Layer Control".into(),
            kind: GroupKind::Effect { effect: "ec.control.layer".into() }, enabled: true, label: None,
            children: vec![Node::Prop(Property::new(900_002, "layer", "Layer", Value::Layer(None)).with_ui(ParamUi::Layer))],
        }));
        let uid = 900_002;
        let comp_mut = project.comp_mut(comp_id).unwrap(); comp_mut.layers.push(source_layer); comp_mut.layers.push(target_layer);
        let source = project.to_file_json().unwrap();
        let before: serde_json::Value = serde_json::from_str(&inspect_project(&source).unwrap()).unwrap();
        let control = &before["compositions"][0]["layers"][0]["effects"][0]["layerRefs"][0];
        assert_eq!(control["uid"], uid); assert!(control["value"].is_null());
        let selected = set_layer_reference_property(&source, comp_id.0, source_id, uid, Some(target_id)).unwrap();
        let selected_meta: serde_json::Value = serde_json::from_str(&inspect_project(&selected).unwrap()).unwrap();
        assert_eq!(selected_meta["compositions"][0]["layers"][0]["effects"][0]["layerRefs"][0]["value"], target_id);
        let cleared = set_layer_reference_property(&selected, comp_id.0, source_id, uid, None).unwrap();
        let cleared_meta: serde_json::Value = serde_json::from_str(&inspect_project(&cleared).unwrap()).unwrap();
        assert!(cleared_meta["compositions"][0]["layers"][0]["effects"][0]["layerRefs"][0]["value"].is_null());
        assert!(set_layer_reference_property(&source, comp_id.0, source_id, uid, Some(999_999)).is_err());
        assert!(set_layer_reference_property(&source, comp_id.0, source_id, 999_999, Some(target_id)).is_err());
    }

    #[test]
    fn canonical_layer_reference_keyframes_are_discrete_and_serialized() {
        use effectcraft_project::{build, Comp, ItemKind, LayerSource, Node, Value};
        use effectcraft_project::props::{GroupKind, ParamUi, PropGroup, Property};
        use effectcraft_color::Label;
        use effectcraft_time::{FrameRate, Tick};
        let mut project = Project::default();
        let comp = Comp::new(640, 360, FrameRate::FPS_30, Tick::from_seconds_f64(3.0));
        let comp_id = project.add_item("Main", Label::Sandstone, None, ItemKind::Comp(comp.clone().into()));
        let mut source_layer = build::layer(&mut project, &comp, "Source", LayerSource::Shape, (640, 360), None);
        let source_id = source_layer.id.0;
        let target_layer = build::layer(&mut project, &comp, "Target", LayerSource::Shape, (640, 360), None);
        let target_id = target_layer.id.0;
        source_layer.props.sub_mut("effects").unwrap().children.push(Node::Group(PropGroup {
            uid: 900_001, match_id: "layerControl".into(), name: "Layer Control".into(),
            kind: GroupKind::Effect { effect: "ec.control.layer".into() }, enabled: true, label: None,
            children: vec![Node::Prop(Property::new(900_002, "layer", "Layer", Value::Layer(None)).with_ui(ParamUi::Layer))],
        }));
        project.comp_mut(comp_id).unwrap().layers.push(source_layer);
        project.comp_mut(comp_id).unwrap().layers.push(target_layer);
        let source = project.to_file_json().unwrap();
        let keyed = set_layer_reference_keyframe(&source, comp_id.0, source_id, 900_002, 15, Some(target_id)).unwrap();
        let metadata: serde_json::Value = serde_json::from_str(&inspect_project(&keyed).unwrap()).unwrap();
        let control = &metadata["compositions"][0]["layers"][0]["effects"][0]["layerRefs"][0];
        assert_eq!(control["keyframes"], json!([0, 15]));
        assert!(control["keys"][0]["layerId"].is_null());
        assert_eq!(control["keys"][1]["layerId"], target_id);
        let none_key = set_layer_reference_keyframe(&keyed, comp_id.0, source_id, 900_002, 30, None).unwrap();
        let metadata: serde_json::Value = serde_json::from_str(&inspect_project(&none_key).unwrap()).unwrap();
        let control = &metadata["compositions"][0]["layers"][0]["effects"][0]["layerRefs"][0];
        assert_eq!(control["keyframes"], json!([0, 15, 30]));
        assert!(control["keys"][2]["layerId"].is_null());
        // A Layer reference key created through the keyframe setter must be
        // removable through the generic curve remover too.
        let (removed_json, removed) = remove_curve_keyframe(&none_key, comp_id.0, source_id, "param:900002", 15).unwrap();
        assert!(removed);
        let removed_meta: serde_json::Value = serde_json::from_str(&inspect_project(&removed_json).unwrap()).unwrap();
        let removed_control = &removed_meta["compositions"][0]["layers"][0]["effects"][0]["layerRefs"][0];
        assert_eq!(removed_control["keyframes"], json!([0, 30]));
        assert!(set_layer_reference_property(&none_key, comp_id.0, source_id, 900_002, Some(target_id)).is_err());
        assert!(set_layer_reference_keyframe(&source, comp_id.0, source_id, 900_002, -1, Some(target_id)).is_err());
        assert!(set_layer_reference_keyframe(&source, comp_id.0, source_id, 900_002, 15, Some(999_999)).is_err());
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
    fn canonical_3d_transform_keyframes_author_interpolate_and_remove() {
        use effectcraft_project::Value;
        let (source, comp_id, layer_id) = text_layer_project();
        let anchor = set_layer_transform_keyframe(&source, comp_id, layer_id, "anchor", 0, 12.0, 34.0, 0.0).unwrap();
        let orientation_15 = set_layer_transform_keyframe(&anchor, comp_id, layer_id, "orientation", 15, 10.0, 20.0, 30.0).unwrap();
        let orientation = set_layer_transform_keyframe(&orientation_15, comp_id, layer_id, "orientation", 30, 40.0, 50.0, 60.0).unwrap();
        let rotation_x = set_layer_transform_keyframe(&orientation, comp_id, layer_id, "rotationX", 0, 45.0, 0.0, 0.0).unwrap();
        let rotation_y = set_layer_transform_keyframe(&rotation_x, comp_id, layer_id, "rotationY", 15, -30.0, 0.0, 0.0).unwrap();
        let eased = set_layer_transform_keyframe_interpolation(&rotation_y, comp_id, layer_id, "orientation", 15, "easy_ease").unwrap();
        let project: Project = serde_json::from_str(&eased).unwrap();
        let transform = project.comp(ItemId(comp_id)).unwrap().layers[0].props.sub("transform").unwrap();
        let anchor_key = &find_property_ref(transform, "anchor").unwrap().keys[0];
        assert!(matches!(&anchor_key.value, Value::Vec3(v) if v[0] == 12.0 && v[1] == 34.0));
        let orientation_keys = &transform.prop("orientation").unwrap().keys;
        assert_eq!(orientation_keys.len(), 2, "first authored key must not leave a frame-zero seed");
        assert_eq!(orientation_keys[0].time, Tick::from_rational(15, 1, 30));
        assert_eq!(orientation_keys[0].value, Value::Vec3([10.0, 20.0, 30.0]));
        assert_eq!(orientation_keys[0].in_interp, Interp::Bezier);
        assert_eq!(orientation_keys[0].out_interp, Interp::Bezier);
        assert_eq!(orientation_keys[1].time, Tick::from_rational(30, 1, 30));
        assert_eq!(orientation_keys[1].value, Value::Vec3([40.0, 50.0, 60.0]));
        let rotation_x_keys = &transform.prop("rotationX").unwrap().keys;
        assert_eq!(rotation_x_keys.len(), 1);
        assert_eq!(rotation_x_keys[0].time, Tick::ZERO);
        assert_eq!(rotation_x_keys[0].value, Value::Scalar(45.0));
        let rotation_y_keys = &transform.prop("rotationY").unwrap().keys;
        assert_eq!(rotation_y_keys.len(), 1, "first authored key must not leave a frame-zero seed");
        assert_eq!(rotation_y_keys[0].time, Tick::from_rational(15, 1, 30));
        assert_eq!(rotation_y_keys[0].value, Value::Scalar(-30.0));
        // The 3D vector property uses the same curve/drag bridge as Position.
        let anchor_bezier = set_keyframe_bezier(&eased, comp_id, layer_id, "transform:anchor", 0, "incoming", 8.0, 0.4).unwrap();
        let anchor_moved = move_keyframe(&anchor_bezier, comp_id, layer_id, "transform:anchor", 0, 5).unwrap();
        let anchor_curve_project: Project = serde_json::from_str(&anchor_moved).unwrap();
        let anchor_curve = &find_property_ref(anchor_curve_project.comp(ItemId(comp_id)).unwrap().layers[0].props.sub("transform").unwrap(), "anchor").unwrap().keys[0];
        assert_eq!(anchor_curve.time, Tick::from_rational(5, 1, 30));
        assert_eq!(anchor_curve.in_interp, Interp::Bezier);
        assert_eq!(anchor_curve.in_ease[0].influence, 0.4);

        let metadata: serde_json::Value = serde_json::from_str(&inspect_project(&eased).unwrap()).unwrap();
        let tracks = &metadata["compositions"][0]["layers"][0]["transformKeys"];
        assert_eq!(tracks["anchor"], json!([0]));
        // Orientation has two authored keys (15 and 30, asserted above), so
        // its track must list both; [15] alone is stale from before the
        // frame-30 keyframe was added to this test.
        assert_eq!(tracks["orientation"], json!([15, 30]));
        assert_eq!(tracks["rotationX"], json!([0]));
        assert_eq!(tracks["rotationY"], json!([15]));
        let (without_anchor, removed) = remove_layer_transform_keyframe(&eased, comp_id, layer_id, "anchor", 0).unwrap();
        assert!(removed);
        let after: Project = serde_json::from_str(&without_anchor).unwrap();
        assert!(find_property_ref(after.comp(ItemId(comp_id)).unwrap().layers[0].props.sub("transform").unwrap(), "anchor").unwrap().keys.is_empty());
        assert!(set_layer_transform_keyframe(&source, comp_id, layer_id, "anchor", 0, f64::NAN, 0.0, 0.0).is_err());
        assert!(set_layer_transform_keyframe(&source, comp_id, layer_id, "orientation", 0, 0.0, 0.0, f64::INFINITY).is_err());
    }

    #[test]
    fn canonical_transform_keyframe_interpolation_presets() {
        let (source, comp_id, layer_id) = text_layer_project();
        let at_zero = set_layer_transform_keyframe(&source, comp_id, layer_id, "position", 0, 10.0, 20.0, 0.0).unwrap();
        let at_fifteen = set_layer_transform_keyframe(&at_zero, comp_id, layer_id, "position", 15, 100.0, 200.0, 0.0).unwrap();
        let incoming_eased = set_layer_transform_keyframe_interpolation(&at_fifteen, comp_id, layer_id, "position", 15, "incoming_easy_ease").unwrap();
        let incoming_project: Project = serde_json::from_str(&incoming_eased).unwrap();
        let incoming_key = &incoming_project.comp(ItemId(comp_id)).unwrap().layers[0].props.sub("transform").unwrap().prop("position").unwrap().keys[1];
        assert_eq!(incoming_key.in_interp, Interp::Bezier);
        assert_eq!(incoming_key.out_interp, Interp::Linear, "incoming edit must preserve outgoing interpolation");
        assert_eq!(incoming_key.in_ease[0].influence, 1.0 / 3.0);
        assert!(incoming_key.out_ease.is_empty(), "incoming edit must preserve outgoing ease");

        let outgoing_hold = set_layer_transform_keyframe_interpolation(&incoming_eased, comp_id, layer_id, "position", 15, "outgoing_hold").unwrap();
        let outgoing_project: Project = serde_json::from_str(&outgoing_hold).unwrap();
        let outgoing_key = &outgoing_project.comp(ItemId(comp_id)).unwrap().layers[0].props.sub("transform").unwrap().prop("position").unwrap().keys[1];
        assert_eq!(outgoing_key.in_interp, Interp::Bezier, "outgoing edit must preserve incoming interpolation");
        assert_eq!(outgoing_key.out_interp, Interp::Hold);
        assert!(!outgoing_key.in_ease.is_empty());
        assert!(outgoing_key.out_ease.is_empty());

        let held = set_layer_transform_keyframe_interpolation(&outgoing_hold, comp_id, layer_id, "position", 15, "hold").unwrap();
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
    fn directional_ease_presets_shape_evaluated_rotation() {
        use effectcraft_project::Value;
        use effectcraft_time::Tick;
        fn rotation_at(project: &Project, comp_id: u64, layer_id: u64, frame: i64) -> f64 {
            let layer = project.comp(ItemId(comp_id)).unwrap().layers.iter().find(|l| l.id.0 == layer_id).unwrap();
            match layer.props.sub("transform").unwrap().prop("rotation").unwrap().value_at(Tick::from_rational(frame, 1, 30)) {
                Value::Scalar(value) => value,
                other => panic!("expected scalar rotation, got {other:?}"),
            }
        }
        fn rotation_key<'a>(project: &'a Project, comp_id: u64, layer_id: u64, index: usize) -> &'a effectcraft_project::keyframe::Keyframe {
            let layer = project.comp(ItemId(comp_id)).unwrap().layers.iter().find(|l| l.id.0 == layer_id).unwrap();
            &layer.props.sub("transform").unwrap().prop("rotation").unwrap().keys[index]
        }
        let (source, comp_id, layer_id) = text_layer_project();
        let at_zero = set_layer_transform_keyframe(&source, comp_id, layer_id, "rotation", 0, 100.0, 0.0, 0.0).unwrap();
        let keyed = set_layer_transform_keyframe(&at_zero, comp_id, layer_id, "rotation", 30, 0.0, 0.0, 0.0).unwrap();
        let linear: Project = serde_json::from_str(&keyed).unwrap();
        assert!((rotation_at(&linear, comp_id, layer_id, 15) - 50.0).abs() < 1e-9);

        // Ease-in shapes the segment LEAVING the key: it starts slow, so the
        // falling rotation stays above the linear value early and at midpoint.
        let eased_in = set_layer_transform_keyframe_interpolation(&keyed, comp_id, layer_id, "rotation", 0, "ease_in").unwrap();
        let in_project: Project = serde_json::from_str(&eased_in).unwrap();
        assert!(rotation_at(&in_project, comp_id, layer_id, 15) > 50.0, "ease-in midpoint must stay above linear 50");
        assert!(rotation_at(&in_project, comp_id, layer_id, 7) > 100.0 - 100.0 * 7.0 / 30.0, "ease-in must lag linear early");
        assert_eq!(rotation_key(&in_project, comp_id, layer_id, 0).out_interp, Interp::Bezier);
        assert_eq!(rotation_key(&in_project, comp_id, layer_id, 0).in_interp, Interp::Linear, "ease-in must not touch the incoming side");

        // Ease-out shapes the segment ARRIVING at the key: it ends slow, so
        // the value is already ahead of linear at the midpoint.
        let eased_out = set_layer_transform_keyframe_interpolation(&keyed, comp_id, layer_id, "rotation", 30, "ease_out").unwrap();
        let out_project: Project = serde_json::from_str(&eased_out).unwrap();
        assert!(rotation_at(&out_project, comp_id, layer_id, 15) < 50.0, "ease-out midpoint must lead linear 50");
        assert_eq!(rotation_key(&out_project, comp_id, layer_id, 1).in_interp, Interp::Bezier);
        assert_eq!(rotation_key(&out_project, comp_id, layer_id, 1).out_interp, Interp::Linear, "ease-out must not touch the outgoing side");

        // Ease-in-out (and its easy_ease alias) slow both ends: above linear
        // early, below linear late.
        let mut both = set_layer_transform_keyframe_interpolation(&keyed, comp_id, layer_id, "rotation", 0, "ease_in_out").unwrap();
        both = set_layer_transform_keyframe_interpolation(&both, comp_id, layer_id, "rotation", 30, "ease_in_out").unwrap();
        let both_project: Project = serde_json::from_str(&both).unwrap();
        assert!(rotation_at(&both_project, comp_id, layer_id, 7) > 100.0 - 100.0 * 7.0 / 30.0);
        assert!(rotation_at(&both_project, comp_id, layer_id, 23) < 100.0 - 100.0 * 23.0 / 30.0);
        let aliased = set_layer_transform_keyframe_interpolation(&keyed, comp_id, layer_id, "rotation", 0, "easy_ease").unwrap();
        let aliased_project: Project = serde_json::from_str(&aliased).unwrap();
        assert_eq!(rotation_key(&aliased_project, comp_id, layer_id, 0).out_interp, Interp::Bezier);

        // Directional presets ignore the qualifier: their direction IS their side.
        let qualified_in = set_layer_transform_keyframe_interpolation(&keyed, comp_id, layer_id, "rotation", 0, "incoming_ease_in").unwrap();
        let qualified_project: Project = serde_json::from_str(&qualified_in).unwrap();
        assert_eq!(rotation_key(&qualified_project, comp_id, layer_id, 0).out_interp, Interp::Bezier);
        assert_eq!(rotation_key(&qualified_project, comp_id, layer_id, 0).in_interp, Interp::Linear);

        // The asymmetric Bezier preset must also move the midpoint off linear,
        // with 25 % incoming / 75 % outgoing influence at zero speed.
        let bezier = set_layer_transform_keyframe_interpolation(&keyed, comp_id, layer_id, "rotation", 0, "bezier").unwrap();
        let bezier_project: Project = serde_json::from_str(&bezier).unwrap();
        assert!((rotation_at(&bezier_project, comp_id, layer_id, 15) - 50.0).abs() > 1e-6);
        let bezier_key = rotation_key(&bezier_project, comp_id, layer_id, 0);
        assert_eq!(bezier_key.in_ease[0].influence, 0.25);
        assert_eq!(bezier_key.out_ease[0].influence, 0.75);
        assert_eq!(bezier_key.in_ease[0].speed, 0.0);
        assert_eq!(bezier_key.out_ease[0].speed, 0.0);

        assert!(set_layer_transform_keyframe_interpolation(&keyed, comp_id, layer_id, "rotation", 0, "bogus").is_err());
        assert!(set_layer_transform_keyframe_interpolation(&keyed, comp_id, layer_id, "rotation", 10, "ease_in").is_err());
        assert!(set_layer_transform_keyframe_interpolation(&keyed, comp_id, layer_id, "nope", 0, "ease_in").is_err());
        assert!(set_layer_transform_keyframe_interpolation(&keyed, comp_id, layer_id, "rotation", 0, "ease_in").is_ok());
    }

    #[test]
    fn canonical_curve_bezier_handles_transform_and_opacity() {
        let (source, comp_id, layer_id) = text_layer_project();
        let at_zero = set_layer_transform_keyframe(&source, comp_id, layer_id, "position", 0, 10.0, 20.0, 0.0).unwrap();
        let at_fifteen = set_layer_transform_keyframe(&at_zero, comp_id, layer_id, "position", 15, 100.0, 200.0, 0.0).unwrap();
        let bezier = set_keyframe_bezier(&at_fifteen, comp_id, layer_id, "transform:position", 15, "incoming", 20.0, 0.25).unwrap();
        let project: Project = serde_json::from_str(&bezier).unwrap();
        let key = &project.comp(ItemId(comp_id)).unwrap().layers[0].props.sub("transform").unwrap().prop("position").unwrap().keys[1];
        assert_eq!(key.in_interp, Interp::Bezier);
        assert_eq!(key.in_ease[0].speed, 20.0);
        assert_eq!(key.in_ease[0].influence, 0.25);
        assert_eq!(key.out_interp, Interp::Linear, "incoming edit must preserve outgoing interpolation");
        assert_eq!(key.in_ease.len(), 3, "Vec3 position carries one ease entry per dimension");

        let both = set_keyframe_bezier(&bezier, comp_id, layer_id, "transform:position", 0, "both", 0.0, 0.5).unwrap();
        let both_project: Project = serde_json::from_str(&both).unwrap();
        let first = &both_project.comp(ItemId(comp_id)).unwrap().layers[0].props.sub("transform").unwrap().prop("position").unwrap().keys[0];
        assert_eq!(first.in_interp, Interp::Bezier);
        assert_eq!(first.out_interp, Interp::Bezier);
        assert_eq!(first.out_ease[0].influence, 0.5);

        assert!(set_keyframe_bezier(&both, comp_id, layer_id, "transform:position", 15, "incoming", 1.0, 1.5).is_err());
        assert!(set_keyframe_bezier(&both, comp_id, layer_id, "transform:position", 14, "incoming", 1.0, 0.5).is_err());
        assert!(set_keyframe_bezier(&both, comp_id, layer_id, "transform:anchor", 15, "incoming", 1.0, 0.5).is_err());
        assert!(set_keyframe_bezier(&both, comp_id, layer_id, "param:abc", 15, "incoming", 1.0, 0.5).is_err());

        let opacity_zero = set_layer_opacity_keyframe(&source, comp_id, layer_id, 0, 100.0).unwrap();
        let opacity_fifteen = set_layer_opacity_keyframe(&opacity_zero, comp_id, layer_id, 15, 50.0).unwrap();
        let opacity_bezier = set_keyframe_bezier(&opacity_fifteen, comp_id, layer_id, "transform:opacity", 15, "outgoing", 30.0, 0.4).unwrap();
        let opacity_project: Project = serde_json::from_str(&opacity_bezier).unwrap();
        let opacity_key = opacity_property(&opacity_project, comp_id, layer_id).keys.iter().find(|k| k.time == effectcraft_time::Tick::from_rational(15, 1, 30)).unwrap();
        assert_eq!(opacity_key.out_interp, Interp::Bezier);
        assert_eq!(opacity_key.out_ease.len(), 1);
        assert_eq!(opacity_key.out_ease[0].speed, 30.0);
        assert_eq!(opacity_key.out_ease[0].influence, 0.4);
        let metadata = serde_json::from_str::<serde_json::Value>(&inspect_project(&opacity_bezier).unwrap()).unwrap();
        let curve = &metadata["compositions"][0]["layers"][0]["opacityCurves"];
        assert_eq!(curve[1]["outInterp"], "Bezier");
        assert_eq!(curve[1]["outEase"][0]["speed"], 30.0);
    }

    #[test]
    fn canonical_curve_key_drag_moves_reorders_and_preserves_bezier() {
        let (source, comp_id, layer_id) = text_layer_project();
        let keyed = set_layer_transform_keyframe(&source, comp_id, layer_id, "position", 0, 10.0, 20.0, 0.0).unwrap();
        let keyed = set_layer_transform_keyframe(&keyed, comp_id, layer_id, "position", 10, 50.0, 60.0, 0.0).unwrap();
        let keyed = set_layer_transform_keyframe(&keyed, comp_id, layer_id, "position", 20, 100.0, 200.0, 0.0).unwrap();
        let keyed = set_keyframe_bezier(&keyed, comp_id, layer_id, "transform:position", 10, "both", 12.0, 0.3).unwrap();
        let past = move_keyframe(&keyed, comp_id, layer_id, "transform:position", 10, 25).unwrap();
        let past_project: Project = serde_json::from_str(&past).unwrap();
        let position = past_project.comp(ItemId(comp_id)).unwrap().layers[0].props.sub("transform").unwrap().prop("position").unwrap();
        let frames: Vec<i64> = position.keys.iter().map(|k| k.time.to_rational_floor(1, 30)).collect();
        assert_eq!(frames, vec![0, 20, 25]);
        let dragged = &position.keys[2];
        assert_eq!(dragged.value, Value::Vec3([50.0, 60.0, 0.0]));
        assert_eq!(dragged.in_interp, Interp::Bezier, "drag must preserve the moved key Bezier sides");
        assert_eq!(dragged.in_ease[0].speed, 12.0);
        assert_eq!(dragged.out_ease[0].influence, 0.3);
        let onto = move_keyframe(&keyed, comp_id, layer_id, "transform:position", 10, 20).unwrap();
        let onto_project: Project = serde_json::from_str(&onto).unwrap();
        let onto_position = onto_project.comp(ItemId(comp_id)).unwrap().layers[0].props.sub("transform").unwrap().prop("position").unwrap();
        assert_eq!(onto_position.keys.len(), 2);
        assert_eq!(onto_position.keys[1].value, Value::Vec3([50.0, 60.0, 0.0]));
        assert!(move_keyframe(&keyed, comp_id, layer_id, "transform:position", 12, 18).is_err());
        assert!(move_keyframe(&keyed, comp_id, layer_id, "transform:position", 10, -1).is_err());
        let same = move_keyframe(&keyed, comp_id, layer_id, "transform:position", 10, 10).unwrap();
        assert_eq!(same, keyed);
    }

    fn position_property(project: &Project, comp_id: u64, layer_id: u64) -> &effectcraft_project::Property {
        let layer = project.comp(ItemId(comp_id)).unwrap().layers.iter().find(|l| l.id.0 == layer_id).unwrap();
        layer.props.sub("transform").unwrap().get("position").unwrap()
    }

    fn position_frames(project: &Project, comp_id: u64, layer_id: u64) -> Vec<i64> {
        position_property(project, comp_id, layer_id).keys.iter()
            .map(|k| k.time.to_rational_floor(1, 30)).collect()
    }

    #[test]
    fn roving_key_is_retimed_proportionally_to_path_length() {
        let (source, comp_id, layer_id) = text_layer_project();
        // Equal 100px legs: the roving middle key lands exactly halfway in time.
        let keyed = set_layer_transform_keyframe(&source, comp_id, layer_id, "position", 0, 0.0, 0.0, 0.0).unwrap();
        let keyed = set_layer_transform_keyframe(&keyed, comp_id, layer_id, "position", 10, 100.0, 0.0, 0.0).unwrap();
        let keyed = set_layer_transform_keyframe(&keyed, comp_id, layer_id, "position", 30, 100.0, 100.0, 0.0).unwrap();
        let roved = set_keyframe_roving(&keyed, comp_id, layer_id, "transform:position", 10, true).unwrap();
        let project: Project = serde_json::from_str(&roved).unwrap();
        assert_eq!(position_frames(&project, comp_id, layer_id), vec![0, 15, 30]);
        assert!(position_property(&project, comp_id, layer_id).keys[1].roving);
        let metadata: serde_json::Value = serde_json::from_str(&inspect_project(&roved).unwrap()).unwrap();
        let curve = &metadata["compositions"][0]["layers"][0]["transformCurves"]["position"];
        assert_eq!(curve[1]["roving"], true);
        assert_eq!(curve[1]["spatialAuto"], true);
        assert_eq!(curve[0]["roving"], false);

        // Unequal legs (30px then 40px): middle lands at 30/70 of the 30-frame span.
        let keyed = set_layer_transform_keyframe(&source, comp_id, layer_id, "position", 0, 0.0, 0.0, 0.0).unwrap();
        let keyed = set_layer_transform_keyframe(&keyed, comp_id, layer_id, "position", 10, 30.0, 0.0, 0.0).unwrap();
        let keyed = set_layer_transform_keyframe(&keyed, comp_id, layer_id, "position", 30, 30.0, 40.0, 0.0).unwrap();
        let roved = set_keyframe_roving(&keyed, comp_id, layer_id, "transform:position", 10, true).unwrap();
        let project: Project = serde_json::from_str(&roved).unwrap();
        assert_eq!(position_frames(&project, comp_id, layer_id), vec![0, 13, 30]);

        // First and last keys can never rove: setting roving on them is cleared.
        let first = set_keyframe_roving(&keyed, comp_id, layer_id, "transform:position", 0, true).unwrap();
        let first_project: Project = serde_json::from_str(&first).unwrap();
        assert!(!position_property(&first_project, comp_id, layer_id).keys[0].roving);
        let last = set_keyframe_roving(&keyed, comp_id, layer_id, "transform:position", 30, true).unwrap();
        let last_project: Project = serde_json::from_str(&last).unwrap();
        assert!(!position_property(&last_project, comp_id, layer_id).keys[2].roving);

        // Fewer than three keys clears roving everywhere.
        let two = set_layer_transform_keyframe(&source, comp_id, layer_id, "position", 0, 0.0, 0.0, 0.0).unwrap();
        let two = set_layer_transform_keyframe(&two, comp_id, layer_id, "position", 10, 100.0, 0.0, 0.0).unwrap();
        let two = set_keyframe_roving(&two, comp_id, layer_id, "transform:position", 0, true).unwrap();
        let two_project: Project = serde_json::from_str(&two).unwrap();
        assert!(position_property(&two_project, comp_id, layer_id).keys.iter().all(|k| !k.roving));

        // Roving is a spatial-property concept: scale rejects it, as do bad frames.
        assert!(set_keyframe_roving(&keyed, comp_id, layer_id, "transform:scale", 10, true).is_err());
        assert!(set_keyframe_roving(&keyed, comp_id, layer_id, "transform:position", 11, true).is_err());
        assert!(set_keyframe_roving(&keyed, comp_id, layer_id, "transform:position", -1, true).is_err());
    }

    #[test]
    fn spatial_tangents_bend_the_path() {
        let (source, comp_id, layer_id) = text_layer_project();
        let keyed = set_layer_transform_keyframe(&source, comp_id, layer_id, "position", 0, 0.0, 0.0, 0.0).unwrap();
        let keyed = set_layer_transform_keyframe(&keyed, comp_id, layer_id, "position", 15, 100.0, 0.0, 0.0).unwrap();
        let keyed = set_layer_transform_keyframe(&keyed, comp_id, layer_id, "position", 30, 200.0, 0.0, 0.0).unwrap();
        let bent = set_keyframe_spatial_tangents(&keyed, comp_id, layer_id, "transform:position", 15, [0.0, -60.0, 0.0], [0.0, -60.0, 0.0], false).unwrap();
        let project: Project = serde_json::from_str(&bent).unwrap();
        let property = position_property(&project, comp_id, layer_id);
        assert_eq!(property.keys[1].spatial_in, [0.0, -60.0, 0.0]);
        assert_eq!(property.keys[1].spatial_out, [0.0, -60.0, 0.0]);
        assert!(!property.keys[1].spatial_auto);
        // Mid-segment evaluation sits on the Bezier curve, well off the y=0 chord.
        let mid = match property.value_at(effectcraft_time::Tick::from_rational(22, 1, 30)) {
            Value::Vec3(value) => value,
            _ => panic!("position must evaluate to Vec3"),
        };
        assert!(mid[0] > 100.0 && mid[0] < 200.0, "bent path x {mid:?} must stay between its keys");
        assert!(mid[1] < -10.0, "bent path y {} must sit well above the straight chord", mid[1]);
        let metadata: serde_json::Value = serde_json::from_str(&inspect_project(&bent).unwrap()).unwrap();
        let curve = &metadata["compositions"][0]["layers"][0]["transformCurves"]["position"];
        assert_eq!(curve[1]["spatialIn"], json!([0.0, -60.0, 0.0]));
        assert_eq!(curve[1]["spatialOut"], json!([0.0, -60.0, 0.0]));
        assert_eq!(curve[1]["spatialAuto"], false);

        // Auto-Bezier keys derive Catmull-Rom tangents from neighbours, so the
        // path bulges off the chord even with no stored tangents.
        let keyed = set_layer_transform_keyframe(&source, comp_id, layer_id, "position", 0, 0.0, 0.0, 0.0).unwrap();
        let keyed = set_layer_transform_keyframe(&keyed, comp_id, layer_id, "position", 15, 100.0, 100.0, 0.0).unwrap();
        let keyed = set_layer_transform_keyframe(&keyed, comp_id, layer_id, "position", 30, 200.0, 0.0, 0.0).unwrap();
        let auto_project: Project = serde_json::from_str(&keyed).unwrap();
        let auto_value = match position_property(&auto_project, comp_id, layer_id)
            .value_at(effectcraft_time::Tick::from_rational(7, 1, 30)) {
            Value::Vec3(value) => value,
            _ => panic!("position must evaluate to Vec3"),
        };
        assert!(auto_value[1] > 51.0, "auto-Bezier path y {} must bulge above the chord midpoint 50", auto_value[1]);

        // Tangents on a non-spatial property, non-finite components and bad frames reject.
        assert!(set_keyframe_spatial_tangents(&keyed, comp_id, layer_id, "transform:scale", 15, [1.0, 0.0, 0.0], [1.0, 0.0, 0.0], false).is_err());
        assert!(set_keyframe_spatial_tangents(&keyed, comp_id, layer_id, "transform:position", 15, [f64::NAN, 0.0, 0.0], [0.0, 0.0, 0.0], false).is_err());
        assert!(set_keyframe_spatial_tangents(&keyed, comp_id, layer_id, "transform:position", 15, [0.0, 0.0, 0.0], [f64::INFINITY, 0.0, 0.0], false).is_err());
        assert!(set_keyframe_spatial_tangents(&keyed, comp_id, layer_id, "transform:position", 16, [0.0, 0.0, 0.0], [0.0, 0.0, 0.0], false).is_err());
        assert!(set_keyframe_spatial_tangents(&keyed, comp_id, layer_id, "transform:position", -1, [0.0, 0.0, 0.0], [0.0, 0.0, 0.0], false).is_err());
    }

    #[test]
    fn move_keyframe_preserves_spatial_fields_and_retimes() {
        let (source, comp_id, layer_id) = text_layer_project();
        let keyed = set_layer_transform_keyframe(&source, comp_id, layer_id, "position", 0, 0.0, 0.0, 0.0).unwrap();
        let keyed = set_layer_transform_keyframe(&keyed, comp_id, layer_id, "position", 15, 100.0, 0.0, 0.0).unwrap();
        let keyed = set_layer_transform_keyframe(&keyed, comp_id, layer_id, "position", 30, 200.0, 0.0, 0.0).unwrap();
        let keyed = set_keyframe_spatial_tangents(&keyed, comp_id, layer_id, "transform:position", 15, [0.0, -40.0, 0.0], [0.0, -40.0, 0.0], false).unwrap();
        let keyed = set_keyframe_roving(&keyed, comp_id, layer_id, "transform:position", 15, true).unwrap();
        // Dragging a roving key in time cannot move it: retiming re-spaces it
        // by path length (equal legs here, so it snaps back to frame 15),
        // while its roving flag and tangents survive the drag.
        let dragged = move_keyframe(&keyed, comp_id, layer_id, "transform:position", 15, 20).unwrap();
        let project: Project = serde_json::from_str(&dragged).unwrap();
        assert_eq!(position_frames(&project, comp_id, layer_id), vec![0, 15, 30]);
        let moved = &position_property(&project, comp_id, layer_id).keys[1];
        assert!(moved.roving);
        assert_eq!(moved.spatial_in, [0.0, -40.0, 0.0]);
        assert_eq!(moved.spatial_out, [0.0, -40.0, 0.0]);
        assert!(!moved.spatial_auto);
        assert_eq!(moved.value, Value::Vec3([100.0, 0.0, 0.0]));

        // A non-roving key drags freely in time and still keeps its tangents.
        let plain = set_keyframe_roving(&keyed, comp_id, layer_id, "transform:position", 15, false).unwrap();
        let dragged = move_keyframe(&plain, comp_id, layer_id, "transform:position", 15, 20).unwrap();
        let project: Project = serde_json::from_str(&dragged).unwrap();
        assert_eq!(position_frames(&project, comp_id, layer_id), vec![0, 20, 30]);
        let moved = &position_property(&project, comp_id, layer_id).keys[1];
        assert!(!moved.roving);
        assert_eq!(moved.spatial_in, [0.0, -40.0, 0.0]);
        assert_eq!(moved.spatial_out, [0.0, -40.0, 0.0]);
    }

    #[test]
    fn remove_curve_keyframe_retimes_roving_run() {
        let (source, comp_id, layer_id) = text_layer_project();
        let keyed = set_layer_transform_keyframe(&source, comp_id, layer_id, "position", 0, 0.0, 0.0, 0.0).unwrap();
        let keyed = set_layer_transform_keyframe(&keyed, comp_id, layer_id, "position", 10, 100.0, 0.0, 0.0).unwrap();
        let keyed = set_layer_transform_keyframe(&keyed, comp_id, layer_id, "position", 20, 100.0, 100.0, 0.0).unwrap();
        let keyed = set_layer_transform_keyframe(&keyed, comp_id, layer_id, "position", 30, 200.0, 100.0, 0.0).unwrap();
        let keyed = set_keyframe_roving(&keyed, comp_id, layer_id, "transform:position", 10, true).unwrap();
        let keyed = set_keyframe_roving(&keyed, comp_id, layer_id, "transform:position", 20, true).unwrap();
        // Deleting a roving key re-times the survivor by path length:
        // arc length with auto tangents puts it ~12.5 frames in, rounding to 13.
        let (removed, was_removed) = remove_curve_keyframe(&keyed, comp_id, layer_id, "transform:position", 20).unwrap();
        assert!(was_removed);
        let project: Project = serde_json::from_str(&removed).unwrap();
        assert_eq!(position_frames(&project, comp_id, layer_id), vec![0, 13, 30]);
        assert!(position_property(&project, comp_id, layer_id).keys[1].roving);
        // Deleting a fixed endpoint never strands a roving key at the edge:
        // the new first key has its roving flag cleared.
        let (removed, was_removed) = remove_curve_keyframe(&keyed, comp_id, layer_id, "transform:position", 0).unwrap();
        assert!(was_removed);
        let project: Project = serde_json::from_str(&removed).unwrap();
        assert_eq!(position_frames(&project, comp_id, layer_id), vec![10, 20, 30]);
        assert!(!position_property(&project, comp_id, layer_id).keys[0].roving);
    }

    fn red_centroid(bytes: &[u8]) -> Option<(f64, f64)> {
        if bytes.len() < 8 { return None; }
        let width = u32::from_be_bytes(bytes[0..4].try_into().unwrap()) as usize;
        let (mut sx, mut sy, mut n) = (0f64, 0f64, 0f64);
        for (index, pixel) in bytes[8..].chunks_exact(4).enumerate() {
            if pixel[0] > 200 && pixel[1] < 20 && pixel[2] < 20 && pixel[3] > 200 {
                sx += (index % width) as f64;
                sy += (index / width) as f64;
                n += 1.0;
            }
        }
        if n == 0.0 { None } else { Some((sx / n, sy / n)) }
    }

    #[test]
    fn spatial_keys_change_rendered_frame() {
        use effectcraft_project::{build, Comp, Footage, FootageKind, ItemKind, LayerSource};
        use effectcraft_color::Label;
        use effectcraft_time::{FrameRate, Tick};
        use image::ImageEncoder;

        // A 16x16 red still: footage draws at its source size, so the red
        // square spans the 16x16 layer and its centroid tracks the position.
        let mut png = Vec::new();
        image::codecs::png::PngEncoder::new(&mut png)
            .write_image(&vec![255u8, 0, 0, 255].repeat(16 * 16), 16, 16, image::ExtendedColorType::Rgba8)
            .unwrap();
        let path = std::env::temp_dir().join("effectcraft-spatial-red.png");
        std::fs::write(&path, png).unwrap();
        let mut project = Project::default();
        let comp = Comp::new(64, 64, FrameRate::FPS_30, Tick::from_seconds_f64(3.0));
        let comp_id = project.add_item("Spatial", Label::Sandstone, None, ItemKind::Comp(comp.clone().into()));
        let footage = Footage {
            path: path.to_string_lossy().into_owned(),
            kind: FootageKind::Still,
            width: 16,
            height: 16,
            has_video: true,
            missing: false,
            ..Footage::default()
        };
        let item_id = project.add_item("Red pixel", Label::Sandstone, None, ItemKind::Footage(footage));
        let layer = build::layer(&mut project, &comp, "Red square", LayerSource::Footage { item: item_id }, (16, 16), None);
        let layer_id = layer.id.0;
        project.comp_mut(comp_id).unwrap().layers.push(layer);
        let source = project.to_file_json().unwrap();

        // Straight horizontal run, bent upward by explicit spatial tangents.
        let keyed = set_layer_transform_keyframe(&source, comp_id.0, layer_id, "position", 0, 16.0, 32.0, 0.0).unwrap();
        let keyed = set_layer_transform_keyframe(&keyed, comp_id.0, layer_id, "position", 30, 48.0, 32.0, 0.0).unwrap();
        let keyed = set_keyframe_spatial_tangents(&keyed, comp_id.0, layer_id, "transform:position", 0, [0.0, 0.0, 0.0], [0.0, -24.0, 0.0], false).unwrap();
        let keyed = set_keyframe_spatial_tangents(&keyed, comp_id.0, layer_id, "transform:position", 30, [0.0, -24.0, 0.0], [0.0, 0.0, 0.0], false).unwrap();

        // Control: at frame 0 the square sits at its first key.
        let frame0 = render_frame_rgba(&keyed, comp_id.0, 0, 64).unwrap();
        let (x0, y0) = red_centroid(&frame0).expect("frame 0 must contain the red square");
        assert!((x0 - 16.0).abs() < 4.0, "frame 0 centroid x {x0} must sit at the first key");
        assert!((y0 - 32.0).abs() < 4.0, "frame 0 centroid y {y0} must sit at the first key");
        // Mid-time the square rides the Bezier path (y ~14), not the y=32 chord.
        let frame15 = render_frame_rgba(&keyed, comp_id.0, 15, 64).unwrap();
        let (x15, y15) = red_centroid(&frame15).expect("frame 15 must contain the red square");
        assert!((x15 - 32.0).abs() < 6.0, "mid-time centroid x {x15} must sit halfway across");
        assert!(y15 < 24.0, "mid-time centroid y {y15} must ride the bent motion path, not the y=32 chord");
        std::fs::remove_file(path).unwrap();
    }

    /// M4-L10 Gap 3 (bool/popup hold keys): builds a shape layer carrying a
    /// Checkbox bool (Rectangle Per-Corner Roundness) and a Popup enum
    /// (Polystar Type: Star/Polygon) plus a red fill, and a null layer with
    /// a Gaussian Blur group carrying a Checkbox bool (Repeat Edge Pixels)
    /// and a Popup enum (Blur Dimensions). Returns the project JSON, the
    /// composition id, the shape layer id, the null layer id, and the four
    /// parameter uids in (per_corner, star_type, repeat_edge, dimensions)
    /// order.
    fn bool_popup_project() -> (String, u64, u64, u64, (u64, u64, u64, u64)) {
        use effectcraft_project::{build, Comp, ItemKind, LayerSource, Value};
        use effectcraft_project::props::ParamUi;
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
        let star = build::shape_star(&mut ids, true, 5.0, [0.0, 0.0], 120.0, 50.0);
        let fill = build::shape_fill(&mut ids, [1.0, 0.0, 0.0, 1.0]);
        shape_layer.props.sub_mut("contents").unwrap().children.push(rect.into());
        shape_layer.props.sub_mut("contents").unwrap().children.push(star.into());
        shape_layer.props.sub_mut("contents").unwrap().children.push(fill.into());
        project.comp_mut(comp_id).unwrap().layers.push(shape_layer);
        let mut null_layer = build::layer(&mut project, &comp, "Null", LayerSource::Null, (640, 360), None);
        let null_id = null_layer.id.0;
        let mut effect = ids.group("gaussianBlur", "Gaussian Blur");
        effect = effect.with(ids.prop("blurriness", "Blurriness", Value::Scalar(0.0)));
        effect = effect.with(ids.prop("repeatEdge", "Repeat Edge Pixels", Value::Bool(false)).with_ui(ParamUi::Checkbox));
        effect = effect.with(ids.prop("dimensions", "Blur Dimensions", Value::Enum(0)).with_ui(ParamUi::Popup { options: vec!["Horizontal".to_owned(), "Vertical".to_owned(), "Both".to_owned()] }));
        null_layer.props.sub_mut("effects").unwrap().children.push(effect.into());
        project.comp_mut(comp_id).unwrap().layers.push(null_layer);
        let source = project.to_file_json().unwrap();
        let metadata: serde_json::Value = serde_json::from_str(&inspect_project(&source).unwrap()).unwrap();
        let shape_meta = &metadata["compositions"][0]["layers"][0]["shapeItems"];
        let rect_item = shape_meta.as_array().unwrap().iter()
            .find(|item| item["kind"] == "rect").expect("rect shape item must be exposed");
        let per_corner_uid = rect_item["bools"].as_array().unwrap().iter()
            .find(|param| param["name"] == "Per-Corner Roundness").expect("rect Per-Corner Roundness bool must be exposed")["uid"].as_u64().unwrap();
        let star_item = shape_meta.as_array().unwrap().iter()
            .find(|item| item["kind"] == "star").expect("star shape item must be exposed");
        let star_type_uid = star_item["popups"].as_array().unwrap().iter()
            .find(|param| param["name"] == "Type").expect("star Type popup must be exposed")["uid"].as_u64().unwrap();
        let blur_item = metadata["compositions"][0]["layers"][1]["effects"].as_array().unwrap().iter()
            .find(|item| item["name"] == "Gaussian Blur").expect("effect group must be exposed");
        let repeat_edge_uid = blur_item["bools"].as_array().unwrap().iter()
            .find(|param| param["name"] == "Repeat Edge Pixels").expect("Repeat Edge Pixels bool must be exposed")["uid"].as_u64().unwrap();
        let dimensions_uid = blur_item["popups"].as_array().unwrap().iter()
            .find(|param| param["name"] == "Blur Dimensions").expect("Blur Dimensions popup must be exposed")["uid"].as_u64().unwrap();
        (source, comp_id.0, shape_id, null_id, (per_corner_uid, star_type_uid, repeat_edge_uid, dimensions_uid))
    }

    #[test]
    fn canonical_bool_and_popup_hold_keys_roundtrip_metadata_and_remove() {
        use effectcraft_project::Value;
        use effectcraft_time::Tick;

        let (source, comp_id, shape_id, null_id, (per_corner_uid, star_type_uid, repeat_edge_uid, dimensions_uid)) = bool_popup_project();
        // Metadata exposes the static values, the popup options, and empty key lists.
        let metadata: serde_json::Value = serde_json::from_str(&inspect_project(&source).unwrap()).unwrap();
        let rect_item = metadata["compositions"][0]["layers"][0]["shapeItems"].as_array().unwrap().iter()
            .find(|item| item["kind"] == "rect").unwrap();
        let per_corner_meta = rect_item["bools"].as_array().unwrap().iter()
            .find(|param| param["name"] == "Per-Corner Roundness").unwrap();
        assert_eq!(per_corner_meta["value"], false);
        assert_eq!(per_corner_meta["keys"], json!([]));
        assert_eq!(per_corner_meta["keyframes"], json!([]));
        let star_item = metadata["compositions"][0]["layers"][0]["shapeItems"].as_array().unwrap().iter()
            .find(|item| item["kind"] == "star").unwrap();
        let star_type_meta = star_item["popups"].as_array().unwrap().iter()
            .find(|param| param["name"] == "Type").unwrap();
        assert_eq!(star_type_meta["value"], 0);
        assert_eq!(star_type_meta["options"], json!(["Star", "Polygon"]));
        assert_eq!(star_type_meta["keys"], json!([]));
        // Hidden popups (e.g. Fill Blend Mode) stay omitted: metadata never
        // promises an edit the UI cannot make, while real popups show.
        let fill_item = metadata["compositions"][0]["layers"][0]["shapeItems"].as_array().unwrap().iter()
            .find(|item| item["kind"] == "fill").unwrap();
        let fill_popups = fill_item["popups"].as_array().unwrap();
        assert!(fill_popups.iter().any(|param| param["name"] == "Fill Rule"));
        assert!(fill_popups.iter().all(|param| param["name"] != "Blend Mode"));

        // Static setters update the static value while the property has no keys.
        let toggled = set_layer_bool_property(&source, comp_id, shape_id, per_corner_uid, true).unwrap();
        let toggled_meta: serde_json::Value = serde_json::from_str(&inspect_project(&toggled).unwrap()).unwrap();
        assert_eq!(toggled_meta["compositions"][0]["layers"][0]["shapeItems"].as_array().unwrap().iter()
            .find(|item| item["kind"] == "rect").unwrap()["bools"][0]["value"], true);
        let picked = set_layer_popup_property(&source, comp_id, null_id, dimensions_uid, 2).unwrap();
        let picked_meta: serde_json::Value = serde_json::from_str(&inspect_project(&picked).unwrap()).unwrap();
        assert_eq!(picked_meta["compositions"][0]["layers"][1]["effects"][0]["popups"][0]["value"], 2);
        assert!(set_layer_popup_property(&source, comp_id, null_id, dimensions_uid, 3).is_err(), "index 3 is outside the 3-option popup");
        assert!(set_layer_bool_property(&source, comp_id, shape_id, star_type_uid, true).is_err(), "bool setter must reject an Enum parameter");
        assert!(set_layer_popup_property(&source, comp_id, null_id, repeat_edge_uid, 1).is_err(), "popup setter must reject a Bool parameter");

        // Hold keyframes: the first key seeds frame 0 with the static value,
        // keys carry Hold interpolation on both sides, and evaluation holds
        // (never tweens) between keys.
        let keyed_bool = set_layer_bool_keyframe(&toggled, comp_id, shape_id, per_corner_uid, 15, false).unwrap();
        let bool_project: Project = serde_json::from_str(&keyed_bool).unwrap();
        let bool_prop = bool_project.comp(ItemId(comp_id)).unwrap()
            .layers.iter().find(|layer| layer.id == LayerId(shape_id)).unwrap()
            .props.prop(&format!("@{per_corner_uid}")).unwrap();
        assert_eq!(bool_prop.keys.len(), 2, "first bool key seeds frame 0 from the static value");
        assert_eq!(bool_prop.keys[0].time, Tick::ZERO);
        assert_eq!(bool_prop.keys[0].value, Value::Bool(true));
        assert_eq!(bool_prop.keys[0].in_interp, Interp::Hold);
        assert_eq!(bool_prop.keys[0].out_interp, Interp::Hold);
        assert_eq!(bool_prop.keys[1].time, Tick::from_rational(15, 1, 30));
        assert_eq!(bool_prop.keys[1].value, Value::Bool(false));
        assert_eq!(bool_prop.keys[1].in_interp, Interp::Hold);
        assert_eq!(bool_prop.keys[1].out_interp, Interp::Hold);
        assert_eq!(bool_prop.value_at(Tick::from_rational(7, 1, 30)), Value::Bool(true));
        assert_eq!(bool_prop.value_at(Tick::from_rational(15, 1, 30)), Value::Bool(false));
        assert_eq!(bool_prop.value_at(Tick::from_rational(29, 1, 30)), Value::Bool(false), "a hold key never tweens toward the next value");

        let keyed_popup = set_layer_popup_keyframe(&picked, comp_id, null_id, dimensions_uid, 15, 1).unwrap();
        let popup_project: Project = serde_json::from_str(&keyed_popup).unwrap();
        let popup_prop = popup_project.comp(ItemId(comp_id)).unwrap()
            .layers.iter().find(|layer| layer.id == LayerId(null_id)).unwrap()
            .props.prop(&format!("@{dimensions_uid}")).unwrap();
        assert_eq!(popup_prop.keys.len(), 2);
        assert_eq!(popup_prop.keys[0].value, Value::Enum(2));
        assert_eq!(popup_prop.keys[1].value, Value::Enum(1));
        assert_eq!(popup_prop.keys[0].in_interp, Interp::Hold);
        assert_eq!(popup_prop.keys[0].out_interp, Interp::Hold);
        assert_eq!(popup_prop.keys[1].in_interp, Interp::Hold);
        assert_eq!(popup_prop.keys[1].out_interp, Interp::Hold);
        assert_eq!(popup_prop.value_at(Tick::from_rational(7, 1, 30)), Value::Enum(2));
        assert_eq!(popup_prop.value_at(Tick::from_rational(29, 1, 30)), Value::Enum(1));
        // Metadata now carries the hold keys (bool as JSON boolean, enum as index).
        let keyed_meta: serde_json::Value = serde_json::from_str(&inspect_project(&keyed_popup).unwrap()).unwrap();
        let popup_keys = &keyed_meta["compositions"][0]["layers"][1]["effects"][0]["popups"][0]["keys"];
        assert_eq!(popup_keys[0]["frame"], 0);
        assert_eq!(popup_keys[0]["value"], 2);
        assert_eq!(popup_keys[0]["inInterp"], "Hold");
        assert_eq!(popup_keys[1]["frame"], 15);
        assert_eq!(popup_keys[1]["value"], 1);
        assert_eq!(keyed_meta["compositions"][0]["layers"][1]["effects"][0]["popups"][0]["keyframes"], json!([0, 15]));

        // Negatives: static setters refuse once keys exist (a static write
        // must never silently upsert a frame-0 key); out-of-range popup
        // indices, wrong kinds, unknown uids and negative frames reject;
        // a Bezier handle on a hold-only key rejects.
        assert!(set_layer_bool_property(&keyed_bool, comp_id, shape_id, per_corner_uid, false).is_err());
        assert!(set_layer_popup_property(&keyed_popup, comp_id, null_id, dimensions_uid, 0).is_err());
        assert!(set_layer_popup_keyframe(&source, comp_id, null_id, dimensions_uid, 15, 3).is_err());
        assert!(set_layer_bool_keyframe(&source, comp_id, shape_id, star_type_uid, 15, true).is_err());
        assert!(set_layer_popup_keyframe(&source, comp_id, null_id, repeat_edge_uid, 15, 1).is_err());
        assert!(set_layer_bool_keyframe(&source, comp_id, shape_id, 999_999, 15, true).is_err());
        assert!(set_layer_bool_keyframe(&source, comp_id, shape_id, per_corner_uid, -1, true).is_err());
        let selector = format!("param:{repeat_edge_uid}");
        assert!(set_keyframe_bezier(&keyed_bool, comp_id, shape_id, &format!("param:{per_corner_uid}"), 15, "incoming", 8.0, 0.6).is_err());
        let _ = selector;

        // Removal: dropping the mid key leaves the frame-0 seed; dropping
        // the last key collapses back to the static value at that frame.
        let (without_mid, removed_mid) = remove_curve_keyframe(&keyed_bool, comp_id, shape_id, &format!("param:{per_corner_uid}"), 15).unwrap();
        assert!(removed_mid);
        let mid_project: Project = serde_json::from_str(&without_mid).unwrap();
        let mid_prop = mid_project.comp(ItemId(comp_id)).unwrap()
            .layers.iter().find(|layer| layer.id == LayerId(shape_id)).unwrap()
            .props.prop(&format!("@{per_corner_uid}")).unwrap();
        assert_eq!(mid_prop.keys.len(), 1);
        assert_eq!(mid_prop.keys[0].value, Value::Bool(true));
        let (static_json, removed_zero) = remove_curve_keyframe(&without_mid, comp_id, shape_id, &format!("param:{per_corner_uid}"), 0).unwrap();
        assert!(removed_zero);
        let static_project: Project = serde_json::from_str(&static_json).unwrap();
        let static_prop = static_project.comp(ItemId(comp_id)).unwrap()
            .layers.iter().find(|layer| layer.id == LayerId(shape_id)).unwrap()
            .props.prop(&format!("@{per_corner_uid}")).unwrap();
        assert!(static_prop.keys.is_empty());
        assert_eq!(static_prop.value, Value::Bool(true));
        let (unchanged, removed_again) = remove_curve_keyframe(&static_json, comp_id, shape_id, &format!("param:{per_corner_uid}"), 0).unwrap();
        assert!(!removed_again);
        assert_eq!(unchanged, static_json);
    }

    #[test]
    fn bool_popup_hold_keys_derive_hold_only_from_value() {
        let (source, comp_id, shape_id, null_id, (per_corner_uid, _star_type_uid, _repeat_edge_uid, dimensions_uid)) = bool_popup_project();
        // A hand-built or older file may omit the `hold_only` flag (serde
        // defaults it to false): Bool/Enum values never interpolate by
        // construction, so the bridge must still author Hold keys. This is
        // the connected-test failure (line 760): the grafted fixture omits
        // the flag, and the bool keys came back Linear instead of Hold.
        let mut project: Project = serde_json::from_str(&source).unwrap();
        project.comp_mut(ItemId(comp_id)).unwrap()
            .layers.find_mut(|layer| layer.id == LayerId(shape_id)).unwrap()
            .props.prop_mut(&format!("@{per_corner_uid}")).unwrap().hold_only = false;
        project.comp_mut(ItemId(comp_id)).unwrap()
            .layers.find_mut(|layer| layer.id == LayerId(null_id)).unwrap()
            .props.prop_mut(&format!("@{dimensions_uid}")).unwrap().hold_only = false;
        let source = project.to_file_json().unwrap();

        let keyed_bool = set_layer_bool_keyframe(&source, comp_id, shape_id, per_corner_uid, 15, false).unwrap();
        let bool_project: Project = serde_json::from_str(&keyed_bool).unwrap();
        let bool_prop = bool_project.comp(ItemId(comp_id)).unwrap()
            .layers.iter().find(|layer| layer.id == LayerId(shape_id)).unwrap()
            .props.prop(&format!("@{per_corner_uid}")).unwrap();
        assert_eq!(bool_prop.keys.len(), 2);
        assert!(bool_prop.keys.iter().all(|key| key.in_interp == Interp::Hold && key.out_interp == Interp::Hold),
            "bool keys must hold on both sides even when the file omits hold_only");
        let bool_meta: serde_json::Value = serde_json::from_str(&inspect_project(&keyed_bool).unwrap()).unwrap();
        let bool_keys = bool_meta["compositions"][0]["layers"][0]["shapeItems"].as_array().unwrap().iter()
            .find(|item| item["kind"] == "rect").unwrap()["bools"].as_array().unwrap().iter()
            .find(|param| param["name"] == "Per-Corner Roundness").unwrap()["keys"].clone();
        assert!(bool_keys.as_array().unwrap().iter().all(|key| key["inInterp"] == "Hold" && key["outInterp"] == "Hold"));

        let keyed_popup = set_layer_popup_keyframe(&source, comp_id, null_id, dimensions_uid, 15, 1).unwrap();
        let popup_project: Project = serde_json::from_str(&keyed_popup).unwrap();
        let popup_prop = popup_project.comp(ItemId(comp_id)).unwrap()
            .layers.iter().find(|layer| layer.id == LayerId(null_id)).unwrap()
            .props.prop(&format!("@{dimensions_uid}")).unwrap();
        assert_eq!(popup_prop.keys.len(), 2);
        assert!(popup_prop.keys.iter().all(|key| key.in_interp == Interp::Hold && key.out_interp == Interp::Hold),
            "popup keys must hold on both sides even when the file omits hold_only");
        let popup_meta: serde_json::Value = serde_json::from_str(&inspect_project(&keyed_popup).unwrap()).unwrap();
        let popup_keys = &popup_meta["compositions"][0]["layers"][1]["effects"][0]["popups"][0]["keys"];
        assert!(popup_keys.as_array().unwrap().iter().all(|key| key["inInterp"] == "Hold" && key["outInterp"] == "Hold"));
    }

    #[test]
    fn canonical_popup_keyframe_changes_rendered_shape() {
        use effectcraft_project::Value;

        let (source, comp_id, shape_id, _null_id, (_per_corner_uid, star_type_uid, _repeat_edge_uid, _dimensions_uid)) = bool_popup_project();
        // Star (index 0) holds until frame 15, then the shape is a polygon.
        let keyed = set_layer_popup_keyframe(&source, comp_id, shape_id, star_type_uid, 15, 1).unwrap();
        let project: Project = serde_json::from_str(&keyed).unwrap();
        let star_prop = project.comp(ItemId(comp_id)).unwrap()
            .layers.iter().find(|layer| layer.id == LayerId(shape_id)).unwrap()
            .props.prop(&format!("@{star_type_uid}")).unwrap();
        assert_eq!(star_prop.value_at(effectcraft_time::Tick::from_rational(14, 1, 30)), Value::Enum(0));
        assert_eq!(star_prop.value_at(effectcraft_time::Tick::from_rational(15, 1, 30)), Value::Enum(1));
        let frame7 = render_frame_rgba(&keyed, comp_id, 7, 160).unwrap();
        let frame14 = render_frame_rgba(&keyed, comp_id, 14, 160).unwrap();
        let frame15 = render_frame_rgba(&keyed, comp_id, 15, 160).unwrap();
        assert_eq!(frame14, frame7, "hold semantics: frame 14 must still render the star");
        assert_ne!(frame15, frame14, "the frame-15 polygon key must change the rendered shape");
        assert!(frame15[8..].chunks_exact(4).any(|pixel| pixel[0] > 200), "the polygon frame must paint red fill pixels");
    }

    #[test]
    fn canonical_param_keyframes_bezier_and_drag() {
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
        let blur_uid = metadata["compositions"][0]["layers"][1]["effects"].as_array().unwrap().iter()
            .find(|item| item["name"] == "Gaussian Blur").unwrap()["scalars"].as_array().unwrap().iter()
            .find(|param| param["name"] == "Blurriness").unwrap()["uid"].as_u64().unwrap();

        let seeded = set_layer_param_keyframe(&source, comp_id.0, null_id, blur_uid, 15, 12.0, 0.0, 0.0).unwrap();
        let seeded_project: Project = serde_json::from_str(&seeded).unwrap();
        let blur = seeded_project.comp(comp_id).unwrap().layers[1].props.prop(&format!("@{blur_uid}")).unwrap();
        assert_eq!(blur.keys.len(), 2, "first param key seeds frame 0 from the static value");
        assert_eq!(blur.keys[0].time, Tick::ZERO);
        assert_eq!(blur.keys[0].value, Value::Scalar(0.0));
        assert_eq!(blur.keys[1].value, Value::Scalar(12.0));
        let selector = format!("param:{blur_uid}");
        let bezier = set_keyframe_bezier(&seeded, comp_id.0, null_id, &selector, 15, "incoming", 8.0, 0.6).unwrap();
        let bezier_project: Project = serde_json::from_str(&bezier).unwrap();
        let bezier_blur = bezier_project.comp(comp_id).unwrap().layers[1].props.prop(&format!("@{blur_uid}")).unwrap();
        assert_eq!(bezier_blur.keys[1].in_interp, Interp::Bezier);
        assert_eq!(bezier_blur.keys[1].in_ease[0].speed, 8.0);
        assert_eq!(bezier_blur.keys[1].in_ease[0].influence, 0.6);
        let dragged = move_keyframe(&bezier, comp_id.0, null_id, &selector, 15, 24).unwrap();
        let dragged_project: Project = serde_json::from_str(&dragged).unwrap();
        let dragged_blur = dragged_project.comp(comp_id).unwrap().layers[1].props.prop(&format!("@{blur_uid}")).unwrap();
        assert_eq!(dragged_blur.keys.len(), 2);
        assert_eq!(dragged_blur.keys[1].time, Tick::from_rational(24, 1, 30));
        assert_eq!(dragged_blur.keys[1].value, Value::Scalar(12.0));
        assert_eq!(dragged_blur.keys[1].in_interp, Interp::Bezier);
        let dragged_meta = serde_json::from_str::<serde_json::Value>(&inspect_project(&dragged).unwrap()).unwrap();
        let param_keys = &dragged_meta["compositions"][0]["layers"][1]["effects"][0]["scalars"][0]["keys"];
        assert_eq!(param_keys[1]["frame"], 24);
        assert_eq!(param_keys[1]["inInterp"], "Bezier");
        assert!(set_layer_param_keyframe(&source, comp_id.0, null_id, 999_999, 15, 1.0, 0.0, 0.0).is_err());
        assert!(move_keyframe(&bezier, comp_id.0, null_id, &selector, 15, -1).is_err());
        let (without_param_mid, removed_param_mid) = remove_curve_keyframe(&dragged, comp_id.0, null_id, &selector, 24).unwrap();
        assert!(removed_param_mid);
        let param_after_mid: Project = serde_json::from_str(&without_param_mid).unwrap();
        let param_property = param_after_mid.comp(comp_id).unwrap().layers[1].props.prop(&format!("@{blur_uid}")).unwrap();
        assert_eq!(param_property.keys.len(), 1);
        assert_eq!(param_property.keys[0].time, Tick::ZERO);
        let (param_static, removed_param_zero) = remove_curve_keyframe(&without_param_mid, comp_id.0, null_id, &selector, 0).unwrap();
        assert!(removed_param_zero);
        let static_param_project: Project = serde_json::from_str(&param_static).unwrap();
        let static_blur = static_param_project.comp(comp_id).unwrap().layers[1].props.prop(&format!("@{blur_uid}")).unwrap();
        assert!(static_blur.keys.is_empty());
        assert_eq!(static_blur.value, Value::Scalar(0.0));
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
