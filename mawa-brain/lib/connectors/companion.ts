import {
  readCompanionMemory,
  rememberRoomContext,
  rememberSpeech,
  saveCompanionMemory,
  shouldAllowSpeech,
  summarizeCompanionMemory,
} from "../companion-memory";
import type {
  CompanionDirective,
  ConnectorOutput,
  ManifestConnector,
  ManifestContext,
  RoomContext,
} from "../manifest";
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
      const deviceId = context.device ?? "unknown-device";
      let room: RoomContext | undefined = context.room;
      let memory = await readCompanionMemory(deviceId, context.now);

      if (room && context.deviceAuthorized) {
        memory = rememberRoomContext(memory, room, context.now);
        room = {
          ...room,
          memory: summarizeCompanionMemory(memory),
        };
      }

      const thought = await generateAmbientThought(room, context.now);
      const canSpeak = Boolean(
        context.deviceAuthorized && shouldAllowSpeech(memory, thought.companion.speech, context.now),
      );
      const companion: CompanionDirective = {
        ...thought.companion,
        memoryHint: room?.memory
          ? [room.memory.arrivalPattern, room.memory.musicPattern, room.memory.familiarPresence]
              .filter(Boolean)
              .join(" ")
              .slice(0, 120) || undefined
          : undefined,
        speech: {
          ...thought.companion.speech,
          shouldSpeak: canSpeak,
        },
      };
      if (context.deviceAuthorized) {
        memory = rememberSpeech(memory, companion.speech, context.now);
        await saveCompanionMemory(memory);
      }
      const modelLabel =
        status.ambientModel === status.model
          ? status.model
          : `${status.ambientModel} (ambient) · ${status.model} (chat)`;
      return {
        state: {
          id: this.id,
          name: this.name,
          status: "ready",
          message: `Ambient thought stream active on ${modelLabel}`,
          lastUpdatedAt: context.now.toISOString(),
        },
        suggestedMood: thought.mood,
        suggestedAnimation: thought.animation,
        suggestedCompanion: companion,
        panels: [
          {
            id: "mawa-thought",
            connector: this.id,
            slot: "top-left",
            eyebrow: thought.eyebrow,
            title: thought.title,
            detail: thought.detail,
            accent: thought.accent,
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
