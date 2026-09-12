package com.aesphome

import android.content.Context

private const val PREFS_NAME = "aesphome_settings"

// Generic on/off toggle, keyed by id — every Sensor and Service shares this, so
// adding a new one never requires a new getter/setter pair here.
fun isEnabled(context: Context, component: Toggleable): Boolean =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(component.id, component.enabledByDefaultApp)

fun setEnabled(context: Context, component: Toggleable, enabled: Boolean) {
  context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(component.id, enabled).apply()
}

// Generic per-Sensor setting value, keyed by id — same pattern as isEnabled/setEnabled.
fun getSetting(context: Context, setting: Setting): Float =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getFloat(setting.id, setting.default)

// Returns the clamped value actually persisted, so callers that need to report it
// elsewhere (e.g. back to HA) never accidentally echo the pre-clamp input instead.
fun setSetting(context: Context, setting: Setting, value: Float): Float {
  val clamped = value.coerceIn(setting.min, setting.max)
  context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putFloat(setting.id, clamped).apply()
  setting.onChanged?.invoke(context)
  return clamped
}

// Same generic-by-id pattern as getSetting/setSetting, but for SelectSetting's string options.
fun getSelectSetting(context: Context, setting: SelectSetting): String =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(setting.id, setting.default) ?: setting.default

fun setSelectSetting(context: Context, setting: SelectSetting, value: String) {
  if (value !in setting.options) return // ignore anything that isn't one of the fixed choices
  context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(setting.id, value).apply()
  setting.onChanged?.invoke(context)
}

// True if this Setting has ever been explicitly persisted (by the device UI, HA, or code).
// Lets a first-use default be seeded from something other than a fixed compile-time value —
// e.g. CameraService's JPEG quality, seeded once from the camera's own default — without
// every later read re-applying it over a value the user may have since changed.
fun hasSetting(context: Context, setting: Setting): Boolean =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).contains(setting.id)
