import { get, list, put } from "@vercel/blob";

const ROOM_MOMENT_SCHEMA_VERSION = 1 as const;
const ROOM_MOMENT_ACCESS = process.env.MAWA_LIVE_BLOB_ACCESS === "public" ? "public" : "private";
const MAX_RECENT_MOMENTS = 10;

export interface RoomMomentInsight {
  title: string;
  summary: string;
  activity: string;
  confidence: number;
}

export interface RoomMomentRecord {
  id: string;
  capturedAt: string;
  receivedAt: string;
  labels: string[];
  changeScore: number;
  luma: number;
  faceCount: number;
  recognized: "me" | "other" | "unknown" | "none";
  personLabel?: string;
  musicActive: boolean;
  groove: number;
  imageDataUrl: string;
  insight: RoomMomentInsight;
}

export interface RoomMomentStore {
  schemaVersion: typeof ROOM_MOMENT_SCHEMA_VERSION;
  deviceId: string;
  lastUpdatedAt: string;
  recent: RoomMomentRecord[];
}

export interface RoomMomentSummary {
  latestScene?: string;
  activityPattern?: string;
}

const momentCache = new Map<string, RoomMomentStore>();

function blobConfigured(): boolean {
  return Boolean(process.env.BLOB_STORE_ID || process.env.BLOB_READ_WRITE_TOKEN);
}

function storePath(deviceId: string): string {
  return `mawa/room-moments/${deviceId}.json`;
}

function fallbackStore(deviceId: string, now: Date): RoomMomentStore {
  return {
    schemaVersion: ROOM_MOMENT_SCHEMA_VERSION,
    deviceId,
    lastUpdatedAt: now.toISOString(),
    recent: [],
  };
}

export async function readRoomMomentStore(deviceId: string, now: Date): Promise<RoomMomentStore> {
  const cached = momentCache.get(deviceId);
  if (cached) return cached;

  const fallback = fallbackStore(deviceId, now);
  if (!blobConfigured()) {
    momentCache.set(deviceId, fallback);
    return fallback;
  }

  try {
    const path = storePath(deviceId);
    const result = await list({ prefix: path, limit: 1 });
    const blob = result.blobs.find((entry) => entry.pathname === path) ?? result.blobs[0];
    if (!blob) {
      momentCache.set(deviceId, fallback);
      return fallback;
    }
    const payload = await get(blob.url, { access: ROOM_MOMENT_ACCESS });
    if (!payload?.stream) {
      momentCache.set(deviceId, fallback);
      return fallback;
    }
    const parsed = JSON.parse(await new Response(payload.stream).text()) as RoomMomentStore;
    momentCache.set(deviceId, parsed);
    return parsed;
  } catch (error) {
    console.warn("Room moments read failed", error);
    momentCache.set(deviceId, fallback);
    return fallback;
  }
}

export async function saveRoomMomentStore(store: RoomMomentStore): Promise<void> {
  momentCache.set(store.deviceId, store);
  if (!blobConfigured()) return;

  try {
    await put(storePath(store.deviceId), JSON.stringify(store), {
      access: ROOM_MOMENT_ACCESS,
      allowOverwrite: true,
      addRandomSuffix: false,
      cacheControlMaxAge: 0,
      contentType: "application/json; charset=utf-8",
    });
  } catch (error) {
    console.warn("Room moments write failed", error);
  }
}

export function rememberRoomMoment(
  store: RoomMomentStore,
  moment: RoomMomentRecord,
  now: Date,
): RoomMomentStore {
  const deduped = store.recent.filter((entry) => entry.id !== moment.id);
  return {
    ...store,
    lastUpdatedAt: now.toISOString(),
    recent: [moment, ...deduped].slice(0, MAX_RECENT_MOMENTS),
  };
}

export function summarizeRoomMoments(store: RoomMomentStore): RoomMomentSummary {
  const latest = store.recent[0];
  const activityCounts = new Map<string, number>();
  for (const moment of store.recent) {
    if (moment.insight.confidence < 0.45) continue;
    const key = moment.insight.activity.trim().toLowerCase();
    if (!key || key === "unclear") continue;
    activityCounts.set(key, (activityCounts.get(key) ?? 0) + 1);
  }
  const topActivity = [...activityCounts.entries()].sort((left, right) => right[1] - left[1])[0];
  return {
    latestScene: latest ? latest.insight.summary.slice(0, 140) : undefined,
    activityPattern:
      topActivity && topActivity[1] >= 2
        ? `Recent room activity often looks like ${topActivity[0]}.`
        : undefined,
  };
}
