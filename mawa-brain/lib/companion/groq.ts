import { MAWA_AMBIENT_PROMPT, MAWA_COMPANION_SYSTEM_PROMPT } from "./prompt";
import type { MawaMood, RoomContext, SceneAnimation } from "../manifest";

interface GroqMessage {
  role: "system" | "user" | "assistant";
  content: string;
}

interface AmbientThought {
  mood: MawaMood;
  eyebrow: string;
  title: string;
  detail: string;
  accent: string;
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
const AMBIENT_CACHE_MS = 3 * 60_000;
let ambientCache: { key: string; expiresAt: number; thought: AmbientThought } | null = null;

const AMBIENT_RESPONSE_FORMAT = {
  type: "json_schema",
  json_schema: {
    name: "mawa_ambient_direction",
    strict: true,
    schema: {
      type: "object",
      additionalProperties: false,
      required: ["mood", "eyebrow", "title", "detail", "accent", "animation"],
      properties: {
        mood: {
          type: "string",
          enum: ["neutral", "happy", "grumpy", "sleepy", "suspicious", "excited"],
        },
        eyebrow: { type: "string" },
        title: { type: "string" },
        detail: { type: "string" },
        accent: { type: "string" },
        animation: {
          type: "object",
          additionalProperties: false,
          required: [
            "palette",
            "gazeMode",
            "energy",
            "expressiveness",
            "aura",
            "bars",
            "glyphs",
            "sway",
            "bounce",
            "blinkRate",
            "openness",
            "pupilScale",
            "squint",
          ],
          properties: {
            palette: { type: "string", enum: ["cool", "warm", "violet", "teal", "dusk"] },
            gazeMode: { type: "string", enum: ["steady", "curious", "dart", "locked", "dreamy"] },
            energy: { type: "number", minimum: 0, maximum: 1 },
            expressiveness: { type: "number", minimum: 0, maximum: 1 },
            aura: { type: "number", minimum: 0, maximum: 1 },
            bars: { type: "number", minimum: 0, maximum: 1 },
            glyphs: { type: "number", minimum: 0, maximum: 1 },
            sway: { type: "number", minimum: 0, maximum: 1 },
            bounce: { type: "number", minimum: 0, maximum: 1 },
            blinkRate: { type: "number", minimum: 0.6, maximum: 1.8 },
            openness: { type: "number", minimum: 0.55, maximum: 1.15 },
            pupilScale: { type: "number", minimum: 0.8, maximum: 1.45 },
            squint: { type: "number", minimum: 0, maximum: 1 },
          },
        },
      },
    },
  },
} as const;

const AMBIENT_JSON_OBJECT_FORMAT = {
  type: "json_object",
} as const;

const STRICT_STRUCTURED_MODELS = new Set(["openai/gpt-oss-20b", "openai/gpt-oss-120b"]);

function supportsStrictStructuredOutput(model: string): boolean {
  return STRICT_STRUCTURED_MODELS.has(model);
}

export function groqAmbientModel(): string {
  const configured = process.env.GROQ_AMBIENT_MODEL?.trim();
  if (configured) return configured;
  return groqModel();
}

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
    ambientModel: groqAmbientModel(),
  };
}

async function groqChat(
  messages: GroqMessage[],
  maxTokens: number,
  temperature: number,
  responseFormat?: Record<string, unknown>,
  modelOverride?: string,
): Promise<string> {
  const apiKey = groqApiKey();
  if (!apiKey) throw new Error("Set GROQ_API_KEY to enable Mawa personality");
  const model = modelOverride ?? groqModel();

  const response = await fetch(GROQ_BASE_URL, {
    method: "POST",
    headers: {
      authorization: `Bearer ${apiKey}`,
      "content-type": "application/json",
    },
    body: JSON.stringify({
      model,
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

function normalizePalette(value: unknown): SceneAnimation["palette"] {
  switch (String(value).trim().toLowerCase()) {
    case "warm":
      return "warm";
    case "violet":
      return "violet";
    case "teal":
      return "teal";
    case "dusk":
      return "dusk";
    default:
      return "cool";
  }
}

function normalizeGazeMode(value: unknown): SceneAnimation["gazeMode"] {
  switch (String(value).trim().toLowerCase()) {
    case "steady":
      return "steady";
    case "dart":
      return "dart";
    case "locked":
      return "locked";
    case "dreamy":
      return "dreamy";
    default:
      return "curious";
  }
}

function clamp(value: unknown, min: number, max: number, fallback: number): number {
  const numeric = typeof value === "number" ? value : Number(value);
  if (!Number.isFinite(numeric)) return fallback;
  return Math.min(max, Math.max(min, numeric));
}

function normalizeEyebrow(value: unknown): string {
  return String(value ?? "MAWA")
    .trim()
    .replace(/[^A-Za-z ]+/g, " ")
    .replace(/\s+/g, " ")
    .slice(0, 20)
    .toUpperCase() || "MAWA";
}

function normalizeAccent(value: unknown, mood: MawaMood): string {
  const raw = String(value ?? "").trim();
  if (/^#[0-9a-fA-F]{6}$/.test(raw)) return raw.toUpperCase();
  return ({
    happy: "#F2C27B",
    grumpy: "#C59BFF",
    sleepy: "#8FA6C0",
    suspicious: "#78D9C8",
    excited: "#FFB36E",
    neutral: "#B6D9F2",
  } satisfies Record<MawaMood, string>)[mood];
}

function blendAnimation(base: SceneAnimation, candidate: Record<string, unknown> | undefined): SceneAnimation {
  const animation = candidate ?? {};
  return {
    palette: normalizePalette(animation.palette ?? base.palette),
    gazeMode: normalizeGazeMode(animation.gazeMode ?? base.gazeMode),
    energy: clamp(animation.energy, 0, 1, base.energy),
    expressiveness: clamp(animation.expressiveness, 0, 1, base.expressiveness),
    aura: clamp(animation.aura, 0, 1, base.aura),
    bars: clamp(animation.bars, 0, 1, base.bars),
    glyphs: clamp(animation.glyphs, 0, 1, base.glyphs),
    sway: clamp(animation.sway, 0, 1, base.sway),
    bounce: clamp(animation.bounce, 0, 1, base.bounce),
    blinkRate: clamp(animation.blinkRate, 0.6, 1.8, base.blinkRate),
    openness: clamp(animation.openness, 0.55, 1.15, base.openness),
    pupilScale: clamp(animation.pupilScale, 0.8, 1.45, base.pupilScale),
    squint: clamp(animation.squint, 0, 1, base.squint),
  };
}

function parseAmbient(raw: string): AmbientThought {
  const objectMatch = raw.match(/\{[\s\S]*\}/);
  if (!objectMatch) throw new Error("Groq ambient response was malformed");
  const parsed = JSON.parse(objectMatch[0]) as {
    mood?: string;
    eyebrow?: string;
    title?: string;
    detail?: string;
    accent?: string;
    animation?: Record<string, unknown>;
  };
  const mood = normalizeMood(parsed.mood ?? "neutral");
  return {
    mood,
    eyebrow: normalizeEyebrow(parsed.eyebrow),
    title: (parsed.title ?? "Quiet orbit").trim().slice(0, 20),
    detail: (parsed.detail ?? "Keeping the room lightly watched.").trim().slice(0, 44),
    accent: normalizeAccent(parsed.accent, mood),
    animation: blendAnimation(MOOD_ANIMATION[mood], parsed.animation),
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
    const workCount = room.events.filter((event) => event.slot === "work").length;
    const personalCount = room.events.length - workCount;
    if (workCount > 0) parts.push(`${workCount} of those are work events.`);
    if (personalCount > 0) parts.push(`${personalCount} of those are personal events.`);
  }
  const perception = room.perception;
  if (perception) {
    if (perception.covered) parts.push("The camera is covered.");
    if (perception.ambientDark) parts.push("The room is dark.");
    if (perception.faceCount <= 0) {
      parts.push("No one is in view.");
    } else {
      parts.push(`${perception.faceCount} face${perception.faceCount === 1 ? "" : "s"} are in view.`);
      switch (perception.recognized) {
        case "me":
          parts.push("The familiar person is present.");
          break;
        case "other":
          parts.push("Someone unfamiliar is present.");
          break;
        case "unknown":
          parts.push("A face is present but identity is uncertain.");
          break;
      }
      if (perception.proximity >= 0.22) {
        parts.push("Someone is quite close to the wall.");
      }
    }
    if (perception.musicActive || perception.groove >= 0.18) {
      parts.push(`There is music or a steady groove in the room at intensity ${perception.groove.toFixed(2)}.`);
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
  const eyebrow =
    room.dayPart === "late night" ? "LATE WATCH" :
    room.dayPart === "morning" ? "DRY LIGHT" :
    room.events.length >= 3 ? "BRACED" :
    room.dayPart === "evening" ? "HOLDING" :
    "MAWA";
  const title =
    room.dayPart === "late night" ? "Night watch" :
    room.dayPart === "morning" ? "Soft start" :
    room.events.length >= 3 ? "Braced a bit" :
    room.dayPart === "evening" ? "Room held" :
    "Quiet orbit";
  const detail =
    room.dayPart === "late night" ? "Keeping the room dim and patient." :
    room.dayPart === "morning" ? "Taking the light a touch brighter." :
    room.events.length >= 3 ? "The day is stacking its weight." :
    room.dayPart === "evening" ? "Watching the edges settle down." :
    "Keeping the room lightly watched.";
  return {
    mood,
    eyebrow,
    title,
    detail,
    accent: normalizeAccent(undefined, mood),
    animation: MOOD_ANIMATION[mood],
  };
}

export async function generateAmbientThought(
  roomInput: RoomContext | undefined,
  now: Date,
): Promise<AmbientThought> {
  const room = roomInput ?? fallbackRoom(now);
  const contextLine = describeRoom(room);
  const model = groqAmbientModel();
  // Cache on the real room state + the hour, so the thought refreshes when the
  // room actually changes rather than on a blind timer.
  const cacheKey = `${model}|${now.toISOString().slice(0, 13)}|${contextLine}`;
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
        0.65,
        undefined,
        model,
      );
      return parseAmbient(raw);
    } catch (error) {
      console.warn("Ambient thought fallback", {
        model,
        message: error instanceof Error ? error.message : String(error),
      });
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
