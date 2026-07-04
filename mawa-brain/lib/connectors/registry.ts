import type { ConnectorState, ManifestConnector } from "../manifest";
import { weatherConnector } from "./weather";

export const activeConnectors: ManifestConnector[] = [weatherConnector];

export const plannedConnectors: ConnectorState[] = [
  {
    id: "calendar",
    name: "Google Calendar",
    status: "planned",
    message: "Read-only morning briefs and meeting heads-ups",
  },
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
