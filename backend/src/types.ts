export type Category = "gold" | "coin" | "currency" | "crypto";

/** Core quote fields without freshness metadata (returned by shared mappers). */
export interface QuoteCore {
  symbol: string;
  name: string;
  category: Category;
  /** "TOMAN" for domestic prices, "USD" for global ounce. */
  currency: string;
  unit: string | null;
  price: number;
  change: number | null;
  change_percent: number | null;
  day_high: number | null;
  day_low: number | null;
  prev_price: number | null;
  source: string;
}

export interface AssetQuote extends QuoteCore {
  updated_at: string;
  is_stale: boolean;
}

export interface PriceProvider {
  readonly name: string;
  readonly priority: number;
  /** Fetch all quotes this provider can supply. Throws on total failure. */
  fetchAll(): Promise<AssetQuote[]>;
}

export type SourceState = "up" | "down" | "unknown";

export interface SourceStatus {
  name: string;
  priority: number;
  status: SourceState;
  last_success_at: string | null;
  last_error: string | null;
  error_count: number;
  avg_response_ms: number | null;
}

export interface HistoryPoint {
  t: number;
  p: number;
}
