import { spawn } from "node:child_process";
import { createCipheriv, createHash, randomBytes } from "node:crypto";
import { mkdir, rm, writeFile } from "node:fs/promises";
import http from "node:http";
import { dirname, join } from "node:path";

const port = 3107;
const mockPort = 3117;
const base = `http://127.0.0.1:${port}`;
const storeFile = join(process.cwd(), ".mawa", "google-calendar-state.json");
const stateSecret = "smoke-state-secret-1234567890";

function encryptString(value, secret) {
  const iv = randomBytes(12);
  const key = createHash("sha256").update(secret).digest();
  const cipher = createCipheriv("aes-256-gcm", key, iv);
  const encrypted = Buffer.concat([cipher.update(value, "utf8"), cipher.final()]);
  return `${iv.toString("base64url")}.${cipher.getAuthTag().toString("base64url")}.${encrypted.toString("base64url")}`;
}

await mkdir(dirname(storeFile), { recursive: true });
await writeFile(
  storeFile,
  encryptString(
    JSON.stringify({
      version: 1,
      slots: {
        personal: {
          slot: "personal",
          refreshToken: "refresh-token",
          scope: "https://www.googleapis.com/auth/calendar.readonly",
          connectedAt: "2026-07-04T00:00:00.000Z",
          updatedAt: "2026-07-04T00:00:00.000Z",
          email: "smoke@example.com",
        },
      },
    }),
    stateSecret,
  ),
  "utf8",
);

const mockServer = http.createServer((request, response) => {
  const url = new URL(request.url ?? "/", `http://127.0.0.1:${mockPort}`);
  if (url.pathname === "/token") {
    response.writeHead(200, { "Content-Type": "application/json" });
    response.end(
      JSON.stringify({
        access_token: "access-token",
        scope: "https://www.googleapis.com/auth/calendar.readonly",
        expires_in: 3600,
      }),
    );
    return;
  }

  if (url.pathname === "/events") {
    response.writeHead(200, { "Content-Type": "application/json" });
    response.end(
      JSON.stringify({
        items: [
          {
            summary: "Ship Mawa",
            status: "confirmed",
            start: { dateTime: "2026-07-04T13:00:00-04:00" },
          },
        ],
      }),
    );
    return;
  }

  if (url.pathname === "/revoke") {
    response.writeHead(200);
    response.end();
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
    GOOGLE_CLIENT_ID: "smoke-client",
    GOOGLE_CLIENT_SECRET: "smoke-secret",
    MAWA_SIGNING_SECRET: "smoke-signing-secret-1234567890",
    MAWA_STATE_ENCRYPTION_SECRET: stateSecret,
    GOOGLE_TOKEN_URL_OVERRIDE: `http://127.0.0.1:${mockPort}/token`,
    GOOGLE_REVOKE_URL_OVERRIDE: `http://127.0.0.1:${mockPort}/revoke`,
    GOOGLE_CALENDAR_EVENTS_URL: `http://127.0.0.1:${mockPort}/events`,
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
  const panel = manifest.scene?.panels?.find((entry) => entry.connector === "calendar-personal");
  if (!panel || panel.title !== "Ship Mawa") {
    throw new Error("authenticated calendar panel was not composed");
  }
  process.stdout.write("manifest + private calendar OAuth smoke passed\n");
} finally {
  server.kill("SIGTERM");
  await new Promise((resolve) => mockServer.close(resolve));
  await rm(storeFile, { force: true });
}
