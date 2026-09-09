import type { AssetQuote, PriceProvider, QuoteCore } from "../types.js";
import { ASSETS } from "../../../shared/assets.mjs";
import { quoteFromTgju } from "../../../shared/quotes.mjs";
import { fetchJson } from "../http.js";

/**
 * TGJU provider — the primary domestic price source (gold, coins, currencies).
 *
 * Multiple ajax.json mirror hosts are tried in order; the first host that
 * answers with a usable payload wins. Hosts are configured via TGJU_HOSTS so
 * new mirrors can be added without code changes.
 */
export class TgjuProvider implements PriceProvider {
  readonly name = "tgju";
  readonly priority = 1;

  constructor(
    private readonly hosts: string[],
    private readonly timeoutMs: number,
  ) {}

  async fetchAll(): Promise<AssetQuote[]> {
    if (this.hosts.length === 0) throw new Error("tgju: no hosts configured");
    let lastError: unknown = null;
    for (const host of this.hosts) {
      try {
        const payload = await fetchJson<any>(`${host.replace(/\/$/, "")}/ajax.json`, this.timeoutMs);
        const current = payload && typeof payload === "object" ? payload.current : null;
        if (!current || typeof current !== "object") throw new Error("tgju: unexpected payload shape");
        const quotes = mapTgjuCurrent(current);
        if (quotes.length === 0) throw new Error("tgju: no mapped quotes in payload");
        return quotes;
      } catch (err) {
        lastError = err;
      }
    }
    throw new Error(`tgju: all hosts failed (${String(lastError)})`);
  }
}

/** Map the raw `current` object of a TGJU ajax.json payload into quotes. */
export function mapTgjuCurrent(current: Record<string, any>): AssetQuote[] {
  const now = new Date().toISOString();
  const quotes: AssetQuote[] = [];
  for (const asset of ASSETS) {
    if (!asset.tgjuKeys) continue;
    for (const key of asset.tgjuKeys) {
      const entry = current[key];
      const core = quoteFromTgju(asset, entry) as unknown as QuoteCore | null;
      if (core) {
        quotes.push({ ...core, updated_at: now, is_stale: false });
        break;
      }
    }
  }
  return quotes;
}
