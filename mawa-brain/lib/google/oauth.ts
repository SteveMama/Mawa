import { randomUUID, timingSafeEqual } from "node:crypto";
import type { GoogleCalendarSlot } from "./shared";
import { signPayload, verifyPayload } from "./secure";

export interface GoogleOAuthTokens {
  accessToken: string;
  refreshToken?: string;
  scope: string;
  expiresIn: number;
  idToken?: string;
}

export interface GoogleCalendarEvent {
  title: string;
  start: Date;
  allDay: boolean;
}

interface GoogleOAuthState {
  slot: GoogleCalendarSlot;
  nonce: string;
}

const GOOGLE_OAUTH_SCOPES = [
  "https://www.googleapis.com/auth/calendar.readonly",
  "openid",
  "email",
  "profile",
];

const GOOGLE_AUTH_BASE =
  process.env.GOOGLE_AUTH_BASE_URL?.trim() || "https://accounts.google.com/o/oauth2/v2/auth";
const GOOGLE_TOKEN_URL =
  process.env.GOOGLE_TOKEN_URL_OVERRIDE?.trim() || "https://oauth2.googleapis.com/token";
const GOOGLE_REVOKE_URL =
  process.env.GOOGLE_REVOKE_URL_OVERRIDE?.trim() || "https://oauth2.googleapis.com/revoke";
const GOOGLE_EVENTS_BASE =
  process.env.GOOGLE_CALENDAR_EVENTS_URL?.trim() ||
  "https://www.googleapis.com/calendar/v3/calendars/primary/events";

function signingSecret(): string | null {
  const value = process.env.MAWA_SIGNING_SECRET?.trim();
  return value && value.length >= 16 ? value : null;
}

function clientId(): string | null {
  return process.env.GOOGLE_CLIENT_ID?.trim() || null;
}

function clientSecret(): string | null {
  return process.env.GOOGLE_CLIENT_SECRET?.trim() || null;
}

function requireClientId(): string {
  const value = clientId();
  if (!value) throw new Error("Set GOOGLE_CLIENT_ID to enable Google Calendar auth");
  return value;
}

function requireClientSecret(): string {
  const value = clientSecret();
  if (!value) throw new Error("Set GOOGLE_CLIENT_SECRET to enable Google Calendar auth");
  return value;
}

function requireSigningSecret(): string {
  const value = signingSecret();
  if (!value) throw new Error("Set MAWA_SIGNING_SECRET for OAuth state protection");
  return value;
}

export function googleCalendarPrerequisites(): { ready: boolean; missing: string[] } {
  const missing: string[] = [];
  if (!clientId()) missing.push("GOOGLE_CLIENT_ID");
  if (!clientSecret()) missing.push("GOOGLE_CLIENT_SECRET");
  if (!signingSecret()) missing.push("MAWA_SIGNING_SECRET");
  if (!process.env.MAWA_STATE_ENCRYPTION_SECRET?.trim()) missing.push("MAWA_STATE_ENCRYPTION_SECRET");
  return { ready: missing.length === 0, missing };
}

function adminToken(): string | null {
  return process.env.MAWA_DASHBOARD_ADMIN_TOKEN?.trim() || null;
}

export function dashboardAdminRequired(): boolean {
  return !!adminToken();
}

export function dashboardAdminConfigured(): boolean {
  return !!adminToken();
}

export function verifyDashboardAdminToken(candidate: string): boolean {
  const expected = adminToken();
  if (!expected) return process.env.VERCEL !== "1";
  const expectedBytes = Buffer.from(expected);
  const suppliedBytes = Buffer.from(candidate);
  return expectedBytes.length === suppliedBytes.length && timingSafeEqual(expectedBytes, suppliedBytes);
}

function redirectUri(origin: string): string {
  return `${origin}/api/google/callback`;
}

function tokenBody(params: Record<string, string>): URLSearchParams {
  const body = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => body.set(key, value));
  return body;
}

async function parseTokenResponse(response: Response): Promise<GoogleOAuthTokens> {
  const payload = (await response.json()) as {
    access_token?: string;
    refresh_token?: string;
    scope?: string;
    expires_in?: number;
    id_token?: string;
    error?: string;
    error_description?: string;
  };
  if (!response.ok || !payload.access_token || !payload.scope || !payload.expires_in) {
    const detail = payload.error_description || payload.error || `Google token exchange returned ${response.status}`;
    throw new Error(detail);
  }
  return {
    accessToken: payload.access_token,
    refreshToken: payload.refresh_token,
    scope: payload.scope,
    expiresIn: payload.expires_in,
    idToken: payload.id_token,
  };
}

export function buildGoogleConnectUrl(origin: string, slot: GoogleCalendarSlot): string {
  const authUrl = new URL(GOOGLE_AUTH_BASE);
  authUrl.searchParams.set("client_id", requireClientId());
  authUrl.searchParams.set("redirect_uri", redirectUri(origin));
  authUrl.searchParams.set("response_type", "code");
  authUrl.searchParams.set("scope", GOOGLE_OAUTH_SCOPES.join(" "));
  authUrl.searchParams.set("access_type", "offline");
  authUrl.searchParams.set("include_granted_scopes", "true");
  authUrl.searchParams.set("prompt", "consent select_account");
  authUrl.searchParams.set(
    "state",
    signPayload<GoogleOAuthState>(
      { slot, nonce: randomUUID() },
      requireSigningSecret(),
      10 * 60,
    ),
  );
  return authUrl.toString();
}

export function parseGoogleConnectState(state: string | null): GoogleOAuthState | null {
  if (!state) return null;
  try {
    return verifyPayload<GoogleOAuthState>(state, requireSigningSecret());
  } catch {
    return null;
  }
}

export async function exchangeGoogleCode(
  code: string,
  origin: string,
): Promise<GoogleOAuthTokens> {
  const response = await fetch(GOOGLE_TOKEN_URL, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: tokenBody({
      code,
      client_id: requireClientId(),
      client_secret: requireClientSecret(),
      redirect_uri: redirectUri(origin),
      grant_type: "authorization_code",
    }),
    cache: "no-store",
    signal: AbortSignal.timeout(8_000),
  });
  return parseTokenResponse(response);
}

export async function refreshGoogleAccessToken(refreshToken: string): Promise<GoogleOAuthTokens> {
  const response = await fetch(GOOGLE_TOKEN_URL, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: tokenBody({
      client_id: requireClientId(),
      client_secret: requireClientSecret(),
      refresh_token: refreshToken,
      grant_type: "refresh_token",
    }),
    cache: "no-store",
    signal: AbortSignal.timeout(8_000),
  });
  return parseTokenResponse(response);
}

export async function revokeGoogleRefreshToken(refreshToken: string): Promise<void> {
  const response = await fetch(GOOGLE_REVOKE_URL, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: tokenBody({ token: refreshToken }),
    cache: "no-store",
    signal: AbortSignal.timeout(5_000),
  });
  if (!response.ok) throw new Error(`Google revoke returned ${response.status}`);
}

export function profileFromIdToken(idToken: string | undefined): {
  email?: string;
  name?: string;
  picture?: string;
} {
  if (!idToken) return {};
  try {
    const payload = JSON.parse(
      Buffer.from(idToken.split(".")[1] ?? "", "base64url").toString("utf8"),
    ) as Record<string, unknown>;
    return {
      email: typeof payload.email === "string" ? payload.email : undefined,
      name: typeof payload.name === "string" ? payload.name : undefined,
      picture: typeof payload.picture === "string" ? payload.picture : undefined,
    };
  } catch {
    return {};
  }
}

export async function fetchUpcomingPrimaryEvents(
  accessToken: string,
  from: Date,
): Promise<GoogleCalendarEvent[]> {
  const to = new Date(from.getTime() + 24 * 60 * 60_000);
  const url = new URL(GOOGLE_EVENTS_BASE);
  url.searchParams.set("singleEvents", "true");
  url.searchParams.set("orderBy", "startTime");
  url.searchParams.set("maxResults", "4");
  url.searchParams.set("timeMin", from.toISOString());
  url.searchParams.set("timeMax", to.toISOString());
  url.searchParams.set("fields", "items(summary,status,start,end)");

  const response = await fetch(url, {
    headers: { authorization: `Bearer ${accessToken}` },
    cache: "no-store",
    signal: AbortSignal.timeout(8_000),
  });

  const payload = (await response.json()) as {
    items?: Array<{
      summary?: string;
      status?: string;
      start?: { date?: string; dateTime?: string };
    }>;
    error?: { message?: string };
  };

  if (!response.ok) {
    throw new Error(payload.error?.message || `Google Calendar returned ${response.status}`);
  }

  return (payload.items ?? [])
    .filter((item) => item.status !== "cancelled" && item.start && (item.start.date || item.start.dateTime))
    .map((item) => {
      const rawStart = item.start?.dateTime ?? item.start?.date ?? "";
      return {
        title: item.summary?.replace(/\s+/g, " ").trim().slice(0, 32) || "Busy",
        start: new Date(rawStart),
        allDay: !!item.start?.date && !item.start?.dateTime,
      };
    });
}

const DASHBOARD_SESSION = "mawa_dashboard_session";

export function createDashboardSessionToken(): string {
  return signPayload({ kind: "dashboard" }, requireSigningSecret(), 14 * 24 * 60 * 60);
}

export function readDashboardSessionToken(token: string | undefined): boolean {
  if (!token) return false;
  try {
    const payload = verifyPayload<{ kind: string }>(token, requireSigningSecret());
    return payload?.kind === "dashboard";
  } catch {
    return false;
  }
}

export const DASHBOARD_SESSION_COOKIE = DASHBOARD_SESSION;
