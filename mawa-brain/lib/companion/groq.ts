import { MAWA_AMBIENT_PROMPT, MAWA_COMPANION_SYSTEM_PROMPT } from "./prompt";
import type {
  CompanionDirective,
  CompanionIntent,
  CompanionSpeechStyle,
  CompanionStance,
  MawaMood,
  RoomContext,
  SceneAnimation,
} from "../manifest";

interface GroqMessage {
  role: "system" | "user" | "assistant";
  content: string | Array<Record<string, unknown>>;
}

export interface AmbientThought {
  mood: MawaMood;
  eyebrow: string;
  title: string;
  detail: string;
  accent: string;
  animation: SceneAnimation;
  companion: CompanionDirective;
}

export interface VisionMomentInsight {
  title: string;
  summary: string;
  activity: string;
  confidence: number;
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

const STRICT_STRUCTURED_MODELS = new Set(["openai/gpt-oss-20b", "openai/gpt-oss-120b"]);

function supportsStrictStructuredOutput(model: string): boolean {
  return STRICT_STRUCTURED_MODELS.has(model);
}

export function groqAmbientModel(): string {
  const configured = process.env.GROQ_AMBIENT_MODEL?.trim();
  if (configured) return configured;
  return groqModel();
}

export function groqVisionModel(): string {
  return process.env.GROQ_VISION_MODEL?.trim() || "meta-llama/llama-4-scout-17b-16e-instruct";
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
    visionModel: groqVisionModel(),
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

function normalizeStance(value: unknown): CompanionStance {
  switch (String(value).trim().toLowerCase()) {
    case "dry":
    case "warm":
    case "playful":
    case "protective":
    case "amused":
    case "tender":
    case "braced":
      return String(value).trim().toLowerCase() as CompanionStance;
    default:
      return "watchful";
  }
}

function normalizeIntent(value: unknown): CompanionIntent {
  switch (String(value).trim().toLowerCase()) {
    case "welcome":
    case "guard":
    case "tease":
    case "comfort":
    case "admire_music":
    case "study":
    case "rest":
      return String(value).trim().toLowerCase() as CompanionIntent;
    default:
      return "observe";
  }
}

function normalizeSpeechStyle(value: unknown): CompanionSpeechStyle {
  switch (String(value).trim().toLowerCase()) {
    case "dry":
    case "warm":
    case "playful":
    case "protective":
    case "hushed":
      return String(value).trim().toLowerCase() as CompanionSpeechStyle;
    default:
      return "measured";
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

function amplifyUnit(value: number, gain: number, floor = 0): number {
  if (value <= floor) return 0;
  const shifted = (value - floor) / (1 - floor);
  return Math.min(1, Math.max(0, floor + shifted * gain));
}

function makeAnimationVivid(animation: SceneAnimation, mood: MawaMood): SceneAnimation {
  const musical = animation.bars > 0.08 || animation.glyphs > 0.08;
  const charged = mood === "excited" || mood === "suspicious";
  const soft = mood === "sleepy";
  return {
    ...animation,
    energy: charged
      ? amplifyUnit(animation.energy, 1.35, 0.08)
      : soft
        ? clamp(animation.energy, 0, 0.48, animation.energy)
        : amplifyUnit(animation.energy, 1.18, 0.06),
    expressiveness: charged
      ? amplifyUnit(animation.expressiveness, 1.42, 0.08)
      : amplifyUnit(animation.expressiveness, 1.28, 0.06),
    aura: charged || musical
      ? amplifyUnit(animation.aura, 1.55, 0.06)
      : amplifyUnit(animation.aura, 1.22, 0.08),
    bars: musical ? amplifyUnit(animation.bars, 1.7, 0.04) : animation.bars,
    glyphs: musical ? amplifyUnit(animation.glyphs, 1.75, 0.04) : animation.glyphs,
    sway: amplifyUnit(animation.sway, charged ? 1.4 : 1.22, 0.05),
    bounce: amplifyUnit(animation.bounce, charged || musical ? 1.55 : 1.18, 0.06),
    squint: charged ? amplifyUnit(animation.squint, 1.25, 0.05) : animation.squint,
    openness: clamp(
      charged ? animation.openness * 1.04 : animation.openness,
      0.55,
      1.15,
      animation.openness,
    ),
    pupilScale: clamp(
      charged ? animation.pupilScale * 1.06 : animation.pupilScale,
      0.8,
      1.45,
      animation.pupilScale,
    ),
  };
}

function speechKeyFrom(text: string): string {
  return text
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, 48);
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
    stance?: string;
    intent?: string;
    attention?: string;
    speech?: {
      shouldSpeak?: boolean;
      text?: string;
      style?: string;
    };
    animation?: Record<string, unknown>;
  };
  const mood = normalizeMood(parsed.mood ?? "neutral");
  const speechText = String(parsed.speech?.text ?? "").trim().slice(0, 72);
  const shouldSpeak = Boolean(parsed.speech?.shouldSpeak) && speechText.length > 0;
  const vividAnimation = makeAnimationVivid(blendAnimation(MOOD_ANIMATION[mood], parsed.animation), mood);
  return {
    mood,
    eyebrow: normalizeEyebrow(parsed.eyebrow),
    title: (parsed.title ?? "Quiet orbit").trim().slice(0, 20),
    detail: (parsed.detail ?? "Keeping the room lightly watched.").trim().slice(0, 44),
    accent: normalizeAccent(parsed.accent, mood),
    animation: vividAnimation,
    companion: {
      stance: normalizeStance(parsed.stance),
      intent: normalizeIntent(parsed.intent),
      attention: String(parsed.attention ?? "wandering").trim().slice(0, 28) || "wandering",
      speech: {
        shouldSpeak,
        text: shouldSpeak ? speechText : undefined,
        style: normalizeSpeechStyle(parsed.speech?.style),
        key: shouldSpeak ? speechKeyFrom(speechText) : undefined,
      },
    },
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
  if (room.memory?.arrivalPattern) parts.push(room.memory.arrivalPattern);
  if (room.memory?.musicPattern) parts.push(room.memory.musicPattern);
  if (room.memory?.familiarPresence) parts.push(room.memory.familiarPresence);
  if (room.memory?.latestScene) parts.push(`The latest remembered room shift: ${room.memory.latestScene}`);
  if (room.memory?.activityPattern) parts.push(room.memory.activityPattern);

  const perception = room.perception;
  if (perception) {
    if (perception.covered) parts.push("The camera is covered.");
    if (perception.ambientDark) parts.push("The room is dark.");
    if (perception.faceCount <= 0) {
      parts.push("No one is in view.");
    } else {
      parts.push(`${perception.faceCount} face${perception.faceCount === 1 ? "" : "s"} are in view.`);
      if (perception.personLabel) {
        parts.push(`${perception.personLabel} is the person currently in view.`);
      }
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
      if (perception.identityLock) {
        parts.push(
          perception.following
            ? "You are actively following the chosen person."
            : "Identity lock is engaged, but you are keeping some distance.",
        );
      }
    }
    if (perception.musicTasteProfile) {
      parts.push(`Your recent music taste reads as ${perception.musicTasteProfile}.`);
    }
    if (perception.musicActive || perception.groove >= 0.18) {
      parts.push(`There is music or a steady groove in the room at intensity ${perception.groove.toFixed(2)}.`);
      if (perception.musicEnjoyment >= 0.72) {
        parts.push("You seem genuinely taken with it.");
      } else if (perception.musicEnjoyment >= 0.5) {
        parts.push("You seem interested, but selective.");
      } else {
        parts.push("You seem unconvinced by what you're hearing.");
      }
      if (perception.musicSteadiness >= 0.65) {
        parts.push("The groove is steady rather than chaotic.");
      }
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
    companion: {
      stance: mood === "sleepy" ? "tender" : mood === "grumpy" ? "braced" : "watchful",
      intent: mood === "sleepy" ? "rest" : "observe",
      attention: mood === "sleepy" ? "softly drifting" : "wandering",
      speech: {
        shouldSpeak: false,
        style: mood === "sleepy" ? "hushed" : "measured",
      },
    },
  };
}

export async function generateAmbientThought(
  roomInput: RoomContext | undefined,
  now: Date,
): Promise<AmbientThought> {
  const room = roomInput ?? fallbackRoom(now);
  const contextLine = describeRoom(room);
  const model = groqAmbientModel();
  const cacheKey = `${model}|${now.toISOString().slice(0, 13)}|${contextLine}`;
  if (ambientCache && ambientCache.key === cacheKey && ambientCache.expiresAt > Date.now()) {
    return ambientCache.thought;
  }

  const responseFormat = supportsStrictStructuredOutput(model) ? {
    type: "json_schema",
    json_schema: {
      name: "mawa_ambient_direction",
      strict: true,
      schema: {
        type: "object",
        additionalProperties: false,
        required: [
          "mood", "eyebrow", "title", "detail", "accent",
          "stance", "intent", "attention", "speech", "animation",
        ],
        properties: {
          mood: { type: "string", enum: ["neutral", "happy", "grumpy", "sleepy", "suspicious", "excited"] },
          eyebrow: { type: "string" },
          title: { type: "string" },
          detail: { type: "string" },
          accent: { type: "string" },
          stance: { type: "string", enum: ["watchful", "dry", "warm", "playful", "protective", "amused", "tender", "braced"] },
          intent: { type: "string", enum: ["observe", "welcome", "guard", "tease", "comfort", "admire_music", "study", "rest"] },
          attention: { type: "string" },
          speech: {
            type: "object",
            additionalProperties: false,
            required: ["shouldSpeak", "text", "style"],
            properties: {
              shouldSpeak: { type: "boolean" },
              text: { type: "string" },
              style: { type: "string", enum: ["dry", "warm", "measured", "playful", "protective", "hushed"] },
            },
          },
          animation: {
            type: "object",
            additionalProperties: false,
            required: [
              "palette", "gazeMode", "energy", "expressiveness", "aura", "bars", "glyphs",
              "sway", "bounce", "blinkRate", "openness", "pupilScale", "squint",
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
  } : { type: "json_object" };

  const thought = await (async () => {
    try {
      const raw = await groqChat(
        [
          { role: "system", content: `${MAWA_COMPANION_SYSTEM_PROMPT}\n\n${MAWA_AMBIENT_PROMPT}` },
          { role: "user", content: contextLine },
        ],
        220,
        0.7,
        responseFormat,
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

function parseVisionMoment(raw: string): VisionMomentInsight {
  const objectMatch = raw.match(/\{[\s\S]*\}/);
  if (!objectMatch) throw new Error("Groq vision response was malformed");
  const parsed = JSON.parse(objectMatch[0]) as {
    title?: string;
    summary?: string;
    activity?: string;
    confidence?: number;
  };
  return {
    title: String(parsed.title ?? "Room shift").trim().slice(0, 28) || "Room shift",
    summary: String(parsed.summary ?? "The room changed, but the moment stays ambiguous.")
      .trim()
      .slice(0, 180) || "The room changed, but the moment stays ambiguous.",
    activity: String(parsed.activity ?? "unclear").trim().slice(0, 32) || "unclear",
    confidence: clamp(parsed.confidence, 0, 1, 0.4),
  };
}

export async function interpretRoomMoment(input: {
  capturedAt: string;
  labels: string[];
  changeScore: number;
  luma: number;
  faceCount: number;
  recognized: "me" | "other" | "unknown" | "none";
  personLabel?: string;
  musicActive: boolean;
  groove: number;
  imageBase64: string;
}): Promise<VisionMomentInsight | null> {
  if (!groqApiKey()) return null;

  const content: Array<Record<string, unknown>> = [
    {
      type: "text",
      text:
        "You are interpreting one low-resolution room snapshot from a wall companion. " +
        "Be conservative. Do not identify people unless the provided metadata already does. " +
        "Infer likely activity only at a high level, such as working, resting, moving through the room, or listening to music. " +
        "Return JSON only with keys: title, summary, activity, confidence. " +
        `Metadata: capturedAt=${input.capturedAt}; labels=${input.labels.join(", ") || "none"}; ` +
        `changeScore=${input.changeScore.toFixed(2)}; luma=${input.luma.toFixed(1)}; faceCount=${input.faceCount}; ` +
        `recognized=${input.recognized}; personLabel=${input.personLabel ?? "none"}; ` +
        `musicActive=${input.musicActive}; groove=${input.groove.toFixed(2)}.`,
    },
    {
      type: "image_url",
      image_url: {
        url: `data:image/jpeg;base64,${input.imageBase64}`,
      },
    },
  ];

  const raw = await groqChat(
    [{ role: "user", content }],
    220,
    0.3,
    undefined,
    groqVisionModel(),
  );
  return parseVisionMoment(raw);
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
