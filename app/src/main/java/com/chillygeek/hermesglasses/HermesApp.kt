package com.chillygeek.hermesglasses

import android.app.Application
import android.util.Log
import java.io.PrintWriter
import java.io.StringWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Application subclass that installs a crash reporter.
 *
 * The DAT SDK touches the Bluetooth adapter during `Wearables.initialize()`. On
 * Android 12+ that throws SecurityException unless BLUETOOTH_CONNECT has been
 * granted at runtime — and an uncaught exception in an Activity takes the whole
 * app down with no way to see why from the phone.
 *
 * This handler posts the stack trace to the bridge so the crash is visible from
 * the server, and also writes it to a file on the device.
 */
class HermesApp : Application() {

  companion object {
    private const val TAG = "HermesCrash"

    /** Where the crash report is posted. Matches BuildConfig.BRIDGE_URL. */
    private fun bridgeUrl(): String = BuildConfig.BRIDGE_URL
  }

  override fun onCreate() {
    super.onCreate()
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
      try {
        report(throwable)
      } catch (e: Throwable) {
        Log.e(TAG, "crash reporting failed: ${e.message}")
      }
      // Always chain to the default handler so the OS still records the crash
      // and shows its own dialog.
      previous?.uncaughtException(thread, throwable)
    }
  }

  private fun report(throwable: Throwable) {
    val sw = StringWriter()
    throwable.printStackTrace(PrintWriter(sw))
    val body =
        buildString {
          append("{")
          append("\"app\":\"hermes-glasses\",")
          append("\"type\":\"").append(throwable.javaClass.name).append("\",")
          append("\"message\":").append(quote(throwable.message ?: "")).append(",")
          append("\"stack\":").append(quote(sw.toString())).append(",")
          append("\"device\":").append(quote(android.os.Build.MODEL)).append(",")
          append("\"android\":").append(quote(android.os.Build.VERSION.RELEASE)).append(",")
          append("\"sdk_int\":").append(android.os.Build.VERSION.SDK_INT).append(",")
          append("\"timestamp\":").append(System.currentTimeMillis())
          append("}")
        }

    Log.e(TAG, "CRASH: ${throwable.javaClass.name}: ${throwable.message}")
    Log.e(TAG, sw.toString())

    // Post on a separate thread — the crashing thread is already dying.
    Thread {
          try {
            val conn =
                URL("${bridgeUrl()}/crash").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray()) }
            conn.inputStream.use { it.readBytes() }
            conn.disconnect()
          } catch (e: Throwable) {
            Log.e(TAG, "could not post crash report: ${e.message}")
          }
        }
        .start()
  }

  private fun quote(value: String): String {
    val sb = StringBuilder("\"")
    for (ch in value) {
      when (ch) {
        '"' -> sb.append("\\\"")
        '\\' -> sb.append("\\\\")
        '\n' -> sb.append("\\n")
        '\r' -> sb.append("\\r")
        '\t' -> sb.append("\\t")
        else ->
            if (ch < ' ') sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
      }
    }
    return sb.append("\"").toString()
  }
}
