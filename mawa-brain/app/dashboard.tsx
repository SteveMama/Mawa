"use client";

import { useCallback, useEffect, useState } from "react";
import type { SceneManifest } from "../lib/manifest";

const DEFAULT_LOCATION = { latitude: 42.3601, longitude: -71.0589 };

export function Dashboard() {
  const [manifest, setManifest] = useState<SceneManifest | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

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

  useEffect(() => {
    load();
  }, [load]);

  function useMyLocation() {
    if (!navigator.geolocation) return;
    navigator.geolocation.getCurrentPosition(
      ({ coords }) => load(coords),
      () => setError("Location permission was not granted; showing the default location."),
      { maximumAge: 10 * 60_000, timeout: 8_000 },
    );
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
          <div className="phone-preview">
            {manifest?.scene.panels.map((panel) => (
              <div className="preview-panel" key={panel.id}>
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
