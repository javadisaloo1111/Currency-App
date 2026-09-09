import { createRequire } from "node:module";
import { mkdirSync } from "node:fs";
import { dirname } from "node:path";
import type { AssetQuote, HistoryPoint, SourceStatus } from "../types.js";

// `node:sqlite` is experimental in Node 22 and not registered in
// `module.builtinModules`, so bundlers/test-runners (vite/vitest) fail to
// externalize it. Importing through createRequire keeps the resolution native.
import type { DatabaseSync as DatabaseSyncType } from "node:sqlite";

const { DatabaseSync } = createRequire(import.meta.url)("node:sqlite") as {
  DatabaseSync: new (path: string) => DatabaseSyncType;
};

/**
 * SQLite layer built on Node's built-in `node:sqlite` (zero native deps).
 * Schema covers: assets, prices, price_history, price_sources, price_alerts.
 */

const SCHEMA = `
CREATE TABLE IF NOT EXISTS assets (
  symbol     TEXT PRIMARY KEY,
  name       TEXT NOT NULL,
  category   TEXT NOT NULL,
  currency   TEXT NOT NULL DEFAULT 'TOMAN',
  unit       TEXT,
  updated_at TEXT
);

CREATE TABLE IF NOT EXISTS prices (
  symbol         TEXT PRIMARY KEY,
  price          REAL NOT NULL,
  change_amount  REAL,
  change_percent REAL,
  day_high       REAL,
  day_low        REAL,
  prev_price     REAL,
  source         TEXT,
  fetched_at     TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS price_history (
  symbol TEXT NOT NULL,
  ts     INTEGER NOT NULL,
  price  REAL NOT NULL,
  PRIMARY KEY (symbol, ts)
);

CREATE TABLE IF NOT EXISTS price_sources (
  name             TEXT PRIMARY KEY,
  priority         INTEGER NOT NULL DEFAULT 100,
  status           TEXT NOT NULL DEFAULT 'unknown',
  last_success_at  TEXT,
  last_error       TEXT,
  error_count      INTEGER NOT NULL DEFAULT 0,
  avg_response_ms  INTEGER
);

CREATE TABLE IF NOT EXISTS price_alerts (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  symbol       TEXT NOT NULL,
  kind         TEXT NOT NULL CHECK (kind IN ('above', 'below', 'percent')),
  threshold    REAL NOT NULL,
  created_at   TEXT NOT NULL DEFAULT (datetime('now')),
  triggered_at TEXT
);

CREATE INDEX IF NOT EXISTS idx_price_history_symbol_ts ON price_history(symbol, ts);
`;

export type TalayarDatabase = DatabaseSyncType;

export function openDatabase(path: string): TalayarDatabase {
  if (path !== ":memory:") {
    mkdirSync(dirname(path), { recursive: true });
  }
  const db = new DatabaseSync(path);
  db.exec("PRAGMA journal_mode = WAL;");
  db.exec("PRAGMA synchronous = NORMAL;");
  db.exec(SCHEMA);
  return db;
}

/** Minimal transaction helper (node:sqlite has no .transaction()). */
export function tx<T>(db: TalayarDatabase, fn: () => T): T {
  db.exec("BEGIN");
  try {
    const result = fn();
    db.exec("COMMIT");
    return result;
  } catch (err) {
    db.exec("ROLLBACK");
    throw err;
  }
}

// ---------------------------------------------------------------------------
// latest prices
// ---------------------------------------------------------------------------

export function upsertPrices(db: TalayarDatabase, quotes: AssetQuote[]): void {
  if (quotes.length === 0) return;
  tx(db, () => {
    const assetStmt = db.prepare(
      `INSERT INTO assets (symbol, name, category, currency, unit, updated_at)
       VALUES (?, ?, ?, ?, ?, ?)
       ON CONFLICT(symbol) DO UPDATE SET
         name = excluded.name, category = excluded.category, currency = excluded.currency,
         unit = excluded.unit, updated_at = excluded.updated_at`,
    );
    const priceStmt = db.prepare(
      `INSERT INTO prices (symbol, price, change_amount, change_percent, day_high, day_low, prev_price, source, fetched_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
       ON CONFLICT(symbol) DO UPDATE SET
         price = excluded.price, change_amount = excluded.change_amount, change_percent = excluded.change_percent,
         day_high = excluded.day_high, day_low = excluded.day_low, prev_price = excluded.prev_price,
         source = excluded.source, fetched_at = excluded.fetched_at`,
    );
    for (const q of quotes) {
      assetStmt.run(q.symbol, q.name, q.category, q.currency, q.unit, q.updated_at);
      priceStmt.run(
        q.symbol,
        q.price,
        q.change,
        q.change_percent,
        q.day_high,
        q.day_low,
        q.prev_price,
        q.source,
        q.updated_at,
      );
    }
  });
}

export function loadLatestPrices(db: TalayarDatabase): AssetQuote[] {
  const rows = db
    .prepare(
      `SELECT a.symbol, a.name, a.category, a.currency, a.unit,
              p.price, p.change_amount AS change, p.change_percent, p.day_high, p.day_low,
              p.prev_price, p.source, p.fetched_at AS updated_at
       FROM prices p JOIN assets a ON a.symbol = p.symbol`,
    )
    .all() as Array<Record<string, unknown>>;
  return rows.map((r) => ({
    symbol: String(r.symbol),
    name: String(r.name),
    category: String(r.category) as AssetQuote["category"],
    currency: String(r.currency),
    unit: (r.unit as string | null) ?? null,
    price: Number(r.price),
    change: r.change == null ? null : Number(r.change),
    change_percent: r.change_percent == null ? null : Number(r.change_percent),
    day_high: r.day_high == null ? null : Number(r.day_high),
    day_low: r.day_low == null ? null : Number(r.day_low),
    prev_price: r.prev_price == null ? null : Number(r.prev_price),
    updated_at: String(r.updated_at),
    source: String(r.source ?? ""),
    is_stale: true,
  }));
}

// ---------------------------------------------------------------------------
// history
// ---------------------------------------------------------------------------

export function insertHistoryPoints(db: TalayarDatabase, symbol: string, points: HistoryPoint[]): void {
  if (points.length === 0) return;
  const stmt = db.prepare(
    `INSERT INTO price_history (symbol, ts, price) VALUES (?, ?, ?)
     ON CONFLICT(symbol, ts) DO UPDATE SET price = excluded.price`,
  );
  tx(db, () => {
    for (const p of points) stmt.run(symbol, p.t, p.p);
  });
}

export function lastHistoryPoint(db: TalayarDatabase, symbol: string): HistoryPoint | null {
  const row = db
    .prepare(`SELECT ts AS t, price AS p FROM price_history WHERE symbol = ? ORDER BY ts DESC LIMIT 1`)
    .get(symbol) as { t: number; p: number } | undefined;
  return row ? { t: Number(row.t), p: Number(row.p) } : null;
}

export function historyPointsSince(db: TalayarDatabase, symbol: string, sinceMs: number): HistoryPoint[] {
  const rows = db
    .prepare(`SELECT ts AS t, price AS p FROM price_history WHERE symbol = ? AND ts >= ? ORDER BY ts ASC`)
    .all(symbol, sinceMs) as Array<{ t: number; p: number }>;
  return rows.map((r) => ({ t: Number(r.t), p: Number(r.p) }));
}

export function pruneHistoryBefore(db: TalayarDatabase, beforeMs: number): number {
  const info = db.prepare(`DELETE FROM price_history WHERE ts < ?`).run(beforeMs);
  return Number(info.changes);
}

// ---------------------------------------------------------------------------
// source metrics
// ---------------------------------------------------------------------------

export interface SourceRow {
  name: string;
  priority: number;
  status: string;
  last_success_at: string | null;
  last_error: string | null;
  error_count: number;
  avg_response_ms: number | null;
}

export function upsertSource(db: TalayarDatabase, status: SourceStatus): void {
  db.prepare(
    `INSERT INTO price_sources (name, priority, status, last_success_at, last_error, error_count, avg_response_ms)
     VALUES (?, ?, ?, ?, ?, ?, ?)
     ON CONFLICT(name) DO UPDATE SET
       priority = excluded.priority, status = excluded.status, last_success_at = excluded.last_success_at,
       last_error = excluded.last_error, error_count = excluded.error_count, avg_response_ms = excluded.avg_response_ms`,
  ).run(
    status.name,
    status.priority,
    status.status,
    status.last_success_at,
    status.last_error,
    status.error_count,
    status.avg_response_ms,
  );
}

export function listSources(db: TalayarDatabase): SourceStatus[] {
  const rows = db.prepare(`SELECT * FROM price_sources ORDER BY priority ASC`).all() as unknown as SourceRow[];
  return rows.map((r) => ({
    name: r.name,
    priority: r.priority,
    status: (r.status === "up" || r.status === "down" ? r.status : "unknown") as SourceStatus["status"],
    last_success_at: r.last_success_at ?? null,
    last_error: r.last_error ?? null,
    error_count: Number(r.error_count || 0),
    avg_response_ms: r.avg_response_ms == null ? null : Number(r.avg_response_ms),
  }));
}

// ---------------------------------------------------------------------------
// price alerts (server-side storage — the app evaluates alerts locally today;
// this table powers a future admin/remote-alert feature without schema changes)
// ---------------------------------------------------------------------------

export interface AlertRow {
  id: number;
  symbol: string;
  kind: "above" | "below" | "percent";
  threshold: number;
  created_at: string;
  triggered_at: string | null;
}

export function listAlerts(db: TalayarDatabase): AlertRow[] {
  const rows = db.prepare(`SELECT * FROM price_alerts ORDER BY id DESC`).all() as Array<Record<string, unknown>>;
  return rows.map((r) => ({
    id: Number(r.id),
    symbol: String(r.symbol),
    kind: String(r.kind) as AlertRow["kind"],
    threshold: Number(r.threshold),
    created_at: String(r.created_at),
    triggered_at: (r.triggered_at as string | null) ?? null,
  }));
}

export function createAlert(db: TalayarDatabase, symbol: string, kind: string, threshold: number): AlertRow {
  if (kind !== "above" && kind !== "below" && kind !== "percent") {
    throw new Error("invalid alert kind");
  }
  const info = db.prepare(`INSERT INTO price_alerts (symbol, kind, threshold) VALUES (?, ?, ?)`).run(
    symbol,
    kind,
    threshold,
  );
  const row = db.prepare(`SELECT * FROM price_alerts WHERE id = ?`).get(Number(info.lastInsertRowid)) as Record<
    string,
    unknown
  >;
  return {
    id: Number(row.id),
    symbol: String(row.symbol),
    kind: String(row.kind) as AlertRow["kind"],
    threshold: Number(row.threshold),
    created_at: String(row.created_at),
    triggered_at: (row.triggered_at as string | null) ?? null,
  };
}

export function deleteAlert(db: TalayarDatabase, id: number): boolean {
  const info = db.prepare(`DELETE FROM price_alerts WHERE id = ?`).run(id);
  return Number(info.changes) > 0;
}
