import { Hono } from "hono";
import { cors } from "hono/cors";
import { streamSSE } from "hono/streaming";
import type { Config } from "./config.ts";
import { AuthError, authenticateHeaders, createInstallationToken } from "./auth.ts";
import { parseAssistRequest, parseMemorySearchRequest, parseMemorySyncRequest, ValidationError } from "./contracts.ts";
import { ConfigurationError, OpenAiError, OpenAiGateway, ProviderTransportError } from "./openai.ts";
import { packagePolicy } from "./policy.ts";
import { CloudMemoryError, CloudMemoryService } from "./cloud-memory.ts";
import { CloudAuthError, SupabaseTokenVerifier } from "./supabase-auth.ts";
import { protectedResourceMetadata } from "./mcp.ts";
import { CloudBlobError, CloudBlobService } from "./cloud-blob.ts";
import { McpGrantError, McpGrantService } from "./mcp-grants.ts";
import { CalendarError, CalendarService, type CalendarEventInput } from "./calendar.ts";
import { IntegrityError, PlayIntegrityVerifier } from "./play-integrity.ts";
import { UsageQuotaError, UsageQuotaService, UsageQuotaUnavailableError } from "./usage-quota.ts";
import { CloudAccountError, CloudAccountService } from "./cloud-account.ts";

export type HonoDependencies = {
  openAi?: OpenAiGateway;
  cloudMemory?: CloudMemoryService;
  tokenVerifier?: SupabaseTokenVerifier;
};

type Variables = { requestId: string };

export function createHonoApi(config: Config, dependencies: HonoDependencies = {}): Hono<{ Variables: Variables }> {
  const app = new Hono<{ Variables: Variables }>();
  const openAi = dependencies.openAi ?? new OpenAiGateway(config);
  const cloudMemory = dependencies.cloudMemory ?? new CloudMemoryService(
    config,
    config.features.semanticSearch && config.embeddingProvider === "openai" && config.openAiApiKey ? openAi : undefined,
  );
  const verifier = dependencies.tokenVerifier ?? new SupabaseTokenVerifier(config);
  const cloudBlobs = new CloudBlobService(config);
  const mcpGrants = new McpGrantService(config);
  const calendar = new CalendarService(config);
  const integrity = new PlayIntegrityVerifier(config);
  const usage = new UsageQuotaService(config);
  const cloudAccount = new CloudAccountService(config, cloudMemory, cloudBlobs);
  const registrationRateLimit = new FixedWindowLimiter(10, 60_000);

  app.use("*", cors({ origin: [], allowHeaders: ["Authorization", "Content-Type", "Idempotency-Key", "X-Context-Bubble-User-Token", "X-Installation-Id", "X-Audio-Sample-Rate"] }));
  app.use("*", async (context, next) => {
    const requestId = crypto.randomUUID();
    context.set("requestId", requestId);
    await next();
    context.header("X-Request-Id", requestId);
    context.header("Cache-Control", context.req.path === "/v1/config" ? "public, max-age=300" : "no-store");
  });

  app.get("/health", (context) => context.json({
    status: "ok",
    apiCompatibilityVersion: config.apiCompatibilityVersion,
    openAiConfigured: Boolean(config.openAiApiKey),
    cloudMemoryConfigured: cloudMemory.configured,
  }));

  app.get("/v1/config", (context) => context.json({
    apiCompatibilityVersion: config.apiCompatibilityVersion,
    minimumAndroidVersion: config.minimumAndroidVersion,
    ...(config.supabaseUrl ? { supabaseUrl: config.supabaseUrl } : {}),
    ...(config.supabasePublishableKey ? { supabasePublishableKey: config.supabasePublishableKey } : {}),
    ...(config.googleOAuthWebClientId ? { googleWebClientId: config.googleOAuthWebClientId } : {}),
    ...(config.features.mcp && config.mcpResourceUrl ? { mcpConnectUrl: `${config.mcpResourceUrl}/mcp` } : {}),
    policyVersion: 1,
    features: config.features,
    limits: config.usageLimits,
  }));

  app.get("/.well-known/oauth-protected-resource", (context) => {
    if (!config.features.mcp) return context.json({ error: "mcp_not_configured" }, 503);
    return context.json(protectedResourceMetadata(config));
  });

  app.post("/v1/installations/register", async (context) => {
    const source = context.req.header("CF-Connecting-IP") ?? context.req.header("X-Forwarded-For")?.split(",")[0]?.trim() ?? "unknown";
    if (!registrationRateLimit.take(source)) return context.json(errorEnvelope(context.get("requestId"), "rate_limited", "Too many installation registrations"), 429);
    const body = await context.req.json<{ installationId?: unknown; attestation?: unknown; inviteCredential?: unknown }>();
    if (typeof body.installationId !== "string" || body.installationId.length < 8 || body.installationId.length > 128) {
      throw new ValidationError("installationId must contain 8 to 128 characters");
    }
    if (config.production) {
      if (typeof body.attestation === "string") await integrity.verify(body.attestation, body.installationId);
      else integrity.verifyLabInvite(typeof body.inviteCredential === "string" ? body.inviteCredential : undefined);
    }
    return context.json({ token: createInstallationToken(config, body.installationId), expiresInSeconds: 86_400 }, 201);
  });

  app.post("/v1/assist", async (context) => {
    const identity = installationIdentity(config, context.req.header());
    const parsed = parseAssistRequest(await context.req.json());
    await usage.reserve(identity, "ASSIST_REQUEST", 1, config.usageLimits.assistantRequestsPerDay);
    const userToken = cloudUserToken(context.req.header("X-Context-Bubble-User-Token"));
    if (userToken && config.features.cloudMemory && cloudMemory.configured) {
      const user = await verifier.verify(userToken);
      const cloudContext = await cloudMemory.search(user, { query: parsed.prompt, limit: 8 });
      parsed.relevantMemories = [
        ...(parsed.relevantMemories ?? []),
        ...cloudContext.map((memory) => `${memory.summary}: ${memory.value}`.slice(0, 500)),
      ].slice(0, 12);
    }
    if (!context.req.header("Accept")?.includes("text/event-stream")) return context.json(await openAi.assist(parsed, identity));
    return streamSSE(context, async (stream) => {
      try {
        for await (const event of openAi.assistStream(parsed, identity)) {
          await stream.writeSSE({ event: event.type, data: JSON.stringify(event) });
        }
      } catch (error) {
        const message = error instanceof Error ? error.message.slice(0, 300) : "Streaming failed";
        await stream.writeSSE({ event: "error", data: JSON.stringify({ code: "stream_failed", message, requestId: context.get("requestId") }) });
      }
    });
  });

  app.post("/v1/transcriptions", async (context) => {
    const identity = installationIdentity(config, context.req.header());
    const audio = new Uint8Array(await context.req.arrayBuffer());
    if (audio.length === 0 || audio.length > 15 * 1024 * 1024) throw new ValidationError("Audio must contain 1 byte to 15 MB");
    const sampleRate = Number(context.req.header("X-Audio-Sample-Rate") ?? 24_000);
    const seconds = audio.length / (Math.max(8_000, sampleRate) * 2);
    await usage.reserve(identity, "TRANSCRIPTION_SECOND", seconds, config.usageLimits.transcriptionMinutesPerDay * 60);
    return context.json({ text: await openAi.transcribePcm(audio, sampleRate, identity) });
  });

  app.post("/v1/realtime/call", async (context) => {
    requireFeature(config.features.realtimeVoice, "Realtime voice");
    const identity = installationIdentity(config, context.req.header());
    const offer = await context.req.text();
    if (!offer.trim() || offer.length > 1_000_000) throw new ValidationError("Body must be an SDP offer");
    // Reserve the two-minute hard session ceiling before creating the upstream call. This is
    // deliberately conservative and prevents reconnect storms from exceeding the configured budget.
    await usage.reserve(identity, "REALTIME_SECOND", 120, config.usageLimits.realtimeMinutesPerDay * 60);
    return context.body(await openAi.createRealtimeCall(offer, identity), 200, { "Content-Type": "application/sdp" });
  });

  app.post("/v1/images", async (context) => {
    const identity = installationIdentity(config, context.req.header());
    const body = await context.req.json<{ prompt?: unknown }>();
    if (typeof body.prompt !== "string" || body.prompt.length < 1 || body.prompt.length > 4_000) throw new ValidationError("prompt is invalid");
    await usage.reserve(identity, "IMAGE_REQUEST", 1, config.usageLimits.imagesPerDay);
    return context.json(await openAi.generateImage(body.prompt, identity));
  });

  app.post("/v1/cloud/blobs/generated-image", async (context) => {
    requireFeature(config.features.cloudMemory, "Cloud storage");
    const user = await requireCloudUser(verifier, context.req.header("X-Context-Bubble-User-Token"));
    const bytes = new Uint8Array(await context.req.arrayBuffer());
    const mimeType = context.req.header("X-Content-Mime-Type") ?? "image/png";
    return context.json(await cloudBlobs.saveGeneratedImage(user, bytes, mimeType), 201);
  });

  app.get("/v1/policies/package-blocklist", (context) => {
    requireFeature(config.features.signedPolicies, "Signed policy updates");
    return context.json(packagePolicy(config));
  });

  app.get("/v1/mcp/grants", async (context) => {
    requireFeature(config.features.mcp, "MCP");
    const user = await requireCloudUser(verifier, context.req.header("X-Context-Bubble-User-Token"));
    return context.json({ grants: await mcpGrants.list(user) });
  });

  app.get("/v1/mcp/changes", async (context) => {
    requireFeature(config.features.mcp, "MCP");
    const user = await requireCloudUser(verifier, context.req.header("X-Context-Bubble-User-Token"));
    return context.json({ changes: await mcpGrants.recentChanges(user) });
  });

  app.patch("/v1/mcp/grants/:clientId", async (context) => {
    requireFeature(config.features.mcp, "MCP");
    const user = await requireCloudUser(verifier, context.req.header("X-Context-Bubble-User-Token"));
    const body = await context.req.json<{ accessLevel?: unknown }>();
    if (body.accessLevel !== "READ_ONLY" && body.accessLevel !== "READ_WRITE") throw new ValidationError("accessLevel is invalid");
    await mcpGrants.setAccess(user, decodeURIComponent(context.req.param("clientId")), body.accessLevel);
    return context.json({ updated: true });
  });

  app.delete("/v1/mcp/grants/:clientId", async (context) => {
    requireFeature(config.features.mcp, "MCP");
    const user = await requireCloudUser(verifier, context.req.header("X-Context-Bubble-User-Token"));
    await mcpGrants.revoke(user, decodeURIComponent(context.req.param("clientId")));
    return context.json({ revoked: true });
  });

  app.post("/v1/memories/sync", async (context) => {
    requireFeature(config.features.cloudMemory, "Cloud memory");
    const user = await requireCloudUser(verifier, context.req.header("X-Context-Bubble-User-Token"));
    return context.json(await cloudMemory.sync(user, parseMemorySyncRequest(await context.req.json())));
  });

  app.post("/v1/memories/search", async (context) => {
    requireFeature(config.features.cloudMemory, "Cloud memory");
    const user = await requireCloudUser(verifier, context.req.header("X-Context-Bubble-User-Token"));
    return context.json({ requestId: context.get("requestId"), memories: await cloudMemory.search(user, parseMemorySearchRequest(await context.req.json())) });
  });

  app.get("/v1/memories/:id", async (context) => {
    requireFeature(config.features.cloudMemory, "Cloud memory");
    const user = await requireCloudUser(verifier, context.req.header("X-Context-Bubble-User-Token"));
    const item = await cloudMemory.get(user, context.req.param("id"));
    return item ? context.json(item) : context.json(errorEnvelope(context.get("requestId"), "not_found", "Memory was not found"), 404);
  });

  app.post("/v1/memories", async (context) => {
    requireFeature(config.features.cloudMemory, "Cloud memory");
    const user = await requireCloudUser(verifier, context.req.header("X-Context-Bubble-User-Token"));
    const idempotencyKey = requiredIdempotencyKey(context.req.header("Idempotency-Key"));
    const raw = await context.req.json<Record<string, unknown>>();
    const mutation = onlyMutation(parseMemorySyncRequest({
      cursor: 0,
      mutations: [{ id: raw.id, operation: "UPSERT", baseVersion: 0, idempotencyKey, payload: raw }],
    }));
    const item = await cloudMemory.mutate(user, mutation);
    return context.json(item, 201);
  });

  app.patch("/v1/memories/:id", async (context) => {
    requireFeature(config.features.cloudMemory, "Cloud memory");
    const user = await requireCloudUser(verifier, context.req.header("X-Context-Bubble-User-Token"));
    const idempotencyKey = requiredIdempotencyKey(context.req.header("Idempotency-Key"));
    const body = await context.req.json<{ baseVersion?: unknown; payload?: unknown }>();
    const mutation = onlyMutation(parseMemorySyncRequest({
      cursor: 0,
      mutations: [{ id: context.req.param("id"), operation: "UPSERT", baseVersion: body.baseVersion, idempotencyKey, payload: body.payload }],
    }));
    return context.json(await cloudMemory.mutate(user, mutation));
  });

  app.delete("/v1/memories/:id", async (context) => {
    requireFeature(config.features.cloudMemory, "Cloud memory");
    const user = await requireCloudUser(verifier, context.req.header("X-Context-Bubble-User-Token"));
    const idempotencyKey = requiredIdempotencyKey(context.req.header("Idempotency-Key"));
    const body = await context.req.json<{ baseVersion?: unknown }>();
    const mutation = onlyMutation(parseMemorySyncRequest({
      cursor: 0,
      mutations: [{ id: context.req.param("id"), operation: "DELETE", baseVersion: body.baseVersion, idempotencyKey }],
    }));
    await cloudMemory.mutate(user, mutation);
    return context.json({ deleted: true });
  });

  app.get("/v1/cloud/dashboard", async (context) => {
    requireFeature(config.features.cloudMemory, "Cloud memory");
    const user = await requireCloudUser(verifier, context.req.header("X-Context-Bubble-User-Token"));
    return context.json(await cloudAccount.dashboard(user));
  });

  app.get("/v1/account/export", async (context) => {
    requireFeature(config.features.cloudMemory, "Cloud memory");
    const user = await requireCloudUser(verifier, context.req.header("X-Context-Bubble-User-Token"));
    return context.json(await cloudAccount.export(user), 200, { "Content-Disposition": `attachment; filename="context-bubble-export-${new Date().toISOString().slice(0, 10)}.json"` });
  });

  app.post("/v1/account/delete/prepare", async (context) => {
    requireFeature(config.features.cloudMemory, "Cloud memory");
    const user = await requireCloudUser(verifier, context.req.header("X-Context-Bubble-User-Token"));
    return context.json(cloudAccount.prepareDelete(user));
  });

  app.post("/v1/account/delete/commit", async (context) => {
    requireFeature(config.features.cloudMemory, "Cloud memory");
    const user = await requireCloudUser(verifier, context.req.header("X-Context-Bubble-User-Token"));
    const body = await context.req.json<{ confirmationToken?: unknown; confirmationText?: unknown }>();
    if (body.confirmationText !== "DELETE CLOUD DATA" || typeof body.confirmationToken !== "string") {
      throw new ValidationError("Type DELETE CLOUD DATA and use the unexpired preview token");
    }
    await cloudAccount.commitDelete(user, body.confirmationToken);
    return context.json({ deleted: true });
  });

  app.get("/v1/calendar/status", async (context) => {
    requireFeature(config.features.calendar, "Google Calendar");
    const user = await requireCloudUser(verifier, context.req.header("X-Context-Bubble-User-Token"));
    return context.json(await calendar.status(user));
  });

  app.post("/v1/calendar/oauth/exchange", async (context) => {
    requireFeature(config.features.calendar, "Google Calendar");
    const user = await requireCloudUser(verifier, context.req.header("X-Context-Bubble-User-Token"));
    const body = await context.req.json<{ code?: unknown }>();
    if (typeof body.code !== "string") throw new ValidationError("Google authorization code is required");
    await calendar.exchangeAuthorizationCode(user, body.code);
    return context.json({ connected: true });
  });

  app.delete("/v1/calendar/connection", async (context) => {
    requireFeature(config.features.calendar, "Google Calendar");
    const user = await requireCloudUser(verifier, context.req.header("X-Context-Bubble-User-Token"));
    await calendar.revoke(user);
    return context.json({ revoked: true });
  });

  app.post("/v1/calendar/events", async (context) => {
    requireFeature(config.features.calendar, "Google Calendar");
    const user = await requireCloudUser(verifier, context.req.header("X-Context-Bubble-User-Token"));
    const body = await context.req.json<{ confirmationToken?: unknown; event?: CalendarEventInput }>();
    if (typeof body.confirmationToken !== "string" || !body.event) throw new ValidationError("An exact calendar confirmation token is required");
    await calendar.consumeWrite(user, body.confirmationToken, "CREATE", body.event);
    const idempotencyKey = requiredIdempotencyKey(context.req.header("Idempotency-Key"));
    return context.json(await calendar.createEvent(user, body.event, idempotencyKey), 201);
  });

  app.post("/v1/calendar/events/prepare", async (context) => {
    requireFeature(config.features.calendar, "Google Calendar");
    const user = await requireCloudUser(verifier, context.req.header("X-Context-Bubble-User-Token"));
    const body = await context.req.json<{ operation?: unknown; eventId?: unknown; event?: CalendarEventInput }>();
    if (body.operation !== "CREATE" && body.operation !== "UPDATE" && body.operation !== "DELETE") throw new ValidationError("Calendar operation is invalid");
    return context.json(calendar.prepareWrite(
      user,
      body.operation,
      body.event,
      typeof body.eventId === "string" ? body.eventId : undefined,
    ));
  });

  app.patch("/v1/calendar/events/:id", async (context) => {
    requireFeature(config.features.calendar, "Google Calendar");
    const user = await requireCloudUser(verifier, context.req.header("X-Context-Bubble-User-Token"));
    const body = await context.req.json<{ confirmationToken?: unknown; event?: CalendarEventInput }>();
    if (typeof body.confirmationToken !== "string" || !body.event) throw new ValidationError("An exact calendar confirmation token is required");
    await calendar.consumeWrite(user, body.confirmationToken, "UPDATE", body.event, context.req.param("id"));
    return context.json(await calendar.updateEvent(user, context.req.param("id"), body.event));
  });

  app.delete("/v1/calendar/events/:id", async (context) => {
    requireFeature(config.features.calendar, "Google Calendar");
    const user = await requireCloudUser(verifier, context.req.header("X-Context-Bubble-User-Token"));
    const body = await context.req.json<{ confirmationToken?: unknown }>().catch((): { confirmationToken?: unknown } => ({}));
    if (typeof body.confirmationToken !== "string") throw new ValidationError("An exact calendar confirmation token is required");
    await calendar.consumeWrite(user, body.confirmationToken, "DELETE", undefined, context.req.param("id"));
    await calendar.deleteEvent(user, context.req.param("id"));
    return context.json({ deleted: true });
  });

  app.onError((error, context) => {
    const requestId = context.get("requestId") || crypto.randomUUID();
    if (error instanceof FeatureDisabledError) return context.json(errorEnvelope(requestId, "feature_disabled", error.message), 503);
    if (error instanceof ValidationError) return context.json(errorEnvelope(requestId, "invalid_request", error.message), 400);
    if (error instanceof AuthError) return context.json(errorEnvelope(requestId, "unauthorized", error.message), 401);
    if (error instanceof CloudAuthError) return context.json(errorEnvelope(requestId, "cloud_unauthorized", error.message), 401);
    if (error instanceof CloudMemoryError) {
      const status = error.code === "not_configured"
        ? 503
        : error.code.includes("conflict") || error.code === "idempotency_mismatch"
          ? 409
          : 502;
      return context.json(errorEnvelope(requestId, error.code, error.message), status);
    }
    if (error instanceof CloudBlobError) {
      const status = error.code === "quota_exceeded" ? 429 : error.code === "invalid_blob" ? 400 : error.code === "not_configured" ? 503 : 502;
      return context.json(errorEnvelope(requestId, error.code, error.message), status);
    }
    if (error instanceof McpGrantError) {
      const status = error.code === "invalid_client" ? 400 : error.code === "not_configured" ? 503 : 502;
      return context.json(errorEnvelope(requestId, error.code, error.message), status);
    }
    if (error instanceof CalendarError) {
      const status = error.code === "not_configured" ? 503 : error.code.startsWith("invalid") ? 400 : error.code === "calendar_not_connected" ? 409 : 502;
      return context.json(errorEnvelope(requestId, error.code, error.message), status);
    }
    if (error instanceof IntegrityError) {
      const status = error.code.endsWith("not_configured") ? 503 : 401;
      return context.json(errorEnvelope(requestId, error.code, error.message), status);
    }
    if (error instanceof UsageQuotaError) return context.json(errorEnvelope(requestId, "daily_quota_reached", error.message), 429);
    if (error instanceof UsageQuotaUnavailableError) return context.json(errorEnvelope(requestId, "quota_service_unavailable", "Usage accounting is temporarily unavailable"), 503);
    if (error instanceof CloudAccountError) {
      const status = error.code === "not_configured" ? 503 : error.code.includes("confirmation") ? 400 : 502;
      return context.json(errorEnvelope(requestId, error.code, error.message), status);
    }
    if (error instanceof ConfigurationError) return context.json(errorEnvelope(requestId, "not_configured", error.message), 503);
    if (error instanceof ProviderTransportError) return context.json(errorEnvelope(requestId, "provider_unreachable", error.message), 503);
    if (error instanceof OpenAiError) {
      const status = error.status === 429 ? 429 : error.status >= 500 ? 502 : 400;
      return context.json(errorEnvelope(requestId, "provider_error", error.message), status);
    }
    console.error(JSON.stringify({ level: "error", requestId, error: error instanceof Error ? error.name : "unknown" }));
    return context.json(errorEnvelope(requestId, "internal_error", "The request could not be completed"), 500);
  });

  return app;
}

class FeatureDisabledError extends Error {}

function requireFeature(enabled: boolean, name: string): void {
  if (!enabled) throw new FeatureDisabledError(`${name} is not enabled on this deployment`);
}

function installationIdentity(config: Config, headers: Record<string, string>): string {
  return authenticateHeaders(config, headers.authorization, headers["x-installation-id"]);
}

function cloudUserToken(header?: string): string | undefined {
  if (!header?.trim()) return undefined;
  return header.startsWith("Bearer ") ? header.slice(7).trim() : header.trim();
}

async function requireCloudUser(verifier: SupabaseTokenVerifier, header?: string) {
  const token = cloudUserToken(header);
  if (!token) throw new CloudAuthError("X-Context-Bubble-User-Token is required");
  return verifier.verify(token);
}

function errorEnvelope(requestId: string, code: string, message: string) {
  return { error: { code, message, requestId } };
}

function requiredIdempotencyKey(value?: string): string {
  if (!value || !/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value)) {
    throw new ValidationError("Idempotency-Key must be a UUID");
  }
  return value;
}

function onlyMutation(request: ReturnType<typeof parseMemorySyncRequest>) {
  const mutation = request.mutations[0];
  if (!mutation) throw new ValidationError("One memory mutation is required");
  return mutation;
}

class FixedWindowLimiter {
  private readonly windows = new Map<string, { startedAt: number; count: number }>();

  constructor(private readonly maximum: number, private readonly windowMs: number) {}

  take(key: string): boolean {
    const now = Date.now();
    const current = this.windows.get(key);
    if (!current || now - current.startedAt >= this.windowMs) {
      this.windows.set(key, { startedAt: now, count: 1 });
      if (this.windows.size > 2_000) this.prune(now);
      return true;
    }
    if (current.count >= this.maximum) return false;
    current.count += 1;
    return true;
  }

  private prune(now: number) {
    for (const [key, value] of this.windows) if (now - value.startedAt >= this.windowMs) this.windows.delete(key);
  }
}
