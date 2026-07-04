import { isDashboardAuthorized } from "../../../../lib/auth";
import { NextResponse } from "next/server";
import {
  dashboardAdminConfigured,
  dashboardAdminRequired,
  googleCalendarPrerequisites,
} from "../../../../lib/google/oauth";
import {
  googleCalendarStorageMode,
  googleCalendarStorageReady,
  listStoredGoogleCalendarAccounts,
} from "../../../../lib/google/store";
import { GOOGLE_CALENDAR_SLOTS } from "../../../../lib/google/shared";

export const dynamic = "force-dynamic";

export async function GET(request: Request) {
  try {
    const adminAuthorized = isDashboardAuthorized(request);
    const prerequisites = googleCalendarPrerequisites();
    const storageMode = googleCalendarStorageMode();
    const accounts = await listStoredGoogleCalendarAccounts();
    return NextResponse.json({
      ready: prerequisites.ready && googleCalendarStorageReady(),
      adminRequired: dashboardAdminRequired(),
      adminConfigured: dashboardAdminConfigured(),
      adminAuthorized,
      missing: prerequisites.missing,
      storageMode,
      slots: GOOGLE_CALENDAR_SLOTS.map((slot) => {
        const account = accounts[slot.slot];
        return {
          slot: slot.slot,
          name: slot.shortName,
          connected: !!account,
          email: adminAuthorized ? account?.email : undefined,
          connectedAt: account?.connectedAt,
          message: account
            ? adminAuthorized && account.email
              ? `Connected to ${account.email}`
              : "Connected to this account's primary calendar"
            : prerequisites.ready && googleCalendarStorageReady()
              ? `Connect a ${slot.shortName.toLowerCase()} Google account`
              : "Google auth is not fully configured yet",
        };
      }),
    });
  } catch (error) {
    return NextResponse.json(
      {
        ready: false,
        adminRequired: dashboardAdminRequired(),
        adminConfigured: dashboardAdminConfigured(),
        adminAuthorized: isDashboardAuthorized(request),
        missing: ["storage"],
        storageMode: googleCalendarStorageMode(),
        slots: GOOGLE_CALENDAR_SLOTS.map((slot) => ({
          slot: slot.slot,
          name: slot.shortName,
          connected: false,
          message: error instanceof Error ? error.message : "Calendar status unavailable",
        })),
      },
      { status: 200 },
    );
  }
}
