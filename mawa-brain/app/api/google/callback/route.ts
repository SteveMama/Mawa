import { NextRequest, NextResponse } from "next/server";
import {
  createDashboardSessionToken,
  DASHBOARD_SESSION_COOKIE,
  exchangeGoogleCode,
  parseGoogleConnectState,
  profileFromIdToken,
} from "../../../../lib/google/oauth";
import { saveStoredGoogleCalendarAccount } from "../../../../lib/google/store";

export const dynamic = "force-dynamic";

function redirectWithError(origin: string, detail: string): NextResponse {
  return NextResponse.redirect(new URL(`/?google_error=${encodeURIComponent(detail)}`, origin));
}

export async function GET(request: NextRequest) {
  const origin = request.nextUrl.origin;
  const state = parseGoogleConnectState(request.nextUrl.searchParams.get("state"));
  if (!state) return redirectWithError(origin, "state");

  if (request.nextUrl.searchParams.get("error")) {
    return redirectWithError(origin, request.nextUrl.searchParams.get("error") ?? "oauth");
  }

  const code = request.nextUrl.searchParams.get("code");
  if (!code) return redirectWithError(origin, "code");

  try {
    const tokens = await exchangeGoogleCode(code, origin);
    if (!tokens.refreshToken) return redirectWithError(origin, "refresh-token");
    const profile = profileFromIdToken(tokens.idToken);
    const now = new Date().toISOString();
    await saveStoredGoogleCalendarAccount({
      slot: state.slot,
      refreshToken: tokens.refreshToken,
      scope: tokens.scope,
      connectedAt: now,
      updatedAt: now,
      ...profile,
    });

    const response = NextResponse.redirect(
      new URL(`/?google=connected&slot=${state.slot}`, origin),
    );
    response.cookies.set(DASHBOARD_SESSION_COOKIE, createDashboardSessionToken(), {
      httpOnly: true,
      sameSite: "lax",
      secure: request.nextUrl.protocol === "https:",
      path: "/",
      maxAge: 14 * 24 * 60 * 60,
    });
    return response;
  } catch (error) {
    return redirectWithError(
      origin,
      error instanceof Error ? error.message.slice(0, 120) : "exchange",
    );
  }
}
