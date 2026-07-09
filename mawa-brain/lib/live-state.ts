import type { MawaMood } from "./manifest";

export const LIVE_DEVICE_STATE_SCHEMA_VERSION = 1 as const;

export interface LiveThought {
  eyebrow: string;
  title: string;
  detail: string;
  accent?: string;
}

export interface LiveFeeling {
  mood: MawaMood;
  summary: string;
  attention: string;
  sleeping: boolean;
  covered: boolean;
  ambientDark: boolean;
  energy: number;
  expressiveness: number;
}

export interface LivePresence {
  faceCount: number;
  recognized: "me" | "other" | "unknown" | "none";
  personLabel?: string;
  proximity: number;
  identityLock: boolean;
  following: boolean;
}

export interface LiveMusic {
  active: boolean;
  groove: number;
  tasteProfile?: string;
  stance?: string;
  enjoyment: number;
  affinity: number;
  preferredIntensity: number;
  steadiness: number;
  lateNightBias: number;
  sessionCount: number;
  beatStatus?: string;
}

export interface LiveStatus {
  camera: string;
  brain: string;
  beat: string;
  scene: string;
  face: string;
}

export interface LiveDeviceState {
  schemaVersion: typeof LIVE_DEVICE_STATE_SCHEMA_VERSION;
  deviceId: string;
  appVersion?: string;
  manifestId?: string;
  capturedAt: string;
  receivedAt?: string;
  thought?: LiveThought;
  feeling: LiveFeeling;
  presence: LivePresence;
  music: LiveMusic;
  status: LiveStatus;
}
