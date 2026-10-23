"""
hermes-glasses-bridge — the server half of the Meta AI glasses assistant.

Runs on ai5080. The phone app (Kotlin, built on Meta's Wearables DAT) streams two
independent things to this service:

  POST /audio   multipart file=<wav>          -> transcribed text (Groq or local whisper)
  POST /frame   multipart file=<jpeg>          -> stored as the "current view"
  POST /ask     json {text, with_image}        -> Hermes reply (SSE streamed) + sentence TTS
  POST /say     json {text}                    -> TTS only (returns wav path)
  GET  /health                                -> status of every downstream dependency

The phone is responsible for all Bluetooth routing (HFP mic in, A2DP out) because
Meta's SDK has no audio API — audio is plain BT profiles on the handset.

Design notes that matter:
  * Latency budget for a conversational turn is ~1s STT + ~1s LLM + ~0.8s TTS per
    sentence. Sentence-level TTS streaming (see /ask) is what makes back-and-forth
    feel responsive instead of a 6s pause before any audio comes out.
  * Hermes' agent loop is slow (2-20s) because it does reasoning + tool scaffolding.
    For the glasses fast path we call MiniMax directly (1.4s) and only fall back to
    the Hermes agent when the request asks for memory/tools.
  * The Hermes session id is sticky per client so conversation memory survives
    across turns (X-Hermes-Session-Id on /v1/chat/completions).
"""

import asyncio
import base64
import io
import json
import os
import re
import sys
import time
import uuid
import wave
from dataclasses import dataclass, field
from typing import AsyncIterator, Optional

import httpx
import numpy as np
import uvicorn
from fastapi import FastAPI, File, Form, HTTPException, Request, UploadFile
from fastapi.responses import JSONResponse, StreamingResponse

# --------------------------------------------------------------------------- config
#
# Everything is overridable by environment variable so the bridge can run on
# someone else's machine. Copy .env.example to .env and fill it in.

# Where the Hermes API server lives. The bridge talks to it over plain HTTP.
HERMES_BASE = os.environ.get("GLASSES_HERMES_BASE", "http://127.0.0.1:6789")
# Bearer key for that API. Read from GLASSES_HERMES_KEY or from the env file.
HERMES_KEY = os.environ.get("GLASSES_HERMES_KEY", "")

# Local TTS (chatterbox-tts-api) and STT (faster-whisper) endpoints.
TTS_BASE = os.environ.get("GLASSES_TTS_BASE", "http://127.0.0.1:4324")
STT_BASE = os.environ.get("GLASSES_STT_BASE", "http://127.0.0.1:9001")

# Cloud fallbacks.
GROQ_BASE = os.environ.get("GLASSES_GROQ_BASE", "https://api.groq.com/openai/v1")
MINIMAX_BASE = os.environ.get("GLASSES_MINIMAX_BASE", "https://api.minimax.io/v1")

# Fast conversational model. The Hermes agent loop is far too slow for a
# glasses turn (2-20s); this answers in ~1.4s.
FAST_MODEL = os.environ.get("GLASSES_FAST_MODEL", "MiniMax-M3.1-Flash")
TTS_VOICE = os.environ.get("GLASSES_TTS_VOICE", "male_rickmorty")
TTS_LANGUAGE = os.environ.get("GLASSES_TTS_LANGUAGE", "en")

# Where to keep crash reports and scratch audio.
WORK_DIR = os.environ.get("GLASSES_WORK_DIR", os.path.join(os.path.expanduser("~"), ".glasses-bridge"))
AUDIO_DIR = os.path.join(WORK_DIR, "audio")
os.makedirs(AUDIO_DIR, exist_ok=True)

# Env files to read credentials from, in order. The first one that exists wins
# per-key, so a local .env overrides the Hermes one.
ENV_FILES = [
    os.path.join(os.path.dirname(os.path.abspath(__file__)), ".env"),
    os.path.expanduser("~/.hermes/.env"),
]


def _load_env_files(paths: list[str]) -> dict:
    """Minimal .env reader — avoids depending on the hermes venv's python-dotenv."""
    out = {}
    for path in reversed(paths):
        try:
            with open(path) as fh:
                for line in fh:
                    line = line.strip()
                    if not line or line.startswith("#") or "=" not in line:
                        continue
                    k, v = line.split("=", 1)
                    v = v.strip().strip('"').strip("'")
                    out[k.strip()] = v
        except OSError:
            pass
    return out


ENV = _load_env_files(ENV_FILES)
HERMES_KEY = HERMES_KEY or ENV.get("API_SERVER_KEY", "") or ENV.get("GLASSES_HERMES_KEY", "")
GROQ_KEY = ENV.get("GROQ_API_KEY", "")
MINIMAX_KEY = ENV.get("MINIMAX_API_KEY", "")


def _require(name: str, value: str) -> str:
    if not value:
        raise HTTPException(
            status_code=500,
            detail=f"missing {name}: set it in {HERMES_ENV} or the environment",
        )
    return value


app = FastAPI(title="hermes-glasses-bridge", version="1.0.0")


# --------------------------------------------------------------------------- state


@dataclass
class ClientState:
    """Per-client conversation state. Keyed by the phone's session id."""

    hermes_session: Optional[str] = None
    current_frame: Optional[bytes] = None
    last_turns: list = field(default_factory=list)


CLIENTS: dict[str, ClientState] = {}


def _state(client_id: str) -> ClientState:
    return CLIENTS.setdefault(client_id, ClientState())


# --------------------------------------------------------------------------- health


@app.get("/health")
async def health() -> JSONResponse:
    """Report reachability + latency of every downstream dependency.

    A glasses app should refuse to start if the chain is broken — a silent
    failure looks identical to "the assistant is ignoring me".
    """
    checks: dict[str, dict] = {}

    async def timed(name, coro):
        t0 = time.perf_counter()
        try:
            ok, detail = await coro
            checks[name] = {
                "ok": ok,
                "ms": round((time.perf_counter() - t0) * 1000),
                "detail": detail,
            }
        except Exception as exc:  # noqa: BLE001 — health must never raise
            checks[name] = {
                "ok": False,
                "ms": round((time.perf_counter() - t0) * 1000),
                "detail": f"{type(exc).__name__}: {exc}",
            }

    async def c_hermes():
        async with httpx.AsyncClient(timeout=5.0) as c:
            r = await c.get(
                f"{HERMES_BASE}/v1/models",
                headers={"Authorization": f"Bearer {HERMES_KEY}"},
            )
            return r.status_code == 200, f"HTTP {r.status_code}"

    async def c_tts():
        async with httpx.AsyncClient(timeout=8.0) as c:
            r = await c.get(f"{TTS_BASE}/health")
            dev = None
            try:
                dev = r.json().get("device")
            except Exception:  # noqa: BLE001
                pass
            return r.status_code == 200, f"HTTP {r.status_code} device={dev}"

    async def c_stt():
        async with httpx.AsyncClient(timeout=8.0) as c:
            r = await c.get(f"{STT_BASE}/health")
            return r.status_code == 200, f"HTTP {r.status_code}"

    await asyncio.gather(
        timed("hermes", c_hermes()),
        timed("tts", c_tts()),
        timed("stt", c_stt()),
    )

    return JSONResponse(
        {
            "status": "ok" if all(v["ok"] for v in checks.values()) else "degraded",
            "checks": checks,
            "fast_model": FAST_MODEL,
            "clients": len(CLIENTS),
        }
    )


# --------------------------------------------------------------------------- stt


async def _transcribe_wav(audio: bytes) -> str:
    """Transcribe with the local whisper server; fall back to Groq.

    The local server on :9001 runs faster-whisper on GPU (~0.45s for a short
    utterance). Groq is the fallback if it is down.
    """
    files = {"file": ("audio.wav", audio, "audio/wav")}
    data = {"model": "whisper-1"}

    async with httpx.AsyncClient(timeout=30.0) as c:
        try:
            r = await c.post(f"{STT_BASE}/v1/audio/transcriptions", files=files, data=data)
            if r.status_code == 200:
                text = r.json().get("text", "")
                if text.strip():
                    return text.strip()
        except httpx.HTTPError:
            pass

    if not GROQ_KEY:
        raise HTTPException(status_code=503, detail="no STT backend available")

    files = {"file": ("audio.wav", audio, "audio/wav")}
    data = {"model": "whisper-large-v3-turbo", "response_format": "text"}
    async with httpx.AsyncClient(timeout=30.0) as c:
        r = await c.post(
            f"{GROQ_BASE}/audio/transcriptions",
            headers={"Authorization": f"Bearer {GROQ_KEY}"},
            files=files,
            data=data,
        )
        if r.status_code != 200:
            raise HTTPException(status_code=502, detail=f"STT failed: {r.text[:200]}")
        return r.text.strip()


@app.post("/audio")
async def transcribe(file: UploadFile = File(...), client_id: str = Form("default")):
    """Transcribe a WAV utterance. Returns {'text': ...}."""
    raw = await file.read()
    if not raw:
        raise HTTPException(status_code=400, detail="empty audio")
    text = await _transcribe_wav(raw)
    return {"text": text, "client_id": client_id}


# --------------------------------------------------------------------------- frames


@app.post("/frame")
async def upload_frame(file: UploadFile = File(...), client_id: str = Form("default")):
    """Store the current camera frame as JPEG. Returns the byte count."""
    raw = await file.read()
    if not raw:
        raise HTTPException(status_code=400, detail="empty frame")
    st = _state(client_id)
    st.current_frame = raw
    return {"ok": True, "bytes": len(raw), "client_id": client_id}


# --------------------------------------------------------------------------- llm


def _split_sentences(text: str) -> list[str]:
    """Split into speakable chunks. TTS per sentence keeps first-audio latency low."""
    text = (text or "").strip()
    if not text:
        return []
    parts = re.split(r"(?<=[.!?])\s+", text)
    return [p.strip() for p in parts if p.strip()]


def _sentence_boundaries(text: str) -> list[int]:
    """Character offsets just past each sentence-ending punctuation mark.

    Returns offsets into *text*. Only a terminator followed by whitespace (or
    end-of-text) counts, so decimals like "3.5" and abbreviations don't split.
    """
    out: list[int] = []
    for match in re.finditer(r"[.!?]+[\"')\]]*(\s+|$)", text):
        out.append(match.end())
    return out


async def _synth_safe(text: str) -> bytes:
    """Synthesize, swallowing failures — a TTS error must not kill the turn."""
    if not text.strip():
        return b""
    try:
        return await _synth(text)
    except HTTPException:
        return b""
    except Exception:  # noqa: BLE001
        return b""


async def _hermes_turn(
    client_id: str,
    text: str,
    image: Optional[bytes],
    use_agent: bool,
) -> AsyncIterator[dict]:
    """Yield {'type': 'text', ...} chunks then {'type': 'done', ...}.

    Two backends:
      * fast  — MiniMax direct, ~1.4s, no memory/tools
      * agent — Hermes /v1/chat/completions with a sticky session id, so the
                conversation keeps memory (and can use tools)
    """
    st = _state(client_id)
    headers = {"Authorization": f"Bearer {_require('API_SERVER_KEY', HERMES_KEY)}"}
    if st.hermes_session:
        headers["X-Hermes-Session-Id"] = st.hermes_session

    content: list[dict] = [{"type": "text", "text": text}]
    if image:
        b64 = base64.b64encode(image).decode()
        content.append(
            {"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{b64}"}}
        )

    body = {
        "model": "hermes-agent",
        "stream": True,
        "messages": [{"role": "user", "content": content}],
    }

    async with httpx.AsyncClient(timeout=180.0) as c:
        if use_agent:
            async with c.stream(
                "POST",
                f"{HERMES_BASE}/v1/chat/completions",
                headers=headers,
                json=body,
                timeout=180.0,
            ) as resp:
                if resp.status_code != 200:
                    detail = (await resp.aread()).decode(errors="replace")[:300]
                    raise HTTPException(status_code=502, detail=f"hermes: {detail}")
                async for line in resp.aiter_lines():
                    if not line.startswith("data:"):
                        continue
                    payload = line[5:].strip()
                    if payload == "[DONE]":
                        break
                    try:
                        obj = json.loads(payload)
                    except json.JSONDecodeError:
                        continue
                    for choice in obj.get("choices", []):
                        piece = (choice.get("delta") or {}).get("content")
                        if piece:
                            yield {"type": "text", "text": piece}
        else:
            mm_headers = {"Authorization": f"Bearer {_require('MINIMAX_API_KEY', MINIMAX_KEY)}"}
            mm_body = {
                "model": FAST_MODEL,
                "stream": True,
                "messages": [{"role": "user", "content": content}],
            }
            async with c.stream(
                "POST",
                f"{MINIMAX_BASE}/chat/completions",
                headers=mm_headers,
                json=mm_body,
                timeout=60.0,
            ) as resp:
                if resp.status_code != 200:
                    detail = (await resp.aread()).decode(errors="replace")[:300]
                    raise HTTPException(status_code=502, detail=f"minimax: {detail}")
                async for line in resp.aiter_lines():
                    if not line.startswith("data:"):
                        continue
                    payload = line[5:].strip()
                    if payload == "[DONE]":
                        break
                    try:
                        obj = json.loads(payload)
                    except json.JSONDecodeError:
                        continue
                    for choice in obj.get("choices", []):
                        piece = (choice.get("delta") or {}).get("content")
                        if piece:
                            yield {"type": "text", "text": piece}


# --------------------------------------------------------------------------- tts


async def _synth(text: str) -> bytes:
    """Synthesize one chunk of speech. Returns WAV bytes."""
    if not text.strip():
        return b""
    payload = {"input": text, "voice": TTS_VOICE, "language": TTS_LANGUAGE}
    async with httpx.AsyncClient(timeout=120.0) as c:
        r = await c.post(f"{TTS_BASE}/tts", json=payload)
        if r.status_code != 200:
            raise HTTPException(
                status_code=502, detail=f"tts failed: {r.status_code} {r.text[:200]}"
            )
        return r.content


def _wav_duration_seconds(raw: bytes) -> float:
    try:
        with wave.open(io.BytesIO(raw)) as wf:
            return wf.getnframes() / float(wf.getframerate() or 1)
    except Exception:  # noqa: BLE001
        return 0.0


@app.post("/say")
async def say(payload: dict):
    """Synthesize speech for text. Returns {'wav': base64, 'seconds': float}."""
    text = str(payload.get("text", ""))
    raw = await _synth(text)
    return {
        "wav": base64.b64encode(raw).decode(),
        "seconds": round(_wav_duration_seconds(raw), 2),
        "format": "wav",
    }


# --------------------------------------------------------------------------- ask


@app.post("/ask")
async def ask(payload: dict, request: Request):
    """The main conversation turn.

    Request:  {"text": "...", "client_id": "phone", "with_image": true, "agent": false}
    Response: SSE stream of events:
        {"type":"text","text":"..."}       as the LLM produces it
        {"type":"audio","wav":"<b64>"}     once per sentence, ready to play
        {"type":"done","text":"full reply","sentences":N}
    """
    text = str(payload.get("text", "")).strip()
    if not text:
        raise HTTPException(status_code=400, detail="empty text")

    client_id = str(payload.get("client_id", "default"))
    with_image = bool(payload.get("with_image", False))
    use_agent = bool(payload.get("agent", False))

    st = _state(client_id)
    image = st.current_frame if with_image else None

    async def event_stream() -> AsyncIterator[str]:
        buf: list[str] = []
        spoken_upto = 0  # chars of buf already synthesized
        sentences: list[str] = []
        try:
            async for chunk in _hermes_turn(client_id, text, image, use_agent):
                piece = chunk.get("text", "")
                if not piece:
                    continue
                buf.append(piece)
                yield _sse({"type": "text", "text": piece})

                # Speak completed sentences as they appear, so the wearer hears
                # the first sentence while the rest is still being generated.
                # Track a character offset rather than a sentence list so that
                # re-splitting the growing buffer can never double-speak.
                joined = "".join(buf)
                for boundary in _sentence_boundaries(joined):
                    if boundary <= spoken_upto:
                        continue
                    sentence = joined[spoken_upto:boundary].strip()
                    spoken_upto = boundary
                    if not sentence:
                        continue
                    sentences.append(sentence)
                    wav = await _synth_safe(sentence)
                    if wav:
                        yield _sse(
                            {
                                "type": "audio",
                                "wav": base64.b64encode(wav).decode(),
                                "seconds": round(_wav_duration_seconds(wav), 2),
                            }
                        )

            full = "".join(buf).strip()
            # Speak the trailing fragment that had no terminal punctuation.
            if len(full) > spoken_upto:
                tail = full[spoken_upto:].strip()
                spoken_upto = len(full)
                if tail:
                    sentences.append(tail)
                    wav = await _synth_safe(tail)
                    if wav:
                        yield _sse(
                            {
                                "type": "audio",
                                "wav": base64.b64encode(wav).decode(),
                                "seconds": round(_wav_duration_seconds(wav), 2),
                            }
                        )
            yield _sse({"type": "done", "text": full, "sentences": len(sentences)})
        except HTTPException as exc:
            yield _sse({"type": "error", "detail": exc.detail})
        except Exception as exc:  # noqa: BLE001
            yield _sse({"type": "error", "detail": f"{type(exc).__name__}: {exc}"})

    return StreamingResponse(
        event_stream(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )


def _sse(obj: dict) -> str:
    return f"data: {json.dumps(obj)}\n\n"


@app.post("/session/reset")
async def session_reset(payload: dict):
    """Clear the conversation memory for a client (new conversation)."""
    client_id = str(payload.get("client_id", "default"))
    st = _state(client_id)
    st.hermes_session = None
    st.current_frame = None
    st.last_turns.clear()
    return {"ok": True, "client_id": client_id}


@app.post("/crash")
async def receive_crash(payload: dict):
    """Receive a crash report from the phone app.

    Without logcat access (no adb pairing), this is the only way to see why the
    app died. The report is written to a file and echoed in the response.
    """
    crash_file = os.path.join(AUDIO_DIR, "last-crash.json")
    try:
        with open(crash_file, "w") as fh:
            json.dump(payload, fh, indent=2)
    except OSError as exc:
        return JSONResponse({"ok": False, "error": str(exc)}, status_code=500)

    summary = {
        "type": payload.get("type"),
        "message": payload.get("message"),
        "device": payload.get("device"),
        "android": payload.get("android"),
    }
    print(f"[CRASH] {summary}", flush=True)
    return {"ok": True, "received": summary}


@app.get("/crash")
async def get_last_crash():
    """Read back the most recent crash report."""
    crash_file = os.path.join(AUDIO_DIR, "last-crash.json")
    if not os.path.exists(crash_file):
        return {"crash": None}
    try:
        with open(crash_file) as fh:
            return {"crash": json.load(fh)}
    except (OSError, json.JSONDecodeError) as exc:
        return {"crash": None, "error": str(exc)}


# --------------------------------------------------------------------------- vad


class EnergyVad:
    """Simple energy-based voice activity detection over 16-bit mono PCM.

    The glasses mic via in-band PCM is not beamformed on firmware 129, so a
    neural VAD would be better — but energy gating is enough to find utterance
    boundaries in a quiet room and adds no dependency.
    """

    def __init__(self, sample_rate: int = 16000, frame_ms: int = 20):
        self.sample_rate = sample_rate
        self.frame_samples = int(sample_rate * frame_ms / 1000)
        self.threshold = 350.0  # RMS amplitude above this counts as speech
        self.speech_frames = 0
        self.silence_frames = 0
        self.in_speech = False

    def _rms(self, block: bytes) -> float:
        if not block:
            return 0.0
        samples = np.frombuffer(block, dtype=np.int16)
        if samples.size == 0:
            return 0.0
        return float(np.sqrt(np.mean(samples.astype(np.float32) ** 2)))

    def push(self, block: bytes) -> tuple[bool, bool]:
        """Feed PCM. Returns (is_speech_now, utterance_just_ended)."""
        rms = self._rms(block)
        voiced = rms > self.threshold
        just_ended = False

        if voiced:
            self.speech_frames += 1
            self.silence_frames = 0
            if not self.in_speech and self.speech_frames >= 3:
                self.in_speech = True
        else:
            self.silence_frames += 1
            self.speech_frames = 0
            # 700ms of silence closes the utterance.
            if self.in_speech and self.silence_frames >= 35:
                self.in_speech = False
                just_ended = True

        return self.in_speech, just_ended


@app.post("/vad")
async def vad(payload: dict):
    """Server-side VAD helper so the phone can reuse the same thresholds."""
    return {
        "sample_rate": 16000,
        "frame_ms": 20,
        "threshold": 350.0,
        "silence_frames_to_end": 35,
        "speech_frames_to_start": 3,
    }


if __name__ == "__main__":
    port = int(os.environ.get("GLASSES_BRIDGE_PORT", "8790"))
    uvicorn.run(app, host="0.0.0.0", port=port, log_level="info")
