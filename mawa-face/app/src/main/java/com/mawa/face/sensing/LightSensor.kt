package com.mawa.face.sensing

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * Ambient light sensor. Emits lux readings so Mawa can sleep when the room
 * goes dark. No permission required.
 */
class LightSensor(
    context: Context,
    private val onLux: (Float) -> Unit,
) : SensorEventListener {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_LIGHT)

    val available: Boolean get() = sensor != null

    fun start() {
        sensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    fun stop() = sm.unregisterListener(this)

    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type == Sensor.TYPE_LIGHT) onLux(e.values[0])
    }

    override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
}
