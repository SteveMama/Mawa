import {
  createCipheriv,
  createDecipheriv,
  createHash,
  createHmac,
  randomBytes,
  timingSafeEqual,
} from "node:crypto";

interface SignedPayload<T> {
  data: T;
  exp?: number;
  iat: number;
}

function encode(value: string | Buffer): string {
  return Buffer.from(value).toString("base64url");
}

function decode(value: string): Buffer {
  return Buffer.from(value, "base64url");
}

function key(secret: string): Buffer {
  return createHash("sha256").update(secret).digest();
}

export function encryptString(value: string, secret: string): string {
  const iv = randomBytes(12);
  const cipher = createCipheriv("aes-256-gcm", key(secret), iv);
  const encrypted = Buffer.concat([cipher.update(value, "utf8"), cipher.final()]);
  const tag = cipher.getAuthTag();
  return `${encode(iv)}.${encode(tag)}.${encode(encrypted)}`;
}

export function decryptString(payload: string, secret: string): string {
  const [ivPart, tagPart, bodyPart] = payload.split(".");
  if (!ivPart || !tagPart || !bodyPart) throw new Error("Encrypted payload is malformed");
  const decipher = createDecipheriv("aes-256-gcm", key(secret), decode(ivPart));
  decipher.setAuthTag(decode(tagPart));
  const plain = Buffer.concat([decipher.update(decode(bodyPart)), decipher.final()]);
  return plain.toString("utf8");
}

export function signPayload<T>(payload: T, secret: string, maxAgeSeconds?: number): string {
  const body: SignedPayload<T> = {
    data: payload,
    iat: Date.now(),
    exp: maxAgeSeconds ? Date.now() + maxAgeSeconds * 1000 : undefined,
  };
  const encoded = encode(JSON.stringify(body));
  const signature = createHmac("sha256", secret).update(encoded).digest("base64url");
  return `${encoded}.${signature}`;
}

export function verifyPayload<T>(token: string, secret: string): T | null {
  const [encoded, signature] = token.split(".");
  if (!encoded || !signature) return null;
  const expected = createHmac("sha256", secret).update(encoded).digest();
  const supplied = Buffer.from(signature, "base64url");
  if (expected.length !== supplied.length || !timingSafeEqual(expected, supplied)) return null;
  const parsed = JSON.parse(decode(encoded).toString("utf8")) as SignedPayload<T>;
  if (parsed.exp && parsed.exp < Date.now()) return null;
  return parsed.data;
}

