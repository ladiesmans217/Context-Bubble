import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { parseMemorySyncRequest, ValidationError } from "./contracts.ts";

describe("cloud memory contracts", () => {
  it("accepts a bounded, versioned shared-memory mutation", () => {
    const parsed = parseMemorySyncRequest({
      cursor: 7,
      mutations: [{
        operation: "UPSERT",
        id: "018f6f25-9cf1-7d6b-8b27-728fb9d06012",
        baseVersion: 0,
        idempotencyKey: "018f6f25-9cf2-7d6b-8b27-728fb9d06012",
        payload: {
          id: "018f6f25-9cf1-7d6b-8b27-728fb9d06012",
          type: "fact",
          summary: "Summary",
          value: "Value",
          sensitivity: "normal",
          createdAtEpochMs: 1,
          expiresAtEpochMs: null,
          pinned: false,
        },
      }],
    });
    assert.equal(parsed.cursor, 7);
    assert.equal(parsed.mutations[0]?.baseVersion, 0);
  });

  it("rejects an oversized sync batch", () => {
    assert.throws(
      () => parseMemorySyncRequest({ cursor: 0, mutations: Array.from({ length: 51 }, () => ({})) }),
      ValidationError,
    );
  });
});
