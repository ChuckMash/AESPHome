package com.aesphome

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.abs
import android.util.Log

/*

  Ambient light sensor

*/


object LightSensor : EventSensor, SensorEventListener{
  override val id                  = "light_sensor"
  override val label               = "LUX (Sensor)"
  override val description         = ""
  override val key: Int            = id.hashCode()
  override val enabledByDefaultApp = false
  override val enabledByDefaultHa  = true
  override val entityCategory      = EntityCategory.NONE
  override val icon                = "mdi:brightness-6"
  override fun kind(c: Context)    = SensorKind.Numeric(unit = "lx", deviceClass = "illuminance")

  private val changeThresholdSetting = Setting(
    id                 = "light_sensor_change_threshold",
    label              = "LUX Sensor Report Threshold",
    default            = 5f,
    min                = 1f,
    max                = 10000f,
    step               = 1f,
    deviceUi           = true,
    homeAssistant      = true,
    entityCategory     = EntityCategory.CONFIG,
    enabledByDefaultHa = false,
    icon               = "mdi:tune")

  private val minReportIntervalSetting = Setting(
    id                 = "light_sensor_min_report_interval",
    label              = "LUX Sensor Report Interval (s)",
    default            = 60f,
    min                = 1f,
    max                = 3600f,
    step               = 1f,
    deviceUi           = true,
    homeAssistant      = true,
    entityCategory     = EntityCategory.CONFIG,
    enabledByDefaultHa = false,
    icon               = "mdi:timer-outline")

  override val settings = listOf(changeThresholdSetting, minReportIntervalSetting)

  private var sensorManager: SensorManager? = null
  private var lsensor: Sensor? = null
  private var appContext: Context? = null

  private var lastReportedLux: Float? = null
  private var lastReportTime = 0L


  override fun start(context: Context) {
    appContext = context
    sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    lsensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)

    lsensor?.let { sensor ->
      sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }
  }

  override fun stop(context: Context) {
    sensorManager?.unregisterListener(this)
    sensorManager = null
    lsensor = null
  }

  override fun onSensorChanged(event: SensorEvent?) {
    val lux = event?.values?.firstOrNull() ?: return
    val now = SystemClock.elapsedRealtime()

    val changeThreshold = appContext?.let { getSetting(it, changeThresholdSetting) } ?: changeThresholdSetting.default
    val minReportIntervalMs = ((appContext?.let { getSetting(it, minReportIntervalSetting) }
      ?: minReportIntervalSetting.default) * 1000f).toLong()

    val changedEnough = lastReportedLux?.let { abs(lux - it) >= changeThreshold } ?: true
    val intervalElapsed = now - lastReportTime >= minReportIntervalMs
    if (!changedEnough && !intervalElapsed) return

    lastReportedLux = lux
    lastReportTime = now
    Log.i(TAG, "Light Sensor LUX: $lux")
    AESPHomeService.instance?.reportSensor(this, lux)
  }

  override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
