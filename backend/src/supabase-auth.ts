import { createRemoteJWKSet, decodeJwt, jwtVerify, type JWTPayload } from "jose";
import type { Config } from "./config.ts";

export type AuthenticatedUser = {
  id: string;
  accessToken: string;
  clientId?: string;
  claims: JWTPayload;
};

export class SupabaseTokenVerifier {
  private readonly jwks?: ReturnType<typeof createRemoteJWKSet>;

  constructor(private readonly config: Config) {
    if (config.supabaseUrl) {
      this.jwks = createRemoteJWKSet(new URL(`${config.supabaseUrl.replace(/\/$/, "")}/auth/v1/.well-known/jwks.json`));
    }
  }

  async verify(accessToken: string, requireMcpAudience = false): Promise<AuthenticatedUser> {
    if (!this.config.supabaseUrl || !this.jwks) throw new CloudAuthError("Supabase authentication is not configured");
    const issuer = `${this.config.supabaseUrl.replace(/\/$/, "")}/auth/v1`;
    const audiences = requireMcpAudience && this.config.mcpResourceUrl
      ? [this.config.mcpResourceUrl, "authenticated"]
      : ["authenticated", this.config.mcpResourceUrl].filter((value): value is string => Boolean(value));
    let claims: JWTPayload;
    try {
      const verified = await jwtVerify(accessToken, this.jwks, {
        issuer,
        ...(audiences.length > 0 ? { audience: audiences } : {}),
        clockTolerance: 5,
      });
      claims = verified.payload;
    } catch {
      throw new CloudAuthError("Supabase access token is invalid or expired");
    }
    if (typeof claims.sub !== "string" || !UUID_PATTERN.test(claims.sub)) {
      throw new CloudAuthError("Supabase access token has no valid user subject");
    }
    const decoded = decodeJwt(accessToken);
    const clientId = typeof decoded.client_id === "string" ? decoded.client_id.slice(0, 500) : undefined;
    return {
      id: claims.sub,
      accessToken,
      ...(clientId ? { clientId } : {}),
      claims,
    };
  }
}

export function userTokenFromHeaders(headers: Record<string, string | string[] | undefined>): string | undefined {
  const header = headers["x-context-bubble-user-token"];
  if (typeof header !== "string" || !header.trim()) return undefined;
  return header.startsWith("Bearer ") ? header.slice(7).trim() : header.trim();
}

export function bearerToken(authorization: string | undefined): string {
  if (!authorization?.startsWith("Bearer ")) throw new CloudAuthError("Bearer access token is required");
  const token = authorization.slice(7).trim();
  if (!token) throw new CloudAuthError("Bearer access token is required");
  return token;
}

export class CloudAuthError extends Error {}

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
