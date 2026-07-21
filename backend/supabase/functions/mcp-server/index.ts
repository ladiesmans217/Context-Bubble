import { loadConfig, type Environment } from "../../../src/config.ts";
import { CloudMemoryService } from "../../../src/cloud-memory.ts";
import { OpenAiGateway } from "../../../src/openai.ts";
import { createMcpHttpHandler, protectedResourceMetadata } from "../../../src/mcp.ts";
import { SupabaseTokenVerifier } from "../../../src/supabase-auth.ts";
import { GteSmallEmbeddingProvider } from "../_shared/gte-small.ts";

const config = loadConfig({ ...Deno.env.toObject(), NODE_ENV: "production" } satisfies Environment);
const openAi = new OpenAiGateway(config);
const embeddings = config.features.semanticSearch
  ? config.embeddingProvider === "openai" ? openAi : new GteSmallEmbeddingProvider()
  : undefined;
const memory = new CloudMemoryService(config, embeddings);
const verifier = new SupabaseTokenVerifier(config);
const handler = createMcpHttpHandler(config, memory, verifier);

Deno.serve(async (incoming) => {
  const request = normalizeFunctionRequest(incoming, "mcp-server");
  const path = new URL(request.url).pathname;
  if (request.method === "GET" && path === "/.well-known/oauth-protected-resource") {
    return Response.json(protectedResourceMetadata(config), { headers: { "Cache-Control": "public, max-age=300" } });
  }
  if (path !== "/mcp" && path !== "/") return Response.json({ error: "not_found" }, { status: 404 });
  return handler(request);
});

function normalizeFunctionRequest(request: Request, functionName: string): Request {
  const url = new URL(request.url);
  const prefixes = [`/functions/v1/${functionName}`, `/${functionName}`];
  for (const prefix of prefixes) {
    if (url.pathname === prefix) url.pathname = "/";
    else if (url.pathname.startsWith(`${prefix}/`)) url.pathname = url.pathname.slice(prefix.length);
  }
  return new Request(url, request);
}
