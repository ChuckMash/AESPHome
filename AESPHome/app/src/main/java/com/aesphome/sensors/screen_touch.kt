package com.aesphome

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent


/*

  Screen Touched
    todo
      better encapsulation

*/


object TouchSensor : EventSensor {
  override val id                     = "touch"
  override val label                  = "Screen Touch"
  override val description            = "Requires Accessability Service Enabled"
  override val key: Int               = id.hashCode()
  override val enabledByDefaultApp    = false
  override val enabledByDefaultHa     = true
  override val entityCategory         = EntityCategory.NONE
  override val icon                   = "mdi:gesture-tap"
  override fun kind(context: Context) = SensorKind.Binary()

  private val resetTime = Setting(
    id                 = "touch_reset_time",
    label              = "Screen Touch Reset Time (s)",
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

  override val settings = listOf(resetTime)

  override fun start(context: Context) {}
  override fun stop(context: Context) {}

  @Volatile private var touchActive = false
  private val offHandler = Handler(Looper.getMainLooper())
  private val offRunnable = Runnable {
    touchActive = false
    AESPHomeService.instance?.reportSensor(this, false)
  }

  fun onTouchDetected(context: Context) {
    offHandler.removeCallbacks(offRunnable) // any touch resets the reset-time window
    touchActive = true
    AESPHomeService.instance?.reportSensor(this, true)
    offHandler.postDelayed(offRunnable, (getSetting(context, resetTime) * 1000).toLong())
  }

  private fun onResetTimeChanged(context: Context) {
    if (!touchActive) return
    offHandler.removeCallbacks(offRunnable)
    offHandler.postDelayed(offRunnable, (getSetting(context, resetTime) * 1000).toLong())
  }
}



class TouchAccessibilityService : AccessibilityService() {
  override fun onServiceConnected() {
    super.onServiceConnected()
    serviceInfo = AccessibilityServiceInfo().apply {
      eventTypes = AccessibilityEvent.TYPES_ALL_MASK
      feedbackType = AccessibilityServiceInfo.FEEDBACK_ALL_MASK
      notificationTimeout = 100
      flags = AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
      packageNames = null // listen across every app, not just this one
    }
    Log.i(TAG, "TouchAccessibilityService connected and configured")
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    val type = event?.eventType ?: return
    //Log.d(TAG, "Accessibility event received: $type")
    when (type) {
      AccessibilityEvent.TYPE_TOUCH_INTERACTION_START,
      AccessibilityEvent.TYPE_VIEW_CLICKED,
      AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
      AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
        if (isEnabled(applicationContext, TouchSensor)) {
          Log.i(TAG, "Scren touch detected -> reporting user activity to HA")
          TouchSensor.onTouchDetected(applicationContext)
        }
      }
    }
  }

  override fun onInterrupt() {}
}
