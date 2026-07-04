import type {
  ConnectorOutput,
  ManifestConnector,
  ManifestContext,
  WeatherScene,
} from "../manifest";

interface OpenMeteoResponse {
  current?: {
    temperature_2m?: number;
    weather_code?: number;
    is_day?: number;
  };
}

function conditionFor(code: number): WeatherScene["condition"] {
  if (code <= 1) return "clear";
  if (code <= 3) return "clouds";
  if (code === 45 || code === 48) return "fog";
  if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) return "rain";
  if ((code >= 71 && code <= 77) || (code >= 85 && code <= 86)) return "snow";
  if (code >= 95 && code <= 99) return "thunder";
  return "clouds";
}

function weatherCopy(weather: WeatherScene): { icon: string; title: string; detail: string } {
  const temperature = `${Math.round(weather.temperatureC)}°C`;
  switch (weather.condition) {
    case "clear":
      return { icon: weather.isDay ? "☀" : "☾", title: temperature, detail: "Clear" };
    case "clouds":
      return { icon: "☁", title: temperature, detail: "Cloudy" };
    case "rain":
      return { icon: "☂", title: temperature, detail: "Rain" };
    case "snow":
      return { icon: "✦", title: temperature, detail: "Snow" };
    case "fog":
      return { icon: "≋", title: temperature, detail: "Fog" };
    case "thunder":
      return { icon: "ϟ", title: temperature, detail: "Thunder" };
  }
}

export const weatherConnector: ManifestConnector = {
  id: "weather",
  name: "Weather",
  async build(context: ManifestContext): Promise<ConnectorOutput> {
    const endpoint = new URL("https://api.open-meteo.com/v1/forecast");
    endpoint.searchParams.set("latitude", context.latitude.toFixed(4));
    endpoint.searchParams.set("longitude", context.longitude.toFixed(4));
    endpoint.searchParams.set("current", "temperature_2m,weather_code,is_day");
    endpoint.searchParams.set("timezone", "auto");

    try {
      const response = await fetch(endpoint, {
        headers: { "user-agent": "Mawa/1.0 (+https://github.com/SteveMama/Mawa)" },
        next: { revalidate: 300 },
        signal: AbortSignal.timeout(4_000),
      });
      if (!response.ok) throw new Error(`Open-Meteo returned ${response.status}`);

      const body = (await response.json()) as OpenMeteoResponse;
      const current = body.current;
      if (
        current?.temperature_2m === undefined ||
        current.weather_code === undefined ||
        current.is_day === undefined
      ) {
        throw new Error("Open-Meteo response is missing current conditions");
      }

      const weather: WeatherScene = {
        condition: conditionFor(current.weather_code),
        temperatureC: current.temperature_2m,
        isDay: current.is_day === 1,
      };
      const copy = weatherCopy(weather);
      const now = context.now.toISOString();
      const expiresAt = new Date(context.now.getTime() + 15 * 60_000).toISOString();

      return {
        state: {
          id: this.id,
          name: this.name,
          status: "ready",
          message: "Live current conditions from Open-Meteo",
          lastUpdatedAt: now,
        },
        weather,
        panels: [
          {
            id: "weather-now",
            connector: this.id,
            slot: "top-right",
            eyebrow: "OUTSIDE",
            title: `${copy.icon}  ${copy.title}`,
            detail: copy.detail,
            accent: "#8FA6C0",
            expiresAt,
          },
        ],
        suggestedMood: weather.condition === "thunder" ? "suspicious" : undefined,
      };
    } catch (error) {
      const message = error instanceof Error ? error.message : "Unknown weather error";
      return {
        state: {
          id: this.id,
          name: this.name,
          status: "degraded",
          message,
        },
        panels: [],
      };
    }
  },
};
