package com.aesphome

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log


/*

  Bluetooth Switch
    TODO
      review and rewrite

*/


object BluetoothSwitch : SwitchEntity {
  override val id                  = "bluetooth_switch"
  override val label               = "Bluetooth Enabled"
  override val description         = "May require Nearby Devices Permissions"
  override val key: Int            = id.hashCode()
  override val enabledByDefaultApp = false
  override val enabledByDefaultHa  = false
  override val icon                = "mdi:bluetooth"

  private fun adapter(context: Context): BluetoothAdapter? =
      (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

  // isEnabled() itself requires BLUETOOTH_CONNECT on Android 12+ and throws without it —
  // same permission BluetoothCommandService already asks for, but worth guarding here too
  // since this gets called every time state is reported, not just when connecting.
  override fun isOn(context: Context): Boolean =
      try { adapter(context)?.isEnabled ?: false } catch (e: SecurityException) { false }

  override fun setOn(context: Context, on: Boolean) {
    val adapter = adapter(context) ?: return

    try {
      // enable()/disable() return false rather than throwing when the OS just refuses
      // the request outright — worth distinguishing from a SecurityException below,
      // since it's common (especially for disable()) on plenty of devices/Android
      // versions even when the permission is granted.
      @Suppress("DEPRECATION")
      val accepted = if (on) adapter.enable() else adapter.disable()
      if (!accepted) Log.e(TAG, "Bluetooth ${if (on) "enable" else "disable"} request rejected by the OS")
    } catch (e: SecurityException) {
      Log.e(TAG, "Bluetooth ${if (on) "enable" else "disable"} not permitted", e)
    }
  }

  private val receiver = object : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
      AESPHomeService.instance?.reportSwitch(this@BluetoothSwitch, isOn(ctx))
    }
  }

  override fun start(context: Context) {
    context.registerReceiver(receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
    AESPHomeService.instance?.reportSwitch(this, isOn(context)) // report the current state right away, not just on the next change
  }

  override fun stop(context: Context) {
    try { context.unregisterReceiver(receiver) } catch (e: IllegalArgumentException) {}
  }
}
