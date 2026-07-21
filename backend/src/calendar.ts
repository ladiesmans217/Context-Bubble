import { createClient, type SupabaseClient } from "@supabase/supabase-js";
import type { Config } from "./config.ts";
import { CloudMemoryCrypto, decodeBase64Url, encodeBase64Url } from "./cloud-crypto.ts";
import type { AuthenticatedUser } from "./supabase-auth.ts";
import { createHash, createHmac, timingSafeEqual } from "node:crypto";

export type CalendarEventInput = {
  title: string;
  description?: string;
  startDateTime: string;
  endDateTime: string;
  timeZone: string;
  recurrence?: string[];
};

type StoredGoogleTokens = {
  accessToken: string;
  refreshToken?: string;
  expiresAtEpochMs: number;
  scope: string;
};

type IntegrationRow = {
  token_ciphertext: string;
  token_nonce: string;
  key_version: number;
  revoked_at: string | null;
};

export class CalendarService {
  private readonly crypto: CloudMemoryCrypto;

  constructor(private readonly config: Config, private readonly fetchImpl: typeof fetch = fetch) {
    this.crypto = new CloudMemoryCrypto(config.cloudMemoryMasterKeys, config.currentCloudMemoryKeyVersion);
  }

  async exchangeAuthorizationCode(user: AuthenticatedUser, code: string): Promise<void> {
    this.requireConfiguration();
    if (!code || code.length > 4_000) throw new CalendarError("invalid_code", "Google authorization code is invalid");
    const client = this.client(user);
    const existing = await this.loadTokens(client, user.id, true);
    const form = new URLSearchParams({
      code,
      client_id: this.config.googleOAuthWebClientId!,
      client_secret: this.config.googleOAuthWebClientSecret!,
      grant_type: "authorization_code",
      redirect_uri: this.config.googleCalendarRedirectUri!,
    });
    const response = await this.fetchImpl("https://oauth2.googleapis.com/token", {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: form,
    });
    const raw = await response.text();
    if (!response.ok) throw new CalendarError("token_exchange_failed", safeGoogleError(raw));
    const token = JSON.parse(raw) as { access_token?: unknown; refresh_token?: unknown; expires_in?: unknown; scope?: unknown };
    if (typeof token.access_token !== "string" || typeof token.expires_in !== "number") {
      throw new CalendarError("token_exchange_failed", "Google returned an invalid token response");
    }
    const refreshToken = typeof token.refresh_token === "string" ? token.refresh_token : existing?.refreshToken;
    if (!refreshToken) throw new CalendarError("refresh_token_missing", "Google did not return offline Calendar access; revoke and reconnect Calendar");
    await this.storeTokens(client, user.id, {
      accessToken: token.access_token,
      refreshToken,
      expiresAtEpochMs: Date.now() + token.expires_in * 1_000,
      scope: typeof token.scope === "string" ? token.scope : CALENDAR_SCOPE,
    });
  }

  async status(user: AuthenticatedUser): Promise<{ connected: boolean }> {
    const row = await this.fetchRow(this.client(user), user.id);
    return { connected: Boolean(row && !row.revoked_at) };
  }

  prepareWrite(
    user: AuthenticatedUser,
    operation: "CREATE" | "UPDATE" | "DELETE",
    input?: CalendarEventInput,
    eventId?: string,
  ): { confirmationToken: string; expiresAt: string; preview: { operation: string; eventId?: string; event?: CalendarEventInput } } {
    if (operation === "CREATE" || operation === "UPDATE") {
      if (!input) throw new CalendarError("invalid_event", "Calendar event is required");
      validateEvent(input);
    }
    if (operation === "UPDATE" || operation === "DELETE") validateEventId(eventId ?? "");
    const claim: CalendarConfirmation = {
      userId: user.id,
      operation,
      ...(eventId ? { eventId } : {}),
      ...(input ? { event: input } : {}),
      nonce: crypto.randomUUID(),
      expiresAtEpochMs: Date.now() + 10 * 60_000,
      payloadHash: calendarPayloadHash(operation, eventId, input),
    };
    return {
      confirmationToken: signCalendarClaim(claim, this.config.installationTokenSecret),
      expiresAt: new Date(claim.expiresAtEpochMs).toISOString(),
      preview: { operation, ...(eventId ? { eventId } : {}), ...(input ? { event: input } : {}) },
    };
  }

  verifyWrite(
    user: AuthenticatedUser,
    token: string,
    operation: CalendarConfirmation["operation"],
    input?: CalendarEventInput,
    eventId?: string,
  ): void {
    const claim = verifyCalendarClaim(token, this.config.installationTokenSecret);
    const expectedHash = calendarPayloadHash(operation, eventId, input);
    if (claim.userId !== user.id || claim.operation !== operation || claim.payloadHash !== expectedHash) {
      throw new CalendarError("invalid_confirmation", "Calendar confirmation does not match the exact preview");
    }
  }

  async consumeWrite(
    user: AuthenticatedUser,
    token: string,
    operation: CalendarConfirmation["operation"],
    input?: CalendarEventInput,
    eventId?: string,
  ): Promise<void> {
    this.verifyWrite(user, token, operation, input, eventId);
    const claim = verifyCalendarClaim(token, this.config.installationTokenSecret);
    const { error } = await this.client(user).from("action_confirmation_nonces").insert({
      user_id: user.id,
      nonce: claim.nonce,
      operation: `CALENDAR_${operation}`,
      expires_at: new Date(claim.expiresAtEpochMs).toISOString(),
    });
    if (error?.code === "23505") throw new CalendarError("invalid_confirmation", "Calendar confirmation was already used");
    if (error) throw new CalendarError("confirmation_failed", error.message);
  }

  async createEvent(user: AuthenticatedUser, input: CalendarEventInput, idempotencyKey: string): Promise<{ id: string; htmlLink?: string }> {
    validateEvent(input);
    const accessToken = await this.accessToken(user);
    const eventId = calendarEventId(idempotencyKey);
    const payload = {
      id: eventId,
      summary: input.title.trim(),
      ...(input.description?.trim() ? { description: input.description.trim().slice(0, 4_000) } : {}),
      start: { dateTime: input.startDateTime, timeZone: input.timeZone },
      end: { dateTime: input.endDateTime, timeZone: input.timeZone },
      ...(input.recurrence?.length ? { recurrence: input.recurrence.slice(0, 5) } : {}),
      extendedProperties: { private: { contextBubbleIdempotencyKey: idempotencyKey } },
    };
    const response = await this.fetchImpl("https://www.googleapis.com/calendar/v3/calendars/primary/events?sendUpdates=none", {
      method: "POST",
      headers: { Authorization: `Bearer ${accessToken}`, "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    });
    const raw = await response.text();
    if (response.status === 409) return this.getEvent(accessToken, eventId);
    if (!response.ok) throw new CalendarError("calendar_write_failed", safeGoogleError(raw));
    return publicEvent(JSON.parse(raw));
  }

  async updateEvent(user: AuthenticatedUser, eventId: string, input: CalendarEventInput): Promise<{ id: string; htmlLink?: string }> {
    validateEvent(input);
    validateEventId(eventId);
    const accessToken = await this.accessToken(user);
    const response = await this.fetchImpl(`https://www.googleapis.com/calendar/v3/calendars/primary/events/${encodeURIComponent(eventId)}?sendUpdates=none`, {
      method: "PATCH",
      headers: { Authorization: `Bearer ${accessToken}`, "Content-Type": "application/json" },
      body: JSON.stringify({
        summary: input.title.trim(),
        description: input.description?.trim().slice(0, 4_000) ?? "",
        start: { dateTime: input.startDateTime, timeZone: input.timeZone },
        end: { dateTime: input.endDateTime, timeZone: input.timeZone },
        recurrence: input.recurrence?.slice(0, 5) ?? [],
      }),
    });
    const raw = await response.text();
    if (!response.ok) throw new CalendarError("calendar_write_failed", safeGoogleError(raw));
    return publicEvent(JSON.parse(raw));
  }

  async deleteEvent(user: AuthenticatedUser, eventId: string): Promise<void> {
    validateEventId(eventId);
    const accessToken = await this.accessToken(user);
    const response = await this.fetchImpl(`https://www.googleapis.com/calendar/v3/calendars/primary/events/${encodeURIComponent(eventId)}?sendUpdates=none`, {
      method: "DELETE",
      headers: { Authorization: `Bearer ${accessToken}` },
    });
    if (!response.ok && response.status !== 404 && response.status !== 410) {
      throw new CalendarError("calendar_write_failed", safeGoogleError(await response.text()));
    }
  }

  async revoke(user: AuthenticatedUser): Promise<void> {
    const client = this.client(user);
    const tokens = await this.loadTokens(client, user.id, true);
    if (tokens?.refreshToken || tokens?.accessToken) {
      const token = tokens.refreshToken ?? tokens.accessToken;
      await this.fetchImpl(`https://oauth2.googleapis.com/revoke?token=${encodeURIComponent(token)}`, {
        method: "POST",
        headers: { "Content-Type": "application/x-www-form-urlencoded" },
      });
    }
    const { error } = await client.from("user_integration_tokens").update({ revoked_at: new Date().toISOString() })
      .eq("user_id", user.id).eq("provider", "GOOGLE_CALENDAR");
    if (error) throw new CalendarError("calendar_revoke_failed", error.message);
  }

  private async accessToken(user: AuthenticatedUser): Promise<string> {
    const client = this.client(user);
    const tokens = await this.loadTokens(client, user.id);
    if (!tokens) throw new CalendarError("calendar_not_connected", "Connect Google Calendar first");
    if (tokens.expiresAtEpochMs > Date.now() + 120_000) return tokens.accessToken;
    if (!tokens.refreshToken) throw new CalendarError("calendar_not_connected", "Reconnect Google Calendar");
    const form = new URLSearchParams({
      refresh_token: tokens.refreshToken,
      client_id: this.config.googleOAuthWebClientId!,
      client_secret: this.config.googleOAuthWebClientSecret!,
      grant_type: "refresh_token",
    });
    const response = await this.fetchImpl("https://oauth2.googleapis.com/token", {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: form,
    });
    const raw = await response.text();
    if (!response.ok) throw new CalendarError("token_refresh_failed", safeGoogleError(raw));
    const refreshed = JSON.parse(raw) as { access_token?: unknown; expires_in?: unknown; scope?: unknown };
    if (typeof refreshed.access_token !== "string" || typeof refreshed.expires_in !== "number") throw new CalendarError("token_refresh_failed", "Google returned an invalid refresh response");
    const next = { ...tokens, accessToken: refreshed.access_token, expiresAtEpochMs: Date.now() + refreshed.expires_in * 1_000 };
    await this.storeTokens(client, user.id, next);
    return next.accessToken;
  }

  private async getEvent(accessToken: string, eventId: string): Promise<{ id: string; htmlLink?: string }> {
    const response = await this.fetchImpl(`https://www.googleapis.com/calendar/v3/calendars/primary/events/${encodeURIComponent(eventId)}`, {
      headers: { Authorization: `Bearer ${accessToken}` },
    });
    const raw = await response.text();
    if (!response.ok) throw new CalendarError("calendar_read_failed", safeGoogleError(raw));
    return publicEvent(JSON.parse(raw));
  }

  private async loadTokens(client: SupabaseClient, userId: string, includeRevoked = false): Promise<StoredGoogleTokens | null> {
    const row = await this.fetchRow(client, userId);
    if (!row || (row.revoked_at && !includeRevoked)) return null;
    const bytes = await this.crypto.decryptBytes(userId, {
      ciphertext: decodeBase64Url(row.token_ciphertext),
      nonce: decodeBase64Url(row.token_nonce),
      keyVersion: row.key_version,
    }, "google-calendar-token");
    return JSON.parse(new TextDecoder().decode(bytes)) as StoredGoogleTokens;
  }

  private async fetchRow(client: SupabaseClient, userId: string): Promise<IntegrationRow | null> {
    const { data, error } = await client.from("user_integration_tokens").select("token_ciphertext,token_nonce,key_version,revoked_at")
      .eq("user_id", userId).eq("provider", "GOOGLE_CALENDAR").maybeSingle();
    if (error) throw new CalendarError("calendar_read_failed", error.message);
    return data as IntegrationRow | null;
  }

  private async storeTokens(client: SupabaseClient, userId: string, tokens: StoredGoogleTokens): Promise<void> {
    const encrypted = await this.crypto.encryptBytes(userId, new TextEncoder().encode(JSON.stringify(tokens)), "google-calendar-token");
    const { error } = await client.from("user_integration_tokens").upsert({
      user_id: userId,
      provider: "GOOGLE_CALENDAR",
      token_ciphertext: encodeBase64Url(encrypted.ciphertext),
      token_nonce: encodeBase64Url(encrypted.nonce),
      key_version: encrypted.keyVersion,
      granted_scopes: tokens.scope.split(" "),
      expires_at: new Date(tokens.expiresAtEpochMs).toISOString(),
      revoked_at: null,
    }, { onConflict: "user_id,provider" });
    if (error) throw new CalendarError("calendar_write_failed", error.message);
  }

  private client(user: AuthenticatedUser) {
    this.requireConfiguration();
    return createClient(this.config.supabaseUrl!, this.config.supabasePublishableKey!, {
      auth: { persistSession: false, autoRefreshToken: false, detectSessionInUrl: false },
      global: { headers: { Authorization: `Bearer ${user.accessToken}` } },
    });
  }

  private requireConfiguration() {
    if (!this.config.supabaseUrl || !this.config.supabasePublishableKey || !this.config.googleOAuthWebClientId ||
      !this.config.googleOAuthWebClientSecret || !this.config.googleCalendarRedirectUri || !this.crypto.configured) {
      throw new CalendarError("not_configured", "Google Calendar is not configured")
    }
  }
}

export class CalendarError extends Error {
  constructor(readonly code: string, message: string) { super(message); }
}

function validateEvent(input: CalendarEventInput) {
  if (!input.title?.trim() || input.title.length > 500) throw new CalendarError("invalid_event", "Calendar title is invalid");
  const start = Date.parse(input.startDateTime);
  const end = Date.parse(input.endDateTime);
  if (!Number.isFinite(start) || !Number.isFinite(end) || end <= start) throw new CalendarError("invalid_event", "Calendar start and end are invalid");
  if (!input.timeZone || input.timeZone.length > 100) throw new CalendarError("invalid_event", "Calendar time zone is invalid");
}

function validateEventId(value: string) {
  if (!/^[a-z0-9]{5,1024}$/.test(value)) throw new CalendarError("invalid_event", "Calendar event ID is invalid");
}

function calendarEventId(idempotencyKey: string): string {
  const normalized = idempotencyKey.toLowerCase().replace(/[^a-v0-9]/g, "");
  if (normalized.length < 5) throw new CalendarError("invalid_idempotency_key", "A valid idempotency key is required");
  return `cb${normalized}`.slice(0, 64);
}

function publicEvent(value: unknown): { id: string; htmlLink?: string } {
  const event = value as { id?: unknown; htmlLink?: unknown };
  if (typeof event.id !== "string") throw new CalendarError("calendar_response_invalid", "Google returned an invalid event");
  return { id: event.id, ...(typeof event.htmlLink === "string" ? { htmlLink: event.htmlLink } : {}) };
}

function safeGoogleError(raw: string): string {
  try {
    const parsed = JSON.parse(raw) as { error?: unknown; error_description?: unknown };
    if (typeof parsed.error_description === "string") return parsed.error_description.slice(0, 300);
    if (typeof parsed.error === "string") return parsed.error.slice(0, 300);
  } catch { /* Return a stable message below. */ }
  return "Google Calendar request failed";
}

const CALENDAR_SCOPE = "https://www.googleapis.com/auth/calendar.events";

export type CalendarConfirmation = {
  userId: string;
  operation: "CREATE" | "UPDATE" | "DELETE";
  eventId?: string;
  event?: CalendarEventInput;
  nonce: string;
  expiresAtEpochMs: number;
  payloadHash: string;
};

function calendarPayloadHash(operation: string, eventId?: string, event?: CalendarEventInput): string {
  return createHash("sha256").update(JSON.stringify({ operation, eventId: eventId ?? null, event: event ?? null })).digest("hex");
}

function signCalendarClaim(claim: CalendarConfirmation, secret: string): string {
  const payload = encodeBase64Url(new TextEncoder().encode(JSON.stringify(claim)));
  const signature = encodeBase64Url(createHmac("sha256", secret).update(payload).digest());
  return `${payload}.${signature}`;
}

function verifyCalendarClaim(token: string, secret: string): CalendarConfirmation {
  const [payload, signature, extra] = token.split(".");
  if (!payload || !signature || extra) throw new CalendarError("invalid_confirmation", "Calendar confirmation is invalid");
  const expected = createHmac("sha256", secret).update(payload).digest();
  const supplied = decodeBase64Url(signature);
  if (supplied.length !== expected.length || !timingSafeEqual(supplied, expected)) throw new CalendarError("invalid_confirmation", "Calendar confirmation is invalid");
  const claim = JSON.parse(new TextDecoder().decode(decodeBase64Url(payload))) as CalendarConfirmation;
  if (!claim.nonce || claim.expiresAtEpochMs <= Date.now()) throw new CalendarError("expired_confirmation", "Calendar confirmation expired");
  if (claim.payloadHash !== calendarPayloadHash(claim.operation, claim.eventId, claim.event)) throw new CalendarError("invalid_confirmation", "Calendar preview was altered");
  return claim;
}
