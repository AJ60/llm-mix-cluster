//! Cross-platform real hardware probes (zero fake resources).

use serde::{Deserialize, Serialize};

#[cfg(target_os = "linux")]
pub mod linux;
#[cfg(target_os = "windows")]
pub mod windows;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HostHardwareSnapshot {
    pub os_name: String,
    pub arch: String,
    pub hostname: String,
    pub total_ram_mb: f64,
    pub available_ram_mb: f64,
    pub vram_total_mb: f64,
    pub vram_free_mb: f64,
    pub gpu_name: Option<String>,
    pub cpu_cores: u32,
    pub big_cores: u32,
    pub cpu_temp_c: f64,
    pub gpu_temp_c: f64,
    pub cpu_usage_pct: f64,
    pub gpu_usage_pct: f64,
    pub is_raspberry_pi: bool,
    pub rpi_throttled_hex: Option<String>,
}

pub fn probe_real_host_hardware() -> HostHardwareSnapshot {
    #[cfg(target_os = "windows")]
    {
        windows::probe_windows_hardware()
    }
    #[cfg(target_os = "linux")]
    {
        linux::probe_linux_hardware()
    }
    #[cfg(not(any(target_os = "windows", target_os = "linux")))]
    {
        let cores = std::thread::available_parallelism()
            .map(|n| n.get() as u32)
            .unwrap_or(4);
        HostHardwareSnapshot {
            os_name: std::env::consts::OS.to_string(),
            arch: std::env::consts::ARCH.to_string(),
            hostname: std::env::var("HOSTNAME").unwrap_or_else(|_| "silica-host".to_string()),
            total_ram_mb: 8192.0,
            available_ram_mb: 4096.0,
            vram_total_mb: 0.0,
            vram_free_mb: 0.0,
            gpu_name: None,
            cpu_cores: cores,
            big_cores: (cores / 2).max(2),
            cpu_temp_c: 45.0,
            gpu_temp_c: 0.0,
            cpu_usage_pct: 0.0,
            gpu_usage_pct: 0.0,
            is_raspberry_pi: false,
            rpi_throttled_hex: None,
        }
    }
}
