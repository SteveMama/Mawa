import type { PanelSlot } from "../manifest";

export type GoogleCalendarSlot = "personal" | "work";

export interface GoogleCalendarSlotDefinition {
  slot: GoogleCalendarSlot;
  connectorId: "calendar-personal" | "calendar-work";
  name: string;
  shortName: string;
  panelSlot: PanelSlot;
  eyebrow: string;
  accent: string;
}

export const GOOGLE_CALENDAR_SLOTS: GoogleCalendarSlotDefinition[] = [
  {
    slot: "personal",
    connectorId: "calendar-personal",
    name: "Personal Calendar",
    shortName: "Personal",
    panelSlot: "bottom-left",
    eyebrow: "PERSONAL NEXT",
    accent: "#A5D6A7",
  },
  {
    slot: "work",
    connectorId: "calendar-work",
    name: "Work Calendar",
    shortName: "Work",
    panelSlot: "bottom-right",
    eyebrow: "WORK NEXT",
    accent: "#D2A679",
  },
];

export function slotDefinition(slot: GoogleCalendarSlot): GoogleCalendarSlotDefinition {
  const definition = GOOGLE_CALENDAR_SLOTS.find((entry) => entry.slot === slot);
  if (!definition) throw new Error(`Unknown Google Calendar slot: ${slot}`);
  return definition;
}

export function parseGoogleCalendarSlot(value: string | null): GoogleCalendarSlot | null {
  return value === "personal" || value === "work" ? value : null;
}

