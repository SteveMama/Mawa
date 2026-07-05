"use client";

import { useCallback, useEffect, useState } from "react";
import type { SceneManifest } from "../lib/manifest";

const DEFAULT_LOCATION = { latitude: 42.3601, longitude: -71.0589 };

interface GoogleSlotStatus {
  slot: "personal" | "work";
  name: string;
  connected: boolean;
  email?: string;
  connectedAt?: string;
  message: string;
}

interface GoogleStatusResponse {
  ready: boolean;
  missing: string[];
  storageMode: "blob" | "file" | "unavailable";
  adminRequired: boolean;
  adminConfigured: boolean;
  adminAuthorized: boolean;
  slots: GoogleSlotStatus[];
}

interface CompanionStatusResponse {
  ready: boolean;
  missing: string[];
  model: string;
  adminRequired: boolean;
  adminConfigured: boolean;
  adminAuthorized: boolean;
}

export function Dashboard() {
  const [manifest, setManifest] = useState<SceneManifest | null>(null);
  const [googleStatus, setGoogleStatus] = useState<GoogleStatusResponse | null>(null);
  const [companionStatus, setCompanionStatus] = useState<CompanionStatusResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [authBusy, setAuthBusy] = useState<string | null>(null);
  const [adminToken, setAdminToken] = useState("");
  const [flash, setFlash] = useState<string | null>(null);
  const [companionInput, setCompanionInput] = useState("How are you feeling on the wall tonight?");
  const [companionReply, setCompanionReply] = useState<string | null>(null);

  const load = useCallback(async (location = DEFAULT_LOCATION) => {
    setLoading(true);
    setError(null);
    try {
      const query = new URLSearchParams({
        lat: String(location.latitude),
        lon: String(location.longitude),
        device: "dashboard-preview",
      });
      const response = await fetch(`/api/manifest?${query}`, { cache: "no-store" });
      if (!response.ok) throw new Error(`Manifest returned ${response.status}`);
      setManifest((await response.json()) as SceneManifest);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Could not load manifest");
    } finally {
      setLoading(false);
    }
  }, []);

  const loadGoogleStatus = useCallback(async () => {
    const response = await fetch("/api/google/status", { cache: "no-store" });
    if (!response.ok) throw new Error(`Google status returned ${response.status}`);
    setGoogleStatus((await response.json()) as GoogleStatusResponse);
  }, []);

  const loadCompanionStatus = useCallback(async () => {
    const response = await fetch("/api/companion/status", { cache: "no-store" });
    if (!response.ok) throw new Error(`Companion status returned ${response.status}`);
    setCompanionStatus((await response.json()) as CompanionStatusResponse);
  }, []);

  useEffect(() => {
    load();
    loadGoogleStatus().catch((cause) => {
      setError(cause instanceof Error ? cause.message : "Could not load Google auth status");
    });
    loadCompanionStatus().catch((cause) => {
      setError(cause instanceof Error ? cause.message : "Could not load companion status");
    });
  }, [load, loadCompanionStatus, loadGoogleStatus]);

  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    if (params.get("google") === "connected") {
      setFlash(`${params.get("slot") === "work" ? "Work" : "Personal"} calendar connected`);
      return;
    }
    if (params.get("google_error")) {
      setFlash(`Google auth error: ${params.get("google_error")}`);
    }
  }, []);

  function useMyLocation() {
    if (!navigator.geolocation) return;
    navigator.geolocation.getCurrentPosition(
      ({ coords }) => load(coords),
      () => setError("Location permission was not granted; showing the default location."),
      { maximumAge: 10 * 60_000, timeout: 8_000 },
    );
  }

  async function disconnect(slot: "personal" | "work") {
    setAuthBusy(slot);
    try {
      const response = await fetch(`/api/google/disconnect?slot=${slot}`, {
        method: "POST",
      });
      if (!response.ok) throw new Error(`Disconnect returned ${response.status}`);
      await Promise.all([load(), loadGoogleStatus(), loadCompanionStatus()]);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Could not disconnect Google Calendar");
    } finally {
      setAuthBusy(null);
    }
  }

  async function unlockAdmin() {
    setAuthBusy("admin");
    try {
      const response = await fetch("/api/google/unlock", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ token: adminToken }),
      });
      if (!response.ok) throw new Error("Admin token was rejected");
      setAdminToken("");
      await Promise.all([load(), loadGoogleStatus(), loadCompanionStatus()]);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Could not unlock calendar admin");
    } finally {
      setAuthBusy(null);
    }
  }

  async function askCompanion() {
    setAuthBusy("companion");
    try {
      const response = await fetch("/api/companion/chat", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ message: companionInput }),
      });
      const payload = (await response.json()) as { reply?: string; error?: string };
      if (!response.ok || !payload.reply) {
        throw new Error(payload.error || `Companion returned ${response.status}`);
      }
      setCompanionReply(payload.reply);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Could not reach the companion");
    } finally {
      setAuthBusy(null);
    }
  }

  return (
    <main>
      <header className="hero">
        <div>
          <p className="kicker">MAWA / CLOUD BRAIN</p>
          <h1>Alive on the wall.<br />Composed in the cloud.</h1>
          <p className="lede">
            Vercel assembles a small scene manifest. The phone renders it, while vision remains
            entirely on-device.
          </p>
        </div>
        <div className="face" aria-label="Mawa face preview">
          <span><i /></span><span><i /></span>
        </div>
      </header>

      <section className="toolbar">
        <div>
          <span className={`dot ${error ? "bad" : ""}`} />
          {loading ? "Composing scene…" : error ?? flash ?? "Manifest online"}
        </div>
        <div className="actions">
          <button onClick={useMyLocation}>Use my location</button>
          <button onClick={() => load()}>Refresh</button>
        </div>
      </section>

      <section className="grid">
        <article className="card scene-card">
          <p className="eyebrow">CURRENT SCENE</p>
          <h2>{manifest?.scene.weather?.condition ?? "Waiting"}</h2>
          <p>
            {manifest?.scene.weather
              ? `${Math.round(manifest.scene.weather.temperatureC)}°C · mood ${manifest.scene.mood}`
              : "The first connector is waking up."}
          </p>
          <div className="phone-preview">
            {manifest?.scene.panels.map((panel) => (
              <div className={`preview-panel ${panel.slot}`} key={panel.id}>
                <small>{panel.eyebrow}</small>
                <strong>{panel.title}</strong>
                <span>{panel.detail}</span>
              </div>
            ))}
            <div className="mini-eyes"><b /><b /></div>
          </div>
        </article>

        <article className="card connectors-card">
          <p className="eyebrow">CONNECTORS</p>
          <div className="connector-list">
            {manifest?.connectors.map((connector) => (
              <div className="connector" key={connector.id}>
                <span className={`dot ${connector.status}`} />
                <div><strong>{connector.name}</strong><small>{connector.message}</small></div>
                <em>{connector.status}</em>
              </div>
            ))}
          </div>
          <div className="calendar-auth">
            <p className="eyebrow">GOOGLE CALENDAR</p>
            <p className="auth-copy">
              Connect two separate Google accounts. Each slot syncs that account&apos;s primary
              calendar and stays private unless this dashboard or the wall device is authorized.
            </p>
            <div className="auth-list">
              {googleStatus?.adminRequired && !googleStatus.adminAuthorized ? (
                <div className="auth-slot auth-unlock">
                  <div>
                    <strong>Unlock Calendar Admin</strong>
                    <small>
                      Enter `MAWA_DASHBOARD_ADMIN_TOKEN` to manage private connectors and use the
                      Groq companion tester.
                    </small>
                  </div>
                  <div className="unlock-controls">
                    <input
                      type="password"
                      value={adminToken}
                      onChange={(event) => setAdminToken(event.target.value)}
                      placeholder="Admin token"
                    />
                    <button onClick={unlockAdmin} disabled={authBusy === "admin" || !adminToken.trim()}>
                      {authBusy === "admin" ? "Unlocking…" : "Unlock"}
                    </button>
                  </div>
                </div>
              ) : null}
              {googleStatus?.slots.map((slot) => (
                <div className="auth-slot" key={slot.slot}>
                  <div>
                    <strong>{slot.name}</strong>
                    <small>{slot.message}</small>
                  </div>
                  <div className="auth-actions">
                    {slot.connected ? (
                      <button
                        onClick={() => disconnect(slot.slot)}
                        disabled={authBusy === slot.slot || !googleStatus.adminAuthorized}
                      >
                        {authBusy === slot.slot ? "Disconnecting…" : "Disconnect"}
                      </button>
                    ) : (
                      <button
                        onClick={() => window.location.assign(`/api/google/connect?slot=${slot.slot}`)}
                        disabled={!googleStatus.ready || !googleStatus.adminAuthorized}
                      >
                        Connect Google Account
                      </button>
                    )}
                  </div>
                </div>
              ))}
            </div>
            {!googleStatus?.ready ? (
              <p className="auth-warning">
                Missing: {googleStatus?.missing.join(", ") || "Google OAuth configuration"}
                {" · "}Storage: {googleStatus?.storageMode ?? "unknown"}
              </p>
            ) : null}
          </div>

          <div className="companion-card">
            <p className="eyebrow">GROQ COMPANION</p>
            <p className="auth-copy">
              Same prompt as the wall-facing ambient thought engine. Use this to tune Mawa&apos;s
              personality before voice is fully wired on the phone.
            </p>
            <div className="auth-slot">
              <div>
                <strong>{companionStatus?.ready ? "Companion ready" : "Companion sleeping"}</strong>
                <small>
                  {companionStatus?.ready
                    ? `Model: ${companionStatus.model}`
                    : `Missing: ${companionStatus?.missing.join(", ") || "Groq configuration"}`}
                </small>
              </div>
            </div>
            <div className="companion-compose">
              <textarea
                value={companionInput}
                onChange={(event) => setCompanionInput(event.target.value)}
                placeholder="Say something to Mawa..."
              />
              <button
                onClick={askCompanion}
                disabled={
                  authBusy === "companion" ||
                  !companionInput.trim() ||
                  !companionStatus?.ready ||
                  !companionStatus.adminAuthorized
                }
              >
                {authBusy === "companion" ? "Listening…" : "Ask Mawa"}
              </button>
            </div>
            {companionReply ? <p className="companion-reply">{companionReply}</p> : null}
          </div>
        </article>
      </section>

      <footer>
        <span>Schema v{manifest?.schemaVersion ?? 1}</span>
        <span>Poll every {manifest?.pollAfterSeconds ?? 300}s</span>
        <span>Camera frames never leave the phone</span>
      </footer>
    </main>
  );
}
