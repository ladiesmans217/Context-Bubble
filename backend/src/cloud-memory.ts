import { createClient, type SupabaseClient } from "@supabase/supabase-js";
import { createHash, randomUUID } from "node:crypto";
import type { Config } from "./config.ts";
import type {
  CloudMemoryPayload,
  CloudMemoryRecord,
  MemoryConflict,
  MemoryMutation,
  MemorySearchRequest,
  MemorySyncRequest,
  MemorySyncResponse,
} from "./contracts.ts";
import { CloudMemoryCrypto } from "./cloud-crypto.ts";
import type { AuthenticatedUser } from "./supabase-auth.ts";

export type EmbeddingProvider = {
  embed(text: string, identity: string): Promise<number[]>;
  readonly model: string;
};

type MemoryRow = {
  id: string;
  user_id: string;
  type: string;
  payload_ciphertext: string;
  payload_nonce: string;
  key_version: number;
  content_hmac: string;
  source_package: string | null;
  sensitivity: string;
  pinned: boolean;
  expires_at: string | null;
  embedding_model: string | null;
  version: number;
  sync_seq: number;
  deleted: boolean;
  created_at: string;
  updated_at: string;
  similarity?: number;
};

export class CloudMemoryService {
  private readonly crypto: CloudMemoryCrypto;

  constructor(
    private readonly config: Config,
    private readonly embeddings?: EmbeddingProvider,
  ) {
    this.crypto = new CloudMemoryCrypto(config.cloudMemoryMasterKeys, config.currentCloudMemoryKeyVersion);
  }

  get configured(): boolean {
    return Boolean(this.config.supabaseUrl && this.config.supabasePublishableKey && this.crypto.configured);
  }

  async sync(user: AuthenticatedUser, request: MemorySyncRequest): Promise<MemorySyncResponse> {
    const client = this.client(user);
    const conflicts: MemoryConflict[] = [];
    for (const mutation of request.mutations) {
      const conflict = await this.applyMutation(client, user, mutation);
      if (conflict) conflicts.push(conflict);
    }

    const { data, error } = await client
      .from("memory_items")
      .select("*")
      .eq("user_id", user.id)
      .gt("sync_seq", request.cursor)
      .order("sync_seq", { ascending: true })
      .limit(200);
    if (error) throw new CloudMemoryError("cloud_read_failed", error.message);
    const changes = await Promise.all(((data ?? []) as MemoryRow[]).map((row) => this.toRecord(user.id, row)));
    const nextCursor = changes.reduce((highest, change) => Math.max(highest, change.syncSequence), request.cursor);
    return { requestId: randomUUID(), nextCursor, changes, conflicts };
  }

  async search(user: AuthenticatedUser, request: MemorySearchRequest): Promise<CloudMemoryRecord[]> {
    const client = this.client(user);
    let rows: MemoryRow[];
    if (this.embeddings) {
      const embedding = normalize(await this.embeddings.embed(request.query, user.id));
      if (embedding.length !== 384) throw new CloudMemoryError("embedding_invalid", "Embedding must have 384 dimensions");
      const serviceRequest = Boolean(user.clientId);
      const { data, error } = await client.rpc(serviceRequest ? "match_memory_items_for_user" : "match_memory_items", {
        ...(serviceRequest ? { requested_user_id: user.id } : {}),
        query_embedding: vectorLiteral(embedding),
        match_count: Math.max(request.limit ?? 8, 20),
        requested_model: this.embeddings.model,
      });
      if (error) throw new CloudMemoryError("memory_search_failed", error.message);
      rows = (data ?? []) as MemoryRow[];
    } else {
      const { data, error } = await client
        .from("memory_items")
        .select("*")
        .eq("user_id", user.id)
        .eq("deleted", false)
        .order("pinned", { ascending: false })
        .order("updated_at", { ascending: false })
        .limit(20);
      if (error) throw new CloudMemoryError("memory_search_failed", error.message);
      rows = (data ?? []) as MemoryRow[];
    }
    const records = await Promise.all(rows.map((row) => this.toRecord(user.id, row)));
    const queryTerms = tokenize(request.query);
    return records
      .filter((record) => !record.deleted && (!record.expiresAtEpochMs || record.expiresAtEpochMs > Date.now() || record.pinned))
      .sort((left, right) => rerank(right, queryTerms) - rerank(left, queryTerms))
      .slice(0, request.limit ?? 8);
  }

  async listRecent(user: AuthenticatedUser, limit = 10): Promise<CloudMemoryRecord[]> {
    const { data, error } = await this.client(user)
      .from("memory_items")
      .select("*")
      .eq("user_id", user.id)
      .eq("deleted", false)
      .order("pinned", { ascending: false })
      .order("updated_at", { ascending: false })
      .limit(Math.max(1, Math.min(limit, 20)));
    if (error) throw new CloudMemoryError("cloud_read_failed", error.message);
    return Promise.all(((data ?? []) as MemoryRow[]).map((row) => this.toRecord(user.id, row)));
  }

  async get(user: AuthenticatedUser, id: string): Promise<CloudMemoryRecord | null> {
    const { data, error } = await this.client(user)
      .from("memory_items")
      .select("*")
      .eq("user_id", user.id)
      .eq("id", id)
      .maybeSingle();
    if (error) throw new CloudMemoryError("cloud_read_failed", error.message);
    return data ? this.toRecord(user.id, data as MemoryRow) : null;
  }

  async exportAll(user: AuthenticatedUser): Promise<CloudMemoryRecord[]> {
    const { data, error } = await this.client(user)
      .from("memory_items")
      .select("*")
      .eq("user_id", user.id)
      .order("updated_at", { ascending: false })
      .limit(10_000);
    if (error) throw new CloudMemoryError("cloud_read_failed", error.message);
    return Promise.all(((data ?? []) as MemoryRow[]).map((row) => this.toRecord(user.id, row)));
  }

  async mutate(user: AuthenticatedUser, mutation: MemoryMutation): Promise<CloudMemoryRecord | null> {
    const conflict = await this.applyMutation(this.client(user), user, mutation);
    if (conflict) throw new CloudMemoryError("memory_conflict", "Cloud memory changed; synchronize and review the conflict");
    return mutation.operation === "DELETE" ? null : this.get(user, mutation.id);
  }

  private async applyMutation(
    client: SupabaseClient,
    user: AuthenticatedUser,
    mutation: MemoryMutation,
  ): Promise<MemoryConflict | null> {
    const cached = await this.idempotencyResponse(client, user.id, mutation);
    if (cached) return cached;
    const current = await this.fetchRow(client, user.id, mutation.id);
    if ((current?.version ?? 0) !== mutation.baseVersion) {
      if (!current) throw new CloudMemoryError("memory_conflict", "Cloud memory no longer exists");
      const conflict = {
        id: mutation.id,
        localBaseVersion: mutation.baseVersion,
        cloud: await this.toRecord(user.id, current),
      };
      await this.storeIdempotency(client, user.id, mutation, conflict);
      return conflict;
    }

    if (mutation.operation === "DELETE") {
      if (!current) {
        await this.storeIdempotency(client, user.id, mutation, null);
        return null;
      }
      const { data, error } = await client
        .from("memory_items")
        .update({ deleted: true, version: current.version + 1 })
        .eq("user_id", user.id)
        .eq("id", mutation.id)
        .eq("version", mutation.baseVersion)
        .select("id");
      if (error) throw new CloudMemoryError("cloud_write_failed", error.message);
      if (!data?.length) return this.conflictAfterRace(client, user, mutation);
      await this.audit(client, user.id, mutation.id, "DELETE");
      await this.storeIdempotency(client, user.id, mutation, null);
      return null;
    }

    const payload = mutation.payload;
    if (!payload) throw new CloudMemoryError("invalid_request", "UPSERT mutation requires a payload");
    const encrypted = await this.crypto.encrypt(user.id, payload);
    const embedding = this.embeddings
      ? normalize(await this.embeddings.embed(canonicalEmbeddingInput(payload), user.id))
      : undefined;
    if (embedding && embedding.length !== 384) throw new CloudMemoryError("embedding_invalid", "Embedding must have 384 dimensions");
    const values = {
      id: payload.id,
      user_id: user.id,
      type: payload.type,
      payload_ciphertext: encrypted.ciphertext,
      payload_nonce: encrypted.nonce,
      key_version: encrypted.keyVersion,
      content_hmac: encrypted.contentHmac,
      source_package: payload.sourcePackage ?? null,
      sensitivity: payload.sensitivity,
      pinned: payload.pinned,
      expires_at: payload.expiresAtEpochMs ? new Date(payload.expiresAtEpochMs).toISOString() : null,
      embedding: embedding ? vectorLiteral(embedding) : null,
      embedding_model: embedding ? this.embeddings?.model : null,
      embedding_input_hash: createHash("sha256").update(canonicalEmbeddingInput(payload)).digest("hex"),
      version: mutation.baseVersion + 1,
      deleted: false,
    };
    if (!current) {
      const { error } = await client.from("memory_items").insert(values);
      if (error?.code === "23505") return this.conflictAfterRace(client, user, mutation);
      if (error) throw new CloudMemoryError("cloud_write_failed", error.message);
      await this.audit(client, user.id, mutation.id, "CREATE");
    } else {
      const { data, error } = await client
        .from("memory_items")
        .update(values)
        .eq("user_id", user.id)
        .eq("id", mutation.id)
        .eq("version", mutation.baseVersion)
        .select("id");
      if (error) throw new CloudMemoryError("cloud_write_failed", error.message);
      if (!data?.length) return this.conflictAfterRace(client, user, mutation);
      await this.audit(client, user.id, mutation.id, "UPDATE");
    }
    await this.storeIdempotency(client, user.id, mutation, null);
    return null;
  }

  private async conflictAfterRace(client: SupabaseClient, user: AuthenticatedUser, mutation: MemoryMutation): Promise<MemoryConflict> {
    const row = await this.fetchRow(client, user.id, mutation.id);
    if (!row) throw new CloudMemoryError("memory_conflict", "Cloud memory changed during synchronization");
    return { id: mutation.id, localBaseVersion: mutation.baseVersion, cloud: await this.toRecord(user.id, row) };
  }

  private async fetchRow(client: SupabaseClient, userId: string, id: string): Promise<MemoryRow | null> {
    const { data, error } = await client.from("memory_items").select("*").eq("user_id", userId).eq("id", id).maybeSingle();
    if (error) throw new CloudMemoryError("cloud_read_failed", error.message);
    return data as MemoryRow | null;
  }

  private async toRecord(userId: string, row: MemoryRow): Promise<CloudMemoryRecord> {
    const payload = await this.crypto.decrypt(userId, {
      ciphertext: row.payload_ciphertext,
      nonce: row.payload_nonce,
      keyVersion: row.key_version,
    });
    return {
      ...payload,
      version: row.version,
      syncSequence: Number(row.sync_seq),
      deleted: row.deleted,
      remoteUpdatedAtEpochMs: Date.parse(row.updated_at),
      embeddingModel: row.embedding_model,
    };
  }

  private client(user: AuthenticatedUser): SupabaseClient {
    if (!this.config.supabaseUrl || !this.config.supabasePublishableKey || !this.crypto.configured) {
      throw new CloudMemoryError("not_configured", "Encrypted cloud memory is not configured");
    }
    if (user.clientId && !this.config.supabaseSecretKey) throw new CloudMemoryError("not_configured", "MCP server access is not configured");
    const key = user.clientId ? this.config.supabaseSecretKey! : this.config.supabasePublishableKey;
    return createClient(this.config.supabaseUrl, key, {
      auth: { persistSession: false, autoRefreshToken: false, detectSessionInUrl: false },
      ...(user.clientId ? {} : { global: { headers: { Authorization: `Bearer ${user.accessToken}` } } }),
    });
  }

  private async audit(client: SupabaseClient, userId: string, memoryId: string, operation: string): Promise<void> {
    const { error } = await client.from("memory_audit").insert({
      user_id: userId,
      memory_id: memoryId,
      operation,
      request_id: randomUUID(),
    });
    if (error) throw new CloudMemoryError("audit_failed", error.message);
  }

  private async idempotencyResponse(
    client: SupabaseClient,
    userId: string,
    mutation: MemoryMutation,
  ): Promise<MemoryConflict | null | undefined> {
    const { data, error } = await client
      .from("idempotency_records")
      .select("request_hash,response")
      .eq("user_id", userId)
      .eq("idempotency_key", mutation.idempotencyKey)
      .gt("expires_at", new Date().toISOString())
      .maybeSingle();
    if (error) throw new CloudMemoryError("cloud_read_failed", error.message);
    if (!data) return undefined;
    const requestHash = mutationHash(mutation);
    if (data.request_hash !== requestHash) throw new CloudMemoryError("idempotency_mismatch", "Idempotency key was reused with different data");
    return (data.response as MemoryConflict | null) ?? null;
  }

  private async storeIdempotency(
    client: SupabaseClient,
    userId: string,
    mutation: MemoryMutation,
    response: MemoryConflict | null,
  ): Promise<void> {
    const { error } = await client.from("idempotency_records").upsert({
      user_id: userId,
      idempotency_key: mutation.idempotencyKey,
      operation: `MEMORY_${mutation.operation}`,
      request_hash: mutationHash(mutation),
      response,
      expires_at: new Date(Date.now() + 24 * 60 * 60 * 1_000).toISOString(),
    });
    if (error) throw new CloudMemoryError("idempotency_write_failed", error.message);
  }
}

function mutationHash(mutation: MemoryMutation): string {
  return createHash("sha256").update(JSON.stringify(mutation)).digest("hex");
}

function canonicalEmbeddingInput(payload: CloudMemoryPayload): string {
  return `${payload.type}\n${payload.summary}\n${payload.value}`.slice(0, 8_000);
}

function vectorLiteral(values: number[]): string {
  return `[${values.map((value) => Number(value.toFixed(8))).join(",")}]`;
}

function normalize(values: number[]): number[] {
  const magnitude = Math.sqrt(values.reduce((sum, value) => sum + value * value, 0));
  return magnitude > 0 ? values.map((value) => value / magnitude) : values;
}

function tokenize(value: string): Set<string> {
  return new Set(value.toLocaleLowerCase().split(/[^\p{L}\p{N}]+/u).filter((term) => term.length > 1));
}

function rerank(record: CloudMemoryRecord, terms: Set<string>): number {
  const haystack = `${record.summary} ${record.value}`.toLocaleLowerCase();
  let lexical = 0;
  for (const term of terms) if (haystack.includes(term)) lexical += 1;
  const recency = Math.max(0, 1 - (Date.now() - record.remoteUpdatedAtEpochMs) / (365 * 24 * 60 * 60 * 1_000));
  return lexical * 10 + (record.pinned ? 5 : 0) + recency;
}

export class CloudMemoryError extends Error {
  constructor(readonly code: string, message: string) { super(message); }
}
