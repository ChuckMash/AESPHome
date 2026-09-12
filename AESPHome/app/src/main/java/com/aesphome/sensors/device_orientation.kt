package com.aesphome

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Surface


/*

  Device Orientation
    Actually screen orientation

*/


object DeviceOrientationSensor : EventSensor {
  override val id                     = "device_orientation"
  override val label                  = "Device Orientation"
  override val description            = ""
  override val key: Int               = id.hashCode()
  override val enabledByDefaultApp    = false
  override val enabledByDefaultHa     = true
  override val entityCategory         = EntityCategory.NONE
  override val icon                   = "mdi:screen-rotation"
  override fun kind(context: Context) = SensorKind.Numeric(unit = "°", deviceClass = "")

  private var displayManager: DisplayManager? = null

  private val listener = object : DisplayManager.DisplayListener {
    override fun onDisplayChanged(displayId: Int) {
      if (displayId == Display.DEFAULT_DISPLAY) report()
    }
    override fun onDisplayAdded(displayId: Int) {}
    override fun onDisplayRemoved(displayId: Int) {}
  }

  override fun start(context: Context) {
    val manager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    displayManager = manager
    manager.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
    report()
  }

  override fun stop(context: Context) {
    displayManager?.unregisterDisplayListener(listener)
    displayManager = null
  }

  private fun report() {
    val degrees = when (displayManager?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation) {
      Surface.ROTATION_90  -> 90
      Surface.ROTATION_180 -> 180
      Surface.ROTATION_270 -> 270
      else                 -> 0
    }
    AESPHomeService.instance?.reportSensor(this, degrees.toFloat())
  }
}
