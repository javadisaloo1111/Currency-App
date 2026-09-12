import { describe, it, expect } from "vitest";
import { readFileSync } from "node:fs";
import { mapTgjuCurrent } from "../src/providers/tgju.js";

const payload = JSON.parse(
  readFileSync(new URL("../fixtures/tgju-ajax.sample.json", import.meta.url), "utf8"),
) as { current: Record<string, unknown> };

const quotes = mapTgjuCurrent(payload.current);
const bySymbol = new Map(quotes.map((q) => [q.symbol, q]));

describe("TGJU payload mapping", () => {
  it("maps every healthy registry entry (broken/absent keys are skipped)", () => {
    // 16 TGJU-mapped assets; CHF has a broken value and is skipped. The hidden
    // USD ounce is replaced by the derived Toman ounce, so 16 − 1 + 1 = 15.
    expect(quotes.length).toBe(15);
    expect(bySymbol.has("CHF")).toBe(false);
  });

  it("parses thousands-separated Toman prices", () => {
    const gold = bySymbol.get("GOLD_18K")!;
    expect(gold.price).toBe(6_703_000);
    expect(gold.change).toBe(115_000);
    expect(gold.change_percent).toBe(1.75);
    expect(gold.day_high).toBe(6_710_000);
    expect(gold.day_low).toBe(6_580_000);
    expect(gold.prev_price).toBe(6_703_000 - 115_000);
    expect(gold.currency).toBe("TOMAN");
    expect(gold.source).toBe("tgju");
    expect(gold.is_stale).toBe(false);
  });

  it("derives the Toman global ounce from the USD ounce and the USD rate", () => {
    const ounce = bySymbol.get("GOLD_OUNCE_TM")!;
    expect(ounce.currency).toBe("TOMAN");
    // 2651.38 USD × 104,850 Toman/USD
    expect(ounce.price).toBe(Math.round(2651.38 * 104_850));
    expect(ounce.change).toBe(Math.round(18.83 * 104_850));
    expect(ounce.change_percent).toBe(0.71);
    expect(ounce.prev_price).toBe(Math.round((2651.38 - 18.83) * 104_850));
    expect(ounce.source).toBe("tgju+toman");
    // The hidden USD source must never be published.
    expect(bySymbol.has("GOLD_OUNCE")).toBe(false);
  });

  it("flips the change sign when the direction is down (dt=low)", () => {
    const gbp = bySymbol.get("GBP")!;
    expect(gbp.change).toBe(-310);
    expect(gbp.change_percent).toBe(-0.24);
  });

  it("returns null change fields for unavailable deltas (---)", () => {
    const cny = bySymbol.get("CNY")!;
    expect(cny.price).toBe(14_700);
    expect(cny.change).toBeNull();
    expect(cny.change_percent).toBeNull();
    expect(cny.prev_price).toBeNull();
  });

  it("stamps quotes with an ISO updated_at", () => {
    for (const q of quotes) {
      expect(() => Date.parse(q.updated_at)).not.toThrow();
      expect(Number.isNaN(Date.parse(q.updated_at))).toBe(false);
    }
  });
});
