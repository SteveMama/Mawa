import { randomUUID } from "node:crypto";
import { NextRequest, NextResponse } from "next/server";
import { isAdminAuthorized, isDeviceAuthorized } from "../../../../lib/auth";
import { interpretRoomMoment, type VisionMomentInsight } from "../../../../lib/companion/groq";
import {
  readRoomMomentStore,
  rememberRoomMoment,
  saveRoomMomentStore,
  type RoomMomentRecord,
} from "../../../../lib/room-moments";

export const dynamic = "force-dynamic";

function stringValue(value: unknown, maxLength: number, fallback = ""): string {
  const text = typeof value === "string" ? value.trim() : "";
  return (text || fallback).slice(0, maxLength);
}

function optionalString(value: unknown, maxLength: number): string | undefined {
  const text = typeof value === "string" ? value.trim() : "";
  return text ? text.slice(0, maxLength) : undefined;
}

function unit(value: unknown, fallback = 0): number {
  const numeric = typeof value === "number" ? value : Number(value);
  return Number.isFinite(numeric) ? Math.min(1, Math.max(0, numeric)) : fallback;
}

function luminance(value: unknown, fallback = 0): number {
  const numeric = typeof value === "number" ? value : Number(value);
  return Number.isFinite(numeric) ? Math.min(255, Math.max(0, numeric)) : fallback;
}

function integer(value: unknown, fallback: number, min: number, max: number): number {
  const numeric =
    typeof value === "number" ? Math.trunc(value) :
    typeof value === "string" ? Number.parseInt(value, 10) :
    Number.NaN;
  return Number.isFinite(numeric) ? Math.min(max, Math.max(min, numeric)) : fallback;
}

function flag(value: unknown): boolean {
  return value === true || value === 1 || value === "1" || value === "true";
}

function recognized(value: unknown): RoomMomentRecord["recognized"] {
  switch (String(value ?? "").trim().toLowerCase()) {
    case "me":
      return "me";
    case "other":
      return "other";
    case "unknown":
      return "unknown";
    default:
      return "none";
  }
}

function isoDate(value: unknown): string {
  const parsed = new Date(typeof value === "string" ? value : Date.now());
  return Number.isNaN(parsed.getTime()) ? new Date().toISOString() : parsed.toISOString();
}

function stringList(value: unknown, maxItems: number, maxLength: number): string[] {
  if (!Array.isArray(value)) return [];
  return value
    .map((item) => (typeof item === "string" ? item.trim().toLowerCase() : ""))
    .filter(Boolean)
    .map((item) => item.slice(0, maxLength))
    .filter((item, index, self) => self.indexOf(item) === index)
    .slice(0, maxItems);
}

function imageBase64(value: unknown): string {
  const text = typeof value === "string" ? value.trim() : "";
  return /^[A-Za-z0-9+/=]+$/.test(text) ? text.slice(0, 900_000) : "";
}

function fallbackInsight(moment: {
  labels: string[];
  faceCount: number;
  musicActive: boolean;
  groove: number;
}): VisionMomentInsight {
  const labelLine = moment.labels.join(", ");
  if (moment.musicActive && moment.groove >= 0.2) {
    return {
      title: "Music in the room",
      summary: "The room seems active around music or rhythm.",
      activity: "listening to music",
      confidence: 0.56,
    };
  }
  if (moment.faceCount > 0) {
    return {
      title: "Presence shift",
      summary: labelLine
        ? `Someone is in view and the room now reads as ${labelLine}.`
        : "Someone is in view and the room state shifted.",
      activity: "present in room",
      confidence: 0.46,
    };
  }
  return {
    title: "Room shift",
    summary: labelLine
      ? `The room changed and now loosely reads as ${labelLine}.`
      : "The room changed, but the moment stays ambiguous.",
    activity: "unclear",
    confidence: 0.34,
  };
}

async function normalizeMoment(payload: unknown): Promise<RoomMomentRecord> {
  const raw = payload && typeof payload === "object" ? (payload as Record<string, unknown>) : {};
  const base64 = imageBase64(raw.imageBase64);
  if (!base64) {
    throw new Error("imageBase64 is required");
  }

  const moment = {
    capturedAt: isoDate(raw.capturedAt),
    labels: stringList(raw.labels, 6, 24),
    changeScore: unit(raw.changeScore),
    luma: luminance(raw.luma),
    faceCount: integer(raw.faceCount, 0, 0, 8),
    recognized: recognized(raw.recognized),
    personLabel: optionalString(raw.personLabel, 40),
    musicActive: flag(raw.musicActive),
    groove: unit(raw.groove),
    imageBase64: base64,
  };

  let insight = fallbackInsight(moment);
  try {
    const interpreted = await interpretRoomMoment(moment);
    if (interpreted) insight = interpreted;
  } catch (error) {
    console.warn("Room moment interpretation failed", error);
  }

  return {
    id: randomUUID(),
    capturedAt: moment.capturedAt,
    receivedAt: new Date().toISOString(),
    labels: moment.labels,
    changeScore: moment.changeScore,
    luma: moment.luma,
    faceCount: moment.faceCount,
    recognized: moment.recognized,
    personLabel: moment.personLabel,
    musicActive: moment.musicActive,
    groove: moment.groove,
    imageDataUrl: `data:image/jpeg;base64,${base64}`,
    insight,
  };
}

export async function GET(request: NextRequest) {
  if (!isAdminAuthorized(request)) {
    return NextResponse.json({ error: "provide the dashboard admin token" }, { status: 401 });
  }

  const deviceId = stringValue(request.nextUrl.searchParams.get("deviceId"), 48, "oneplus-wall");
  return NextResponse.json(
    {
      moments: await readRoomMomentStore(deviceId, new Date()),
      adminAuthorized: true,
    },
    {
      headers: {
        "Cache-Control": "private, no-store",
        Vary: "Authorization, Cookie",
      },
    },
  );
}

export async function POST(request: NextRequest) {
  if (!isDeviceAuthorized(request)) {
    return NextResponse.json({ error: "unauthorized device" }, { status: 401 });
  }

  try {
    const payload = await request.json();
    const deviceId = stringValue(
      payload && typeof payload === "object" ? (payload as Record<string, unknown>).deviceId : undefined,
      48,
      "oneplus-wall",
    );
    const now = new Date();
    const current = await readRoomMomentStore(deviceId, now);
    const moment = await normalizeMoment(payload);
    const saved = rememberRoomMoment(current, moment, now);
    await saveRoomMomentStore(saved);

    return NextResponse.json(
      {
        ok: true,
        stored: saved.recent.length,
        latestId: moment.id,
        latestTitle: moment.insight.title,
      },
      {
        headers: {
          "Cache-Control": "private, no-store",
          Vary: "Authorization",
        },
      },
    );
  } catch (error) {
    return NextResponse.json(
      { error: error instanceof Error ? error.message : "invalid room moment payload" },
      { status: 400 },
    );
  }
}
