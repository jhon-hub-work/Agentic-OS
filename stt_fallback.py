"""Backup speech-to-text for the dashboard mic when VoiceStudio is down (its engine restarts now and then).

Runs on Jharvis's venv: the same offline faster-whisper model his wake word uses. Audio is decoded with
ffmpeg (that venv's PyAV is too old for faster-whisper's own decoder). Usage: python stt_fallback.py <file>
"""
import subprocess
import sys

import numpy as np

sys.path.insert(0, r"C:\Users\Admin-PC\Documents\Jharvis\src")
from faster_whisper import WhisperModel  # noqa: E402
from jharvis.wake import STT_DIR, STT_MODEL  # noqa: E402

pcm = subprocess.run(["ffmpeg", "-v", "error", "-i", sys.argv[1], "-f", "s16le", "-ac", "1", "-ar", "16000", "-"],
                     capture_output=True, check=True).stdout
audio = np.frombuffer(pcm, np.int16).astype(np.float32) / 32768.0
model = WhisperModel(STT_MODEL, device="cpu", compute_type="int8", download_root=STT_DIR, local_files_only=True)
segments, _ = model.transcribe(audio, language="en", beam_size=1, without_timestamps=True)
print(" ".join(s.text.strip() for s in segments).strip())
