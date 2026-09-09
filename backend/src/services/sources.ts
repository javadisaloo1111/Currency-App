import type { PriceProvider, SourceState, SourceStatus } from "../types.js";

interface SourceRecord {
  name: string;
  priority: number;
  status: SourceState;
  lastSuccessAt: string | null;
  lastError: string | null;
  errorCount: number;
  responseTimes: number[];
}

/**
 * Tracks provider health: status, last success, rolling error count and
 * response-time stats. Powers /api/v1/health and the admin source dashboard.
 */
export class SourceMonitor {
  private readonly records = new Map<string, SourceRecord>();

  register(provider: PriceProvider): void {
    if (!this.records.has(provider.name)) {
      this.records.set(provider.name, {
        name: provider.name,
        priority: provider.priority,
        status: "unknown",
        lastSuccessAt: null,
        lastError: null,
        errorCount: 0,
        responseTimes: [],
      });
    }
  }

  recordSuccess(name: string, responseMs: number, at = new Date()): void {
    const rec = this.get(name);
    rec.status = "up";
    rec.lastSuccessAt = at.toISOString();
    rec.lastError = null;
    rec.errorCount = Math.max(0, rec.errorCount - 1); // errors heal gradually
    rec.responseTimes.push(responseMs);
    if (rec.responseTimes.length > 20) rec.responseTimes.shift();
  }

  recordFailure(name: string, error: unknown, at = new Date()): void {
    const rec = this.get(name);
    rec.status = "down";
    rec.lastError = error instanceof Error ? error.message : String(error);
    rec.errorCount += 1;
    void at;
  }

  private get(name: string): SourceRecord {
    let rec = this.records.get(name);
    if (!rec) {
      rec = {
        name,
        priority: 999,
        status: "unknown",
        lastSuccessAt: null,
        lastError: null,
        errorCount: 0,
        responseTimes: [],
      };
      this.records.set(name, rec);
    }
    return rec;
  }

  list(): SourceStatus[] {
    return [...this.records.values()]
      .sort((a, b) => a.priority - b.priority)
      .map((r) => ({
        name: r.name,
        priority: r.priority,
        status: r.status,
        last_success_at: r.lastSuccessAt,
        last_error: r.lastError,
        error_count: r.errorCount,
        avg_response_ms:
          r.responseTimes.length > 0
            ? Math.round(r.responseTimes.reduce((a, b) => a + b, 0) / r.responseTimes.length)
            : null,
      }));
  }
}
