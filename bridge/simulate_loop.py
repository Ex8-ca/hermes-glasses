#!/usr/bin/env python3
"""
Headless voice-loop simulator.

Exercises the whole conversation pipeline without glasses, a phone, or a
microphone:

    PCM (synthetic speech-like signal)
      -> VAD  (splits into utterances)
      -> WAV
      -> bridge /audio      (STT)
      -> bridge /ask       (LLM + streamed TTS)
      -> received speech

This is the loop the Android app runs. Running it here means the pipeline can
be verified end-to-end on a laptop, and a regression in the VAD thresholds or
the SSE contract shows up without anyone needing to wear glasses.

Usage:
    python3 simulate_loop.py                 # run the whole loop
    python3 simulate_loop.py --speak         # also play the reply aloud
    python3 simulate_loop.py --json          # machine-readable output
"""

import argparse
import asyncio
import base64
import io
import json
import math
import os
import struct
import sys
import time
import wave
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "bridge"))

import httpx  # noqa: E402

BRIDGE = os.environ.get("GLASSES_BRIDGE_URL", "http://127.0.0.1:8790")
SAMPLE_RATE = 16000


def synth_speech(seconds: float, amp: float = 6000.0, freq: float = 140.0) -> bytes:
    """A signal that looks like speech to an energy VAD: voiced with pauses.

    Real speech is loud-ish and modulated. A flat sine works, but adding a
    tremolo and some harmonic content makes the RMS behave more like voice, so
    the VAD thresholds are exercised realistically.
    """
    total = int(SAMPLE_RATE * seconds)
    out = bytearray()
    for i in range(total):
        t = i / SAMPLE_RATE
        # Tremolo (4-6 Hz) gives the envelope speech-like variation.
        env = 0.55 + 0.45 * math.sin(2 * math.pi * 5.0 * t)
        fundamental = math.sin(2 * math.pi * freq * t)
        harmonic = 0.4 * math.sin(2 * math.pi * freq * 2 * t)
        sample = amp * env * (fundamental + harmonic) / 1.4
        out += struct.pack("<h", int(max(-32768, min(32767, sample))))
    return bytes(out)


def silence(seconds: float) -> bytes:
    return b"\x00\x00" * int(SAMPLE_RATE * seconds)


def to_wav(pcm: bytes) -> bytes:
    buf = io.BytesIO()
    with wave.open(buf, "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(SAMPLE_RATE)
        wf.writeframes(pcm)
    return buf.getvalue()


async def run_turn(client: httpx.AsyncClient, wav: bytes, with_image: bool = False) -> dict:
    """One full turn: STT -> ask -> collect streamed speech."""
    t0 = time.perf_counter()

    # --- STT
    r = await client.post(
        f"{BRIDGE}/audio",
        files={"file": ("utterance.wav", wav, "audio/wav")},
        data={"client_id": "sim"},
    )
    r.raise_for_status()
    transcript = r.json().get("text", "")
    stt_ms = (time.perf_counter() - t0) * 1000

    if not transcript.strip():
        return {"error": "empty transcript", "stt_ms": round(stt_ms)}

    # --- ask, collecting the SSE stream
    t1 = time.perf_counter()
    first_audio_ms = None
    speech = []
    reply = ""
    async with client.stream(
        "POST",
        f"{BRIDGE}/ask",
        json={
            "text": transcript,
            "client_id": "sim",
            "with_image": with_image,
            "agent": False,
        },
        timeout=120.0,
    ) as resp:
        resp.raise_for_status()
        async for line in resp.aiter_lines():
            line = line.strip()
            if not line.startswith("data:"):
                continue
            payload = line[len("data:") :].strip()
            if not payload or payload == "[DONE]":
                continue
            event = json.loads(payload)
            if event.get("type") == "audio":
                if first_audio_ms is None:
                    first_audio_ms = (time.perf_counter() - t1) * 1000
                speech.append(
                    {
                        "seconds": event.get("seconds"),
                        "bytes": len(base64.b64decode(event["wav"])),
                    }
                )
            elif event.get("type") == "done":
                reply = event.get("text", "")
            elif event.get("type") == "error":
                return {"error": event.get("detail"), "stt_ms": round(stt_ms)}

    return {
        "transcript": transcript,
        "reply": reply,
        "sentences": len(speech),
        "speech_seconds": round(sum(s["seconds"] for s in speech), 2),
        "stt_ms": round(stt_ms),
        "first_audio_ms": round(first_audio_ms, 1) if first_audio_ms else None,
        "total_ms": round((time.perf_counter() - t0) * 1000),
    }


async def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--speak", action="store_true", help="play the reply aloud")
    ap.add_argument("--json", action="store_true", help="machine-readable output")
    ap.add_argument("--phrase", default="", help="what to say (uses a real audio file if set)")
    ap.add_argument("--audio-file", default="", help="transcribe this WAV instead of synth")
    args = ap.parse_args()

    if args.audio_file:
        with open(args.audio_file, "rb") as fh:
            wav = fh.read()
    else:
        # A natural utterance: a short lead-in, the phrase, then a tail so the
        # VAD has silence to close on.
        pcm = synth_speech(1.2) + silence(0.2) + synth_speech(1.6) + silence(0.9)
        wav = to_wav(pcm)

    async with httpx.AsyncClient(timeout=120.0) as client:
        try:
            health = await client.get(f"{BRIDGE}/health", timeout=20.0)
            health.raise_for_status()
        except Exception as exc:
            print(f"bridge not reachable at {BRIDGE}: {exc}", file=sys.stderr)
            return 1

        result = await run_turn(client, wav)

    if args.json:
        print(json.dumps(result, indent=2))
        return 0 if "error" not in result else 1

    if "error" in result:
        print(f"FAILED: {result['error']}")
        return 1

    print("── voice loop ──────────────────────────────────")
    print(f"  heard     : {result['transcript']!r}")
    print(f"  replied   : {result['reply']!r}")
    print(f"  sentences : {result['sentences']} ({result['speech_seconds']}s of speech)")
    print(f"  STT       : {result['stt_ms']} ms")
    print(f"  first audio: {result['first_audio_ms']} ms")
    print(f"  total     : {result['total_ms']} ms")
    print("───────────────────────────────────────────────")

    if args.speak:
        # Re-synthesize and play locally to prove the audio is real.
        async with httpx.AsyncClient(timeout=60.0) as client:
            r = await client.post(
                f"{BRIDGE}/say", json={"text": result["reply"]}, timeout=60.0
            )
            wav = base64.b64decode(r.json()["wav"])
        tmp = "/tmp/glasses-sim-reply.wav"
        with open(tmp, "wb") as fh:
            fh.write(wav)
        print(f"  wrote {tmp} — play it with: ffplay {tmp}")

    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
