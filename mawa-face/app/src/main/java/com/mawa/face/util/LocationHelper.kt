package com.mawa.face.util

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager

/**
 * Coarse location for the weather feature. Uses the platform LocationManager's
 * last-known fix (no Play Services dependency). Falls back to DEFAULT_* when
 * permission is denied or there's no fix yet — change those to your city if
 * you'd rather not grant location.
 */
object LocationHelper {
    // Boston, MA — change to your city, or grant location for an auto fix.
    const val DEFAULT_LAT = 42.3601
    const val DEFAULT_LON = -71.0589

    @SuppressLint("MissingPermission")
    fun lastKnown(context: Context): Pair<Double, Double> {
        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            var best: Location? = null
            for (p in lm.getProviders(true)) {
                val l = lm.getLastKnownLocation(p) ?: continue
                if (best == null || l.accuracy < best!!.accuracy) best = l
            }
            best?.let { Pair(it.latitude, it.longitude) } ?: Pair(DEFAULT_LAT, DEFAULT_LON)
        } catch (e: SecurityException) {
            Pair(DEFAULT_LAT, DEFAULT_LON)
        }
    }
}
