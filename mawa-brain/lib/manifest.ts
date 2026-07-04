export const MANIFEST_SCHEMA_VERSION = 1 as const;

export type MawaMood =
  | "neutral"
  | "happy"
  | "grumpy"
  | "sleepy"
  | "suspicious"
  | "excited";

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
  now: Date;
}

export interface ConnectorOutput {
  state: ConnectorState;
  panels: ScenePanel[];
  weather?: WeatherScene;
  suggestedMood?: MawaMood;
}

export interface ManifestConnector {
  id: string;
  name: string;
  build(context: ManifestContext): Promise<ConnectorOutput>;
}
