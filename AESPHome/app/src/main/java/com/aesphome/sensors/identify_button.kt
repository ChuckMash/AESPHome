package com.aesphome

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator

/*

  Identify
    When triggered, causes the device to beep twice

*/

private const val IDENTIFY_TONE_DURATION_MS = 300

object IdentifyButton : Button {
  override val id                  = "identify"
  override val label               = "Identify"
  override val description         = ""
  override val key: Int            = id.hashCode()
  override val enabledByDefaultApp = true
  override val enabledByDefaultHa  = true
  override val entityCategory      = EntityCategory.DIAGNOSTIC
  override val icon                = "mdi:bullhorn"

  override fun press(context: Context) {
    val toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, ToneGenerator.MAX_VOLUME)
    toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP2, IDENTIFY_TONE_DURATION_MS)
    Thread({
      Thread.sleep(IDENTIFY_TONE_DURATION_MS.toLong() + 100)
      toneGenerator.release()
    }, "IdentifyToneRelease").start()
  }
}
