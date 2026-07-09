//! SL Studio Log Standard formatter for Chunkup (English, structured).

use std::sync::atomic::{AtomicU32, Ordering};
use std::time::{SystemTime, UNIX_EPOCH};

static EVENT_SEQ: AtomicU32 = AtomicU32::new(1);

const PROJECT_ID: &str = "[Multi-Lang-Chunkup]";

fn timestamp_ms() -> String {
    let dur = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default();
    let secs = dur.as_secs();
    let ms = dur.subsec_millis();
    // UTC wall clock approximation for game logs (local TZ handled by JVM layer).
    let days = secs / 86400;
    let day_secs = secs % 86400;
    let h = day_secs / 3600;
    let m = (day_secs % 3600) / 60;
    let s = day_secs % 60;
    // Simple epoch day — sufficient for log correlation; JVM uses local time.
    let y = 1970 + days / 365;
    let mo = 1 + ((days % 365) / 30);
    let d = 1 + ((days % 365) % 30);
    format!(
        "{:04}-{:02}-{:02} {:02}:{:02}:{:02}.{:03}",
        y, mo, d, h, m, s, ms
    )
}

fn next_event_id() -> String {
    let seq = EVENT_SEQ.fetch_add(1, Ordering::Relaxed);
    let day = timestamp_ms();
    let date = day.split(' ').next().unwrap_or("1970-01-01").replace('-', "");
    format!("CHUP-{}-{:03}", date, seq % 1000)
}

fn emit(
    level_id: u8,
    level_name: &str,
    module: &str,
    actor: &str,
    content: &str,
    params: &str,
    optional: Option<&str>,
) {
    let ts = timestamp_ms();
    let event = next_event_id();
    let mut line = format!(
        "[{level_id}-{level_name}] [{ts}] {PROJECT_ID} [{module}] [{actor}] [{event}] | Content:[{content}] | Params:[{params}]"
    );
    if let Some(extra) = optional {
        if !extra.is_empty() {
            line.push_str(" | ");
            line.push_str(extra);
        }
    }
    log::info!("{line}");
}

pub fn info_init(module: &str, content: &str, params: &str) {
    emit(4, "INFO_INIT", module, "Service:chunkup_core", content, params, None);
}

pub fn info_start(module: &str, content: &str, params: &str) {
    emit(5, "INFO_START", module, "Service:chunkup_core", content, params, None);
}

pub fn info_progress(module: &str, content: &str, params: &str) {
    emit(6, "INFO_PROGRESS", module, "Service:chunkup_core", content, params, None);
}

pub fn info_complete(module: &str, content: &str, params: &str) {
    emit(7, "INFO_COMPLETE", module, "Service:chunkup_core", content, params, None);
}

pub fn info_status(module: &str, content: &str, params: &str) {
    emit(8, "INFO_STATUS", module, "Service:chunkup_core", content, params, None);
}

pub fn debug_func(module: &str, content: &str, params: &str) {
    emit(3, "DEBUG_FUNC", module, "Service:chunkup_core", content, params, None);
}

pub fn warn_perf(module: &str, content: &str, params: &str) {
    emit(11, "WARN_PERF", module, "Service:chunkup_core", content, params, None);
}
