package com.aesphome

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlin.math.sqrt


/*

  Device Movement

    TODO:
      *Review and cleanup

*/


object DeviceMovementSensor : EventSensor, SensorEventListener {
  override val id                     = "device_movement"
  override val label                  = "Device Movement"
  override val description            = ""
  override val key: Int               = id.hashCode()
  override val enabledByDefaultApp    = false
  override val enabledByDefaultHa     = true
  override val entityCategory         = EntityCategory.NONE
  override val icon                   = "mdi:vibrate"
  override fun kind(context: Context) = SensorKind.Binary()

  private val movementThreshold = Setting(
    id                 = "movement_sensitivity",
    label              = "Movement Sensitivity",
    default            = 2.0f,
    min                = 0.01f,
    max                = 5f,
    step               = 0.01f,
    deviceUi           = true,
    homeAssistant      = true,
    entityCategory     = EntityCategory.CONFIG,
    enabledByDefaultHa = false,
    icon               = "mdi:tune")

  private val resetTime = Setting(
    id                 = "movement_reset_time",
    label              = "Movement Reset Time (s)",
    default            = 5f,
    min                = 1f,
    max                = 3600f,
    step               = 1f,
    deviceUi           = true,
    homeAssistant      = true,
    entityCategory     = EntityCategory.CONFIG,
    enabledByDefaultHa = false,
    icon               = "mdi:timer-outline",
    onChanged          = { onResetTimeChanged(it) })

  override val settings = listOf(movementThreshold, resetTime)

  private var sensorManager: SensorManager? = null
  private var appContext: Context? = null
  private var isMoving: Boolean? = null
  @Volatile private var cooldownUntil: Long = 0L
  @Volatile private var cooldownThread: Thread? = null

  override fun start(context: Context) {
    appContext = context
    val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    sensorManager = manager
    manager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)?.let {
      manager.registerListener(this, it, 1_000_000)
    }
  }

  override fun stop(context: Context) {
    sensorManager?.unregisterListener(this)
    isMoving = null
  }

  override fun onSensorChanged(event: SensorEvent?) {
    if (event == null || event.sensor.type != Sensor.TYPE_LINEAR_ACCELERATION) return
    val x = event.values[0]
    val y = event.values[1]
    val z = event.values[2]
    val magnitude = sqrt((x * x + y * y + z * z).toDouble()).toFloat()
    val threshold = appContext?.let { getSetting(it, movementThreshold) } ?: movementThreshold.default
    if (magnitude < threshold) return // no movement right now — leave any running cooldown to expire on its own

    val cooldownSeconds = appContext?.let { getSetting(it, resetTime) } ?: resetTime.default
    cooldownUntil = System.currentTimeMillis() + (cooldownSeconds * 1000).toLong()
    if (isMoving == true) return // already reporting moving — cooldown above just got pushed back out
    isMoving = true
    Log.i(TAG, "Device movement started (magnitude=$magnitude)")
    AESPHomeService.instance?.reportSensor(this, true)
    val thread = Thread({ awaitCooldown() }, "AESPHomeMovementCooldown")
    cooldownThread = thread
    thread.start()
  }

  // Sleeps until cooldownUntil passes without being pushed back further by another
  // movement event (or by onResetTimeChanged below), then reports at-rest. Only one of
  // these ever runs at a time — guarded by the isMoving == true check above.
  private fun awaitCooldown() {
    while (true) {
      val remaining = cooldownUntil - System.currentTimeMillis()
      if (remaining <= 0) break
      try { Thread.sleep(remaining) } catch (_: InterruptedException) {} // woken early to re-check remaining
    }
    isMoving = false
    Log.i(TAG, "Device movement stopped")
    AESPHomeService.instance?.reportSensor(this, false)
  }

  // Reset time changed while a cooldown may be running — recompute the deadline using
  // the new duration, measured from now, and wake the waiting thread so it applies
  // immediately instead of at the old deadline.
  private fun onResetTimeChanged(context: Context) {
    if (isMoving != true) return
    cooldownUntil = System.currentTimeMillis() + (getSetting(context, resetTime) * 1000).toLong()
    cooldownThread?.interrupt()
  }

  override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
