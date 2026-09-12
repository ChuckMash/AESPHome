
# ÆSPHome
<img width="438" height="320" alt="AESPHome 1" src="https://github.com/user-attachments/assets/e618842e-ada1-4117-b1f5-fca8add50a47" />

 
 Android Simulating ESPHome Device for use with Home Assistant
 
---
Work in Progress
---
Featuring 
---
* Media Player
* Bluetooth Speaker
* Camera
* Other Stuff!

Does it work?
---
Yes. sorta.

Is it ESPHome?
---
No. sorta.

What is it?
---
It's an Android app that looks to Home Assistant like an ESPHome device.

Do I need to install anything?
---
Just the app on an old Android device.
Home Assistant detects it automatically through the ESPHome integration.

You do not need to install anything to Home Assistant.

What about...
---
[FAQ](FAQ.md)

---

Features
---
* Controls
  * Enable / Disable Bluetooth
    * May required "Nearby Devices" / Bluetooth permissions
  * Connect / Disconnect to paired Bluetooth Speakers
    * Trigger a connection or disconnection from a known Bluetooth Speaker
    * May required "Nearby Devices" / Bluetooth permissions
  * Media Player
    * Backed by VLC library
    * Audio Only at the moment
  * System Volume
    * Adjust the system volume
 * Sensors
   * Ambient Noise in dB
     * Estimate ambient sound levels with microphone
     * Requires Microphone Permissions
   * Camera
      * Stills
      * Streaming Video
      * Requires Camera Permissions
   * Device Movement
      * Is the device at rest, or moving
   * Device Orientation
      * Is the device oriented at 0°, 90°, 180°, 270°
   * LUX (Camera)
       * LUX estimated from camera still shots
   * LUX (Sensor)
      * LUX reported from devices light sensor (if exists)
   * Screen On
     * Is the screen currently on or off
   * Screen Touch
     * Is the screen being used at this moment.
     * Requires Accessibility Service enabled

 * Configuration 
   * Camera resolution (per lens)
   * Camera rotation (per lens)
   * Camera Effect
     * Effects supported by the camera platform. E.g. Mono / Negative / Solarize / etc
   * Camera JPEG Quality
     * A 1-100 sliding scale of quality. 1 is lowest. 100 is highest.
   * Camera idle update
     * How often selected camera lens should send a still
   * Camera Lens
     * Select which camera lens should be considered this devices Camera at this time
   * LUX Sensor Report Interval
     * How often to send an idle LUX sensor update
   * LUX Sensor Report Threshold
     * How large of a LUX change should be reported immediately outside of the the report interval
   * Movement Reset Time
     * The time it take to reset after "Device Movement" is triggered
   * Movement Sensitivity
     * How sensitive the "Device Movement" sensor is, lower is more sensitive. Down to 0.01
   * Screen Touch Reset Time
     * The time it takes to reset after "Screen Touch" is triggered

 * Diagnostic
   * Battery Charging
     * Is the device charging
   * Battery Percent
     * The percent of battery charged
   * Battery Temperature
     * The temperature of the battery
   * Identify
     * When pressed will trigger a short audible "beep beep" from the device
   * WiFi RSSI
     * That thing you leave disabled




---


<img width="343" height="1901" alt="Screenshot from 2026-09-11 21-47-10" src="https://github.com/user-attachments/assets/c7e0e06b-13c4-4114-81b1-af4dddc3f853" />


