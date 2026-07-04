import { BlobNotFoundError, get, put } from "@vercel/blob";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { dirname, join } from "node:path";
import { decryptString, encryptString } from "./secure";
import type { GoogleCalendarSlot } from "./shared";

export interface StoredGoogleCalendarAccount {
  slot: GoogleCalendarSlot;
  refreshToken: string;
  scope: string;
  connectedAt: string;
  updatedAt: string;
  email?: string;
  name?: string;
  picture?: string;
}

interface GoogleCalendarStoreDocument {
  version: 1;
  slots: Partial<Record<GoogleCalendarSlot, StoredGoogleCalendarAccount>>;
}

export type GoogleCalendarStorageMode = "blob" | "file" | "unavailable";

const STORE_PATHNAME = "mawa/google-calendar-state.json";
const LOCAL_STORE_FILE = join(process.cwd(), ".mawa", "google-calendar-state.json");

function storeSecret(): string | null {
  const value = process.env.MAWA_STATE_ENCRYPTION_SECRET?.trim();
  return value && value.length >= 16 ? value : null;
}

export function googleCalendarStorageMode(): GoogleCalendarStorageMode {
  if (process.env.BLOB_READ_WRITE_TOKEN?.trim()) return "blob";
  if (process.env.VERCEL === "1") return "unavailable";
  return "file";
}

export function googleCalendarStorageReady(): boolean {
  return googleCalendarStorageMode() !== "unavailable" && !!storeSecret();
}

async function readEncrypted(): Promise<string | null> {
  const mode = googleCalendarStorageMode();
  if (mode === "blob") {
    try {
      const response = await get(STORE_PATHNAME, { access: "private" });
      if (!response || response.statusCode !== 200) return null;
      return await new Response(response.stream).text();
    } catch (error) {
      if (error instanceof BlobNotFoundError) return null;
      throw error;
    }
  }

  if (mode === "file") {
    try {
      return await readFile(LOCAL_STORE_FILE, "utf8");
    } catch (error) {
      if (error && typeof error === "object" && "code" in error && error.code === "ENOENT") return null;
      throw error;
    }
  }

  throw new Error("Durable Google Calendar storage is not configured");
}

async function writeEncrypted(payload: string): Promise<void> {
  const mode = googleCalendarStorageMode();
  if (mode === "blob") {
    await put(STORE_PATHNAME, payload, {
      access: "private",
      addRandomSuffix: false,
      allowOverwrite: true,
      contentType: "text/plain; charset=utf-8",
    });
    return;
  }

  if (mode === "file") {
    await mkdir(dirname(LOCAL_STORE_FILE), { recursive: true });
    await writeFile(LOCAL_STORE_FILE, payload, "utf8");
    return;
  }

  throw new Error("Durable Google Calendar storage is not configured");
}

async function readDocument(): Promise<GoogleCalendarStoreDocument> {
  const secret = storeSecret();
  if (!secret) throw new Error("Set MAWA_STATE_ENCRYPTION_SECRET to protect Google refresh tokens");
  const encrypted = await readEncrypted();
  if (!encrypted) return { version: 1, slots: {} };
  return JSON.parse(decryptString(encrypted, secret)) as GoogleCalendarStoreDocument;
}

async function writeDocument(document: GoogleCalendarStoreDocument): Promise<void> {
  const secret = storeSecret();
  if (!secret) throw new Error("Set MAWA_STATE_ENCRYPTION_SECRET to protect Google refresh tokens");
  const encrypted = encryptString(JSON.stringify(document), secret);
  await writeEncrypted(encrypted);
}

export async function getStoredGoogleCalendarAccount(
  slot: GoogleCalendarSlot,
): Promise<StoredGoogleCalendarAccount | null> {
  const document = await readDocument();
  return document.slots[slot] ?? null;
}

export async function listStoredGoogleCalendarAccounts(): Promise<
  Partial<Record<GoogleCalendarSlot, StoredGoogleCalendarAccount>>
> {
  return (await readDocument()).slots;
}

export async function saveStoredGoogleCalendarAccount(
  account: StoredGoogleCalendarAccount,
): Promise<void> {
  const document = await readDocument();
  document.slots[account.slot] = account;
  await writeDocument(document);
}

export async function removeStoredGoogleCalendarAccount(slot: GoogleCalendarSlot): Promise<void> {
  const document = await readDocument();
  delete document.slots[slot];
  await writeDocument(document);
}

