import { timingSafeEqual } from "node:crypto";
import { DASHBOARD_SESSION_COOKIE, readDashboardSessionToken } from "./google/oauth";

/** Private connector data is emitted only to the paired wall device. */
export function isDeviceAuthorized(request: Request): boolean {
  const expected = process.env.MAWA_DEVICE_TOKEN?.trim();
  if (!expected) return false;
  const header = request.headers.get("authorization") ?? "";
  const supplied = header.startsWith("Bearer ") ? header.slice(7) : "";
  const expectedBytes = Buffer.from(expected);
  const suppliedBytes = Buffer.from(supplied);
  return expectedBytes.length === suppliedBytes.length && timingSafeEqual(expectedBytes, suppliedBytes);
}

export function isDashboardAuthorized(request: Request): boolean {
  const cookieHeader = request.headers.get("cookie") ?? "";
  const token = cookieHeader
    .split(/;\s*/)
    .find((part) => part.startsWith(`${DASHBOARD_SESSION_COOKIE}=`))
    ?.slice(DASHBOARD_SESSION_COOKIE.length + 1);
  return readDashboardSessionToken(token);
}

export function hasPrivateConnectorAccess(request: Request): boolean {
  return isDeviceAuthorized(request) || isDashboardAuthorized(request);
}
