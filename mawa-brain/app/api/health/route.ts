import { NextResponse } from "next/server";

export function GET() {
  return NextResponse.json({ ok: true, service: "mawa-brain", time: new Date().toISOString() });
}
