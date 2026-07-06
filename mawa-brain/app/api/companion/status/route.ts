import { NextResponse } from "next/server";
import { isAdminAuthorized } from "../../../../lib/auth";
import { groqStatus } from "../../../../lib/companion/groq";

export const dynamic = "force-dynamic";

export function GET(request: Request) {
  return NextResponse.json({
    ...groqStatus(),
    adminAuthorized: isAdminAuthorized(request),
  });
}
