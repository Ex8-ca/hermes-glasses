"""
Tests for the Android-side logic that can be exercised off-device.

The UtteranceSplitter lives in the Kotlin app, but its behaviour is the part
most likely to break the conversation, so the algorithm is mirrored here in
Python and pinned by tests. The Kotlin implementation is kept in step with
this file — see `SpeechPlayer.kt` (class `UtteranceSplitter`).

The bug this file exists to prevent: push-to-talk used to call `reset()` on
release, which discarded the words the user had just spoken. The user would
press, talk, release — and nothing would be sent.
"""

import struct


class UtteranceSplitter:
    """Mirror of the Kotlin UtteranceSplitter."""

    def __init__(
        self,
        sample_rate: int = 16000,
        frame_ms: int = 20,
        threshold: float = 350.0,
        silence_frames_to_end: int = 35,
        speech_frames_to_start: int = 3,
    ):
        self.sample_rate = sample_rate
        self.frame_ms = frame_ms
        self.threshold = threshold
        self.silence_frames_to_end = silence_frames_to_end
        self.speech_frames_to_start = speech_frames_to_start
        self.frame_samples = int(sample_rate * frame_ms / 1000)

        self.buffer = bytearray()
        # Audio captured since the utterance opened. The scan buffer is consumed
        # as it is examined, so the utterance needs its own copy.
        self.utterance = bytearray()
        self.voiced_run = 0
        self.silent_run = 0
        self.speaking = False

    # ------------------------------------------------------------------ helpers

    @staticmethod
    def _rms(frame: bytes) -> float:
        if not frame:
            return 0.0
        count = len(frame) // 2
        if count == 0:
            return 0.0
        total = 0
        for i in range(0, len(frame) - 1, 2):
            sample = struct.unpack_from("<h", frame, i)[0]
            total += sample * sample
        return (total / count) ** 0.5

    @staticmethod
    def _pcm_to_wav(pcm: bytes, rate: int = 16000) -> bytes:
        import io
        import wave

        out = io.BytesIO()
        with wave.open(out, "wb") as wf:
            wf.setnchannels(1)
            wf.setsampwidth(2)
            wf.setframerate(rate)
            wf.writeframes(pcm)
        return out.getvalue()

    # ------------------------------------------------------------------ core

    def push(self, pcm: bytes) -> bytes | None:
        """Feed PCM. Returns a WAV utterance when one completes, else None."""
        self.buffer.extend(pcm)
        index = 0
        while len(self.buffer) - index >= self.frame_samples:
            frame = bytes(self.buffer[index : index + self.frame_samples])
            index += self.frame_samples
            voiced = self._rms(frame) > self.threshold

            if voiced:
                self.voiced_run += 1
                self.silent_run = 0
                if not self.speaking and self.voiced_run >= self.speech_frames_to_start:
                    self.speaking = True
                    # Start the utterance at the first voiced frame.
                    drop = (self.voiced_run - self.speech_frames_to_start) * self.frame_samples
                    if drop > 0 and index - drop > 0:
                        index -= drop
            else:
                self.silent_run += 1
                self.voiced_run = 0

            if self.speaking:
                # Copy the frame into the utterance AS IT IS SCANNED. The scan
                # buffer is consumed by this loop, so a copy taken after the
                # loop would find nothing — this is why the original
                # implementation lost every utterance.
                start = max(0, index - self.frame_samples)
                self.utterance.extend(self.buffer[start:index])

        if index > 0:
            del self.buffer[:index]

        if self.speaking and self.silent_run >= self.silence_frames_to_end:
            self.speaking = False
            self.voiced_run = 0
            self.silent_run = 0
            utterance = bytes(self.utterance)
            self.utterance.clear()
            self.buffer.clear()
            return self._pcm_to_wav(utterance)
        return None

    def flush(self) -> bytes | None:
        """Return any buffered audio as a finished utterance.

        Push-to-talk needs this: the words are still waiting for the silence
        that would normally close the utterance, and releasing the button is
        that signal.
        """
        if not self.utterance and not self.buffer:
            self.reset()
            return None
        utterance = bytes(self.utterance) if self.utterance else bytes(self.buffer)
        self.reset()
        return self._pcm_to_wav(utterance)

    def reset(self) -> None:
        self.buffer.clear()
        self.utterance.clear()
        self.voiced_run = 0
        self.silent_run = 0
        self.speaking = False


# --------------------------------------------------------------------------- helpers


def loud_frame(samples: int = 320, amplitude: int = 6000) -> bytes:
    """A frame of loud samples — the splitter sees this as speech."""
    return struct.pack("<h", amplitude) * samples


def quiet_frame(samples: int = 320) -> bytes:
    return b"\x00\x00" * samples


# --------------------------------------------------------------------------- tests


def test_silence_produces_nothing():
    splitter = UtteranceSplitter()
    for _ in range(50):
        assert splitter.push(quiet_frame()) is None


def test_speech_then_silence_closes_the_utterance():
    splitter = UtteranceSplitter()
    for _ in range(10):
        splitter.push(quiet_frame())

    # Speech opens the utterance.
    result = None
    for _ in range(8):
        result = splitter.push(loud_frame())
    assert result is None  # not closed yet — still speaking

    # 700ms of silence closes it.
    closed = None
    for _ in range(40):
        closed = splitter.push(quiet_frame())
        if closed is not None:
            break
    assert closed is not None
    assert closed.startswith(b"RIFF")


def test_diagnose_buffer_after_speech():
    """Why does flush() find nothing? The frames were consumed."""
    splitter = UtteranceSplitter()
    for _ in range(10):
        splitter.push(loud_frame())
    print(f"  buffer bytes left: {len(splitter.buffer)}")
    print(f"  speaking: {splitter.speaking}, voiced_run: {splitter.voiced_run}")
    assert len(splitter.buffer) == 0, "the buffer is empty — frames were consumed"


def test_flush_returns_the_pending_utterance():
    """THE REGRESSION: releasing push-to-talk must send what was said."""
    splitter = UtteranceSplitter()

    # Say something without any trailing silence.
    for _ in range(10):
        splitter.push(loud_frame())

    flushed = splitter.flush()
    assert flushed is not None, "flush must return the buffered utterance"
    assert flushed.startswith(b"RIFF")

    # And the buffer must be empty afterwards.
    assert splitter.flush() is None


def test_flush_on_empty_buffer_returns_none():
    splitter = UtteranceSplitter()
    assert splitter.flush() is None


def test_reset_discards_the_buffer():
    """reset() is for interrupting, so it must throw the audio away."""
    splitter = UtteranceSplitter()
    for _ in range(10):
        splitter.push(loud_frame())
    splitter.reset()
    assert splitter.flush() is None


def test_push_to_talk_round_trip():
    """The full push-to-talk interaction: press, talk, release."""
    splitter = UtteranceSplitter()

    # Press.
    splitter.reset()

    # Talk (no silence at the end).
    for _ in range(12):
        splitter.push(loud_frame())

    # Release — this must produce the utterance.
    released = splitter.flush()
    assert released is not None
    assert len(released) > 44  # header plus payload
