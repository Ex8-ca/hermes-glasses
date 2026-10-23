package com.chillygeek.hermesglasses

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Where the bridge lives, and how to change it without rebuilding.
 *
 * The bridge URL is compiled in as a default, but that default is wrong for
 * anyone who did not build the app themselves: a release APK shipped to the
 * world cannot know the installer's LAN address. So the URL is overridable at
 * runtime and persisted across launches.
 */
object BridgeConfig {

  private const val TAG = "BridgeConfig"
  private const val PREFS = "hermes_glasses"
  private const val KEY_URL = "bridge_url"

  /** The compiled-in default, used until the user sets their own. */
  val defaultUrl: String = BuildConfig.BRIDGE_URL

  private var prefs: SharedPreferences? = null

  fun init(context: Context) {
    if (prefs == null) {
      prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
  }

  /** The URL to use: the user's setting if set, otherwise the compiled default. */
  fun url(): String {
    val stored = prefs?.getString(KEY_URL, null)?.trim()
    if (!stored.isNullOrEmpty()) return stored
    return defaultUrl
  }

  /**
   * Store a new bridge URL.
   *
   * Returns the normalised URL on success, or null if it is not a usable
   * address — a bad URL here means the app silently talks to nothing.
   *
   * The check is deliberately strict. This runs on every keystroke, and
   * accepting a half-typed address like "http://" would leave the app pointed
   * at a host that does not exist.
   */
  fun setUrl(raw: String): String? {
    val cleaned = raw.trim().trimEnd('/')
    if (cleaned.isEmpty()) {
      Log.w(TAG, "refusing to store an empty bridge URL")
      return null
    }
    if (!cleaned.startsWith("http://") && !cleaned.startsWith("https://")) {
      Log.w(TAG, "bridge URL must start with http:// or https://")
      return null
    }
    val authority = cleaned.substringAfter("://").substringBefore('/')
    if (authority.isEmpty() || authority.contains(' ')) {
      Log.w(TAG, "bridge URL has no usable host")
      return null
    }
    // A host must look like a hostname or an IP, not a partial word.
    val host = authority.substringBefore(':')
    if (host.isEmpty() || (!host.contains('.') && host != "localhost")) {
      Log.w(TAG, "bridge URL host looks incomplete: $host")
      return null
    }
    // A dotted-numeric host is almost always an IP being typed. "192.168" and
    // "192.168.1" are partial addresses, so reject them rather than pointing
    // the app at a host that does not exist.
    if (host.all { it.isDigit() || it == '.' }) {
      val octets = host.split('.')
      val valid = octets.size == 4 && octets.all { o -> o.isNotEmpty() && o.all { it.isDigit() } && o.toInt() <= 255 }
      if (!valid) {
        Log.w(TAG, "bridge URL host is not a complete IP address: $host")
        return null
      }
    }
    prefs?.edit()?.putString(KEY_URL, cleaned)?.apply()
    Log.i(TAG, "bridge URL set to $cleaned")
    return cleaned
  }

  /** Forget the override and fall back to the compiled-in default. */
  fun reset() {
    prefs?.edit()?.remove(KEY_URL)?.apply()
  }

  /** True when the user has overridden the compiled-in default. */
  fun isOverridden(): Boolean = !prefs?.getString(KEY_URL, null).isNullOrEmpty()
}
