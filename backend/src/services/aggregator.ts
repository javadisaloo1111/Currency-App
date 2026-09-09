import type { AssetQuote, PriceProvider } from "../types.js";
import { validateQuote } from "../../../shared/quotes.mjs";
import { PriceCache } from "./cache.js";
import { SourceMonitor } from "./sources.js";

export interface PollResult {
  fresh: number;
  carried: number;
  errors: string[];
}

/**
 * Multi-source price aggregation:
 *
 *  - All providers are polled in parallel (each with its own timeout).
 *  - Quotes are merged per symbol: the highest-priority provider that
 *    returned a valid quote for a symbol wins (priority-based failover).
 *  - Quotes are validated (positive price, bounded change) against the
 *    previously cached price; suspicious values are dropped and the cached
 *    value is served instead.
 *  - Provider health is recorded for /api/v1/health & the admin dashboard.
 */
export class PriceAggregator {
  constructor(
    private readonly providers: PriceProvider[],
    private readonly monitor: SourceMonitor,
    private readonly cache: PriceCache,
  ) {
    for (const p of providers) monitor.register(p);
    providers.sort((a, b) => a.priority - b.priority);
  }

  async pollOnce(now = new Date()): Promise<PollResult> {
    const settled = await Promise.allSettled(
      this.providers.map(async (provider) => {
        const started = Date.now();
        try {
          const quotes = await provider.fetchAll();
          this.monitor.recordSuccess(provider.name, Date.now() - started, now);
          return { provider, quotes };
        } catch (err) {
          this.monitor.recordFailure(provider.name, err, now);
          throw err;
        }
      }),
    );

    // merge: priority order — first valid quote per symbol wins
    const merged = new Map<string, AssetQuote>();
    const errors: string[] = [];
    let index = 0;
    for (const result of settled) {
      const provider = this.providers[index++];
      if (result.status !== "fulfilled") {
        errors.push(`${provider.name}: ${result.reason instanceof Error ? result.reason.message : String(result.reason)}`);
        continue;
      }
      for (const q of result.value.quotes) {
        if (merged.has(q.symbol)) continue; // higher priority already supplied it
        const prev = this.cache.previousPrice(q.symbol);
        if (!validateQuote(q, prev)) continue; // suspicious quote -> keep cache
        merged.set(q.symbol, { ...q, is_stale: false });
      }
    }

    this.cache.apply([...merged.values()], now);

    const fresh = merged.size;
    const carried = Math.max(0, this.cache.size - fresh);

    return { fresh, carried, errors };
  }
}
