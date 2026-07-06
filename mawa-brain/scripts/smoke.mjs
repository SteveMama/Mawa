import { spawn } from "node:child_process";
import http from "node:http";

const port = 3107;
const mockPort = 3117;
const base = `http://127.0.0.1:${port}`;

// A timed event ~1 hour from now (UTC) so it lands inside the connector's
// next-24-hours window regardless of when the smoke test runs.
function icsStamp(date) {
  return date.toISOString().replace(/[-:]/g, "").replace(/\.\d{3}Z$/, "Z");
}
const eventStart = new Date(Date.now() + 60 * 60_000);
const ics = [
  "BEGIN:VCALENDAR",
  "VERSION:2.0",
  "BEGIN:VEVENT",
  "UID:smoke-1@mawa",
  `DTSTART:${icsStamp(eventStart)}`,
  "SUMMARY:Ship Mawa",
  "STATUS:CONFIRMED",
  "END:VEVENT",
  "END:VCALENDAR",
].join("\r\n");

const mockServer = http.createServer((request, response) => {
  const url = new URL(request.url ?? "/", `http://127.0.0.1:${mockPort}`);
  if (url.pathname === "/personal.ics") {
    response.writeHead(200, { "Content-Type": "text/calendar" });
    response.end(ics);
    return;
  }
  response.writeHead(404);
  response.end();
});

await new Promise((resolve) => mockServer.listen(mockPort, "127.0.0.1", resolve));

const server = spawn(process.execPath, ["node_modules/next/dist/bin/next", "start", "-p", String(port)], {
  stdio: "ignore",
  env: {
    ...process.env,
    MAWA_DEVICE_TOKEN: "smoke-token",
    MAWA_CALENDAR_PERSONAL_ICS: `http://127.0.0.1:${mockPort}/personal.ics`,
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
    throw new Error("authenticated ICS calendar connector did not execute");
  }
  const panel = manifest.scene?.panels?.find((entry) => entry.connector === "calendar-personal");
  if (!panel || panel.title !== "Ship Mawa") {
    throw new Error("authenticated ICS calendar panel was not composed");
  }
  process.stdout.write("manifest + private ICS calendar smoke passed\n");
} finally {
  server.kill("SIGTERM");
  await new Promise((resolve) => mockServer.close(resolve));
}
