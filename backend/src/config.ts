export type OpenAiModelProfile = "budget" | "quality";

export type OpenAiModels = {
  profile: OpenAiModelProfile;
  ask: string;
  remember: string;
  complex: string;
  transcription: string;
  realtimeTranscription: string;
  realtimeConversation: string;
  embedding: string;
  image: string;
};

export type FeatureFlags = {
  cloudMemory: boolean;
  semanticSearch: boolean;
  mcp: boolean;
  realtimeVoice: boolean;
  calendar: boolean;
  signedPolicies: boolean;
};

export type UsageLimits = {
  assistantRequestsPerDay: number;
  imagesPerDay: number;
  realtimeMinutesPerDay: number;
  transcriptionMinutesPerDay: number;
  cloudBlobBytesPerUser: number;
};

export type Config = {
  port: number;
  host: string;
  production: boolean;
  openAiApiKey?: string;
  openAiModels: OpenAiModels;
  embeddingProvider: "gte-small" | "openai";
  installationTokenSecret: string;
  supabaseUrl?: string;
  supabasePublishableKey?: string;
  supabaseSecretKey?: string;
  cloudMemoryMasterKeys: ReadonlyMap<number, string>;
  currentCloudMemoryKeyVersion: number;
  mcpResourceUrl?: string;
  googleOAuthWebClientId?: string;
  googleOAuthWebClientSecret?: string;
  googleCalendarRedirectUri?: string;
  policySigningPrivateKeyPem?: string;
  policySigningPublicKeyPem?: string;
  policyCertificateFingerprints: Readonly<Record<string, readonly string[]>>;
  playIntegrityServiceAccountJson?: string;
  playPackageName: string;
  labInviteSecret?: string;
  minimumAndroidVersion: number;
  apiCompatibilityVersion: number;
  usageLimits: UsageLimits;
  features: FeatureFlags;
};

const modelProfiles: Record<OpenAiModelProfile, Omit<OpenAiModels, "profile">> = {
  budget: {
    ask: "gpt-5.4-mini",
    remember: "gpt-5.4-nano",
    complex: "gpt-5.6-luna",
    transcription: "gpt-4o-mini-transcribe",
    realtimeTranscription: "gpt-realtime-whisper",
    realtimeConversation: "gpt-realtime-2.1",
    embedding: "text-embedding-3-small",
    image: "gpt-image-1-mini",
  },
  quality: {
    ask: "gpt-5.6-terra",
    remember: "gpt-5.6-luna",
    complex: "gpt-5.6-sol",
    transcription: "gpt-4o-transcribe",
    realtimeTranscription: "gpt-realtime-whisper",
    realtimeConversation: "gpt-realtime-2.1",
    embedding: "text-embedding-3-small",
    image: "gpt-image-2",
  },
};

export type Environment = Record<string, string | undefined>;

export function loadConfig(env: Environment = runtimeEnvironment()): Config {
  const production = env.NODE_ENV === "production";
  const installationTokenSecret = env.INSTALLATION_TOKEN_SECRET ?? "local-development-only-secret-change-me";
  if (production && installationTokenSecret.length < 32) {
    throw new Error("INSTALLATION_TOKEN_SECRET must be at least 32 characters in production");
  }
  if (production && !env.OPENAI_API_KEY) throw new Error("OPENAI_API_KEY is required in production");

  const requestedProfile = env.OPENAI_MODEL_PROFILE?.trim() || "budget";
  if (requestedProfile !== "budget" && requestedProfile !== "quality") {
    throw new Error("OPENAI_MODEL_PROFILE must be 'budget' or 'quality'");
  }
  const profile = requestedProfile as OpenAiModelProfile;
  const defaults = modelProfiles[profile];
  const embeddingProvider = env.EMBEDDING_PROVIDER?.trim() || "gte-small";
  if (embeddingProvider !== "gte-small" && embeddingProvider !== "openai") {
    throw new Error("EMBEDDING_PROVIDER must be 'gte-small' or 'openai'");
  }
  const cloudMemoryMasterKeys = loadVersionedSecrets(env, "CLOUD_MEMORY_MASTER_KEY_V");
  const currentCloudMemoryKeyVersion = highestKeyVersion(cloudMemoryMasterKeys);
  const supabaseUrl = env.SUPABASE_URL?.trim() || env.NEXT_PUBLIC_SUPABASE_URL?.trim();
  const supabasePublishableKey = env.SUPABASE_PUBLISHABLE_KEY?.trim()
    || env.NEXT_PUBLIC_SUPABASE_PUBLISHABLE_KEY?.trim()
    || namedApiKey(env.SUPABASE_PUBLISHABLE_KEYS)
    || env.SUPABASE_ANON_KEY?.trim();
  const supabaseSecretKey = env.SUPABASE_SECRET_KEY?.trim()
    || namedApiKey(env.SUPABASE_SECRET_KEYS);
  const supabaseConfigured = Boolean(supabaseUrl && supabasePublishableKey);
  const cloudMemoryConfigured = supabaseConfigured && cloudMemoryMasterKeys.size > 0;
  const realtimeConfigured = Boolean(env.OPENAI_API_KEY);
  const signedPoliciesConfigured = Boolean(env.POLICY_SIGNING_PRIVATE_KEY_PEM);
  const calendarConfigured = Boolean(
    supabaseConfigured &&
    cloudMemoryMasterKeys.size > 0 &&
    env.GOOGLE_OAUTH_WEB_CLIENT_ID &&
    env.GOOGLE_OAUTH_WEB_CLIENT_SECRET &&
    env.GOOGLE_CALENDAR_REDIRECT_URI,
  );
  const rollout = (name: string, configured: boolean): boolean =>
    configured && booleanEnv(env[name], !production);
  const cloudMemoryEnabled = rollout("ENABLE_CLOUD_MEMORY", cloudMemoryConfigured);

  return {
    port: Number(env.PORT ?? 8787),
    host: env.HOST ?? "127.0.0.1",
    production,
    ...(env.OPENAI_API_KEY ? { openAiApiKey: env.OPENAI_API_KEY } : {}),
    openAiModels: {
      profile,
      ask: env.OPENAI_ASK_MODEL?.trim() || defaults.ask,
      remember: env.OPENAI_REMEMBER_MODEL?.trim() || defaults.remember,
      complex: env.OPENAI_COMPLEX_MODEL?.trim() || defaults.complex,
      transcription: env.OPENAI_TRANSCRIPTION_MODEL?.trim() || defaults.transcription,
      realtimeTranscription: env.OPENAI_REALTIME_TRANSCRIPTION_MODEL?.trim() || defaults.realtimeTranscription,
      realtimeConversation: env.OPENAI_REALTIME_MODEL?.trim() || defaults.realtimeConversation,
      embedding: env.OPENAI_EMBEDDING_MODEL?.trim() || defaults.embedding,
      image: env.OPENAI_IMAGE_MODEL?.trim() || defaults.image,
    },
    embeddingProvider,
    installationTokenSecret,
    ...(supabaseUrl ? { supabaseUrl } : {}),
    ...(supabasePublishableKey ? { supabasePublishableKey } : {}),
    ...(supabaseSecretKey ? { supabaseSecretKey } : {}),
    cloudMemoryMasterKeys,
    currentCloudMemoryKeyVersion,
    ...(env.MCP_RESOURCE_URL ? { mcpResourceUrl: env.MCP_RESOURCE_URL.replace(/\/$/, "") } : {}),
    ...(env.GOOGLE_OAUTH_WEB_CLIENT_ID ? { googleOAuthWebClientId: env.GOOGLE_OAUTH_WEB_CLIENT_ID } : {}),
    ...(env.GOOGLE_OAUTH_WEB_CLIENT_SECRET ? { googleOAuthWebClientSecret: env.GOOGLE_OAUTH_WEB_CLIENT_SECRET } : {}),
    ...(env.GOOGLE_CALENDAR_REDIRECT_URI ? { googleCalendarRedirectUri: env.GOOGLE_CALENDAR_REDIRECT_URI } : {}),
    ...(env.POLICY_SIGNING_PRIVATE_KEY_PEM ? { policySigningPrivateKeyPem: env.POLICY_SIGNING_PRIVATE_KEY_PEM.replaceAll("\\n", "\n") } : {}),
    ...(env.POLICY_SIGNING_PUBLIC_KEY_PEM ? { policySigningPublicKeyPem: env.POLICY_SIGNING_PUBLIC_KEY_PEM.replaceAll("\\n", "\n") } : {}),
    policyCertificateFingerprints: parseCertificateFingerprints(env.POLICY_CERTIFICATE_FINGERPRINTS_JSON),
    ...(env.PLAY_INTEGRITY_SERVICE_ACCOUNT_JSON ? { playIntegrityServiceAccountJson: env.PLAY_INTEGRITY_SERVICE_ACCOUNT_JSON } : {}),
    playPackageName: env.PLAY_PACKAGE_NAME?.trim() || "com.contextbubble.app.play",
    ...(env.LAB_INVITE_SECRET ? { labInviteSecret: env.LAB_INVITE_SECRET } : {}),
    minimumAndroidVersion: integerEnv(env.MINIMUM_ANDROID_VERSION, 33, 33, 36),
    apiCompatibilityVersion: integerEnv(env.API_COMPATIBILITY_VERSION, 2, 1, 100),
    usageLimits: {
      assistantRequestsPerDay: integerEnv(env.DAILY_ASSIST_REQUESTS, 100, 1, 100_000),
      imagesPerDay: integerEnv(env.DAILY_IMAGE_REQUESTS, 10, 0, 10_000),
      realtimeMinutesPerDay: integerEnv(env.DAILY_REALTIME_MINUTES, 30, 0, 100_000),
      transcriptionMinutesPerDay: integerEnv(env.DAILY_TRANSCRIPTION_MINUTES, 60, 0, 100_000),
      cloudBlobBytesPerUser: integerEnv(env.CLOUD_BLOB_BYTES_PER_USER, 100_000_000, 1_000_000, 10_000_000_000),
    },
    features: {
      cloudMemory: cloudMemoryEnabled,
      semanticSearch: cloudMemoryEnabled && rollout("ENABLE_SEMANTIC_SEARCH", cloudMemoryConfigured),
      mcp: cloudMemoryEnabled && rollout("ENABLE_MCP", Boolean(supabaseSecretKey && env.MCP_RESOURCE_URL)),
      realtimeVoice: rollout("ENABLE_REALTIME_VOICE", realtimeConfigured),
      calendar: rollout("ENABLE_CALENDAR", calendarConfigured),
      signedPolicies: rollout("ENABLE_SIGNED_POLICIES", signedPoliciesConfigured),
    },
  };
}

function booleanEnv(raw: string | undefined, fallback: boolean): boolean {
  if (!raw?.trim()) return fallback;
  if (raw === "true") return true;
  if (raw === "false") return false;
  throw new Error("Boolean environment values must be 'true' or 'false'");
}

function namedApiKey(raw: string | undefined): string | undefined {
  if (!raw?.trim()) return undefined;
  const parsed = JSON.parse(raw) as unknown;
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
    throw new Error("Supabase hosted API key maps must be JSON objects");
  }
  const keys = parsed as Record<string, unknown>;
  const preferred = keys.default;
  if (typeof preferred === "string" && preferred.trim()) return preferred.trim();
  for (const value of Object.values(keys)) {
    if (typeof value === "string" && value.trim()) return value.trim();
  }
  return undefined;
}

function parseCertificateFingerprints(raw: string | undefined): Readonly<Record<string, readonly string[]>> {
  if (!raw?.trim()) return {};
  const parsed = JSON.parse(raw) as unknown;
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) throw new Error("POLICY_CERTIFICATE_FINGERPRINTS_JSON must be an object");
  const output: Record<string, string[]> = {};
  for (const [packageName, values] of Object.entries(parsed as Record<string, unknown>)) {
    if (!/^[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+$/.test(packageName) || !Array.isArray(values)) throw new Error("Policy certificate map is invalid");
    output[packageName] = values.map((value) => {
      if (typeof value !== "string") throw new Error("Policy certificate fingerprint must be a string");
      const normalized = value.replaceAll(":", "").toUpperCase();
      if (!/^[0-9A-F]{64}$/.test(normalized)) throw new Error("Policy certificate fingerprint must be SHA-256 hex");
      return normalized;
    });
  }
  return output;
}

function loadVersionedSecrets(env: Environment, prefix: string): ReadonlyMap<number, string> {
  const values = new Map<number, string>();
  for (const [name, rawValue] of Object.entries(env)) {
    if (!name.startsWith(prefix) || !rawValue?.trim()) continue;
    const version = Number(name.slice(prefix.length));
    if (!Number.isSafeInteger(version) || version < 1) continue;
    values.set(version, rawValue.trim());
  }
  return values;
}

function highestKeyVersion(keys: ReadonlyMap<number, string>): number {
  let highest = 0;
  for (const version of keys.keys()) highest = Math.max(highest, version);
  return highest;
}

function integerEnv(raw: string | undefined, fallback: number, minimum: number, maximum: number): number {
  if (!raw?.trim()) return fallback;
  const value = Number(raw);
  if (!Number.isSafeInteger(value) || value < minimum || value > maximum) {
    throw new Error(`Integer environment value must be between ${minimum} and ${maximum}`);
  }
  return value;
}

function runtimeEnvironment(): Environment {
  const runtime = globalThis as typeof globalThis & { process?: { env?: Environment } };
  return runtime.process?.env ?? {};
}
