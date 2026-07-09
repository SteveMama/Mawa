import { NextRequest, NextResponse } from "next/server";
import { isAdminAuthorized, isDeviceAuthorized } from "../../../../lib/auth";
import {
  LIVE_DEVICE_STATE_SCHEMA_VERSION,
  type LiveDeviceState,
  type LiveFeeling,
  type LiveMusic,
  type LivePresence,
  type LiveStatus,
  type LiveThought,
} from "../../../../lib/live-state";
import { readLiveDeviceState, saveLiveDeviceState } from "../../../../lib/live-state-store";

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

function recognized(value: unknown): LivePresence["recognized"] {
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

function mood(value: unknown): LiveFeeling["mood"] {
  switch (String(value ?? "").trim().toLowerCase()) {
    case "happy":
      return "happy";
    case "grumpy":
      return "grumpy";
    case "sleepy":
      return "sleepy";
    case "suspicious":
      return "suspicious";
    case "excited":
      return "excited";
    default:
      return "neutral";
  }
}

function isoDate(value: unknown): string {
  const parsed = new Date(typeof value === "string" ? value : Date.now());
  return Number.isNaN(parsed.getTime()) ? new Date().toISOString() : parsed.toISOString();
}

function thought(value: unknown): LiveThought | undefined {
  if (!value || typeof value !== "object") return undefined;
  const raw = value as Record<string, unknown>;
  const eyebrow = stringValue(raw.eyebrow, 20);
  const title = stringValue(raw.title, 32);
  if (!eyebrow && !title) return undefined;
  return {
    eyebrow: eyebrow || "MAWA",
    title: title || "Quiet orbit",
    detail: stringValue(raw.detail, 64),
    accent: optionalString(raw.accent, 16),
  };
}

function feeling(value: unknown): LiveFeeling {
  const raw = value && typeof value === "object" ? (value as Record<string, unknown>) : {};
  return {
    mood: mood(raw.mood),
    summary: stringValue(raw.summary, 120, "Quietly holding the room."),
    attention: stringValue(raw.attention, 40, "wandering"),
    sleeping: flag(raw.sleeping),
    covered: flag(raw.covered),
    ambientDark: flag(raw.ambientDark),
    energy: unit(raw.energy),
    expressiveness: unit(raw.expressiveness),
  };
}

function presence(value: unknown): LivePresence {
  const raw = value && typeof value === "object" ? (value as Record<string, unknown>) : {};
  return {
    faceCount: integer(raw.faceCount, 0, 0, 8),
    recognized: recognized(raw.recognized),
    personLabel: optionalString(raw.personLabel, 40),
    proximity: unit(raw.proximity),
    identityLock: flag(raw.identityLock),
    following: flag(raw.following),
  };
}

function music(value: unknown): LiveMusic {
  const raw = value && typeof value === "object" ? (value as Record<string, unknown>) : {};
  return {
    active: flag(raw.active),
    groove: unit(raw.groove),
    tasteProfile: optionalString(raw.tasteProfile, 64),
    stance: optionalString(raw.stance, 40),
    enjoyment: unit(raw.enjoyment),
    affinity: unit(raw.affinity),
    preferredIntensity: unit(raw.preferredIntensity),
    steadiness: unit(raw.steadiness),
    lateNightBias: unit(raw.lateNightBias),
    sessionCount: integer(raw.sessionCount, 0, 0, 100_000),
    beatStatus: optionalString(raw.beatStatus, 120),
  };
}

function status(value: unknown): LiveStatus {
  const raw = value && typeof value === "object" ? (value as Record<string, unknown>) : {};
  return {
    camera: stringValue(raw.camera, 120, "camera: unknown"),
    brain: stringValue(raw.brain, 120, "brain: unknown"),
    beat: stringValue(raw.beat, 120, "beat: unknown"),
    scene: stringValue(raw.scene, 120, "scene: unknown"),
    face: stringValue(raw.face, 220, "face: unknown"),
  };
}

function normalizeState(payload: unknown): LiveDeviceState {
  const raw = payload && typeof payload === "object" ? (payload as Record<string, unknown>) : {};
  return {
    schemaVersion: LIVE_DEVICE_STATE_SCHEMA_VERSION,
    deviceId: stringValue(raw.deviceId, 48, "unknown-device"),
    appVersion: optionalString(raw.appVersion, 32),
    manifestId: optionalString(raw.manifestId, 80),
    capturedAt: isoDate(raw.capturedAt),
    thought: thought(raw.thought),
    feeling: feeling(raw.feeling),
    presence: presence(raw.presence),
    music: music(raw.music),
    status: status(raw.status),
  };
}

export async function GET(request: NextRequest) {
  if (!isAdminAuthorized(request)) {
    return NextResponse.json({ error: "provide the dashboard admin token" }, { status: 401 });
  }

  return NextResponse.json(
    {
      live: await readLiveDeviceState(),
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

  const saved = await saveLiveDeviceState(normalizeState(await request.json()));

  return NextResponse.json(
    {
      ok: true,
      schemaVersion: LIVE_DEVICE_STATE_SCHEMA_VERSION,
      receivedAt: saved.receivedAt,
    },
    {
      headers: {
        "Cache-Control": "private, no-store",
        Vary: "Authorization",
      },
    },
  );
}
