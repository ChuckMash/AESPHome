package com.aesphome

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log


/*

  Battery Temperature

*/


object BatteryTemperatureC : ReadSensor {
  override val id                     = "battery_temperature_c"
  override val label                  = "Battery Temperature"
  override val description            = ""
  override val key: Int               = id.hashCode()
  override val enabledByDefaultApp    = false
  override val enabledByDefaultHa     = true
  override val entityCategory         = EntityCategory.DIAGNOSTIC
  override val icon                   = "mdi:thermometer"
  override fun kind(context: Context) = SensorKind.Numeric(unit = "°C", deviceClass = "temperature")

  override fun read(context: Context): Float {
    val filter     = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
    val i: Intent? = context.registerReceiver(null, filter)
    val rawTemp    = i?.extras?.get(BatteryManager.EXTRA_TEMPERATURE) as? Int

    if (rawTemp == null) {
      Log.e(TAG, "Failed to retrieve one-shot battery hardware data.")
      return 0.0f
    }

    val tempC = rawTemp / 10.0f
    val tempF = (tempC * 1.8f) + 32f
    Log.i(TAG, "Battery Temperature: %.2fC / %.2fF".format(tempC, tempF))
    return tempC
  }
}
