package com.mawa.face.vision

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max

/**
 * Small on-device identity gallery.
 *
 * Algorithm:
 * - face crop -> embedding
 * - compare embedding against stored identities with cosine similarity
 * - if best match passes threshold, update that identity
 * - otherwise create a new unlabeled identity record
 *
 * This gives us the simple "database of people" behavior needed for the wall:
 * unknown people accumulate into stable records first, and naming can happen
 * later without retraining anything.
 */
class FaceGallery(
    private val prefs: SharedPreferences,
) {

    data class Identity(
        val id: String,
        var label: String?,
        var lastSeenAt: Long,
        var seenCount: Int,
        val samples: MutableList<FloatArray>,
    )

    data class Match(
        val identity: Identity,
        val similarity: Float,
        val created: Boolean,
    ) {
        val displayName: String
            get() = identity.label ?: identity.id
    }

    private val identities = mutableListOf<Identity>()

    init {
        load()
    }

    fun observe(embedding: FloatArray, now: Long): Match {
        val best = identities
            .map { identity -> identity to similarity(identity, embedding) }
            .maxByOrNull { it.second }

        if (best != null && best.second >= MATCH_THRESHOLD) {
            val identity = best.first
            identity.lastSeenAt = now
            identity.seenCount += 1
            maybeAddSample(identity, embedding)
            persist()
            return Match(identity, best.second, created = false)
        }

        val identity = Identity(
            id = "person-${java.lang.Long.toString(now, 36)}",
            label = null,
            lastSeenAt = now,
            seenCount = 1,
            samples = mutableListOf(embedding.copyOf()),
        )
        identities += identity
        persist()
        return Match(identity, 1f, created = true)
    }

    fun rename(identityId: String, label: String): Boolean {
        val identity = identities.firstOrNull { it.id == identityId } ?: return false
        identity.label = label.trim().take(MAX_LABEL_LENGTH).takeIf { it.isNotBlank() }
        persist()
        return true
    }

    fun identities(): List<Identity> = identities.map { identity ->
        identity.copy(samples = identity.samples.map { it.copyOf() }.toMutableList())
    }

    private fun similarity(identity: Identity, embedding: FloatArray): Float =
        identity.samples.fold(-1f) { best, sample ->
            max(best, FaceRecognizer.cosine(sample, embedding))
        }

    private fun maybeAddSample(identity: Identity, embedding: FloatArray) {
        val bestExisting = identity.samples.maxOfOrNull { FaceRecognizer.cosine(it, embedding) } ?: -1f
        if (bestExisting >= SAMPLE_REDUNDANCY_THRESHOLD && identity.samples.size >= 2) return
        if (identity.samples.size >= MAX_SAMPLES_PER_IDENTITY) {
            identity.samples.removeAt(0)
        }
        identity.samples += embedding.copyOf()
    }

    private fun load() {
        identities.clear()
        val raw = prefs.getString(PREF_KEY, null) ?: return
        runCatching {
            val root = JSONArray(raw)
            for (index in 0 until root.length()) {
                val item = root.optJSONObject(index) ?: continue
                val samplesJson = item.optJSONArray("samples") ?: continue
                val samples = mutableListOf<FloatArray>()
                for (sampleIndex in 0 until samplesJson.length()) {
                    val sampleJson = samplesJson.optJSONArray(sampleIndex) ?: continue
                    val sample = FloatArray(sampleJson.length())
                    for (i in 0 until sampleJson.length()) {
                        sample[i] = sampleJson.optDouble(i, 0.0).toFloat()
                    }
                    if (sample.isNotEmpty()) samples += sample
                }
                if (samples.isEmpty()) continue
                identities += Identity(
                    id = item.optString("id", "person-$index"),
                    label = item.optString("label").takeIf { it.isNotBlank() },
                    lastSeenAt = item.optLong("lastSeenAt", 0L),
                    seenCount = item.optInt("seenCount", 0).coerceAtLeast(0),
                    samples = samples,
                )
            }
        }.getOrElse {
            identities.clear()
        }
    }

    private fun persist() {
        val root = JSONArray()
        for (identity in identities) {
            val item = JSONObject()
            item.put("id", identity.id)
            item.put("label", identity.label)
            item.put("lastSeenAt", identity.lastSeenAt)
            item.put("seenCount", identity.seenCount)
            val samplesJson = JSONArray()
            for (sample in identity.samples) {
                val sampleJson = JSONArray()
                for (value in sample) sampleJson.put(value.toDouble())
                samplesJson.put(sampleJson)
            }
            item.put("samples", samplesJson)
            root.put(item)
        }
        prefs.edit().putString(PREF_KEY, root.toString()).apply()
    }

    companion object {
        private const val PREF_KEY = "face_gallery_v1"
        private const val MATCH_THRESHOLD = 0.62f
        private const val SAMPLE_REDUNDANCY_THRESHOLD = 0.74f
        private const val MAX_SAMPLES_PER_IDENTITY = 6
        private const val MAX_LABEL_LENGTH = 40
    }
}
