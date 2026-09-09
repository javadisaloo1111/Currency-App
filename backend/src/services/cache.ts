import type { AssetQuote } from "../types.js";
import { ASSETS } from "../../../shared/assets.mjs";

const ORDER = new Map<string, number>(ASSETS.map((a, i) => [a.symbol as string, i]));

/**
 * In-memory latest-quote cache. The last known good value for every asset is
 * always retained — when providers go down, clients keep receiving the last
 * valid price with `is_stale=true` and the original `updated_at`.
 *
 * `is_stale` is derived from the quote's age at read time, so it stays honest
 * for live providers (fresh) and snapshot bootstrapping (older) alike.
 */
export class PriceCache {
  private readonly quotes = new Map<string, AssetQuote>();
  private lastSuccessAt: string | null = null;

  /** Seed the cache (e.g. from the DB at boot). */
  load(quotes: AssetQuote[]): void {
    for (const q of quotes) this.quotes.set(q.symbol, { ...q });
  }

  /** Apply fresh provider quotes; missing symbols keep their last good value. */
  apply(fresh: AssetQuote[], now: Date): void {
    for (const q of fresh) {
      this.quotes.set(q.symbol, {
        ...q,
        // A "snapshot" quote is never considered fresher than its own timestamp.
        is_stale: false,
      });
    }
    if (fresh.length > 0) this.lastSuccessAt = now.toISOString();
  }

  private stale(q: AssetQuote, now: Date, staleAfterMs: number): boolean {
    const ts = Date.parse(q.updated_at);
    if (!Number.isFinite(ts)) return true;
    return now.getTime() - ts > staleAfterMs;
  }

  /** All quotes in registry order with `is_stale` derived from their age. */
  all(now: Date, staleAfterMs: number): AssetQuote[] {
    return [...this.quotes.values()]
      .map((q) => ({ ...q, is_stale: this.stale(q, now, staleAfterMs) }))
      .sort((a, b) => (ORDER.get(a.symbol) ?? 999) - (ORDER.get(b.symbol) ?? 999));
  }

  get(symbol: string, now: Date, staleAfterMs: number): AssetQuote | null {
    const q = this.quotes.get(symbol);
    if (!q) return null;
    return { ...q, is_stale: this.stale(q, now, staleAfterMs) };
  }

  /** The most recent provider success time (for /health). */
  get lastSuccess(): string | null {
    return this.lastSuccessAt;
  }

  /** Number of assets currently held (fresh + carried forward). */
  get size(): number {
    return this.quotes.size;
  }

  /** Previous known price for a symbol (used for outlier validation). */
  previousPrice(symbol: string): number | null {
    return this.quotes.get(symbol)?.price ?? null;
  }
}
