import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { createHash } from "node:crypto";
import { McpConfirmationCodec, type PreparedChange } from "./mcp.ts";

const secret = "confirmation-test-secret".repeat(3);
const prepared: PreparedChange = {
  userId: "03d271db-8a32-4ac5-8612-18637c0e66e9",
  clientId: "chatgpt-client",
  operation: "UPSERT",
  baseVersion: 3,
  payload: {
    id: "6c048086-f467-4e2a-80dd-2694cbb9b43f",
    type: "fact",
    summary: "Project deadline",
    value: "Submit on Friday",
    sensitivity: "normal",
    pinned: false,
    createdAtEpochMs: 1,
    expiresAtEpochMs: null,
    sourcePackage: "chatgpt-mcp",
  },
  nonce: "2285f8b2-760f-4ca0-b16c-05327ef94061",
  expiresAtEpochMs: Date.now() + 60_000,
  payloadHash: "placeholder",
};

describe("MCP confirmation binding", () => {
  it("binds a signed preview to user, client, operation, version, payload, expiry, and nonce", () => {
    const codec = new McpConfirmationCodec(secret);
    const token = codec.sign(withActualPayloadHash(prepared, codec));
    const verified = codec.verify(token, prepared.userId, prepared.clientId, prepared.operation);
    assert.equal(verified.baseVersion, 3);
    assert.equal(verified.nonce, prepared.nonce);
    assert.equal(verified.payload.value, "Submit on Friday");
    assert.throws(() => codec.verify(token, prepared.userId, "different-client", prepared.operation), /does not belong/);
    assert.throws(() => codec.verify(`${token.slice(0, -2)}aa`, prepared.userId, prepared.clientId, prepared.operation), /signature/);
  });

  it("rejects expired previews", () => {
    const codec = new McpConfirmationCodec(secret);
    const expired = { ...prepared, expiresAtEpochMs: Date.now() - 1 };
    const token = codec.sign(withActualPayloadHash(expired, codec));
    assert.throws(() => codec.verify(token, prepared.userId, prepared.clientId, prepared.operation), /expired/);
  });
});

function withActualPayloadHash(value: PreparedChange, codec: McpConfirmationCodec): PreparedChange {
  // The codec verifies the signed payload hash. Generate a normally prepared token once, inspect
  // the payload, then reuse its deterministic hash without exposing an additional production API.
  const provisional = codec.sign(value).split(".")[0]!;
  const parsed = JSON.parse(Buffer.from(provisional, "base64url").toString("utf8")) as PreparedChange;
  return { ...parsed, payloadHash: createHash("sha256").update(JSON.stringify(parsed.payload)).digest("hex") };
}
