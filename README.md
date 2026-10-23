# Hermes Glasses

Talk to your AI glasses. Wear a pair of Ray-Ban Meta glasses, speak a question,
and hear the answer — with the assistant able to see what you're looking at.

Built on Meta's [Wearables Device Access Toolkit](https://wearables.developer.meta.com/docs/develop/dat/),
which gives an Android app access to the glasses' camera, microphone, and
Bluetooth audio.

```
   ┌──────────────┐   camera frames    ┌─────────────┐
   │  Android app │ ─────────────────▶ │   Bridge    │
   │  (Pixel/Any) │                    │  (your box) │
   │              │ ◀───────────────── │             │
   └──────────────┘  spoken reply (wav)└─────────────┘
          │  Bluetooth                       │
          ▼                                  ├─ STT  (whisper)
    ┌───────────┐                             ├─ LLM  (MiniMax / Hermes)
    │  Glasses  │                             └─ TTS  (chatterbox)
    └───────────┘
```

The phone is a thin transport. All the model work happens on your own machine,
so audio and video never touch a cloud service you don't control.

---

## ⚠️ Read this before you start: the firmware 129 issue

**On glasses firmware 129, opening a Bluetooth SCO (HFP) route makes the glasses
end the Device Access Toolkit session within 0.1–0.5 seconds.** They treat an
open SCO route as an incoming phone call. Every later session start is then
refused until you reboot the glasses.

Meta confirms this is a glasses-software bug with no SDK-side fix
([meta-wearables-dat-android#206](https://github.com/facebook/meta-wearables-dat-android/issues/206)).

The workaround this app uses is **in-band PCM audio**: setting
`audioCodec = AudioCodec.PCM(RATE_16000, 1)` on the stream configuration
delivers microphone audio *inside* the video stream, without touching either
Bluetooth profile.

The trade-offs, honestly:

- In-band audio is **not beamformed**, so ambient noise is much louder than a
  normal headset microphone would give you.
- It is an **experimental** feature — development and beta release channels
  only. You can build and test with it; you cannot publish an app that uses it.
- If you would rather have a clean, beamformed mic than simultaneous video,
  drop `audioCodec` and use the phone microphone instead, or run the app as a
  plain Bluetooth headset with no camera session at all (the firmware 129 bug
  never fires in that mode).

---

## Requirements

**Hardware**
- Ray-Ban Meta Gen 1 or Gen 2 glasses (Gen 3 is **not** supported by the DAT
  yet; Ray-Ban **Stories** are not supported at all)
- An Android phone, Android 12+ (API 31)

**Software**
- JDK 17+
- Android SDK with platform 36
- Python 3.11+ for the bridge

**Meta setup**
1. Install the **Meta AI** app on your phone and pair your glasses.
2. Enable **Developer Mode**: Meta AI → Settings → App info → tap the App
   version 5 times → toggle Developer Mode on.
3. Accept the
   [Meta Wearables Developer Terms](https://wearables.developer.meta.com/terms).

Developer Mode lets an unpublished app register with your glasses. For
production use you would register an app in the
[Wearables Developer Center](https://wearables.developer.meta.com/) and set
`mwdat_application_id` / `mwdat_client_token` in `local.properties`.

---

## Setup

### 1. The bridge (runs on your machine)

The bridge does speech-to-text, the LLM call, and text-to-speech.

```bash
cd bridge
cp .env.example .env      # then fill in your API keys
pip install fastapi uvicorn httpx numpy
python3 bridge.py
```

It listens on `:8790`. Check it with:

```bash
curl -s http://127.0.0.1:8790/health | python3 -m json.tool
```

The health endpoint reports the latency of every dependency, so you can see
immediately which one is slow.

#### Endpoints

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/health` | Reachability + latency of STT/LLM/TTS |
| `POST` | `/audio` | Transcribe a WAV utterance → `{"text": ...}` |
| `POST` | `/frame` | Upload the current camera frame (JPEG) |
| `POST` | `/ask` | Conversation turn → SSE stream of sentences + audio |
| `POST` | `/say` | Text → synthesized speech |
| `POST` | `/crash` | Receive a crash report from the phone app |
| `GET` | `/crash` | Read back the last crash report |
| `POST` | `/session/reset` | Clear conversation memory |

`/ask` streams, which is what makes the exchange feel like a conversation
instead of a pause:

```json
{"type": "text",  "text": "The door is open."}
{"type": "audio", "wav": "<base64 wav>", "seconds": 1.6}
{"type": "done",  "text": "The door is open. Should I go through it?"}
```

Each sentence is synthesized and sent the moment it is complete, so the first
audio plays while the rest is still being generated.

### 2. The Android app

```bash
cd app
echo "sdk.dir=$ANDROID_HOME" > ../local.properties
echo "bridgeUrl=http://YOUR_BRIDGE_HOST:8790" >> ../local.properties
../gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`bridgeUrl` is baked into the app at build time. If the phone and the bridge are
on different networks, use a Tailscale or VPN address.

> Use a **debug build** for sideloading. It is signed with the standard Android
> debug key, so it will not install over a Play Store version of the same
> package.

---

## How it works

1. **Register** — the app asks the Meta AI app to authorize it for your
   glasses (Developer Mode).
2. **Stream** — a `DeviceSession` opens, the camera capability attaches, and
   frames plus in-band PCM audio start flowing to the phone.
3. **Detect** — an energy-based VAD splits the incoming PCM into utterances.
4. **Transcribe** — the utterance goes to the bridge, which runs faster-whisper
   locally (Groq as fallback).
5. **Think** — the transcript (and the current camera frame, if there is one)
   goes to the LLM. The bridge streams the reply back sentence by sentence.
6. **Speak** — each sentence is synthesized and pushed to the phone, which
   plays it out through the glasses over A2DP.

A foreground service keeps the DAT session alive when the screen locks, so you
can put the phone in your pocket and keep talking.

## How do I actually call it?

There are three ways to trigger a turn, in increasing order of effort:

| Method | How | Needs |
|---|---|---|
| **Always listening** | Talk. The VAD detects the end of your sentence and sends it. | Stream running. Mic comes from the glasses (in-band PCM) or the phone. |
| **Push to talk** | Hold the **Hold to talk** button, speak, release. | Stream running. |
| **Wake word** | "Hey Meta, open Hermes Glasses" | A Wearables Developer Center app listing, a Beta release channel, Developer Mode **off**, and the "Launch with 'Hey Meta'" toggle in the Meta AI app. Not available in Developer Mode. |

The voice-in path depends on your firmware (see the warning at the top). On
firmware 129 the glasses mic works alongside the camera only via the noisy
in-band path; if you want a clean beamformed mic, use the phone mic or earbuds
instead and keep the camera for vision.

### Turning the stream on and off

- **Start** / **Stop** — opens and closes the DAT session (and the foreground
  service).
- **Hold to talk** — push-to-talk. Outside of holding it, incoming audio is
  discarded, so the assistant only hears you while you press.
- The notification has a **Stop** action so you can end the session without
  opening the app.

### Talking to it hands-free with the phone in your pocket

This is the intended way to use it, and it works because the conversation runs in
a foreground service, not in the Activity:

1. Open the app, tap **Register with glasses**, tap **Start**.
2. Put the phone away. The notification confirms it is live.
3. Talk. Replies come back through the glasses speakers.

---

## Other projects doing this

You are not first. There are at least 29 projects in this space — the most
complete map is `straff2002/OpenGlasses` → `docs/ecosystem-review-2026-10.md`.

| Project | Stars | Platform | Notes |
|---|---|---|---|
| [Intent-Lab/VisionClaw](https://github.com/Intent-Lab/VisionClaw) | 2.6k | iOS + Android | Gemini Live + OpenClaw. Uses LiveKit Cloud. Closest to this project. |
| [affaan-m/JARVIS](https://github.com/affaan-m/JARVIS) | 530 | Python | OSINT platform streamed via glasses. |
| [rayl15/OpenVision](https://github.com/rayl15/OpenVision) | 168 | iOS | MIT. Five backends incl. a Hermes Agent backend. |
| [straff2002/OpenGlasses](https://github.com/straff2002/OpenGlasses) | 120 | iOS | The most complete product; offline MLX models, WebRTC POV, Display HUD. |
| [prasanthsasikumar/hermes-glasses](https://github.com/prasanthsasikumar/hermes-glasses) | 24 | iOS | MIT. Closest in spirit — Hermes bridge mode plus vision. |
| [rodcone/flutter_meta_wearables_dat](https://github.com/rodcone/flutter_meta_wearables_dat) | 15 | Flutter | A DAT plugin, not an app. |

Two things worth knowing if you are choosing between them:

**Almost all of them are iOS.** This project is one of very few Android ones.

**Almost all of them avoid the glasses microphone.** Streaming mic audio over
HFP/SCO alongside a camera session is exactly what breaks on firmware 129, so
the industry answer is to use the phone mic (VisionClaw's Android sample uses
`AudioRecord` with no `audioCodec` at all) or earbuds (one project has a
"Headset mode" where the mic and TTS live in the earbuds). This project uses
the glasses' own microphone via the in-band path, which is the unusual choice
and the only one that keeps the mic on your face.

---

## Credits and licensing

This project is MIT licensed — see [LICENSE](LICENSE).

It depends on the **Meta Wearables Device Access Toolkit**, which is *not*
MIT licensed and is **not** redistributed here. It is obtained from Maven
Central as `com.meta.wearable:mwdat-*` and is governed by the
[Meta Wearables Developer Terms](https://wearables.developer.meta.com/terms) and
[Acceptable Use Policy](https://wearables.developer.meta.com/acceptable-use-policy).
By using it you accept those terms.

No Meta sample code is vendored in this repository. It was written against the
public DAT documentation and the public API reference.

The Acceptable Use Policy is worth reading in full if you build on this — it
covers covert filming, recording in sensitive locations, and biometric
identification, all of which are easy to trip over with a camera on someone's
face all day.

---

## Troubleshooting

**The app crashes on launch.** Check `GET /crash` on the bridge — the app
posts its stack trace there before it dies.

**Sessions are refused with `START_ERROR_DEVICE_UNAVAILABLE`.** This usually
means Developer Mode was reset. Do not call `startRegistration()` while already
registered: on Android that silently resets Developer Mode for the rest of the
process. Only call it when the state is not `REGISTERED`.

**Sessions die as soon as you enable the microphone.** Firmware 129 — see the
warning at the top of this file.

**The camera stream is choppy.** Resolution and frame rate are constrained by
the Bluetooth Classic link between phone and glasses. Requesting a lower
resolution or frame rate often yields *better* visual quality, because the
per-frame compression adapts to available bandwidth.

**Everything is slow.** Check `/health`. On an RTX 5080, TTS should be ~1s and
STT ~0.45s. If they are on CPU, move them to CUDA — a 9x difference.
