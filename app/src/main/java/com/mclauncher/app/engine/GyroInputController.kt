package com.mclauncher.app.engine

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

class GyroInputController(context: Context) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private var sensitivity = 1f
    private var previousTimestamp = 0L

    fun start(sensitivity: Float) {
        this.sensitivity = sensitivity.coerceIn(0.1f, 4f)
        previousTimestamp = 0L
        sensor?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        manager.unregisterListener(this)
        previousTimestamp = 0L
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (previousTimestamp != 0L) {
            val seconds = (event.timestamp - previousTimestamp) / 1_000_000_000f
            val yaw = -event.values[1] * seconds * 85f
            val pitch = -event.values[0] * seconds * 85f
            if (kotlin.math.abs(yaw) > 0.001f || kotlin.math.abs(pitch) > 0.001f) {
                GameInputBridge.cursorDelta(yaw, pitch)
            }
        }
        previousTimestamp = event.timestamp
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
