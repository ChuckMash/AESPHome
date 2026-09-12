package com.aesphome

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer


/*

  Media Player

    TODO
      * Review and cleanup
*/


object MediaPlayerService : Service {
  override val id                  = "media_player"
  val key: Int                     = id.hashCode()
  override val label               = "Media Player"
  override val description         = ""
  override val enabledByDefaultApp = true
  override val enabledByDefaultHa  = true
  override val entityCategory      = EntityCategory.NONE
  override val icon                = "mdi:play-circle"

  val volumeSetting = Setting(
    id                 = "media_player_volume",
    label              = "Volume",
    default            = 1.0f,
    min                = 0.0f,
    max                = 1.0f,
    step               = 0.01f,
    deviceUi           = true,
    homeAssistant      = false,
    entityCategory     = EntityCategory.NONE,
    enabledByDefaultHa = true,
    icon               = "mdi:volume-high")

  override val settings = listOf(volumeSetting)



  enum class PlaybackState {
    IDLE,
    PLAYING,
    PAUSED,
  }



  private lateinit var libVLC: LibVLC
  private lateinit var player: MediaPlayer
  @Volatile private var currentState = PlaybackState.IDLE
  @Volatile private var currentVolume = 1.0f
  @Volatile private var muted = false

  // The URL from the last real (non-announcement) play() — what an announcement hands
  // playback back to once it ends.
  @Volatile private var currentUrl: String? = null

  // Set only while an announcement is ducking real playback: what to resume, and (if the
  // ducked media was seekable — a stream generally isn't) where to resume it from.
  @Volatile private var resumeUrl: String? = null
  @Volatile private var resumePositionMs: Long? = null
  @Volatile private var pendingSeekMs: Long? = null
  @Volatile private var isAnnouncement = false

  // libVLC's event listener fires on its own internal thread, and calling back into the
  // player synchronously from inside it (e.g. stop()/play() below) can itself trigger a
  // second, nested end-event before the first one is done handling — which would otherwise
  // re-run the resume logic against already-cleared state and stomp the just-resumed
  // playback back to idle. mainHandler + playGeneration together neutralize that: end-event
  // handling always happens as a distinct, serialized post on the main thread, and any event
  // that arrives for media that's since been superseded by a newer play() is ignored.
  private val mainHandler = Handler(Looper.getMainLooper())
  @Volatile private var playGeneration = 0



  override fun start(context: Context) {
    libVLC = LibVLC(context.applicationContext, arrayListOf())
    player = MediaPlayer(libVLC)
    player.setEventListener { event ->
      when (event.type) {

        MediaPlayer.Event.Playing -> {
          player.volume = if (muted) 0 else (currentVolume * 100).toInt()
          // Only set right after play() ducks-and-resumes into a seekable position; consumed once.
          pendingSeekMs?.let {
            if (player.isSeekable()) player.setTime(it)
            pendingSeekMs = null
          }
        }

        MediaPlayer.Event.EndReached, MediaPlayer.Event.Stopped, MediaPlayer.Event.EncounteredError -> {
          val generation = playGeneration
          val eventType = event.type
          mainHandler.post { handlePlaybackEnded(eventType, generation) }
        }
      }
    }
    setVolume(getSetting(context, volumeSetting))
  }

  override fun stop(context: Context) = stopPlayback()

  // Runs on the main thread, one event at a time — see the mainHandler/playGeneration comment
  // above for why this is split out of the event listener instead of handled inline there.
  private fun handlePlaybackEnded(eventType: Int, generation: Int) {
    if (generation != playGeneration) return // stale event for media that's already been replaced

    val toResume = resumeUrl
    val resumeAt = resumePositionMs
    val wasAnnouncement = isAnnouncement
    isAnnouncement = false
    resumeUrl = null
    resumePositionMs = null

    if (wasAnnouncement && toResume != null) {
      // This was an announcement ducking real playback — hand it back. If the ducked media
      // wasn't seekable (e.g. a live stream), there's nothing to resume to a position from,
      // so it just restarts from the top.
      play(toResume, announcement = false, resumeAtMs = resumeAt)
    } else {
      currentState = PlaybackState.IDLE
      AESPHomeService.instance?.notifyMediaPlayerIdle()
      Log.i(TAG, "Playback ended or stopped")

      if (eventType == MediaPlayer.Event.EndReached) {
        player.stop()
      }

    }

  }

  // announcement=true ducks whatever's currently playing — remembering it (and its position,
  // if seekable) — instead of replacing it outright, then hands playback back once the
  // announcement ends. Matches HA's MediaPlayerCommandRequest.announcement semantics (used for
  // TTS/assist responses that shouldn't just clobber whatever the user was listening to). A
  // plain play() (the default) always behaves as a normal replace, same as before, and also
  // cancels any announcement resume that might have been pending. resumeAtMs is internal —
  // only handlePlaybackEnded passes it, to seek a resumed track back to where it was ducked.
  fun play(url: String, announcement: Boolean = false, resumeAtMs: Long? = null) {
    try {
      if (announcement) {
        if (currentState == PlaybackState.PLAYING) {
          resumeUrl = currentUrl
          resumePositionMs = if (player.isSeekable()) player.getTime() else null
        }
        isAnnouncement = true
      } else {
        resumeUrl = null
        resumePositionMs = null
        isAnnouncement = false
        currentUrl = url
      }
      pendingSeekMs = resumeAtMs

      playGeneration++ // marks this as a new generation, so stale end-events for whatever was playing before are ignored
      val media = Media(libVLC, Uri.parse(url))
      player.media = media
      media.release()
      player.play()
      currentState = PlaybackState.PLAYING
      Log.i(TAG, if (announcement) "Started announcement: $url" else "Started playback: $url")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to play media: $url", e)
    }
  }

  fun pause() {
    if (currentState == PlaybackState.PLAYING) {
      player.pause()
      currentState = PlaybackState.PAUSED
      Log.i(TAG, "Playback paused")
    }
  }

  fun resume() {
    if (currentState == PlaybackState.PAUSED) {
      player.play()
      currentState = PlaybackState.PLAYING
      Log.i(TAG, "Playback resumed")
    }
  }

  fun stopPlayback() {
    if (::player.isInitialized) player.stop()
    currentState = PlaybackState.IDLE
    resumeUrl = null
    resumePositionMs = null
    pendingSeekMs = null
    isAnnouncement = false
    Log.i(TAG, "Playback stopped")
  }

  fun setVolume(volume: Float) {
    currentVolume = volume.coerceIn(0.0f, 1.0f)
    muted = false // adjusting the volume clears mute, same as a normal remote/slider
    if (::player.isInitialized) player.volume = (currentVolume * 100).toInt()
    Log.i(TAG, "Volume set to: $currentVolume")
  }

  fun getVolume(): Float = currentVolume

  fun mute() {
    muted = true
    if (::player.isInitialized) player.volume = 0
    Log.i(TAG, "Muted")
  }

  fun unmute() {
    muted = false
    if (::player.isInitialized) player.volume = (currentVolume * 100).toInt()
    Log.i(TAG, "Unmuted")
  }

  fun isMuted(): Boolean = muted

  fun getState(): PlaybackState = currentState
}
