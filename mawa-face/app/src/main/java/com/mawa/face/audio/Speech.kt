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

    fun say(text: String) {
        if (ready) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "mawa-utterance")
    }

    fun shutdown() {
        tts.shutdown()
    }
}
