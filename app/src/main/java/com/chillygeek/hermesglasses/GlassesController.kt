package com.chillygeek.hermesglasses

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.removeCamera
import com.meta.wearable.dat.camera.types.AudioCodec
import com.meta.wearable.dat.camera.types.AudioSampleRate
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.selectors.DeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.RegistrationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

/**
 * Owns the Meta Wearables DAT session, the camera stream, and in-band audio
 * capture.
 *
 * ## Why in-band audio instead of HFP/SCO
 *
 * On glasses firmware 129, opening a Bluetooth SCO (HFP) route makes the glasses
 * treat it as an incoming phone call and they **end the DAT session** within
 * 0.1-0.5s. Every later start is refused with START_ERROR_DEVICE_UNAVAILABLE
 * until the glasses reboot. Meta confirms this is a glasses-software bug with
 * no SDK-side fix (github issue #206).
 *
 * The only mic path that survives alongside a camera session on 129 is
 * **in-band PCM**: `StreamConfiguration(audioCodec = AudioCodec.PCM(...))`
 * delivers microphone audio inside the video stream without touching either
 * Bluetooth profile. Trade-off: in-band audio is NOT beamformed, so ambient
 * noise is much louder than a headset mic gives you.
 *
 * Speech *output* is unaffected — that is A2DP, an output-only profile, and it
 * coexists with the camera session.
 *
 * ## Other firmware-129 landmines handled here
 *
 *  - `startRegistration()` must NOT be called while already REGISTERED: on
 *    Android that silently resets Developer Mode for the rest of the process
 *    and every subsequent session is refused.
 *  - Keep the session open for the whole session; the SDK will not reconnect on
 *    its own if the link drops with no session active.
 *  - `Wearables.reset()` is a one-shot escape hatch, not a retry loop.
 */
class GlassesController(
  private val context: Context,
  private val scope: CoroutineScope,
) {
  companion object {
    private const val TAG = "GlassesController"
  }

  sealed interface State {
    data object Idle : State
    data object Registering : State
    data object Ready : State
    data class Streaming(val frames: Int) : State
    data class Failed(val reason: String) : State
  }

  private val _state = MutableStateFlow<State>(State.Idle)
  val state: StateFlow<State> = _state.asStateFlow()

  private var session: DeviceSession? = null
  private var camera: Camera? = null
  private var stream: Stream? = null
  private var sessionJob: Job? = null
  private var audioJob: Job? = null
  private var videoJob: Job? = null

  // AutoDeviceSelector's constructor calls Wearables.getInstance(), which
  // THROWS WearablesException if the SDK has not been initialized yet. It must
  // therefore be lazy — a field initializer would run during construction,
  // before initialize() has been called, and crash the app on launch.
  private val deviceSelector: DeviceSelector by lazy { AutoDeviceSelector() }
  private val decoder = HevcJpegDecoder()

  // ------------------------------------------------------------------ registration

  /**
   * Register with the glasses. Safe to call repeatedly — it is a no-op when
   * already registered, which is what keeps Developer Mode alive.
   */
  fun register(activity: Activity) {
    // Guard against the SDK not being initialized yet (permissions may still
    // be pending). Calling into Wearables before initialize() would throw.
    if (!datReady) {
      Log.w(TAG, "register() called before DAT was initialized")
      return
    }
    scope.launch {
      Wearables.registrationState.collect { regState ->
        if (regState == RegistrationState.REGISTERED) {
          _state.value = State.Ready
        }
      }
    }
    _state.value = State.Registering
    scope.launch {
      // ONLY call startRegistration when not already registered — calling it
      // again resets isDevMode and every session afterwards is refused.
      val current = Wearables.registrationState.value
      if (current != RegistrationState.REGISTERED) {
        Wearables.startRegistration(activity)
      }
    }
  }

  /** Set once the DAT SDK has been initialized (permissions granted). */
  @Volatile var datReady = false

  fun unregister(activity: Activity) {
    if (!datReady) {
      Log.w(TAG, "unregister() called before DAT was initialized")
      return
    }
    scope.launch { Wearables.startUnregistration(activity) }
  }

  // ------------------------------------------------------------------ session

  /** Open a DAT session and start the camera + in-band audio stream. */
  fun startStream(includeAudio: Boolean = true) {
    if (!datReady) {
      Log.w(TAG, "startStream() called before DAT was initialized")
      _state.value = State.Failed("DAT SDK not initialized yet")
      return
    }
    scope.launch {
      try {
        if (session == null) {
          val created = Wearables.createSession(deviceSelector)
          val newSession = created.getOrNull()
          if (newSession == null) {
            created.onFailure { error, _ ->
              Log.e(TAG, "createSession failed: ${error.description}")
              _state.value = State.Failed(error.description)
            }
            return@launch
          }
          session = newSession

          sessionJob =
              scope.launch {
                newSession.state.collect { s ->
                  when (s) {
                    DeviceSessionState.STARTED -> attachCamera(includeAudio)
                    DeviceSessionState.STOPPED,
                    DeviceSessionState.STOPPING -> _state.value = State.Idle
                    else -> Unit
                  }
                }
              }
          newSession.start()
        } else {
          attachCamera(includeAudio)
        }
      } catch (e: Exception) {
        Log.e(TAG, "startStream failed: ${e.message}", e)
        _state.value = State.Failed(e.message ?: "unknown error")
      }
    }
  }

  /**
   * Attach the camera capability and start streaming.
   *
   * The audio codec is what makes the mic work on firmware 129 — see the class
   * comment. Leaving audioCodec null streams video only.
   */
  private suspend fun attachCamera(includeAudio: Boolean) {
    val current = session ?: return
    val config =
        StreamConfiguration(
            audioCodec =
                if (includeAudio) {
                  AudioCodec.PCM(
                      sampleRate = AudioSampleRate.RATE_16000,
                      numberOfChannels = 1,
                  )
                } else {
                  null
                },
            videoQuality = VideoQuality.MEDIUM,
            frameRate = 24,
            compressVideo = true,
        )

    current.addCamera(config)
        .onSuccess { cam ->
          camera = cam
          val s = cam.stream
          stream = s
          collectAudio(s)
          collectVideo(s)
          s.start()
              .onFailure { error, _ ->
                Log.e(TAG, "stream start failed: ${error.description}")
                _state.value = State.Failed(error.description)
              }
              .onSuccess { _state.value = State.Streaming(0) }
        }
        .onFailure { error, _ ->
          Log.e(TAG, "addCamera failed: ${error.description}")
          _state.value = State.Failed(error.description)
        }
  }

  private fun collectAudio(s: Stream) {
    audioJob?.cancel()
    audioJob =
        scope.launch {
          s.audioStream.collect { frame ->
            // Raw PCM from the glasses mic, 16 kHz mono. The splitter decides
            // when an utterance has ended and emits it as WAV.
            val bytes = ByteArray(frame.buffer.remaining())
            frame.buffer.get(bytes)
            onAudioChunk(bytes)
          }
        }
  }

  private fun collectVideo(s: Stream) {
    videoJob?.cancel()
    videoJob =
        scope.launch {
          var count = 0
          s.videoStream.collect { frame ->
            count++
            if (count % 10 == 0) {
              _state.value = State.Streaming(count)
              // Decoding every frame is wasteful, so keep only the most recent
              // one and re-encode it to JPEG on demand.
              decodeToJpeg(frame)?.let { onJpegFrame(it) }
            }
          }
        }
  }

  /** Hooks the activity replaces to drive the conversation. */
  var onAudioChunk: (ByteArray) -> Unit = {}
  var onJpegFrame: (ByteArray) -> Unit = {}

  fun stopStream() {
    audioJob?.cancel()
    videoJob?.cancel()
    audioJob = null
    videoJob = null
    scope.launch {
      try {
        stream?.stop()
        session?.removeCamera()
      } catch (e: Exception) {
        Log.w(TAG, "stopStream: ${e.message}")
      }
      stream = null
      camera = null
      _state.value = State.Ready
    }
  }

  fun closeSession() {
    stopStream()
    sessionJob?.cancel()
    sessionJob = null
    scope.launch {
      try {
        session?.stop()
      } catch (e: Exception) {
        Log.w(TAG, "closeSession: ${e.message}")
      }
      session = null
      _state.value = State.Idle
    }
  }

  /**
   * One-shot recovery for a session the glasses are refusing.
   *
   * `reset()` does not fully clear the old connection in 1.0, so repeated calls
   * stack up. Use it once, then fall back to restarting the glasses.
   */
  fun resetSdk() {
    scope.launch {
      try {
        Wearables.reset()
        Wearables.initialize(context)
        Log.i(TAG, "SDK reset; re-register if needed")
      } catch (e: Exception) {
        Log.e(TAG, "reset failed: ${e.message}")
      }
    }
  }

  // ------------------------------------------------------------------ decoding

  private fun decodeToJpeg(frame: VideoFrame): ByteArray? {
    return try {
      if (!frame.isCompressed || frame.isCodecConfig) return null
      if (frame.width <= 0 || frame.height <= 0) return null
      val data = ByteArray(frame.buffer.remaining())
      frame.buffer.get(data)
      decoder.decode(data, frame.width, frame.height, frame.presentationTimeUs)
      decoder.latestJpeg()
    } catch (e: Exception) {
      Log.w(TAG, "frame decode failed: ${e.message}")
      null
    }
  }

  /**
   * Minimal HEVC decoder: feeds NAL units to MediaCodec and renders to an
   * ImageReader so the most recent frame can be read back as a Bitmap.
   *
   * Hardware HEVC decoders that corrupt this stream are skipped, the same way
   * Meta's own sample does.
   */
  private class HevcJpegDecoder {
    private var codec: MediaCodec? = null
    private var reader: ImageReader? = null
    private var width = 0
    private var height = 0
    private var configured = false

    @Volatile var latest: Bitmap? = null

    fun decode(data: ByteArray, w: Int, h: Int, pts: Long) {
      if (w != width || h != height) {
        width = w
        height = h
        release()
      }
      if (!configured) {
        val codecName = pickCodec()
        if (codecName == null) {
          Log.w(TAG, "no usable HEVC decoder")
          return
        }
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, w, h)
        val imageReader = ImageReader.newInstance(w, h, android.graphics.PixelFormat.RGBA_8888, 2)
        imageReader.setOnImageAvailableListener(
            { r ->
              val image = r.acquireLatestImage()
              if (image != null) {
                try {
                  val plane = image.planes[0]
                  val buffer = plane.buffer
                  val rowStride = plane.rowStride
                  val pixel = ByteArray(4)
                  val pixels = IntArray(w * h)
                  for (y in 0 until h) {
                    for (x in 0 until w) {
                      val idx = y * rowStride + x * 4
                      buffer.position(idx)
                      buffer.get(pixel, 0, 4)
                      pixels[y * w + x] =
                          (pixel[0].toInt() and 0xFF) or
                              ((pixel[1].toInt() and 0xFF) shl 8) or
                              ((pixel[2].toInt() and 0xFF) shl 16) or
                              ((pixel[3].toInt() and 0xFF) shl 24)
                    }
                  }
                  val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                  bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
                  latest = bitmap
                } catch (e: Exception) {
                  Log.w(TAG, "readback failed: ${e.message}")
                } finally {
                  image.close()
                }
              }
            },
            null,
        )
        codec =
            try {
              MediaCodec.createByCodecName(codecName).apply {
                configure(format, imageReader.surface, null, 0)
                start()
              }
            } catch (e: Exception) {
              Log.e(TAG, "codec configure failed: ${e.message}")
              null
            }
        reader = imageReader
        configured = codec != null
        if (!configured) return
      }

      val c = codec ?: return
      try {
        val index = c.dequeueInputBuffer(10_000)
        if (index >= 0) {
          val buf = c.getInputBuffer(index)
          if (buf != null) {
            buf.clear()
            buf.put(data)
            c.queueInputBuffer(index, 0, data.size, pts, 0)
          }
        }
        val info = MediaCodec.BufferInfo()
        while (true) {
          val outIndex = c.dequeueOutputBuffer(info, 0)
          if (outIndex < 0) break
          c.releaseOutputBuffer(outIndex, true)
        }
      } catch (e: Exception) {
        Log.w(TAG, "decode pump failed: ${e.message}")
      }
    }

    fun latestJpeg(): ByteArray? {
      val bmp = latest ?: return null
      val out = ByteArrayOutputStream()
      bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
      return out.toByteArray()
    }

    private fun pickCodec(): String? {
      val blocked = setOf("c2.mtk.hevc.decoder", "OMX.Exynos.hevc.dec")
      val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
      return infos
          .firstOrNull {
            !it.isEncoder &&
                it.supportedTypes.any { t -> t.contains("hevc") } &&
                blocked.none { b -> it.name.equals(b, true) }
          }
          ?.name
    }

    fun release() {
      try {
        codec?.stop()
      } catch (_: Exception) {}
      codec?.release()
      codec = null
      reader?.close()
      reader = null
      configured = false
      latest = null
    }
  }
}
