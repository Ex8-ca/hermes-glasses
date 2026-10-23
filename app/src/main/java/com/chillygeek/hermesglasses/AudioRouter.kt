package com.chillygeek.hermesglasses

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Picks the microphone and the speaker, and captures mic audio.
 *
 * ## Why this exists
 *
 * On glasses firmware 129, opening a Bluetooth SCO (hands-free) route **to the
 * glasses** makes the glasses treat it as an incoming phone call, and they end the
 * DAT camera session within ~0.1-0.5s. Meta confirms this is a glasses-software
 * bug with no SDK-side fix (meta-wearables-dat-android #206, #130).
 *
 * The workaround used by every mature project in this space is the same: **keep the
 * hands-free link away from the glasses**. Use the phone's own mic, or a pair of
 * Bluetooth earbuds/headphones, and let the glasses do nothing but carry the camera
 * (and the HUD, on Display models).
 *
 * That is strictly better than the in-band PCM path it replaces:
 *  - the mic is beamformed/normal quality instead of un-processed in-band audio,
 *  - the camera link is freed of the extra radio load,
 *  - it needs no experimental SDK feature, so the app stays publishable.
 */
class AudioRouter(
  private val context: Context,
  private val scope: CoroutineScope,
) {

  companion object {
    private const val TAG = "AudioRouter"
    private const val SAMPLE_RATE = 16000
    private const val CHANNEL_MASK = AudioFormat.CHANNEL_IN_MONO
    private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
  }

  /** Where the microphone audio comes from. */
  enum class MicSource {
    /** A paired Bluetooth headset — the best option, and the one that avoids the firmware bug. */
    HEADSET,

    /** The phone's own microphone. Always available. */
    PHONE,

    /** The glasses' microphone via the DAT in-band PCM stream. Experimental, not beamformed. */
    GLASSES,
  }

  /** Where spoken replies are played. */
  enum class OutputSink {
    /** A paired Bluetooth headset or earbuds. */
    HEADSET,

    /** The glasses' speakers over A2DP. */
    GLASSES,

    /** The phone's speaker. */
    PHONE,
  }

  private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

  private var record: AudioRecord? = null
  private var captureJob: Job? = null

  /** The paired hands-free (SCO) device, if any. */
  fun headsetScoDevice(): AudioDeviceInfo? =
      audioManager
          .getDevices(AudioManager.GET_DEVICES_INPUTS)
          .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }

  /** The paired media (A2DP) device, if any. */
  fun headsetA2dpDevice(): AudioDeviceInfo? =
      audioManager
          .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
          .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }

  /**
   * The output device for spoken replies.
   *
   * A headset is preferred over the glasses because A2DP to the glasses shares
   * the same Bluetooth link the camera stream uses, and on tight links the SDK
   * drops resolution to compensate.
   */
  fun preferredOutputDevice(sink: OutputSink): AudioDeviceInfo? =
      when (sink) {
        OutputSink.HEADSET -> headsetA2dpDevice()
        OutputSink.GLASSES ->
            audioManager
                .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
        OutputSink.PHONE -> null
      }

  /**
   * Start capturing microphone audio from [source].
   *
   * [onChunk] receives raw 16-bit PCM at 16 kHz mono. Capture from the GLASSES
   * source is not handled here — that audio arrives through the DAT stream, not
   * through Android's audio stack.
   */
  fun startCapture(source: MicSource, onChunk: (ByteArray) -> Unit) {
    stopCapture()
    if (source == MicSource.GLASSES) {
      // Glasses mic comes in-band over the DAT stream; the controller feeds it.
      return
    }

    val scoDevice = if (source == MicSource.HEADSET) headsetScoDevice() else null
    if (source == MicSource.HEADSET && scoDevice == null) {
      Log.w(TAG, "no SCO headset found — falling back to the phone mic")
    }

    try {
      val minBuffer =
          AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_MASK, ENCODING).coerceAtLeast(2048)
      val rec =
          AudioRecord.Builder()
              .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
              .setAudioFormat(
                  AudioFormat.Builder()
                      .setSampleRate(SAMPLE_RATE)
                      .setChannelMask(CHANNEL_MASK)
                      .setEncoding(ENCODING)
                      .build()
              )
              .setBufferSizeInBytes(minBuffer * 2)
              .build()

      // Pin the route: the headset's SCO link for a headset, otherwise the phone's
      // own mic. VOICE_RECOGNITION rather than VOICE_COMMUNICATION, because the
      // wideband SCO profile reserves more radio than narrowband.
      scoDevice?.let { rec.preferredDevice = it }
      if (scoDevice != null) {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.setCommunicationDevice(scoDevice)
      }

      rec.startRecording()
      record = rec
      Log.i(TAG, "capture started: source=$source sco=${scoDevice?.productName}")

      captureJob =
          scope.launch {
            val buffer = ByteArray(minBuffer)
            val shortBuf = ShortArray(minBuffer / 2)
            while (true) {
              val read = rec.read(buffer, 0, buffer.size)
              if (read <= 0) continue
              // Feed little-endian PCM out of the recorder's native order.
              ByteBuffer.wrap(buffer, 0, read)
                  .order(ByteOrder.LITTLE_ENDIAN)
                  .asShortBuffer()
                  .get(shortBuf, 0, read / 2)
              val out = ByteArray(read)
              ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(shortBuf, 0, read / 2)
              onChunk(out)
            }
          }
    } catch (e: Exception) {
      Log.e(TAG, "capture failed: ${e.message}", e)
      stopCapture()
    }
  }

  fun stopCapture() {
    captureJob?.cancel()
    captureJob = null
    try {
      record?.stop()
    } catch (_: Exception) {}
    try {
      record?.release()
    } catch (_: Exception) {}
    record = null
    // Release the communication route so the camera link is not sharing radio.
    if (audioManager.mode == AudioManager.MODE_IN_COMMUNICATION) {
      try {
        audioManager.clearCommunicationDevice()
      } catch (_: Exception) {}
      audioManager.mode = AudioManager.MODE_NORMAL
    }
  }
}
