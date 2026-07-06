import type { ConnectorOutput, ManifestConnector, ManifestContext } from "../manifest";
import { generateAmbientThought, groqStatus } from "../companion/groq";

export const companionConnector: ManifestConnector = {
  id: "companion",
  name: "Mawa Personality",
  async build(context: ManifestContext): Promise<ConnectorOutput> {
    const status = groqStatus();
    if (!status.ready) {
      return {
        state: {
          id: this.id,
          name: this.name,
          status: "planned",
          message: `Add ${status.missing.join(", ")} to wake the companion voice`,
        },
        panels: [],
      };
    }

    try {
      const thought = await generateAmbientThought(context.now);
      return {
        state: {
          id: this.id,
          name: this.name,
          status: "ready",
          message: `Ambient thought stream active on ${status.model}`,
          lastUpdatedAt: context.now.toISOString(),
        },
        suggestedMood: thought.mood,
        suggestedAnimation: thought.animation,
        panels: [
          {
            id: "mawa-thought",
            connector: this.id,
            slot: "top-left",
            eyebrow: "MAWA",
            title: thought.title,
            detail: thought.detail,
            accent: "#B6D9F2",
            expiresAt: new Date(context.now.getTime() + 15 * 60_000).toISOString(),
          },
        ],
      };
    } catch (error) {
      return {
        state: {
          id: this.id,
          name: this.name,
          status: "degraded",
          message: error instanceof Error ? error.message : "Companion unavailable",
        },
        panels: [],
      };
    }
  },
};
