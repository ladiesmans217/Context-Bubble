import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { CloudCryptoError, CloudMemoryCrypto } from "./cloud-crypto.ts";
import type { CloudMemoryPayload } from "./contracts.ts";

const payload: CloudMemoryPayload = {
  id: "018f6f25-9cf1-7d6b-8b27-728fb9d06012",
  type: "fact",
  summary: "Project deadline",
  value: "The demo is due on Friday.",
  sourcePackage: "com.example",
  sensitivity: "normal",
  createdAtEpochMs: 1_700_000_000_000,
  expiresAtEpochMs: null,
  pinned: false,
};

describe("CloudMemoryCrypto", () => {
  it("round-trips authenticated user-bound ciphertext", async () => {
    const crypto = new CloudMemoryCrypto(new Map([[1, "a".repeat(48)]]), 1);
    const encrypted = await crypto.encrypt("user-a", payload);
    assert.equal(encrypted.keyVersion, 1);
    assert.notEqual(encrypted.ciphertext, JSON.stringify(payload));
    assert.deepEqual(await crypto.decrypt("user-a", encrypted), payload);
    await assert.rejects(() => crypto.decrypt("user-b", encrypted), CloudCryptoError);
  });

  it("detects tampering and retains old keys during rotation", async () => {
    const oldCrypto = new CloudMemoryCrypto(new Map([[1, "a".repeat(48)]]), 1);
    const encrypted = await oldCrypto.encrypt("user-a", payload);
    const rotated = new CloudMemoryCrypto(new Map([[1, "a".repeat(48)], [2, "b".repeat(48)]]), 2);
    assert.deepEqual(await rotated.decrypt("user-a", encrypted), payload);
    await assert.rejects(
      () => rotated.decrypt("user-a", { ...encrypted, ciphertext: `${encrypted.ciphertext.slice(0, -1)}A` }),
      CloudCryptoError,
    );
  });
});
