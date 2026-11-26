//! Optional Web Search & URL Fetch tools with strict outbound network safety validation
//! (blocks loopback, RFC1918 private networks, link-local metadata IPs, and non-HTTP(S) schemes).

use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, ToSocketAddrs};
use url::Url;

pub fn is_blocked_ip(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(v4) => is_blocked_v4(v4),
        IpAddr::V6(v6) => is_blocked_v6(v6),
    }
}

fn is_blocked_v4(ip: Ipv4Addr) -> bool {
    let o = ip.octets();
    ip.is_loopback()
        || ip.is_private()
        || ip.is_link_local()
        || ip.is_broadcast()
        || ip.is_unspecified()
        || ip.is_multicast()
        || (o[0] == 100 && (o[1] & 0b1100_0000) == 64) // CGNAT 100.64.0.0/10
        || (o[0] == 169 && o[1] == 254)
}

fn is_blocked_v6(ip: Ipv6Addr) -> bool {
    if let Some(v4) = ip.to_ipv4_mapped() {
        return is_blocked_v4(v4);
    }
    let seg0 = ip.segments()[0];
    ip.is_loopback()
        || ip.is_unspecified()
        || ip.is_multicast()
        || (seg0 & 0xfe00) == 0xfc00 // unique local fc00::/7
        || (seg0 & 0xffc0) == 0xfe80 // link-local fe80::/10
}

pub fn validate_public_http_url(raw: &str) -> Result<Url, String> {
    let parsed = Url::parse(raw.trim()).map_err(|_| "Invalid URL format".to_string())?;
    if !matches!(parsed.scheme(), "http" | "https") {
        return Err("Only http:// and https:// URLs are permitted".to_string());
    }
    let host = parsed
        .host_str()
        .ok_or_else(|| "URL must contain a valid hostname".to_string())?;
    let lower = host.to_ascii_lowercase();
    if lower == "localhost"
        || lower.ends_with(".localhost")
        || lower.ends_with(".local")
        || lower.ends_with(".internal")
    {
        return Err("Private or loopback hostnames are blocked".to_string());
    }

    if let Ok(ip) = host.trim_matches(|c| c == '[' || c == ']').parse::<IpAddr>() {
        if is_blocked_ip(ip) {
            return Err("Private, loopback, or link-local IP addresses are blocked".to_string());
        }
        return Ok(parsed);
    }

    // DNS check: reject if any resolved address falls inside a blocked range.
    let port = parsed.port_or_known_default().unwrap_or(443);
    if let Ok(addrs) = (host, port).to_socket_addrs() {
        for addr in addrs {
            if is_blocked_ip(addr.ip()) {
                return Err("Hostname resolves to a blocked private/loopback address".to_string());
            }
        }
    }

    Ok(parsed)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn blocks_private_loopback_and_metadata_urls() {
        assert!(validate_public_http_url("http://127.0.0.1:8080/secret").is_err());
        assert!(validate_public_http_url("http://169.254.169.254/latest/meta-data/").is_err());
        assert!(validate_public_http_url("http://192.168.1.1/admin").is_err());
        assert!(validate_public_http_url("http://10.0.0.5/").is_err());
        assert!(validate_public_http_url("http://localhost:3000/").is_err());
        assert!(validate_public_http_url("file:///etc/passwd").is_err());
        assert!(validate_public_http_url("https://93.184.216.34/docs").is_ok());
    }
}
