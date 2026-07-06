"use client";

import { useCallback, useEffect, useState } from "react";
import type { SceneManifest } from "../lib/manifest";

const DEFAULT_LOCATION = { latitude: 42.3601, longitude: -71.0589 };

interface CompanionStatusResponse {
  ready: boolean;
  missing: string[];
  model: string;
  adminAuthorized: boolean;
}

export function Dashboard() {
  const [manifest, setManifest] = useState<SceneManifest | null>(null);
  const [companionStatus, setCompanionStatus] = useState<CompanionStatusResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [adminToken, setAdminToken] = useState("");
  const [companionInput, setCompanionInput] = useState("How are you feeling on the wall tonight?");
  const [companionReply, setCompanionReply] = useState<string | null>(null);

  const authHeaders = useCallback((): HeadersInit => {
    return adminToken.trim() ? { Authorization: `Bearer ${adminToken.trim()}` } : {};
  }, [adminToken]);

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

  const loadCompanionStatus = useCallback(async () => {
    const response = await fetch("/api/companion/status", {
      cache: "no-store",
      headers: authHeaders(),
    });
    if (!response.ok) throw new Error(`Companion status returned ${response.status}`);
    setCompanionStatus((await response.json()) as CompanionStatusResponse);
  }, [authHeaders]);

  useEffect(() => {
    load();
    loadCompanionStatus().catch((cause) => {
      setError(cause instanceof Error ? cause.message : "Could not load companion status");
    });
  }, [load, loadCompanionStatus]);

  function useMyLocation() {
    if (!navigator.geolocation) return;
    navigator.geolocation.getCurrentPosition(
      ({ coords }) => load(coords),
      () => setError("Location permission was not granted; showing the default location."),
      { maximumAge: 10 * 60_000, timeout: 8_000 },
    );
  }

  async function askCompanion() {
    setBusy(true);
    try {
      const response = await fetch("/api/companion/chat", {
        method: "POST",
        headers: { "Content-Type": "application/json", ...authHeaders() },
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
      setBusy(false);
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
          {loading ? "Composing scene…" : error ?? "Manifest online"}
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
          {manifest?.scene.animation ? (
            <p>
              {manifest.scene.animation.palette} palette · {manifest.scene.animation.gazeMode} gaze
              {" · "}energy {manifest.scene.animation.energy.toFixed(2)}
            </p>
          ) : null}
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
            <p className="eyebrow">CALENDARS (iCal)</p>
            <p className="auth-copy">
              Calendars connect via each calendar&apos;s secret iCal URL — set{" "}
              <code>MAWA_CALENDAR_PERSONAL_ICS</code> and <code>MAWA_CALENDAR_WORK_ICS</code> in the
              environment. No OAuth, no stored tokens. Event details reach the paired wall device
              only; this public preview shows connection status alone.
            </p>
          </div>

          <div className="companion-card">
            <p className="eyebrow">GROQ COMPANION</p>
            <p className="auth-copy">
              Same prompt as the wall-facing ambient thought engine. Paste the dashboard admin token
              to unlock the tester (not needed on localhost).
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
              <input
                type="password"
                value={adminToken}
                onChange={(event) => setAdminToken(event.target.value)}
                onBlur={() => loadCompanionStatus().catch(() => {})}
                placeholder="Dashboard admin token (blank on localhost)"
              />
              <textarea
                value={companionInput}
                onChange={(event) => setCompanionInput(event.target.value)}
                placeholder="Say something to Mawa..."
              />
              <button
                onClick={askCompanion}
                disabled={busy || !companionInput.trim() || !companionStatus?.ready}
              >
                {busy ? "Listening…" : "Ask Mawa"}
              </button>
            </div>
            {companionStatus && !companionStatus.adminAuthorized ? (
              <p className="auth-warning">
                Enter the admin token above to unlock the companion tester in this browser.
              </p>
            ) : null}
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
