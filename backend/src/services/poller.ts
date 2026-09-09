import type { TalayarDatabase } from "../db/database.js";
import { PriceAggregator } from "./aggregator.js";
import { PriceCache } from "./cache.js";
import { SourceMonitor } from "./sources.js";
import { HistoryService } from "./history.js";
import { upsertPrices, upsertSource } from "../db/database.js";

/**
 * Background poller: aggregates prices on a fixed interval, persists latest
 * values, records history points and mirrors provider metrics into the
 * database (foundation for a future admin dashboard).
 */
export class PricePoller {
  private timer: NodeJS.Timeout | null = null;
  private ticks = 0;

  constructor(
    private readonly aggregator: PriceAggregator,
    private readonly cache: PriceCache,
    private readonly monitor: SourceMonitor,
    private readonly history: HistoryService,
    private readonly db: TalayarDatabase,
    private readonly opts: { intervalMs: number; historyEnabled: boolean },
  ) {}

  start(): void {
    void this.tick();
    this.timer = setInterval(() => void this.tick(), this.opts.intervalMs);
    this.timer.unref?.();
  }

  stop(): void {
    if (this.timer) clearInterval(this.timer);
    this.timer = null;
  }

  async tick(): Promise<void> {
    try {
      const now = new Date();
      const result = await this.aggregator.pollOnce(now);

      if (result.fresh > 0) {
        // Persist the whole cache view (fresh + carried, with honest timestamps).
        const quotes = this.cache.all(now, Number.MAX_SAFE_INTEGER);
        upsertPrices(this.db, quotes);
        if (this.opts.historyEnabled) {
          for (const q of quotes) this.history.recordPoint(q.symbol, q.price);
        }
      }

      for (const status of this.monitor.list()) {
        upsertSource(this.db, status);
      }

      if (result.errors.length > 0 && this.ticks % 20 === 0) {
        console.warn("[poller] provider errors:", result.errors.join(" | "));
      }
      this.ticks += 1;
    } catch (err) {
      console.error("[poller] unexpected error:", err);
    }
  }
}
