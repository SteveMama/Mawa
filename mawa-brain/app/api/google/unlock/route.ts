import { NextRequest, NextResponse } from "next/server";
import {
  createDashboardSessionToken,
  DASHBOARD_SESSION_COOKIE,
  dashboardAdminRequired,
  verifyDashboardAdminToken,
} from "../../../../lib/google/oauth";

export const dynamic = "force-dynamic";

export async function POST(request: NextRequest) {
  if (!dashboardAdminRequired()) {
    return NextResponse.json({ ok: true });
  }

  const payload = (await request.json().catch(() => ({}))) as { token?: string };
  const candidate = payload.token?.trim() ?? "";
  if (!verifyDashboardAdminToken(candidate)) {
    return NextResponse.json({ error: "invalid admin token" }, { status: 401 });
  }

  const response = NextResponse.json({ ok: true });
  response.cookies.set(DASHBOARD_SESSION_COOKIE, createDashboardSessionToken(), {
    httpOnly: true,
    sameSite: "lax",
    secure: request.nextUrl.protocol === "https:",
    path: "/",
    maxAge: 14 * 24 * 60 * 60,
  });
  return response;
}
