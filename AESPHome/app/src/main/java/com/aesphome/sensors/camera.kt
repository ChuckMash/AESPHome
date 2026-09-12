package com.aesphome

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock


/*

  Camera
    Provides still shots and streaming for HA's camera entity, built on Camera2 rather
    than CameraX — CameraX has no continuous-JPEG use case; its ImageAnalysis delivers raw
    YUV frames, which would mean giving up the hardware JPEG encoder Camera2's JPEG-format
    ImageReader already gets for free. Owns the state machine that decides *when* a frame
    gets captured — the idle-capture loop, and the stream/timeout tracking that starts,
    refreshes, and expires a live stream. esphome.kt's only involvement is wire protocol:
    it dispatches an incoming CameraImageRequest straight to onImageRequest() below, and
    pushCameraFrame() (on AESPHome) is the one place that knows how to chunk a JPEG onto
    the socket.

*/


// aioesphomeapi's plaintext frame helper closes the connection on any frame over 65535
// bytes (MAX_PLAINTEXT_FRAME_SIZE, matching the noise transport's fixed 16-bit length
// header) — and that check applies to the *whole* CameraImageResponse protobuf message,
// not just the raw chunk. Each chunk carries 11 bytes of protobuf overhead alongside it
// (entity_key + the bytes-field tag/length + the done flag), so the true ceiling is
// 65535 - 11 = 65524. 65000 leaves a small margin below that rather than cutting it
// exactly to the wire. A chunk size of 0 (send the whole JPEG unchunked) crashes as soon
// as the JPEG exceeds that limit, which most real captures do.
var CAMERA_CHUNK_SIZE = 65000

var CAMERA_STREAM_INTERVAL_MS = 50L
var CAMERA_STREAM_TIMEOUT_MS = 5000L

private const val CAPTURE_TIMEOUT_MS = 5000L
private const val CAMERA_LOCK_TIMEOUT_MS = 20000L // headroom above a one-shot's worst case; only trips if something's genuinely stuck
private const val MAX_WARMUP_FRAMES = 20 // safety cap if AE never reports converged


object CameraService : Service {
  override val id                  = "camera"
  override val label               = "Camera"
  override val description         = "Requires Camera Permissions"
  override val enabledByDefaultApp = false
  override val enabledByDefaultHa  = false
  override val entityCategory      = EntityCategory.NONE
  override val icon                = "mdi:camera"
  val key: Int                     = id.hashCode()

  val lensSetting = SelectSetting(
      id = "camera_lens",
      label = "Camera Lens",
      options = listOf("Camera 1"), //placeholder
      default = "Camera 1",
      deviceUi = true,
      homeAssistant = true,
      entityCategory = EntityCategory.CONFIG,
      enabledByDefaultHa = false,
      icon = "mdi:camera-switch",
      onChanged = { stopStreamNow() },
  )

  private val rotationOptions = listOf("0°", "90°", "180°", "270°")

  // Fixed, hand-picked list rather than discovered per-device (CONTROL_AVAILABLE_EFFECTS
  // varies wildly by lens/OEM, and this is meant to be one global selector, not one per
  // lens) — see openCamera, which only actually applies a choice the active lens supports
  // and silently falls back to Off otherwise.
  private val effectModes = listOf(
      "Off" to CaptureRequest.CONTROL_EFFECT_MODE_OFF,
      "Mono" to CaptureRequest.CONTROL_EFFECT_MODE_MONO,
      "Negative" to CaptureRequest.CONTROL_EFFECT_MODE_NEGATIVE,
      "Solarize" to CaptureRequest.CONTROL_EFFECT_MODE_SOLARIZE,
      "Sepia" to CaptureRequest.CONTROL_EFFECT_MODE_SEPIA,
      "Posterize" to CaptureRequest.CONTROL_EFFECT_MODE_POSTERIZE,
      "Whiteboard" to CaptureRequest.CONTROL_EFFECT_MODE_WHITEBOARD,
      "Blackboard" to CaptureRequest.CONTROL_EFFECT_MODE_BLACKBOARD,
      "Aqua" to CaptureRequest.CONTROL_EFFECT_MODE_AQUA,
  )

  val effectSetting = SelectSetting(
      id = "camera_effect",
      label = "Camera Effect",
      options = effectModes.map { it.first },
      default = "Off",
      deviceUi = true,
      homeAssistant = true,
      entityCategory = EntityCategory.CONFIG,
      enabledByDefaultHa = false,
      icon = "mdi:image-filter-vintage",
      onChanged = { stopStreamNow() },
  )

  // Not really "frames per second" — the stored value is the number of seconds between
  // idle single-shot captures (default 60s, 0 = never), named to match how it's exposed
  // to the user.
  val idleFpsSetting = Setting(
      id                 = "camera_idle_fps",
      label              = "Camera idle update (s)",
      default            = 60f,
      min                = 0f,
      max                = 3600f,
      step               = 1f,
      deviceUi           = true,
      homeAssistant      = true,
      entityCategory     = EntityCategory.CONFIG,
      enabledByDefaultHa = false,
      icon               = "mdi:timer-outline",
      onChanged          = { interruptIdleLoop() },
  )

  // default is a placeholder only — actually seeded from the camera's own reported default
  // the first time it opens (see openCamera), so this value is never seen in practice.
  val qualitySetting = Setting(
      id                 = "camera_jpeg_quality",
      label              = "Camera JPEG Quality",
      default            = 90f,
      min                = 1f,
      max                = 100f,
      step               = 1f,
      deviceUi           = true,
      homeAssistant      = true,
      entityCategory     = EntityCategory.CONFIG,
      enabledByDefaultHa = false,
      icon               = "mdi:image-outline",
  )

  override val settings: List<Setting> = listOf(idleFpsSetting, qualitySetting)

  override var selectSettings: List<SelectSetting> = listOf(lensSetting, effectSetting)
  private var rotationSettings: Map<Int, SelectSetting> = emptyMap()   // keyed by lens index
  private var resolutionSettings: Map<Int, SelectSetting> = emptyMap() // keyed by lens index

  // Held for the idle loop's own use (nobody calls it with a context — it drives itself).
  private var appContext: Context? = null

  private var handlerThread: HandlerThread? = null
  private var handler: Handler? = null

  private var camera: CameraDevice? = null
  private var session: CameraCaptureSession? = null
  private var reader: ImageReader? = null
  private var frameSize: Size? = null
  private var supportsAutofocus = false
  private var rotationDegrees = 0
  private var jpegQuality = 90
  private var effectMode = CaptureRequest.CONTROL_EFFECT_MODE_OFF
  private val jpegQueue = ArrayBlockingQueue<ByteArray>(2)

  // Guards the whole open→calibrate→capture→close cycle so at most one of {one-shot, idle
  // tick, stream} ever touches the camera at a time. A stream acquires this once for its
  // entire lifetime (capture(), below) — not per frame — so a setting change mid-stream
  // can't let a fresh restart race the old cycle's teardown. One-shot/idle callers only
  // ever try for it and skip cleanly if a stream already holds it, so a live stream is
  // never interrupted by either of them.
  private val cameraLock = ReentrantLock()



  // Rebuilds lensSetting's options from whatever cameras this device actually has, plus a
  // rotation and resolution SelectSetting per lens. Per-lens (rather than one shared
  // dropdown that follows the current lens) because HA has no way to be told an existing
  // entity's options changed without a reconnect, which isn't an option here — so instead
  // every lens gets its own fixed dropdowns, built once, that never need to change.
  fun refreshLensOptions(context: Context) {
    val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    val ids = try { manager.cameraIdList } catch (e: Exception) { Log.e(TAG, "cameraIdList failed", e); return }
    if (ids.isEmpty()) return
    lensSetting.options = ids.indices.map { "Camera ${it + 1}" }

    rotationSettings = ids.indices.associateWith { index ->
      SelectSetting(
        id = "camera_${index + 1}_rotation",
        label = "Camera ${index + 1} Rotation",
        options = rotationOptions,
        default = rotationOptions.first(),
        deviceUi = true,
        homeAssistant = true,
        entityCategory = EntityCategory.CONFIG,
        enabledByDefaultHa = false,
        icon = "mdi:rotate-right",
        onChanged = { stopStreamNow() })
    }

    resolutionSettings = ids.indices.mapNotNull { index ->
      val sizes = outputSizes(manager, ids[index]).map { "${it.width}x${it.height}" }
      if (sizes.isEmpty()) return@mapNotNull null
      index to SelectSetting(
        id = "camera_${index + 1}_resolution",
        label = "Camera ${index + 1} Resolution",
        options = sizes,
        default = sizes.first(),
        deviceUi = true,
        homeAssistant = true,
        entityCategory = EntityCategory.CONFIG,
        enabledByDefaultHa = false,
        icon = "mdi:image-size-select-large",
        onChanged = { stopStreamNow() })
    }.toMap()

    // Each lens's own rotation + resolution sit together, right after the lens picker;
    // effectSetting is global (not per-lens), so it just tags along unconditionally.
    selectSettings = listOf(lensSetting, effectSetting) +
      ids.indices.flatMap { listOfNotNull(rotationSettings[it], resolutionSettings[it]) }
  }

  // JPEG output sizes a given lens supports, smallest first. Shared by refreshLensOptions
  // (to build each lens's resolution options) and openCamera (as the fallback if the saved
  // resolution doesn't match anything this lens actually offers).
  private fun outputSizes(manager: CameraManager, cameraId: String): List<Size> =
    try {
      manager.getCameraCharacteristics(cameraId)
        .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        ?.getOutputSizes(ImageFormat.JPEG)
        ?.sortedBy { it.width * it.height }
        ?: emptyList()
    } catch (e: Exception) { Log.e(TAG, "getCameraCharacteristics failed for $cameraId", e); emptyList() }

  private fun parseSize(value: String): Size? {
    val (w, h) = value.split("x").takeIf { it.size == 2 } ?: return null
    return Size(w.toIntOrNull() ?: return null, h.toIntOrNull() ?: return null)
  }




  override fun start(context: Context) {
    if (handlerThread != null) return
    appContext = context
    refreshLensOptions(context)
    handlerThread = HandlerThread("AESPHomeCamera").apply { start() }
    handler = Handler(handlerThread!!.looper)
    idleRunning = true
    idleThread = Thread({ idleLoop() }, "AESPHomeCameraIdle").apply { start() }
    Log.i(TAG, "CameraService started")
  }

  override fun stop(context: Context) {
    val t = handlerThread ?: return
    try { t.quitSafely() } catch (_: Exception) {}
    handlerThread = null
    handler = null
    idleRunning = false
    idleThread?.interrupt()
    idleThread = null
    streamTimeout = 0L
    Log.i(TAG, "CameraService stopped")
  }




  //
  // Streaming / idle state machine — everything about *when* a frame gets captured and
  // pushed to HA. onImageRequest and interruptIdleLoop are the only entry points
  // esphome.kt calls into this file for; stopStreamNow is also called on client
  // disconnect so a stream doesn't linger past its connection.
  //

  // Set by a CameraImageRequest(stream=true) and refreshed by every subsequent one — also
  // acts as the timeout that lets a stream stop itself once HA stops re-requesting it.
  @Volatile private var streamTimeout = 0L
  private fun isStreamActive(): Boolean = System.currentTimeMillis() < streamTimeout

  // A stream running with the old lens/rotation/resolution is no longer valid the instant
  // any of those change — each SelectSetting above calls this via its onChanged rather than
  // waiting out CAMERA_STREAM_TIMEOUT_MS. isStreamActive() picks it up on its next poll (at
  // most one capture + CAMERA_STREAM_INTERVAL_MS away), which lets capture()'s while loop
  // exit and closeCamera() run via its finally block.
  fun stopStreamNow() { streamTimeout = 0L }

  // CameraImageRequest: single=one-shot capture; stream=true starts (or refreshes) a
  // continuous stream that stops itself once HA stops re-requesting it.
  fun onImageRequest(context: Context, stream: Boolean) {
    if (stream) {
      val alreadyStreaming = streamTimeout > 0
      streamTimeout = System.currentTimeMillis() + CAMERA_STREAM_TIMEOUT_MS
      if (alreadyStreaming) return

      Log.i(TAG, "Camera: stream capture started")
      Thread({
        capture(context, streaming = true, isRunning = ::isStreamActive) { AESPHomeService.instance?.pushCameraFrame(it) }
        streamTimeout = 0L
        Log.i(TAG, "Camera: stream capture stopped")
      }, "AESPHomeCameraStream").start()
    } else if (streamTimeout == 0L) {
      // A running stream already sends frames continuously, so it satisfies this on its own.
      Log.i(TAG, "Camera: one-shot capture requested")
      capture(context, streaming = false) { AESPHomeService.instance?.pushCameraFrame(it) }
    }
  }

  // Independent of AESPHome's diagnosticsLoop (which polls readSensors on a fixed 60s
  // cadence) — this fires its own single-shot capture on idleFpsSetting's interval, read
  // fresh each cycle so a change takes effect on the next capture. Skipped whenever there's
  // no HA connection to send to, or a real stream is already covering it. 0 = never: sleeps
  // until interrupted rather than polling, woken by idleFpsSetting's onChanged (a new
  // interval applies immediately) or by stop().
  @Volatile private var idleRunning = false
  private var idleThread: Thread? = null

  private fun idleLoop() {
    val context = appContext ?: return
    while (idleRunning) {
      val idleSeconds = getSetting(context, idleFpsSetting)
      if (idleSeconds > 0 && AESPHomeService.instance?.hasActiveConnection == true && !isStreamActive()) {
        capture(context, streaming = false) { AESPHomeService.instance?.pushCameraFrame(it) }
      }
      val sleepMs = if (idleSeconds > 0) (idleSeconds * 1000).toLong() else Long.MAX_VALUE
      try { Thread.sleep(sleepMs) } catch (_: InterruptedException) {}
    }
  }

  fun interruptIdleLoop() = idleThread?.interrupt()

  // Debug-only: logs actual delivered stream throughput once a second, from real elapsed
  // wall-clock time — not assumed from CAMERA_STREAM_INTERVAL_MS. Only meaningful during
  // streaming, so capture() resets these at the start of each stream and only calls
  // trackFps() on the streaming path.
  private var fpsCount = 0
  private var fpsWindowStart = 0L

  private fun trackFps() {
    val now = System.currentTimeMillis()
    if (fpsWindowStart == 0L) fpsWindowStart = now
    fpsCount++
    val elapsed = now - fpsWindowStart
    if (elapsed >= 1000L) {
      Log.d(TAG, "Camera: ${"%.1f".format(fpsCount * 1000f / elapsed)} fps sent to HA")
      fpsCount = 0
      fpsWindowStart = now
    }
  }

  // One-shot and streaming captures share the same open→calibrate→capture→close lifecycle
  // and only differ in what happens in the middle: one-shot takes a single still (and
  // reports lux from its own metadata); streaming loops taking stills, paced by
  // CAMERA_STREAM_INTERVAL_MS, until isRunning() says stop — with AE/AF calibration left
  // running for the stream's whole life rather than stopped after warm-up.
  //
  // One-shot/idle callers skip entirely rather than waiting if a stream already holds the
  // camera — they should never delay or interrupt a live stream. A stream instead waits
  // (bounded) for its turn, since it's expected to actually run once requested.
  private fun capture(context: Context, streaming: Boolean, isRunning: () -> Boolean = { false }, onFrame: (ByteArray) -> Unit) {
    val locked = if (streaming) cameraLock.tryLock(CAMERA_LOCK_TIMEOUT_MS, TimeUnit.MILLISECONDS) else cameraLock.tryLock()
    if (!locked) {
      if (streaming) Log.e(TAG, "Camera still busy after ${CAMERA_LOCK_TIMEOUT_MS}ms — giving up on stream")
      else Log.d(TAG, "Camera busy streaming — skipping capture")
      return
    }
    try {
      val h = handler ?: return
      if (!openCamera(context, h)) return
      try {
        calibrate(h, keepRunning = streaming)
        if (streaming) {
          fpsCount = 0
          fpsWindowStart = 0L
          while (isRunning()) {
            captureStill(h, reportLux = false)?.let { onFrame(it); trackFps() }
            Thread.sleep(CAMERA_STREAM_INTERVAL_MS)
          }
        } else {
          captureStill(h, reportLux = true)?.let(onFrame)
        }
      } finally {
        closeCamera()
      }
    } finally {
      cameraLock.unlock()
    }
  }




  private fun openCamera(context: Context, h: Handler): Boolean {
    if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
      Log.e(TAG, "Camera permission not granted"); return false
    }

    val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    val ids = manager.cameraIdList
    if (ids.isEmpty()) { Log.e(TAG, "No cameras found"); return false }

    // lensSetting's options are positional ("Camera 1", "Camera 2", ...), so the selected
    // label maps straight back to an index. Falls back to the first camera if the saved
    // selection doesn't match anything current (e.g. stale pick from a different device).
    val selectedIndex = lensSetting.options.indexOf(getSelectSetting(context, lensSetting)).coerceAtLeast(0)
    val cameraId = ids.getOrElse(selectedIndex) { ids[0] }

    val characteristics = manager.getCameraCharacteristics(cameraId)
    val sizes = outputSizes(manager, cameraId)
    if (sizes.isEmpty()) { Log.e(TAG, "No JPEG sizes"); return false }
    val saved = resolutionSettings[selectedIndex]?.let { parseSize(getSelectSetting(context, it)) }
    val outputSize = saved?.takeIf { it in sizes } ?: sizes.first()

    supportsAutofocus = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
        ?.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) == true

    // Only actually applied if this lens's own CONTROL_AVAILABLE_EFFECTS lists it —
    // effectSetting itself doesn't know which lens is active, so unsupported picks
    // silently fall back to Off rather than failing the capture.
    val wantedEffect = effectModes.firstOrNull { it.first == getSelectSetting(context, effectSetting) }?.second
    val supportedEffects = characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_EFFECTS)?.toSet() ?: emptySet()
    effectMode = wantedEffect?.takeIf { it in supportedEffects } ?: CaptureRequest.CONTROL_EFFECT_MODE_OFF

    rotationDegrees = rotationSettings[selectedIndex]?.let { getSelectSetting(context, it).removeSuffix("°").toIntOrNull() } ?: 0
    frameSize = outputSize
    jpegQueue.clear()
    reader = ImageReader.newInstance(outputSize.width, outputSize.height, ImageFormat.JPEG, 2).apply {
      setOnImageAvailableListener({ r ->
        r.acquireLatestImage()?.use { image ->
          val buffer = image.planes[0].buffer
          val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
          if (!jpegQueue.offer(bytes)) { jpegQueue.poll(); jpegQueue.offer(bytes) }
        }
      }, h)
    }

    val latch = CountDownLatch(1)
    try {
      manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
        override fun onOpened(device: CameraDevice) {
          camera = device
          try {
            device.createCaptureSession(listOf(reader!!.surface), object : CameraCaptureSession.StateCallback() {
              override fun onConfigured(s: CameraCaptureSession) { session = s; latch.countDown() }
              override fun onConfigureFailed(s: CameraCaptureSession) { Log.e(TAG, "createCaptureSession failed"); latch.countDown() }
            }, h)
          } catch (e: Exception) { Log.e(TAG, "session config failed", e); latch.countDown() }
        }
        override fun onDisconnected(device: CameraDevice) { device.close(); latch.countDown() }
        override fun onError(device: CameraDevice, error: Int) { Log.e(TAG, "camera error: $error"); device.close(); latch.countDown() }
      }, h)
    } catch (e: Exception) { Log.e(TAG, "openCamera failed", e); latch.countDown() }

    if (!latch.await(CAPTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) Log.w(TAG, "Timed out opening camera")
    if (session == null) return false

    // First-ever open: nothing persisted yet, so seed the setting from the camera's own
    // default rather than a guessed constant. Every open after that (here or on any other
    // lens) just reads back whatever's persisted, same as rotation/resolution above.
    if (!hasSetting(context, qualitySetting)) {
      try {
        camera?.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)?.build()?.get(CaptureRequest.JPEG_QUALITY)
            ?.let { setSetting(context, qualitySetting, it.toFloat()) }
      } catch (e: Exception) { Log.w(TAG, "Couldn't read default JPEG quality", e) }
    }
    jpegQuality = getSetting(context, qualitySetting).toInt()

    return true
  }

  private fun closeCamera() {
    try { camera?.close() } catch (_: Exception) {}
    try { reader?.close() } catch (_: Exception) {}
    camera = null
    session = null
    reader = null
  }

  private fun buildRequest(template: Int): CaptureRequest.Builder =
    checkNotNull(camera) { "camera not open" }.createCaptureRequest(template).apply {
      addTarget(reader!!.surface)
      set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
      if (supportsAutofocus) set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
      set(CaptureRequest.JPEG_ORIENTATION, rotationDegrees)
      set(CaptureRequest.JPEG_QUALITY, jpegQuality.toByte())
      set(CaptureRequest.CONTROL_EFFECT_MODE, effectMode)
    }

  // Runs a repeating preview request until AE converges (or the safety cap is hit). If
  // keepRunning is false (one-shot), the repeating request is stopped afterwards so it
  // can't race with the real capture that follows; if true (streaming), it's left running
  // so AE/AF keep adjusting for the life of the stream.
  private fun calibrate(h: Handler, keepRunning: Boolean) {
    val s = session ?: return
    val warmLatch = CountDownLatch(1)
    var frames = 0

    try {
      s.setRepeatingRequest(buildRequest(CameraDevice.TEMPLATE_PREVIEW).build(), object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
          frames++
          val ae = result.get(CaptureResult.CONTROL_AE_STATE)
          val converged = ae == null || ae == CaptureResult.CONTROL_AE_STATE_CONVERGED || ae == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED
          if (converged || frames >= MAX_WARMUP_FRAMES) warmLatch.countDown()
        }
      }, h)
    } catch (e: Exception) { Log.e(TAG, "setRepeatingRequest failed", e); return }

    if (!warmLatch.await(CAPTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) Log.w(TAG, "Warmup timed out")
    if (!keepRunning) { try { s.stopRepeating() } catch (_: Exception) {} }
  }

  // Takes one still and returns its JPEG bytes. When reportLux is true, computes and
  // reports illuminance from THIS capture's own metadata (one-shot only).
  private fun captureStill(h: Handler, reportLux: Boolean): ByteArray? {
    val s = session ?: return null
    if (reportLux) jpegQueue.clear() // drop any stray frame left from calibration before the real shot

    var result: TotalCaptureResult? = null
    val resultLatch = if (reportLux) CountDownLatch(1) else null
    val callback = if (reportLux) object : CameraCaptureSession.CaptureCallback() {
      override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, r: TotalCaptureResult) {
        result = r; resultLatch?.countDown()
      }
      override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
        resultLatch?.countDown()
      }
    } else null

    try { s.capture(buildRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).build(), callback, h) }
    catch (e: Exception) { Log.e(TAG, "capture failed", e); return null }

    resultLatch?.await(CAPTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)

    val jpeg = try {
      jpegQueue.poll(CAPTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    } catch (e: InterruptedException) { Log.w(TAG, "Interrupted waiting for JPEG", e); null }

    jpeg?.let {
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeByteArray(it, 0, it.size, bounds)
      if (bounds.outWidth != frameSize?.width || bounds.outHeight != frameSize?.height) {
        Log.w(TAG, "Camera returned ${bounds.outWidth}x${bounds.outHeight}, requested ${frameSize?.width}x${frameSize?.height}")
      }
    }
    if (reportLux) result?.let(::reportLux)
    return jpeg
  }

  private fun reportLux(result: CaptureResult) {
    try {
      val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)?.toFloat() ?: 100f
      val exposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.toFloat() ?: 10_000_000f
      val aperture = result.get(CaptureResult.LENS_APERTURE) ?: 2.0f
      val shutterSpeedSec = exposureTimeNs / 1_000_000_000f
      val calibrationConstant = 340f
      val estimatedLux = (calibrationConstant * (aperture * aperture)) / (shutterSpeedSec * iso)
      Log.d(TAG, "Estimated Room Brightness: $estimatedLux lx")
      CameraLuxSensor.updateLux(null, estimatedLux)
    } catch (_: Exception) {}
  }
}

// Camera-based illuminance sensor reported to HA. Kept as its own object (rather than
// folded into CameraService) because it's a distinct ESPHome entity — its own id, key, and
// SensorKind, listed separately in Sensors.eventSensors — so HA sees "Camera" and "Camera
// Illuminance" as two different entities, not one.
object CameraLuxSensor : EventSensor {
  override val id = "camera_lux"
  override val label = "LUX (Camera)"
  override val description = "Requires Camera and Camera Permissions"
  override val key: Int = id.hashCode()
  override fun kind(context: Context) = SensorKind.Numeric(unit = "lx", deviceClass = "illuminance")
  override val enabledByDefaultApp = true
  override val enabledByDefaultHa = true
  override val entityCategory = EntityCategory.NONE
  override val icon = "mdi:brightness-6"

  @Volatile internal var lastValue: Float = 0f

  fun updateLux(context: Context?, lux: Float) {
    lastValue = lux
    AESPHomeService.instance?.reportSensor(this, lux)
  }

  override fun start(context: Context) {}
  override fun stop(context: Context) {}
}
