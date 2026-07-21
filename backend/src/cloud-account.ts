import { createHmac, timingSafeEqual } from "node:crypto";
import { createClient } from "@supabase/supabase-js";
import type { Config } from "./config.ts";
import type { CloudBlobService } from "./cloud-blob.ts";
import type { CloudMemoryService } from "./cloud-memory.ts";
import type { AuthenticatedUser } from "./supabase-auth.ts";
import { decodeBase64Url, encodeBase64Url } from "./cloud-crypto.ts";

type DeleteClaim = { userId: string; operation: "DELETE_CLOUD_ACCOUNT"; expiresAt: number; nonce: string };

export class CloudAccountService {
  constructor(
    private readonly config: Config,
    private readonly memories: CloudMemoryService,
    private readonly blobs: CloudBlobService,
  ) {}

  async dashboard(user: AuthenticatedUser) {
    const client = this.userClient(user);
    const [storage, memories, grants, integrations] = await Promise.all([
      this.blobs.usage(user),
      client.from("memory_items").select("id", { count: "exact", head: true }).eq("user_id", user.id).eq("deleted", false),
      client.from("mcp_grants").select("client_id", { count: "exact", head: true }).eq("user_id", user.id).is("revoked_at", null),
      client.from("user_integration_tokens").select("provider", { count: "exact", head: true }).eq("user_id", user.id).is("revoked_at", null),
    ]);
    for (const result of [memories, grants, integrations]) if (result.error) throw new CloudAccountError("dashboard_failed", result.error.message);
    return {
      storage,
      memories: memories.count ?? 0,
      activeMcpGrants: grants.count ?? 0,
      activeIntegrations: integrations.count ?? 0,
    };
  }

  async export(user: AuthenticatedUser) {
    const client = this.userClient(user);
    const [memories, audits, grants, blobs] = await Promise.all([
      this.memories.exportAll(user),
      client.from("memory_audit").select("memory_id,operation,actor_client_id,created_at").eq("user_id", user.id).order("created_at", { ascending: false }).limit(10_000),
      client.from("mcp_grants").select("client_id,access_level,revoked_at,last_access_at,created_at").eq("user_id", user.id),
      client.from("cloud_blobs").select("id,kind,mime_type,encrypted_size_bytes,created_at,expires_at,pinned,deleted_at").eq("user_id", user.id),
    ]);
    for (const result of [audits, grants, blobs]) if (result.error) throw new CloudAccountError("export_failed", result.error.message);
    return {
      format: "context-bubble-export-v1",
      exportedAt: new Date().toISOString(),
      userId: user.id,
      memories,
      audit: audits.data ?? [],
      mcpGrants: grants.data ?? [],
      cloudBlobs: blobs.data ?? [],
    };
  }

  prepareDelete(user: AuthenticatedUser): { confirmationToken: string; expiresAt: string; preview: string } {
    const claim: DeleteClaim = {
      userId: user.id,
      operation: "DELETE_CLOUD_ACCOUNT",
      expiresAt: Date.now() + 10 * 60_000,
      nonce: crypto.randomUUID(),
    };
    return {
      confirmationToken: signClaim(claim, this.config.installationTokenSecret),
      expiresAt: new Date(claim.expiresAt).toISOString(),
      preview: "Permanently delete every shared cloud memory, generated cloud image, MCP grant, Calendar connection, audit record, and Context Bubble cloud account. Local phone data is not deleted.",
    };
  }

  async commitDelete(user: AuthenticatedUser, token: string): Promise<void> {
    const claim = verifyClaim(token, this.config.installationTokenSecret);
    if (claim.userId !== user.id || claim.operation !== "DELETE_CLOUD_ACCOUNT") throw new CloudAccountError("invalid_confirmation", "Deletion confirmation does not match this account");
    await this.blobs.deleteAll(user);
    if (!this.config.supabaseUrl || !this.config.supabaseSecretKey) throw new CloudAccountError("not_configured", "Cloud account administration is not configured");
    const admin = createClient(this.config.supabaseUrl, this.config.supabaseSecretKey, { auth: { persistSession: false } });
    const { error } = await admin.auth.admin.deleteUser(user.id);
    if (error) throw new CloudAccountError("delete_failed", error.message);
  }

  private userClient(user: AuthenticatedUser) {
    if (!this.config.supabaseUrl || !this.config.supabasePublishableKey) throw new CloudAccountError("not_configured", "Cloud account is not configured");
    return createClient(this.config.supabaseUrl, this.config.supabasePublishableKey, {
      auth: { persistSession: false, autoRefreshToken: false, detectSessionInUrl: false },
      global: { headers: { Authorization: `Bearer ${user.accessToken}` } },
    });
  }
}

function signClaim(claim: DeleteClaim, secret: string): string {
  const payload = encodeBase64Url(new TextEncoder().encode(JSON.stringify(claim)));
  const signature = encodeBase64Url(createHmac("sha256", secret).update(payload).digest());
  return `${payload}.${signature}`;
}

function verifyClaim(token: string, secret: string): DeleteClaim {
  const [payload, signature, extra] = token.split(".");
  if (!payload || !signature || extra) throw new CloudAccountError("invalid_confirmation", "Deletion confirmation is invalid");
  const expected = createHmac("sha256", secret).update(payload).digest();
  const supplied = decodeBase64Url(signature);
  if (supplied.length !== expected.length || !timingSafeEqual(supplied, expected)) throw new CloudAccountError("invalid_confirmation", "Deletion confirmation is invalid");
  const claim = JSON.parse(new TextDecoder().decode(decodeBase64Url(payload))) as DeleteClaim;
  if (!claim.nonce || claim.expiresAt < Date.now()) throw new CloudAccountError("expired_confirmation", "Deletion confirmation expired");
  return claim;
}

export class CloudAccountError extends Error {
  constructor(readonly code: string, message: string) { super(message); }
}
