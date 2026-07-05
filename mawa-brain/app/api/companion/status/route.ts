import { NextResponse } from "next/server";
import { isDashboardAuthorized } from "../../../../lib/auth";
import { dashboardAdminConfigured, dashboardAdminRequired } from "../../../../lib/google/oauth";
import { groqStatus } from "../../../../lib/companion/groq";

export const dynamic = "force-dynamic";

export function GET(request: Request) {
  const status = groqStatus();
  return NextResponse.json({
    ...status,
    adminRequired: dashboardAdminRequired(),
    adminConfigured: dashboardAdminConfigured(),
    adminAuthorized: isDashboardAuthorized(request),
  });
}

