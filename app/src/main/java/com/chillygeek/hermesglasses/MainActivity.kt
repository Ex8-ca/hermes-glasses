package com.chillygeek.hermesglasses

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.chillygeek.hermesglasses.ui.GlassesScreen
import com.chillygeek.hermesglasses.ui.HermesTheme
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
class MainActivity : ComponentActivity() {

  companion object {
    private const val TAG = "HermesGlasses"
    private const val REQ_AUDIO = 1001
  }

  private var permissionContinuation: kotlin.coroutines.Continuation<PermissionStatus>? = null

  /**
   * DAT camera permission is granted through the Meta AI app, not the Android
   * permission dialog, so it needs its own activity-result contract.
   */
  private val datPermissionLauncher =
      registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
        val status = result.getOrElse { PermissionStatus.Denied }
        permissionContinuation?.resume(status)
        permissionContinuation = null
      }

  private val bluetoothPermissionLauncher =
      registerForActivityResult(RequestMultiplePermissions()) { results ->
        val denied = results.filterValues { !it }.keys
        if (denied.isEmpty()) {
          initializeDat()
        } else {
          Log.e(TAG, "Bluetooth permissions denied: $denied")
        }
      }

  private val audioPermissionLauncher =
      registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {
          granted ->
        if (granted) {
          GlassesRuntime.controller.register(this)
        } else {
          Log.e(TAG, "microphone permission denied")
        }
      }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    GlassesRuntime.init(applicationContext)

    // Wearables.initialize is required before any DAT API call, and it must
    // come after the runtime Bluetooth permissions — see initializeDat().
    ensureRuntimePermissionsThenInitialize()

    setContent {
      HermesTheme {
        var state by remember { mutableStateOf<GlassesController.State>(GlassesController.State.Idle) }
        var transcript by remember { mutableStateOf("") }
        var reply by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        var listening by remember { mutableStateOf(false) }
        var statusText by remember { mutableStateOf("Not connected") }

        LaunchedEffect(Unit) {
          GlassesRuntime.controller.state.collect { s ->
            state = s
            statusText =
                when (s) {
                  is GlassesController.State.Idle -> "Idle"
                  is GlassesController.State.Registering -> "Registering"
                  is GlassesController.State.Ready -> "Registered"
                  is GlassesController.State.Streaming -> "Live"
                  is GlassesController.State.Failed -> "Error"
                }
          }
        }
        LaunchedEffect(Unit) {
          GlassesRuntime.onTranscript = { transcript = it }
          GlassesRuntime.onReply = { reply = it }
          GlassesRuntime.onError = { error = it }
        }

        GlassesScreen(
            state = state,
            statusText = statusText,
            transcript = transcript,
            reply = reply,
            error = error,
            listening = listening,
            onRegister = { register() },
            onToggleStream = { toggleStreaming() },
            onTalkDown = {
              listening = true
              GlassesRuntime.startListening()
            },
            onTalkUp = {
              listening = false
              GlassesRuntime.stopListening()
            },
            onReset = {
              lifecycleScope.launch {
                GlassesRuntime.bridge.resetSession()
                transcript = ""
                reply = ""
                error = null
              }
            },
        )
      }
    }
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
      bluetoothPermissionLauncher.launch(missing.toTypedArray())
    }
  }

  /** Initialize the DAT SDK. Must run after BLUETOOTH_CONNECT is granted. */
  private fun initializeDat() {
    try {
      Wearables.initialize(this).onFailure { error, _ ->
        Log.e(TAG, "DAT initialize failed: ${error.description}")
      }
      GlassesRuntime.markDatReady()
    } catch (e: Throwable) {
      Log.e(TAG, "DAT initialize threw: ${e.message}", e)
    }
  }

  // ------------------------------------------------------------------ registration

  private fun register() {
    if (!hasAudioPermission()) {
      audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
      return
    }
    GlassesRuntime.controller.register(this)
  }

  private fun hasAudioPermission(): Boolean =
      ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
          PackageManager.PERMISSION_GRANTED

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
        Log.e(TAG, "camera permission denied")
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
          datPermissionLauncher.launch(Permission.CAMERA)
        }
    return requested == PermissionStatus.Granted
  }
}
