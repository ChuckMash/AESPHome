package com.aesphome

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

/*

  Bluetooth Commands
    TODO:
      review and rewrite
*/

private const val NO_COMMAND = "Select a command"
private const val CONNECT_PREFIX = "Connect "
private const val DISCONNECT_PREFIX = "Disconnect "

private val PROFILES = intArrayOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)

object BluetoothCommandService : Service {
  override val id                  = "bluetooth_commands"
  override val label               = "Bluetooth Commands"
  override val description         = "May require Nearby Devices Permissions"
  override val enabledByDefaultApp = false
  override val enabledByDefaultHa  = true
  override val icon                = "mdi:bluetooth-audio"

  val commandSetting = SelectSetting(
      id                 = "bluetooth_command",
      label              = "Bluetooth Command",
      options            = listOf(NO_COMMAND), // placeholder — replaced by refreshOptions() with real paired devices
      default            = NO_COMMAND,
      deviceUi           = false,
      homeAssistant      = true,
      enabledByDefaultHa = false,
      icon               = "mdi:bluetooth",
      onCommand          = ::runCommand,
  )

  override val selectSettings: List<SelectSetting> = listOf(commandSetting)

  override fun start(context: Context) = refreshOptions(context)
  override fun stop(context: Context) {}

  fun refreshOptions(context: Context) {
    val devices = pairedDevices(context) ?: return
    val names   = devices.map { it.name ?: it.address }.sorted()
    commandSetting.options = listOf(NO_COMMAND) + names.flatMap { listOf(CONNECT_PREFIX + it, DISCONNECT_PREFIX + it) }
  }

  private fun pairedDevices(context: Context): Set<BluetoothDevice>? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
      Log.e(TAG, "BLUETOOTH_CONNECT permission not granted"); return null
    }
    val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return null
    if (!adapter.isEnabled) return emptySet()
    return try { adapter.bondedDevices } catch (e: SecurityException) {
      Log.e(TAG, "Reading paired devices failed", e); null
    }
  }

  private fun runCommand(context: Context, selection: String) {
    val connecting = when {
      selection.startsWith(CONNECT_PREFIX) -> true
      selection.startsWith(DISCONNECT_PREFIX) -> false
      else -> return // NO_COMMAND, or an option that no longer exists — nothing to do
    }
    val name = selection.removePrefix(if (connecting) CONNECT_PREFIX else DISCONNECT_PREFIX)
    val device = pairedDevices(context)?.find { (it.name ?: it.address) == name } ?: run {
      Log.e(TAG, "No paired device named '$name'"); return
    }
    val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return
    for (profileId in PROFILES) {
      adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
          try {
            proxy.javaClass.getMethod(if (connecting) "connect" else "disconnect", BluetoothDevice::class.java)
                .invoke(proxy, device)
          } catch (e: Exception) {
            // Expected for profiles this device doesn't support — only worth noting, not acting on.
            Log.i(TAG, "${if (connecting) "Connect" else "Disconnect"} via profile $profile not applicable for $name")
          } finally {
            adapter.closeProfileProxy(profile, proxy)
          }
        }
        override fun onServiceDisconnected(profile: Int) {}
      }, profileId)
    }
  }
}
