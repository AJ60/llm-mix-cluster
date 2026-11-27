//! Real Linux & ARM64 Edge SBC hardware telemetry:
//! - `/proc/meminfo` (MemTotal / MemAvailable, ignoring swap/zram)
//! - `/sys/class/thermal/thermal_zone*/temp`
//! - CPU big-core detection via `cpuinfo_max_freq`
//! - Raspberry Pi `vcgencmd measure_temp` & `get_throttled`
//! - Real NVIDIA NVML (`nvidia-smi`) or Vulkan (`vulkaninfo`) VRAM query

use super::HostHardwareSnapshot;
use std::fs;
use std::process::Command;
use std::time::Duration;

fn read_proc_meminfo() -> (f64, f64) {
    let Ok(content) = fs::read_to_string("/proc/meminfo") else {
        return (0.0, 0.0);
    };
    let mut total_kb = 0.0f64;
    let mut avail_kb = 0.0f64;
    for line in content.lines() {
        if let Some(rest) = line.strip_prefix("MemTotal:") {
            total_kb = rest
                .split_whitespace()
                .next()
                .and_then(|s| s.parse::<f64>().ok())
                .unwrap_or(0.0);
        } else if let Some(rest) = line.strip_prefix("MemAvailable:") {
            avail_kb = rest
                .split_whitespace()
                .next()
                .and_then(|s| s.parse::<f64>().ok())
                .unwrap_or(0.0);
        }
    }
    (total_kb / 1024.0, avail_kb / 1024.0)
}

fn read_sys_thermal_c() -> f64 {
    let mut max_c = 0.0f64;
    if let Ok(entries) = fs::read_dir("/sys/class/thermal") {
        for entry in entries.flatten() {
            let name = entry.file_name();
            if !name.to_string_lossy().starts_with("thermal_zone") {
                continue;
            }
            let temp_path = entry.path().join("temp");
            if let Ok(raw) = fs::read_to_string(temp_path) {
                if let Ok(milli_c) = raw.trim().parse::<f64>() {
                    let c = if milli_c > 200.0 { milli_c / 1000.0 } else { milli_c };
                    if c > 15.0 && c < 120.0 && c > max_c {
                        max_c = c;
                    }
                }
            }
        }
    }
    max_c
}

fn detect_big_core_count(total_cores: u32) -> u32 {
    let mut freqs = Vec::new();
    for i in 0..total_cores {
        let path = format!("/sys/devices/system/cpu/cpu{}/cpufreq/cpuinfo_max_freq", i);
        if let Ok(raw) = fs::read_to_string(path) {
            if let Ok(khz) = raw.trim().parse::<u64>() {
                freqs.push(khz);
            }
        }
    }
    if freqs.is_empty() {
        return (total_cores / 2).max(2).min(total_cores);
    }
    let max_freq = *freqs.iter().max().unwrap_or(&0);
    let threshold = (max_freq as f64 * 0.85) as u64;
    let big = freqs.iter().filter(|&&f| f >= threshold).count() as u32;
    big.max(1)
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
    let name = parts[0].to_string();
    let total_mb = parts[1].parse::<f64>().ok()?;
    let free_mb = parts[2].parse::<f64>().ok()?;
    let temp_c = parts[3].parse::<f64>().unwrap_or(0.0);
    let util_pct = parts[4].parse::<f64>().unwrap_or(0.0);
    Some((name, total_mb, free_mb, temp_c, util_pct))
}

fn probe_rpi_vcgencmd() -> (bool, Option<f64>, Option<String>) {
    let model = fs::read_to_string("/proc/device-tree/model").unwrap_or_default();
    let is_pi = model.to_ascii_lowercase().contains("raspberry pi");
    if !is_pi {
        return (false, None, None);
    }
    let temp = Command::new("vcgencmd")
        .arg("measure_temp")
        .output()
        .ok()
        .and_then(|o| {
            let s = String::from_utf8_lossy(&o.stdout);
            s.split('=').nth(1)?.split('\'').next()?.trim().parse::<f64>().ok()
        });
    let throttled = Command::new("vcgencmd")
        .arg("get_throttled")
        .output()
        .ok()
        .map(|o| String::from_utf8_lossy(&o.stdout).trim().to_string());
    (true, temp, throttled)
}


fn query_linux_cpu_usage_pct() -> f64 {
    fn sample() -> Option<(u64, u64)> {
        let content = fs::read_to_string("/proc/stat").ok()?;
        let line = content.lines().find(|line| line.starts_with("cpu "))?;
        let values: Vec<u64> = line[4..]
            .split_whitespace()
            .filter_map(|v| v.parse::<u64>().ok())
            .collect();
        if values.len() < 5 {
            return None;
        }
        let total = values.iter().sum();
        let idle = values[3] + values[4];
        Some((total, idle))
    }
    let (Some((total_a, idle_a)), Some((total_b, idle_b))) = (sample(), {
        std::thread::sleep(Duration::from_millis(120));
        sample()
    }) else {
        return 0.0;
    };
    let total = total_b.saturating_sub(total_a);
    let idle = idle_b.saturating_sub(idle_a);
    if total == 0 {
        return 0.0;
    }
    ((1.0 - idle as f64 / total as f64) * 100.0).clamp(0.0, 100.0)
}

fn query_linux_storage_mb() -> (f64, f64) {
    let out = Command::new("df").args(["-kP", "."]).output();
    let Ok(out) = out else {
        return (0.0, 0.0);
    };
    if !out.status.success() {
        return (0.0, 0.0);
    }
    let line = String::from_utf8_lossy(&out.stdout);
    let Some(fields) = line.lines().nth(1) else {
        return (0.0, 0.0);
    };
    let parts: Vec<&str> = fields.split_whitespace().collect();
    if parts.len() < 4 {
        return (0.0, 0.0);
    }
    let total_kb = parts[1].parse::<f64>().unwrap_or(0.0);
    let free_kb = parts[3].parse::<f64>().unwrap_or(0.0);
    (total_kb / 1024.0, free_kb / 1024.0)
}

pub fn probe_linux_hardware() -> HostHardwareSnapshot {
    let (total_ram_mb, available_ram_mb) = read_proc_meminfo();
    let cpu_cores = std::thread::available_parallelism()
        .map(|n| n.get() as u32)
        .unwrap_or(4);
    let big_cores = detect_big_core_count(cpu_cores);
    let (is_pi, pi_temp, pi_throttled) = probe_rpi_vcgencmd();
    let cpu_temp_c = pi_temp.unwrap_or_else(read_sys_thermal_c);
    let cpu_usage_pct = query_linux_cpu_usage_pct();
    let (storage_total_mb, storage_free_mb) = query_linux_storage_mb();
    let hostname = fs::read_to_string("/etc/hostname")
        .unwrap_or_else(|_| "linux-node".to_string())
        .trim()
        .to_string();

    let (gpu_name, vram_total_mb, vram_free_mb, gpu_temp_c, gpu_usage_pct) =
        match probe_nvidia_gpu() {
            Some((name, tot, free, temp, util)) => (Some(name), tot, free, temp, util),
            None => (None, 0.0, 0.0, 0.0, 0.0),
        };

    HostHardwareSnapshot {
        os_name: "linux".to_string(),
        arch: std::env::consts::ARCH.to_string(),
        hostname,
        total_ram_mb,
        available_ram_mb,
        vram_total_mb,
        vram_free_mb,
        gpu_name,
        cpu_cores,
        big_cores,
        cpu_temp_c,
        gpu_temp_c,
        cpu_usage_pct,
        gpu_usage_pct,
        storage_total_mb,
        storage_free_mb,
        is_raspberry_pi: is_pi,
        rpi_throttled_hex: pi_throttled,
    }
}
