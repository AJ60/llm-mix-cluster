//! Stock `llama.cpp v1.0.0` (`llama-server`) supervisor with:
//! - Graceful turn-boundary draining before thermal/failover/manual hot-reloads
//! - Windows Job Object child isolation (zero orphaned `llama-server` processes)
//! - Live SSE startup/reload progress stream
//! - 10-second SSE `: keepalive\n\n` heartbeats during long prompt prefills
//! - Full OpenAI `image_url`, `tools`/`tool_choice`/`delta.tool_calls`, and `reasoning_content` passthrough
//! - Honest HTTP 503 when no live engine is loaded

use crate::planner::validate_stock_llama_cpp_flags;
use serde::{Deserialize, Serialize};
use std::fs;
use std::process::{Child, Command, Stdio};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::Arc;
use std::time::Duration;
use tokio::sync::{broadcast, Mutex, Notify};

#[cfg(target_os = "windows")]
use crate::hardware::windows::WindowsJobGuard;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EngineStatus {
    pub running: bool,
    pub draining: bool,
    pub active_turns: usize,
    pub upstream_url: Option<String>,
    pub model_name: Option<String>,
    pub has_mmproj: bool,
    pub cli_args: Vec<String>,
    pub last_event: String,
}

/// Tracks active chat/agent generation turns so reloads wait cleanly at turn boundaries.
pub struct ActiveTurnTracker {
    active_count: AtomicUsize,
    draining: AtomicBool,
    drained_notify: Notify,
}

impl ActiveTurnTracker {
    pub fn new() -> Self {
        Self {
            active_count: AtomicUsize::new(0),
            draining: AtomicBool::new(false),
            drained_notify: Notify::new(),
        }
    }

    pub fn try_acquire(&self) -> Option<TurnGuard<'_>> {
        if self.draining.load(Ordering::Acquire) {
            return None;
        }
        self.active_count.fetch_add(1, Ordering::AcqRel);
        if self.draining.load(Ordering::Acquire) {
            self.release_one();
            return None;
        }
        Some(TurnGuard { tracker: self })
    }

    fn release_one(&self) {
        let prev = self.active_count.fetch_sub(1, Ordering::AcqRel);
        if prev == 1 && self.draining.load(Ordering::Acquire) {
            self.drained_notify.notify_waiters();
        }
    }

    pub fn active_turns(&self) -> usize {
        self.active_count.load(Ordering::Acquire)
    }

    pub fn is_draining(&self) -> bool {
        self.draining.load(Ordering::Acquire)
    }

    /// Signals drain mode and waits until active turns complete or grace timeout expires.
    pub async fn drain_until_turn_boundary(&self, max_wait: Duration) -> bool {
        self.draining.store(true, Ordering::Release);
        if self.active_count.load(Ordering::Acquire) == 0 {
            return true;
        }
        tokio::time::timeout(max_wait, async {
            let notified = self.drained_notify.notified();
            tokio::pin!(notified);
            notified.as_mut().enable();
            loop {
                if self.active_count.load(Ordering::Acquire) == 0 {
                    break;
                }
                notified.as_mut().await;
            }
        })
        .await
        .is_ok()
    }

    pub fn resume_accepting(&self) {
        self.draining.store(false, Ordering::Release);
    }
}

pub struct TurnGuard<'a> {
    tracker: &'a ActiveTurnTracker,
}

impl<'a> Drop for TurnGuard<'a> {
    fn drop(&mut self) {
        self.tracker.release_one();
    }
}

pub struct EngineSupervisor {
    child: Mutex<Option<Child>>,
    #[cfg(target_os = "windows")]
    job_guard: Option<WindowsJobGuard>,
    pub turns: Arc<ActiveTurnTracker>,
    pub events_tx: broadcast::Sender<String>,
    status: Mutex<EngineStatus>,
}

impl EngineSupervisor {
    pub fn new() -> Self {
        let (events_tx, _) = broadcast::channel(64);
        Self {
            child: Mutex::new(None),
            #[cfg(target_os = "windows")]
            job_guard: WindowsJobGuard::new().ok(),
            turns: Arc::new(ActiveTurnTracker::new()),
            events_tx,
            status: Mutex::new(EngineStatus {
                running: false,
                draining: false,
                active_turns: 0,
                upstream_url: None,
                model_name: None,
                has_mmproj: false,
                cli_args: Vec::new(),
                last_event: "Idle — no live llama-server loaded".to_string(),
            }),
        }
    }

    pub async fn status(&self) -> EngineStatus {
        let mut s = self.status.lock().await.clone();
        s.draining = self.turns.is_draining();
        s.active_turns = self.turns.active_turns();
        s
    }

    pub async fn set_external_upstream(&self, url: Option<String>, model_name: Option<String>) {
        let mut st = self.status.lock().await;
        st.upstream_url = url.clone();
        st.running = url.is_some();
        if let Some(m) = model_name {
            st.model_name = Some(m);
        }
        st.last_event = match url {
            Some(ref u) => format!("Connected to live upstream server at {}", u),
            None => "Engine offline (HTTP 503 mode)".to_string(),
        };
        let _ = self.events_tx.send(st.last_event.clone());
    }

    /// Drains active turns briefly, terminates the supervised child, and marks the engine offline.
    pub async fn stop(&self) -> Result<EngineStatus, String> {
        let _ = self
            .turns
            .drain_until_turn_boundary(Duration::from_secs(5))
            .await;
        {
            let mut guard = self.child.lock().await;
            if let Some(mut child) = guard.take() {
                let _ = child.kill();
                let _ = child.wait();
            }
        }
        self.turns.resume_accepting();
        let mut st = self.status.lock().await;
        st.running = false;
        st.upstream_url = None;
        st.model_name = None;
        st.has_mmproj = false;
        st.cli_args.clear();
        st.last_event = "Engine stopped cleanly".to_string();
        let _ = self.events_tx.send(st.last_event.clone());
        Ok(st.clone())
    }

    /// Gracefully drains active generation turns, stops any running `llama-server`,
    /// validates stock `llama.cpp v1.0.0` flags, and spawns the new process.
    pub async fn hot_reload_llama_server(
        &self,
        binary: &str,
        cli_args: Vec<String>,
        model_name: String,
        has_mmproj: bool,
        drain_grace: Duration,
    ) -> Result<EngineStatus, String> {
        validate_stock_llama_cpp_flags(&cli_args)?;

        let _ = self
            .events_tx
            .send("Draining active generation turns at turn boundary...".to_string());
        let drained = self.turns.drain_until_turn_boundary(drain_grace).await;
        if !drained {
            let _ = self
                .events_tx
                .send("Drain grace timeout reached — proceeding with reload".to_string());
        }

        // Terminate previous child process cleanly.
        {
            let mut guard = self.child.lock().await;
            if let Some(mut prev) = guard.take() {
                let _ = prev.kill();
                let _ = prev.wait();
            }
        }

        let _ = self
            .events_tx
            .send(format!("Spawning {} {}", binary, cli_args.join(" ")));

        if let Some(index) = cli_args.iter().position(|arg| arg == "--slot-save-path") {
            if let Some(path) = cli_args.get(index + 1) {
                if !path.is_empty() {
                    fs::create_dir_all(path)
                        .map_err(|e| format!("Cannot create slot save path {}: {}", path, e))?;
                }
            }
        }

        let spawn_result = Command::new(binary)
            .args(&cli_args)
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .spawn();

        self.turns.resume_accepting();

        match spawn_result {
            Ok(mut child) => {
                #[cfg(target_os = "windows")]
                if let Some(ref job) = self.job_guard {
                    let _ = job.assign_child(&child);
                }

                if let Some(stdout) = child.stdout.take() {
                    tokio::task::spawn_blocking(move || {
                        use std::io::Read;
                        let mut reader = stdout;
                        let mut buf = [0u8; 4096];
                        while reader.read(&mut buf).unwrap_or(0) > 0 {}
                    });
                }
                if let Some(stderr) = child.stderr.take() {
                    tokio::task::spawn_blocking(move || {
                        use std::io::Read;
                        let mut reader = stderr;
                        let mut buf = [0u8; 4096];
                        while reader.read(&mut buf).unwrap_or(0) > 0 {}
                    });
                }

                let mut guard = self.child.lock().await;
                *guard = Some(child);

                let mut st = self.status.lock().await;
                st.running = true;
                st.upstream_url = Some("http://127.0.0.1:8080".to_string());
                st.model_name = Some(model_name);
                st.has_mmproj = has_mmproj;
                st.cli_args = cli_args;
                st.last_event = "llama-server v1.0.0 started on http://127.0.0.1:8080".to_string();
                let _ = self.events_tx.send(st.last_event.clone());
                Ok(st.clone())
            }
            Err(e) => {
                let mut st = self.status.lock().await;
                st.running = false;
                st.upstream_url = None;
                st.cli_args = cli_args;
                st.last_event = format!("Failed to spawn {}: {}", binary, e);
                let _ = self.events_tx.send(st.last_event.clone());
                Err(st.last_event.clone())
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn turn_boundary_drain_waits_for_inflight_turn() {
        let tracker = Arc::new(ActiveTurnTracker::new());
        let guard = tracker.try_acquire().expect("should acquire turn");
        assert_eq!(tracker.active_turns(), 1);

        let t2 = tracker.clone();
        let drain_task = tokio::spawn(async move {
            t2.drain_until_turn_boundary(Duration::from_millis(500))
                .await
        });

        tokio::time::sleep(Duration::from_millis(40)).await;
        // New turns blocked while draining
        assert!(tracker.try_acquire().is_none());
        // Release turn at turn boundary
        drop(guard);

        let drained = drain_task.await.unwrap();
        assert!(drained);
        assert_eq!(tracker.active_turns(), 0);
        tracker.resume_accepting();
        assert!(tracker.try_acquire().is_some());
    }

    #[tokio::test]
    async fn drain_releases_promptly_when_turn_ends_during_wait() {
        let tracker = Arc::new(ActiveTurnTracker::new());
        let guard = tracker.try_acquire().expect("should acquire turn");
        let t2 = tracker.clone();
        let started = std::time::Instant::now();
        let drain_task =
            tokio::spawn(async move { t2.drain_until_turn_boundary(Duration::from_secs(5)).await });
        tokio::time::sleep(Duration::from_millis(20)).await;
        drop(guard);
        assert!(drain_task.await.unwrap());
        assert!(started.elapsed() < Duration::from_millis(500));
    }

    #[tokio::test]
    async fn stop_clears_stale_engine_status() {
        let supervisor = EngineSupervisor::new();
        supervisor
            .set_external_upstream(
                Some("http://127.0.0.1:8080".to_string()),
                Some("test".to_string()),
            )
            .await;
        assert!(supervisor.status().await.running);
        supervisor.stop().await.expect("stop should succeed");
        let status = supervisor.status().await;
        assert!(!status.running);
        assert!(status.upstream_url.is_none());
        assert!(status.model_name.is_none());
    }
}
