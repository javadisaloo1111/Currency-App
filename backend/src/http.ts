/** Tiny fetch helper with timeout + JSON parsing (no dependencies). */
export async function fetchJson<T = unknown>(url: string, timeoutMs = 12_000, init: RequestInit = {}): Promise<T> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const res = await fetch(url, {
      ...init,
      signal: controller.signal,
      headers: {
        "user-agent": "Talayar-Price-Gateway/1.0 (+https://github.com/javadisaloo1111/Currency-App)",
        ...(init.body ? { "content-type": "application/json" } : {}),
        ...(init.headers || {}),
      },
    });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    return (await res.json()) as T;
  } finally {
    clearTimeout(timer);
  }
}
