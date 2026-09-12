#!/usr/bin/env node
/**
 * Serverless price aggregator & publisher.
 *
 * Runs inside GitHub Actions (see .github/workflows/snapshot.yml):
 *   1. Fetches prices from TGJU (multi-host failover) and Nobitex (crypto).
 *   2. Validates & normalizes quotes using the shared registry (shared/assets.mjs).
 *   3. Merges with the previous snapshot (carry-forward on provider failure).
 *   4. Appends history points with bounded, progressively-thinned storage.
 *   5. Writes the static JSON API consumed by the Android app:
 *
 *        api/v1/health.json
 *        api/v1/market/prices.json | gold.json | coins.json | currencies.json | crypto.json
 *        api/v1/market/assets/{symbol}.json
 *        api/v1/market/history/{symbol}.json
 *        api/_debug/sources.json   (provider diagnostics)
 *
 * The output schema is byte-compatible with the self-hosted backend (backend/)
 * so the Android app can switch between both seamlessly.
 *
 * No npm dependencies: Node.js >= 18 (global fetch, AbortController).
 */

import { mkdirSync, readFileSync, writeFileSync, existsSync, appendFileSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import {
  ASSETS,
  CATEGORIES,
  HIDDEN_SYMBOLS,
  RANGES,
  maintainPoints,
  rangePoints,
} from "../shared/assets.mjs";
import { quoteFromTgju, quoteFromNobitex, deriveQuote, validateQuote } from "../shared/quotes.mjs";

const __dirname = dirname(fileURLToPath(import.meta.url));

const OUT_DIR = process.env.OUT_DIR || join(__dirname, "..", "public");
const TGJU_HOSTS = (process.env.TGJU_HOSTS || "https://call5.tgju.org,https://call4.tgju.org,https://call2.tgju.org,https://call.tgju.org")
  .split(",")
  .map((h) => h.trim())
  .filter(Boolean);
const NOBITEX_ENABLED = (process.env.NOBITEX_ENABLED || "true") !== "false";
const NOBITEX_STATS_URL = process.env.NOBITEX_STATS_URL || "https://api.nobitex.ir/market/stats";
const FETCH_TIMEOUT_MS = Number(process.env.FETCH_TIMEOUT_MS || 12000);
const API_VERSION = "1.0.0";
/** A quote older than this is flagged stale for clients. */
const STALE_AFTER_MS = Number(process.env.STALE_AFTER_MS || 30 * 60 * 1000);

// ---------------------------------------------------------------------------
// small helpers
// ---------------------------------------------------------------------------

function log(...args) {
  console.log("[snapshot]", ...args);
}

async function fetchJson(url, { timeoutMs = FETCH_TIMEOUT_MS, method = "GET", body } = {}) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const res = await fetch(url, {
      method,
      signal: controller.signal,
      headers: {
        "user-agent": "Talayar-Price-Gateway/1.0 (+https://github.com/javadisaloo1111/Currency-App)",
        ...(body ? { "content-type": "application/json" } : {}),
      },
      body: body ? JSON.stringify(body) : undefined,
    });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    return await res.json();
  } finally {
    clearTimeout(timer);
  }
}

function readJsonIfExists(path) {
  try {
    if (!existsSync(path)) return null;
    return JSON.parse(readFileSync(path, "utf8"));
  } catch (err) {
    log(`failed to read ${path}:`, err.message);
    return null;
  }
}

// ---------------------------------------------------------------------------
// providers
// ---------------------------------------------------------------------------

async function fetchTgju() {
  for (const host of TGJU_HOSTS) {
    const started = Date.now();
    try {
      const payload = await fetchJson(`${host.replace(/\/$/, "")}/ajax.json`);
      const current = payload && typeof payload === "object" ? payload.current : null;
      if (current && typeof current === "object") {
        log(`TGJU OK via ${host} (${Date.now() - started}ms, ${Object.keys(current).length} keys)`);
        return { ok: true, host, current, responseMs: Date.now() - started, error: null };
      }
      throw new Error("unexpected payload shape (missing `current`)");
    } catch (err) {
      log(`TGJU FAILED via ${host}: ${err.message}`);
    }
  }
  return { ok: false, host: null, current: null, responseMs: null, error: "all hosts failed" };
}

async function fetchNobitex(symbols) {
  if (!NOBITEX_ENABLED || symbols.length === 0) return { ok: false, stats: null, error: "disabled" };
  try {
    const payload = await fetchJson(NOBITEX_STATS_URL, {
      method: "POST",
      body: { srcCurrency: symbols.join(","), dstCurrency: "rls" },
    });
    if (payload && payload.status === "ok" && payload.stats) {
      log(`Nobitex OK (${symbols.join(",")})`);
      return { ok: true, stats: payload.stats, error: null };
    }
    throw new Error(payload && payload.status ? `status=${payload.status}` : "unexpected payload");
  } catch (err) {
    log(`Nobitex FAILED: ${err.message}`);
    return { ok: false, stats: null, error: err.message };
  }
}

// ---------------------------------------------------------------------------
// main
// ---------------------------------------------------------------------------

async function main() {
  const generatedAt = new Date();
  const apiDir = join(OUT_DIR, "api");
  const marketDir = join(apiDir, "v1", "market");
  const assetsDir = join(marketDir, "assets");
  const historyDir = join(marketDir, "history");
  for (const dir of [apiDir, marketDir, assetsDir, historyDir, join(apiDir, "_debug")]) {
    mkdirSync(dir, { recursive: true });
  }

  // previous snapshot (carry-forward state)
  const prevPayload = readJsonIfExists(join(marketDir, "prices.json"));
  const prevAssets = new Map((prevPayload?.data || []).map((a) => [a.symbol, a]));

  // fetch providers
  const tgju = await fetchTgju();
  const nobitexSymbols = ASSETS.filter((a) => a.nobitex).map((a) => a.nobitex);
  const nobitex = await fetchNobitex(nobitexSymbols);

  const freshQuotes = [];
  const carryForward = [];
  const missing = [];

  for (const asset of ASSETS) {
    if (asset.derive) continue; // derived quotes are computed after the sources below
    let quote = null;

    if (asset.nobitex && nobitex.ok) {
      quote = quoteFromNobitex(asset, nobitex.stats[`${asset.nobitex}-rls`] || nobitex.stats[`${asset.nobitex}-rlt`]);
    }
    if (quote == null && asset.tgjuKeys && tgju.ok) {
      for (const key of asset.tgjuKeys) {
        const entry = tgju.current[key];
        if (entry) {
          quote = quoteFromTgju(asset, entry);
          if (quote) break;
        }
      }
    }

    const prev = prevAssets.get(asset.symbol) || null;
    if (quote && validateQuote(quote, prev ? prev.price : null)) {
      quote.updated_at = generatedAt.toISOString();
      quote.is_stale = false;
      freshQuotes.push(quote);
    } else if (prev) {
      // provider failed for this asset -> serve last known good value, marked stale
      carryForward.push({ ...prev, is_stale: true });
    } else {
      missing.push(asset.symbol);
    }
  }

  // fill missing prev/change info from the previous snapshot when the provider had none
  for (const q of freshQuotes) {
    const prev = prevAssets.get(q.symbol);
    if (prev && q.change == null && q.prev_price == null) {
      q.change = q.price - prev.price;
      q.prev_price = prev.price;
      q.change_percent = prev.price > 0 ? Number((((q.price - prev.price) / prev.price) * 100).toFixed(2)) : null;
      q.source = `${q.source}+calc`;
    }
  }

  // derive canonical Toman quotes (GOLD_OUNCE_TM = USD ounce × USD/Toman rate)
  const combinedQuotes = new Map([...freshQuotes, ...carryForward].map((q) => [q.symbol, q]));
  for (const asset of ASSETS) {
    if (!asset.derive) continue;
    const src = combinedQuotes.get(asset.derive.from) || null;
    const rate = combinedQuotes.get(asset.derive.byQuote) || null;
    const core = deriveQuote(asset, src, rate);
    const prev = prevAssets.get(asset.symbol) || null;
    const sourcesFresh = src != null && rate != null && !src.is_stale && !rate.is_stale;
    if (core && sourcesFresh && validateQuote(core, prev ? prev.price : null)) {
      core.updated_at = generatedAt.toISOString();
      core.is_stale = false;
      freshQuotes.push(core);
    } else if (core && prev) {
      // sources unavailable/stale -> keep the last published value, marked stale
      carryForward.push({ ...prev, is_stale: true });
    } else if (core) {
      // first run without a previous snapshot: publish the derived value marked stale
      core.updated_at = generatedAt.toISOString();
      core.is_stale = true;
      carryForward.push(core);
    } else if (prev) {
      carryForward.push({ ...prev, is_stale: true });
    } else {
      missing.push(asset.symbol);
    }
  }

  const data = [
    ...freshQuotes,
    ...carryForward,
  ]
    .filter((q) => !HIDDEN_SYMBOLS.has(q.symbol))
    .sort((a, b) => ASSETS.findIndex((x) => x.symbol === a.symbol) - ASSETS.findIndex((x) => x.symbol === b.symbol));
  const anyFresh = freshQuotes.length > 0;
  const newestUpdatedAt = data.reduce((acc, q) => (q.updated_at > acc ? q.updated_at : acc), "1970-01-01T00:00:00Z");
  const isStale = !anyFresh || generatedAt.getTime() - Date.parse(newestUpdatedAt) > STALE_AFTER_MS;

  // -----------------------------------------------------------------------
  // history (append fresh points, maintain bounded storage per symbol)
  // -----------------------------------------------------------------------
  const freshBySymbol = new Map(freshQuotes.map((q) => [q.symbol, q]));

  for (const q of data) {
    const historyPath = join(historyDir, `${q.symbol}.json`);
    const prevHistory = readJsonIfExists(historyPath);
    const allPoints = Array.isArray(prevHistory?.all) ? prevHistory.all : [];
    const fresh = freshBySymbol.get(q.symbol);
    if (fresh && (allPoints.length === 0 || allPoints[allPoints.length - 1].t !== generatedAt.getTime())) {
      allPoints.push({ t: generatedAt.getTime(), p: q.price });
    }
    const maintained = maintainPoints(allPoints, generatedAt.getTime());
    const ranges = {};
    for (const range of RANGES) ranges[range] = rangePoints(maintained, range, generatedAt.getTime());
    const historyPayload = {
      symbol: q.symbol,
      updated_at: generatedAt.toISOString(),
      is_stale: !fresh,
      ranges,
      all: maintained,
    };
    writeOutput(historyPath, JSON.stringify(historyPayload, null, 1) + "\n");
  }

  // -----------------------------------------------------------------------
  // market endpoints
  // -----------------------------------------------------------------------
  const envelope = (list) => ({
    updated_at: newestUpdatedAt,
    generated_at: generatedAt.toISOString(),
    is_stale: isStale,
    data: list,
  });

  writeOutput(join(marketDir, "prices.json"), JSON.stringify(envelope(data), null, 1) + "\n");
  for (const category of CATEGORIES) {
    writeOutput(
      join(marketDir, `${category === "coin" ? "coins" : category === "currency" ? "currencies" : category}.json`),
      JSON.stringify(envelope(data.filter((q) => q.category === category)), null, 1) + "\n",
    );
  }
  for (const q of data) {
    writeOutput(
      join(assetsDir, `${q.symbol}.json`),
      JSON.stringify({ ...q, generated_at: generatedAt.toISOString() }, null, 1) + "\n",
    );
  }

  // -----------------------------------------------------------------------
  // health + diagnostics
  // -----------------------------------------------------------------------
  const health = {
    status: anyFresh ? "ok" : "degraded",
    version: API_VERSION,
    time: generatedAt.toISOString(),
    is_stale: isStale,
    cache: {
      assets: data.length,
      fresh: freshQuotes.length,
      stale: carryForward.length,
      last_success_at: anyFresh ? generatedAt.toISOString() : prevPayload?.generated_at || null,
    },
    sources: [
      {
        name: "tgju",
        status: tgju.ok ? "up" : "down",
        host: tgju.host,
        priority: 1,
        response_ms: tgju.responseMs,
        error: tgju.error,
      },
      {
        name: "nobitex",
        status: nobitex.ok ? "up" : "down",
        priority: 2,
        error: nobitex.error,
      },
    ],
  };
  writeOutput(join(apiDir, "v1", "health.json"), JSON.stringify(health, null, 1) + "\n");

  const debug = {
    generated_at: generatedAt.toISOString(),
    tgju_host_used: tgju.host,
    tgju_key_count: tgju.current ? Object.keys(tgju.current).length : 0,
    tgju_keys: tgju.current ? Object.keys(tgju.current).sort() : [],
    // Raw entries for keys whose unit/scale needs verification (diagnostics).
    probe: tgju.current
      ? Object.fromEntries(
          [
            "ons", "price_eur", "price_gbp", "price_aed", "price_try", "price_cny", "price_chf",
            "crypto-tether", "crypto-tether-irr", "crypto-bitcoin-irr", "btc-irr",
          ]
            .filter((k) => tgju.current[k])
            .map((k) => [k, tgju.current[k]]),
        )
      : {},
    mapped_symbols: freshQuotes.map((q) => `${q.symbol}:${q.source}`),
    carried_forward: carryForward.map((q) => q.symbol),
    missing,
    nobitex_error: nobitex.error,
  };
  writeOutput(join(apiDir, "_debug", "sources.json"), JSON.stringify(debug, null, 1) + "\n");

  writeOutput(
    join(OUT_DIR, "index.html"),
    `<!doctype html>
<html lang="fa" dir="rtl">
<head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1"/>
<title>طلایار — Price Gateway</title>
<style>
  body{font-family:system-ui,'Vazirmatn',sans-serif;background:#101310;color:#e6e2d9;max-width:720px;margin:0 auto;padding:32px 20px;line-height:1.9}
  h1{color:#e9ba4e;font-size:22px} code{background:#1c1c16;padding:2px 8px;border-radius:8px;font-size:13px;direction:ltr;display:inline-block}
  a{color:#e9ba4e}
  .box{background:#171a17;border:1px solid #262a22;border-radius:16px;padding:16px 20px;margin:14px 0}
</style>
</head>
<body>
<h1>طلایار — Price Gateway (Static API)</h1>
<p>این صفحه سرویس ایستای قیمت است که اپلیکیشن اندروید طلایار از آن استفاده می‌کند و به‌طور خودکار به‌روزرسانی می‌شود.</p>
<div class="box">
  <div><code>api/v1/market/prices.json</code> — همه دارایی‌ها</div>
  <div><code>api/v1/market/gold.json</code> — طلا</div>
  <div><code>api/v1/market/coins.json</code> — سکه</div>
  <div><code>api/v1/market/currencies.json</code> — ارز</div>
  <div><code>api/v1/market/crypto.json</code> — رمزارز</div>
  <div><code>api/v1/market/assets/GOLD_18K.json</code> — جزئیات یک دارایی</div>
  <div><code>api/v1/market/history/GOLD_18K.json</code> — تاریخچه قیمت</div>
  <div><code>api/v1/health.json</code> — وضعیت سرویس</div>
</div>
<p><a href="https://github.com/javadisaloo1111/Currency-App">مخزن پروژه در GitHub</a></p>
</body>
</html>
`,
  );

  log(`published ${data.length} assets (${freshQuotes.length} fresh, ${carryForward.length} carried, ${missing.length} missing)`);

  if (process.env.GITHUB_OUTPUT) {
    appendToGithubOutput("changed", writtenFiles.size > 0 ? "true" : "false");
  }
}

/** Files whose content changed during this run. */
const writtenFiles = new Set();

function writeOutput(path, content) {
  try {
    if (existsSync(path) && readFileSync(path, "utf8") === content) return;
    writeFileSync(path, content, "utf8");
    writtenFiles.add(path);
  } catch (err) {
    console.error(`[snapshot] failed to write ${path}:`, err);
  }
}

function appendToGithubOutput(name, value) {
  appendFileSync(process.env.GITHUB_OUTPUT, `${name}=${value}\n`, "utf8");
}

main().catch((err) => {
  console.error("[snapshot] fatal:", err);
  if (process.env.GITHUB_OUTPUT) {
    try {
      appendToGithubOutput("changed", "false");
    } catch {
      /* noop */
    }
  }
  process.exit(1);
});
