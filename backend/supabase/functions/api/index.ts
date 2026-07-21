import { loadConfig, type Environment } from "../../../src/config.ts";
import { createHonoApi } from "../../../src/hono-app.ts";
import { CloudMemoryService } from "../../../src/cloud-memory.ts";
import { OpenAiGateway } from "../../../src/openai.ts";
import { GteSmallEmbeddingProvider } from "../_shared/gte-small.ts";

const config = loadConfig({ ...Deno.env.toObject(), NODE_ENV: "production" } satisfies Environment);
const openAi = new OpenAiGateway(config);
const embeddings = config.features.semanticSearch
  ? config.embeddingProvider === "openai" ? openAi : new GteSmallEmbeddingProvider()
  : undefined;
const cloudMemory = new CloudMemoryService(config, embeddings);
const app = createHonoApi(config, { openAi, cloudMemory });

Deno.serve((request) => app.fetch(normalizeFunctionRequest(request, "api")));

function normalizeFunctionRequest(request: Request, functionName: string): Request {
  const url = new URL(request.url);
  const prefixes = [`/functions/v1/${functionName}`, `/${functionName}`];
  for (const prefix of prefixes) {
    if (url.pathname === prefix) url.pathname = "/";
    else if (url.pathname.startsWith(`${prefix}/`)) url.pathname = url.pathname.slice(prefix.length);
  }
  return new Request(url, request);
}
