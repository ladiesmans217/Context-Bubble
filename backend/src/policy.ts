import { createPrivateKey, sign } from "node:crypto";
import { Buffer } from "node:buffer";
import type { Config } from "./config.ts";

const hardBlockedPackages = [
  "com.google.android.apps.nbu.paisa.user", "com.phonepe.app", "net.one97.paytm", "in.org.npci.upiapp",
  "com.sbi.SBIFreedomPlus", "com.axis.mobile", "com.csam.icici.bank.imobile", "com.idfcfirstbank.optimus",
  "com.agilebits.onepassword", "com.x8bit.bitwarden", "com.google.android.apps.authenticator2", "com.azure.authenticator",
].sort();

export function packagePolicy(config: Config): {
  version: number; issuedAt: string; expiresAt: string; packages: string[]; certificateFingerprints: Readonly<Record<string, readonly string[]>>; signedPayload: string; signature: string | null;
} {
  const issuedAt = new Date();
  const payload = {
    version: 1,
    issuedAt: issuedAt.toISOString(),
    expiresAt: new Date(issuedAt.getTime() + 7 * 24 * 60 * 60 * 1_000).toISOString(),
    packages: hardBlockedPackages,
    certificateFingerprints: config.policyCertificateFingerprints,
  };
  const payloadBytes = Buffer.from(JSON.stringify(payload));
  const signedPayload = payloadBytes.toString("base64");
  if (!config.policySigningPrivateKeyPem) return { ...payload, signedPayload, signature: null };
  const signature = sign(null, payloadBytes, createPrivateKey(config.policySigningPrivateKeyPem)).toString("base64");
  return { ...payload, signedPayload, signature };
}
