package com.aesphome

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress


/*

  MDNS service
    Makes device auto-discoverable by Home Assistant

*/


object MdnsService : Service {
  override val id                  = "mdns"
  override val label               = "mDNS"
  override val description         = ""
  override val enabledByDefaultApp = true
  override val enabledByDefaultHa  = false // nothing to expose anyway
  override val entityCategory      = EntityCategory.NONE
  override val icon                = "" // no HA entity to show an icon on

  @Volatile private var running = false

  override fun start(context: Context) {
    val aesphome = AESPHomeService.instance ?: return
    running = true
    Thread({ aesphome.mdnsAnnounceLoop { running } }, "AESPHomeMDNS").start()
  }

  override fun stop(context: Context) {
    running = false
  }
}



fun getWifiNetwork(context: Context): Network? {
  val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
  for (network in cm.allNetworks) {
    val caps = cm.getNetworkCapabilities(network) ?: continue
    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return network
  }
  return null
}

private fun AESPHome.getLocalIp(): String {
  val socket = DatagramSocket()
  try {
    val wifi = getWifiNetwork(appContext) ?: throw IOException("No Wi-Fi network available")
    wifi.bindSocket(socket) // force WiFi — never fall through to whatever Android picks by default
    socket.connect(InetAddress.getByName("8.8.8.8"), 80) // doees not actually send
    return socket.localAddress?.hostAddress ?: "0.0.0.0"
  } finally {
    socket.close()
  }
}

internal fun AESPHome.mdnsAnnounceLoop(isRunning: () -> Boolean) {
  while (isRunning()) {
    try {
      val nameBytes = byteArrayOf(name.length.toByte()) + name.toByteArray()
      val inst = nameBytes + "\u000b_esphomelib\u0004_tcp\u0005local\u0000".toByteArray(Charsets.ISO_8859_1)
      val host = nameBytes + "\u0005local\u0000".toByteArray(Charsets.ISO_8859_1)
      val macText = "mac=$mac".toByteArray()
      val ip = InetAddress.getByName(getLocalIp()).address // throws if there's no network route yet
      val portBytes = byteArrayOf((port shr 8).toByte(), (port and 0xFF).toByte())
      val srv = byteArrayOf(0, 0, 0, 0) + portBytes + host

      var packet = byteArrayOf(0, 0, -124, 0, 0, 0, 0, 4, 0, 0, 0, 0)
      packet += "\u000b_esphomelib\u0004_tcp\u0005local\u0000\u0000\u000c\u0000\u0001\u0000\u0000\u0000\u0078".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0, inst.size.toByte()) + inst
      packet += inst + byteArrayOf(0, 0x21, 0, 1, 0, 0, 0, 0x78) + byteArrayOf(0, srv.size.toByte()) + srv
      packet += inst + byteArrayOf(0, 0x10, 0, 1, 0, 0, 0, 0x78) + byteArrayOf(0, (macText.size + 1).toByte()) + byteArrayOf(macText.size.toByte()) + macText
      packet += host + byteArrayOf(0, 1, 0, 1, 0, 0, 0, 0x78, 0, 4) + ip

      val sock = DatagramSocket()
      try {
        val wifi = getWifiNetwork(appContext) ?: throw IOException("No Wi-Fi network available")
        wifi.bindSocket(sock) // force WiFi as the outgoing interface — never fall through to default routing
        val addr = InetSocketAddress("224.0.0.251", 5353)
        while (isRunning()) {
          sock.send(DatagramPacket(packet, packet.size, addr))
          Thread.sleep(10000)
        }
      } finally {
        sock.close()
      }
    } catch (e: Exception) {
      Log.e(TAG, "mDNS announce failed, retrying: ${e.message}")
      Thread.sleep(5000)
    }
  }
}
