package com.mawa.face.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * Thin wrapper over Android TTS. Phase 1 uses it for calibration feedback;
 * Phase 2 routes brain `say` commands through here.
 */
class Speech(context: Context) {

    private var ready = false
    private val tts: TextToSpeech = TextToSpeech(context) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts.language = Locale.US
            tts.setSpeechRate(0.95f)
        } else {
            Log.w("Speech", "TTS init failed: $status")
        }
    }

    fun say(text: String, style: String = "measured") {
        if (!ready) return
        val (rate, pitch) = when (style.lowercase()) {
            "dry" -> 0.90f to 0.90f
            "warm" -> 0.98f to 1.00f
            "playful" -> 1.02f to 1.06f
            "protective" -> 0.88f to 0.86f
            "hushed" -> 0.84f to 0.92f
            else -> 0.94f to 0.95f
        }
        tts.setSpeechRate(rate)
        tts.setPitch(pitch)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "mawa-utterance")
    }

    fun shutdown() {
        tts.shutdown()
    }
}
