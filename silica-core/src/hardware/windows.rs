//! Real Windows hardware telemetry & Windows Job Object child-process lifecycle guard.
//! Uses `GlobalMemoryStatusEx` for real RAM and `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE`
//! so child `llama-server` / `rpc-server` processes never survive crashes or exits.

use super::HostHardwareSnapshot;
use std::mem::{size_of, zeroed};
use std::os::windows::io::AsRawHandle;
use std::process::{Child, Command};
use windows_sys::Win32::Foundation::{CloseHandle, HANDLE};
use windows_sys::Win32::System::JobObjects::{
    AssignProcessToJobObject, CreateJobObjectW, JobObjectExtendedLimitInformation,
    SetInformationJobObject, JOBOBJECT_EXTENDED_LIMIT_INFORMATION,
    JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE,
};
use windows_sys::Win32::System::SystemInformation::{GlobalMemoryStatusEx, MEMORYSTATUSEX};

pub struct WindowsJobGuard {
    handle: HANDLE,
}

unsafe impl Send for WindowsJobGuard {}
unsafe impl Sync for WindowsJobGuard {}

impl WindowsJobGuard {
    pub fn new() -> Result<Self, String> {
        unsafe {
            let handle = CreateJobObjectW(std::ptr::null(), std::ptr::null());
            if handle.is_null() {
                return Err("CreateJobObjectW failed".to_string());
            }
            let mut info: JOBOBJECT_EXTENDED_LIMIT_INFORMATION = zeroed();
            info.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
            let ok = SetInformationJobObject(
                handle,
                JobObjectExtendedLimitInformation,
                &info as *const _ as *const _,
                size_of::<JOBOBJECT_EXTENDED_LIMIT_INFORMATION>() as u32,
            );
            if ok == 0 {
                CloseHandle(handle);
                return Err("SetInformationJobObject(KILL_ON_JOB_CLOSE) failed".to_string());
            }
            Ok(Self { handle })
        }
    }

    pub fn assign_child(&self, child: &Child) -> Result<(), String> {
        unsafe {
            let proc_handle = child.as_raw_handle() as HANDLE;
            let ok = AssignProcessToJobObject(self.handle, proc_handle);
            if ok == 0 {
                Err("AssignProcessToJobObject failed".to_string())
            } else {
                Ok(())
            }
        }
    }
}

impl Drop for WindowsJobGuard {
    fn drop(&mut self) {
        unsafe {
            if !self.handle.is_null() {
                CloseHandle(self.handle);
            }
        }
    }
}

fn query_windows_ram_mb() -> (f64, f64) {
    unsafe {
        let mut status: MEMORYSTATUSEX = zeroed();
        status.dwLength = size_of::<MEMORYSTATUSEX>() as u32;
        if GlobalMemoryStatusEx(&mut status) != 0 {
            let total_mb = status.ullTotalPhys as f64 / (1024.0 * 1024.0);
            let avail_mb = status.ullAvailPhys as f64 / (1024.0 * 1024.0);
            (total_mb, avail_mb)
        } else {
            (0.0, 0.0)
        }
    }
}

fn probe_nvidia_gpu() -> Option<(String, f64, f64, f64, f64)> {
    let out = Command::new("nvidia-smi")
        .args([
            "--query-gpu=name,memory.total,memory.free,temperature.gpu,utilization.gpu",
            "--format=csv,noheader,nounits",
        ])
        .output()
        .ok()?;
    if !out.status.success() {
        return None;
    }
    let line = String::from_utf8_lossy(&out.stdout).lines().next()?.trim().to_string();
    let parts: Vec<&str> = line.split(',').map(|s| s.trim()).collect();
    if parts.len() < 5 {
        return None;
    }
    Some((
        parts[0].to_string(),
        parts[1].parse::<f64>().ok()?,
        parts[2].parse::<f64>().ok()?,
        parts[3].parse::<f64>().unwrap_or(0.0),
        parts[4].parse::<f64>().unwrap_or(0.0),
    ))
}

pub fn probe_windows_hardware() -> HostHardwareSnapshot {
    let (total_ram_mb, available_ram_mb) = query_windows_ram_mb();
    let cpu_cores = std::thread::available_parallelism()
        .map(|n| n.get() as u32)
        .unwrap_or(4);
    let hostname = std::env::var("COMPUTERNAME").unwrap_or_else(|_| "windows-node".to_string());
    let (gpu_name, vram_total_mb, vram_free_mb, gpu_temp_c, gpu_usage_pct) =
        match probe_nvidia_gpu() {
            Some((name, tot, free, temp, util)) => (Some(name), tot, free, temp, util),
            None => (None, 0.0, 0.0, 0.0, 0.0),
        };

    HostHardwareSnapshot {
        os_name: "windows".to_string(),
        arch: std::env::consts::ARCH.to_string(),
        hostname,
        total_ram_mb,
        available_ram_mb,
        vram_total_mb,
        vram_free_mb,
        gpu_name,
        cpu_cores,
        big_cores: (cpu_cores / 2).max(2),
        cpu_temp_c: 0.0,
        gpu_temp_c,
        cpu_usage_pct: 0.0,
        gpu_usage_pct,
        is_raspberry_pi: false,
        rpi_throttled_hex: None,
    }
}
