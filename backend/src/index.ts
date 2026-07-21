import { loadConfig } from "./config.ts";
import { serve } from "@hono/node-server";
import { createHonoApi } from "./hono-app.ts";

const config = loadConfig();
const app = createHonoApi(config);

serve({ fetch: app.fetch, port: config.port, hostname: config.host }, () => {
  console.log(`Context Bubble backend listening on http://${config.host}:${config.port}`);
});
