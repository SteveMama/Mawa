import type { ManifestConnector, ConnectorOutput, ManifestContext, PanelSlot } from "../manifest";

interface CalendarFeed {
  id: "calendar-personal" | "calendar-work";
  name: string;
  envKey: "PERSONAL_CALENDAR_ICS_URL" | "WORK_CALENDAR_ICS_URL";
  slot: PanelSlot;
  eyebrow: string;
}

interface UpcomingEvent {
  title: string;
  start: Date;
  allDay: boolean;
}

function dateValues(value: unknown): Date[] {
  if (!value || typeof value !== "object") return [];
  return Object.values(value).filter((item): item is Date => item instanceof Date);
}

function eventStarts(
  event: import("node-ical").VEvent,
  from: Date,
  to: Date,
): UpcomingEvent[] {
  const durationMs = Math.max(0, event.end.getTime() - event.start.getTime());
  const rangeStart = new Date(from.getTime() - durationMs);
  const starts = event.rrule ? event.rrule.between(rangeStart, to, true) : [event.start];
  const excluded = dateValues(event.exdate).map((date) => date.getTime());
  const overrides = Object.values(event.recurrences ?? {});

  return starts.flatMap((occurrence) => {
    if (excluded.includes(occurrence.getTime())) return [];
    const override = overrides.find((candidate) =>
      candidate.recurrenceid instanceof Date &&
      candidate.recurrenceid.getTime() === occurrence.getTime()
    );
    if (override?.status === "CANCELLED") return [];
    const start = override?.start ? new Date(override.start) : new Date(occurrence);
    const end = override?.end ? new Date(override.end) : new Date(start.getTime() + durationMs);
    if (end < from || start > to) return [];
    return [{
      title: text(override?.summary ?? event.summary).replace(/\s+/g, " ").trim().slice(0, 32) || "Busy",
      start,
      allDay: (override?.datetype ?? event.datetype) === "date",
    }];
  });
}

function text(value: unknown): string {
  if (typeof value === "string") return value;
  if (value && typeof value === "object" && "val" in value) return String(value.val);
  return "Busy";
}

function validateFeedUrl(raw: string): URL {
  const url = new URL(raw);
  if (url.protocol !== "https:" || url.hostname !== "calendar.google.com") {
    throw new Error("calendar feed must be an HTTPS calendar.google.com URL");
  }
  return url;
}

async function upcoming(url: URL, from: Date): Promise<UpcomingEvent[]> {
  // Keep the CommonJS parser out of Next's route-module initialization. This
  // also avoids doing any calendar work for public dashboard previews.
  const ical = await import("node-ical");
  const response = await fetch(url, {
    cache: "no-store",
    redirect: "follow",
    signal: AbortSignal.timeout(5_000),
  });
  if (!response.ok) throw new Error(`Google Calendar returned ${response.status}`);
  const body = await response.text();
  const calendar = ical.sync.parseICS(body);
  const to = new Date(from.getTime() + 24 * 60 * 60_000);
  const events: UpcomingEvent[] = [];

  for (const component of Object.values(calendar)) {
    if (!component || component.type !== "VEVENT" || component.status === "CANCELLED") continue;
    events.push(...eventStarts(component, from, to));
  }

  return events
    .filter((event) => event.start <= to)
    .sort((a, b) => a.start.getTime() - b.start.getTime())
    .slice(0, 4);
}

function when(event: UpcomingEvent): string {
  if (event.allDay) return "All day";
  return new Intl.DateTimeFormat("en-US", {
    weekday: "short",
    hour: "numeric",
    minute: "2-digit",
    timeZone: process.env.MAWA_TIME_ZONE || "America/New_York",
  }).format(event.start);
}

export function googleCalendarConnector(feed: CalendarFeed): ManifestConnector {
  return {
    id: feed.id,
    name: feed.name,
    async build(context: ManifestContext): Promise<ConnectorOutput> {
      const rawUrl = process.env[feed.envKey]?.trim();
      if (!rawUrl) {
        return {
          state: {
            id: feed.id,
            name: feed.name,
            status: "planned",
            message: `Add ${feed.envKey} in Vercel to connect`,
          },
          panels: [],
        };
      }

      if (!context.privateAccess) {
        return {
          state: {
            id: feed.id,
            name: feed.name,
            status: "ready",
            message: "Connected; event details are visible only to the paired phone",
          },
          panels: [],
        };
      }

      try {
        const events = await upcoming(validateFeedUrl(rawUrl), context.now);
        const next = events[0];
        const expiresAt = new Date(context.now.getTime() + 5 * 60_000).toISOString();
        return {
          state: {
            id: feed.id,
            name: feed.name,
            status: "ready",
            message: next ? `${events.length} event(s) in the next 24 hours` : "Clear for 24 hours",
            lastUpdatedAt: context.now.toISOString(),
          },
          panels: [
            {
              id: `${feed.id}-next`,
              connector: feed.id,
              slot: feed.slot,
              eyebrow: feed.eyebrow,
              title: next?.title ?? "Clear",
              detail: next ? when(next) : "Next 24 hours",
              accent: feed.id === "calendar-work" ? "#D2A679" : "#A5D6A7",
              expiresAt,
            },
          ],
        };
      } catch (error) {
        return {
          state: {
            id: feed.id,
            name: feed.name,
            status: "degraded",
            message: error instanceof Error ? error.message : "Calendar unavailable",
          },
          panels: [],
        };
      }
    },
  };
}

export const personalCalendarConnector = googleCalendarConnector({
  id: "calendar-personal",
  name: "Personal Calendar",
  envKey: "PERSONAL_CALENDAR_ICS_URL",
  slot: "bottom-left",
  eyebrow: "PERSONAL NEXT",
});

export const workCalendarConnector = googleCalendarConnector({
  id: "calendar-work",
  name: "Work Calendar",
  envKey: "WORK_CALENDAR_ICS_URL",
  slot: "bottom-right",
  eyebrow: "WORK NEXT",
});
