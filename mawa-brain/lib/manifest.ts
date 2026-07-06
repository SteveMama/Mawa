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

export interface ManifestContext {
  latitude: number;
  longitude: number;
  device?: string;
  appVersion?: string;
  privateAccess: boolean;
  now: Date;
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
