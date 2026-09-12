package com.aesphome

import android.content.BroadcastReceiver
import android.os.BatteryManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log


/*

  Battery Percent

    TODO:
      * Only report when percent changes

*/


object BatteryPercent : EventSensor {
  override val id                     = "battery_percent"
  override val label                  = "Battery Percent"
  override val description            = ""
  override val key: Int               = id.hashCode()
  override val enabledByDefaultApp    = false
  override val enabledByDefaultHa     = true
  override val entityCategory         = EntityCategory.DIAGNOSTIC
  override val icon                   = "mdi:battery"
  override fun kind(context: Context) = SensorKind.Numeric(unit = "%", deviceClass = "battery")

  private val receiver = object : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
      val level   = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
      val scale   = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)    
      val percent = if (level >= 0 && scale > 0) (level * 100f / scale) else -1f

      Log.i(TAG, "Battery Percent: $percent")

      AESPHomeService.instance?.reportSensor(this@BatteryPercent, percent)
    }
  }

  override fun start(context: Context) {
    val filter = IntentFilter().apply { addAction(Intent.ACTION_BATTERY_CHANGED) }
    context.registerReceiver(receiver, filter)
  }

  override fun stop(context: Context) {
    try { context.unregisterReceiver(receiver) }
    catch (e: IllegalArgumentException) {}
  }
}
