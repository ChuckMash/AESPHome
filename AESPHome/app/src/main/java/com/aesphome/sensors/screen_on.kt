package com.aesphome

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.util.Log


/*

  Screen On/Off

*/


object ScreenStateSensor : EventSensor {
  override val id                     = "screen_on"
  override val label                  = "Screen On"
  override val description            = ""
  override val key: Int               = id.hashCode()
  override val enabledByDefaultApp    = false
  override val enabledByDefaultHa     = true
  override val entityCategory         = EntityCategory.NONE
  override val icon                   = "mdi:cellphone-screenshot"
  override fun kind(context: Context) = SensorKind.Binary()

  private val receiver = object : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
      val isOn = intent.action == Intent.ACTION_SCREEN_ON
      Log.i(TAG, if (isOn) "Screen turned on" else "Screen turned off")
      AESPHomeService.instance?.reportSensor(this@ScreenStateSensor, isOn)
    }
  }

  override fun start(context: Context) {
    val filter = IntentFilter().apply {
      addAction(Intent.ACTION_SCREEN_ON)
      addAction(Intent.ACTION_SCREEN_OFF)
    }
    context.registerReceiver(receiver, filter)
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    AESPHomeService.instance?.reportSensor(this, powerManager.isInteractive)
  }

  override fun stop(context: Context) {
    try {
      context.unregisterReceiver(receiver)
    } catch (e: IllegalArgumentException) {}
  }
}
