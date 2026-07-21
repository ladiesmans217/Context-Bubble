import { createHmac, timingSafeEqual } from "node:crypto";
import { Buffer } from "node:buffer";
import type { Config } from "./config.ts";

type TokenPayload = { installationId: string; expiresAt: number };

export function createInstallationToken(config: Config, installationId: string, lifetimeSeconds = 86_400): string {
  const payload: TokenPayload = { installationId, expiresAt: Math.floor(Date.now() / 1000) + lifetimeSeconds };
  const encoded = Buffer.from(JSON.stringify(payload)).toString("base64url");
  const signature = sign(config.installationTokenSecret, encoded);
  return `${encoded}.${signature}`;
}

export function authenticateHeaders(config: Config, authorization?: string, developmentInstallationId?: string): string {
  if (authorization?.startsWith("Bearer ")) {
    return verifyInstallationToken(config, authorization.slice(7)).installationId;
  }
  if (!config.production && developmentInstallationId) return developmentInstallationId.slice(0, 128);
  throw new AuthError("A valid installation token is required");
}

function verifyInstallationToken(config: Config, token: string): TokenPayload {
  const [encoded, providedSignature] = token.split(".");
  if (!encoded || !providedSignature) throw new AuthError("Malformed installation token");
  const expected = Buffer.from(sign(config.installationTokenSecret, encoded));
  const provided = Buffer.from(providedSignature);
  if (expected.length !== provided.length || !timingSafeEqual(expected, provided)) throw new AuthError("Invalid installation token");
  const payload = JSON.parse(Buffer.from(encoded, "base64url").toString("utf8")) as TokenPayload;
  if (!payload.installationId || payload.expiresAt < Date.now() / 1000) throw new AuthError("Installation token expired");
  return payload;
}

function sign(secret: string, value: string): string {
  return createHmac("sha256", secret).update(value).digest("base64url");
}

export class AuthError extends Error {}
