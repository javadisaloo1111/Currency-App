import type { AssetQuote, PriceProvider, QuoteCore } from "../types.js";
import { ASSETS } from "../../../shared/assets.mjs";
import { quoteFromNobitex } from "../../../shared/quotes.mjs";
import { fetchJson } from "../http.js";

export interface NobitexStat {
  latest?: string;
  dayHigh?: string;
  dayLow?: string;
  dayChange?: string;
}

/**
 * Nobitex provider — public market stats for crypto pairs (prices in RIAL).
 * No API key required. Supplies Tether & Bitcoin quotes in Toman.
 */
export class NobitexProvider implements PriceProvider {
  readonly name = "nobitex";
  readonly priority = 2;

  constructor(
    private readonly statsUrl = "https://api.nobitex.ir/market/stats",
    private readonly timeoutMs = 12_000,
    private readonly enabled = true,
  ) {}

  async fetchAll(): Promise<AssetQuote[]> {
    if (!this.enabled) throw new Error("nobitex: disabled");
    const pairs = ASSETS.filter((a) => a.nobitex);
    if (pairs.length === 0) return [];
    const payload = await fetchJson<{ status?: string; stats?: Record<string, NobitexStat> }>(
      this.statsUrl,
      this.timeoutMs,
      {
        method: "POST",
        body: JSON.stringify({ srcCurrency: pairs.map((a) => a.nobitex).join(","), dstCurrency: "rls" }),
      },
    );
    if (payload.status !== "ok" || !payload.stats) throw new Error("nobitex: unexpected payload");
    const now = new Date().toISOString();
    const quotes: AssetQuote[] = [];
    for (const asset of pairs) {
      const stat = payload.stats[`${asset.nobitex}-rls`] || payload.stats[`${asset.nobitex}-rlt`];
      const core = quoteFromNobitex(asset, stat) as unknown as QuoteCore | null;
      if (core) quotes.push({ ...core, updated_at: now, is_stale: false });
    }
    return quotes;
  }
}
