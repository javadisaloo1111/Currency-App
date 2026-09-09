/**
 * Canonical asset registry shared by:
 *   - backend price providers (backend/src/*)
 *   - the serverless snapshot publisher (scripts/snapshot-publisher.mjs)
 *   - documentation
 *
 * Keep this file dependency-free plain JavaScript (ESM) so it can run in any
 * Node.js >= 18 environment and be imported from TypeScript via allowJs.
 */

export const CATEGORIES = ["gold", "coin", "currency", "crypto"];

export const CATEGORY_LABELS_FA = {
  gold: "طلا",
  coin: "سکه",
  currency: "ارز",
  crypto: "رمزارز",
};

export const RANGES = ["1H", "6H", "1D", "1W", "1M", "3M", "1Y"];

export const RANGE_LABELS_FA = {
  "1H": "۱ ساعت",
  "6H": "۶ ساعت",
  "1D": "۱ روز",
  "1W": "۱ هفته",
  "1M": "۱ ماه",
  "3M": "۳ ماه",
  "1Y": "۱ سال",
};

export const HOUR = 3600 * 1000;

export const RANGE_MS = {
  "1H": 1 * HOUR,
  "6H": 6 * HOUR,
  "1D": 24 * HOUR,
  "1W": 7 * 24 * HOUR,
  "1M": 30 * 24 * HOUR,
  "3M": 90 * 24 * HOUR,
  "1Y": 365 * 24 * HOUR,
};

/** Upper bound of points served per range (charts stay responsive). */
export const RANGE_MAX_POINTS = {
  "1H": 720,
  "6H": 720,
  "1D": 720,
  "1W": 336,
  "1M": 360,
  "3M": 360,
  "1Y": 366,
};

/**
 * Asset registry.
 *  - tgjuKeys: candidate keys inside the TGJU ajax.json `current` object (first hit wins).
 *  - nobitex:  symbol pair on Nobitex (crypto); preferred for crypto assets.
 *  - decimals: fractional digits used when the price is not an integer amount.
 */
export const ASSETS = [
  { symbol: "GOLD_18K",     name: "طلای ۱۸ عیار",     category: "gold",     currency: "TOMAN", unit: "گرم",    tgjuKeys: ["geram18"] },
  { symbol: "GOLD_24K",     name: "طلای ۲۴ عیار",     category: "gold",     currency: "TOMAN", unit: "گرم",    tgjuKeys: ["geram24"] },
  { symbol: "GOLD_MESGHAL", name: "مثقال طلا",        category: "gold",     currency: "TOMAN", unit: "مثقال",  tgjuKeys: ["mesghal"] },
  // Global ounce: live TGJU uses "ons" (انس); "once"/"ounce" kept for compatibility.
  { symbol: "GOLD_OUNCE",   name: "انس جهانی طلا",    category: "gold",     currency: "USD",   unit: "انس",    tgjuKeys: ["ons", "once", "ounce"], decimals: 2 },
  { symbol: "COIN_EMAMI",   name: "سکه امامی",        category: "coin",     currency: "TOMAN", unit: "عدد",    tgjuKeys: ["sekee"] },
  { symbol: "COIN_BAHAR",   name: "سکه بهار آزادی",   category: "coin",     currency: "TOMAN", unit: "عدد",    tgjuKeys: ["sekeb"] },
  { symbol: "COIN_NIM",     name: "نیم سکه",          category: "coin",     currency: "TOMAN", unit: "عدد",    tgjuKeys: ["nim"] },
  { symbol: "COIN_ROB",     name: "ربع سکه",          category: "coin",     currency: "TOMAN", unit: "عدد",    tgjuKeys: ["rob"] },
  { symbol: "COIN_GERAMI",  name: "سکه گرمی",         category: "coin",     currency: "TOMAN", unit: "عدد",    tgjuKeys: ["gerami"] },
  // Currencies: live TGJU ajax.json exposes "price_eur"/"price_gbp"/... keys.
  { symbol: "USD",          name: "دلار آمریکا",      category: "currency", currency: "TOMAN", unit: "دلار",   tgjuKeys: ["price_dollar_rl"] },
  { symbol: "EUR",          name: "یورو",             category: "currency", currency: "TOMAN", unit: "یورو",   tgjuKeys: ["price_eur", "eur"] },
  { symbol: "GBP",          name: "پوند انگلیس",      category: "currency", currency: "TOMAN", unit: "پوند",   tgjuKeys: ["price_gbp", "gbp"] },
  { symbol: "AED",          name: "درهم امارات",      category: "currency", currency: "TOMAN", unit: "درهم",   tgjuKeys: ["price_aed", "aed"] },
  { symbol: "TRY",          name: "لیر ترکیه",        category: "currency", currency: "TOMAN", unit: "لیر",    tgjuKeys: ["price_try", "try"] },
  { symbol: "CNY",          name: "یوان چین",         category: "currency", currency: "TOMAN", unit: "یوان",   tgjuKeys: ["price_cny", "cny"] },
  { symbol: "CHF",          name: "فرانک سوئیس",      category: "currency", currency: "TOMAN", unit: "فرانک",  tgjuKeys: ["price_chf", "chf"] },
  // Crypto: Nobitex preferred; TGJU "-irr" keys are the fallback when Nobitex is unreachable.
  { symbol: "USDT",         name: "تتر",              category: "crypto",   currency: "TOMAN", unit: "تتر",    tgjuKeys: ["crypto-tether-irr"], nobitex: "usdt" },
  { symbol: "BTC",          name: "بیت‌کوین",         category: "crypto",   currency: "TOMAN", unit: "بیت‌کوین", tgjuKeys: ["crypto-bitcoin-irr", "btc-irr"], nobitex: "btc" },
];

export const ASSET_BY_SYMBOL = Object.fromEntries(ASSETS.map((a) => [a.symbol, a]));

export function findAssetByTgjuKey(key) {
  return ASSETS.find((a) => (a.tgjuKeys || []).includes(key)) || null;
}

/**
 * Reduce a sorted (ascending t) point list to at most `maxPoints` entries,
 * always keeping the most recent point.
 */
export function thinPoints(points, maxPoints) {
  if (!Array.isArray(points) || points.length <= maxPoints) return points ? [...points] : [];
  const stride = Math.ceil(points.length / maxPoints);
  const thinned = points.filter((_, i) => i % stride === 0);
  const last = points[points.length - 1];
  if (thinned.length === 0 || thinned[thinned.length - 1].t !== last.t) thinned.push(last);
  return thinned;
}

/** Points of `points` inside the time window of `range`, thinned to the range cap. */
export function rangePoints(points, range, now = Date.now()) {
  const windowMs = RANGE_MS[range] || RANGE_MS["1D"];
  const from = now - windowMs;
  const inWindow = points.filter((p) => typeof p.t === "number" && p.t >= from);
  return thinPoints(inWindow, RANGE_MAX_POINTS[range] || 720);
}

/**
 * Progressive compaction: keep everything from the last 24h and thin the tail
 * so long-running snapshots stay bounded (~<= 4500 points per symbol).
 */
export function maintainPoints(points, now = Date.now()) {
  const cutoff = now - RANGE_MS["1Y"] - 12 * HOUR;
  let pts = (points || []).filter((p) => typeof p.t === "number" && Number.isFinite(p.p) && p.t >= cutoff);
  pts.sort((a, b) => a.t - b.t);
  if (pts.length > 3000) {
    const horizon = now - 24 * HOUR;
    const old = pts.filter((p) => p.t < horizon);
    const recent = pts.filter((p) => p.t >= horizon);
    const stride = Math.ceil(old.length / 1500);
    const thinnedOld = old.filter((_, i) => i % stride === 0);
    if (thinnedOld.length === 0 && old.length > 0) thinnedOld.push(old[old.length - 1]);
    pts = [...thinnedOld, ...recent];
  }
  // de-duplicate identical timestamps (keep last)
  const byT = new Map();
  for (const p of pts) byT.set(p.t, p);
  return [...byT.values()].sort((a, b) => a.t - b.t);
}
