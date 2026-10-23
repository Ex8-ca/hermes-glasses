package com.chillygeek.hermesglasses

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Client for the hermes-glasses-bridge service running on the LAN box.
 *
 * The bridge owns every model call (STT, LLM, TTS) so the phone app stays a thin
 * Bluetooth + camera client. That split matters: the 5080 has the GPU, the models,
 * and the API keys, and the phone only has to move bytes.
 */
class BridgeClient(overrideUrl: String? = null) {

  companion object {
    private const val TAG = "BridgeClient"
    private const val CLIENT_ID = "pixel10proxl"
  }

  /**
   * The bridge address.
   *
   * Read from [BridgeConfig] on every request rather than captured once, so
   * changing the address in the app takes effect without a restart.
   */
  private val overrideUrl: String? = overrideUrl
  private val baseUrl: String get() = overrideUrl ?: BridgeConfig.url()

  private val http = OkHttpClient.Builder()
      .connectTimeout(10, TimeUnit.SECONDS)
      .readTimeout(180, TimeUnit.SECONDS)
      .writeTimeout(60, TimeUnit.SECONDS)
      .build()

  private val jsonMedia = "application/json; charset=utf-8".toMediaType()

  /** A spoken reply: one sentence's worth of audio plus its text. */
  data class Speech(val text: String, val wav: ByteArray, val seconds: Double) {
    // ByteArray in a data class needs explicit equality or lint complains.
    override fun equals(other: Any?): Boolean =
        this === other || (other is Speech && text == other.text && wav.contentEquals(other.wav))

    override fun hashCode(): Int = 31 * text.hashCode() + wav.contentHashCode()
  }

  /** Result of one conversational turn. */
  data class Turn(val transcript: String, val reply: String, val speech: List<Speech>) {
    val firstAudioDelayMs: Long get() = 0
  }

  /** Raised when the bridge is unreachable or returns an error. */
  class BridgeException(message: String) : IOException(message)

  suspend fun health(): String = withContext(Dispatchers.IO) {
    val request = Request.Builder().url("$baseUrl/health").get().build()
    execute(request).use { it.body?.string() ?: "" }
  }

  /**
   * Transcribe an utterance captured from the glasses microphone.
   * [wav] must be a 16-bit PCM mono WAV at 16 kHz.
   */
  suspend fun transcribe(wav: ByteArray): String = withContext(Dispatchers.IO) {
    val tmp = File.createTempFile("utterance", ".wav")
    try {
      tmp.writeBytes(wav)
      val body = MultipartBody.Builder()
        .setType(MultipartBody.FORM)
        .addFormDataPart("file", "utterance.wav", tmp.asRequestBody("audio/wav".toMediaType()))
        .addFormDataPart("client_id", CLIENT_ID)
        .build()
      val request = Request.Builder().url("$baseUrl/audio").post(body).build()
      execute(request).use { response ->
        val raw = response.body?.string().orEmpty()
        val text = extractJsonString(raw, "text")
        if (text.isNullOrBlank()) throw BridgeException("empty transcript from bridge")
        text
      }
    } finally {
      tmp.delete()
    }
  }

  /**
   * Upload the current camera frame so the bridge can answer questions about
   * what the wearer is looking at.
   */
  suspend fun uploadFrame(jpeg: ByteArray): Boolean = withContext(Dispatchers.IO) {
    val tmp = File.createTempFile("frame", ".jpg")
    try {
      tmp.writeBytes(jpeg)
      val body = MultipartBody.Builder()
        .setType(MultipartBody.FORM)
        .addFormDataPart("file", "frame.jpg", tmp.asRequestBody("image/jpeg".toMediaType()))
        .addFormDataPart("client_id", CLIENT_ID)
        .build()
      val request = Request.Builder().url("$baseUrl/frame").post(body).build()
      execute(request).use { true }
    } catch (e: Exception) {
      Log.w(TAG, "frame upload failed: ${e.message}")
      false
    } finally {
      tmp.delete()
    }
  }

  /**
   * Ask a question and receive the reply as a stream of sentences, each already
   * synthesized to WAV so it can be played while the rest is still being made.
   *
   * [onSpeech] is invoked on a background thread as each sentence's audio
   * arrives — play them in order for the lowest perceived latency.
   */
  suspend fun ask(
    text: String,
    withImage: Boolean,
    agent: Boolean,
    onSpeech: (Speech) -> Unit,
  ): Turn = withContext(Dispatchers.IO) {
    val payload =
        buildString {
          append("{")
          append("\"text\":").append(jsonString(text)).append(",")
          append("\"client_id\":").append(jsonString(CLIENT_ID)).append(",")
          append("\"with_image\":").append(withImage).append(",")
          append("\"agent\":").append(agent)
          append("}")
        }

    val request =
        Request.Builder()
            .url("$baseUrl/ask")
            .post(payload.toRequestBody(jsonMedia))
            .build()

    val speeches = mutableListOf<Speech>()
    var reply = ""

    execute(request).use { response ->
      val source = response.body?.source() ?: throw BridgeException("empty response body")
      while (!source.exhausted()) {
        val line = source.readUtf8Line() ?: break
        if (!line.startsWith("data:")) continue
        val payloadJson = line.removePrefix("data:").trim()
        if (payloadJson.isEmpty() || payloadJson == "[DONE]") continue

        val type = extractJsonString(payloadJson, "type")
        when (type) {
          "text" -> Unit // intermediate token; the final "done" carries the whole reply
          "audio" -> {
            val wavB64 = extractJsonString(payloadJson, "wav")
            val seconds = extractJsonDouble(payloadJson, "seconds")
            if (!wavB64.isNullOrBlank()) {
              val wav = decodeBase64(wavB64)
              val speech = Speech("", wav, seconds)
              speeches += speech
              onSpeech(speech)
            }
          }
          "done" -> {
            reply = extractJsonString(payloadJson, "text").orEmpty()
          }
          "error" -> {
            val detail = extractJsonString(payloadJson, "detail").orEmpty()
            throw BridgeException("bridge error: $detail")
          }
        }
      }
    }

    Turn(text, reply, speeches)
  }

  /** Reset conversation memory for this client (start a fresh conversation). */
  suspend fun resetSession(): Boolean = withContext(Dispatchers.IO) {
    val request =
        Request.Builder()
            .url("$baseUrl/session/reset")
            .post("{\"client_id\":\"$CLIENT_ID\"}".toRequestBody(jsonMedia))
            .build()
    execute(request).use { true }
  }

  // ---------------------------------------------------------------- internals

  private suspend fun execute(request: Request): Response =
      suspendCoroutine { cont ->
        http.newCall(request).enqueue(
            object : okhttp3.Callback {
              override fun onFailure(call: okhttp3.Call, e: IOException) {
                cont.resumeWithException(BridgeException("bridge unreachable: ${e.message}"))
              }

              override fun onResponse(call: okhttp3.Call, response: Response) {
                if (!response.isSuccessful) {
                  val body = response.body?.string().orEmpty()
                  response.close()
                  cont.resumeWithException(
                      BridgeException("bridge HTTP ${response.code}: ${body.take(200)}")
                  )
                } else {
                  cont.resume(response)
                }
              }
            }
        )
      }

  private fun jsonString(value: String): String {
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

  /**
   * Pull a string field out of a JSON object without pulling in a JSON library.
   * The bridge's payloads are flat and generated by us, so this is safe here.
   */
  private fun extractJsonString(json: String, field: String): String? {
    val key = "\"$field\":"
    val start = json.indexOf(key)
    if (start < 0) return null
    var i = start + key.length
    while (i < json.length && json[i] == ' ') i++
    if (i >= json.length) return null
    if (json[i] != '"') {
      // Non-string value (number/bool/null) — read until a delimiter.
      val end = json.indexOfAny(charArrayOf(',', '}', ']'), i).let { if (it < 0) json.length else it }
      return json.substring(i, end)
    }
    i++
    val sb = StringBuilder()
    while (i < json.length) {
      val c = json[i]
      when (c) {
        '\\' -> {
          i++
          if (i >= json.length) break
          when (json[i]) {
            'n' -> sb.append('\n')
            'r' -> sb.append('\r')
            't' -> sb.append('\t')
            'u' -> {
              val hex = json.substring(i + 1, minOf(i + 5, json.length))
              sb.append(hex.toInt(16).toChar())
              i += 4
            }
            else -> sb.append(json[i])
          }
        }
        '"' -> return sb.toString()
        else -> sb.append(c)
      }
      i++
    }
    return sb.toString()
  }

  private fun extractJsonDouble(json: String, field: String): Double {
    val key = "\"$field\":"
    val start = json.indexOf(key)
    if (start < 0) return 0.0
    val from = start + key.length
    val end = json.indexOfAny(charArrayOf(',', '}', ']'), from).let { if (it < 0) json.length else it }
    return json.substring(from, end).trim().toDoubleOrNull() ?: 0.0
  }

  private fun decodeBase64(value: String): ByteArray = try {
    android.util.Base64.decode(value, android.util.Base64.DEFAULT)
  } catch (e: IllegalArgumentException) {
    Log.w(TAG, "bad base64 audio payload: ${e.message}")
    ByteArray(0)
  }
}
