"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import type { LiveDeviceState } from "../lib/live-state";
import type { SceneManifest } from "../lib/manifest";
import type { RoomMomentStore } from "../lib/room-moments";

const DEFAULT_LOCATION = { latitude: 42.3601, longitude: -71.0589 };

interface CompanionStatusResponse {
  ready: boolean;
  missing: string[];
  model: string;
  ambientModel: string;
  visionModel: string;
  adminAuthorized: boolean;
}

interface LiveStateResponse {
  live: LiveDeviceState | null;
  adminAuthorized: boolean;
}

interface RoomMomentsResponse {
  moments: RoomMomentStore | null;
  adminAuthorized: boolean;
}

export function Dashboard() {
  const [manifest, setManifest] = useState<SceneManifest | null>(null);
  const [companionStatus, setCompanionStatus] = useState<CompanionStatusResponse | null>(null);
  const [liveState, setLiveState] = useState<LiveDeviceState | null>(null);
  const [roomMoments, setRoomMoments] = useState<RoomMomentStore | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [liveError, setLiveError] = useState<string | null>(null);
  const [momentError, setMomentError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [adminToken, setAdminToken] = useState("");
  const [companionInput, setCompanionInput] = useState("How are you feeling on the wall tonight?");
  const [companionReply, setCompanionReply] = useState<string | null>(null);

  const dashboardBuild = useMemo(() => Date.now().toString(36), []);

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
      query.set("v", dashboardBuild);
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
      const response = await fetch(`/api/companion/status?v=${dashboardBuild}`, {
        cache: "no-store",
        headers: authHeaders(),
      });
    if (!response.ok) throw new Error(`Companion status returned ${response.status}`);
    setCompanionStatus((await response.json()) as CompanionStatusResponse);
  }, [authHeaders]);

  const loadLiveState = useCallback(async () => {
    try {
      const response = await fetch(`/api/device/telemetry?v=${dashboardBuild}`, {
        cache: "no-store",
        headers: authHeaders(),
      });
      const payload = (await response.json()) as { error?: string } & Partial<LiveStateResponse>;
      if (!response.ok) throw new Error(payload.error || `Live state returned ${response.status}`);
      setLiveState(payload.live ?? null);
      setLiveError(null);
    } catch (cause) {
      setLiveState(null);
      setLiveError(cause instanceof Error ? cause.message : "Could not load live device state");
    }
  }, [authHeaders]);

  const loadRoomMoments = useCallback(async () => {
    try {
      const response = await fetch(`/api/device/moment?deviceId=oneplus-wall&v=${dashboardBuild}`, {
        cache: "no-store",
        headers: authHeaders(),
      });
      const payload = (await response.json()) as { error?: string } & Partial<RoomMomentsResponse>;
      if (!response.ok) throw new Error(payload.error || `Room moments returned ${response.status}`);
      setRoomMoments(payload.moments ?? null);
      setMomentError(null);
    } catch (cause) {
      setRoomMoments(null);
      setMomentError(cause instanceof Error ? cause.message : "Could not load room moments");
    }
  }, [authHeaders, dashboardBuild]);

  useEffect(() => {
    try {
      const stored = window.localStorage.getItem("mawa_dashboard_admin_token");
      if (stored) setAdminToken(stored);
      const params = new URLSearchParams(window.location.search);
      const tokenFromUrl = params.get("adminToken")?.trim();
      if (tokenFromUrl) {
        setAdminToken(tokenFromUrl);
        window.localStorage.setItem("mawa_dashboard_admin_token", tokenFromUrl);
      }
    } catch {}
  }, []);

  useEffect(() => {
    load();
    loadCompanionStatus().catch((cause) => {
      setError(cause instanceof Error ? cause.message : "Could not load companion status");
    });
    loadLiveState().catch(() => {});
    loadRoomMoments().catch(() => {});
    const interval = window.setInterval(() => {
      loadLiveState().catch(() => {});
      loadRoomMoments().catch(() => {});
    }, 5_000);
    return () => window.clearInterval(interval);
  }, [load, loadCompanionStatus, loadLiveState, loadRoomMoments]);

  useEffect(() => {
    try {
      if (adminToken.trim()) {
        window.localStorage.setItem("mawa_dashboard_admin_token", adminToken.trim());
      } else {
        window.localStorage.removeItem("mawa_dashboard_admin_token");
      }
    } catch {}
  }, [adminToken]);

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

  const liveAgeMs =
    liveState?.capturedAt ? Date.now() - new Date(liveState.capturedAt).getTime() : Number.POSITIVE_INFINITY;
  const liveOnline = Number.isFinite(liveAgeMs) && liveAgeMs < 25_000;
  const liveSeen =
    !liveState?.capturedAt ? "No device telemetry yet" :
    liveAgeMs < 5_000 ? "just now" :
    liveAgeMs < 60_000 ? `${Math.round(liveAgeMs / 1000)}s ago` :
    `${Math.round(liveAgeMs / 60_000)}m ago`;
  const latestMoment = roomMoments?.recent?.[0];

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

      <section className="live-board">
        <article className="card live-card">
          <div className="live-head">
            <div>
              <p className="eyebrow">LIVE WALL STATE</p>
              <h2>{liveState?.feeling.mood ?? "Waiting"}</h2>
              <p>
                {liveState
                  ? `${liveState.feeling.summary} · ${liveState.feeling.attention}`
                  : liveError ?? "Waiting for the wall phone to report in."}
              </p>
            </div>
            <div className="live-meta">
              <span className={`dot ${liveOnline ? "" : "planned"}`} />
              <strong>{liveOnline ? "Live" : "Idle"}</strong>
              <small>{liveSeen}</small>
            </div>
          </div>

          {liveState?.thought ? (
            <div className="live-thought" style={{ borderColor: liveState.thought.accent }}>
              <small>{liveState.thought.eyebrow}</small>
              <strong>{liveState.thought.title}</strong>
              <span>{liveState.thought.detail}</span>
            </div>
          ) : null}

          <div className="live-stats">
            <div>
              <small>Presence</small>
              <strong>
                {liveState
                  ? `${liveState.presence.faceCount} face${liveState.presence.faceCount === 1 ? "" : "s"}`
                  : "—"}
              </strong>
              <span>
                {liveState?.presence.personLabel
                  ? liveState.presence.personLabel
                  : liveState?.presence.recognized ?? "—"}
              </span>
            </div>
            <div>
              <small>Music</small>
              <strong>{liveState ? `${Math.round(liveState.music.groove * 100)}% groove` : "—"}</strong>
              <span>{liveState?.music.stance ?? liveState?.music.tasteProfile ?? "quiet"}</span>
            </div>
            <div>
              <small>Energy</small>
              <strong>{liveState ? `${Math.round(liveState.feeling.energy * 100)}%` : "—"}</strong>
              <span>{liveState ? `${Math.round(liveState.feeling.expressiveness * 100)}% expressive` : "—"}</span>
            </div>
            <div>
              <small>Taste memory</small>
              <strong>{liveState?.music.tasteProfile ?? "not learned yet"}</strong>
              <span>
                {liveState
                  ? `${liveState.music.sessionCount} sessions · affinity ${Math.round(liveState.music.affinity * 100)}%`
                  : "—"}
              </span>
            </div>
          </div>

          {liveState ? (
            <div className="live-debug">
              <div className="live-debug-line"><small>Camera</small><span>{liveState.status.camera}</span></div>
              <div className="live-debug-line"><small>Brain</small><span>{liveState.status.brain}</span></div>
              <div className="live-debug-line"><small>Beat</small><span>{liveState.status.beat}</span></div>
              <div className="live-debug-line"><small>Scene</small><span>{liveState.status.scene}</span></div>
              <div className="live-debug-line"><small>Face</small><span>{liveState.status.face}</span></div>
            </div>
          ) : null}
        </article>

        <article className="card moment-card">
          <div className="live-head">
            <div>
              <p className="eyebrow">LATEST ROOM MOMENT</p>
              <h2>{latestMoment?.insight.title ?? "Waiting"}</h2>
              <p>{latestMoment?.insight.summary ?? momentError ?? "Waiting for a scene change worth remembering."}</p>
            </div>
            <div className="live-meta">
              <span className={`dot ${latestMoment ? "" : "planned"}`} />
              <strong>{latestMoment ? latestMoment.insight.activity : "Idle"}</strong>
              <small>
                {latestMoment ? `${Math.round(latestMoment.insight.confidence * 100)}% confidence` : "No moment yet"}
              </small>
            </div>
          </div>

          {latestMoment?.imageDataUrl ? (
            <img className="moment-preview" src={latestMoment.imageDataUrl} alt={latestMoment.insight.title} />
          ) : null}

          <div className="live-stats">
            <div>
              <small>Labels</small>
              <strong>{latestMoment?.labels.join(", ") || "—"}</strong>
              <span>{latestMoment ? `change ${Math.round(latestMoment.changeScore * 100)}%` : "—"}</span>
            </div>
            <div>
              <small>Presence</small>
              <strong>{latestMoment ? `${latestMoment.faceCount} face${latestMoment.faceCount === 1 ? "" : "s"}` : "—"}</strong>
              <span>{latestMoment?.personLabel ?? latestMoment?.recognized ?? "—"}</span>
            </div>
            <div>
              <small>Audio context</small>
              <strong>{latestMoment?.musicActive ? "music nearby" : "quiet"}</strong>
              <span>{latestMoment ? `${Math.round(latestMoment.groove * 100)}% groove` : "—"}</span>
            </div>
          </div>
        </article>
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
              Same prompt as the wall-facing ambient thought engine. If you set a dashboard admin
              token, paste it here. If you do not, the tester is open by default.
            </p>
            <div className="auth-slot">
              <div>
                <strong>{companionStatus?.ready ? "Companion ready" : "Companion sleeping"}</strong>
                <small>
                  {companionStatus?.ready
                    ? companionStatus.ambientModel === companionStatus.model
                      ? `Chat: ${companionStatus.model} · Vision: ${companionStatus.visionModel}`
                      : `Chat: ${companionStatus.model} · Ambient: ${companionStatus.ambientModel} · Vision: ${companionStatus.visionModel}`
                    : `Missing: ${companionStatus?.missing.join(", ") || "Groq configuration"}`}
                </small>
              </div>
            </div>
            <div className="companion-compose">
              <input
                type="password"
                value={adminToken}
                onChange={(event) => setAdminToken(event.target.value)}
                onBlur={() => {
                  loadCompanionStatus().catch(() => {});
                  loadLiveState().catch(() => {});
                  loadRoomMoments().catch(() => {});
                }}
                placeholder="Dashboard admin token (optional)"
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
            {liveError === "provide the dashboard admin token" ? (
              <p className="auth-warning">
                Enter the admin token above to unlock the live wall feed in this browser.
              </p>
            ) : null}
            {momentError === "provide the dashboard admin token" ? (
              <p className="auth-warning">
                Enter the admin token above to unlock scene memories in this browser.
              </p>
            ) : null}
            {companionReply ? <p className="companion-reply">{companionReply}</p> : null}
          </div>
        </article>
      </section>

      <footer>
        <span>Schema v{manifest?.schemaVersion ?? 1}</span>
        <span>Poll every {manifest?.pollAfterSeconds ?? 300}s</span>
        <span>Scene snapshots can leave the phone for cloud interpretation</span>
      </footer>
    </main>
  );
}
