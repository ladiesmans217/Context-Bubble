import { createClient } from "@supabase/supabase-js";
import type { Config } from "./config.ts";
import type { AuthenticatedUser } from "./supabase-auth.ts";

export type McpGrant = {
  clientId: string;
  accessLevel: "READ_ONLY" | "READ_WRITE";
  revoked: boolean;
  lastAccessAt: string | null;
  createdAt: string;
};

export class McpGrantService {
  constructor(private readonly config: Config) {}

  async list(user: AuthenticatedUser): Promise<McpGrant[]> {
    const { data, error } = await this.client(user)
      .from("mcp_grants")
      .select("client_id,access_level,revoked_at,last_access_at,created_at")
      .eq("user_id", user.id)
      .order("last_access_at", { ascending: false, nullsFirst: false });
    if (error) throw new McpGrantError("grant_read_failed", error.message);
    return (data ?? []).map((row) => ({
      clientId: String(row.client_id),
      accessLevel: row.access_level === "READ_ONLY" ? "READ_ONLY" : "READ_WRITE",
      revoked: Boolean(row.revoked_at),
      lastAccessAt: typeof row.last_access_at === "string" ? row.last_access_at : null,
      createdAt: String(row.created_at),
    }));
  }

  async setAccess(user: AuthenticatedUser, clientId: string, accessLevel: "READ_ONLY" | "READ_WRITE"): Promise<void> {
    validateClientId(clientId);
    const { error } = await this.client(user).from("mcp_grants")
      .update({ access_level: accessLevel, revoked_at: null })
      .eq("user_id", user.id)
      .eq("client_id", clientId);
    if (error) throw new McpGrantError("grant_write_failed", error.message);
  }

  async revoke(user: AuthenticatedUser, clientId: string): Promise<void> {
    validateClientId(clientId);
    const client = this.client(user);
    const { error } = await client.from("mcp_grants")
      .update({ revoked_at: new Date().toISOString() })
      .eq("user_id", user.id)
      .eq("client_id", clientId);
    if (error) throw new McpGrantError("grant_write_failed", error.message);
    await client.from("memory_audit").insert({
      user_id: user.id,
      memory_id: crypto.randomUUID(),
      operation: "MCP_REVOKE",
      actor_client_id: clientId,
      request_id: crypto.randomUUID(),
    });
  }

  async recentChanges(user: AuthenticatedUser, limit = 20) {
    const { data, error } = await this.client(user)
      .from("memory_audit")
      .select("memory_id,operation,actor_client_id,created_at")
      .eq("user_id", user.id)
      .in("operation", ["MCP_PREPARE", "MCP_COMMIT", "MCP_REVOKE"])
      .order("created_at", { ascending: false })
      .limit(Math.max(1, Math.min(limit, 100)));
    if (error) throw new McpGrantError("grant_read_failed", error.message);
    return data ?? [];
  }

  private client(user: AuthenticatedUser) {
    if (!this.config.supabaseUrl || !this.config.supabasePublishableKey) throw new McpGrantError("not_configured", "Supabase is not configured");
    return createClient(this.config.supabaseUrl, this.config.supabasePublishableKey, {
      auth: { persistSession: false, autoRefreshToken: false, detectSessionInUrl: false },
      global: { headers: { Authorization: `Bearer ${user.accessToken}` } },
    });
  }
}

export class McpGrantError extends Error {
  constructor(readonly code: string, message: string) { super(message); }
}

function validateClientId(value: string) {
  if (!value || value.length > 500) throw new McpGrantError("invalid_client", "MCP client ID is invalid");
}
