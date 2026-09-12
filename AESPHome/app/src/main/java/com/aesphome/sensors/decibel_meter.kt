package com.aesphome

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.math.log10
import kotlin.math.sqrt


/*

  Decibel Meter

    TODO:
      * Review and cleanup

*/


private const val SAMPLE_RATE_HZ = 16000
private const val POLL_MS = 10_000L
private const val BURST_MS = 1000L
private const val MIN_DBFS = -60f // floor for near-silence; also avoids log10(0) = -Infinity
private const val CALIBRATION_OFFSET_DB = 90f // best-guess dBFS -> dB SPL shift for a typical mic


object DecibelMeterSensor : EventSensor {
  override val id                     = "decibel_meter"
  override val label                  = "Ambient Noise"
  override val description            = "Requires Microphone Permission"
  override val key: Int               = id.hashCode()
  override val enabledByDefaultApp    = false
  override val enabledByDefaultHa     = true
  override val entityCategory         = EntityCategory.NONE
  override val icon                   = "mdi:volume-high"
  override fun kind(context: Context) = SensorKind.Numeric(unit = "dB", deviceClass = "")


  @Volatile private var running = false
  private var thread: Thread? = null

  override fun start(context: Context) {
    running = true
    thread = Thread({ loop(context) }, "AESPHomeDecibelMeter").apply { start() }
  }

  override fun stop(context: Context) {
    running = false
    thread?.interrupt()
    thread = null
  }

  private fun loop(context: Context) {
    while (running) {
      sample(context)?.let { AESPHomeService.instance?.reportSensor(this, it) }
      try { Thread.sleep(POLL_MS) } catch (_: InterruptedException) {}
    }
  }

  // Opens the mic, drains it for BURST_MS (reading continuously, not sleeping-then-reading,
  // since a sleep would let the mic's small internal buffer overwrite itself), computes RMS
  // across everything read, then closes the mic immediately.
  private fun sample(context: Context): Float? {
    if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
      return null
    }

    val minBufferSize = AudioRecord.getMinBufferSize(
      SAMPLE_RATE_HZ, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    if (minBufferSize <= 0) return null

    val recorder = try {
      AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE_HZ,
        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBufferSize)
    } catch (e: Exception) { Log.e(TAG, "AudioRecord init failed", e); return null }

    if (recorder.state != AudioRecord.STATE_INITIALIZED) { recorder.release(); return null }

    return try {
      recorder.startRecording()
      val buffer = ShortArray(minBufferSize / 2)
      var sumSquares = 0.0
      var totalSamples = 0L
      val deadline = System.currentTimeMillis() + BURST_MS
      while (running && System.currentTimeMillis() < deadline) {
        val read = recorder.read(buffer, 0, buffer.size) // blocks until data's available
        if (read <= 0) continue
        for (i in 0 until read) sumSquares += buffer[i].toDouble() * buffer[i].toDouble()
        totalSamples += read
      }
      if (totalSamples == 0L) return null

      val rms = sqrt(sumSquares / totalSamples)
      val dbfs = if (rms < 1.0) MIN_DBFS else (20 * log10(rms / Short.MAX_VALUE)).toFloat().coerceAtLeast(MIN_DBFS)
      (dbfs + CALIBRATION_OFFSET_DB).coerceAtLeast(0f)
    } catch (e: Exception) {
      Log.e(TAG, "Decibel sample failed", e)
      null
    } finally {
      try { recorder.stop() } catch (e: Exception) {}
      recorder.release()
    }
  }
}
