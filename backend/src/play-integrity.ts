import { createHash, timingSafeEqual } from "node:crypto";
import { importPKCS8, SignJWT } from "jose";
import type { Config } from "./config.ts";

type ServiceAccount = { client_email: string; private_key: string; token_uri?: string };

export class PlayIntegrityVerifier {
  private accessToken?: { value: string; expiresAtEpochMs: number };

  constructor(private readonly config: Config, private readonly fetchImpl: typeof fetch = fetch) {}

  async verify(attestation: string, installationId: string): Promise<void> {
    const serviceAccount = this.serviceAccount();
    if (!attestation || attestation.length > 100_000) throw new IntegrityError("integrity_required", "Play Integrity token is required");
    const accessToken = await this.googleAccessToken(serviceAccount);
    const response = await this.fetchImpl(
      `https://playintegrity.googleapis.com/v1/${encodeURIComponent(this.config.playPackageName)}:decodeIntegrityToken`,
      {
        method: "POST",
        headers: { Authorization: `Bearer ${accessToken}`, "Content-Type": "application/json" },
        body: JSON.stringify({ integrity_token: attestation }),
      },
    );
    const raw = await response.text();
    if (!response.ok) throw new IntegrityError("integrity_decode_failed", "Play Integrity could not verify this installation");
    const decoded = JSON.parse(raw) as IntegrityDecodeResponse;
    const payload = decoded.tokenPayloadExternal;
    const expectedHash = requestHash(installationId);
    if (!payload?.requestDetails || payload.requestDetails.requestPackageName !== this.config.playPackageName ||
      !safeEqual(payload.requestDetails.requestHash, expectedHash)) {
      throw new IntegrityError("integrity_binding_failed", "Play Integrity request binding is invalid");
    }
    const issuedAt = Number(payload.requestDetails.timestampMillis);
    if (!Number.isFinite(issuedAt) || Math.abs(Date.now() - issuedAt) > 2 * 60_000) {
      throw new IntegrityError("integrity_expired", "Play Integrity token is stale");
    }
    if (payload.appIntegrity?.appRecognitionVerdict !== "PLAY_RECOGNIZED" || payload.appIntegrity.packageName !== this.config.playPackageName) {
      throw new IntegrityError("app_not_recognized", "This app installation is not recognized by Google Play");
    }
    if (!payload.deviceIntegrity?.deviceRecognitionVerdict?.includes("MEETS_DEVICE_INTEGRITY")) {
      throw new IntegrityError("device_not_trusted", "This device did not meet the required integrity level");
    }
    if (payload.accountDetails?.appLicensingVerdict !== "LICENSED") {
      throw new IntegrityError("app_not_licensed", "This Google Play account is not licensed for Context Bubble");
    }
  }

  verifyLabInvite(provided: string | undefined): void {
    const expected = this.config.labInviteSecret;
    if (!expected || !provided || !safeEqual(provided, expected)) throw new IntegrityError("lab_invite_required", "A valid private Lab invite is required");
  }

  private serviceAccount(): ServiceAccount {
    const raw = this.config.playIntegrityServiceAccountJson;
    if (!raw) throw new IntegrityError("integrity_not_configured", "Play Integrity verification is not configured");
    try {
      const value = JSON.parse(raw) as Partial<ServiceAccount>;
      if (!value.client_email || !value.private_key) throw new Error("missing fields");
      return { client_email: value.client_email, private_key: value.private_key.replaceAll("\\n", "\n"), ...(value.token_uri ? { token_uri: value.token_uri } : {}) };
    } catch {
      throw new IntegrityError("integrity_not_configured", "Play Integrity service account JSON is invalid");
    }
  }

  private async googleAccessToken(serviceAccount: ServiceAccount): Promise<string> {
    if (this.accessToken && this.accessToken.expiresAtEpochMs > Date.now() + 60_000) return this.accessToken.value;
    const tokenUri = serviceAccount.token_uri || "https://oauth2.googleapis.com/token";
    const key = await importPKCS8(serviceAccount.private_key, "RS256");
    const assertion = await new SignJWT({ scope: "https://www.googleapis.com/auth/playintegrity" })
      .setProtectedHeader({ alg: "RS256", typ: "JWT" })
      .setIssuer(serviceAccount.client_email)
      .setAudience(tokenUri)
      .setIssuedAt()
      .setExpirationTime("10m")
      .sign(key);
    const response = await this.fetchImpl(tokenUri, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion }),
    });
    const raw = await response.text();
    if (!response.ok) throw new IntegrityError("integrity_auth_failed", "Play Integrity verifier could not authenticate");
    const token = JSON.parse(raw) as { access_token?: unknown; expires_in?: unknown };
    if (typeof token.access_token !== "string" || typeof token.expires_in !== "number") throw new IntegrityError("integrity_auth_failed", "Play Integrity verifier returned an invalid token");
    this.accessToken = { value: token.access_token, expiresAtEpochMs: Date.now() + token.expires_in * 1_000 };
    return token.access_token;
  }
}

export class IntegrityError extends Error {
  constructor(readonly code: string, message: string) { super(message); }
}

function requestHash(installationId: string): string {
  return createHash("sha256").update(`register:${installationId}`).digest("base64url");
}

function safeEqual(left: string | undefined, right: string): boolean {
  if (!left) return false;
  const leftBytes = new TextEncoder().encode(left);
  const rightBytes = new TextEncoder().encode(right);
  return leftBytes.length === rightBytes.length && timingSafeEqual(leftBytes, rightBytes);
}

type IntegrityDecodeResponse = {
  tokenPayloadExternal?: {
    requestDetails?: { requestPackageName?: string; requestHash?: string; timestampMillis?: string };
    appIntegrity?: { appRecognitionVerdict?: string; packageName?: string };
    accountDetails?: { appLicensingVerdict?: string };
    deviceIntegrity?: { deviceRecognitionVerdict?: string[] };
  };
};
