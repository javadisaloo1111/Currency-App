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
    // 16 TGJU-mapped assets in the registry; CHF has a broken value and is skipped.
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

  it("keeps USD decimals for the global ounce", () => {
    const ounce = bySymbol.get("GOLD_OUNCE")!;
    expect(ounce.currency).toBe("USD");
    expect(ounce.price).toBeCloseTo(2651.38, 2);
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
