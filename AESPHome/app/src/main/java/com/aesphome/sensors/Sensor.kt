package com.aesphome

import android.content.Context

/*
The metadata every settings-toggleable component carries so nothing else in the app
needs a hardcoded reference to a specific one — the UI, settings, and AESPHome's
entity dispatch all just iterate Sensors.toggleables / .eventSensors / .readSensors
/ .services.
*/

// Matches EntityCategory in api.proto. NONE lets HA place the entity by its own type
// (sensor/binary_sensor -> Sensors, number/media_player/camera/switch -> Controls) —
// CONFIG and DIAGNOSTIC override that into their own dedicated sections.
enum class EntityCategory(val wireValue: Int) {
  NONE(0),
  CONFIG(1),
  DIAGNOSTIC(2),
}

// Anything with a persisted on/off setting and a switch in the UI. Sensor and
// Service both carry an ESPHome entity or background feature on top of this.
interface Toggleable {
  val id: String            // stable id: settings key (and HA object_id, for Sensor)
  val label: String         // UI switch text (and HA entity name, for Sensor)
  val description: String get() = "" // optional one-line blurb shown under the switch in the app; empty shows nothing
  val enabledByDefaultApp: Boolean   // whether this app enables/reports it by default
  val enabledByDefaultHa: Boolean get() = true // whether HA's registry starts it enabled by default
  val entityCategory: EntityCategory get() = EntityCategory.NONE
  val icon: String get() = "" // MDI icon override, e.g. "mdi:battery"; empty lets HA pick its own default
  val settings: List<Setting> get() = emptyList()
  val selectSettings: List<SelectSetting> get() = emptyList()
}

sealed class SensorKind {
  // Maps to ListEntitiesBinarySensorResponse/BinarySensorStateResponse.
  data class Binary(val deviceClass: String? = null) : SensorKind()

  // Maps to ListEntitiesSensorResponse/SensorStateResponse.
  data class Numeric(val unit: String, val deviceClass: String) : SensorKind()
}

// A configurable numeric value belonging to a Sensor, exposed as a real ESPHome Number
// entity when homeAssistant is true (id/key/label/min/max/step map straight onto
// ListEntitiesNumberResponse). deviceUi and homeAssistant are independent: a setting can
// show in the app only, HA only, both, or neither (code-only, changed by editing `default`).
class Setting(
    val id: String,   // persistence key, and HA object_id when exposed
    val label: String,
    val default: Float,
    val min: Float,
    val max: Float,
    val step: Float,
    val deviceUi: Boolean,
    val homeAssistant: Boolean,
    val entityCategory: EntityCategory = EntityCategory.NONE,
    val enabledByDefaultHa: Boolean = true,
    val icon: String = "", // MDI icon override, e.g. "mdi:volume-high"; empty lets HA pick its own default
    val onChanged: ((Context) -> Unit)? = null, // fires after every persisted change, from setSetting()
) {
  val key: Int = id.hashCode() // ESPHome wire-protocol entity key — derived so it never needs manual tracking
}

// A fixed-choice setting belonging to a Sensor/Service, exposed as a real ESPHome Select
// entity when homeAssistant is true (id/key/label/options map onto
// ListEntitiesSelectResponse) — HA shows it as an actual dropdown, unlike Setting's Number
// entity. deviceUi and homeAssistant are independent, same as Setting.
class SelectSetting(
    val id: String,   // persistence key, and HA object_id when exposed
    val label: String,
    options: List<String>,
    val default: String,
    val deviceUi: Boolean,
    val homeAssistant: Boolean,
    val entityCategory: EntityCategory = EntityCategory.NONE,
    val enabledByDefaultHa: Boolean = true,
    val icon: String = "", // MDI icon override, e.g. "mdi:camera-switch"; empty lets HA pick its own default
    // If set, this is a command dropdown rather than a persisted setting (e.g. "Connect
    // <device>"): picking an option fires this instead of being saved, and the entity is
    // immediately reported back as `default` so it resets in HA after every use.
    val onCommand: ((Context, String) -> Unit)? = null,
    // If set, called after a new value is persisted (device UI or HA command alike) — e.g.
    // CameraService's lens/rotation/resolution dropdowns use this to invalidate a running
    // stream. Not called on the onCommand path above, since nothing is persisted there.
    val onChanged: ((Context) -> Unit)? = null,
) {
  init { require(default in options) { "SelectSetting '$id': default '$default' is not one of $options" } }

  // Mutable: most SelectSettings have a fixed option list for life, but some (e.g. CameraService's
  // lens picker) only know their real choices once a Context is available, and refresh it in place.
  var options: List<String> = options
  val key: Int = id.hashCode() // ESPHome wire-protocol entity key — derived so it never needs manual tracking
}

// A Toggleable that shows up to Home Assistant as an ESPHome entity.
interface Sensor : Toggleable {
  val key: Int               // ESPHome wire-protocol entity key — must be unique per device
  fun kind(context: Context): SensorKind // a function (not a fixed val) so it can depend on a live Setting/SelectSetting, e.g. a unit choice
}

// A stateless, press-only action exposed to HA as an ESPHome Button entity — unlike
// Sensor, it has no ongoing value to report, just a one-shot action run when HA
// sends a ButtonCommandRequest for it.
interface Button : Toggleable {
  val key: Int               // ESPHome wire-protocol entity key — must be unique per device
  fun press(context: Context)
}

// An on/off control exposed to HA as a real ESPHome Switch entity — unlike Setting's
// Number or SelectSetting's dropdown, it's a two-way boolean: HA flipping it calls
// setOn(), and start()/stop() (Startable) let it listen for the underlying state
// changing on its own (e.g. Bluetooth toggled from Android's quick settings) and
// report that back, the same way EventSensor does for a Sensor. Named SwitchEntity,
// not Switch, since MainActivity already uses android.widget.Switch unqualified.
interface SwitchEntity : Toggleable, Startable {
  val key: Int               // ESPHome wire-protocol entity key — must be unique per device
  fun isOn(context: Context): Boolean
  fun setOn(context: Context, on: Boolean)
}

// A component with a start()/stop() lifecycle, independent of what it toggles (an
// ESPHome entity, for EventSensor; nothing, for Service). Lets callers like MainActivity
// treat both the same way instead of branching on each type separately.
interface Startable {
  fun start(context: Context)
  fun stop(context: Context)
}

// A sensor whose value only changes on a real OS event (touch, movement, screen on/off).
// start()/stop() control whether it's actively listening at all.
interface EventSensor : Sensor, Startable

// A sensor with no natural event — polled on a timer by AESPHome instead.
interface ReadSensor : Sensor {
  fun read(context: Context): Float
}

// A Toggleable background feature with no ESPHome entity of its own — nothing for
// Home Assistant to see, just a setting plus a start()/stop() lifecycle. mDNS
// advertisement and the media player's playback lifecycle are Services, not Sensors.
interface Service : Toggleable, Startable

object Sensors {

  val eventSensors: List<EventSensor> = listOf(
    BatteryPercent,
    TouchSensor,
    DeviceMovementSensor,
    ScreenStateSensor,
    BatteryCharging,
    CameraLuxSensor,
    LightSensor,
    DecibelMeterSensor,
    DeviceOrientationSensor
  )

  val readSensors: List<ReadSensor> = listOf(
    WifiRssiSensor,
    BatteryTemperatureC
  )

  val services: List<Service> = listOf(
    MdnsService,
    MediaPlayerService,
    CameraService,
    SystemVolumeService,
    BluetoothCommandService
  )
  

  val buttons: List<Button> = listOf(IdentifyButton)
  val switches: List<SwitchEntity> = listOf(BluetoothSwitch)
  val all: List<Sensor> = eventSensors + readSensors
  val toggleables: List<Toggleable> = all + services + buttons + switches
}
