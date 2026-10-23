# Hermes Glasses

An assistant for **Ray-Ban Meta glasses** that talks to a
[Hermes](https://github.com/Ex8-ca) bridge on your own machine.

The glasses provide the **camera**. Your phone provides the **microphone and
audio output** — a Bluetooth headset if you have one. The bridge does the
thinking: speech to text, the model, and speech back out.

## What this release contains

| | |
|---|---|
| **APK** | `app-debug.apk` — sideload it, or install with `adb install` |
| **Source** | https://github.com/Ex8-ca/hermes-glasses |
| **Bridge** | `bridge/` — the server that runs the models |

The APK is signed with the standard Android **debug key**. It installs by
sideloading, but not over a Play Store build of the same package.

## Before you start

1. **You need a Meta Developer Account** and **Developer Mode** enabled on
   the glasses. See [Meta's setup guide](https://wearables.developer.meta.com/docs/getting-started/).
2. **You need to run the bridge** on a machine with the models. See
   [the README](https://github.com/Ex8-ca/hermes-glasses#running-the-bridge).
3. **You need your glasses' release firmware to be V128 or later.** Older
   releases cannot run the camera session.

### Set the bridge address

The app cannot know your network, so set it on first launch:

**Glasses → BRIDGE → Bridge address**

```
http://<your-bridge-host>:8790
```

The address is validated as you type and saved on the device. A partial
address is rejected rather than silently pointing the app at nothing.

## Using it

- **Register** — pair the app with the glasses through the Meta AI app.
- **Start** — open a camera session. The glasses become the eyes.
- **Talk** — press and hold *Hold to talk*, speak, release. Or let the
  voice-activity detector close the turn for you.
- **Watch** — the reply appears as text and is spoken back through your
  headset, sentence by sentence, so the first words start before the full
  reply is generated.

With the phone screen off the session keeps running: the app runs a
foreground service with a wake lock.

## Microphone notes

On **firmware 129** the glasses' own microphone is not usable alongside the
camera — opening the Bluetooth audio link ends the camera session. Meta
treats this as a glasses-software bug.

So there are three microphone routes:

| Route | Camera | Notes |
|---|---|---|
| **Headset** | ✅ | Best. A Bluetooth headset is a separate Bluetooth link and does not disturb the glasses. |
| **Phone** | ✅ | Works. The phone's own mic. |
| **Glasses** | ✅ | In-band PCM only — unfiltered, so it is noisy. Experimental. |

Audio output can go to the headset, the phone speaker, or the glasses
speakers. Note that routing output *to the glasses* ends the camera session
on firmware 129, so use the headset or the phone while streaming.

## If the camera session fails to start

Gen 1 glasses are flaky here — this is a known issue in Meta's SDK
([#177](https://github.com/facebook/meta-wearables-dat-android/issues/177)).
If `createSession` fails, restart the glasses and try again.

To test everything *without* the glasses, switch **Glasses: Simulated** in
the app. It uses Meta's MockDeviceKit, so registration, session lifecycle,
camera attach and stream start all run for real against a simulated device.

## Requirements

- Android 12 or newer (API 31+)
- A phone on the same network as the bridge
- Ray-Ban Meta Gen 1 or Gen 2, developer mode on, firmware V128+

## Privacy

This app streams the glasses camera to the bridge you configure. What happens
to the frames is up to that bridge. Do not use it to record people without
their consent, and note that Meta's [acceptable use
policy](https://wearables.developer.meta.com/acceptable-use-policy) and
developer terms apply to anything built on the Wearables Device Access
Toolkit.

## Licence

MIT for this project's code. The Meta Wearables Device Access Toolkit is
licensed separately by Meta and is not redistributed here.
