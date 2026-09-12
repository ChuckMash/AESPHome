package com.aesphome

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.util.Log
import kotlin.math.roundToInt

/*

  System Volume

*/

private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
private const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"

object SystemVolumeService : Service {
  override val id                  = "system_volume"
  override val label               = "System Volume"
  override val description         = ""
  override val enabledByDefaultApp = true
  override val enabledByDefaultHa  = false
  override val entityCategory      = EntityCategory.NONE
  override val icon                = "mdi:volume-high"

  private var audioManager: AudioManager? = null
  private var receiver: BroadcastReceiver? = null

  val volumeSetting = Setting(
    id                 = "system_volume_level",
    label              = "System Volume",
    default            = 1.0f,
    min                = 0.0f,
    max                = 1.0f,
    step               = 0.01f,
    deviceUi           = false,
    homeAssistant      = true,
    entityCategory     = EntityCategory.NONE,
    enabledByDefaultHa = false,
    icon               = "mdi:volume-high",
    onChanged          = { applySystemVolume(it) })

  override val settings: List<Setting> = listOf(volumeSetting)

  override fun start(context: Context) {
    val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    audioManager = manager

    val localReceiver = object : BroadcastReceiver() {
      override fun onReceive(receiverContext: Context, intent: Intent) {
        if (intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1) != AudioManager.STREAM_MUSIC) return
        val level = currentVolume(manager)
        Log.i(TAG, "System volume changed locally: $level")
        setSetting(context, volumeSetting, level)
        AESPHomeService.instance?.reportSetting(volumeSetting, level)
      }
    }
    receiver = localReceiver
    context.registerReceiver(localReceiver, IntentFilter(VOLUME_CHANGED_ACTION))

    // Sync once at startup in case the physical buttons changed it while this service
    // wasn't running to hear about it.
    setSetting(context, volumeSetting, currentVolume(manager))
  }

  override fun stop(context: Context) {
    receiver?.let { context.unregisterReceiver(it) }
    receiver = null
    audioManager = null
  }

  private fun currentVolume(manager: AudioManager): Float {
    val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    if (max <= 0) return 0f
    return manager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
  }

  private fun applySystemVolume(context: Context) {
    val manager = audioManager ?: return
    val level = getSetting(context, volumeSetting)
    val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    val index = (level * max).roundToInt().coerceIn(0, max)

    // Already at this value — skip, so a change echoed back in from our own broadcast
    // receiver doesn't re-trigger a redundant apply (and log) of a no-op change.
    if (index == manager.getStreamVolume(AudioManager.STREAM_MUSIC)) return

    Log.i(TAG, "System volume changed remotely: $level")
    manager.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
  }
}
