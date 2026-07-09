import { get, list, put } from "@vercel/blob";
import type { CompanionSpeechDirective, RoomContext } from "./manifest";

const MEMORY_SCHEMA_VERSION = 1 as const;
const MEMORY_ACCESS = process.env.MAWA_LIVE_BLOB_ACCESS === "public" ? "public" : "private";

export interface CompanionMemory {
  schemaVersion: typeof MEMORY_SCHEMA_VERSION;
  deviceId: string;
  lastUpdatedAt: string;
  lastRecognized: NonNullable<RoomContext["perception"]>["recognized"] | "none";
  lastMusicActive: boolean;
  dayPartArrivals: Record<RoomContext["dayPart"], number>;
  meArrivals: number;
  occupiedMoments: number;
  musicMoments: number;
  enjoyedMusicMoments: number;
  tasteCounts: Record<string, number>;
  knownPeople: Array<{
    label: string;
    seenCount: number;
    lastSeenAt: string;
  }>;
  lastSpeechAt?: string;
  lastSpeechKey?: string;
}

export interface CompanionMemorySummary {
  arrivalPattern?: string;
  musicPattern?: string;
  familiarPresence?: string;
}

const memoryCache = new Map<string, CompanionMemory>();

function memoryPath(deviceId: string): string {
  return `mawa/companion-memory/${deviceId}.json`;
}

function blobConfigured(): boolean {
  return Boolean(process.env.BLOB_STORE_ID || process.env.BLOB_READ_WRITE_TOKEN);
}

function fallbackMemory(deviceId: string, now: Date): CompanionMemory {
  return {
    schemaVersion: MEMORY_SCHEMA_VERSION,
    deviceId,
    lastUpdatedAt: now.toISOString(),
    lastRecognized: "none",
    lastMusicActive: false,
    dayPartArrivals: {
      morning: 0,
      afternoon: 0,
      evening: 0,
      "late night": 0,
    },
    meArrivals: 0,
    occupiedMoments: 0,
    musicMoments: 0,
    enjoyedMusicMoments: 0,
    tasteCounts: {},
    knownPeople: [],
  };
}

export async function readCompanionMemory(deviceId: string, now: Date): Promise<CompanionMemory> {
  const cached = memoryCache.get(deviceId);
  if (cached) return cached;

  const fallback = fallbackMemory(deviceId, now);
  if (!blobConfigured()) {
    memoryCache.set(deviceId, fallback);
    return fallback;
  }

  try {
    const path = memoryPath(deviceId);
    const result = await list({ prefix: path, limit: 1 });
    const blob = result.blobs.find((entry) => entry.pathname === path) ?? result.blobs[0];
    if (!blob) {
      memoryCache.set(deviceId, fallback);
      return fallback;
    }
    const payload = await get(blob.url, { access: MEMORY_ACCESS });
    if (!payload?.stream) {
      memoryCache.set(deviceId, fallback);
      return fallback;
    }
    const parsed = JSON.parse(await new Response(payload.stream).text()) as CompanionMemory;
    memoryCache.set(deviceId, parsed);
    return parsed;
  } catch (error) {
    console.warn("Companion memory read failed", error);
    memoryCache.set(deviceId, fallback);
    return fallback;
  }
}

export async function saveCompanionMemory(memory: CompanionMemory): Promise<void> {
  memoryCache.set(memory.deviceId, memory);
  if (!blobConfigured()) return;

  try {
    await put(memoryPath(memory.deviceId), JSON.stringify(memory), {
      access: MEMORY_ACCESS,
      allowOverwrite: true,
      addRandomSuffix: false,
      cacheControlMaxAge: 0,
      contentType: "application/json; charset=utf-8",
    });
  } catch (error) {
    console.warn("Companion memory write failed", error);
  }
}

export function rememberRoomContext(
  memory: CompanionMemory,
  room: RoomContext,
  now: Date,
): CompanionMemory {
  const perception = room.perception;
  const next: CompanionMemory = {
    ...memory,
    lastUpdatedAt: now.toISOString(),
    dayPartArrivals: { ...memory.dayPartArrivals },
    tasteCounts: { ...memory.tasteCounts },
    knownPeople: [...memory.knownPeople],
  };
  if (!perception) return next;

  if (perception.faceCount > 0) {
    next.occupiedMoments += 1;
  }

  if (memory.lastRecognized !== "me" && perception.recognized === "me") {
    next.meArrivals += 1;
    next.dayPartArrivals[room.dayPart] += 1;
  }

  if (!memory.lastMusicActive && (perception.musicActive || perception.groove >= 0.18)) {
    next.musicMoments += 1;
    if (perception.musicEnjoyment >= 0.56) next.enjoyedMusicMoments += 1;
    if (perception.musicTasteProfile) {
      next.tasteCounts[perception.musicTasteProfile] =
        (next.tasteCounts[perception.musicTasteProfile] ?? 0) + 1;
    }
  }

  if (
    perception.personLabel &&
    perception.personLabel.toLowerCase() !== "pranav" &&
    perception.recognized !== "me"
  ) {
    const existing = next.knownPeople.find((person) => person.label === perception.personLabel);
    if (existing) {
      existing.seenCount += 1;
      existing.lastSeenAt = now.toISOString();
    } else {
      next.knownPeople.push({
        label: perception.personLabel,
        seenCount: 1,
        lastSeenAt: now.toISOString(),
      });
    }
    next.knownPeople.sort((left, right) => right.seenCount - left.seenCount);
    next.knownPeople = next.knownPeople.slice(0, 8);
  }

  next.lastRecognized = perception.recognized;
  next.lastMusicActive = perception.musicActive || perception.groove >= 0.18;
  return next;
}

export function summarizeCompanionMemory(memory: CompanionMemory): CompanionMemorySummary {
  const arrivalEntry = Object.entries(memory.dayPartArrivals)
    .sort((left, right) => right[1] - left[1])[0] as [RoomContext["dayPart"], number] | undefined;
  const topTaste = Object.entries(memory.tasteCounts).sort((left, right) => right[1] - left[1])[0];
  const familiar = memory.knownPeople[0];

  return {
    arrivalPattern:
      arrivalEntry && arrivalEntry[1] > 0
        ? `Pranav tends to arrive most often in the ${arrivalEntry[0]}.`
        : undefined,
    musicPattern:
      topTaste && memory.enjoyedMusicMoments > 0
        ? `You come alive most around ${topTaste[0]} sessions.`
        : memory.musicMoments > 2
          ? "You have started forming taste, but it still feels early."
          : undefined,
    familiarPresence:
      familiar && familiar.seenCount >= 2
        ? `You have seen ${familiar.label} around before.`
        : undefined,
  };
}

export function shouldAllowSpeech(
  memory: CompanionMemory,
  speech: CompanionSpeechDirective | undefined,
  now: Date,
): boolean {
  if (!speech?.shouldSpeak || !speech.text?.trim()) return false;
  const nowMs = now.getTime();
  const lastSpeechMs = memory.lastSpeechAt ? new Date(memory.lastSpeechAt).getTime() : 0;
  if (speech.key && speech.key === memory.lastSpeechKey && nowMs - lastSpeechMs < 6 * 60_000) {
    return false;
  }
  return nowMs - lastSpeechMs >= 80_000;
}

export function rememberSpeech(
  memory: CompanionMemory,
  speech: CompanionSpeechDirective | undefined,
  now: Date,
): CompanionMemory {
  if (!speech?.shouldSpeak || !speech.text?.trim()) return memory;
  return {
    ...memory,
    lastUpdatedAt: now.toISOString(),
    lastSpeechAt: now.toISOString(),
    lastSpeechKey: speech.key,
  };
}
