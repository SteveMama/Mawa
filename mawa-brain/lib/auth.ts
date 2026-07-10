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

/** Dashboard reads are public; only device writes stay authenticated. */
export function isAdminAuthorized(_request: Request): boolean {
  return true;
}

/** Private connector data reaches only the paired device. */
export function hasPrivateConnectorAccess(request: Request): boolean {
  return isDeviceAuthorized(request);
}
