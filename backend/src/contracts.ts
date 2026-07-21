export type ScreenContext = {
  packageName: string;
  windowId: number;
  focusedText?: string | null;
  surroundingText?: string | null;
  editable: boolean;
  sensitive: boolean;
  capturedAtEpochMs: number;
  screenshotBase64?: string | null;
};

export type AssistMode = "ASK" | "REMEMBER" | "REMINDER" | "IMAGE";

export type AssistRequest = {
  prompt: string;
  screen?: ScreenContext | null;
  relevantMemories?: string[];
  mode?: AssistMode;
  stream?: boolean;
};

export type MemoryMutationOperation = "UPSERT" | "DELETE";

export type CloudMemoryPayload = {
  id: string;
  type: string;
  summary: string;
  value: string;
  sourcePackage?: string | null;
  sensitivity: string;
  createdAtEpochMs: number;
  expiresAtEpochMs?: number | null;
  pinned: boolean;
};

export type MemoryMutation = {
  operation: MemoryMutationOperation;
  id: string;
  baseVersion: number;
  payload?: CloudMemoryPayload | null;
  idempotencyKey: string;
};

export type MemorySyncRequest = {
  cursor: number;
  mutations: MemoryMutation[];
};

export type CloudMemoryRecord = CloudMemoryPayload & {
  version: number;
  syncSequence: number;
  deleted: boolean;
  remoteUpdatedAtEpochMs: number;
  embeddingModel?: string | null;
};

export type MemoryConflict = {
  id: string;
  localBaseVersion: number;
  cloud: CloudMemoryRecord;
};

export type MemorySyncResponse = {
  requestId: string;
  nextCursor: number;
  changes: CloudMemoryRecord[];
  conflicts: MemoryConflict[];
};

export type MemorySearchRequest = {
  query: string;
  limit?: number;
};

export type BackendCapabilities = {
  apiCompatibilityVersion: number;
  minimumAndroidVersion: number;
  supabaseUrl?: string;
  supabasePublishableKey?: string;
  googleWebClientId?: string;
  mcpConnectUrl?: string;
  policyVersion: number;
  features: Record<string, boolean>;
  limits: Record<string, number>;
};

export type MemoryCandidate = {
  id: string;
  type: string;
  summary: string;
  value: string;
  sensitivity: string;
};

export type ActionStep = {
  type: string;
  label: string;
  payload: Record<string, string>;
};

export type ActionPlan = {
  id: string;
  summary: string;
  risk: "READ_ONLY" | "LOW" | "CONSEQUENTIAL" | "BLOCKED";
  steps: ActionStep[];
};

export type AssistResponse = {
  requestId: string;
  text: string;
  memoryCandidates: MemoryCandidate[];
  actionPlan?: ActionPlan | null;
};

export function parseAssistRequest(input: unknown): AssistRequest {
  if (!input || typeof input !== "object") throw new ValidationError("Request body must be an object");
  const body = input as Record<string, unknown>;
  if (typeof body.prompt !== "string" || body.prompt.trim().length === 0 || body.prompt.length > 4_000) {
    throw new ValidationError("prompt must contain 1 to 4000 characters");
  }
  const mode = body.mode ?? "ASK";
  if (!(["ASK", "REMEMBER", "REMINDER", "IMAGE"] as unknown[]).includes(mode)) {
    throw new ValidationError("mode is invalid");
  }
  if (body.screen != null) validateScreen(body.screen);
  const relevantMemories = body.relevantMemories;
  if (relevantMemories != null && (!Array.isArray(relevantMemories) || relevantMemories.some((item) => typeof item !== "string"))) {
    throw new ValidationError("relevantMemories must be a string array");
  }
  return body as AssistRequest;
}

export function parseMemorySyncRequest(input: unknown): MemorySyncRequest {
  if (!input || typeof input !== "object") throw new ValidationError("Request body must be an object");
  const body = input as Record<string, unknown>;
  const cursor = body.cursor ?? 0;
  if (!Number.isSafeInteger(cursor) || Number(cursor) < 0) throw new ValidationError("cursor must be a non-negative integer");
  if (!Array.isArray(body.mutations) || body.mutations.length > 50) {
    throw new ValidationError("mutations must contain at most 50 items");
  }
  const mutations = body.mutations.map(parseMemoryMutation);
  return { cursor: Number(cursor), mutations };
}

export function parseMemorySearchRequest(input: unknown): MemorySearchRequest {
  if (!input || typeof input !== "object") throw new ValidationError("Request body must be an object");
  const body = input as Record<string, unknown>;
  if (typeof body.query !== "string" || body.query.trim().length === 0 || body.query.length > 2_000) {
    throw new ValidationError("query must contain 1 to 2000 characters");
  }
  const limit = body.limit ?? 8;
  if (!Number.isSafeInteger(limit) || Number(limit) < 1 || Number(limit) > 20) {
    throw new ValidationError("limit must be between 1 and 20");
  }
  return { query: body.query.trim(), limit: Number(limit) };
}

function parseMemoryMutation(input: unknown): MemoryMutation {
  if (!input || typeof input !== "object") throw new ValidationError("mutation must be an object");
  const value = input as Record<string, unknown>;
  if (value.operation !== "UPSERT" && value.operation !== "DELETE") throw new ValidationError("mutation.operation is invalid");
  if (typeof value.id !== "string" || !UUID_PATTERN.test(value.id)) throw new ValidationError("mutation.id must be a UUID");
  if (!Number.isSafeInteger(value.baseVersion) || Number(value.baseVersion) < 0) throw new ValidationError("mutation.baseVersion is invalid");
  if (typeof value.idempotencyKey !== "string" || !UUID_PATTERN.test(value.idempotencyKey)) {
    throw new ValidationError("mutation.idempotencyKey must be a UUID");
  }
  const payload = value.operation === "UPSERT" ? parseCloudMemoryPayload(value.payload, value.id) : null;
  return {
    operation: value.operation,
    id: value.id,
    baseVersion: Number(value.baseVersion),
    payload,
    idempotencyKey: value.idempotencyKey,
  };
}

function parseCloudMemoryPayload(input: unknown, expectedId: string): CloudMemoryPayload {
  if (!input || typeof input !== "object") throw new ValidationError("UPSERT mutation requires payload");
  const value = input as Record<string, unknown>;
  if (value.id !== expectedId) throw new ValidationError("payload.id must match mutation.id");
  const requiredStrings = ["type", "summary", "value", "sensitivity"] as const;
  for (const field of requiredStrings) {
    if (typeof value[field] !== "string" || value[field].length === 0) throw new ValidationError(`payload.${field} is invalid`);
  }
  if ((value.summary as string).length > 1_000 || (value.value as string).length > 8_000) {
    throw new ValidationError("memory payload is too large");
  }
  if (!Number.isSafeInteger(value.createdAtEpochMs) || Number(value.createdAtEpochMs) < 0) {
    throw new ValidationError("payload.createdAtEpochMs is invalid");
  }
  if (value.expiresAtEpochMs != null && (!Number.isSafeInteger(value.expiresAtEpochMs) || Number(value.expiresAtEpochMs) < 0)) {
    throw new ValidationError("payload.expiresAtEpochMs is invalid");
  }
  if (typeof value.pinned !== "boolean") throw new ValidationError("payload.pinned is invalid");
  if (value.sourcePackage != null && (typeof value.sourcePackage !== "string" || value.sourcePackage.length > 255)) {
    throw new ValidationError("payload.sourcePackage is invalid");
  }
  return value as CloudMemoryPayload;
}

function validateScreen(value: unknown): asserts value is ScreenContext {
  if (!value || typeof value !== "object") throw new ValidationError("screen must be an object");
  const screen = value as Record<string, unknown>;
  if (typeof screen.packageName !== "string" || screen.packageName.length > 255) throw new ValidationError("screen.packageName is invalid");
  if (screen.sensitive === true) throw new ValidationError("Sensitive screen context cannot be processed");
  for (const field of ["focusedText", "surroundingText"] as const) {
    if (typeof screen[field] === "string" && screen[field].length > 8_000) throw new ValidationError(`screen.${field} is too large`);
  }
  if (screen.screenshotBase64 != null &&
      (typeof screen.screenshotBase64 !== "string" || screen.screenshotBase64.length > 2_500_000)) {
    throw new ValidationError("screen.screenshotBase64 is invalid or too large");
  }
}

export class ValidationError extends Error {}

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
