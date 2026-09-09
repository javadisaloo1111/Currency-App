import { pathToFileURL } from "node:url";
import Fastify, { type FastifyInstance } from "fastify";
import cors from "@fastify/cors";
import rateLimit from "@fastify/rate-limit";
import { loadConfig, type AppConfig } from "./config.js";
import { openDatabase, loadLatestPrices } from "./db/database.js";
import type { TalayarDatabase } from "./db/database.js";
import type { PriceProvider } from "./types.js";
import { TgjuProvider } from "./providers/tgju.js";
import { NobitexProvider } from "./providers/nobitex.js";
import { SnapshotProvider, readSnapshotHistory } from "./providers/snapshot.js";
import { PriceCache } from "./services/cache.js";
import { SourceMonitor } from "./services/sources.js";
import { PriceAggregator } from "./services/aggregator.js";
import { HistoryService } from "./services/history.js";
import { PricePoller } from "./services/poller.js";
import { registerRoutes } from "./api/routes.js";
import { ASSETS } from "../../shared/assets.mjs";

export interface BuildAppDeps {
  db: TalayarDatabase;
  cache: PriceCache;
  monitor: SourceMonitor;
  history: HistoryService;
  staleAfterMs: number;
  rateLimitMax: number;
  rateLimitWindowMs: number;
  loggerEnabled?: boolean;
}

/** Build the Fastify app (used by main() and by tests). */
export async function buildApp(deps: BuildAppDeps): Promise<FastifyInstance> {
  const app = Fastify({ logger: deps.loggerEnabled ?? false });

  await app.register(cors, { origin: true });

  await app.register(rateLimit, {
    global: true,
    max: deps.rateLimitMax,
    timeWindow: deps.rateLimitWindowMs,
  });

  registerRoutes(app, {
    db: deps.db,
    cache: deps.cache,
    monitor: deps.monitor,
    history: deps.history,
    staleAfterMs: deps.staleAfterMs,
  });

  return app;
}

async function main(): Promise<void> {
  const config: AppConfig = loadConfig();

  const db = openDatabase(config.dbPath);
  const monitor = new SourceMonitor();
  const cache = new PriceCache();
  const history = new HistoryService(db);

  // 1) Bootstrap from the last persisted state (offline-first start).
  const persisted = loadLatestPrices(db);
  cache.load(persisted);
  console.log(`[boot] loaded ${persisted.length} persisted quotes from ${config.dbPath}`);

  // 2) Bootstrap history from the static snapshot so a fresh deploy has charts.
  if (config.snapshotImportHistory && config.snapshotDir) {
    let imported = 0;
    for (const asset of ASSETS) {
      const points = readSnapshotHistory(config.snapshotDir, asset.symbol as string);
      if (points.length > 0) imported += history.importPoints(asset.symbol, points);
    }
    console.log(`[boot] imported ${imported} history points from snapshot`);
  }

  // 3) Providers (priority order defines the failover chain).
  const providers: PriceProvider[] = [
    new TgjuProvider(config.tgjuHosts, config.fetchTimeoutMs),
  ];
  if (config.nobitexEnabled) {
    providers.push(new NobitexProvider("https://api.nobitex.ir/market/stats", config.fetchTimeoutMs, true));
  }
  if (config.snapshotDir || config.snapshotUrl) {
    providers.push(new SnapshotProvider({ dir: config.snapshotDir, url: config.snapshotUrl, timeoutMs: config.fetchTimeoutMs }));
  }
  const aggregator = new PriceAggregator(providers, monitor, cache);

  // 4) HTTP API
  const app = await buildApp({
    db,
    cache,
    monitor,
    history,
    staleAfterMs: config.staleAfterMs,
    rateLimitMax: config.rateLimitMax,
    rateLimitWindowMs: config.rateLimitWindowMs,
    loggerEnabled: true,
  });

  // 5) Background polling
  const poller = new PricePoller(aggregator, cache, monitor, history, db, {
    intervalMs: config.pollIntervalMs,
    historyEnabled: true,
  });
  poller.start();

  for (const signal of ["SIGINT", "SIGTERM"] as const) {
    process.on(signal, () => {
      console.log(`[shutdown] received ${signal}`);
      poller.stop();
      void app.close().finally(() => {
        db.close();
        process.exit(0);
      });
    });
  }

  await app.listen({ port: config.port, host: config.host });
  console.log(`[boot] Talayar Price Gateway listening on http://${config.host}:${config.port}`);
  console.log(`[boot] providers: ${providers.map((p) => `${p.name}(p${p.priority})`).join(", ")}`);
}

// run only when executed directly (not under test import)
const isMain = process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href;
if (isMain) {
  main().catch((err) => {
    console.error("[fatal]", err);
    process.exit(1);
  });
}
