package com.chillygeek.hermesglasses

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * The whole app in one activity.
 *
 * Flow: register with the glasses -> open a DAT session -> stream camera +
 * in-band mic audio -> on each completed utterance, transcribe it, ask the
 * bridge, and play the spoken reply out through the glasses.
 *
 * The conversation itself lives in [GlassesRuntime] and is kept alive by
 * [GlassesService], so locking the screen does not end it. This activity is the
 * UI and the permission surface — it can go away at any time.
 */
class MainActivity : AppCompatActivity() {

  companion object {
    private const val TAG = "HermesGlasses"
    private const val REQ_AUDIO = 1001
  }

  private lateinit var statusView: TextView
  private lateinit var transcriptView: TextView
  private lateinit var toggleButton: Button
  private lateinit var talkButton: Button

  private var permissionContinuation: kotlin.coroutines.Continuation<PermissionStatus>? = null

  /**
   * DAT camera permission is granted through the Meta AI app, not the Android
   * permission dialog, so it needs its own activity-result contract.
   */
  private val permissionLauncher =
      registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
        val status = result.getOrElse { PermissionStatus.Denied }
        permissionContinuation?.resume(status)
        permissionContinuation = null
      }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)

    GlassesRuntime.init(applicationContext)

    statusView = findViewById(R.id.status)
    transcriptView = findViewById(R.id.transcript)
    toggleButton = findViewById(R.id.toggle)
    talkButton = findViewById(R.id.talk)

    findViewById<Button>(R.id.register).setOnClickListener { register() }
    toggleButton.setOnClickListener { toggleStreaming() }
    findViewById<Button>(R.id.reset_session).setOnClickListener {
      lifecycleScope.launch {
        GlassesRuntime.bridge.resetSession()
        transcriptView.text = "New conversation."
      }
    }

    // Push-to-talk: hold to speak, release to send.
    talkButton.setOnTouchListener { _, event ->
      when (event.action) {
        MotionEvent.ACTION_DOWN -> {
          GlassesRuntime.startListening()
          talkButton.text = "Listening…"
          true
        }
        MotionEvent.ACTION_UP,
        MotionEvent.ACTION_CANCEL -> {
          GlassesRuntime.stopListening()
          talkButton.text = "Hold to talk"
          true
        }
        else -> false
      }
    }

    GlassesRuntime.onTranscript = { text -> transcriptView.text = "You: $text" }
    GlassesRuntime.onReply = { text -> transcriptView.text = "Hermes: $text" }

    lifecycleScope.launch {
      GlassesRuntime.controller.state.collect { state ->
        statusView.text = describe(state)
        toggleButton.text = if (state is GlassesController.State.Streaming) "Stop" else "Start"
      }
    }

    // The DAT SDK touches the Bluetooth adapter inside initialize(). On Android 12+
    // that throws SecurityException unless BLUETOOTH_CONNECT is granted at
    // runtime, so the runtime permissions MUST be granted before initialize() is
    // called — this is the same ordering Meta's own sample uses.
    ensureRuntimePermissionsThenInitialize()
  }

  /**
   * Request the runtime permissions the DAT SDK needs, then initialize it.
   *
   * Order matters: initialize() scans for Bluetooth devices, and calling it
   * before BLUETOOTH_CONNECT is granted crashes the app with a
   * SecurityException that is invisible without logcat.
   */
  private fun ensureRuntimePermissionsThenInitialize() {
    val needed =
        arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    val missing =
        needed.filter {
          ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

    if (missing.isEmpty()) {
      initializeDat()
    } else {
      statusView.text = "Requesting Bluetooth permissions…"
      bluetoothPermissionLauncher.launch(missing.toTypedArray())
    }
  }

  private val bluetoothPermissionLauncher =
      registerForActivityResult(RequestMultiplePermissions()) { results ->
        val denied = results.filterValues { !it }.keys
        if (denied.isNotEmpty()) {
          statusView.text = "Bluetooth permission denied — the DAT SDK cannot scan for glasses."
          Log.e(TAG, "denied permissions: $denied")
          return@registerForActivityResult
        }
        initializeDat()
      }

  /** Initialize the DAT SDK. Must run after BLUETOOTH_CONNECT is granted. */
  private fun initializeDat() {
    try {
      Wearables.initialize(this).onFailure { error, _ ->
        Log.e(TAG, "DAT initialize failed: ${error.description}")
        statusView.text = "DAT init failed: ${error.description}"
      }
      GlassesRuntime.markDatReady()
      statusView.text = "Ready. Tap Register with glasses."
    } catch (e: Throwable) {
      Log.e(TAG, "DAT initialize threw: ${e.message}", e)
      statusView.text = "DAT init crashed: ${e.message}"
    }
  }

  private fun describe(state: GlassesController.State): String =
      when (state) {
        is GlassesController.State.Idle -> "Idle"
        is GlassesController.State.Registering -> "Registering with glasses…"
        is GlassesController.State.Ready -> "Registered. Ready to stream."
        is GlassesController.State.Streaming -> "Streaming — ${state.frames} frames"
        is GlassesController.State.Failed -> "Error: ${state.reason}"
      }

  // ------------------------------------------------------------------ registration

  private fun register() {
    if (!hasAudioPermission()) {
      requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
      return
    }
    GlassesRuntime.controller.register(this)
  }

  private fun hasAudioPermission(): Boolean =
      ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
          PackageManager.PERMISSION_GRANTED

  override fun onRequestPermissionsResult(
      requestCode: Int,
      permissions: Array<out String>,
      grantResults: IntArray,
  ) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    if (requestCode == REQ_AUDIO) {
      if (hasAudioPermission()) {
        GlassesRuntime.controller.register(this)
      } else {
        statusView.text = "Microphone permission denied — cannot capture speech."
      }
    }
  }

  // ------------------------------------------------------------------ streaming

  private fun toggleStreaming() {
    if (GlassesRuntime.controller.state.value is GlassesController.State.Streaming) {
      GlassesRuntime.controller.stopStream()
      GlassesService.stop(this)
      return
    }

    lifecycleScope.launch {
      val granted = requestCameraPermission()
      if (!granted) {
        statusView.text = "Camera permission denied."
        return@launch
      }
      // Keep the conversation alive while the screen is off or the phone is
      // in your pocket.
      GlassesService.start(this@MainActivity)
      GlassesRuntime.controller.startStream(includeAudio = true)
    }
  }

  /** Ask the Meta AI app for camera access via the DAT permission contract. */
  private suspend fun requestCameraPermission(): Boolean {
    val status =
        Wearables.checkPermissionStatus(Permission.CAMERA).getOrElse { error ->
          Log.e(TAG, "permission check failed: ${error.message}")
          PermissionStatus.Denied
        }
    if (status == PermissionStatus.Granted) return true

    val requested =
        suspendCancellableCoroutine<PermissionStatus> { cont ->
          permissionContinuation = cont
          cont.invokeOnCancellation { permissionContinuation = null }
          permissionLauncher.launch(Permission.CAMERA)
        }
    return requested == PermissionStatus.Granted
  }

  override fun onDestroy() {
    super.onDestroy()
    // Deliberately do NOT stop the service or the runtime here — locking the
    // screen destroys the activity, and the conversation must survive that.
  }
}
