import { createHash } from "node:crypto";
import { createClient } from "@supabase/supabase-js";
import { loadConfig } from "../src/config.ts";
import { CloudMemoryCrypto } from "../src/cloud-crypto.ts";
import { OpenAiGateway } from "../src/openai.ts";
import type { CloudMemoryPayload } from "../src/contracts.ts";

const apply = process.argv.includes("--apply");
const config = loadConfig();
if (config.embeddingProvider !== "openai") {
  throw new Error("Set EMBEDDING_PROVIDER=openai before re-embedding with the OpenAI fallback");
}
if (!config.supabaseUrl || !config.supabaseSecretKey || !config.openAiApiKey) {
  throw new Error("SUPABASE_URL, SUPABASE_SECRET_KEY, OPENAI_API_KEY, and cloud memory keys are required");
}
const admin = createClient(config.supabaseUrl, config.supabaseSecretKey, { auth: { persistSession: false } });
const crypto = new CloudMemoryCrypto(config.cloudMemoryMasterKeys, config.currentCloudMemoryKeyVersion);
const embeddings = new OpenAiGateway(config);
let offset = 0;
let processed = 0;

for (;;) {
  const { data, error } = await admin.from("memory_items")
    .select("id,user_id,payload_ciphertext,payload_nonce,key_version,embedding_model,deleted")
    .eq("deleted", false)
    .order("id")
    .range(offset, offset + 99);
  if (error) throw new Error(error.message);
  if (!data?.length) break;
  for (const row of data) {
    if (row.embedding_model === embeddings.model) continue;
    processed += 1;
    if (!apply) continue;
    const payload = await crypto.decrypt(String(row.user_id), {
      ciphertext: String(row.payload_ciphertext),
      nonce: String(row.payload_nonce),
      keyVersion: Number(row.key_version),
    }) as CloudMemoryPayload;
    const input = `${payload.type}\n${payload.summary}\n${payload.value}`.slice(0, 8_000);
    const vector = normalize(await embeddings.embed(input, String(row.user_id)));
    if (vector.length !== 384) throw new Error(`Embedding for ${row.id} had ${vector.length} dimensions`);
    const { error: updateError } = await admin.from("memory_items").update({
      embedding: `[${vector.map((value) => Number(value.toFixed(8))).join(",")}]`,
      embedding_model: embeddings.model,
      embedding_input_hash: createHash("sha256").update(input).digest("hex"),
    }).eq("user_id", row.user_id).eq("id", row.id);
    if (updateError) throw new Error(updateError.message);
  }
  offset += data.length;
  if (data.length < 100) break;
}

console.log(`${apply ? "Re-embedded" : "Would re-embed"} ${processed} memories with ${embeddings.model}.`);
if (!apply) console.log("Run again with --apply only after the English/Hinglish Recall@5 gate approves the target model and cost budget.");

function normalize(values: number[]): number[] {
  const magnitude = Math.sqrt(values.reduce((sum, value) => sum + value * value, 0));
  return magnitude > 0 ? values.map((value) => value / magnitude) : values;
}
