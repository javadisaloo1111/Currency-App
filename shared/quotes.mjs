/**
 * Shared quote mapping & validation used by BOTH the backend providers and the
 * serverless snapshot publisher. Plain ESM, no dependencies.
 */
import { ASSET_BY_SYMBOL } from "./assets.mjs";

/** Parse market numeric strings: "104,850" | "2,651.38" | "---" | number */
export function parseMarketNum(value) {
  if (value == null) return null;
  if (typeof value === "number") return Number.isFinite(value) ? value : null;
  let s = String(value).trim();
  if (!s || s === "-" || s === "---" || s === "N/A") return null;
  s = s.replace(/[,\s\u066C]/g, "");
  const n = Number(s);
  return Number.isFinite(n) ? n : null;
}

const roundFor = (asset, v) => {
  if (v == null) return null;
  return asset.decimals ? Number(v.toFixed(asset.decimals)) : Math.round(v);
};

/**
 * Map one TGJU `current[key]` entry to a quote core (no updated_at / is_stale).
 * Prices are Toman unless the asset declares another currency.
 */
export function quoteFromTgju(asset, entry) {
  if (!entry || typeof entry !== "object") return null;
  const price = parseMarketNum(entry.p);
  if (price == null || price <= 0) return null;

  let change = parseMarketNum(entry.d);
  let changePercent = parseMarketNum(entry.dp);
  if (changePercent != null && changePercent > 0 && entry.dt === "low") changePercent = -changePercent;
  if (change != null && change > 0 && entry.dt === "low") change = -change;

  const high = parseMarketNum(entry.h);
  const low = parseMarketNum(entry.l);

  return {
    symbol: asset.symbol,
    name: asset.name,
    category: asset.category,
    currency: asset.currency,
    unit: asset.unit || null,
    price: roundFor(asset, price),
    change: change == null ? null : roundFor(asset, change),
    change_percent: changePercent == null ? null : Number(changePercent.toFixed(2)),
    day_high: roundFor(asset, high),
    day_low: roundFor(asset, low),
    prev_price: change == null ? null : roundFor(asset, price - change),
    source: "tgju",
  };
}

/**
 * Map one Nobitex stats entry (prices in RIAL) to a quote in Toman.
 */
export function quoteFromNobitex(asset, stat) {
  if (!stat || typeof stat !== "object") return null;
  const latestRls = parseMarketNum(stat.latest);
  if (latestRls == null || latestRls <= 0) return null;
  const toman = (rls) => (rls == null ? null : Math.round(rls / 10));
  const price = toman(latestRls);
  const changePercent = parseMarketNum(stat.dayChange);
  const change = changePercent != null ? Math.round((price * changePercent) / 100) : null;
  return {
    symbol: asset.symbol,
    name: asset.name,
    category: asset.category,
    currency: asset.currency,
    unit: asset.unit || null,
    price,
    change,
    change_percent: changePercent == null ? null : Number(changePercent.toFixed(2)),
    day_high: toman(parseMarketNum(stat.dayHigh)),
    day_low: toman(parseMarketNum(stat.dayLow)),
    prev_price: change == null ? null : price - change,
    source: "nobitex",
  };
}

/** Reject absurd quotes (provider glitch / parsing bug). */
export function validateQuote(quote, prevPrice, maxAbsChangePercent = 25) {
  if (!quote || !Number.isFinite(quote.price) || quote.price <= 0) return false;
  if (quote.change_percent != null && Math.abs(quote.change_percent) > maxAbsChangePercent) return false;
  if (prevPrice != null && prevPrice > 0) {
    const implied = ((quote.price - prevPrice) / prevPrice) * 100;
    if (Math.abs(implied) > maxAbsChangePercent) return false;
  }
  return true;
}
