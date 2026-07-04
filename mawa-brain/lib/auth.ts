import { timingSafeEqual } from "node:crypto";

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
