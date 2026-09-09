import { readFileSync, existsSync } from "node:fs";
import { join } from "node:path";
import type { AssetQuote, HistoryPoint, PriceProvider } from "../types.js";
import { RANGES } from "../../../shared/assets.mjs";
import { fetchJson } from "../http.js";

interface SnapshotEnvelope {
  updated_at?: string;
  is_stale?: boolean;
  data?: AssetQuote[];
}

/**
 * Snapshot provider — the resilient bootstrap/fallback tier.
 *
 * Serves the static GitHub-Pages snapshot produced by
 * `scripts/snapshot-publisher.mjs` (same schema as this gateway), either from a
 * local checkout of the gh-pages branch (SNAPSHOT_DIR) or a remote URL
 * (SNAPSHOT_URL). When live providers (TGJU / Nobitex) are unreachable —
 * e.g. filtered network segments — the gateway keeps serving the last
 * published values, clearly flagged via `is_stale`.
 */
export class SnapshotProvider implements PriceProvider {
  readonly name = "snapshot";
  readonly priority = 90;

  constructor(
    private readonly opts: { dir: string | null; url: string | null; timeoutMs: number },
  ) {}

  async fetchAll(): Promise<AssetQuote[]> {
    if (this.opts.url) {
      const payload = await fetchJson<SnapshotEnvelope>(this.opts.url, this.opts.timeoutMs);
      const data = Array.isArray(payload.data) ? payload.data : [];
      if (data.length === 0) throw new Error("snapshot: empty payload");
      return data.map((q) => ({ ...q, source: "snapshot", is_stale: true }));
    }
    if (this.opts.dir) {
      const path = join(this.opts.dir, "api", "v1", "market", "prices.json");
      if (!existsSync(path)) throw new Error(`snapshot: ${path} not found`);
      const payload = JSON.parse(readFileSync(path, "utf8")) as SnapshotEnvelope;
      const data = Array.isArray(payload.data) ? payload.data : [];
      if (data.length === 0) throw new Error("snapshot: empty payload");
      return data.map((q) => ({ ...q, source: "snapshot", is_stale: true }));
    }
    throw new Error("snapshot: no source configured");
  }
}

/** Read the history part of a local gh-pages snapshot (used to bootstrap the DB). */
export function readSnapshotHistory(dir: string, symbol: string): HistoryPoint[] {
  try {
    const path = join(dir, "api", "v1", "market", "history", `${symbol}.json`);
    if (!existsSync(path)) return [];
    const payload = JSON.parse(readFileSync(path, "utf8")) as {
      all?: HistoryPoint[];
      ranges?: Record<string, HistoryPoint[]>;
    };
    const points = Array.isArray(payload.all)
      ? payload.all
      : RANGES.flatMap((r) => (payload.ranges?.[r] ?? []) as HistoryPoint[]);
    return points.filter((p) => typeof p?.t === "number" && typeof p?.p === "number");
  } catch {
    return [];
  }
}
