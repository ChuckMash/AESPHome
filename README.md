
# ÆSPHome
 <img width="438" height="320" alt="AESPHome 1" src="https://github.com/user-attachments/assets/d10a55dd-af17-4ce7-b510-bed4276df1ca" />

 
 Android Simulating ESPHome Device for use with Home Assistant

Featuring 
---
* Media Player
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


<img width="688" height="1700" alt="AESPHome" src="https://github.com/user-attachments/assets/b2931eb8-96e8-4022-b0ad-f40d0cd5cbc9" />


