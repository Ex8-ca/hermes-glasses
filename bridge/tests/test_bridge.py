"""
Tests for the Hermes Glasses bridge.

These cover the pieces that are easy to get subtly wrong and impossible to
eyeball on a phone: sentence splitting (a double-speak bug here is very
visible to the wearer), the SSE event contract the Android app parses, the
crash-report round trip, and the VAD's utterance boundaries.

Run with:
    python3 -m pytest bridge/tests/ -v
"""

import asyncio
import json
import os
import sys
import wave
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import bridge as B  # noqa: E402


# --------------------------------------------------------------------------- helpers


def make_wav(seconds: float = 1.0, rate: int = 16000, tone: int = 440) -> bytes:
    """A mono 16-bit WAV containing a sine tone."""
    import struct

    frames = bytearray()
    for i in range(int(rate * seconds)):
        value = int(12000 * ((i * tone / rate) % 2 - 1) / 1)
        frames += struct.pack("<h", value)
    buf = wave.open(os.devnull, "wb") if False else None
    import io

    out = io.BytesIO()
    with wave.open(out, "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(rate)
        wf.writeframes(bytes(frames))
    return out.getvalue()


def sse_events(raw: str):
    """Parse an SSE body into a list of event dicts."""
    events = []
    for line in raw.splitlines():
        line = line.strip()
        if not line.startswith("data:"):
            continue
        payload = line[len("data:") :].strip()
        if not payload or payload == "[DONE]":
            continue
        events.append(json.loads(payload))
    return events


# --------------------------------------------------------------------------- config


def test_env_files_precedence(tmp_path, monkeypatch):
    """A local .env must win over the Hermes one, per key."""
    hermes_env = tmp_path / "hermes.env"
    hermes_env.write_text("API_SERVER_KEY=from-hermes\nOTHER=hermes-only\n")
    local_env = tmp_path / "local.env"
    local_env.write_text("API_SERVER_KEY=from-local\n")

    merged = B._load_env_files([str(local_env), str(hermes_env)])

    # The first file in the list wins for keys it defines.
    assert merged["API_SERVER_KEY"] == "from-local"
    assert merged["OTHER"] == "hermes-only"


def test_env_loader_skips_missing_files(tmp_path):
    merged = B._load_env_files([str(tmp_path / "nope.env")])
    assert merged == {}


def test_env_loader_ignores_comments_and_blanks(tmp_path):
    env = tmp_path / "x.env"
    env.write_text("# comment\n\nKEY=value\n  \nOTHER='quoted'\n")
    merged = B._load_env_files([str(env)])
    assert merged["KEY"] == "value"
    assert merged["OTHER"] == "quoted"


# --------------------------------------------------------------------------- sentences


def test_sentence_boundaries_simple():
    bounds = B._sentence_boundaries("Hello there. How are you? Fine!")
    assert bounds == [len("Hello there. "), len("Hello there. How are you? "), len("Hello there. How are you? Fine!")]


def test_sentence_boundaries_ignores_decimals():
    """'3.5' must not split — a false boundary would clip a word."""
    bounds = B._sentence_boundaries("It costs 3.5 dollars total.")
    assert bounds == [len("It costs 3.5 dollars total.")]


def test_sentence_boundaries_no_trailing_punctuation():
    assert B._sentence_boundaries("no terminator here") == []


def test_sentence_boundaries_handles_quotes():
    text = 'He said "stop." Then he left.'
    bounds = B._sentence_boundaries(text)
    assert bounds
    # The boundary must land after the closing quote, not inside it.
    assert text[: bounds[0]].strip().endswith('"')
    assert text[: bounds[0]].strip().endswith('stop."')


def test_split_sentences_helper():
    parts = B._split_sentences("One. Two. Three.")
    assert parts == ["One.", "Two.", "Three."]


# --------------------------------------------------------------------------- VAD


def test_vad_detects_speech_then_silence():
    vad = B.EnergyVad()
    loud = b"\x00\x10" * 320  # ~20ms, RMS 4096 — well over the 350 threshold
    quiet = b"\x00\x00" * 320  # silence

    # Silence alone must not trigger.
    for _ in range(10):
        assert vad.push(quiet) == (False, False)

    # Speech opens the utterance.
    speech = (False, False)
    for _ in range(5):
        speech = vad.push(loud)
    assert speech[0] is True
    assert speech[1] is False

    # 700ms of silence closes it.
    ended = (False, False)
    for _ in range(40):
        ended = vad.push(quiet)
        if ended[1]:
            break
    assert ended[1] is True


def test_vad_silence_never_opens():
    vad = B.EnergyVad()
    quiet = b"\x00\x00" * 320  # silence
    for _ in range(50):
        assert vad.push(quiet) == (False, False)


# --------------------------------------------------------------------------- endpoints


@pytest.mark.asyncio
async def test_crash_endpoint_round_trip(tmp_path, monkeypatch):
    """A crash report from the phone must be readable back from the server."""
    monkeypatch.setattr(B, "AUDIO_DIR", str(tmp_path))

    payload = {
        "app": "hermes-glasses",
        "type": "java.lang.RuntimeException",
        "message": "Unable to start activity",
        "stack": "at com.chillygeek.hermesglasses.MainActivity.onCreate(MainActivity.kt:42)",
        "device": "Pixel 10 Pro XL",
        "android": "17",
    }
    resp = await B.receive_crash(payload)
    assert resp["ok"] is True
    assert resp["received"]["type"] == "java.lang.RuntimeException"

    read_back = await B.get_last_crash()
    assert read_back["crash"]["message"] == "Unable to start activity"
    assert "MainActivity.kt:42" in read_back["crash"]["stack"]


@pytest.mark.asyncio
async def test_crash_endpoint_returns_none_when_empty(tmp_path, monkeypatch):
    monkeypatch.setattr(B, "AUDIO_DIR", str(tmp_path))
    read_back = await B.get_last_crash()
    assert read_back["crash"] is None


@pytest.mark.asyncio
async def test_vad_endpoint_reports_thresholds():
    out = await B.vad({})
    assert out["sample_rate"] == 16000
    assert out["silence_frames_to_end"] > 0


@pytest.mark.asyncio
async def test_session_reset_clears_state():
    state = B._state("reset-client")
    state.hermes_session = "abc"
    state.current_frame = b"jpeg"
    state.last_turns.append("x")

    await B.session_reset({"client_id": "reset-client"})

    state = B._state("reset-client")
    assert state.hermes_session is None
    assert state.current_frame is None
    assert state.last_turns == []


@pytest.mark.asyncio
async def test_frame_upload_stores_jpeg():
    resp = await B.upload_frame(_FakeUpload(b"\xff\xd8\xff\xe0jpegdata"), "frame-client")
    assert resp["ok"] is True
    assert resp["bytes"] == len(b"\xff\xd8\xff\xe0jpegdata")
    assert B._state("frame-client").current_frame == b"\xff\xd8\xff\xe0jpegdata"


class _FakeUpload:
    """Minimal stand-in for fastapi.UploadFile."""

    def __init__(self, data: bytes):
        self._data = data

    async def read(self) -> bytes:
        return self._data


# --------------------------------------------------------------------------- streaming


@pytest.mark.asyncio
async def test_ask_streams_sentences_then_done(monkeypatch):
    """The Android client parses exactly this event sequence."""

    async def fake_turn(client_id, text, image, use_agent):
        for piece in ["The capital ", "of France ", "is Paris."]:
            yield {"type": "text", "text": piece}

    async def fake_synth(text: str) -> bytes:
        return b"RIFF-fake-wav-for-" + text.encode()

    monkeypatch.setattr(B, "_hermes_turn", fake_turn)
    monkeypatch.setattr(B, "_synth", fake_synth)

    resp = await B.ask({"text": "What is the capital of France?", "client_id": "t"}, None)
    body = "".join([chunk async for chunk in resp.body_iterator])

    events = sse_events(body)
    types = [e["type"] for e in events]

    assert "done" in types
    assert types[-1] == "done"
    assert types.count("audio") == 1

    done = events[-1]
    assert done["text"] == "The capital of France is Paris."

    audio = [e for e in events if e["type"] == "audio"][0]
    import base64

    assert base64.b64decode(audio["wav"]).startswith(b"RIFF")


@pytest.mark.asyncio
async def test_ask_speaks_each_sentence_as_it_completes(monkeypatch):
    """Two sentences must produce two audio events, in order."""

    async def fake_turn(client_id, text, image, use_agent):
        yield {"type": "text", "text": "First part. "}
        yield {"type": "text", "text": "Second part."}

    async def fake_synth(text: str) -> bytes:
        return b"W" + text.encode()

    monkeypatch.setattr(B, "_hermes_turn", fake_turn)
    monkeypatch.setattr(B, "_synth", fake_synth)

    resp = await B.ask({"text": "go", "client_id": "t2"}, None)
    body = "".join([chunk async for chunk in resp.body_iterator])
    events = sse_events(body)

    audios = [e for e in events if e["type"] == "audio"]
    assert len(audios) == 2
    assert b"First part." in base64.b64decode(audios[0]["wav"])
    assert b"Second part." in base64.b64decode(audios[1]["wav"])


@pytest.mark.asyncio
async def test_ask_does_not_repeat_a_sentence(monkeypatch):
    """The same sentence must not be spoken twice as the buffer grows.

    This is the bug the character-offset tracking exists to prevent: if the
    splitter re-scanned the whole buffer, the first sentence would be spoken
    again every time more text arrived.
    """

    async def fake_turn(client_id, text, image, use_agent):
        # Simulate token-by-token arrival of a two-sentence reply.
        for piece in ["Hello ", "there. ", "How ", "are ", "you?"]:
            yield {"type": "text", "text": piece}

    spoken = []

    async def fake_synth(text: str) -> bytes:
        spoken.append(text)
        return b"W"

    monkeypatch.setattr(B, "_hermes_turn", fake_turn)
    monkeypatch.setattr(B, "_synth", fake_synth)

    resp = await B.ask({"text": "go", "client_id": "t3"}, None)
    body = "".join([chunk async for chunk in resp.body_iterator])
    events = sse_events(body)

    # The spoken sentences must be exactly the two sentences, once each.
    assert spoken == ["Hello there.", "How are you?"], f"spoken={spoken}"

    # And the stream must not carry duplicate audio events for the same text.
    audios = [e for e in events if e["type"] == "audio"]
    assert len(audios) == len(spoken), f"{len(audios)} audio events for {len(spoken)} sentences"


@pytest.mark.asyncio
async def test_ask_reports_errors_as_an_event(monkeypatch):
    """A failing turn must emit an error event, not blow up the stream."""

    async def fake_turn(client_id, text, image, use_agent):
        raise RuntimeError("upstream exploded")
        yield  # pragma: no cover — makes this an async generator

    monkeypatch.setattr(B, "_hermes_turn", fake_turn)

    resp = await B.ask({"text": "go", "client_id": "t4"}, None)
    body = "".join([chunk async for chunk in resp.body_iterator])
    events = sse_events(body)

    assert events[-1]["type"] == "error"
    assert "upstream exploded" in events[-1]["detail"]


@pytest.mark.asyncio
async def test_ask_rejects_empty_text():
    with pytest.raises(Exception):
        await B.ask({"text": "   ", "client_id": "t5"}, None)


# --------------------------------------------------------------------------- stt


@pytest.mark.asyncio
async def test_transcribe_prefers_local_server(monkeypatch):
    """The local whisper server is preferred; Groq is only a fallback."""

    called = {"local": False, "groq": False}

    class _Resp:
        def __init__(self, status, payload=None, text=""):
            self.status_code = status
            self._payload = payload
            self._text = text

        def json(self):
            return self._payload or {}

        @property
        def text(self):
            return self._text

    class _Client:
        async def __aenter__(self):
            return self

        async def __aexit__(self, *a):
            return False

        async def post(self, url, **kwargs):
            if "9001" in url or "127.0.0.1" in url:
                called["local"] = True
                return _Resp(200, {"text": "local transcript"})
            called["groq"] = True
            return _Resp(200, text="groq transcript")

    monkeypatch.setattr(B.httpx, "AsyncClient", lambda **kw: _Client())
    monkeypatch.setattr(B, "GROQ_KEY", "gsk-test")

    out = await B._transcribe_wav(make_wav(0.2))
    assert out == "local transcript"
    assert called["local"] is True
    assert called["groq"] is False


@pytest.mark.asyncio
async def test_transcribe_falls_back_to_groq(monkeypatch):
    """If the local server is down, Groq must take over."""

    called = {"local": False, "groq": False}

    class _Resp:
        def __init__(self, status, payload=None, text=""):
            self.status_code = status
            self._payload = payload
            self._text = text

        def json(self):
            return self._payload or {}

        @property
        def text(self):
            return self._text

    class _Client:
        async def __aenter__(self):
            return self

        async def __aexit__(self, *a):
            return False

        async def post(self, url, **kwargs):
            if "9001" in url or "127.0.0.1" in url:
                called["local"] = True
                raise B.httpx.HTTPError("connection refused")
            called["groq"] = True
            return _Resp(200, text="groq transcript")

    monkeypatch.setattr(B.httpx, "AsyncClient", lambda **kw: _Client())
    monkeypatch.setattr(B, "GROQ_KEY", "gsk-test")

    out = await B._transcribe_wav(make_wav(0.2))
    assert out == "groq transcript"
    assert called["local"] is True
    assert called["groq"] is True


# --------------------------------------------------------------------------- import


import base64  # noqa: E402  (used above; imported late to keep the header tidy)
