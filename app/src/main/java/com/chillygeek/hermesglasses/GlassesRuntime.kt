package com.chillygeek.hermesglasses

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application-scoped owner of the whole glasses conversation loop.
 *
 * This exists because the conversation must survive the activity going away —
 * when the screen locks or you put the phone in your pocket, the Activity is
 * destroyed and `lifecycleScope` is cancelled with it. A foreground service
 * (see [GlassesService]) keeps the process alive, but only if the objects doing
 * the work are held here rather than in the Activity.
 */
object GlassesRuntime {

  private const val TAG = "GlassesRuntime"

  /** Survives activity destruction — this is the whole point. */
  val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  lateinit var bridge: BridgeClient
    private set
  lateinit var controller: GlassesController
    private set
  lateinit var player: SpeechPlayer
    private set
  lateinit var splitter: UtteranceSplitter
    private set
  lateinit var router: AudioRouter
    private set
  lateinit var mock: MockGlasses
    private set

  /** True when running against simulated glasses rather than hardware. */
  @Volatile var usingMockGlasses: Boolean = false
    private set

  /** Where the microphone audio comes from. */
  @Volatile var micSource: AudioRouter.MicSource = AudioRouter.MicSource.HEADSET

  /** Where spoken replies are played. */
  @Volatile var outputSink: AudioRouter.OutputSink = AudioRouter.OutputSink.HEADSET

  /** Latest camera frame as JPEG, uploaded to the bridge before each turn. */
  @Volatile var lastFrameJpeg: ByteArray? = null
    private set

  /** When false, incoming mic audio is discarded — push-to-talk mode. */
  @Volatile var listening: Boolean = true

  /** Set while a turn is in flight so turns cannot overlap. */
  @Volatile var conversationBusy: Boolean = false

  /** Called with each completed utterance (already transcribed). */
  @Volatile var onTranscript: ((String) -> Unit)? = null

  /** Called with each spoken sentence so the UI can show progress. */
  @Volatile var onReply: ((String) -> Unit)? = null

  /** Called when a turn fails, so the UI can surface it. */
  @Volatile var onError: ((String) -> Unit)? = null

  @Volatile private var initialized = false

  fun init(context: Context) {
    if (initialized) return
    initialized = true

    bridge = BridgeClient()
    router = AudioRouter(context, scope)
    mock = MockGlasses(context, scope)
    player = SpeechPlayer(context, router)
    splitter = UtteranceSplitter()
    controller = GlassesController(context, scope)

    // Glasses mic (in-band PCM) only — the headset/phone paths come from
    // AudioRouter instead.
    controller.onAudioChunk = { pcm ->
      if (micSource == AudioRouter.MicSource.GLASSES) onMicAudio(pcm)
    }
    controller.onJpegFrame = { jpeg -> lastFrameJpeg = jpeg }
  }

  /**
   * Switch the microphone source.
   *
   * A headset is the recommended source on firmware 129: routing hands-free audio
   * to the *glasses* ends the DAT camera session, so the glasses must not be
   * the ones holding the SCO link.
   */
  fun applyMicSource(source: AudioRouter.MicSource) {
    micSource = source
    if (source == AudioRouter.MicSource.GLASSES) {
      router.stopCapture()
    } else {
      router.startCapture(source) { pcm -> onMicAudio(pcm) }
    }
  }

  /**
   * Turn the simulated glasses on or off.
   *
   * With mock glasses enabled the DAT session runs against a simulated device,
   * so registration, camera attach and stream start can all be exercised
   * without wearing anything. Pairing is automatic, which skips the Meta AI app
   * round trip.
   */
  fun setMockGlasses(enabled: Boolean): Boolean {
    return if (enabled) {
      val ok = mock.enable(initiallyRegistered = true, permissionsGranted = true)
      if (ok) {
        usingMockGlasses = true
        controller.datReady = true
        mock.pairAndActivate()
      }
      ok
    } else {
      mock.disable()
      usingMockGlasses = false
      controller.datReady = false
      true
    }
  }

  /** Set once the DAT SDK has been initialized (permissions granted). */
  fun markDatReady() {
    if (::controller.isInitialized) controller.datReady = true
  }

  /**
   * Called with raw PCM from the glasses mic. The splitter decides when an
   * utterance has ended; when it does we run the full turn.
   */
  private fun onMicAudio(pcm: ByteArray) {
    if (!listening) return
    val utterance = splitter.push(pcm) ?: return
    if (conversationBusy) return
    conversationBusy = true

    scope.launch {
      try {
        val transcript = bridge.transcribe(utterance)
        onTranscript?.invoke(transcript)
        if (transcript.isBlank()) return@launch

        // Ship the latest camera frame so the reply can reference what is seen.
        lastFrameJpeg?.let { bridge.uploadFrame(it) }

        val reply = StringBuilder()
        bridge.ask(
                text = transcript,
                withImage = lastFrameJpeg != null,
                agent = false,
                onSpeech = { speech ->
                  // Play each sentence as it arrives — this is what makes the
                  // exchange feel like a conversation instead of a pause.
                  player.play(speech.wav, outputSink)
                  reply.append(speech.text)
                },
            )
            .also { turn ->
              val full = if (turn.reply.isNotBlank()) turn.reply else reply.toString()
              onReply?.invoke(full)
            }
      } catch (e: Exception) {
        Log.e(TAG, "turn failed: ${e.message}", e)
        onReply?.invoke("")
        onError?.invoke(e.message ?: "turn failed")
      } finally {
        conversationBusy = false
      }
    }
  }

  /** Push-to-talk: start capturing speech. */
  fun startListening() {
    listening = true
    splitter.reset()
  }

  /** Push-to-talk: stop capturing speech. */
  fun stopListening() {
    listening = false
    splitter.reset()
  }
}
