import { describe, it, expect } from "vitest";
import { PriceAggregator } from "../src/services/aggregator.js";
import { PriceCache } from "../src/services/cache.js";
import { SourceMonitor } from "../src/services/sources.js";
import type { AssetQuote, PriceProvider } from "../src/types.js";

function makeQuote(symbol: string, price: number, overrides: Partial<AssetQuote> = {}): AssetQuote {
  return {
    symbol,
    name: symbol,
    category: "currency",
    currency: "TOMAN",
    unit: null,
    price,
    change: null,
    change_percent: null,
    day_high: null,
    day_low: null,
    prev_price: null,
    updated_at: new Date().toISOString(),
    source: "test",
    is_stale: false,
    ...overrides,
  };
}

class FakeProvider implements PriceProvider {
  constructor(
    readonly name: string,
    readonly priority: number,
    private readonly behavior: () => AssetQuote[],
  ) {}

  async fetchAll(): Promise<AssetQuote[]> {
    return this.behavior();
  }
}

class FailingProvider implements PriceProvider {
  constructor(
    readonly name: string,
    readonly priority: number,
  ) {}

  async fetchAll(): Promise<AssetQuote[]> {
    throw new Error("source down");
  }
}

describe("PriceAggregator (multi-source failover & merge)", () => {
  it("falls back to the next provider when the primary fails", async () => {
    const monitor = new SourceMonitor();
    const cache = new PriceCache();
    const aggregator = new PriceAggregator(
      [
        new FailingProvider("primary", 1),
        new FakeProvider("secondary", 2, () => [makeQuote("USD", 104_850)]),
      ],
      monitor,
      cache,
    );

    const result = await aggregator.pollOnce();

    expect(result.fresh).toBe(1);
    const usd = cache.get("USD", new Date(), 60_000);
    expect(usd?.price).toBe(104_850);
    expect(monitor.list().find((s) => s.name === "primary")?.status).toBe("down");
    expect(monitor.list().find((s) => s.name === "secondary")?.status).toBe("up");
  });

  it("lets the highest-priority provider win per symbol", async () => {
    const monitor = new SourceMonitor();
    const cache = new PriceCache();
    const aggregator = new PriceAggregator(
      [
        new FakeProvider("a", 1, () => [makeQuote("USD", 100, { source: "a" })]),
        new FakeProvider("b", 2, () => [makeQuote("USD", 999, { source: "b" }), makeQuote("EUR", 112_400, { source: "b" })]),
      ],
      monitor,
      cache,
    );

    await aggregator.pollOnce();

    const now = new Date();
    expect(cache.get("USD", now, 60_000)?.price).toBe(100);
    expect(cache.get("USD", now, 60_000)?.source).toBe("a");
    expect(cache.get("EUR", now, 60_000)?.source).toBe("b");
  });

  it("rejects outlier quotes and keeps the cached value", async () => {
    const monitor = new SourceMonitor();
    const cache = new PriceCache();
    cache.load([makeQuote("GOLD_18K", 6_703_000, { updated_at: new Date().toISOString() })]);

    const aggregator = new PriceAggregator(
      [new FakeProvider("a", 1, () => [makeQuote("GOLD_18K", 6_703_000 * 3)])], // +200% jump
      monitor,
      cache,
    );

    const result = await aggregator.pollOnce();
    expect(result.fresh).toBe(0);
    expect(cache.get("GOLD_18K", new Date(), 60_000)?.price).toBe(6_703_000);
  });

  it("carries forward the last good value when no provider supplies a symbol", async () => {
    const monitor = new SourceMonitor();
    const cache = new PriceCache();
    const yesterday = new Date(Date.now() - 26 * 3_600_000).toISOString();
    cache.load([makeQuote("EUR", 112_400, { updated_at: yesterday })]);

    const aggregator = new PriceAggregator(
      [new FakeProvider("a", 1, () => [makeQuote("USD", 104_850)])],
      monitor,
      cache,
    );
    await aggregator.pollOnce();

    const now = new Date();
    const eur = cache.get("EUR", now, 60_000);
    expect(eur?.price).toBe(112_400);
    expect(eur?.is_stale).toBe(true); // old timestamp -> stale
    expect(eur?.updated_at).toBe(yesterday);
  });

  it("does not consider a snapshot quote fresh when its timestamp is old", () => {
    const cache = new PriceCache();
    const old = new Date(Date.now() - 3_600_000).toISOString();
    cache.apply([makeQuote("USD", 104_850, { updated_at: old, source: "snapshot" })], new Date());

    const fresh = cache.get("USD", new Date(), 5 * 60_000);
    expect(fresh?.is_stale).toBe(true);
    expect(fresh?.updated_at).toBe(old);
  });
});
