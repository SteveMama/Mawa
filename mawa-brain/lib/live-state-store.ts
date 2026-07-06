import { get, list, put } from "@vercel/blob";
import type { LiveDeviceState } from "./live-state";

const LIVE_STATE_PATH = "mawa/live-device-state.json";
const LIVE_BLOB_ACCESS = process.env.MAWA_LIVE_BLOB_ACCESS === "public" ? "public" : "private";

let memoryState: LiveDeviceState | null = null;

function blobConfigured(): boolean {
  return Boolean(process.env.BLOB_STORE_ID || process.env.BLOB_READ_WRITE_TOKEN);
}

export async function saveLiveDeviceState(state: LiveDeviceState): Promise<LiveDeviceState> {
  const normalized: LiveDeviceState = {
    ...state,
    receivedAt: new Date().toISOString(),
  };
  memoryState = normalized;

  if (!blobConfigured()) return normalized;

  try {
    await put(LIVE_STATE_PATH, JSON.stringify(normalized), {
      access: LIVE_BLOB_ACCESS,
      allowOverwrite: true,
      addRandomSuffix: false,
      cacheControlMaxAge: 0,
      contentType: "application/json; charset=utf-8",
    });
  } catch (error) {
    console.warn("Live state blob write failed", {
      access: LIVE_BLOB_ACCESS,
      message: error instanceof Error ? error.message : String(error),
    });
  }

  return normalized;
}

export async function readLiveDeviceState(): Promise<LiveDeviceState | null> {
  if (memoryState) return memoryState;
  if (!blobConfigured()) return null;

  try {
    const result = await list({ prefix: LIVE_STATE_PATH, limit: 1 });
    const blob = result.blobs.find((entry) => entry.pathname === LIVE_STATE_PATH) ?? result.blobs[0];
    if (!blob) return null;
    const payload = await get(blob.url, { access: LIVE_BLOB_ACCESS });
    if (!payload?.stream) return null;
    const raw = await new Response(payload.stream).text();
    const parsed = JSON.parse(raw) as LiveDeviceState;
    memoryState = parsed;
    return parsed;
  } catch (error) {
    console.warn("Live state blob read failed", {
      access: LIVE_BLOB_ACCESS,
      message: error instanceof Error ? error.message : String(error),
    });
    return memoryState;
  }
}
