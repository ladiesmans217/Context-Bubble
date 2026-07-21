import { createHash } from "node:crypto";
import { createClient } from "@supabase/supabase-js";
import type { Config } from "./config.ts";

export type UsageMetric = "ASSIST_REQUEST" | "IMAGE_REQUEST" | "REALTIME_SECOND" | "TRANSCRIPTION_SECOND";

export class UsageQuotaService {
  constructor(private readonly config: Config) {}

  get configured(): boolean {
    return Boolean(this.config.supabaseUrl && this.config.supabaseSecretKey);
  }

  async reserve(identity: string, metric: UsageMetric, amount: number, limit: number): Promise<number> {
    if (!this.config.production || !this.config.supabaseUrl || !this.config.supabaseSecretKey) return amount;
    const client = createClient(this.config.supabaseUrl, this.config.supabaseSecretKey, {
      auth: { persistSession: false, autoRefreshToken: false, detectSessionInUrl: false },
    });
    const ownerKey = createHash("sha256").update(`context-bubble-usage\0${identity}`).digest("hex");
    const { data, error } = await client.rpc("reserve_context_bubble_usage", {
      requested_owner_key: ownerKey,
      requested_metric: metric,
      requested_amount: Math.max(1, Math.ceil(amount)),
      requested_limit: Math.max(0, Math.floor(limit)),
    });
    if (error?.message.includes("daily_usage_limit_exceeded")) throw new UsageQuotaError(metric);
    if (error) throw new UsageQuotaUnavailableError(error.message);
    return Number(data ?? amount);
  }
}

export class UsageQuotaError extends Error {
  constructor(readonly metric: UsageMetric) {
    super(`Daily ${friendlyMetric(metric)} limit reached. Try again after midnight UTC.`);
  }
}

export class UsageQuotaUnavailableError extends Error {}

function friendlyMetric(metric: UsageMetric): string {
  switch (metric) {
    case "ASSIST_REQUEST": return "assistant request";
    case "IMAGE_REQUEST": return "image generation";
    case "REALTIME_SECOND": return "Realtime voice";
    case "TRANSCRIPTION_SECOND": return "transcription";
  }
}
