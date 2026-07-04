import type { ConnectorOutput, ManifestConnector, ManifestContext } from "../manifest";
import {
  fetchUpcomingPrimaryEvents,
  googleCalendarPrerequisites,
  refreshGoogleAccessToken,
} from "../google/oauth";
import {
  getStoredGoogleCalendarAccount,
  removeStoredGoogleCalendarAccount,
} from "../google/store";
import type { GoogleCalendarSlotDefinition } from "../google/shared";
import { slotDefinition } from "../google/shared";

function when(event: { allDay: boolean; start: Date }): string {
  if (event.allDay) return "All day";
  return new Intl.DateTimeFormat("en-US", {
    weekday: "short",
    hour: "numeric",
    minute: "2-digit",
    timeZone: process.env.MAWA_TIME_ZONE || "America/New_York",
  }).format(event.start);
}

function plannedState(feed: GoogleCalendarSlotDefinition, message: string): ConnectorOutput {
  return {
    state: {
      id: feed.connectorId,
      name: feed.name,
      status: "planned",
      message,
    },
    panels: [],
  };
}

export function googleCalendarConnector(slot: "personal" | "work"): ManifestConnector {
  const feed = slotDefinition(slot);
  return {
    id: feed.connectorId,
    name: feed.name,
    async build(context: ManifestContext): Promise<ConnectorOutput> {
      const prerequisites = googleCalendarPrerequisites();
      if (!prerequisites.ready) {
        return plannedState(feed, `Add ${prerequisites.missing.join(", ")} to enable Google sign-in`);
      }

      const account = await getStoredGoogleCalendarAccount(feed.slot);
      if (!account) return plannedState(feed, `Connect the ${feed.shortName.toLowerCase()} Google account in the dashboard`);

      if (!context.privateAccess) {
        return {
          state: {
            id: feed.connectorId,
            name: feed.name,
            status: "ready",
            message: account.email
              ? `Connected to ${account.email}; event details stay private`
              : "Connected; event details stay private",
          },
          panels: [],
        };
      }

      try {
        const tokens = await refreshGoogleAccessToken(account.refreshToken);
        const events = await fetchUpcomingPrimaryEvents(tokens.accessToken, context.now);
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
        if (error instanceof Error && /invalid_grant/i.test(error.message)) {
          await removeStoredGoogleCalendarAccount(feed.slot);
          return plannedState(feed, "Google revoked the refresh token; reconnect in the dashboard");
        }
        return {
          state: {
            id: feed.connectorId,
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

export const personalCalendarConnector = googleCalendarConnector("personal");
export const workCalendarConnector = googleCalendarConnector("work");
