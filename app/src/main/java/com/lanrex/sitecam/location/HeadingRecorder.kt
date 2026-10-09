package com.lanrex.sitecam.location

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Records which way the phone's back camera points (compass heading) while a
 * camera is in use, so each stamp can show a blue "facing" cone.
 *
 * Runs only during camera use (started by the Site Mode / Open Camera service),
 * at a low rate, to save battery.
 */
class HeadingRecorder(context: Context) : SensorEventListener {

    private data class Sample(val timeMillis: Long, val magneticDegrees: Float)

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val samples = ArrayDeque<Sample>()
    private val rotation = FloatArray(9)
    private var thread: HandlerThread? = null
    private var lastSampleAt = 0L

    /** Rough position, used only to turn magnetic north into true north. */
    @Volatile
    var lastKnownPosition: Pair<Double, Double>? = null

    val isAvailable: Boolean get() = sensor != null

    @Synchronized
    fun start() {
        if (thread != null || sensor == null) return
        val t = HandlerThread("SiteCamHeading").apply { start() }
        thread = t
        sensorManager.registerListener(this, sensor, SAMPLE_PERIOD_US, Handler(t.looper))
    }

    @Synchronized
    fun stop() {
        val t = thread ?: return
        sensorManager.unregisterListener(this)
        t.quitSafely()
        thread = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        val now = System.currentTimeMillis()
        if (now - lastSampleAt < 150) return
        // Estimated heading accuracy (radians) when the sensor reports it.
        if (event.values.size > 4 && event.values[4] > 0.6f) return
        SensorManager.getRotationMatrixFromVector(rotation, event.values)
        // The back camera looks along the phone's -Z axis; convert it to world East/North/Up.
        val east = -rotation[2]
        val north = -rotation[5]
        val horizontal = sqrt(east * east + north * north)
        if (horizontal < 0.35f) return // pointing almost straight up or down: no useful heading
        val degrees = ((Math.toDegrees(atan2(east.toDouble(), north.toDouble())) + 360.0) % 360.0).toFloat()
        lastSampleAt = now
        synchronized(samples) {
            samples.addLast(Sample(now, degrees))
            while (samples.isNotEmpty() && now - samples.first().timeMillis > KEEP_MS) samples.removeFirst()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /**
     * True-north heading of the camera at [timeMillis] (within 3 seconds), or
     * null when SiteCam wasn't recording then.
     */
    fun headingAt(timeMillis: Long): Float? {
        val nearest = synchronized(samples) { samples.minByOrNull { abs(it.timeMillis - timeMillis) } } ?: return null
        if (abs(nearest.timeMillis - timeMillis) > MATCH_WINDOW_MS) return null
        val position = lastKnownPosition ?: return nearest.magneticDegrees
        val declination = GeomagneticField(
            position.first.toFloat(),
            position.second.toFloat(),
            0f,
            timeMillis,
        ).declination
        return ((nearest.magneticDegrees + declination + 360f) % 360f)
    }

    private companion object {
        const val SAMPLE_PERIOD_US = 100_000
        const val KEEP_MS = 20 * 60_000L
        const val MATCH_WINDOW_MS = 3_000L
    }
}
