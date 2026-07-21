import { readFile } from "node:fs/promises";

type Case = {
  id: string;
  summary: string;
  value: string;
  englishQuery: string;
  hinglishQuery: string;
};

type CloudRecord = { id: string; version: number };
type SyncResponse = { changes: CloudRecord[]; conflicts: unknown[] };

const baseUrl = requiredEnv("RETRIEVAL_EVAL_BACKEND_URL").replace(/\/$/, "");
const accessToken = requiredEnv("RETRIEVAL_EVAL_ACCESS_TOKEN");
if (process.env.RETRIEVAL_EVAL_CONFIRM !== "I_AM_USING_A_DISPOSABLE_DEV_USER") {
  throw new Error("Set RETRIEVAL_EVAL_CONFIRM=I_AM_USING_A_DISPOSABLE_DEV_USER. This runner writes evaluation memories.");
}

const cases = JSON.parse(await readFile(new URL("./retrieval-eval-cases.json", import.meta.url), "utf8")) as Case[];
if (cases.length !== 50 || new Set(cases.map((item) => item.id)).size !== cases.length) {
  throw new Error("Retrieval fixture must contain exactly 50 uniquely identified memories");
}

const createdAtEpochMs = Date.now();
const seed = await request<SyncResponse>("/v1/memories/sync", {
  cursor: 0,
  mutations: cases.map((item, index) => ({
    operation: "UPSERT",
    id: item.id,
    baseVersion: 0,
    idempotencyKey: evaluationUuid(1, index + 1),
    payload: {
      id: item.id,
      type: "retrieval-evaluation",
      summary: item.summary,
      value: item.value,
      sourcePackage: "context-bubble-retrieval-gate",
      sensitivity: "normal",
      createdAtEpochMs,
      expiresAtEpochMs: createdAtEpochMs + 24 * 60 * 60 * 1_000,
      pinned: false,
    },
  })),
});
if (seed.conflicts.length > 0) {
  throw new Error("Evaluation user already contains fixture IDs. Use a fresh disposable dev user or delete that user first.");
}

const versions = new Map(seed.changes.map((record) => [record.id, record.version]));
const results = await mapConcurrent(
  cases.flatMap((item) => [
    { language: "English", query: item.englishQuery, expectedId: item.id },
    { language: "Hinglish", query: item.hinglishQuery, expectedId: item.id },
  ]),
  5,
  async (item) => {
    const started = performance.now();
    const response = await request<{ memories: Array<{ id: string }> }>("/v1/memories/search", { query: item.query, limit: 5 });
    return { ...item, matched: response.memories.some((memory) => memory.id === item.expectedId), durationMs: performance.now() - started };
  },
);

for (const language of ["English", "Hinglish"] as const) {
  const languageResults = results.filter((result) => result.language === language);
  const recall = languageResults.filter((result) => result.matched).length / languageResults.length;
  console.log(`${language} Recall@5: ${(recall * 100).toFixed(1)}% (${languageResults.filter((result) => result.matched).length}/${languageResults.length})`);
  if (recall < 0.85) process.exitCode = 1;
}
const latencies = results.map((result) => result.durationMs).sort((left, right) => left - right);
console.log(`Search p95: ${latencies[Math.ceil(latencies.length * 0.95) - 1]?.toFixed(0)} ms`);
const misses = results.filter((result) => !result.matched);
if (misses.length > 0) console.log(`Misses: ${misses.map((miss) => `${miss.language}: ${miss.query}`).join(" | ")}`);

if (process.argv.includes("--cleanup")) {
  const missingVersions = cases.filter((item) => !versions.has(item.id));
  if (missingVersions.length > 0) throw new Error("Cannot safely clean up because one or more seeded versions are unknown");
  await request<SyncResponse>("/v1/memories/sync", {
    cursor: 0,
    mutations: cases.map((item, index) => ({
      operation: "DELETE",
      id: item.id,
      baseVersion: versions.get(item.id),
      idempotencyKey: evaluationUuid(2, index + 1),
    })),
  });
  console.log("Evaluation memories were tombstoned.");
}

async function request<T>(path: string, body: unknown): Promise<T> {
  const response = await fetch(`${baseUrl}${path}`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "X-Context-Bubble-User-Token": accessToken,
    },
    body: JSON.stringify(body),
  });
  const text = await response.text();
  if (!response.ok) throw new Error(`${path} failed (${response.status}): ${text.slice(0, 500)}`);
  return JSON.parse(text) as T;
}

async function mapConcurrent<T, R>(items: T[], concurrency: number, worker: (item: T) => Promise<R>): Promise<R[]> {
  const results = new Array<R>(items.length);
  let next = 0;
  await Promise.all(Array.from({ length: concurrency }, async () => {
    while (true) {
      const index = next++;
      if (index >= items.length) return;
      results[index] = await worker(items[index]!);
    }
  }));
  return results;
}

function evaluationUuid(group: number, index: number): string {
  return `${group.toString().padStart(8, "0")}-0000-4000-8000-${index.toString().padStart(12, "0")}`;
}

function requiredEnv(name: string): string {
  const value = process.env[name]?.trim();
  if (!value) throw new Error(`${name} is required`);
  return value;
}
