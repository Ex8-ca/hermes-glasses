package com.chillygeek.hermesglasses

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Plays synthesized speech out through the glasses or a headset.
 *
 * The glasses are a Bluetooth A2DP sink, so playback is routed by setting the
 * media output device to the paired A2DP device. This is the *output* half and
 * is independent of the microphone capture path — on firmware 129 the two are
 * mutually exclusive only when the mic is routed over HFP/SCO *to the glasses*,
 * which is why the camera session can use in-band PCM audio instead (see
 * GlassesController).
 *
 * The output sink is selectable: a paired headset is preferred when you want to
 * keep the camera link free (see [AudioRouter]).
 */
class SpeechPlayer(
  private val context: Context,
  private val router: AudioRouter? = null,
) {

  companion object {
    private const val TAG = "SpeechPlayer"
  }

  private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
  private var player: MediaPlayer? = null

  /** Play a WAV byte array. Returns when playback is queued (not when it ends). */
  @Synchronized
  fun play(wav: ByteArray, sink: AudioRouter.OutputSink = AudioRouter.OutputSink.GLASSES) {
    if (wav.isEmpty()) return
    try {
      stop()
      val tmp = File.createTempFile("speech", ".wav", context.cacheDir)
      FileOutputStream(tmp).use { it.write(wav) }

      val mp =
          MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            setDataSource(tmp.absolutePath)
            prepare()
          }
      // Route to the chosen sink.
      selectOutputDevice(mp, sink)
      mp.start()
      player = mp
      mp.setOnCompletionListener {
        synchronized(this@SpeechPlayer) {
          it.release()
          if (player === it) player = null
        }
        tmp.delete()
      }
    } catch (e: Exception) {
      Log.e(TAG, "playback failed: ${e.message}")
    }
  }

  @Synchronized fun stop() {
    try {
      player?.stop()
    } catch (_: IllegalStateException) {
      // Already stopped.
    }
    player?.release()
    player = null
  }

  private fun selectOutputDevice(mp: MediaPlayer, sink: AudioRouter.OutputSink) {
    try {
      val device =
          router?.preferredOutputDevice(sink)
              ?: audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
                it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
              }
      if (device != null) {
        mp.setPreferredDevice(device)
        Log.i(TAG, "routed playback to ${device.productName} (sink=$sink)")
      } else {
        Log.w(TAG, "no A2DP output device found; using the default route")
      }
    } catch (e: Exception) {
      Log.w(TAG, "output routing failed: ${e.message}")
    }
  }
}

/**
 * Accumulates PCM from the glasses microphone and splits it into utterances.
 *
 * On firmware 129 the glasses mic arrives as in-band PCM alongside the video
 * stream (not over HFP/SCO — opening SCO ends the DAT session). That audio is
 * NOT beamformed, so ambient noise is much louder than a normal headset mic.
 * Energy gating therefore uses a slightly higher threshold than it would for
 * a beamformed signal.
 */
class UtteranceSplitter(
  private val sampleRate: Int = 16000,
  private val frameMs: Int = 20,
  /** RMS amplitude above which a frame counts as speech. */
  private val threshold: Float = 350f,
  /** Consecutive silent frames that close an utterance (700ms at 20ms/frame). */
  private val silenceFramesToEnd: Int = 35,
  /** Consecutive voiced frames that open an utterance. */
  private val speechFramesToStart: Int = 3,
) {
  private val frameSamples = sampleRate * frameMs / 1000
  private val buffer = ArrayList<Byte>(frameSamples * 64)

  /**
   * Audio captured since the utterance opened.
   *
   * The scan buffer is consumed as it is examined, so the utterance needs its
   * own copy — otherwise the words are gone by the time the utterance closes.
   */
  private val utterance = ArrayList<Byte>(frameSamples * 128)

  private var voicedRun = 0
  private var silentRun = 0
  private var speaking = false

  /**
   * Feed PCM bytes. Returns a completed utterance as WAV bytes when one ends,
   * otherwise null.
   *
   * This mirrors the reference implementation in
   * `bridge/tests/test_splitter.py`, which pins the behaviour.
   */
  fun push(pcm: ByteArray): ByteArray? {
    buffer.addAll(pcm.toList())
    var index = 0
    while (buffer.size - index >= frameSamples) {
      val frame = buffer.subList(index, index + frameSamples)
      index += frameSamples
      val rms = rms(frame)
      val voiced = rms > threshold

      if (voiced) {
        voicedRun++
        silentRun = 0
        if (!speaking && voicedRun >= speechFramesToStart) {
          speaking = true
          // Start the utterance at the first voiced frame.
          val drop = (voicedRun - speechFramesToStart) * frameSamples
          if (drop > 0 && index - drop > 0) index -= drop
        }
      } else {
        silentRun++
        voicedRun = 0
      }

      if (speaking) {
        // Copy the frame into the utterance AS IT IS SCANNED. Copying after the
        // loop would find nothing — the scan buffer is consumed by it.
        val start = maxOf(0, index - frameSamples)
        utterance.addAll(buffer.subList(start, index))
      }
    }
    if (index > 0) buffer.subList(0, index).clear()

    if (speaking && silentRun >= silenceFramesToEnd) {
      speaking = false
      voicedRun = 0
      silentRun = 0
      val bytes = utterance.toByteArray()
      utterance.clear()
      buffer.clear()
      return pcmToWav(bytes)
    }
    return null
  }

  /**
   * Return any buffered audio as a finished utterance, or null if there is
   * nothing pending.
   *
   * This is what push-to-talk needs: when the user releases the button, the
   * words they just spoke are still sitting in the buffer waiting for the
   * silence that would normally close the utterance. Discarding them would
   * throw away the whole request.
   */
  fun flush(): ByteArray? {
    if (utterance.isEmpty() && buffer.isEmpty()) {
      reset()
      return null
    }
    val bytes = if (utterance.isNotEmpty()) utterance.toByteArray() else buffer.toByteArray()
    reset()
    return pcmToWav(bytes)
  }

  /** Drop any buffered audio — used when the user interrupts. */
  fun reset() {
    buffer.clear()
    utterance.clear()
    voicedRun = 0
    silentRun = 0
    speaking = false
  }

  private fun rms(frame: List<Byte>): Float {
    if (frame.isEmpty()) return 0f
    var sum = 0.0
    var i = 0
    while (i + 1 < frame.size) {
      val sample = ((frame[i + 1].toInt() shl 8) or (frame[i].toInt() and 0xFF)).toShort()
      sum += (sample.toDouble() * sample.toDouble())
      i += 2
    }
    return Math.sqrt(sum / (frame.size / 2)).toFloat()
  }

  private fun pcmToWav(pcm: ByteArray): ByteArray {
    val header = ByteArray(44)
    val byteRate = sampleRate * 2
    val dataSize = pcm.size
    fun putInt(offset: Int, value: Int) {
      header[offset] = (value and 0xFF).toByte()
      header[offset + 1] = ((value shr 8) and 0xFF).toByte()
      header[offset + 2] = ((value shr 16) and 0xFF).toByte()
      header[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }
    fun putShort(offset: Int, value: Int) {
      header[offset] = (value and 0xFF).toByte()
      header[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }
    System.arraycopy("RIFF".toByteArray(), 0, header, 0, 4)
    putInt(4, 36 + dataSize)
    System.arraycopy("WAVE".toByteArray(), 0, header, 8, 4)
    System.arraycopy("fmt ".toByteArray(), 0, header, 12, 4)
    putInt(16, 16)
    putShort(20, 1) // PCM
    putShort(22, 1) // mono
    putInt(24, sampleRate)
    putInt(28, byteRate)
    putShort(32, 2) // block align
    putShort(34, 16) // bits per sample
    System.arraycopy("data".toByteArray(), 0, header, 36, 4)
    putInt(40, dataSize)

    val out = ByteArray(44 + dataSize)
    System.arraycopy(header, 0, out, 0, 44)
    System.arraycopy(pcm, 0, out, 44, dataSize)
    return out
  }
}
