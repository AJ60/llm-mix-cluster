const TOKEN_KEY = 'silica.api.token.v1';

export function setApiToken(token: string): void {
  try {
    if (token.trim()) sessionStorage.setItem(TOKEN_KEY, token.trim());
  } catch {
    // storage unavailable
  }
}

export function getApiToken(): string {
  try {
    return sessionStorage.getItem(TOKEN_KEY) || '';
  } catch {
    return '';
  }
}

export function initApiTokenFromUrl(): void {
  if (typeof window === 'undefined') return;
  try {
    const params = new URLSearchParams(window.location.search);
    const token = params.get('apiToken');
    if (token) {
      setApiToken(token);
      params.delete('apiToken');
      const query = params.toString();
      window.history.replaceState({}, '', `${window.location.pathname}${query ? `?${query}` : ''}`);
    }
  } catch {
    // ignore
  }
}

export function authHeaders(): Record<string, string> {
  const token = getApiToken();
  return token ? { Authorization: `Bearer ${token}` } : {};
}

export async function apiFetch(input: string, init?: RequestInit): Promise<Response> {
  const headers = new Headers(init?.headers);
  for (const [key, value] of Object.entries(authHeaders())) headers.set(key, value);
  return fetch(input, { ...init, headers });
}
