import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { CalendarService, type CalendarEventInput } from "./calendar.ts";
import { loadConfig } from "./config.ts";

const config = loadConfig({ NODE_ENV: "development", INSTALLATION_TOKEN_SECRET: "calendar-test-secret".repeat(3) });
const user = { id: "03d271db-8a32-4ac5-8612-18637c0e66e9", accessToken: "token", claims: {} };
const event: CalendarEventInput = {
  title: "Project review",
  startDateTime: "2026-07-25T10:00:00+05:30",
  endDateTime: "2026-07-25T10:30:00+05:30",
  timeZone: "Asia/Calcutta",
};

describe("Calendar confirmation", () => {
  it("accepts only the exact user, operation, and preview payload", () => {
    const calendar = new CalendarService(config);
    const prepared = calendar.prepareWrite(user, "CREATE", event);
    assert.doesNotThrow(() => calendar.verifyWrite(user, prepared.confirmationToken, "CREATE", event));
    assert.throws(() => calendar.verifyWrite(user, prepared.confirmationToken, "CREATE", { ...event, title: "Changed" }), /does not match/);
    assert.throws(() => calendar.verifyWrite({ ...user, id: "6c048086-f467-4e2a-80dd-2694cbb9b43f" }, prepared.confirmationToken, "CREATE", event), /does not match/);
  });

  it("rejects invalid time ranges before issuing a preview", () => {
    const calendar = new CalendarService(config);
    assert.throws(() => calendar.prepareWrite(user, "CREATE", { ...event, endDateTime: event.startDateTime }), /start and end/);
  });
});
