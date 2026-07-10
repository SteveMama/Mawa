import { NextResponse } from "next/server";
import { groqStatus } from "../../../../lib/companion/groq";

export const dynamic = "force-dynamic";

export function GET() {
  return NextResponse.json({
    ...groqStatus(),
  });
}
