export const MANIFEST_SCHEMA_VERSION = 1 as const;

export type MawaMood =
  | "neutral"
  | "happy"
  | "grumpy"
  | "sleepy"
  | "suspicious"
  | "excited";

export type MawaPalette = "cool" | "warm" | "violet" | "teal" | "dusk";

export type MawaGazeMode = "steady" | "curious" | "dart" | "locked" | "dreamy";

export type PanelSlot = "top-left" | "top-right" | "bottom-left" | "bottom-right";

export interface ScenePanel {
  id: string;
  connector: string;
  slot: PanelSlot;
  eyebrow: string;
  title: string;
  detail?: string;
  icon?: string;
  accent?: string;
  expiresAt: string;
}

export interface ConnectorState {
  id: string;
  name: string;
  status: "ready" | "degraded" | "planned";
  message: string;
  lastUpdatedAt?: string;
}

export interface WeatherScene {
  condition: "clear" | "clouds" | "rain" | "snow" | "fog" | "thunder";
  temperatureC: number;
  isDay: boolean;
}

export interface SceneAnimation {
  palette: MawaPalette;
  gazeMode: MawaGazeMode;
  energy: number;
  expressiveness: number;
  aura: number;
  bars: number;
  glyphs: number;
  sway: number;
  bounce: number;
  blinkRate: number;
  openness: number;
  pupilScale: number;
  squint: number;
}

export interface SceneManifest {
  schemaVersion: typeof MANIFEST_SCHEMA_VERSION;
  manifestId: string;
  generatedAt: string;
  expiresAt: string;
  pollAfterSeconds: number;
  scene: {
    mode: "ambient";
    mood: MawaMood;
    weather?: WeatherScene;
    animation?: SceneAnimation;
    panels: ScenePanel[];
  };
  connectors: ConnectorState[];
  privacy: {
    cameraFramesLeaveDevice: false;
    audioUploaded: false;
  };
}

/**
 * Distilled state of the room, assembled from the data connectors and handed to
 * the personality model so its "thought" is grounded in something real (time,
 * weather, what's on the calendar) instead of generated from thin air.
 */
export interface RoomContext {
  dayPart: "morning" | "afternoon" | "evening" | "late night";
  weather?: WeatherScene["condition"];
  events: { title: string; when: string; slot: "personal" | "work" }[];
  perception?: {
    faceCount: number;
    recognized: "me" | "other" | "unknown" | "none";
    personLabel?: string;
    proximity: number;
    covered: boolean;
    ambientDark: boolean;
    musicActive: boolean;
    groove: number;
  };
}

export interface ManifestContext {
  latitude: number;
  longitude: number;
  device?: string;
  appVersion?: string;
  privateAccess: boolean;
  now: Date;
  perception?: RoomContext["perception"];
  /** Populated by composeManifest for the companion pass only. */
  room?: RoomContext;
}

export interface ConnectorOutput {
  state: ConnectorState;
  panels: ScenePanel[];
  weather?: WeatherScene;
  suggestedMood?: MawaMood;
  suggestedAnimation?: SceneAnimation;
}

export interface ManifestConnector {
  id: string;
  name: string;
  build(context: ManifestContext): Promise<ConnectorOutput>;
}
