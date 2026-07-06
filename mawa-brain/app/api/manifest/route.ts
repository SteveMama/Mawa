import { NextRequest, NextResponse } from "next/server";
import { composeManifest } from "../../../lib/compose-manifest";
import { hasPrivateConnectorAccess } from "../../../lib/auth";

export const dynamic = "force-dynamic";

function coordinate(value: string | null, fallback: number, min: number, max: number): number {
  if (value === null || value.trim() === "") return fallback;
  const parsed = Number(value);
  return Number.isFinite(parsed) && parsed >= min && parsed <= max ? parsed : fallback;
}

function integer(value: string | null, fallback: number, min: number, max: number): number {
  if (value === null || value.trim() === "") return fallback;
  const parsed = Number.parseInt(value, 10);
  return Number.isFinite(parsed) && parsed >= min && parsed <= max ? parsed : fallback;
}

function unit(value: string | null, fallback = 0): number {
  if (value === null || value.trim() === "") return fallback;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? Math.min(1, Math.max(0, parsed)) : fallback;
}

function flag(value: string | null): boolean {
  return value === "1" || value === "true" || value === "yes";
}

function recognized(value: string | null): "me" | "other" | "unknown" | "none" {
  switch ((value ?? "").trim().toLowerCase()) {
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

export async function GET(request: NextRequest) {
  const privateAccess = hasPrivateConnectorAccess(request);
  const manifest = await composeManifest({
    latitude: coordinate(request.nextUrl.searchParams.get("lat"), 42.3601, -90, 90),
    longitude: coordinate(request.nextUrl.searchParams.get("lon"), -71.0589, -180, 180),
    device: request.nextUrl.searchParams.get("device") ?? undefined,
    appVersion: request.nextUrl.searchParams.get("version") ?? undefined,
    privateAccess,
    now: new Date(),
    perception: {
      faceCount: integer(request.nextUrl.searchParams.get("faces"), 0, 0, 8),
      recognized: recognized(request.nextUrl.searchParams.get("recognized")),
      proximity: unit(request.nextUrl.searchParams.get("prox")),
      covered: flag(request.nextUrl.searchParams.get("covered")),
      ambientDark: flag(request.nextUrl.searchParams.get("dark")),
      musicActive: flag(request.nextUrl.searchParams.get("music")),
      groove: unit(request.nextUrl.searchParams.get("groove")),
    },
  });

  return NextResponse.json(manifest, {
    headers: {
      "Cache-Control": privateAccess
        ? "private, no-store"
        : "public, s-maxage=60, stale-while-revalidate=240",
      ...(privateAccess ? {} : { "Access-Control-Allow-Origin": "*" }),
      Vary: "Authorization, Cookie",
    },
  });
}
