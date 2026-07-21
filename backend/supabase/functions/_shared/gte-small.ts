import type { EmbeddingProvider } from "../../../src/cloud-memory.ts";

type SupabaseAiSession = { run(input: string, options?: Record<string, unknown>): Promise<unknown> };
type SupabaseAiConstructor = new (model: string) => SupabaseAiSession;

export class GteSmallEmbeddingProvider implements EmbeddingProvider {
  readonly model = "gte-small-v1.0";
  private readonly session: SupabaseAiSession;

  constructor() {
    const runtime = globalThis as typeof globalThis & { Supabase?: { ai?: { Session?: SupabaseAiConstructor } } };
    const Session = runtime.Supabase?.ai?.Session;
    if (!Session) throw new Error("Supabase built-in AI is unavailable in this runtime");
    this.session = new Session("gte-small");
  }

  async embed(text: string): Promise<number[]> {
    const output = await this.session.run(text.slice(0, 2_000), { mean_pool: true, normalize: true });
    if (!Array.isArray(output) || output.some((value) => typeof value !== "number")) {
      throw new Error("gte-small returned an invalid embedding");
    }
    return output as number[];
  }
}
