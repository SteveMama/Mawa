import { MAWA_AMBIENT_PROMPT, MAWA_COMPANION_SYSTEM_PROMPT } from "./prompt";
import type { MawaMood, RoomContext, SceneAnimation } from "../manifest";

interface GroqMessage {
  role: "system" | "user" | "assistant";
  content: string;
}

interface AmbientThought {
  mood: MawaMood;
  title: string;
  detail: string;
  animation: SceneAnimation;
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

// The model chooses a mood and two short strings — nothing else. It has no
// grounding for animation physics, so those are derived deterministically from
// the mood below (tuned by hand, not hallucinated).
const AMBIENT_RESPONSE_FORMAT = {
  type: "json_schema",
  json_schema: {
    name: "mawa_ambient_thought",
    strict: true,
    schema: {
      type: "object",
      additionalProperties: false,
      required: ["mood", "title", "detail"],
      properties: {
        mood: {
          type: "string",
          enum: ["neutral", "happy", "grumpy", "sleepy", "suspicious", "excited"],
        },
        title: { type: "string", minLength: 2, maxLength: 20 },
        detail: { type: "string", minLength: 2, maxLength: 44 },
      },
    },
  },
} as const;

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

async function groqChat(
  messages: GroqMessage[],
  maxTokens: number,
  temperature: number,
  responseFormat?: Record<string, unknown>,
): Promise<string> {
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
      ...(responseFormat ? { response_format: responseFormat } : {}),
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

// Mood -> animation is a fixed, hand-tuned table. The renderer knows exactly
// what each of these looks like; the LLM does not, so it never picks them.
const MOOD_ANIMATION: Record<MawaMood, SceneAnimation> = {
  happy: {
    palette: "warm", gazeMode: "curious", energy: 0.42, expressiveness: 0.62,
    aura: 0.34, bars: 0.1, glyphs: 0.12, sway: 0.32, bounce: 0.18,
    blinkRate: 1.02, openness: 1.04, pupilScale: 1.08, squint: 0.08,
  },
  grumpy: {
    palette: "violet", gazeMode: "steady", energy: 0.24, expressiveness: 0.58,
    aura: 0.18, bars: 0.0, glyphs: 0.0, sway: 0.12, bounce: 0.04,
    blinkRate: 0.84, openness: 0.84, pupilScale: 0.96, squint: 0.34,
  },
  sleepy: {
    palette: "dusk", gazeMode: "dreamy", energy: 0.12, expressiveness: 0.36,
    aura: 0.12, bars: 0.0, glyphs: 0.0, sway: 0.08, bounce: 0.02,
    blinkRate: 0.68, openness: 0.68, pupilScale: 0.9, squint: 0.16,
  },
  suspicious: {
    palette: "teal", gazeMode: "locked", energy: 0.38, expressiveness: 0.72,
    aura: 0.22, bars: 0.02, glyphs: 0.0, sway: 0.22, bounce: 0.08,
    blinkRate: 1.14, openness: 0.82, pupilScale: 0.92, squint: 0.42,
  },
  excited: {
    palette: "warm", gazeMode: "dart", energy: 0.82, expressiveness: 0.86,
    aura: 0.74, bars: 0.56, glyphs: 0.46, sway: 0.68, bounce: 0.58,
    blinkRate: 1.34, openness: 1.08, pupilScale: 1.24, squint: 0.06,
  },
  neutral: {
    palette: "cool", gazeMode: "curious", energy: 0.24, expressiveness: 0.44,
    aura: 0.18, bars: 0.02, glyphs: 0.0, sway: 0.18, bounce: 0.06,
    blinkRate: 0.96, openness: 0.96, pupilScale: 1.0, squint: 0.06,
  },
};

function parseAmbient(raw: string): { mood: MawaMood; title: string; detail: string } {
  const objectMatch = raw.match(/\{[\s\S]*\}/);
  if (!objectMatch) throw new Error("Groq ambient response was malformed");
  const parsed = JSON.parse(objectMatch[0]) as {
    mood?: string;
    title?: string;
    detail?: string;
  };
  return {
    mood: normalizeMood(parsed.mood ?? "neutral"),
    title: (parsed.title ?? "Quiet orbit").trim().slice(0, 20),
    detail: (parsed.detail ?? "Keeping the room lightly watched.").trim().slice(0, 44),
  };
}

function describeRoom(room: RoomContext): string {
  const parts = [`It is ${room.dayPart} in the room.`];
  if (room.weather) parts.push(`The weather outside is ${room.weather}.`);
  if (room.events.length === 0) {
    parts.push("The calendar is clear for the next 24 hours.");
  } else {
    const next = room.events[0];
    parts.push(`The next thing on the calendar is "${next.title}" (${next.when}).`);
    if (room.events.length > 1) {
      parts.push(`There are ${room.events.length} events in the next 24 hours.`);
    }
  }
  return parts.join(" ");
}

function fallbackRoom(now: Date): RoomContext {
  const hour = Number(
    new Intl.DateTimeFormat("en-US", {
      hour: "numeric",
      hour12: false,
      timeZone: process.env.MAWA_TIME_ZONE || "America/New_York",
    }).format(now),
  );
  const dayPart: RoomContext["dayPart"] =
    hour >= 22 || hour <= 5 ? "late night" : hour <= 11 ? "morning" : hour <= 16 ? "afternoon" : "evening";
  return { dayPart, events: [] };
}

function fallbackThought(room: RoomContext): AmbientThought {
  const mood: MawaMood =
    room.dayPart === "late night" ? "sleepy" :
    room.dayPart === "morning" ? "happy" :
    room.events.length >= 3 ? "grumpy" :
    "neutral";
  const title =
    room.dayPart === "late night" ? "Night watch" :
    room.dayPart === "morning" ? "Soft start" :
    room.events.length >= 3 ? "Braced" :
    room.dayPart === "evening" ? "Room held" :
    "Quiet orbit";
  const detail =
    room.dayPart === "late night" ? "Keeping the room dim and patient." :
    room.dayPart === "morning" ? "Taking the light a little brighter." :
    room.events.length >= 3 ? "A full day is stacking up." :
    room.dayPart === "evening" ? "Watching the edges of the room." :
    "Keeping the room lightly watched.";
  return { mood, title, detail, animation: MOOD_ANIMATION[mood] };
}

export async function generateAmbientThought(
  roomInput: RoomContext | undefined,
  now: Date,
): Promise<AmbientThought> {
  const room = roomInput ?? fallbackRoom(now);
  const contextLine = describeRoom(room);
  // Cache on the real room state + the hour, so the thought refreshes when the
  // room actually changes rather than on a blind timer.
  const cacheKey = `${now.toISOString().slice(0, 13)}|${contextLine}`;
  if (ambientCache && ambientCache.key === cacheKey && ambientCache.expiresAt > Date.now()) {
    return ambientCache.thought;
  }

  const thought = await (async () => {
    try {
      const raw = await groqChat(
        [
          { role: "system", content: `${MAWA_COMPANION_SYSTEM_PROMPT}\n\n${MAWA_AMBIENT_PROMPT}` },
          { role: "user", content: contextLine },
        ],
        120,
        0.85,
        AMBIENT_RESPONSE_FORMAT,
      );
      const { mood, title, detail } = parseAmbient(raw);
      return { mood, title, detail, animation: MOOD_ANIMATION[mood] };
    } catch {
      return fallbackThought(room);
    }
  })();

  ambientCache = { key: cacheKey, expiresAt: Date.now() + AMBIENT_CACHE_MS, thought };
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
