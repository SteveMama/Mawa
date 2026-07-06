import { timingSafeEqual } from "node:crypto";

function safeEqual(expected: string | undefined, supplied: string): boolean {
  if (!expected) return false;
  const expectedBytes = Buffer.from(expected);
  const suppliedBytes = Buffer.from(supplied);
  return expectedBytes.length === suppliedBytes.length && timingSafeEqual(expectedBytes, suppliedBytes);
}

function bearer(request: Request): string {
  const header = request.headers.get("authorization") ?? "";
  return header.startsWith("Bearer ") ? header.slice(7) : "";
}

/** The paired wall device presents MAWA_DEVICE_TOKEN. */
export function isDeviceAuthorized(request: Request): boolean {
  return safeEqual(process.env.MAWA_DEVICE_TOKEN?.trim(), bearer(request));
}

/**
 * Dashboard admin actions (companion tester). Gated by MAWA_DASHBOARD_ADMIN_TOKEN;
 * open on localhost when unset so local dev needs no setup, closed in production
 * until the token is configured. The device token also counts as admin.
 */
export function isAdminAuthorized(request: Request): boolean {
  const admin = process.env.MAWA_DASHBOARD_ADMIN_TOKEN?.trim();
  if (!admin) return process.env.VERCEL !== "1";
  return safeEqual(admin, bearer(request)) || isDeviceAuthorized(request);
}

/** Private connector data (calendar event details) reaches device or admin only. */
export function hasPrivateConnectorAccess(request: Request): boolean {
  return isDeviceAuthorized(request) || isAdminAuthorized(request);
}
