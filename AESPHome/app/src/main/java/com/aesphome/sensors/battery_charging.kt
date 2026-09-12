package com.aesphome

import android.content.BroadcastReceiver
import android.os.BatteryManager
import android.os.PowerManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log


/*

  Battery Charging

*/


object BatteryCharging : EventSensor {
  override val id                     = "battery_charging"
  override val label                  = "Battery Charging"
  override val description            = ""
  override val key: Int               = id.hashCode()
  override val enabledByDefaultApp    = false
  override val enabledByDefaultHa     = true
  override val entityCategory         = EntityCategory.DIAGNOSTIC
  override val icon                   = "mdi:battery-charging"
  override fun kind(context: Context) = SensorKind.Binary()

  private val receiver = object : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
      val isOn = intent.action == Intent.ACTION_POWER_CONNECTED
      Log.i(TAG, if (isOn) "Battery Charging" else "Battery Discharging")
      AESPHomeService.instance?.reportSensor(this@BatteryCharging, isOn)
    }
  }

  override fun start(context: Context) {
    val filter = IntentFilter().apply {
      addAction(Intent.ACTION_POWER_CONNECTED)
      addAction(Intent.ACTION_POWER_DISCONNECTED)
    }
    context.registerReceiver(receiver, filter)

    val powerManager   = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    AESPHomeService.instance?.reportSensor(this, batteryManager.isCharging)
  }

  override fun stop(context: Context) {
    try{ context.unregisterReceiver(receiver) }
    catch (e: IllegalArgumentException) {}
  }
}
