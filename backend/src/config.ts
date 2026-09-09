export interface AppConfig {
  host: string;
  port: number;
  pollIntervalMs: number;
  staleAfterMs: number;
  dbPath: string;
  tgjuHosts: string[];
  nobitexEnabled: boolean;
  snapshotDir: string | null;
  snapshotUrl: string | null;
  snapshotImportHistory: boolean;
  rateLimitMax: number;
  rateLimitWindowMs: number;
  fetchTimeoutMs: number;
}

function num(value: string | undefined, fallback: number): number {
  const n = Number(value);
  return Number.isFinite(n) && n > 0 ? n : fallback;
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): AppConfig {
  return {
    host: env.HOST || "0.0.0.0",
    port: num(env.PORT, 8080),
    pollIntervalMs: num(env.POLL_INTERVAL_MS, 15_000),
    staleAfterMs: num(env.STALE_AFTER_MS, 5 * 60_000),
    dbPath: env.DB_PATH || "data/talayar.db",
    tgjuHosts: (env.TGJU_HOSTS || "https://call5.tgju.org,https://call4.tgju.org,https://call2.tgju.org,https://call.tgju.org")
      .split(",")
      .map((h) => h.trim())
      .filter(Boolean),
    nobitexEnabled: (env.NOBITEX_ENABLED || "true") !== "false",
    snapshotDir: env.SNAPSHOT_DIR?.trim() || null,
    snapshotUrl: env.SNAPSHOT_URL?.trim() || null,
    snapshotImportHistory: (env.SNAPSHOT_IMPORT_HISTORY || "true") !== "false",
    rateLimitMax: num(env.RATE_LIMIT_MAX, 120),
    rateLimitWindowMs: num(env.RATE_LIMIT_WINDOW_MS, 60_000),
    fetchTimeoutMs: num(env.FETCH_TIMEOUT_MS, 12_000),
  };
}
