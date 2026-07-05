import type { ConnectorState, ManifestConnector } from "../manifest";
import { companionConnector } from "./companion";
import { weatherConnector } from "./weather";
import { personalCalendarConnector, workCalendarConnector } from "./google-calendar";

export const activeConnectors: ManifestConnector[] = [
  weatherConnector,
  companionConnector,
  personalCalendarConnector,
  workCalendarConnector,
];

export const plannedConnectors: ConnectorState[] = [
  {
    id: "gmail",
    name: "Gmail",
    status: "planned",
    message: "Read-only important-sender mentions",
  },
  {
    id: "spotify",
    name: "Spotify",
    status: "planned",
    message: "Now-playing reactions and Connect playback",
  },
];
