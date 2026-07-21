import { createHash, createHmac, randomUUID, timingSafeEqual } from "node:crypto";
import { Buffer } from "node:buffer";
import { createClient } from "@supabase/supabase-js";
import { McpServer, StreamableHttpTransport, type Ctx, type ToolCallResult } from "mcp-lite";
import { z } from "zod";
import type { Config } from "./config.ts";
import type { CloudMemoryPayload, MemoryMutation } from "./contracts.ts";
import { CloudMemoryService } from "./cloud-memory.ts";
import { bearerToken, CloudAuthError, type AuthenticatedUser, SupabaseTokenVerifier } from "./supabase-auth.ts";

const oauthSecuritySchemes = [{ type: "oauth2", scopes: ["openid", "email", "profile"] }] as const;

export function createMcpHttpHandler(
  config: Config,
  memory: CloudMemoryService,
  verifier: SupabaseTokenVerifier,
): (request: Request) => Promise<Response> {
  const grants = new McpGrantStore(config);
  const confirmations = new McpConfirmationCodec(config.installationTokenSecret);
  const server = createMcpServer(config, memory, grants, confirmations);
  const transport = new StreamableHttpTransport();
  const transportHandler = transport.bind(server);

  return async (request: Request): Promise<Response> => {
    const rpcMethod = await readRpcMethod(request);
    let user: AuthenticatedUser | undefined;
    const authorization = request.headers.get("authorization") ?? undefined;
    if (authorization) {
      try {
        user = await verifier.verify(bearerToken(authorization), true);
      } catch (error) {
        if (error instanceof CloudAuthError) return oauthUnauthorized(config, error.message);
        throw error;
      }
    }
    const authInfo = user
      ? {
          token: user.accessToken,
          scopes: ["openid", "email", "profile"],
          ...(typeof user.claims.exp === "number" ? { expiresAt: user.claims.exp } : {}),
          extra: { user },
        }
      : undefined;
    const response = await transportHandler(request, authInfo ? { authInfo } : undefined);
    return rpcMethod === "tools/list" ? injectOpenAiSecuritySchemes(response) : response;
  };
}

export function protectedResourceMetadata(config: Config): Record<string, unknown> {
  if (!config.mcpResourceUrl || !config.supabaseUrl) {
    throw new CloudAuthError("MCP OAuth metadata is not configured");
  }
  return {
    resource: config.mcpResourceUrl,
    authorization_servers: [config.supabaseUrl.replace(/\/$/, "")],
    scopes_supported: ["openid", "email", "profile"],
    bearer_methods_supported: ["header"],
    resource_documentation: `${config.mcpResourceUrl}/docs`,
  };
}

function createMcpServer(
  config: Config,
  memory: CloudMemoryService,
  grants: McpGrantStore,
  confirmations: McpConfirmationCodec,
): McpServer {
  const server = new McpServer({
    name: "Context Bubble Memory",
    version: "0.2.0",
    schemaAdapter: (schema) => z.toJSONSchema(schema as z.ZodType),
  });

  server.tool("search_memories", {
    title: "Search Context Bubble memories",
    description: "Searches only memories the user explicitly approved for shared AI access.",
    inputSchema: z.object({ query: z.string().min(1).max(2_000), limit: z.number().int().min(1).max(20).default(8) }),
    outputSchema: z.object({ memories: z.array(memoryOutputSchema) }),
    annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false },
    _meta: securityMeta(),
    handler: async ({ query, limit }, ctx) => withMcpUser(config, grants, ctx, false, async (user) => {
      const records = await memory.search(user, { query, limit });
      return structured({ memories: records.map(publicMemory) }, `${records.length} approved memories found.`);
    }),
  });

  server.tool("get_memory", {
    title: "Get one Context Bubble memory",
    description: "Reads one approved shared memory by its UUID.",
    inputSchema: z.object({ id: z.string().uuid() }),
    outputSchema: z.object({ memory: memoryOutputSchema.nullable() }),
    annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false },
    _meta: securityMeta(),
    handler: async ({ id }, ctx) => withMcpUser(config, grants, ctx, false, async (user) => {
      const record = await memory.get(user, id);
      return structured({ memory: record && !record.deleted ? publicMemory(record) : null }, record ? record.summary : "Memory not found.");
    }),
  });

  server.tool("list_recent_memories", {
    title: "List recent Context Bubble memories",
    description: "Lists recently updated approved shared memories.",
    inputSchema: z.object({ limit: z.number().int().min(1).max(20).default(10) }),
    outputSchema: z.object({ memories: z.array(memoryOutputSchema) }),
    annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false },
    _meta: securityMeta(),
    handler: async ({ limit }, ctx) => withMcpUser(config, grants, ctx, false, async (user) => {
      const records = await memory.listRecent(user, limit);
      return structured({ memories: records.map(publicMemory) }, `${records.length} recent approved memories.`);
    }),
  });

  server.tool("prepare_memory_change", {
    title: "Preview a Context Bubble memory change",
    description: "Prepares an exact create or update preview. This does not change memory. The returned confirmation token must be committed only after the user confirms the preview.",
    inputSchema: z.object({
      operation: z.enum(["CREATE", "UPDATE"]),
      id: z.string().uuid().optional(),
      type: z.string().min(1).max(80),
      summary: z.string().min(1).max(1_000),
      value: z.string().min(1).max(8_000),
      sensitivity: z.string().min(1).max(40).default("normal"),
      pinned: z.boolean().default(false),
      expiresAtEpochMs: z.number().int().positive().nullable().default(null),
    }),
    outputSchema: z.object({ preview: z.object({ operation: z.string(), memory: memoryOutputSchema }), confirmationToken: z.string(), expiresAtEpochMs: z.number() }),
    annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: false, openWorldHint: false },
    _meta: securityMeta(),
    handler: async (args, ctx) => withMcpUser(config, grants, ctx, true, async (user, clientId) => {
      const id = args.operation === "CREATE" ? (args.id ?? randomUUID()) : required(args.id, "UPDATE requires an id");
      const existing = args.operation === "UPDATE" ? await memory.get(user, id) : null;
      if (args.operation === "UPDATE" && (!existing || existing.deleted)) throw new Error("Memory to update was not found");
      const payload: CloudMemoryPayload = {
        id,
        type: args.type,
        summary: args.summary,
        value: args.value,
        sensitivity: args.sensitivity,
        pinned: args.pinned,
        createdAtEpochMs: existing?.createdAtEpochMs ?? Date.now(),
        expiresAtEpochMs: args.expiresAtEpochMs,
        sourcePackage: existing?.sourcePackage ?? "chatgpt-mcp",
      };
      const expiresAtEpochMs = Date.now() + 10 * 60 * 1_000;
      const prepared: PreparedChange = {
        userId: user.id,
        clientId,
        operation: "UPSERT",
        baseVersion: existing?.version ?? 0,
        payload,
        nonce: randomUUID(),
        expiresAtEpochMs,
        payloadHash: payloadHash(payload),
      };
      const confirmationToken = confirmations.sign(prepared);
      await grants.audit(user, id, "MCP_PREPARE", clientId);
      return structured(
        { preview: { operation: args.operation, memory: publicMemory({ ...payload, version: prepared.baseVersion, remoteUpdatedAtEpochMs: Date.now() }) }, confirmationToken, expiresAtEpochMs },
        `Preview only: ${args.operation.toLowerCase()} memory “${args.summary}”. Ask the user to confirm before calling commit_memory_change.`,
      );
    }),
  });

  server.tool("commit_memory_change", {
    title: "Commit a confirmed Context Bubble memory change",
    description: "Commits a previously prepared memory change. Call only after the user explicitly confirms the exact preview.",
    inputSchema: z.object({ confirmationToken: z.string().min(20).max(30_000) }),
    outputSchema: z.object({ status: z.string(), memoryId: z.string().uuid() }),
    annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: true, openWorldHint: false },
    _meta: securityMeta(),
    handler: async ({ confirmationToken }, ctx) => withMcpUser(config, grants, ctx, true, async (user, clientId) => {
      const prepared = confirmations.verify(confirmationToken, user.id, clientId, "UPSERT");
      await grants.consumeConfirmation(user, prepared);
      const mutation: MemoryMutation = {
        operation: "UPSERT",
        id: prepared.payload.id,
        baseVersion: prepared.baseVersion,
        payload: prepared.payload,
        idempotencyKey: prepared.nonce,
      };
      const result = await memory.sync(user, { cursor: 0, mutations: [mutation] });
      if (result.conflicts.length) throw new Error("Memory changed after preview. Prepare a new preview before retrying.");
      await grants.audit(user, prepared.payload.id, "MCP_COMMIT", clientId);
      return structured({ status: "committed", memoryId: prepared.payload.id }, `Saved approved shared memory “${prepared.payload.summary}”.`);
    }),
  });

  server.tool("prepare_memory_delete", {
    title: "Preview a Context Bubble memory deletion",
    description: "Prepares deletion of one approved memory without deleting it.",
    inputSchema: z.object({ id: z.string().uuid() }),
    outputSchema: z.object({ preview: z.object({ operation: z.literal("DELETE"), memory: memoryOutputSchema }), confirmationToken: z.string(), expiresAtEpochMs: z.number() }),
    annotations: { readOnlyHint: true, destructiveHint: true, idempotentHint: false, openWorldHint: false },
    _meta: securityMeta(),
    handler: async ({ id }, ctx) => withMcpUser(config, grants, ctx, true, async (user, clientId) => {
      const existing = await memory.get(user, id);
      if (!existing || existing.deleted) throw new Error("Memory to delete was not found");
      const expiresAtEpochMs = Date.now() + 10 * 60 * 1_000;
      const prepared: PreparedChange = {
        userId: user.id,
        clientId,
        operation: "DELETE",
        baseVersion: existing.version,
        payload: existing,
        nonce: randomUUID(),
        expiresAtEpochMs,
        payloadHash: payloadHash(existing),
      };
      await grants.audit(user, id, "MCP_PREPARE", clientId);
      return structured(
        { preview: { operation: "DELETE" as const, memory: publicMemory(existing) }, confirmationToken: confirmations.sign(prepared), expiresAtEpochMs },
        `Preview only: delete memory “${existing.summary}”. Ask the user to confirm before calling commit_memory_delete.`,
      );
    }),
  });

  server.tool("commit_memory_delete", {
    title: "Commit a confirmed Context Bubble memory deletion",
    description: "Deletes a previously previewed memory. Call only after the user explicitly confirms the exact deletion preview.",
    inputSchema: z.object({ confirmationToken: z.string().min(20).max(30_000) }),
    outputSchema: z.object({ status: z.string(), memoryId: z.string().uuid() }),
    annotations: { readOnlyHint: false, destructiveHint: true, idempotentHint: true, openWorldHint: false },
    _meta: securityMeta(),
    handler: async ({ confirmationToken }, ctx) => withMcpUser(config, grants, ctx, true, async (user, clientId) => {
      const prepared = confirmations.verify(confirmationToken, user.id, clientId, "DELETE");
      await grants.consumeConfirmation(user, prepared);
      const result = await memory.sync(user, {
        cursor: 0,
        mutations: [{
          operation: "DELETE",
          id: prepared.payload.id,
          baseVersion: prepared.baseVersion,
          payload: null,
          idempotencyKey: prepared.nonce,
        }],
      });
      if (result.conflicts.length) throw new Error("Memory changed after preview. Prepare a new deletion preview.");
      await grants.audit(user, prepared.payload.id, "MCP_COMMIT", clientId);
      return structured({ status: "deleted", memoryId: prepared.payload.id }, `Deleted memory “${prepared.payload.summary}”.`);
    }),
  });

  return server;
}

async function withMcpUser<T extends Record<string, unknown>>(
  config: Config,
  grants: McpGrantStore,
  ctx: Ctx,
  write: boolean,
  action: (user: AuthenticatedUser, clientId: string) => Promise<ToolCallResult<T>>,
): Promise<ToolCallResult<T>> {
  const user = ctx.authInfo?.extra?.user as AuthenticatedUser | undefined;
  if (!user) return authChallenge(config) as ToolCallResult<T>;
  const clientId = user.clientId ?? "chatgpt-oauth";
  const grant = await grants.authorize(user, clientId);
  if (write && grant !== "READ_WRITE") {
    return {
      content: [{ type: "text", text: "This connection has read-only access. Enable read/write access in Context Bubble." }],
      isError: true,
    } as ToolCallResult<T>;
  }
  return action(user, clientId);
}

class McpGrantStore {
  constructor(private readonly config: Config) {}

  async authorize(user: AuthenticatedUser, clientId: string): Promise<"READ_ONLY" | "READ_WRITE"> {
    const client = this.client(user);
    const { data, error } = await client
      .from("mcp_grants")
      .select("access_level,revoked_at")
      .eq("user_id", user.id)
      .eq("client_id", clientId)
      .maybeSingle();
    if (error) throw new Error(`MCP grant lookup failed: ${error.message}`);
    if (data?.revoked_at) throw new Error("This MCP connection was revoked in Context Bubble");
    if (!data) {
      const { error: insertError } = await client.from("mcp_grants").insert({
        user_id: user.id,
        client_id: clientId,
        access_level: "READ_WRITE",
        last_access_at: new Date().toISOString(),
      });
      if (insertError) throw new Error(`MCP grant creation failed: ${insertError.message}`);
      return "READ_WRITE";
    }
    await client.from("mcp_grants").update({ last_access_at: new Date().toISOString() })
      .eq("user_id", user.id).eq("client_id", clientId);
    return data.access_level === "READ_ONLY" ? "READ_ONLY" : "READ_WRITE";
  }

  async consumeConfirmation(user: AuthenticatedUser, prepared: PreparedChange): Promise<void> {
    const { error } = await this.client(user).from("mcp_confirmation_nonces").insert({
      user_id: user.id,
      nonce: prepared.nonce,
      client_id: prepared.clientId,
      operation: prepared.operation,
      expires_at: new Date(prepared.expiresAtEpochMs).toISOString(),
    });
    if (error?.code === "23505") throw new Error("Confirmation token was already used");
    if (error) throw new Error(`Confirmation could not be consumed: ${error.message}`);
  }

  async audit(user: AuthenticatedUser, memoryId: string, operation: "MCP_PREPARE" | "MCP_COMMIT", clientId: string): Promise<void> {
    const { error } = await this.client(user).from("memory_audit").insert({
      user_id: user.id,
      memory_id: memoryId,
      operation,
      actor_client_id: clientId,
      request_id: randomUUID(),
    });
    if (error) throw new Error(`MCP audit failed: ${error.message}`);
  }

  private client(user: AuthenticatedUser) {
    if (!this.config.supabaseUrl || !this.config.supabaseSecretKey) throw new Error("MCP server database access is not configured");
    return createClient(this.config.supabaseUrl, this.config.supabaseSecretKey, {
      auth: { persistSession: false, autoRefreshToken: false, detectSessionInUrl: false },
    });
  }
}

export type PreparedChange = {
  userId: string;
  clientId: string;
  operation: "UPSERT" | "DELETE";
  baseVersion: number;
  payload: CloudMemoryPayload;
  nonce: string;
  expiresAtEpochMs: number;
  payloadHash: string;
};

export class McpConfirmationCodec {
  constructor(private readonly secret: string) {}

  sign(prepared: PreparedChange): string {
    const encoded = Buffer.from(JSON.stringify(prepared)).toString("base64url");
    const signature = createHmac("sha256", this.secret).update(encoded).digest("base64url");
    return `${encoded}.${signature}`;
  }

  verify(token: string, userId: string, clientId: string, operation: PreparedChange["operation"]): PreparedChange {
    const [encoded, signature] = token.split(".");
    if (!encoded || !signature) throw new Error("Confirmation token is malformed");
    const expected = Buffer.from(createHmac("sha256", this.secret).update(encoded).digest("base64url"));
    const provided = Buffer.from(signature);
    if (expected.length !== provided.length || !timingSafeEqual(expected, provided)) throw new Error("Confirmation token signature is invalid");
    const prepared = JSON.parse(Buffer.from(encoded, "base64url").toString("utf8")) as PreparedChange;
    if (prepared.userId !== userId || prepared.clientId !== clientId || prepared.operation !== operation) {
      throw new Error("Confirmation token does not belong to this connection or operation");
    }
    if (prepared.expiresAtEpochMs <= Date.now()) throw new Error("Confirmation token expired; prepare a new preview");
    if (prepared.payloadHash !== payloadHash(prepared.payload)) throw new Error("Confirmation token payload changed after preview");
    return prepared;
  }
}

function payloadHash(payload: CloudMemoryPayload): string {
  return createHash("sha256").update(JSON.stringify(payload)).digest("hex");
}

const memoryOutputSchema = z.object({
  id: z.string().uuid(),
  type: z.string(),
  summary: z.string(),
  value: z.string(),
  sensitivity: z.string(),
  pinned: z.boolean(),
  createdAtEpochMs: z.number(),
  expiresAtEpochMs: z.number().nullable(),
  version: z.number(),
  updatedAtEpochMs: z.number(),
});

function publicMemory(memory: CloudMemoryPayload & { version: number; remoteUpdatedAtEpochMs: number }) {
  return {
    id: memory.id,
    type: memory.type,
    summary: memory.summary,
    value: memory.value,
    sensitivity: memory.sensitivity,
    pinned: memory.pinned,
    createdAtEpochMs: memory.createdAtEpochMs,
    expiresAtEpochMs: memory.expiresAtEpochMs ?? null,
    version: memory.version,
    updatedAtEpochMs: memory.remoteUpdatedAtEpochMs,
  };
}

function structured<T extends Record<string, unknown>>(value: T, text: string): ToolCallResult<T> {
  return { content: [{ type: "text", text }], structuredContent: value };
}

function securityMeta(): Record<string, unknown> {
  return {
    securitySchemes: oauthSecuritySchemes,
    "openai/securitySchemes": oauthSecuritySchemes,
  };
}

function authChallenge(config: Config): ToolCallResult {
  const metadataUrl = `${config.mcpResourceUrl ?? "https://api.contextbubble.app"}/.well-known/oauth-protected-resource`;
  const challenge = `Bearer resource_metadata="${metadataUrl}", error="insufficient_scope", error_description="Connect Context Bubble to continue"`;
  return {
    content: [{ type: "text", text: "Authentication required. Connect Context Bubble to continue." }],
    _meta: { "mcp/www_authenticate": [challenge] },
    isError: true,
  };
}

function oauthUnauthorized(config: Config, message: string): Response {
  const metadataUrl = `${config.mcpResourceUrl ?? "https://api.contextbubble.app"}/.well-known/oauth-protected-resource`;
  return new Response(JSON.stringify({ error: "invalid_token", error_description: message }), {
    status: 401,
    headers: {
      "Content-Type": "application/json",
      "WWW-Authenticate": `Bearer resource_metadata="${metadataUrl}", error="invalid_token", error_description="${message.replaceAll('"', "'")}"`,
    },
  });
}

async function readRpcMethod(request: Request): Promise<string | undefined> {
  if (request.method !== "POST") return undefined;
  try {
    const body = await request.clone().json() as { method?: unknown };
    return typeof body.method === "string" ? body.method : undefined;
  } catch {
    return undefined;
  }
}

async function injectOpenAiSecuritySchemes(response: Response): Promise<Response> {
  if (!response.headers.get("content-type")?.includes("application/json")) return response;
  const value = await response.clone().json() as { result?: { tools?: Array<Record<string, unknown>> } };
  for (const tool of value.result?.tools ?? []) tool.securitySchemes = oauthSecuritySchemes;
  const headers = new Headers(response.headers);
  headers.delete("content-length");
  return new Response(JSON.stringify(value), { status: response.status, statusText: response.statusText, headers });
}

function required<T>(value: T | undefined, message: string): T {
  if (value === undefined) throw new Error(message);
  return value;
}
