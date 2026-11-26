/**
 * Outbound network safety validator for Web Search & URL Fetch tools.
 * Blocks loopback, RFC1918 private networks, CGNAT, link-local metadata endpoints,
 * and non-HTTP(S) schemes before any server-side fetch executes.
 */

export function isPrivateOrBlockedIpv4(ip: string): boolean {
  const parts = ip.split('.');
  if (parts.length !== 4) return false;
  const nums = parts.map((p) => Number(p));
  if (nums.some((n) => !Number.isInteger(n) || n < 0 || n > 255)) return false;
  const [a, b] = nums;
  if (a === 0 || a === 10 || a === 127) return true;
  if (a === 169 && b === 254) return true; // link-local & cloud metadata
  if (a === 172 && b >= 16 && b <= 31) return true;
  if (a === 192 && b === 168) return true;
  if (a === 100 && b >= 64 && b <= 127) return true; // CGNAT
  if (a >= 224) return true; // multicast / reserved
  return false;
}

export function isPrivateOrBlockedIpv6(rawIp: string): boolean {
  const ip = rawIp.toLowerCase().replace(/^\[|\]$/g, '');
  if (!ip.includes(':')) return false;
  if (ip === '::1' || ip === '::') return true;
  if (ip.startsWith('fc') || ip.startsWith('fd')) return true; // ULA fc00::/7
  if (ip.startsWith('fe8') || ip.startsWith('fe9') || ip.startsWith('fea') || ip.startsWith('feb')) {
    return true; // link-local fe80::/10
  }
  if (ip.startsWith('ff')) return true; // multicast
  const v4Mapped = ip.match(/::ffff:(\d+\.\d+\.\d+\.\d+)$/i);
  if (v4Mapped) return isPrivateOrBlockedIpv4(v4Mapped[1]);
  return false;
}

export function isBlockedIpLiteral(host: string): boolean {
  const clean = host.trim().toLowerCase().replace(/^\[|\]$/g, '');
  if (isPrivateOrBlockedIpv4(clean)) return true;
  if (isPrivateOrBlockedIpv6(clean)) return true;
  return false;
}

export function validatePublicHttpUrl(rawUrl: string): { ok: true; url: URL } | { ok: false; error: string } {
  let parsed: URL;
  try {
    parsed = new URL(rawUrl.trim());
  } catch {
    return { ok: false, error: 'Invalid URL format' };
  }

  if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
    return { ok: false, error: 'Only http:// and https:// URLs are permitted' };
  }

  const host = parsed.hostname.toLowerCase();
  if (
    !host ||
    host === 'localhost' ||
    host.endsWith('.localhost') ||
    host.endsWith('.local') ||
    host.endsWith('.internal') ||
    host === 'metadata.google.internal'
  ) {
    return { ok: false, error: 'Private, local, or metadata hostnames are blocked' };
  }

  if (isBlockedIpLiteral(host)) {
    return { ok: false, error: 'Private, loopback, or link-local IP addresses are blocked' };
  }

  return { ok: true, url: parsed };
}
