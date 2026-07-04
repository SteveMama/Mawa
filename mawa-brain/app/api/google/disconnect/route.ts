import { NextRequest, NextResponse } from "next/server";
import { isDashboardAuthorized } from "../../../../lib/auth";
import { DASHBOARD_SESSION_COOKIE, revokeGoogleRefreshToken } from "../../../../lib/google/oauth";
import {
  getStoredGoogleCalendarAccount,
  listStoredGoogleCalendarAccounts,
  removeStoredGoogleCalendarAccount,
} from "../../../../lib/google/store";
import { parseGoogleCalendarSlot } from "../../../../lib/google/shared";

export const dynamic = "force-dynamic";

export async function POST(request: NextRequest) {
  if (!isDashboardAuthorized(request)) {
    return NextResponse.json({ error: "admin authorization required" }, { status: 401 });
  }

  const slot = parseGoogleCalendarSlot(request.nextUrl.searchParams.get("slot"));
  if (!slot) return NextResponse.json({ error: "invalid slot" }, { status: 400 });

  const account = await getStoredGoogleCalendarAccount(slot);
  if (account) {
    try {
      await revokeGoogleRefreshToken(account.refreshToken);
    } catch {
      // Local cleanup still matters if Google revocation fails.
    }
    await removeStoredGoogleCalendarAccount(slot);
  }

  const remaining = await listStoredGoogleCalendarAccounts();
  const response = NextResponse.json({ ok: true });
  if (!remaining.personal && !remaining.work) {
    response.cookies.set(DASHBOARD_SESSION_COOKIE, "", {
      httpOnly: true,
      sameSite: "lax",
      secure: request.nextUrl.protocol === "https:",
      path: "/",
      maxAge: 0,
    });
  }
  return response;
}
