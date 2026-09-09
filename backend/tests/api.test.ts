import { describe, it, expect, beforeAll } from "vitest";
import { openDatabase } from "../src/db/database.js";
import { PriceCache } from "../src/services/cache.js";
import { SourceMonitor } from "../src/services/sources.js";
import { HistoryService } from "../src/services/history.js";
import { buildApp } from "../src/index.js";
import type { FastifyInstance } from "fastify";
import type { AssetQuote } from "../src/types.js";

let app: FastifyInstance;

const seeded: AssetQuote[] = [
  {
    symbol: "GOLD_18K",
    name: "طلای ۱۸ عیار",
    category: "gold",
    currency: "TOMAN",
    unit: "گرم",
    price: 6_703_000,
    change: 115_000,
    change_percent: 1.75,
    day_high: 6_710_000,
    day_low: 6_580_000,
    prev_price: 6_588_000,
    updated_at: new Date().toISOString(),
    source: "tgju",
    is_stale: false,
  },
  {
    symbol: "COIN_EMAMI",
    name: "سکه امامی",
    category: "coin",
    currency: "TOMAN",
    unit: "عدد",
    price: 60_150_000,
    change: 350_000,
    change_percent: 0.59,
    day_high: 60_300_000,
    day_low: 59_200_000,
    prev_price: 59_800_000,
    updated_at: new Date().toISOString(),
    source: "tgju",
    is_stale: false,
  },
  {
    symbol: "USD",
    name: "دلار آمریکا",
    category: "currency",
    currency: "TOMAN",
    unit: "دلار",
    price: 104_850,
    change: 640,
    change_percent: 0.61,
    day_high: 105_020,
    day_low: 104_210,
    prev_price: 104_210,
    updated_at: new Date().toISOString(),
    source: "tgju",
    is_stale: false,
  },
];

beforeAll(async () => {
  const db = openDatabase(":memory:");
  const cache = new PriceCache();
  cache.load(seeded);
  const monitor = new SourceMonitor();
  const history = new HistoryService(db);
  const now = Date.now();
  history.importPoints(
    "GOLD_18K",
    Array.from({ length: 60 }, (_, i) => ({ t: now - (60 - i) * 60_000 + 30_000, p: 6_600_000 + i * 1_500 })),
  );

  app = await buildApp({
    db,
    cache,
    monitor,
    history,
    staleAfterMs: 5 * 60_000,
    rateLimitMax: 1000,
    rateLimitWindowMs: 60_000,
  });
  await app.ready();
});

describe("Price Gateway API", () => {
  it("GET /api/v1/health returns ok with source registry", async () => {
    const res = await app.inject({ method: "GET", url: "/api/v1/health.json" });
    expect(res.statusCode).toBe(200);
    const body = res.json();
    expect(body.status).toBe("ok");
    expect(body.cache.assets).toBe(3);
    expect(Array.isArray(body.sources)).toBe(true);
    expect(body.is_stale).toBe(false);
  });

  it("GET /api/v1/market/prices returns the seeded envelope", async () => {
    const res = await app.inject({ method: "GET", url: "/api/v1/market/prices.json" });
    expect(res.statusCode).toBe(200);
    const body = res.json();
    expect(body.data.length).toBe(3);
    expect(body.updated_at).toBeTruthy();
    expect(typeof body.is_stale).toBe("boolean");
    const symbols = body.data.map((q: AssetQuote) => q.symbol);
    expect(symbols).toContain("GOLD_18K");
    expect(symbols).toContain("USD");
    expect(res.headers["cache-control"]).toContain("max-age");
  });

  it("category endpoints filter correctly (gold / coins / currencies)", async () => {
    const gold = (await app.inject({ method: "GET", url: "/api/v1/market/gold.json" })).json();
    expect(gold.data.map((q: AssetQuote) => q.symbol)).toEqual(["GOLD_18K"]);

    const coins = (await app.inject({ method: "GET", url: "/api/v1/market/coins.json" })).json();
    expect(coins.data.map((q: AssetQuote) => q.symbol)).toEqual(["COIN_EMAMI"]);

    const currencies = (await app.inject({ method: "GET", url: "/api/v1/market/currencies.json" })).json();
    expect(currencies.data.map((q: AssetQuote) => q.symbol)).toEqual(["USD"]);
  });

  it("GET /api/v1/market/assets/:symbol returns details and 404 for unknown", async () => {
    const ok = await app.inject({ method: "GET", url: "/api/v1/market/assets/GOLD_18K.json" });
    expect(ok.statusCode).toBe(200);
    expect(ok.json().price).toBe(6_703_000);

    const missing = await app.inject({ method: "GET", url: "/api/v1/market/assets/NOPE.json" });
    expect(missing.statusCode).toBe(404);
    expect(missing.json().error).toBe("not_found");
  });

  it("GET /api/v1/market/history/:symbol serves all chart ranges", async () => {
    const res = await app.inject({ method: "GET", url: "/api/v1/market/history/GOLD_18K.json" });
    expect(res.statusCode).toBe(200);
    const body = res.json();
    expect(Object.keys(body.ranges).sort()).toEqual(["1D", "1H", "1M", "1W", "1Y", "3M", "6H"].sort());
    expect(body.ranges["1H"].length).toBe(60);
    expect(body.ranges["1H"][0].p).toBeGreaterThanOrEqual(6_600_000);
  });

  it("supports range filtering via query parameter", async () => {
    const res = await app.inject({ method: "GET", url: "/api/v1/market/history/GOLD_18K.json?range=1H" });
    expect(res.statusCode).toBe(200);
    const body = res.json();
    expect(body.range).toBe("1H");
    expect(Array.isArray(body.points)).toBe(true);
  });

  it("manages price alerts (create / list / delete)", async () => {
    const created = await app.inject({
      method: "POST",
      url: "/api/v1/alerts",
      payload: { symbol: "USD", kind: "above", threshold: 110_000 },
    });
    expect(created.statusCode).toBe(201);
    const alert = created.json().data;
    expect(alert.symbol).toBe("USD");

    const list = (await app.inject({ method: "GET", url: "/api/v1/alerts.json" })).json();
    expect(list.data.length).toBe(1);

    const removed = await app.inject({ method: "DELETE", url: `/api/v1/alerts/${alert.id}` });
    expect(removed.statusCode).toBe(204);

    const empty = (await app.inject({ method: "GET", url: "/api/v1/alerts.json" })).json();
    expect(empty.data.length).toBe(0);
  });

  it("rejects invalid alert payloads", async () => {
    const res = await app.inject({
      method: "POST",
      url: "/api/v1/alerts",
      payload: { symbol: "USD", kind: "wat", threshold: 1 },
    });
    expect(res.statusCode).toBe(400);
  });
});
