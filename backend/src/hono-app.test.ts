import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { loadConfig } from "./config.ts";
import { OpenAiGateway } from "./openai.ts";
import { createHonoApi } from "./hono-app.ts";

const config = loadConfig({ NODE_ENV: "development", OPENAI_API_KEY: "test-key", INSTALLATION_TOKEN_SECRET: "a".repeat(32) });

describe("Context Bubble shared Hono API", () => {
  it("defaults to budget models and keeps quality one switch away", () => {
    assert.equal(config.openAiModels.ask, "gpt-5.4-mini");
    assert.equal(config.openAiModels.remember, "gpt-5.4-nano");
    assert.equal(config.openAiModels.realtimeConversation, "gpt-realtime-2.1");
    assert.equal(config.embeddingProvider, "gte-small");
    const quality = loadConfig({ NODE_ENV: "development", OPENAI_MODEL_PROFILE: "quality", INSTALLATION_TOKEN_SECRET: "a".repeat(32) });
    assert.equal(quality.openAiModels.ask, "gpt-5.6-terra");
    assert.equal(quality.openAiModels.complex, "gpt-5.6-sol");
    assert.equal(loadConfig({ NODE_ENV: "development", EMBEDDING_PROVIDER: "openai", INSTALLATION_TOKEN_SECRET: "a".repeat(32) }).embeddingProvider, "openai");
  });

  it("reports health without exposing secrets", async () => {
    const response = await createHonoApi(config).request("/health");
    assert.equal(response.status, 200);
    assert.deepEqual(await response.json(), { status: "ok", apiCompatibilityVersion: 2, openAiConfigured: true, cloudMemoryConfigured: false });
  });

  it("reports only public capabilities", async () => {
    const configured = loadConfig({
      NODE_ENV: "development", OPENAI_API_KEY: "must-not-appear", INSTALLATION_TOKEN_SECRET: "a".repeat(32),
      SUPABASE_URL: "https://example.supabase.co", SUPABASE_PUBLISHABLE_KEY: "sb_publishable_public",
      SUPABASE_SECRET_KEY: "sb_secret_private", CLOUD_MEMORY_MASTER_KEY_V1: "b".repeat(48), MCP_RESOURCE_URL: "https://api.example.com",
    });
    const response = await createHonoApi(configured).request("/v1/config");
    const body = await response.text();
    assert.equal(response.status, 200);
    assert.match(body, /sb_publishable_public/);
    assert.doesNotMatch(body, /must-not-appear|sb_secret_private|bbbbbbbb/);
  });

  it("accepts Supabase public values copied from a Next.js environment", () => {
    const copied = loadConfig({
      NODE_ENV: "development",
      INSTALLATION_TOKEN_SECRET: "a".repeat(32),
      NEXT_PUBLIC_SUPABASE_URL: "https://copied.supabase.co",
      NEXT_PUBLIC_SUPABASE_PUBLISHABLE_KEY: "sb_publishable_copied",
      SUPABASE_SECRET_KEY: "sb_secret_private",
      CLOUD_MEMORY_MASTER_KEY_V1: "b".repeat(48),
    });
    assert.equal(copied.supabaseUrl, "https://copied.supabase.co");
    assert.equal(copied.supabasePublishableKey, "sb_publishable_copied");
    assert.equal(copied.features.cloudMemory, true);
  });

  it("accepts the hosted Edge Function API-key maps", () => {
    const hosted = loadConfig({
      NODE_ENV: "development",
      INSTALLATION_TOKEN_SECRET: "a".repeat(32),
      SUPABASE_URL: "https://hosted.supabase.co",
      SUPABASE_PUBLISHABLE_KEYS: JSON.stringify({ default: "sb_publishable_hosted" }),
      SUPABASE_SECRET_KEYS: JSON.stringify({ default: "sb_secret_hosted" }),
      CLOUD_MEMORY_MASTER_KEY_V1: "b".repeat(48),
      MCP_RESOURCE_URL: "https://hosted.supabase.co/functions/v1/mcp-server",
    });
    assert.equal(hosted.supabasePublishableKey, "sb_publishable_hosted");
    assert.equal(hosted.supabaseSecretKey, "sb_secret_hosted");
    assert.equal(hosted.features.mcp, true);
  });

  it("keeps configured production features off until each rollout switch is enabled", async () => {
    const environment = {
      NODE_ENV: "production",
      OPENAI_API_KEY: "test-key",
      INSTALLATION_TOKEN_SECRET: "a".repeat(32),
      SUPABASE_URL: "https://example.supabase.co",
      SUPABASE_PUBLISHABLE_KEY: "sb_publishable_public",
      SUPABASE_SECRET_KEY: "sb_secret_private",
      CLOUD_MEMORY_MASTER_KEY_V1: "b".repeat(48),
      MCP_RESOURCE_URL: "https://api.example.com",
      POLICY_SIGNING_PRIVATE_KEY_PEM: "private-key",
    };
    const closed = loadConfig(environment);
    assert.deepEqual(closed.features, {
      cloudMemory: false,
      semanticSearch: false,
      mcp: false,
      realtimeVoice: false,
      calendar: false,
      signedPolicies: false,
    });
    const opened = loadConfig({
      ...environment,
      ENABLE_CLOUD_MEMORY: "true",
      ENABLE_SEMANTIC_SEARCH: "true",
      ENABLE_MCP: "true",
      ENABLE_REALTIME_VOICE: "true",
      ENABLE_SIGNED_POLICIES: "true",
    });
    assert.equal(opened.features.cloudMemory, true);
    assert.equal(opened.features.semanticSearch, true);
    assert.equal(opened.features.mcp, true);
    assert.equal(opened.features.realtimeVoice, true);
    assert.equal(opened.features.signedPolicies, true);
  });

  it("returns a stable feature-disabled error before invoking a gated provider", async () => {
    const closed = loadConfig({
      NODE_ENV: "production",
      OPENAI_API_KEY: "test-key",
      INSTALLATION_TOKEN_SECRET: "a".repeat(32),
    });
    const response = await createHonoApi(closed).request("/v1/realtime/call", {
      method: "POST",
      headers: { "Content-Type": "application/sdp" },
      body: "v=0",
    });
    assert.equal(response.status, 503);
    assert.equal((await response.json() as { error: { code: string } }).error.code, "feature_disabled");
  });

  it("rejects sensitive screen context before model execution", async () => {
    const response = await jsonRequest(createHonoApi(config), "/v1/assist", {
      prompt: "summarize", screen: { packageName: "bank", windowId: 1, editable: false, sensitive: true, capturedAtEpochMs: 1 },
    });
    assert.equal(response.status, 400);
  });

  it("uses store:false structured responses", async () => {
    let sentBody: Record<string, unknown> | undefined;
    const gateway = new OpenAiGateway(config, async (_input, init) => {
      sentBody = JSON.parse(String(init?.body)) as Record<string, unknown>;
      return Response.json({ output: [{ content: [{ text: JSON.stringify({ text: "Hello", memoryCandidates: [], actionPlan: null }) }] }] });
    });
    const response = await jsonRequest(createHonoApi(config, { openAi: gateway }), "/v1/assist", { prompt: "hello", mode: "ASK" });
    assert.equal(response.status, 200);
    assert.equal((await response.json() as { text: string }).text, "Hello");
    assert.equal(sentBody?.store, false);
    assert.equal(sentBody?.model, "gpt-5.4-mini");
    assertStrictObjectSchemas((sentBody?.text as { format?: { schema?: unknown } })?.format?.schema);
  });

  it("streams typed SSE events", async () => {
    const fakeFetch: typeof fetch = async () => new Response([
      'data: {"type":"response.output_text.delta","delta":"Hello"}', "",
      'data: {"type":"response.output_text.delta","delta":" world"}', "",
      'data: {"type":"response.completed"}', "",
    ].join("\n"), { headers: { "Content-Type": "text/event-stream" } });
    const response = await jsonRequest(createHonoApi(config, { openAi: new OpenAiGateway(config, fakeFetch) }), "/v1/assist", { prompt: "hello", mode: "ASK" }, { Accept: "text/event-stream" });
    const body = await response.text();
    assert.equal(response.status, 200);
    assert.match(body, /event: delta/);
    assert.match(body, /Hello world/);
    assert.match(body, /event: completed/);
  });

  it("creates push-to-talk Realtime sessions with input transcription enabled", async () => {
    let session: Record<string, unknown> | undefined;
    const gateway = new OpenAiGateway(config, async (_input, init) => {
      const form = init?.body as FormData;
      session = JSON.parse(String(form.get("session"))) as Record<string, unknown>;
      return new Response("v=0\r\n", { status: 200, headers: { "Content-Type": "application/sdp" } });
    });
    await gateway.createRealtimeCall("v=0", "test-installation");
    const input = ((session?.audio as { input?: Record<string, unknown> })?.input ?? {});
    assert.equal(input.turn_detection, null);
    assert.deepEqual(input.transcription, { model: "gpt-realtime-whisper" });
    assert.equal(session?.model, "gpt-realtime-2.1");
  });

  it("classifies DNS failures as retryable", async () => {
    const dnsError = Object.assign(new Error("DNS lookup failed"), { code: "ENOTFOUND" });
    const gateway = new OpenAiGateway(config, async () => { throw new TypeError("fetch failed", { cause: dnsError }); });
    const response = await jsonRequest(createHonoApi(config, { openAi: gateway }), "/v1/assist", { prompt: "hello", mode: "ASK" });
    assert.equal(response.status, 503);
    assert.equal((await response.json() as { error: { code: string } }).error.code, "provider_unreachable");
  });

  it("rate limits installation-token minting", async () => {
    const app = createHonoApi(config);
    for (let index = 0; index < 10; index += 1) {
      const response = await app.request("/v1/installations/register", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ installationId: `installation-${index}` }) });
      assert.equal(response.status, 201);
    }
    const limited = await app.request("/v1/installations/register", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ installationId: "installation-over-limit" }) });
    assert.equal(limited.status, 429);
  });
});

function jsonRequest(app: ReturnType<typeof createHonoApi>, path: string, body: unknown, extraHeaders: Record<string, string> = {}) {
  return app.request(path, {
    method: "POST",
    headers: { "Content-Type": "application/json", "X-Installation-Id": "test-installation", ...extraHeaders },
    body: JSON.stringify(body),
  });
}

function assertStrictObjectSchemas(value: unknown): void {
  if (Array.isArray(value)) return value.forEach(assertStrictObjectSchemas);
  if (!value || typeof value !== "object") return;
  const schema = value as Record<string, unknown>;
  if (schema.type === "object" && schema.properties && typeof schema.properties === "object") {
    assert.equal(schema.additionalProperties, false);
    assert.deepEqual([...((schema.required as string[] | undefined) ?? [])].sort(), Object.keys(schema.properties as Record<string, unknown>).sort());
  }
  Object.values(schema).forEach(assertStrictObjectSchemas);
}
