import { spawn } from "node:child_process";

const port = 3107;
const base = `http://127.0.0.1:${port}`;
const server = spawn(process.execPath, ["node_modules/next/dist/bin/next", "start", "-p", String(port)], {
  stdio: "ignore",
  env: {
    ...process.env,
    MAWA_DEVICE_TOKEN: "smoke-token",
    // Google's public US-holiday feed exercises the real ICS parser without
    // exposing a private calendar in CI.
    PERSONAL_CALENDAR_ICS_URL:
      "https://calendar.google.com/calendar/ical/en.usa%23holiday%40group.v.calendar.google.com/public/basic.ics",
  },
});

async function waitForServer() {
  for (let attempt = 0; attempt < 40; attempt += 1) {
    try {
      const response = await fetch(`${base}/api/health`);
      if (response.ok) return;
    } catch {
      // Server has not bound the port yet.
    }
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  throw new Error("Next server did not become ready");
}

try {
  await waitForServer();
  const response = await fetch(`${base}/api/manifest?lat=42.36&lon=-71.06`, {
    headers: { authorization: "Bearer smoke-token" },
  });
  if (!response.ok) throw new Error(`manifest smoke returned ${response.status}`);
  const manifest = await response.json();
  const personal = manifest.connectors?.find((connector) => connector.id === "calendar-personal");
  if (!personal || personal.status !== "ready") {
    throw new Error("authenticated calendar connector did not execute");
  }
  process.stdout.write("manifest + private calendar smoke passed\n");
} finally {
  server.kill("SIGTERM");
}
