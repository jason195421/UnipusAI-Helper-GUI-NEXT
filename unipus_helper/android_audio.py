"""Standard-library boundary for Android's local speech and media engines.

The Java bridge owns TextToSpeech, FFmpeg and whisper.cpp. Python never launches
desktop executables or downloads a model implicitly. Calls run on workers, and
the bridge reference lock is deliberately released before blocking native work
so a second worker can always cancel synthesis or inference.
"""
from __future__ import annotations

import base64
import binascii
import io
import json
import math
import os
from pathlib import Path
import threading
import wave

_bridge = None
_lock = threading.Lock()


class AndroidAudioError(RuntimeError):
    def __init__(self, message, name="AndroidAudioError"):
        super().__init__(str(message))
        self.name = str(name)


def configure(bridge):
    global _bridge
    with _lock:
        _bridge = bridge


def _call(operation, **arguments):
    with _lock:
        bridge = _bridge
    if bridge is None:
        raise AndroidAudioError("安卓语音桥尚未初始化。")
    request = dict(arguments, op=operation)
    try:
        response = json.loads(str(bridge.callJson(json.dumps(
            request, ensure_ascii=False, allow_nan=False))))
    except (ValueError, TypeError) as exc:
        raise AndroidAudioError("安卓语音桥返回了无效的 JSON。") from exc
    if not isinstance(response, dict) or not isinstance(response.get("ok"), bool):
        raise AndroidAudioError("安卓语音桥返回格式无效。")
    if not response["ok"]:
        error = response.get("error")
        error = error if isinstance(error, dict) else {}
        raise AndroidAudioError(error.get("message") or "安卓语音操作失败。",
                                error.get("name") or "AndroidAudioError")
    value = response.get("value")
    if not isinstance(value, dict):
        raise AndroidAudioError("安卓语音操作未返回有效结果。")
    return value


def native_capabilities():
    return _call("audio_capabilities", timeoutMs=10000)


def tts_status(*, engine="", language="en-US", voice=""):
    return _call("tts_status", engine=str(engine or ""),
                 language=str(language or "en-US"), voice=str(voice or ""), offlineOnly=True,
                 timeoutMs=30000)


def status(*, voice=""):
    """Read real provider/model state, retaining errors for the settings UI."""
    result = {}
    for name, operation in (("tts", lambda: tts_status(voice=voice)),
                            ("transcription", native_capabilities)):
        try:
            result[name] = operation()
        except Exception as exc:
            result[name] = {"available": False, "ready": False, "error": str(exc)}
    return result


def _rate(value):
    try:
        if isinstance(value, str) and value.strip().endswith("%"):
            number = 1.0 + float(value.strip()[:-1]) / 100.0
        else:
            number = float(value)
    except (ValueError, TypeError) as exc:
        raise ValueError("语速必须是百分比或 0.25–4 之间的倍率。") from exc
    if not math.isfinite(number) or not 0.25 <= number <= 4:
        raise ValueError("语速必须在 0.25–4 倍之间。")
    return number


def _duration(value):
    seconds = float(value)
    if not math.isfinite(seconds) or not 0 < seconds <= 110:
        raise ValueError("合成音频时长上限必须在 0–110 秒之间。")
    return seconds


def _wav_bytes(value, max_seconds):
    if str(value.get("format", "")).lower() not in ("wav", "wave"):
        raise AndroidAudioError("安卓语音引擎未生成 WAV 音频。")
    encoded = value.get("data")
    if not isinstance(encoded, str) or not encoded or len(encoded) > 64 * 1024 * 1024:
        raise AndroidAudioError("安卓语音引擎返回了空音频或过大的音频。")
    try:
        data = base64.b64decode(encoded, validate=True)
    except (ValueError, binascii.Error) as exc:
        raise AndroidAudioError("安卓语音音频编码无效。") from exc
    try:
        with wave.open(io.BytesIO(data), "rb") as audio:
            channels, width = audio.getnchannels(), audio.getsampwidth()
            rate, frames = audio.getframerate(), audio.getnframes()
            if (audio.getcomptype() != "NONE" or channels not in (1, 2)
                    or width not in (1, 2, 3, 4) or not 8000 <= rate <= 192000
                    or frames <= 0 or frames / rate > max_seconds + .25):
                raise AndroidAudioError("安卓语音音频的格式或时长无效。")
            samples = audio.readframes(frames)
            if len(samples) != frames * channels * width:
                raise AndroidAudioError("安卓语音音频不完整。")
            silence = 128 if width == 1 else 0
            if not any(sample != silence for sample in samples):
                raise AndroidAudioError("安卓语音引擎只生成了静音。")
    except (wave.Error, EOFError) as exc:
        raise AndroidAudioError("安卓语音引擎返回了无效的 WAV 音频。") from exc
    return data


def synthesize(text, voice="", rate="+0%", max_seconds=110.0):
    """Return validated PCM WAV bytes for the existing virtual-microphone flow."""
    if not isinstance(text, str) or not text.strip():
        raise ValueError("朗读文本不能为空。")
    maximum = _duration(max_seconds)
    result = _call("tts_synthesize", text=text.strip(), voice=str(voice or ""),
                   language="en-US", rate=_rate(rate), pitch=1.0,
                   maxSeconds=maximum, offlineOnly=True,
                   timeoutMs=min(120000, max(30000, int(maximum * 1000) + 10000)))
    return "wav", _wav_bytes(result, maximum)


def speak(text, voice="", rate="+0%", max_seconds=110.0):
    """Explicit user preview through the speaker; automation uses synthesize."""
    if not isinstance(text, str) or not text.strip():
        raise ValueError("朗读文本不能为空。")
    maximum = _duration(max_seconds)
    return _call("tts_speak", text=text.strip(), voice=str(voice or ""),
                 language="en-US", rate=_rate(rate), pitch=1.0,
                 maxSeconds=maximum, offlineOnly=True,
                 timeoutMs=min(120000, max(30000, int(maximum * 1000) + 10000)))


def prepare_model():
    """Download only after the user explicitly chooses the model-download action."""
    result = _call("audio_prepare_model", timeoutMs=1800000)
    if result.get("modelReady") is not True:
        raise AndroidAudioError(result.get("error") or "本地语音模型尚未准备好。")
    return result


def cancel():
    """Attempt both cancellations even if one engine is absent or already closed."""
    errors = []
    for operation in ("tts_stop", "audio_cancel"):
        try:
            _call(operation, timeoutMs=5000)
        except Exception as exc:
            errors.append(str(exc))
    return {"stopped": not errors, "errors": errors}


def release():
    return _call("audio_release", timeoutMs=10000)


def _private_input(path):
    root = Path(os.environ.get("UNIPUS_DATA_DIR", ""))
    if not root.is_absolute():
        raise AndroidAudioError("安卓应用私有数据目录尚未设置。")
    candidate = Path(path).resolve(strict=True)
    try:
        candidate.relative_to(root.resolve(strict=True))
    except ValueError as exc:
        raise AndroidAudioError("音频必须保存在安卓应用私有目录中。") from exc
    if not candidate.is_file() or candidate.stat().st_size == 0:
        raise AndroidAudioError("待识别的音频文件为空或不存在。")
    return str(candidate)


class AndroidWhisperModel:
    """Whisper-shaped local model backed by FFmpeg and whisper.cpp via JNI."""

    def __init__(self):
        capabilities = native_capabilities()
        if capabilities.get("whisperAvailable") is not True:
            raise AndroidAudioError(capabilities.get("error") or "此安卓构建未包含本地 Whisper 引擎。")
        if capabilities.get("ffmpegAvailable") is not True:
            raise AndroidAudioError(capabilities.get("error") or "此安卓构建未包含本地 FFmpeg 解码器。")
        if capabilities.get("modelReady") is not True:
            raise AndroidAudioError("尚未下载本地 Whisper 模型，请在安卓语音设置中下载模型后重试。")
        self.closed = False

    def transcribe(self, audio_path, language="en", fp16=False):
        if self.closed:
            raise AndroidAudioError("本地 Whisper 模型已释放。")
        result = _call("audio_transcribe", inputPath=_private_input(audio_path),
                       language=str(language or "en"), timeoutMs=1800000)
        text = result.get("text")
        if not isinstance(text, str):
            raise AndroidAudioError("本地 Whisper 未返回有效的识别文本。")
        return dict(result, text=text.strip())

    def close(self):
        if not self.closed:
            self.closed = True
            release()
