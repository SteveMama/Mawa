import { MAWA_AMBIENT_PROMPT, MAWA_COMPANION_SYSTEM_PROMPT } from "./prompt";
import type { MawaGazeMode, MawaMood, MawaPalette, SceneAnimation } from "../manifest";

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

function normalizePalette(value: string): MawaPalette {
  switch (value.trim().toLowerCase()) {
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

function normalizeGazeMode(value: string): MawaGazeMode {
  switch (value.trim().toLowerCase()) {
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

function defaultAnimation(mood: MawaMood): SceneAnimation {
  switch (mood) {
    case "happy":
      return {
        palette: "warm",
        gazeMode: "curious",
        energy: 0.42,
        expressiveness: 0.62,
        aura: 0.34,
        bars: 0.10,
        glyphs: 0.12,
        sway: 0.32,
        bounce: 0.18,
        blinkRate: 1.02,
        openness: 1.04,
        pupilScale: 1.08,
        squint: 0.08,
      };
    case "grumpy":
      return {
        palette: "violet",
        gazeMode: "steady",
        energy: 0.24,
        expressiveness: 0.58,
        aura: 0.18,
        bars: 0.0,
        glyphs: 0.0,
        sway: 0.12,
        bounce: 0.04,
        blinkRate: 0.84,
        openness: 0.84,
        pupilScale: 0.96,
        squint: 0.34,
      };
    case "sleepy":
      return {
        palette: "dusk",
        gazeMode: "dreamy",
        energy: 0.12,
        expressiveness: 0.36,
        aura: 0.12,
        bars: 0.0,
        glyphs: 0.0,
        sway: 0.08,
        bounce: 0.02,
        blinkRate: 0.68,
        openness: 0.68,
        pupilScale: 0.9,
        squint: 0.16,
      };
    case "suspicious":
      return {
        palette: "teal",
        gazeMode: "locked",
        energy: 0.38,
        expressiveness: 0.72,
        aura: 0.22,
        bars: 0.02,
        glyphs: 0.0,
        sway: 0.22,
        bounce: 0.08,
        blinkRate: 1.14,
        openness: 0.82,
        pupilScale: 0.92,
        squint: 0.42,
      };
    case "excited":
      return {
        palette: "warm",
        gazeMode: "dart",
        energy: 0.82,
        expressiveness: 0.86,
        aura: 0.74,
        bars: 0.56,
        glyphs: 0.46,
        sway: 0.68,
        bounce: 0.58,
        blinkRate: 1.34,
        openness: 1.08,
        pupilScale: 1.24,
        squint: 0.06,
      };
    case "neutral":
    default:
      return {
        palette: "cool",
        gazeMode: "curious",
        energy: 0.24,
        expressiveness: 0.44,
        aura: 0.18,
        bars: 0.02,
        glyphs: 0.0,
        sway: 0.18,
        bounce: 0.06,
        blinkRate: 0.96,
        openness: 0.96,
        pupilScale: 1.0,
        squint: 0.06,
      };
  }
}

function parseAmbient(raw: string): AmbientThought {
  const objectMatch = raw.match(/\{[\s\S]*\}/);
  if (!objectMatch) throw new Error("Groq ambient response was malformed");
  const parsed = JSON.parse(objectMatch[0]) as {
    mood?: string;
    title?: string;
    detail?: string;
    animation?: Record<string, unknown>;
  };
  const mood = normalizeMood(parsed.mood ?? "neutral");
  const animationDefaults = defaultAnimation(mood);
  const animation = parsed.animation ?? {};
  return {
    mood,
    title: (parsed.title ?? "Quiet orbit").trim().slice(0, 20),
    detail: (parsed.detail ?? "Keeping the room lightly watched.").trim().slice(0, 44),
    animation: {
      palette: normalizePalette(String(animation.palette ?? animationDefaults.palette)),
      gazeMode: normalizeGazeMode(String(animation.gazeMode ?? animationDefaults.gazeMode)),
      energy: clamp(animation.energy, 0, 1, animationDefaults.energy),
      expressiveness: clamp(animation.expressiveness, 0, 1, animationDefaults.expressiveness),
      aura: clamp(animation.aura, 0, 1, animationDefaults.aura),
      bars: clamp(animation.bars, 0, 1, animationDefaults.bars),
      glyphs: clamp(animation.glyphs, 0, 1, animationDefaults.glyphs),
      sway: clamp(animation.sway, 0, 1, animationDefaults.sway),
      bounce: clamp(animation.bounce, 0, 1, animationDefaults.bounce),
      blinkRate: clamp(animation.blinkRate, 0.6, 1.8, animationDefaults.blinkRate),
      openness: clamp(animation.openness, 0.55, 1.15, animationDefaults.openness),
      pupilScale: clamp(animation.pupilScale, 0.8, 1.45, animationDefaults.pupilScale),
      squint: clamp(animation.squint, 0, 1, animationDefaults.squint),
    },
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
