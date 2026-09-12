package com.aesphome

import android.app.Activity
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView

const val TAG = "AESPHome"
private const val CONNECTION_INFO_REFRESH_MS = 2000L

class AESPHomeService : Service() {

  companion object {
    var instance: AESPHome? = null
  }

  override fun onCreate() {
    super.onCreate()

    val channelId = "aesphome_channel"
    val manager = getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(
        NotificationChannel(channelId, "ÆSPHome", NotificationManager.IMPORTANCE_LOW)
    )
    val notification = Notification.Builder(this, channelId)
        .setContentTitle("ÆSPHome")
        .setContentText("Running in the background.")
        .setSmallIcon(android.R.drawable.ic_media_play)
        .build()
    startForeground(1, notification)

    // AESPHome.start() blocks forever (accept loop), so it needs its own thread.
    // Startup happens here too, after `instance` is set, so anything that reports
    // immediately (like ScreenStateSensor) can't race ahead of it being assigned.
    // Nothing is named directly — anything Startable in the registry (EventSensor,
    // Service, SwitchEntity, ...) starts the same way, so a new one needs no changes here.
    Thread({
      val aesphome = AESPHome(this)
      instance = aesphome

      for (component in Sensors.toggleables) {
        if (component is Startable && isEnabled(applicationContext, component)) {
          component.start(applicationContext)
        }
      }

      aesphome.start()
    }, "AESPHomeInit").start()
  }

  override fun onDestroy() {
    super.onDestroy()
    for (component in Sensors.toggleables) {
      if (component is Startable) component.stop(applicationContext)
    }
  }

  override fun onBind(intent: Intent?): IBinder? = null
}

class BootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    context.startForegroundService(Intent(context, AESPHomeService::class.java))
  }
}

class MainActivity : Activity() {
  private val settingInputs = mutableListOf<Pair<Setting, EditText>>()
  private val selectSettingSpinners = mutableListOf<Pair<SelectSetting, Spinner>>()
  private var wifiIpText: TextView? = null
  private var haStatusText: TextView? = null

  // Reposts itself every CONNECTION_INFO_REFRESH_MS so the Wi-Fi/HA lines stay live
  // the whole time this screen is on-screen, not just when it's first opened.
  private val refreshHandler = Handler(Looper.getMainLooper())
  private val refreshRunnable = object : Runnable {
    override fun run() {
      refreshConnectionInfo()
      refreshHandler.postDelayed(this, CONNECTION_INFO_REFRESH_MS)
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // MainActivity can be the very first thing to touch Sensors.selectSettings (e.g. first
    // launch, before AESPHomeService has run CameraService.start()), so refresh this here
    // too rather than assuming the service already did it.
    CameraService.refreshLensOptions(this)
    BluetoothCommandService.refreshOptions(this)

    val layout = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(16), dp(16), dp(16), dp(16))
    }

    val statusText = TextView(this)
    statusText.text = "ÆSPHome is running in the background."
    statusText.setPadding(0, 0, 0, dp(16))
    layout.addView(statusText)

    val wifiIpText = TextView(this)
    wifiIpText.setPadding(0, 0, 0, dp(4))
    layout.addView(wifiIpText)

    val haStatusText = TextView(this)
    haStatusText.setPadding(0, 0, 0, dp(16))
    layout.addView(haStatusText)

    this.wifiIpText = wifiIpText
    this.haStatusText = haStatusText
    refreshConnectionInfo()

    // One group per component: its enable switch immediately followed by that same
    // component's own settings/select-settings, indented underneath it, with a divider
    // before the next group — so it's obvious at a glance which options belong to which
    // toggle, instead of three unrelated flat lists. Sorted alphabetically by label so
    // the order doesn't depend on registration order in Sensors. Still built entirely
    // from registry metadata: a new Sensor/Service/Setting needs no changes here to show
    // up correctly grouped.
    val groups = Sensors.toggleables.sortedBy { it.label }
    for ((index, component) in groups.withIndex()) {
      val switch = Switch(this)
      //switch.text = "Enable ${component.label}"
      switch.text = "${component.label}"
      switch.isChecked = isEnabled(this, component)

      val header = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
      header.addView(switch)
      if (component.description.isNotEmpty()) {
        header.addView(TextView(this).apply {
          text = component.description
          textSize = 12f
          setTextColor(Color.GRAY)
          setPadding(dp(4), 0, 0, 0)
        })
      }
      layout.addView(header)

      val childRows = component.settings.filter { it.deviceUi }.map { settingRow(it) } +
                      component.selectSettings.filter { it.deviceUi }.map { selectSettingRow(it) }
      // Hidden rather than greyed out while the toggle is off — its settings don't do
      // anything until it's back on, so there's nothing useful to show in the meantime.
      childRows.forEach { layout.addView(it); it.visibility = if (switch.isChecked) View.VISIBLE else View.GONE }

      switch.setOnCheckedChangeListener { _, checked ->
        setEnabled(this, component, checked)
        if (component is Startable) {
          if (checked) component.start(applicationContext) else component.stop(applicationContext)
        }
        childRows.forEach { it.visibility = if (checked) View.VISIBLE else View.GONE }
      }

      if (index != groups.lastIndex) layout.addView(divider())
    }

    layout.addView(divider())

    // Entity-affecting changes (toggles, lens/resolution picks, etc.) only take effect in
    // HA once it reconnects and re-enumerates entities. Left as an explicit action rather
    // than firing on every change, so several changes can be made before paying for it.
    val refreshButton = Button(this)
    refreshButton.text = "Refresh Changes / Force Reconnect"
    refreshButton.layoutParams = LinearLayout.LayoutParams(
      LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(8) }
    refreshButton.setOnClickListener {
      // Both device lists (paired Bluetooth devices, camera lenses) can change while
      // the app is running, so re-gather them here rather than only at start()/launch
      // — otherwise this button would just re-send the same stale options.
      for ((component, refresh) in listOf<Pair<Toggleable, () -> Unit>>(
        BluetoothCommandService to { BluetoothCommandService.refreshOptions(applicationContext) },
        CameraService to { CameraService.refreshLensOptions(applicationContext) },
      )) {
        if (isEnabled(applicationContext, component)) refresh()
      }
      AESPHomeService.instance?.requestDisconnect()
    }
    layout.addView(refreshButton)

    val shutdownButton = Button(this)
    shutdownButton.text = "Shutdown ÆSPHome"
    shutdownButton.layoutParams = LinearLayout.LayoutParams(
      LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(8) }
    shutdownButton.setOnClickListener {
      AlertDialog.Builder(this)
        .setTitle("Shutdown ÆSPHome?")
        .setMessage("This disconnects Home Assistant and stops all background monitoring until you reopen the app.")
        .setPositiveButton("Shutdown") { _, _ -> shutdown() }
        .setNegativeButton("Cancel", null)
        .show()
    }
    layout.addView(shutdownButton)

    setContentView(ScrollView(this).apply { addView(layout) })

    startForegroundService(Intent(this, AESPHomeService::class.java))
  }

  // Tells HA we're going away, tears down the service (which stops every Toggleable —
  // Bluetooth receivers, camera, media player, etc.), then kills the process outright.
  // A plain stopService() isn't enough on its own: AESPHome.start()'s accept loop and its
  // diagnosticsLoop() are bare background threads with no cancellation hook, so they'd
  // keep running past onDestroy() if the process weren't ended too. BootReceiver only
  // relaunches on device boot / package replace, so this stays down until reopened by hand.
  private fun shutdown() {
    AESPHomeService.instance?.requestDisconnect()
    // requestDisconnect() sends asynchronously on its own thread — give it a moment to
    // actually reach HA before the connection is torn down from under it.
    refreshHandler.postDelayed({
      stopService(Intent(this, AESPHomeService::class.java))
      finishAndRemoveTask()
      android.os.Process.killProcess(android.os.Process.myPid())
    }, 300)
  }

  private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

  // Shows this device's own Wi-Fi IP, and — if Home Assistant is currently connected —
  // its IP and the client name it reported at handshake (e.g. "Home Assistant 2024.8.0").
  private fun refreshConnectionInfo() {
    wifiIpText?.text = "Wi-Fi IP: ${getWifiIpAddress() ?: "not connected"}"

    val ha = AESPHomeService.instance
    val haAddress = ha?.connectedClientAddress
    haStatusText?.text = if (haAddress != null) {
      "Home Assistant: $haAddress (${ha.connectedClientName ?: "unknown"})"
    } else {
      "Home Assistant: not connected"
    }
  }

  // A thin line separating one component's group from the next.
  private fun divider(): View = View(this).apply {
    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
      topMargin = dp(12)
      bottomMargin = dp(12)
    }
    setBackgroundColor(Color.LTGRAY)
  }

  // One indented label + numeric input for a Setting, wired to persist on commit and
  // push the new value to HA.
  private fun settingRow(setting: Setting): View {
    val row = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(32), dp(4), 0, dp(4))
    }

    val label = TextView(this)
    label.text = setting.label
    row.addView(label)

    val input = EditText(this)
    input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
    input.imeOptions = EditorInfo.IME_ACTION_DONE
    input.setText(getSetting(this, setting).toString())

    fun commit() {
      val value = input.text.toString().toFloatOrNull() ?: return
      val clamped = setSetting(this, setting, value)
      AESPHomeService.instance?.reportSetting(setting, clamped)
    }

    // The keyboard's Done action is the primary way to finish editing; losing focus
    // (e.g. tapping elsewhere) still commits too, as a safety net.
    input.setOnEditorActionListener { _, actionId, _ ->
      if (actionId != EditorInfo.IME_ACTION_DONE) return@setOnEditorActionListener false
      commit()
      input.clearFocus()
      true
    }
    input.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commit() }

    row.addView(input)
    settingInputs.add(setting to input)
    return row
  }

  // One indented label + dropdown for a SelectSetting — same pattern as settingRow, just
  // a fixed set of choices instead of free entry.
  private fun selectSettingRow(setting: SelectSetting): View {
    val row = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(32), dp(4), 0, dp(4))
    }

    val label = TextView(this)
    label.text = setting.label
    row.addView(label)

    val spinner = Spinner(this)
    spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, setting.options)
    spinner.setSelection(setting.options.indexOf(getSelectSetting(this, setting)).coerceAtLeast(0))
    spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
      override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
        val value = setting.options[position]
        setSelectSetting(this@MainActivity, setting, value)
        AESPHomeService.instance?.reportSelectSetting(setting, value)
      }
      override fun onNothingSelected(parent: AdapterView<*>?) {}
    }

    row.addView(spinner)
    selectSettingSpinners.add(setting to spinner)
    return row
  }

  // Re-reads every setting from storage whenever this screen becomes visible again, so a
  // value HA changed while the app was merely backgrounded (not recreated) still shows up
  // — onCreate() only runs once and won't otherwise notice a change made outside the app.
  // Skips any field the user is actively typing in.
  override fun onResume() {
    super.onResume()
    refreshHandler.post(refreshRunnable) // keeps reposting itself every CONNECTION_INFO_REFRESH_MS
    for ((setting, input) in settingInputs) {
      if (!input.hasFocus()) input.setText(getSetting(this, setting).toString())
    }
    for ((setting, spinner) in selectSettingSpinners) {
      spinner.setSelection(setting.options.indexOf(getSelectSetting(this, setting)).coerceAtLeast(0))
    }

    // Live-update the volume field the moment HA changes it, instead of only on the
    // next onResume() poll.
    AESPHomeService.instance?.onMediaPlayerVolumeChanged = { volume ->
      settingInputs.firstOrNull { it.first === MediaPlayerService.volumeSetting }?.second?.let { input ->
        if (!input.hasFocus()) input.setText(volume.toString())
      }
    }
  }

  // Stops the repeating refresh while the screen isn't visible — no point polling for
  // something nobody can see, and it'd otherwise run forever in the background.
  override fun onPause() {
    super.onPause()
    refreshHandler.removeCallbacks(refreshRunnable)
    AESPHomeService.instance?.onMediaPlayerVolumeChanged = null
  }
}
