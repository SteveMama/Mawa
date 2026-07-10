import { NextRequest, NextResponse } from "next/server";
import { generateCompanionReply } from "../../../../lib/companion/groq";

export const dynamic = "force-dynamic";

export async function POST(request: NextRequest) {
  const payload = (await request.json().catch(() => ({}))) as { message?: string };
  const message = payload.message?.trim() ?? "";
  if (!message) {
    return NextResponse.json({ error: "message is required" }, { status: 400 });
  }

  try {
    const reply = await generateCompanionReply(message);
    return NextResponse.json({ reply });
  } catch (error) {
    return NextResponse.json(
      { error: error instanceof Error ? error.message : "Companion unavailable" },
      { status: 500 },
    );
  }
}
