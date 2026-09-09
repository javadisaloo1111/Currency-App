import type { TalayarDatabase } from "../db/database.js";
import { RANGES, rangePoints } from "../../../shared/assets.mjs";
import type { HistoryPoint } from "../types.js";
import { historyPointsSince, insertHistoryPoints } from "../db/database.js";

/**
 * Price history service: persists points in SQLite (price_history) and serves
 * bucketed/downsampled series for each chart range.
 */
export class HistoryService {
  constructor(private readonly db: TalayarDatabase) {}

  /** Insert a point if the price changed or the last point is older than `minGapMs`. */
  recordPoint(symbol: string, price: number, now = Date.now(), minGapMs = 60_000): boolean {
    const last = this.lastPoint(symbol);
    if (last && last.p === price && now - last.t < minGapMs) return false;
    this.db
      .prepare(
        `INSERT INTO price_history (symbol, ts, price) VALUES (?, ?, ?)
         ON CONFLICT(symbol, ts) DO UPDATE SET price = excluded.price`,
      )
      .run(symbol, now, price);
    return true;
  }

  lastPoint(symbol: string): HistoryPoint | null {
    const row = this.db
      .prepare(`SELECT ts AS t, price AS p FROM price_history WHERE symbol = ? ORDER BY ts DESC LIMIT 1`)
      .get(symbol) as { t: number; p: number } | undefined;
    return row ? { t: Number(row.t), p: Number(row.p) } : null;
  }

  /** Points for one range, thinned to the shared per-range cap. */
  range(symbol: string, range: string, now = Date.now()): HistoryPoint[] {
    const windowMs = RANGE_WINDOWS.get(range) ?? RANGE_WINDOWS.get("1D")!;
    const since = now - windowMs;
    const points = historyPointsSince(this.db, symbol, since);
    return rangePoints(points, range, now);
  }

  /** All chart ranges for a symbol (used by /market/history/{symbol}). */
  allRanges(symbol: string, now = Date.now()): Record<string, HistoryPoint[]> {
    const out: Record<string, HistoryPoint[]> = {};
    for (const range of RANGES) out[range] = this.range(symbol, range, now);
    return out;
  }

  /** Import pre-existing points (e.g. from the static snapshot) — idempotent. */
  importPoints(symbol: string, points: HistoryPoint[]): number {
    if (points.length === 0) return 0;
    insertHistoryPoints(this.db, symbol, points);
    return points.length;
  }

  prune(days = 400): number {
    const info = this.db.prepare(`DELETE FROM price_history WHERE ts < ?`).run(Date.now() - days * 86_400_000);
    return Number(info.changes);
  }
}

const RANGE_WINDOWS = new Map<string, number>([
  ["1H", 1 * 3_600_000],
  ["6H", 6 * 3_600_000],
  ["1D", 24 * 3_600_000],
  ["1W", 7 * 24 * 3_600_000],
  ["1M", 30 * 24 * 3_600_000],
  ["3M", 90 * 24 * 3_600_000],
  ["1Y", 365 * 24 * 3_600_000],
]);
