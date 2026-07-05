import { activeConnectors, plannedConnectors } from "./connectors/registry";
import {
  MANIFEST_SCHEMA_VERSION,
  type ManifestContext,
  type SceneManifest,
} from "./manifest";

export async function composeManifest(context: ManifestContext): Promise<SceneManifest> {
  const outputs = await Promise.all(activeConnectors.map((connector) => connector.build(context)));
  const generatedAt = context.now.toISOString();
  const expiresAt = new Date(context.now.getTime() + 5 * 60_000).toISOString();
  const weather = outputs.find((output) => output.weather)?.weather;
  const mood = outputs
    .map((output) => output.suggestedMood)
    .filter((value): value is NonNullable<typeof value> => !!value)
    .at(-1) ?? "neutral";

  return {
    schemaVersion: MANIFEST_SCHEMA_VERSION,
    manifestId: `mawa-${context.now.getTime()}`,
    generatedAt,
    expiresAt,
    pollAfterSeconds: 300,
    scene: {
      mode: "ambient",
      mood,
      weather,
      panels: outputs.flatMap((output) => output.panels),
    },
    connectors: [...outputs.map((output) => output.state), ...plannedConnectors],
    privacy: {
      cameraFramesLeaveDevice: false,
      audioUploaded: false,
    },
  };
}
