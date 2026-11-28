"""Verify Android audio transport without Android, model downloads or secrets."""
from __future__ import annotations

import ast
import base64
import builtins
from contextlib import redirect_stdout
import io
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import threading
from types import ModuleType
from typing import Any, Dict, Tuple
import unittest
from unittest.mock import Mock, call, patch
import wave

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from unipus_helper import android_audio as audio
from unipus_helper.audio_recognizer import AudioTranscriber


def wav_bytes(*, silent=False, seconds=.1):
    output = io.BytesIO()
    with wave.open(output, "wb") as handle:
        handle.setparams((1, 2, 16000, 0, "NONE", "not compressed"))
        handle.writeframes((b"\0\0" if silent else b"\1\0") * int(16000 * seconds))
    return output.getvalue()


class FakeBridge:
    def __init__(self):
        self.calls = []
        self.values = {
            "tts_status": {"available": True, "ready": True, "voices": []},
            "tts_synthesize": {"format": "wav", "data": base64.b64encode(wav_bytes()).decode("ascii")},
            "audio_capabilities": {"whisperAvailable": True, "ffmpegAvailable": True,
                                   "modelReady": True, "modelName": "tiny.en"},
            "audio_transcribe": {"text": " hello fixture ", "sampleRate": 16000},
            "audio_prepare_model": {"modelReady": True},
            "tts_stop": {"stopped": True}, "audio_cancel": {"stopped": True},
            "audio_release": {"released": True},
        }

    def callJson(self, raw):
        request = json.loads(raw)
        self.calls.append(request)
        value = self.values[request["op"]]
        return json.dumps(value if "ok" in value else {"ok": True, "value": value})


class SharedTtsRoutingTests(unittest.TestCase):
    """Use the actual TTSEngine class without executing application/config imports."""

    @classmethod
    def setUpClass(cls):
        path = Path(__file__).resolve().parents[1] / "UnipusAI_Helper.py"
        tree = ast.parse(path.read_text(encoding="utf-8-sig"))
        engine = next(node for node in tree.body if isinstance(node, ast.ClassDef) and node.name == "TTSEngine")
        namespace = {"os": os, "hashlib": hashlib, "re": re, "subprocess": subprocess,
                     "Dict": Dict, "Tuple": Tuple, "Any": Any, "logger": Mock()}
        exec(compile(ast.Module(body=[engine], type_ignores=[]), str(path), "exec"), namespace)
        cls.engine_type = namespace["TTSEngine"]

    def setUp(self):
        self.bridge = FakeBridge()
        audio.configure(self.bridge)
        self.addCleanup(audio.configure, None)
        environment = patch.dict(os.environ, {"UNIPUS_ANDROID": "1"})
        environment.start()
        self.addCleanup(environment.stop)

    def test_android_auto_edge_and_system_use_native_synthesis_with_all_parameters(self):
        original_import = builtins.__import__
        imports = []

        def guarded_import(name, *args, **kwargs):
            imports.append(name)
            if name == "edge_tts" or name.startswith("edge_tts."):
                raise AssertionError("Android must not import desktop Edge TTS")
            return original_import(name, *args, **kwargs)

        for preference in ("auto", "edge", "system"):
            with self.subTest(preference=preference), \
                    patch.object(self.engine_type, "_synth_edge") as edge, \
                    patch.object(self.engine_type, "_synth_sapi") as sapi, \
                    patch.object(audio, "synthesize", wraps=audio.synthesize) as native, \
                    patch("subprocess.run") as process, \
                    patch("builtins.__import__", side_effect=guarded_import), \
                    redirect_stdout(io.StringIO()):
                engine = self.engine_type(preference, voice="fixture-voice", rate="+25%")
                self.assertEqual(engine._engine_order(), ("android",))
                self.assertEqual(engine.synthesize("Read this fixture.", max_seconds=3.5), ("wav", wav_bytes()))
                native.assert_called_once_with("Read this fixture.", voice="fixture-voice", rate="+25%", max_seconds=3.5)
                edge.assert_not_called()
                sapi.assert_not_called()
                process.assert_not_called()
                request = self.bridge.calls[-1]
                self.assertEqual(request["op"], "tts_synthesize")
                self.assertEqual(request["maxSeconds"], 3.5)
                self.assertAlmostEqual(request["rate"], 1.25)
        self.assertFalse(any(name == "edge_tts" or name.startswith("edge_tts.") for name in imports))

    def test_disabled_engine_does_not_call_native_or_desktop_synthesis(self):
        with patch.object(audio, "synthesize") as native, \
                patch.object(self.engine_type, "_synth_edge") as edge, \
                patch.object(self.engine_type, "_synth_sapi") as sapi, \
                patch("subprocess.run") as process:
            for preference in ("off", "none", "disable", "disabled"):
                self.assertEqual(self.engine_type(preference).synthesize("Fixture", max_seconds=2), ("", b""))
            native.assert_not_called()
            edge.assert_not_called()
            sapi.assert_not_called()
            process.assert_not_called()
        self.assertEqual(self.bridge.calls, [])

    def test_synthesis_cache_keeps_duration_limits_separate(self):
        with patch.object(audio, "synthesize", wraps=audio.synthesize) as native, redirect_stdout(io.StringIO()):
            engine = self.engine_type("auto", voice="fixture-voice", rate="+10%")
            for duration in (3.0, 6.0, 6.0):
                self.assertEqual(engine.synthesize("A short fixture.", duration), ("wav", wav_bytes()))
            self.assertEqual(native.call_args_list, [
                call("A short fixture.", voice="fixture-voice", rate="+10%", max_seconds=3.0),
                call("A short fixture.", voice="fixture-voice", rate="+10%", max_seconds=6.0),
            ])

    def test_desktop_engine_order_is_preserved(self):
        with patch.dict(os.environ, {"UNIPUS_ANDROID": "0"}):
            for preference, expected in (("auto", ("edge", "sapi")), ("edge", ("edge",)),
                                         ("system", ("sapi",)), ("sapi", ("sapi",)), ("windows", ("sapi",))):
                with self.subTest(preference=preference):
                    self.assertEqual(self.engine_type(preference)._engine_order(), expected)


class AndroidAudioTests(unittest.TestCase):
    def setUp(self):
        self.bridge = FakeBridge()
        audio.configure(self.bridge)
        self.directory = tempfile.TemporaryDirectory(prefix="android-audio-test-")
        self.private = Path(self.directory.name)
        self.input = self.private / "fixture.mp3"
        self.input.write_bytes(b"fixture audio input")
        self.environment = patch.dict(os.environ, {
            "UNIPUS_ANDROID": "1", "UNIPUS_DATA_DIR": str(self.private),
        })
        self.environment.start()
        self.addCleanup(self.environment.stop)
        self.addCleanup(self.directory.cleanup)
        self.addCleanup(audio.configure, None)

    def test_synthesis_uses_offline_native_engine_and_validates_wav(self):
        fmt, data = audio.synthesize(" Fixture text ", voice="fixture-voice", rate="+20%", max_seconds=4)
        self.assertEqual(fmt, "wav")
        self.assertEqual(data, wav_bytes())
        call = self.bridge.calls[-1]
        self.assertEqual(call["op"], "tts_synthesize")
        self.assertEqual(call["text"], "Fixture text")
        self.assertEqual(call["voice"], "fixture-voice")
        self.assertAlmostEqual(call["rate"], 1.2)
        self.assertTrue(call["offlineOnly"])
        self.assertEqual(call["language"], "en-US")
        self.assertEqual(call["maxSeconds"], 4)
        self.assertNotIn("tts_speak", [call["op"] for call in self.bridge.calls])

    def test_invalid_synthesis_arguments_do_not_reach_native(self):
        for kwargs in ({"text": " "}, {"text": "x", "rate": "-100%"},
                       {"text": "x", "rate": float("nan")},
                       {"text": "x", "max_seconds": float("inf")},
                       {"text": "x", "max_seconds": 111}):
            with self.subTest(kwargs=kwargs), self.assertRaises(ValueError):
                audio.synthesize(**kwargs)
        self.assertEqual(self.bridge.calls, [])

    def test_rejects_invalid_empty_silent_truncated_or_long_audio(self):
        for data in (b"", b"not a wave", wav_bytes(silent=True), wav_bytes()[:-2], wav_bytes(seconds=1)):
            self.bridge.values["tts_synthesize"] = {
                "format": "wav", "data": base64.b64encode(data).decode("ascii")}
            with self.subTest(length=len(data)), self.assertRaises(audio.AndroidAudioError):
                audio.synthesize("Fixture", max_seconds=.1)

    def test_rejects_invalid_base64_and_non_wav_format(self):
        for result in ({"format": "wav", "data": "not base64!"},
                       {"format": "mp3", "data": "Zml4dHVyZQ=="}):
            self.bridge.values["tts_synthesize"] = result
            with self.assertRaises(audio.AndroidAudioError):
                audio.synthesize("Fixture")

    def test_native_error_name_and_message_are_preserved(self):
        self.bridge.values["tts_synthesize"] = {
            "ok": False, "error": {"name": "DependencyUnavailableException", "message": "缺少离线语音数据"}}
        with self.assertRaisesRegex(audio.AndroidAudioError, "缺少离线语音数据") as caught:
            audio.synthesize("Fixture")
        self.assertEqual(caught.exception.name, "DependencyUnavailableException")

    def test_status_retains_each_provider_failure(self):
        self.bridge.values["tts_status"] = {"ok": False, "error": {"message": "fixture missing voice"}}
        state = audio.status()
        self.assertFalse(state["tts"]["ready"])
        self.assertIn("fixture missing voice", state["tts"]["error"])
        self.assertTrue(state["transcription"]["modelReady"])

    def test_model_requires_both_native_libraries_and_downloaded_model(self):
        for missing in ("whisperAvailable", "ffmpegAvailable", "modelReady"):
            with self.subTest(missing=missing):
                self.bridge.values["audio_capabilities"][missing] = False
                with self.assertRaises(audio.AndroidAudioError):
                    audio.AndroidWhisperModel()
                self.bridge.values["audio_capabilities"][missing] = True
        self.assertNotIn("audio_prepare_model", [call["op"] for call in self.bridge.calls])

    def test_model_transcribes_real_private_file_and_releases_once(self):
        model = audio.AndroidWhisperModel()
        self.assertEqual(model.transcribe(self.input)["text"], "hello fixture")
        call = self.bridge.calls[-1]
        self.assertEqual(call["inputPath"], str(self.input.resolve()))
        self.assertEqual(call["language"], "en")
        self.assertEqual(call["timeoutMs"], 1800000)
        model.close()
        model.close()
        self.assertEqual([call["op"] for call in self.bridge.calls].count("audio_release"), 1)
        with self.assertRaisesRegex(audio.AndroidAudioError, "已释放"):
            model.transcribe(self.input)

    def test_outside_file_and_invalid_transcription_are_rejected(self):
        model = audio.AndroidWhisperModel()
        with self.assertRaises(audio.AndroidAudioError):
            model.transcribe(__file__)
        self.bridge.values["audio_transcribe"] = {"text": None}
        with self.assertRaises(audio.AndroidAudioError):
            model.transcribe(self.input)

    def test_cancellation_is_not_blocked_by_an_inflight_native_call(self):
        entered, cancelled = threading.Event(), threading.Event()
        original = self.bridge.callJson

        def call(raw):
            op = json.loads(raw)["op"]
            if op == "tts_synthesize":
                entered.set()
                if not cancelled.wait(2):
                    raise AssertionError("Cancellation could not enter the bridge")
            elif op == "tts_stop":
                cancelled.set()
            return original(raw)

        self.bridge.callJson = call
        errors = []
        def synthesize():
            try:
                audio.synthesize("Fixture")
            except Exception as exc:
                errors.append(exc)
        worker = threading.Thread(target=synthesize)
        worker.start()
        self.assertTrue(entered.wait(1))
        self.assertTrue(audio.cancel()["stopped"])
        worker.join(2)
        self.assertFalse(worker.is_alive())
        self.assertEqual(errors, [])

    def test_cancellation_attempts_both_engines_after_failure(self):
        self.bridge.values["tts_stop"] = {"ok": False, "error": {"message": "fixture TTS stopped"}}
        result = audio.cancel()
        self.assertFalse(result["stopped"])
        self.assertEqual([call["op"] for call in self.bridge.calls], ["tts_stop", "audio_cancel"])

    def test_prepare_model_is_only_called_explicitly(self):
        audio.status()
        self.assertNotIn("audio_prepare_model", [call["op"] for call in self.bridge.calls])
        self.assertTrue(audio.prepare_model()["modelReady"])

    def test_android_transcriber_uses_native_model_without_python_whisper(self):
        with patch.dict(sys.modules, {"whisper": None, "torch": None}), redirect_stdout(io.StringIO()):
            transcriber = AudioTranscriber()
            self.assertTrue(transcriber._ensure_local_model())
            self.assertIsInstance(transcriber.local_model, audio.AndroidWhisperModel)
            self.assertEqual(transcriber._transcribe_local(str(self.input), "en"), "hello fixture")
            transcriber.release_local_model()
            self.assertIsNone(transcriber.local_model)

    def test_transcriber_only_caches_nonempty_success_and_removes_downloads(self):
        transcriber = AudioTranscriber()
        paths = []
        original = self.bridge.callJson

        def call(raw):
            request = json.loads(raw)
            if request["op"] == "audio_transcribe":
                paths.append(Path(request["inputPath"]))
            return original(raw)

        self.bridge.callJson = call
        self.bridge.values["audio_transcribe"] = {"text": " "}
        with patch("unipus_helper.audio_recognizer.tempfile.tempdir", str(self.private)), \
                patch("unipus_helper.audio_recognizer.urllib.request.urlopen", side_effect=lambda *a, **kw: io.BytesIO(b"fixture media")), \
                redirect_stdout(io.StringIO()):
            self.assertEqual(transcriber.transcribe("https://example.invalid/fixture.mp3"), "")
            self.assertEqual(transcriber._transcript_cache, {})
            self.bridge.values["audio_transcribe"] = {"text": " actual fixture "}
            self.assertEqual(transcriber.transcribe("https://example.invalid/fixture.mp3"), "actual fixture")
            self.assertEqual(transcriber.transcribe("https://example.invalid/fixture.mp3"), "actual fixture")
        self.assertEqual(len(paths), 2)
        self.assertTrue(all(not path.exists() for path in paths))

    def test_transcriber_missing_model_never_downloads_media_or_model(self):
        self.bridge.values["audio_capabilities"]["modelReady"] = False
        with patch("unipus_helper.audio_recognizer.urllib.request.urlopen") as download, \
                redirect_stdout(io.StringIO()) as log:
            self.assertEqual(AudioTranscriber().transcribe("https://example.invalid/missing.mp3"), "")
        download.assert_not_called()
        self.assertIn("下载本地 Whisper 模型", log.getvalue())
        self.assertNotIn("audio_prepare_model", [call["op"] for call in self.bridge.calls])

    def test_desktop_transcriber_keeps_python_whisper_loading(self):
        module = ModuleType("whisper")
        model = object()
        calls = []
        module.load_model = lambda *args, **kwargs: calls.append((args, kwargs)) or model
        with patch.dict(os.environ, {"UNIPUS_ANDROID": "0"}), \
                patch.dict(sys.modules, {"whisper": module}), \
                patch("unipus_helper.whisper_runtime.bundled_whisper_dir", return_value=None), \
                redirect_stdout(io.StringIO()):
            transcriber = AudioTranscriber()
            self.assertTrue(transcriber._ensure_local_model())
            self.assertIs(transcriber.local_model, model)
            self.assertEqual(calls, [(("base",), {})])
            transcriber.release_local_model()
            self.assertIsNone(transcriber.local_model)

    def test_missing_bridge_or_malformed_native_response_is_clear(self):
        audio.configure(None)
        with self.assertRaisesRegex(audio.AndroidAudioError, "尚未初始化"):
            audio.tts_status()
        self.bridge.callJson = lambda _: "[]"
        audio.configure(self.bridge)
        with self.assertRaisesRegex(audio.AndroidAudioError, "格式无效"):
            audio.tts_status()


if __name__ == "__main__":
    unittest.main()
