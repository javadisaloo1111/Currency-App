import type { FastifyInstance } from "fastify";
import type { TalayarDatabase } from "../db/database.js";
import type { AssetQuote, Category } from "../types.js";
import { PriceCache } from "../services/cache.js";
import { SourceMonitor } from "../services/sources.js";
import { HistoryService } from "../services/history.js";
import { createAlert, deleteAlert, listAlerts } from "../db/database.js";

export interface RouteDeps {
  db: TalayarDatabase;
  cache: PriceCache;
  monitor: SourceMonitor;
  history: HistoryService;
  staleAfterMs: number;
}

const CATEGORY_FILES: Record<string, Category> = {
  gold: "gold",
  coins: "coin",
  currencies: "currency",
  crypto: "crypto",
};

/** Register a route twice: canonical and `.json` alias (static-API compatible). */
function get(app: FastifyInstance, path: string, handler: (req: any, reply: any) => Promise<void> | void): void {
  app.get(path, handler);
  app.get(`${path}.json`, handler);
}

export function registerRoutes(app: FastifyInstance, deps: RouteDeps): void {
  const { cache, monitor, history, db, staleAfterMs } = deps;

  const nowMs = () => Date.now();
  const envelope = (list: AssetQuote[]) => {
    const now = nowMs();
    const newest = list.reduce((acc, q) => (Date.parse(q.updated_at) > Date.parse(acc) ? q.updated_at : acc), "1970-01-01T00:00:00Z");
    const stale = list.length > 0 && now - Date.parse(newest) > staleAfterMs;
    return { updated_at: newest, generated_at: new Date(now).toISOString(), is_stale: stale, data: list };
  };

  const cacheControl = (reply: any) => reply.header("Cache-Control", "public, max-age=5");

  // ---- health -----------------------------------------------------------
  get(app, "/api/v1/health", async (_req, reply) => {
    const now = nowMs();
    const quotes = cache.all(new Date(now), staleAfterMs);
    const freshest = quotes.reduce((acc, q) => (Date.parse(q.updated_at) > acc ? Date.parse(q.updated_at) : acc), 0);
    reply.send({
      status: quotes.length === 0 ? "degraded" : "ok",
      version: "1.0.0",
      time: new Date(now).toISOString(),
      is_stale: quotes.length === 0 || now - freshest > staleAfterMs,
      cache: {
        assets: quotes.length,
        last_success_at: cache.lastSuccess,
      },
      sources: monitor.list(),
    });
  });

  // ---- admin: provider health (foundation for the admin panel) -----------
  get(app, "/api/v1/admin/sources", async (_req, reply) => {
    reply.send({ data: monitor.list(), generated_at: new Date(nowMs()).toISOString() });
  });

  // ---- market prices ------------------------------------------------------
  get(app, "/api/v1/market/prices", async (_req, reply) => {
    cacheControl(reply);
    reply.send(envelope(cache.all(new Date(nowMs()), staleAfterMs)));
  });

  for (const [file, category] of Object.entries(CATEGORY_FILES)) {
    get(app, `/api/v1/market/${file}`, async (_req, reply) => {
      cacheControl(reply);
      const list = cache.all(new Date(nowMs()), staleAfterMs).filter((q) => q.category === category);
      reply.send(envelope(list));
    });
  }

  get(app, "/api/v1/market/assets/:symbol", async (req, reply) => {
    cacheControl(reply);
    const { symbol } = req.params as { symbol: string };
    const quote = cache.get(symbol, new Date(nowMs()), staleAfterMs);
    if (!quote) {
      reply.code(404).send({ error: "not_found", message: "دارایی موردنظر یافت نشد." });
      return;
    }
    reply.send({ ...quote, generated_at: new Date(nowMs()).toISOString() });
  });

  get(app, "/api/v1/market/history/:symbol", async (req, reply) => {
    cacheControl(reply);
    const { symbol } = req.params as { symbol: string };
    const query = req.query as { range?: string };
    const quote = cache.get(symbol, new Date(nowMs()), staleAfterMs);
    if (!quote) {
      reply.code(404).send({ error: "not_found", message: "دارایی موردنظر یافت نشد." });
      return;
    }
    if (query.range) {
      const points = history.range(symbol, query.range, nowMs());
      reply.send({
        symbol,
        range: query.range,
        is_stale: quote.is_stale,
        updated_at: new Date(nowMs()).toISOString(),
        points,
      });
      return;
    }
    reply.send({
      symbol,
      is_stale: quote.is_stale,
      updated_at: new Date(nowMs()).toISOString(),
      ranges: history.allRanges(symbol, nowMs()),
    });
  });

  // ---- price alerts (server-side storage; app evaluates locally) ----------
  app.get("/api/v1/alerts", async (_req, reply) => {
    reply.send({ data: listAlerts(db) });
  });
  app.get("/api/v1/alerts.json", async (_req, reply) => {
    reply.send({ data: listAlerts(db) });
  });

  app.post("/api/v1/alerts", async (req, reply) => {
    const body = req.body as { symbol?: string; kind?: string; threshold?: number | string };
    const symbol = typeof body.symbol === "string" ? body.symbol.trim() : "";
    const kind = typeof body.kind === "string" ? body.kind.trim() : "";
    const threshold = Number(body.threshold);
    if (!symbol || (kind !== "above" && kind !== "below" && kind !== "percent") || !Number.isFinite(threshold)) {
      reply.code(400).send({ error: "invalid_request", message: "ورودی نامعتبر است." });
      return;
    }
    const alert = createAlert(db, symbol, kind, threshold);
    reply.code(201).send({ data: alert });
  });

  app.delete("/api/v1/alerts/:id", async (req, reply) => {
    const { id } = req.params as { id: string };
    const ok = deleteAlert(db, Number(id));
    if (!ok) {
      reply.code(404).send({ error: "not_found", message: "هشدار یافت نشد." });
      return;
    }
    reply.code(204).send();
  });
}
