import { NextRequest, NextResponse } from "next/server";
import { buildGoogleConnectUrl, googleCalendarPrerequisites } from "../../../../lib/google/oauth";
import { googleCalendarStorageReady } from "../../../../lib/google/store";
import { parseGoogleCalendarSlot } from "../../../../lib/google/shared";

export const dynamic = "force-dynamic";

export async function GET(request: NextRequest) {
  const slot = parseGoogleCalendarSlot(request.nextUrl.searchParams.get("slot"));
  if (!slot) {
    return NextResponse.redirect(new URL("/?google_error=slot", request.nextUrl.origin));
  }

  const prerequisites = googleCalendarPrerequisites();
  if (!prerequisites.ready || !googleCalendarStorageReady()) {
    const reason = encodeURIComponent(
      prerequisites.ready
        ? "storage"
        : `config:${prerequisites.missing.join(",")}`,
    );
    return NextResponse.redirect(new URL(`/?google_error=${reason}`, request.nextUrl.origin));
  }

  return NextResponse.redirect(buildGoogleConnectUrl(request.nextUrl.origin, slot));
}
