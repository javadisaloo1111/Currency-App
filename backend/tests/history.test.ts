import { describe, it, expect } from "vitest";
import { openDatabase } from "../src/db/database.js";
import { HistoryService } from "../src/services/history.js";
import { RANGES } from "../../shared/assets.mjs";

function service(): HistoryService {
  const db = openDatabase(":memory:");
  return new HistoryService(db);
}

describe("HistoryService", () => {
  it("records a point and dedupes unchanged prices inside the gap window", () => {
    const history = service();
    const now = Date.now();
    expect(history.recordPoint("USD", 104_850, now)).toBe(true);
    expect(history.recordPoint("USD", 104_850, now + 10_000)).toBe(false); // same price, <60s
    expect(history.recordPoint("USD", 104_990, now + 20_000)).toBe(true); // price changed
    expect(history.recordPoint("USD", 104_990, now + 120_000)).toBe(true); // gap exceeded
    expect(history.lastPoint("USD")).toEqual({ t: now + 120_000, p: 104_990 });
  });

  it("serves windowed, thinned points per range", () => {
    const history = service();
    const now = Date.now();
    // 3 hours of points, one per minute
    const points = Array.from({ length: 180 }, (_, i) => ({ t: now - (180 - i) * 60_000, p: 100 + i }));
    history.importPoints("GOLD_18K", points);

    const lastHour = history.range("GOLD_18K", "1H", now);
    expect(lastHour.length).toBe(60);
    expect(lastHour[lastHour.length - 1]!.p).toBe(279);

    const sixHours = history.range("GOLD_18K", "6H", now);
    expect(sixHours.length).toBe(180);

    const all = history.allRanges("GOLD_18K", now);
    expect(Object.keys(all).sort()).toEqual([...RANGES].sort());
  });

  it("imports idempotently (same timestamp updates in place)", () => {
    const history = service();
    history.importPoints("USD", [{ t: 1000, p: 10 }, { t: 2000, p: 20 }]);
    history.importPoints("USD", [{ t: 1000, p: 11 }]);
    const points = history.range("USD", "1Y", 100_000);
    expect(points).toEqual([
      { t: 1000, p: 11 },
      { t: 2000, p: 20 },
    ]);
  });
});
