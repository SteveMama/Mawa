import { NextRequest, NextResponse } from "next/server";
import { composeManifest } from "../../../lib/compose-manifest";
import { hasPrivateConnectorAccess } from "../../../lib/auth";

export const dynamic = "force-dynamic";

function coordinate(value: string | null, fallback: number, min: number, max: number): number {
  if (value === null || value.trim() === "") return fallback;
  const parsed = Number(value);
  return Number.isFinite(parsed) && parsed >= min && parsed <= max ? parsed : fallback;
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
