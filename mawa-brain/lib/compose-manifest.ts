import { activeConnectors, plannedConnectors } from "./connectors/registry";
import {
  MANIFEST_SCHEMA_VERSION,
  type ConnectorOutput,
  type ManifestContext,
  type RoomContext,
  type SceneBrief,
  type SceneManifest,
} from "./manifest";

function timeZoneName(): string {
  return process.env.MAWA_TIME_ZONE || "America/New_York";
}

function dayPart(now: Date): RoomContext["dayPart"] {
  const hour = Number(
    new Intl.DateTimeFormat("en-US", {
      hour: "numeric",
      hour12: false,
      timeZone: timeZoneName(),
    }).format(now),
  );
  if (hour >= 22 || hour <= 5) return "late night";
  if (hour <= 11) return "morning";
  if (hour <= 16) return "afternoon";
  return "evening";
}

/** A once-a-morning summary of the day, built from the calendars' agendas. */
function buildBrief(now: Date, outputs: ConnectorOutput[]): SceneBrief | undefined {
  if (dayPart(now) !== "morning") return undefined;
  const items = outputs
    .flatMap((output) => output.agenda ?? [])
    .sort((a, b) => a.startMs - b.startMs);
  if (items.length === 0) return undefined;
  const dayKey = new Intl.DateTimeFormat("en-CA", {
    timeZone: timeZoneName(),
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).format(now);
  return {
    id: `brief-${dayKey}`,
    headline: items.length === 1 ? "1 thing today" : `${items.length} things today`,
    lines: items.slice(0, 3).map((item) => `${item.label} · ${item.title}`),
    accent: "#B6D9F2",
  };
}

function roomFrom(context: ManifestContext, dataOutputs: ConnectorOutput[]): RoomContext {
  return {
    dayPart: dayPart(context.now),
    weather: dataOutputs.find((output) => output.weather)?.weather?.condition,
    // Calendar panels only exist when the request is from the paired device, so
    // event details reach the companion prompt only on the private path.
    events: dataOutputs
      .flatMap((output) => output.panels)
      .filter((panel) => panel.connector.startsWith("calendar"))
      .map((panel) => ({
        title: panel.title,
        when: panel.detail ?? "",
        slot: panel.connector.includes("work") ? ("work" as const) : ("personal" as const),
      })),
    perception: context.perception,
  };
}

export async function composeManifest(context: ManifestContext): Promise<SceneManifest> {
  // Two passes: run the data connectors first, distill the room, then let the
  // personality connector reason about that room instead of about nothing.
  const dataConnectors = activeConnectors.filter((connector) => connector.id !== "companion");
  const dataOutputs = await Promise.all(dataConnectors.map((connector) => connector.build(context)));

  const room = roomFrom(context, dataOutputs);
  const companionConnector = activeConnectors.find((connector) => connector.id === "companion");
  const companionOutput = companionConnector ? await companionConnector.build({ ...context, room }) : null;

  // Reassemble in registry order so mood/animation precedence is stable.
  const outputs = activeConnectors
    .map((connector) =>
      connector.id === "companion"
        ? companionOutput
        : dataOutputs[dataConnectors.indexOf(connector)],
    )
    .filter((output): output is ConnectorOutput => !!output);

  const generatedAt = context.now.toISOString();
  const expiresAt = new Date(context.now.getTime() + 5 * 60_000).toISOString();
  const weather = outputs.find((output) => output.weather)?.weather;
  const mood = outputs
    .map((output) => output.suggestedMood)
    .filter((value): value is NonNullable<typeof value> => !!value)
    .at(-1) ?? "neutral";
  const animation = outputs
    .map((output) => output.suggestedAnimation)
    .filter((value): value is NonNullable<typeof value> => !!value)
    .at(-1);
  const companionDirective = outputs
    .map((output) => output.suggestedCompanion)
    .filter((value): value is NonNullable<typeof value> => !!value)
    .at(-1);
  const reminder = outputs
    .map((output) => output.reminder)
    .filter((value): value is NonNullable<typeof value> => !!value)
    .sort((a, b) => a.minutesUntil - b.minutesUntil)
    .at(0);
  const brief = buildBrief(context.now, outputs);

  return {
    schemaVersion: MANIFEST_SCHEMA_VERSION,
    manifestId: `mawa-${context.now.getTime()}`,
    generatedAt,
    expiresAt,
    // Poll faster while a reminder is live so its countdown stays fresh.
    pollAfterSeconds: reminder ? 60 : 120,
    scene: {
      mode: "ambient",
      mood,
      weather,
      animation,
      companion: companionDirective,
      reminder,
      brief,
      panels: outputs.flatMap((output) => output.panels),
    },
    connectors: [...outputs.map((output) => output.state), ...plannedConnectors],
    privacy: {
      cameraFramesLeaveDevice: true,
      audioUploaded: false,
    },
  };
}
