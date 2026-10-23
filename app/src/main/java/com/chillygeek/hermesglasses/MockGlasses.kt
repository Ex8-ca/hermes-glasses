package com.chillygeek.hermesglasses

import android.content.Context
import android.util.Log
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import com.meta.wearable.dat.mockdevice.api.GlassesModel
import com.meta.wearable.dat.mockdevice.api.MockDeviceKitConfig
import com.meta.wearable.dat.mockdevice.api.MockDeviceKitInterface
import com.meta.wearable.dat.mockdevice.api.MockGlasses
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Drives a simulated pair of glasses so the app can be exercised without
 * hardware.
 *
 * This matters more than it looks: the glasses DAT path is the one part of this
 * project that cannot be tested on a laptop, and it is exactly the part with
 * the firmware-129 landmines. A simulated device exercises registration, session
 * lifecycle, camera attach, and stream start without wearing anything.
 *
 * The mock device also generates a video feed, so the vision path can be
 * checked end-to-end.
 */
class MockGlasses(
  private val context: Context,
  private val scope: CoroutineScope,
) {

  companion object {
    private const val TAG = "MockGlasses"
  }

  private var mockDeviceKit: MockDeviceKitInterface? = null
  private var pairedDevice: MockGlasses? = null

  val isActive: Boolean get() = mockDeviceKit != null

  /**
   * Enable the mock and pair a simulated Ray-Ban Meta.
   *
   * [initiallyRegistered] makes the app believe it is already registered with
   * the glasses, which skips the Meta AI app round trip.
   */
  fun enable(initiallyRegistered: Boolean = true, permissionsGranted: Boolean = true): Boolean {
    return try {
      val kit = MockDeviceKit.getInstance(context.applicationContext)
      kit.enable(MockDeviceKitConfig(initiallyRegistered, permissionsGranted))
      mockDeviceKit = kit
      Log.i(TAG, "mock device kit enabled")
      true
    } catch (e: Exception) {
      Log.e(TAG, "could not enable mock device kit: ${e.message}", e)
      false
    }
  }

  /**
   * Pair a simulated device and bring it to a usable state: powered on and
   * worn — the state a real pair would be in.
   */
  fun pairAndActivate(): Boolean {
    val kit = mockDeviceKit ?: return false
    return try {
      val paired: MockGlasses? = kit.pairGlasses(GlassesModel.RAYBAN_META).getOrNull()
      if (paired == null) {
        Log.e(TAG, "pairGlasses failed")
        return false
      }
      val device: MockGlasses = paired
      pairedDevice = device
      device.powerOn()
      device.unfold()
      device.don()
      Log.i(TAG, "mock glasses paired, powered on, unfolded and worn")
      true
    } catch (e: Exception) {
      Log.e(TAG, "could not activate the mock device: ${e.message}", e)
      false
    }
  }

  /** Take the mock device off, as if the wearer removed them. */
  fun deactivate() {
    try {
      pairedDevice?.doff()
      pairedDevice?.powerOff()
    } catch (e: Exception) {
      Log.w(TAG, "deactivate: ${e.message}")
    }
  }

  fun disable() {
    try {
      mockDeviceKit?.disable()
    } catch (e: Exception) {
      Log.w(TAG, "disable: ${e.message}")
    }
    mockDeviceKit = null
    pairedDevice = null
  }
}
