import type {
  AgendaItem,
  ConnectorOutput,
  ManifestConnector,
  ManifestContext,
  PanelSlot,
  SceneReminder,
} from "../manifest";

/** How many minutes before a timed event Mawa starts nudging you. */
const LEAD_MINUTES = 10;

/**
 * Calendar via a secret iCal (.ics) URL — the "Secret address in iCal format"
 * Google gives each calendar. No OAuth, no client credentials, no token
 * storage, no encryption at rest: the URL itself is a read-only bearer
 * capability, the same trust model as the paired-device token. Set
 * MAWA_CALENDAR_PERSONAL_ICS / MAWA_CALENDAR_WORK_ICS and you're done.
 */
type Slot = "personal" | "work";

interface SlotDefinition {
  slot: Slot;
  connectorId: "calendar-personal" | "calendar-work";
  name: string;
  shortName: string;
  panelSlot: PanelSlot;
  eyebrow: string;
  accent: string;
  envVar: string;
}

const SLOTS: Record<Slot, SlotDefinition> = {
  personal: {
    slot: "personal",
    connectorId: "calendar-personal",
    name: "Personal Calendar",
    shortName: "Personal",
    panelSlot: "bottom-left",
    eyebrow: "PERSONAL NEXT",
    accent: "#A5D6A7",
    envVar: "MAWA_CALENDAR_PERSONAL_ICS",
  },
  work: {
    slot: "work",
    connectorId: "calendar-work",
    name: "Work Calendar",
    shortName: "Work",
    panelSlot: "bottom-right",
    eyebrow: "WORK NEXT",
    accent: "#D2A679",
    envVar: "MAWA_CALENDAR_WORK_ICS",
  },
};

interface CalendarEvent {
  title: string;
  start: Date;
  allDay: boolean;
}

function timeZone(): string {
  return process.env.MAWA_TIME_ZONE || "America/New_York";
}

function isValidTimeZone(tz: string | undefined): tz is string {
  if (!tz) return false;
  try {
    new Intl.DateTimeFormat("en-US", { timeZone: tz });
    return true;
  } catch {
    return false;
  }
}

/** Interpret a naive wall-clock time as being in [tz] and return the UTC instant. */
function zonedToUtc(
  y: number,
  mo: number,
  d: number,
  h: number,
  mi: number,
  s: number,
  tz: string,
): Date {
  const guess = Date.UTC(y, mo - 1, d, h, mi, s);
  const parts = Object.fromEntries(
    new Intl.DateTimeFormat("en-US", {
      timeZone: tz,
      hourCycle: "h23",
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
    })
      .formatToParts(new Date(guess))
      .map((part) => [part.type, part.value]),
  );
  const asUtc = Date.UTC(
    Number(parts.year),
    Number(parts.month) - 1,
    Number(parts.day),
    Number(parts.hour),
    Number(parts.minute),
    Number(parts.second),
  );
  return new Date(guess - (asUtc - guess));
}

function parseIcsDate(value: string, tzid: string | undefined): CalendarEvent["start"] | null {
  const match = value.trim().match(/^(\d{4})(\d{2})(\d{2})(?:T(\d{2})(\d{2})(\d{2})(Z)?)?/);
  if (!match) return null;
  const [, y, mo, d, h, mi, s, z] = match;
  const year = Number(y);
  const month = Number(mo);
  const day = Number(d);
  if (!h) {
    // VALUE=DATE (all-day): anchor to local midnight in the display zone.
    return zonedToUtc(year, month, day, 0, 0, 0, timeZone());
  }
  if (z) return new Date(Date.UTC(year, month - 1, day, Number(h), Number(mi), Number(s)));
  const tz = isValidTimeZone(tzid) ? tzid : timeZone();
  return zonedToUtc(year, month, day, Number(h), Number(mi), Number(s), tz);
}

function unfold(ics: string): string[] {
  const raw = ics.replace(/\r\n/g, "\n").replace(/\r/g, "\n").split("\n");
  const out: string[] = [];
  for (const line of raw) {
    if ((line.startsWith(" ") || line.startsWith("\t")) && out.length) {
      out[out.length - 1] += line.slice(1);
    } else {
      out.push(line);
    }
  }
  return out;
}

function unescapeText(value: string): string {
  return value
    .replace(/\\n/gi, " ")
    .replace(/\\,/g, ",")
    .replace(/\\;/g, ";")
    .replace(/\\\\/g, "\\");
}

function parseEvents(ics: string): CalendarEvent[] {
  const events: CalendarEvent[] = [];
  let current: { start?: Date; allDay?: boolean; summary?: string; cancelled?: boolean } | null =
    null;
  for (const line of unfold(ics)) {
    if (line === "BEGIN:VEVENT") {
      current = {};
      continue;
    }
    if (line === "END:VEVENT") {
      if (current?.start && !current.cancelled) {
        events.push({
          title: (current.summary || "Busy").replace(/\s+/g, " ").trim().slice(0, 32) || "Busy",
          start: current.start,
          allDay: current.allDay ?? false,
        });
      }
      current = null;
      continue;
    }
    if (!current) continue;
    const colon = line.indexOf(":");
    if (colon < 0) continue;
    const [name, ...params] = line.slice(0, colon).split(";");
    const value = line.slice(colon + 1);
    switch (name.toUpperCase()) {
      case "DTSTART": {
        const tzid = params.find((p) => p.toUpperCase().startsWith("TZID="))?.slice(5);
        current.allDay = params.some((p) => /^VALUE=DATE$/i.test(p));
        current.start = parseIcsDate(value, tzid) ?? undefined;
        break;
      }
      case "SUMMARY":
        current.summary = unescapeText(value);
        break;
      case "STATUS":
        current.cancelled = value.trim().toUpperCase() === "CANCELLED";
        break;
    }
  }
  return events;
}

async function fetchUpcomingEvents(url: string, from: Date): Promise<CalendarEvent[]> {
  const response = await fetch(url, {
    cache: "no-store",
    headers: { Accept: "text/calendar" },
    signal: AbortSignal.timeout(8_000),
  });
  if (!response.ok) throw new Error(`Calendar feed returned ${response.status}`);
  const horizon = from.getTime() + 24 * 60 * 60_000;
  return parseEvents(await response.text())
    .filter((event) => {
      const ms = event.start.getTime();
      return ms >= from.getTime() - 60_000 && ms <= horizon;
    })
    .sort((a, b) => a.start.getTime() - b.start.getTime());
}

function when(event: CalendarEvent): string {
  if (event.allDay) return "All day";
  return new Intl.DateTimeFormat("en-US", {
    weekday: "short",
    hour: "numeric",
    minute: "2-digit",
    timeZone: timeZone(),
  }).format(event.start);
}

/** Local calendar date (YYYY-MM-DD) in the display zone, for same-day checks. */
function localDay(date: Date): string {
  return new Intl.DateTimeFormat("en-CA", {
    timeZone: timeZone(),
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).format(date);
}

/** A short, time-only label for the morning brief ("9:30 AM" / "All day"). */
function briefLabel(event: CalendarEvent): string {
  if (event.allDay) return "All day";
  return new Intl.DateTimeFormat("en-US", {
    hour: "numeric",
    minute: "2-digit",
    timeZone: timeZone(),
  }).format(event.start);
}

/** Today's remaining events (from the already-fetched, filtered, sorted list). */
function agendaFrom(feed: SlotDefinition, events: CalendarEvent[], now: Date): AgendaItem[] {
  const today = localDay(now);
  return events
    .filter((event) => localDay(event.start) === today)
    .slice(0, 6)
    .map((event) => ({
      title: event.title,
      label: briefLabel(event),
      startMs: event.start.getTime(),
      allDay: event.allDay,
      slot: feed.slot,
    }));
}

/** The soonest timed event, if it starts within the lead window, as a reminder. */
function reminderFrom(
  feed: SlotDefinition,
  events: CalendarEvent[],
  now: Date,
): SceneReminder | undefined {
  const next = events.find((event) => !event.allDay);
  if (!next) return undefined;
  const minutesUntil = Math.round((next.start.getTime() - now.getTime()) / 60_000);
  if (minutesUntil < 0 || minutesUntil > LEAD_MINUTES) return undefined;
  return {
    id: `${feed.connectorId}-${next.start.getTime()}`,
    title: next.title,
    when: minutesUntil <= 0 ? "starting now" : `in ${minutesUntil} min`,
    minutesUntil,
    slot: feed.slot,
    accent: feed.accent,
  };
}

function plannedState(feed: SlotDefinition, message: string): ConnectorOutput {
  return {
    state: { id: feed.connectorId, name: feed.name, status: "planned", message },
    panels: [],
  };
}

export function icsCalendarConnector(slot: Slot): ManifestConnector {
  const feed = SLOTS[slot];
  return {
    id: feed.connectorId,
    name: feed.name,
    async build(context: ManifestContext): Promise<ConnectorOutput> {
      const url = process.env[feed.envVar]?.trim();
      if (!url) {
        return plannedState(
          feed,
          `Set ${feed.envVar} to the ${feed.shortName.toLowerCase()} calendar's secret iCal URL`,
        );
      }

      // Event details are private; only the paired device receives the panel.
      if (!context.privateAccess) {
        return {
          state: {
            id: feed.connectorId,
            name: feed.name,
            status: "ready",
            message: "Connected via iCal; event details stay private",
          },
          panels: [],
        };
      }

      try {
        const events = await fetchUpcomingEvents(url, context.now);
        const next = events[0];
        const expiresAt = new Date(context.now.getTime() + 5 * 60_000).toISOString();
        return {
          state: {
            id: feed.connectorId,
            name: feed.name,
            status: "ready",
            message: next ? `${events.length} event(s) in the next 24 hours` : "Clear for 24 hours",
            lastUpdatedAt: context.now.toISOString(),
          },
          reminder: reminderFrom(feed, events, context.now),
          agenda: agendaFrom(feed, events, context.now),
          panels: [
            {
              id: `${feed.connectorId}-next`,
              connector: feed.connectorId,
              slot: feed.panelSlot,
              eyebrow: feed.eyebrow,
              title: next?.title ?? "Clear",
              detail: next ? when(next) : "Next 24 hours",
              accent: feed.accent,
              expiresAt,
            },
          ],
        };
      } catch (error) {
        return {
          state: {
            id: feed.connectorId,
            name: feed.name,
            status: "degraded",
            message: error instanceof Error ? error.message : "Calendar feed unavailable",
          },
          panels: [],
        };
      }
    },
  };
}

export const personalCalendarConnector = icsCalendarConnector("personal");
export const workCalendarConnector = icsCalendarConnector("work");
