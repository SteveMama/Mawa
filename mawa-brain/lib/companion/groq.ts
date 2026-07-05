import { MAWA_AMBIENT_PROMPT, MAWA_COMPANION_SYSTEM_PROMPT } from "./prompt";
import type { MawaMood } from "../manifest";

interface GroqMessage {
  role: "system" | "user" | "assistant";
  content: string;
}

interface AmbientThought {
  mood: MawaMood;
  title: string;
  detail: string;
}

interface GroqChatResponse {
  choices?: Array<{
    message?: {
      content?: string | null;
    };
  }>;
  error?: {
    message?: string;
  };
}

const GROQ_BASE_URL = "https://api.groq.com/openai/v1/chat/completions";
const AMBIENT_CACHE_MS = 15 * 60_000;
let ambientCache: { key: string; expiresAt: number; thought: AmbientThought } | null = null;

function groqApiKey(): string | null {
  return process.env.GROQ_API_KEY?.trim() || null;
}

export function groqModel(): string {
  return process.env.GROQ_MODEL?.trim() || "llama-3.3-70b-versatile";
}

export function groqStatus() {
  const missing: string[] = [];
  if (!groqApiKey()) missing.push("GROQ_API_KEY");
  return {
    ready: missing.length === 0,
    missing,
    model: groqModel(),
  };
}

async function groqChat(messages: GroqMessage[], maxTokens: number, temperature: number): Promise<string> {
  const apiKey = groqApiKey();
  if (!apiKey) throw new Error("Set GROQ_API_KEY to enable Mawa personality");

  const response = await fetch(GROQ_BASE_URL, {
    method: "POST",
    headers: {
      authorization: `Bearer ${apiKey}`,
      "content-type": "application/json",
    },
    body: JSON.stringify({
      model: groqModel(),
      messages,
      max_tokens: maxTokens,
      temperature,
    }),
    cache: "no-store",
    signal: AbortSignal.timeout(12_000),
  });

  const payload = (await response.json()) as GroqChatResponse;
  const content = payload.choices?.[0]?.message?.content?.trim();
  if (!response.ok || !content) {
    throw new Error(payload.error?.message || `Groq returned ${response.status}`);
  }
  return content;
}

function ambientCacheKey(now: Date, contextLine: string): string {
  return `${now.toISOString().slice(0, 13)}|${contextLine}`;
}

function normalizeMood(value: string): MawaMood {
  switch (value.trim().toLowerCase()) {
    case "happy":
      return "happy";
    case "grumpy":
      return "grumpy";
    case "sleepy":
      return "sleepy";
    case "suspicious":
      return "suspicious";
    case "excited":
      return "excited";
    default:
      return "neutral";
  }
}

function parseAmbient(raw: string): AmbientThought {
  const [mood, title, detail] = raw.split("|").map((piece) => piece?.trim() ?? "");
  if (!title || !detail) throw new Error("Groq ambient response was malformed");
  return {
    mood: normalizeMood(mood),
    title: title.slice(0, 20),
    detail: detail.slice(0, 44),
  };
}

function dayContext(now: Date): string {
  const hour = Number(
    new Intl.DateTimeFormat("en-US", {
      hour: "numeric",
      hour12: false,
      timeZone: process.env.MAWA_TIME_ZONE || "America/New_York",
    }).format(now),
  );
  if (hour >= 22 || hour <= 5) return "late night";
  if (hour <= 11) return "morning";
  if (hour <= 16) return "afternoon";
  return "evening";
}

export async function generateAmbientThought(now: Date): Promise<AmbientThought> {
  const contextLine = `It is ${dayContext(now)} in the room.`;
  const cacheKey = ambientCacheKey(now, contextLine);
  if (ambientCache && ambientCache.key == cacheKey && ambientCache.expiresAt > Date.now()) {
    return ambientCache.thought;
  }

  const raw = await groqChat(
    [
      { role: "system", content: `${MAWA_COMPANION_SYSTEM_PROMPT}\n\n${MAWA_AMBIENT_PROMPT}` },
      { role: "user", content: contextLine },
    ],
    80,
    0.9,
  );
  const thought = parseAmbient(raw);
  ambientCache = {
    key: cacheKey,
    expiresAt: Date.now() + AMBIENT_CACHE_MS,
    thought,
  };
  return thought;
}

export async function generateCompanionReply(message: string): Promise<string> {
  return groqChat(
    [
      { role: "system", content: MAWA_COMPANION_SYSTEM_PROMPT },
      { role: "user", content: message.trim().slice(0, 600) },
    ],
    220,
    0.8,
  );
}

