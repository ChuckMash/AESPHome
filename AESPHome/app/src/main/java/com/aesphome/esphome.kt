package com.aesphome

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket

// Message type constants
private const val MESSAGE_HELLO_REQUEST = 1
private const val MESSAGE_HELLO_RESPONSE = 2
private const val MESSAGE_DEVICE_INFO_REQUEST = 9
private const val MESSAGE_DEVICE_INFO_RESPONSE = 10
private const val MESSAGE_LIST_ENTITIES_REQUEST = 11
private const val MESSAGE_LIST_ENTITIES_RESPONSE = 63
private const val MESSAGE_LIST_ENTITIES_BINARY_SENSOR = 12
private const val MESSAGE_LIST_ENTITIES_SENSOR = 16
private const val MESSAGE_LIST_ENTITIES_DONE = 19
private const val MESSAGE_SUBSCRIBE_STATES_REQUEST = 20
private const val MESSAGE_STATE_RESPONSE = 64
private const val MESSAGE_BINARY_SENSOR_STATE = 21
private const val MESSAGE_PING_REQUEST = 7
private const val MESSAGE_PING_RESPONSE = 8
private const val MESSAGE_DISCONNECT_REQUEST = 5
private const val MESSAGE_DISCONNECT_RESPONSE = 6
private const val MESSAGE_MEDIA_PLAYER_COMMAND_REQUEST = 65
private const val MESSAGE_SENSOR_STATE = 25
private const val MESSAGE_LIST_ENTITIES_CAMERA = 43
private const val MESSAGE_CAMERA_IMAGE_RESPONSE = 44
private const val MESSAGE_CAMERA_IMAGE_REQUEST = 45
private const val MESSAGE_LIST_ENTITIES_NUMBER = 49
private const val MESSAGE_NUMBER_STATE_RESPONSE = 50
private const val MESSAGE_NUMBER_COMMAND_REQUEST = 51
private const val MESSAGE_LIST_ENTITIES_SELECT = 52
private const val MESSAGE_SELECT_STATE_RESPONSE = 53
private const val MESSAGE_SELECT_COMMAND_REQUEST = 54
private const val MESSAGE_LIST_ENTITIES_BUTTON = 61
private const val MESSAGE_BUTTON_COMMAND_REQUEST = 62
private const val MESSAGE_LIST_ENTITIES_SWITCH = 17
private const val MESSAGE_SWITCH_STATE_RESPONSE = 26
private const val MESSAGE_SWITCH_COMMAND_REQUEST = 33

// Shared across every ListEntities*Response (InfoResponseProtoMessage base class):
// object_id, key, and name are always fields 1, 2, 3, regardless of entity type.
private const val F_OBJECT_ID = 1
private const val F_KEY = 2
private const val F_NAME = 3

// Shared across every *StateResponse/*CommandRequest (StateResponseProtoMessage /
// CommandProtoMessage base classes): key is always field 1.
private const val F_ENTITY_KEY = 1

// HelloResponse fields
private const val F_HELLO_API_VERSION_MAJOR = 1
private const val F_HELLO_API_VERSION_MINOR = 2
private const val F_HELLO_SERVER_INFO = 3
private const val F_HELLO_NAME = 4
private const val F_HELLO_REQUEST_CLIENT_INFO = 1 // HelloRequest field 1 — separate message from the response fields above

// DeviceInfoResponse fields
private const val F_DEVICE_INFO_NAME = 2
private const val F_DEVICE_INFO_MAC_ADDRESS = 3
private const val F_DEVICE_INFO_ESPHOME_VERSION = 4
private const val F_DEVICE_INFO_MODEL = 6
private const val F_DEVICE_INFO_MANUFACTURER = 12
private const val F_DEVICE_INFO_FRIENDLY_NAME = 13

// ListEntitiesBinarySensorResponse fields beyond the shared object_id/key/name
private const val F_BINARY_SENSOR_DEVICE_CLASS = 5
private const val F_BINARY_SENSOR_DISABLED_BY_DEFAULT = 7
private const val F_BINARY_SENSOR_ICON = 8
private const val F_BINARY_SENSOR_ENTITY_CATEGORY = 9

// BinarySensorStateResponse
private const val F_BINARY_SENSOR_STATE = 2

// ListEntitiesSensorResponse fields beyond the shared object_id/key/name
private const val F_SENSOR_ICON = 5
private const val F_SENSOR_UNIT = 6
private const val F_SENSOR_ACCURACY_DECIMALS = 7
private const val F_SENSOR_DEVICE_CLASS = 9
private const val F_SENSOR_STATE_CLASS = 10
private const val F_SENSOR_DISABLED_BY_DEFAULT = 12
private const val F_SENSOR_ENTITY_CATEGORY = 13

// SensorStateResponse
private const val F_SENSOR_STATE = 2
private const val F_SENSOR_MISSING_STATE = 3

// ListEntitiesCameraResponse fields beyond the shared object_id/key/name
private const val F_CAMERA_DISABLED_BY_DEFAULT = 5
private const val F_CAMERA_ICON = 6
private const val F_CAMERA_ENTITY_CATEGORY = 7

// CameraImageResponse (server -> client)
private const val F_CAMERA_IMAGE_DATA = 2
private const val F_CAMERA_IMAGE_DONE = 3

// CameraImageRequest (client -> server)
private const val F_CAMERA_REQUEST_SINGLE = 1
private const val F_CAMERA_REQUEST_STREAM = 2

// ListEntitiesNumberResponse fields beyond the shared object_id/key/name
private const val F_NUMBER_ICON = 5
private const val F_NUMBER_MIN_VALUE = 6
private const val F_NUMBER_MAX_VALUE = 7
private const val F_NUMBER_STEP = 8
private const val F_NUMBER_DISABLED_BY_DEFAULT = 9
private const val F_NUMBER_ENTITY_CATEGORY = 10

// NumberStateResponse
private const val F_NUMBER_STATE = 2

// NumberCommandRequest (client -> server)
private const val F_NUMBER_COMMAND_STATE = 2

// ListEntitiesSelectResponse fields beyond the shared object_id/key/name
private const val F_SELECT_ICON = 5
private const val F_SELECT_OPTIONS = 6
private const val F_SELECT_DISABLED_BY_DEFAULT = 7
private const val F_SELECT_ENTITY_CATEGORY = 8

// ListEntitiesButtonResponse fields beyond the shared object_id/key/name
private const val F_BUTTON_ICON = 5
private const val F_BUTTON_DISABLED_BY_DEFAULT = 6
private const val F_BUTTON_ENTITY_CATEGORY = 7
private const val F_BUTTON_DEVICE_CLASS = 8

// ListEntitiesSwitchResponse fields beyond the shared object_id/key/name
private const val F_SWITCH_ICON = 5
private const val F_SWITCH_ASSUMED_STATE = 6
private const val F_SWITCH_DISABLED_BY_DEFAULT = 7
private const val F_SWITCH_ENTITY_CATEGORY = 8
private const val F_SWITCH_DEVICE_CLASS = 9

// SwitchStateResponse
private const val F_SWITCH_STATE = 2

// SwitchCommandRequest (client -> server)
private const val F_SWITCH_COMMAND_STATE = 2

// SelectStateResponse
private const val F_SELECT_STATE = 2
private const val F_SELECT_MISSING_STATE = 3

// SelectCommandRequest (client -> server)
private const val F_SELECT_COMMAND_STATE = 2

// ListEntitiesMediaPlayerResponse fields beyond the shared object_id/key/name
private const val F_MEDIA_PLAYER_ICON = 5
private const val F_MEDIA_PLAYER_DISABLED_BY_DEFAULT = 6
private const val F_MEDIA_PLAYER_ENTITY_CATEGORY = 7
private const val F_MEDIA_PLAYER_SUPPORTS_PAUSE = 8
private const val F_MEDIA_PLAYER_FEATURE_FLAGS = 11

// MediaPlayerStateResponse
private const val F_MEDIA_PLAYER_STATE = 2
private const val F_MEDIA_PLAYER_VOLUME = 3
private const val F_MEDIA_PLAYER_MUTED = 4

// MediaPlayerCommandRequest (client -> server)
private const val F_MPCMD_HAS_COMMAND = 2
private const val F_MPCMD_COMMAND = 3
private const val F_MPCMD_VOLUME = 5
private const val F_MPCMD_MEDIA_URL = 7
private const val F_MPCMD_ANNOUNCEMENT = 9

// Player state constants (MediaPlayerState in api.proto)
private const val STATE_IDLE = 1
private const val STATE_PLAYING = 2
private const val STATE_PAUSED = 3

// is this function is out of place and should be moved lower?
private fun MediaPlayerService.PlaybackState.toWire(): Int = when (this) {
  MediaPlayerService.PlaybackState.IDLE -> STATE_IDLE
  MediaPlayerService.PlaybackState.PLAYING -> STATE_PLAYING
  MediaPlayerService.PlaybackState.PAUSED -> STATE_PAUSED
}

// MediaPlayerCommandRequest command values
private const val COMMAND_PLAY = 0
private const val COMMAND_PAUSE = 1
private const val COMMAND_STOP = 2
private const val COMMAND_MUTE = 3
private const val COMMAND_UNMUTE = 4

// Feature flags (values match HA's own MediaPlayerEntityFeature bitmask, which is what
// ESPHome's feature_flags is interpreted as on the HA side)
private const val FEATURE_PAUSE = 1
private const val FEATURE_VOLUME_SET = 4
private const val FEATURE_VOLUME_MUTE = 8
private const val FEATURE_STOP = 4096
private const val FEATURE_PLAY_MEDIA = 512
private const val FEATURE_PLAY = 16384
private const val FEATURE_MEDIA_ANNOUNCE = 1048576

private inline fun <reified T> Map<Int, Any>.fieldOrNull(key: Int): T? = this[key] as? T





class AESPHome(context: Context, name: String? = null, friendlyName: String? = null, mac: String? = null, val port: Int = 6053) {

  internal val appContext         = context.applicationContext
  val name                        = name         ?: getDeviceHostname(appContext) // DNS-safe, used for mDNS + protocol name
  val friendlyName                = friendlyName ?: getDeviceName(appContext)     // raw device name, shown in HA's UI
  val mac                         = mac          ?: getDeviceMac(appContext)


  //private val mediaPlayer         = MediaPlayerController(appContext)


  private var activeConn: Socket? = null

  // Read by CameraService's idle loop to skip capturing when nobody's listening,
  // without it needing to know what a connection actually is.
  val hasActiveConnection: Boolean get() = activeConn != null

  // Set once a client connects / says hello; cleared together when it disconnects.
  // Read by MainActivity to show who's currently connected.
  @Volatile var connectedClientAddress: String? = null
    private set
  @Volatile var connectedClientName: String? = null
    private set



  // Posts back to the main thread — handleClient() runs on a background socket thread,
  // and onMediaPlayerVolumeChanged below is meant to touch MainActivity's UI.
  private val mainHandler = Handler(Looper.getMainLooper())

  // Optional hook MainActivity registers (onResume, cleared in onPause) to be told
  // immediately when HA changes the media player volume, so the on-screen field reflects
  // it live instead of waiting for the next onResume() poll. Always invoked on the main thread.
  var onMediaPlayerVolumeChanged: ((Float) -> Unit)? = null

  // Let HA know the state of the media player
  private fun notifyMediaPlayerState() = pushToHA(MESSAGE_STATE_RESPONSE, mediaPlayerStatePayload())

  // Generic cache of every sensor's last known value, keyed by Sensor.id
  private val sensorValues = HashMap<String, Float>()

  // Called by MediaPlayerService when playback ends on its own — HA otherwise has no way to know.
  internal fun notifyMediaPlayerIdle() = notifyMediaPlayerState()


  //
  // Used by diagnostics loop (only?) to report sensor values to HA
  //
  fun reportSensor(sensor: Sensor, value: Boolean) = reportSensor(sensor, if (value) 1f else 0f)
  fun reportSensor(sensor: Sensor, value: Float) {
    if (sensor.kind(appContext) is SensorKind.Binary && sensorValues[sensor.id] == value) return // binary: only send on change
    sensorValues[sensor.id] = value
    val (type, payload) = stateMessage(sensor, value)
    pushToHA(type, payload)
  }



  //
  // Called by a SwitchEntity (e.g. BluetoothSwitch) when its underlying state changes
  // on its own, outside of an HA command — same idea as reportSensor, but for Switches.
  //
  fun reportSwitch(switch: SwitchEntity, on: Boolean) {
    val (type, payload) = switchStateMessage(switch, on)
    pushToHA(type, payload)
  }



  //
  // Threaded sender for HA connection
  //
  private fun pushToHA(msgType: Int, payload: ByteArray) {
    val conn = activeConn ?: return
    Thread({
      try {
        send(conn, msgType, payload)
      } catch (e: Exception) {
        Log.e(TAG, "State push failed: ${e.message}", e)
      }
    }, "AESPHomePush").start()
  }



  //
  // Helper functions for socket
  //
  private fun readVarintFromSocket(conn: Socket): Int = decodeVarint { recvExact(conn, 1)[0].toInt() }
  private fun recvExact(conn: Socket, count: Int): ByteArray {
    val data = ByteArray(count)
    var read = 0
    while (read < count) {
      val n = conn.getInputStream().read(data, read, count - read)
      if (n < 0) throw IOException("closed")
      read += n
    }
    return data
  }



  //
  //
  //
  private fun readMessage(conn: Socket): Pair<Int, ByteArray> {
    if (recvExact(conn, 1)[0].toInt() != 0) throw IOException("expected plaintext preamble")
    val length = readVarintFromSocket(conn)
    val msgType = readVarintFromSocket(conn)
    val payload = if (length > 0) recvExact(conn, length) else ByteArray(0)
    return Pair(msgType, payload)
  }



  //
  //
  //
  @Synchronized
  private fun send(conn: Socket, msgType: Int, payload: ByteArray) {
    val frame = byteArrayOf(0) + encodeVarint(payload.size) + encodeVarint(msgType) + payload
    conn.getOutputStream().write(frame)
  }



  //
  //
  //
  private fun mediaPlayerEntityListPayload(): ByteArray {
    val features = FEATURE_PAUSE + FEATURE_VOLUME_SET + FEATURE_VOLUME_MUTE + FEATURE_STOP + FEATURE_PLAY_MEDIA + FEATURE_PLAY + FEATURE_MEDIA_ANNOUNCE
    return ProtobufMessageBuilder()
      .string(F_OBJECT_ID, "media_player")
      .fixed32(F_KEY, MediaPlayerService.key)
      .string(F_NAME, "Media Player")
      .string(F_MEDIA_PLAYER_ICON, MediaPlayerService.icon)
      .varint(F_MEDIA_PLAYER_DISABLED_BY_DEFAULT, if (MediaPlayerService.enabledByDefaultHa) 0 else 1)
      .varint(F_MEDIA_PLAYER_ENTITY_CATEGORY, MediaPlayerService.entityCategory.wireValue)
      .varint(F_MEDIA_PLAYER_SUPPORTS_PAUSE, 1)
      .varint(F_MEDIA_PLAYER_FEATURE_FLAGS, features)
      .build()
  }




  //
  //
  //
  private fun cameraEntityListPayload(): Pair<Int, ByteArray> =
    MESSAGE_LIST_ENTITIES_CAMERA to ProtobufMessageBuilder()
      .string(F_OBJECT_ID, "camera")
      .fixed32(F_KEY, CameraService.key)
      .string(F_NAME, CameraService.label)
      .string(F_CAMERA_ICON, CameraService.icon)
      .varint(F_CAMERA_DISABLED_BY_DEFAULT, if (CameraService.enabledByDefaultHa) 0 else 1)
      .varint(F_CAMERA_ENTITY_CATEGORY, CameraService.entityCategory.wireValue)
      .build()



  // Sends a JPEG to whichever client is currently connected, chunked to CAMERA_CHUNK_SIZE.
  // A no-op if nobody's connected — safe for CameraService to call speculatively (e.g.
  // from its idle loop) without checking hasActiveConnection first.
  fun pushCameraFrame(jpeg: ByteArray) {
    val conn = activeConn ?: return
    val chunkSize = if (CAMERA_CHUNK_SIZE > 0) CAMERA_CHUNK_SIZE else jpeg.size

    var offset = 0
    while (offset < jpeg.size) {
      val end = minOf(offset + chunkSize, jpeg.size)
      val done = end == jpeg.size
      val chunkBytes = if (offset == 0 && done) jpeg else jpeg.copyOfRange(offset, end)

      val chunk = ProtobufMessageBuilder()
        .fixed32(F_ENTITY_KEY, CameraService.key)
        .bytes(F_CAMERA_IMAGE_DATA, chunkBytes)
        .varint(F_CAMERA_IMAGE_DONE, if (done) 1 else 0)
        .build()

      send(conn, MESSAGE_CAMERA_IMAGE_RESPONSE, chunk)
      offset = end
    }
  }



  // Settings belonging to a disabled component (e.g. camera rotation/lens when the
  // camera itself is off) shouldn't be reported either — gate on the owning
  // Toggleable's enabled state, not just each setting's own `homeAssistant` flag.
  private fun visibleSettings(): List<Setting> =
    Sensors.toggleables.filter { isEnabled(appContext, it) }.flatMap { it.settings }.filter { it.homeAssistant }

  private fun visibleSelectSettings(): List<SelectSetting> =
    Sensors.toggleables.filter { isEnabled(appContext, it) }.flatMap { it.selectSettings }.filter { it.homeAssistant }



  //
  //
  //
  private fun numberStateMessage(setting: Setting, value: Float): Pair<Int, ByteArray> =
      MESSAGE_NUMBER_STATE_RESPONSE to ProtobufMessageBuilder()
        .fixed32(F_ENTITY_KEY, setting.key)
        .float(F_NUMBER_STATE, value)
        .build()



  //
  // Forces HA to reconnect so it re-runs Hello -> DeviceInfo -> ListEntities ->
  // SubscribeStates from scratch, picking up any change to which entities exist
  // (e.g. a Sensor/Service toggled on or off) or their capabilities. Sent as a valid,
  // empty DisconnectRequest — reason defaults to UNSPECIFIED. api.proto marks
  // DisconnectRequest as SOURCE_BOTH, so it's valid for the device to send one
  // unprompted, not just in response to one from HA. HA acks with DisconnectResponse
  // and closes its side; our read loop then hits IOException on the closed socket and
  // cleans up via the existing catch/finally in handleClient(). Called from
  // MainActivity whenever a toggle changes.
  //
  fun requestDisconnect() = pushToHA(MESSAGE_DISCONNECT_REQUEST, ByteArray(0))



  //
  // Called from MainActivity when a setting changes on-device, inform HA
  //
  fun reportSetting(setting: Setting, value: Float) {
    if (setting === MediaPlayerService.volumeSetting) {
      MediaPlayerService.setVolume(value)
      // Volume lives on the media_player entity, not as a separate `number` entity in HA
      // (volumeSetting.homeAssistant is false on purpose), so it never reaches HA via the
      // numberStateMessage push below. Push the media player's state directly instead —
      // the same call the HA-command handler already makes to confirm its own changes.
      notifyMediaPlayerState()
      return
    }

    // This setting is not reported to HA
    if (!setting.homeAssistant){
      return
    }

    // Report new setting state to HA
    val (type, payload) = numberStateMessage(setting, value)
    pushToHA(type, payload)
  }



  //
  // Called from MainActivity when a dropdown setting changes on-device, inform HA
  //
  fun reportSelectSetting(setting: SelectSetting, value: String) {
    if (!setting.homeAssistant) return // this setting is not reported to HA
    val (type, payload) = selectStateMessage(setting, value)
    pushToHA(type, payload)
  }



  //
  //
  //
  private fun numberListMessage(setting: Setting): Pair<Int, ByteArray> =
    MESSAGE_LIST_ENTITIES_NUMBER to ProtobufMessageBuilder()
      .string(F_OBJECT_ID, setting.id)
      .fixed32(F_KEY, setting.key)
      .string(F_NAME, setting.label)
      .string(F_NUMBER_ICON, setting.icon)
      .float(F_NUMBER_MIN_VALUE, setting.min)
      .float(F_NUMBER_MAX_VALUE, setting.max)
      .float(F_NUMBER_STEP, setting.step)
      .varint(F_NUMBER_DISABLED_BY_DEFAULT, if (setting.enabledByDefaultHa) 0 else 1)
      .varint(F_NUMBER_ENTITY_CATEGORY, setting.entityCategory.wireValue)
      .build()



  //
  // Select entities render as a real dropdown in HA (unlike Number). `options` is a
  // repeated field — calling .string() once per option under the same field number is
  // exactly what protobuf's wire format expects for "repeated string".
  //
  private fun selectListMessage(setting: SelectSetting): Pair<Int, ByteArray> {
    val builder = ProtobufMessageBuilder()
      .string(F_OBJECT_ID, setting.id)
      .fixed32(F_KEY, setting.key)
      .string(F_NAME, setting.label)
      .string(F_SELECT_ICON, setting.icon)
    for (option in setting.options) builder.string(F_SELECT_OPTIONS, option)
    builder
      .varint(F_SELECT_DISABLED_BY_DEFAULT, if (setting.enabledByDefaultHa) 0 else 1)
      .varint(F_SELECT_ENTITY_CATEGORY, setting.entityCategory.wireValue)
    return MESSAGE_LIST_ENTITIES_SELECT to builder.build()
  }

  private fun selectStateMessage(setting: SelectSetting, value: String): Pair<Int, ByteArray> =
    MESSAGE_SELECT_STATE_RESPONSE to ProtobufMessageBuilder()
      .fixed32(F_ENTITY_KEY, setting.key)
      .string(F_SELECT_STATE, value)
      .varint(F_SELECT_MISSING_STATE, 0)
      .build()



  //
  // Buttons are press-only — no state message exists for them, unlike every other
  // entity type here. HA just calls press() and moves on.
  //
  private fun buttonListMessage(button: Button): Pair<Int, ByteArray> =
    MESSAGE_LIST_ENTITIES_BUTTON to ProtobufMessageBuilder()
      .string(F_OBJECT_ID, button.id)
      .fixed32(F_KEY, button.key)
      .string(F_NAME, button.label)
      .string(F_BUTTON_ICON, button.icon)
      .varint(F_BUTTON_DISABLED_BY_DEFAULT, if (button.enabledByDefaultHa) 0 else 1)
      .varint(F_BUTTON_ENTITY_CATEGORY, button.entityCategory.wireValue)
      .string(F_BUTTON_DEVICE_CLASS, "")
      .build()



  //
  // Switches are the only entity type here that's genuinely two-way: HA can flip them
  // (SwitchCommandRequest) and the underlying state can also change on its own, on the
  // device (see SwitchEntity.start(), which reports that back via reportSwitch()).
  //
  private fun switchListMessage(switch: SwitchEntity): Pair<Int, ByteArray> =
    MESSAGE_LIST_ENTITIES_SWITCH to ProtobufMessageBuilder()
      .string(F_OBJECT_ID, switch.id)
      .fixed32(F_KEY, switch.key)
      .string(F_NAME, switch.label)
      .string(F_SWITCH_ICON, switch.icon)
      .varint(F_SWITCH_ASSUMED_STATE, 0) // false: HA should trust our reported state, not show it as an assumed toggle
      .varint(F_SWITCH_DISABLED_BY_DEFAULT, if (switch.enabledByDefaultHa) 0 else 1)
      .varint(F_SWITCH_ENTITY_CATEGORY, switch.entityCategory.wireValue)
      .string(F_SWITCH_DEVICE_CLASS, "")
      .build()

  private fun switchStateMessage(switch: SwitchEntity, on: Boolean): Pair<Int, ByteArray> =
    MESSAGE_SWITCH_STATE_RESPONSE to ProtobufMessageBuilder()
      .fixed32(F_ENTITY_KEY, switch.key)
      .varint(F_SWITCH_STATE, if (on) 1 else 0)
      .build()



  //
  // Builds the ListEntities*Response (type + payload)
  //
  private fun listMessage(sensor: Sensor): Pair<Int, ByteArray> {
    val base = ProtobufMessageBuilder()
      .string(F_OBJECT_ID, sensor.id)
      .fixed32(F_KEY, sensor.key)
      .string(F_NAME, sensor.label)

    val disabledByDefault = if (sensor.enabledByDefaultHa) 0 else 1

    return when (val kind = sensor.kind(appContext)) {

      is SensorKind.Binary -> {
        val builder = base
          .string(F_BINARY_SENSOR_ICON, sensor.icon)
          .varint(F_BINARY_SENSOR_DISABLED_BY_DEFAULT, disabledByDefault)
          .varint(F_BINARY_SENSOR_ENTITY_CATEGORY, sensor.entityCategory.wireValue)
        if (kind.deviceClass != null) {
          builder.string(F_BINARY_SENSOR_DEVICE_CLASS, kind.deviceClass)
        }
        MESSAGE_LIST_ENTITIES_BINARY_SENSOR to builder.build()
      }

      is SensorKind.Numeric ->
        // state_class=1 (STATE_CLASS_MEASUREMENT); accuracy_decimals left at 0 (default).
        MESSAGE_LIST_ENTITIES_SENSOR to ProtobufMessageBuilder()
          .string(F_OBJECT_ID, sensor.id)
          .fixed32(F_KEY, sensor.key)
          .string(F_NAME, sensor.label)
          .string(F_SENSOR_ICON, sensor.icon)
          .string(F_SENSOR_UNIT, kind.unit)
          .varint(F_SENSOR_ACCURACY_DECIMALS, 0)
          .string(F_SENSOR_DEVICE_CLASS, kind.deviceClass)
          .varint(F_SENSOR_STATE_CLASS, 1)
          .varint(F_SENSOR_DISABLED_BY_DEFAULT, disabledByDefault)
          .varint(F_SENSOR_ENTITY_CATEGORY, sensor.entityCategory.wireValue)
          .build()
    }
  }



  //
  //
  //
  private fun stateMessage(sensor: Sensor, value: Float): Pair<Int, ByteArray> = when (sensor.kind(appContext)) {
    is SensorKind.Binary -> MESSAGE_BINARY_SENSOR_STATE to ProtobufMessageBuilder()
      .fixed32(F_ENTITY_KEY, sensor.key)
      .varint(F_BINARY_SENSOR_STATE, if (value != 0f) 1 else 0)
      .build()
    is SensorKind.Numeric -> MESSAGE_SENSOR_STATE to ProtobufMessageBuilder()
      .fixed32(F_ENTITY_KEY, sensor.key)
      .float(F_SENSOR_STATE, value)
      .varint(F_SENSOR_MISSING_STATE, 0)
      .build()
  }



  //
  //
  //
  private fun mediaPlayerStatePayload(): ByteArray =
    ProtobufMessageBuilder()
      .fixed32(F_ENTITY_KEY, MediaPlayerService.key)
      .varint(F_MEDIA_PLAYER_STATE, MediaPlayerService.getState().toWire())
      .float(F_MEDIA_PLAYER_VOLUME, MediaPlayerService.getVolume())
      .varint(F_MEDIA_PLAYER_MUTED, if (MediaPlayerService.isMuted()) 1 else 0)
      .build()





  //
  //
  //
  private fun handleClient(conn: Socket) {
    activeConn = conn
    connectedClientAddress = conn.inetAddress?.hostAddress
    try {
      while (true) {
        val (msgType, payload) = readMessage(conn)
        when (msgType) {


          //
          // HelloRequest: initial handshake — confirm the API version and report our name.
          // Also records the client's self-reported name (e.g. "Home Assistant 2024.8.0"),
          // shown alongside its IP in MainActivity.
          //
          MESSAGE_HELLO_REQUEST -> {
            connectedClientName = decodeFields(payload).fieldOrNull<ByteArray>(F_HELLO_REQUEST_CLIENT_INFO)?.let { String(it) }
            send(conn, MESSAGE_HELLO_RESPONSE,
              ProtobufMessageBuilder()
                .varint(F_HELLO_API_VERSION_MAJOR, 1)
                .varint(F_HELLO_API_VERSION_MINOR, 9)
                .string(F_HELLO_SERVER_INFO, "aesphome")
                .string(F_HELLO_NAME, name)
                .build())
          }


          //
          // DeviceInfoRequest: static identity info about this device (name, MAC, versions).
          //
          MESSAGE_DEVICE_INFO_REQUEST -> {
            send(conn, MESSAGE_DEVICE_INFO_RESPONSE,
              ProtobufMessageBuilder()
                .string(F_DEVICE_INFO_NAME, name)
                .string(F_DEVICE_INFO_MAC_ADDRESS, mac)
                .string(F_DEVICE_INFO_ESPHOME_VERSION, BuildConfig.VERSION_NAME)
                .string(F_DEVICE_INFO_MODEL, "Android Simulating ESPHome Device")
                .string(F_DEVICE_INFO_MANUFACTURER, "ÆSPHome")
                .string(F_DEVICE_INFO_FRIENDLY_NAME, friendlyName)
                .build())
          }


          //
          // ListEntitiesRequest: enumerate every entity this device exposes — media player,
          // camera, each Sensor, and every HA-facing Setting — ending with ListEntitiesDone.
          //
          MESSAGE_LIST_ENTITIES_REQUEST -> { 
            if (isEnabled(appContext, MediaPlayerService)){
              send(conn, MESSAGE_LIST_ENTITIES_RESPONSE, mediaPlayerEntityListPayload())
            }
            if (isEnabled(appContext, CameraService)) {
              val (type, bytes) = cameraEntityListPayload()
              send(conn, type, bytes)
            }
            for (sensor in Sensors.all.filter { isEnabled(appContext, it) }) {
              val (type, bytes) = listMessage(sensor)
              send(conn, type, bytes)
            }
            for (setting in visibleSettings()) {
              val (type, bytes) = numberListMessage(setting)
              send(conn, type, bytes)
            }
            for (setting in visibleSelectSettings()) {
              val (type, bytes) = selectListMessage(setting)
              send(conn, type, bytes)
            }
            for (button in Sensors.buttons.filter { isEnabled(appContext, it) }) {
              val (type, bytes) = buttonListMessage(button)
              send(conn, type, bytes)
            }
            for (switch in Sensors.switches.filter { isEnabled(appContext, it) }) {
              val (type, bytes) = switchListMessage(switch)
              send(conn, type, bytes)
            }
            send(conn, MESSAGE_LIST_ENTITIES_DONE, ByteArray(0))
          }


          //
          // SubscribeStatesRequest: send the current value of every entity once — further
          // updates arrive later via reportSensor/reportSetting/notifyMediaPlayerState as they happen.
          //
          MESSAGE_SUBSCRIBE_STATES_REQUEST -> {
            if (isEnabled(appContext, MediaPlayerService)){
              send(conn, MESSAGE_STATE_RESPONSE, mediaPlayerStatePayload())
            }
            for (sensor in Sensors.all.filter { isEnabled(appContext, it) }) {
              val value = sensorValues[sensor.id] ?: 0f
              val (type, bytes) = stateMessage(sensor, value)
              send(conn, type, bytes)
            }
            for (setting in visibleSettings()) {
              val (type, bytes) = numberStateMessage(setting, getSetting(appContext, setting))
              send(conn, type, bytes)
            }
            for (setting in visibleSelectSettings()) {
              val (type, bytes) = selectStateMessage(setting, getSelectSetting(appContext, setting))
              send(conn, type, bytes)
            }
            for (switch in Sensors.switches.filter { isEnabled(appContext, it) }) {
              val (type, bytes) = switchStateMessage(switch, switch.isOn(appContext))
              send(conn, type, bytes)
            }
            // HA has now finished its handshake and is listening — wake CameraService's
            // idle loop (same mechanism idleFpsSetting's onChanged uses) so it sends an
            // idle still right away instead of waiting out the rest of the current interval.
            CameraService.interruptIdleLoop()
          }


          //
          // PingRequest: keep-alive — just echo back an empty PingResponse.
          //
          MESSAGE_PING_REQUEST -> send(conn, MESSAGE_PING_RESPONSE, ByteArray(0))


          //
          // DisconnectRequest: HA is ending the connection cleanly — acknowledge and stop.
          //
          MESSAGE_DISCONNECT_REQUEST -> {
            send(conn, MESSAGE_DISCONNECT_RESPONSE, ByteArray(0))
            return
          }


          //
          // MediaPlayerCommandRequest: play/pause/stop/volume/media_url from HA's media_player
          // entity. Always confirm the resulting state back so HA's UI reflects it immediately.
          //
          MESSAGE_MEDIA_PLAYER_COMMAND_REQUEST -> {
            if (isEnabled(appContext, MediaPlayerService)) {
              val fields = decodeFields(payload)

              val isAnnouncement = (fields.fieldOrNull<Int>(F_MPCMD_ANNOUNCEMENT) ?: 0) != 0
              fields.fieldOrNull<ByteArray>(F_MPCMD_MEDIA_URL)?.let { MediaPlayerService.play(String(it), isAnnouncement) }
              fields.fieldOrNull<ByteArray>(F_MPCMD_VOLUME)?.let {
                val vol = bytesToFloat(it)
                MediaPlayerService.setVolume(vol)
                setSetting(appContext, MediaPlayerService.volumeSetting, vol)
                mainHandler.post { onMediaPlayerVolumeChanged?.invoke(vol) }
              }

              if (fields.containsKey(F_MPCMD_HAS_COMMAND)) {
                when (fields.fieldOrNull<Int>(F_MPCMD_COMMAND) ?: COMMAND_PLAY) {
                  COMMAND_PAUSE -> MediaPlayerService.pause()  // no-op unless currently playing
                  COMMAND_PLAY -> MediaPlayerService.resume()  // no-op unless currently paused
                  COMMAND_STOP -> MediaPlayerService.stopPlayback()
                  COMMAND_MUTE -> MediaPlayerService.mute()
                  COMMAND_UNMUTE -> MediaPlayerService.unmute()
                }
              }

              send(conn, MESSAGE_STATE_RESPONSE, mediaPlayerStatePayload())
            }
          }


          //
          // CameraImageRequest: single=one-shot capture; stream=true starts (or refreshes) a
          // continuous stream that stops itself once HA stops re-requesting it.
          //
          MESSAGE_CAMERA_IMAGE_REQUEST -> {
            if (isEnabled(appContext, CameraService)) {
              val stream = (decodeFields(payload).fieldOrNull<Int>(F_CAMERA_REQUEST_STREAM) ?: 0) != 0
              CameraService.onImageRequest(appContext, stream)
            }
          }


          //
          // NumberCommandRequest: HA changed one of our exposed Settings — persist it and
          // confirm the new value back. The run{} block's early returns only abandon this one
          // case (unknown key/malformed value), not the whole connection.
          //
          MESSAGE_NUMBER_COMMAND_REQUEST -> run {
            val fields = decodeFields(payload)
            val key = fields.fieldOrNull<ByteArray>(F_ENTITY_KEY)?.let(::bytesToInt) ?: return@run
            val value = fields.fieldOrNull<ByteArray>(F_NUMBER_COMMAND_STATE)?.let(::bytesToFloat) ?: return@run
            val setting = Sensors.toggleables.flatMap { it.settings }.find { it.key == key } ?: return@run
            val clamped = setSetting(appContext, setting, value)
            val (type, bytes) = numberStateMessage(setting, clamped)
            send(conn, type, bytes)
          }


          //
          // SelectCommandRequest: HA changed one of our exposed dropdown Settings (e.g.
          // camera rotation) — persist it and confirm the new value back. Same pattern
          // as NumberCommandRequest, but the value is a string that must be one of the
          // entity's fixed `options` rather than any float in range. setSelectSetting
          // fires the setting's own onChanged hook (e.g. CameraService's lens/rotation/
          // resolution settings stop an active stream there) — nothing camera-specific
          // needs to happen here.
          //
          // If the setting is a command dropdown (onCommand != null, e.g. BluetoothCommandService)
          // the picked value is never persisted — it just fires onCommand, then the entity is
          // reported straight back to its default so it resets in HA after every use.
          //
          MESSAGE_SELECT_COMMAND_REQUEST -> run {
            val fields = decodeFields(payload)
            val key = fields.fieldOrNull<ByteArray>(F_ENTITY_KEY)?.let(::bytesToInt) ?: return@run
            val value = fields.fieldOrNull<ByteArray>(F_SELECT_COMMAND_STATE)?.let { String(it) } ?: return@run
            val setting = Sensors.toggleables.flatMap { it.selectSettings }.find { it.key == key } ?: return@run
            if (value !in setting.options) return@run
            val onCommand = setting.onCommand
            val reported = if (onCommand != null) {
              onCommand(appContext, value)
              setting.default
            } else {
              setSelectSetting(appContext, setting, value)
              value
            }
            val (type, bytes) = selectStateMessage(setting, reported)
            send(conn, type, bytes)
          }


          //
          // ButtonCommandRequest: HA pressed one of our exposed Button entities. Buttons
          // are press-only — there's no value to persist and nothing to report back.
          //
          MESSAGE_BUTTON_COMMAND_REQUEST -> run {
            val fields = decodeFields(payload)
            val key = fields.fieldOrNull<ByteArray>(F_ENTITY_KEY)?.let(::bytesToInt) ?: return@run
            val button = Sensors.buttons.find { it.key == key } ?: return@run
            button.press(appContext)
          }


          //
          // SwitchCommandRequest: HA flipped one of our exposed Switch entities. Applies
          // the change, then reports back whatever the switch's state actually is right
          // now — not just an echo of what was requested, since enabling/disabling
          // Bluetooth is asynchronous (and can fail outright on Android 13+). The
          // BroadcastReceiver each SwitchEntity registers in start() sends the real
          // final state shortly after, once the OS actually finishes the transition.
          //
          MESSAGE_SWITCH_COMMAND_REQUEST -> run {
            val fields = decodeFields(payload)
            val key = fields.fieldOrNull<ByteArray>(F_ENTITY_KEY)?.let(::bytesToInt) ?: return@run
            val on = (fields.fieldOrNull<Int>(F_SWITCH_COMMAND_STATE) ?: 0) != 0
            val switch = Sensors.switches.find { it.key == key } ?: return@run
            switch.setOn(appContext, on)
            val (type, bytes) = switchStateMessage(switch, switch.isOn(appContext))
            send(conn, type, bytes)
          }

        // end when
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Client handler error", e)
    } finally {
      MediaPlayerService.stopPlayback()
      CameraService.stopStreamNow()
      activeConn = null
      connectedClientAddress = null
      connectedClientName = null
      conn.close()
    }
  }



  //
  // Report some sensors every 60 seconds in thread
  //
  private fun diagnosticsLoop() {
    while (true) {
      for (sensor in Sensors.readSensors) {
        if (isEnabled(appContext, sensor)) {
          reportSensor(sensor, sensor.read(appContext))
        }
      }
      Thread.sleep(60000)
    }
  }



  // One connection at a time
  fun start() {
    val server = ServerSocket(port)
    Thread({ diagnosticsLoop() }, "AESPHomeDiagnostics").start()
    while (true) {
      val conn = server.accept()
      val clientThread = Thread({ handleClient(conn) }, "AESPHomeClient")
      clientThread.start()
      clientThread.join()
    }
  }
}
