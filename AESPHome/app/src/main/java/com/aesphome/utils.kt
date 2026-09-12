package com.aesphome

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections



// Regex pattern for DNS sanitization
private val DNS_LABEL_PATTERN = Regex("-+")



fun bytesToFloat(bytes: ByteArray): Float = Float.fromBits(bytesToInt(bytes))



fun bytesToInt(bytes: ByteArray):   Int =(bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8) or ((bytes[2].toInt() and 0xFF) shl 16) or ((bytes[3].toInt() and 0xFF) shl 24)



fun getDeviceName(context: Context): String {
  val name = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
  return name ?: Build.MODEL // falls back to the hardware model if no user-set device name exists
}



fun sanitizeForDns(text: String): String {
  val cleaned = text.map { c -> if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-') c else '-' }.joinToString("")
  val collapsed = cleaned.replace(DNS_LABEL_PATTERN, "-").trim('-')
  val truncated = collapsed.take(63) // DNS label length limit
  return if (truncated.isEmpty()) "android-device" else truncated
}



fun getDeviceHostname(context: Context): String {
  try {
    // net.hostname is the actual network hostname Android's own stack uses (e.g. for DHCP),
    // distinct from the user-facing device name. Not a public API, so this is best-effort.
    val systemProperties = Class.forName("android.os.SystemProperties")
    val get = systemProperties.getMethod("get", String::class.java)
    val hostname = get.invoke(null, "net.hostname") as? String
    if (!hostname.isNullOrBlank()) return hostname
  } catch (e: Exception) {
    // net.hostname isn't readable on this device/Android version — fall through below
  }
  // Fall back to the display device name, made DNS-safe (it can contain spaces, e.g. "LGE LM-T600").
  return sanitizeForDns(getDeviceName(context))
}



// Android ID formatted as a MAC
fun getDeviceMac(context: Context): String {
  val id = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "000000000000"
  return id.padStart(12, '0').takeLast(12).chunked(2).joinToString(":").uppercase()
}



// Same wlan0-scoped lookup as getDeviceMac, just returning the IPv4 address instead.
fun getWifiIpAddress(): String? {
  try {
    for (iface in Collections.list(NetworkInterface.getNetworkInterfaces())) {
      if (!iface.name.equals("wlan0", ignoreCase = true)) continue
      for (addr in Collections.list(iface.inetAddresses)) {
        if (addr is Inet4Address) return addr.hostAddress
      }
    }
  } catch (e: Exception) {
    // fall through — no wlan0 address available (e.g. Wi-Fi is off)
  }
  return null
}
