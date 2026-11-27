"""Exercise smoke-test diagnostics without a built application or GUI."""
from __future__ import annotations

import io
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

import smoke_test


class SmokeDiagnosticsTests(unittest.TestCase):
    def run_smoke(self, output, *, returncode=1, timeout=False, app_log=None):
        process = Mock(returncode=returncode)
        process.communicate.side_effect = (
            [subprocess.TimeoutExpired("mock-app", 0.1), (output, None)]
            if timeout else [(output, None)]
        )
        buffer = io.BytesIO()
        console = io.TextIOWrapper(buffer, encoding="cp1252")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            payload = root / "dist" / "UnipusAI-Helper-Debug"
            payload.mkdir(parents=True)
            executable = "UnipusAI-Helper-Debug" + (".exe" if sys.platform == "win32" else "")
            (payload / executable).touch()
            logdir = payload / "logs"

            def start_process(*args, **kwargs):
                self.assertEqual(kwargs["env"]["PYTHONUTF8"], "1")
                self.assertEqual(kwargs["env"]["PYTHONIOENCODING"], "utf-8:backslashreplace")
                if app_log:
                    (logdir / "native-ui.log").write_text(app_log, encoding="utf-8")
                return process

            with (
                patch.object(smoke_test, "ROOT", root),
                patch.object(sys, "argv", ["smoke_test.py", "--mode", "debug", "--seconds", "0.1"]),
                patch.object(sys, "stdout", console),
                patch.object(smoke_test.subprocess, "Popen", side_effect=start_process),
            ):
                result = smoke_test.main()
            console.flush()
            terminal = buffer.getvalue().decode("utf-8")
            raw_log = (logdir / "smoke-console.log").read_bytes()
            report = (logdir / "smoke-report.log").read_text(encoding="utf-8")
        console.close()
        return result, terminal, raw_log, report, process

    def test_chinese_traceback_is_reported_instead_of_encoding_crash(self):
        output = "Traceback (most recent call last):\nRuntimeError: 设置界面启动失败\n".encode("utf-8")
        result, terminal, raw, report, _ = self.run_smoke(output)
        self.assertEqual(result, 1)
        self.assertIn("FAIL marker: Traceback", terminal)
        self.assertIn("设置界面启动失败", terminal)
        self.assertEqual(raw, output)
        self.assertIn("returncode=1", report)

    def test_invalid_child_bytes_are_preserved(self):
        output = b"Traceback (most recent call last):\ninvalid output: \xff\n"
        result, terminal, raw, report, _ = self.run_smoke(output)
        self.assertEqual(result, 1)
        self.assertIn("FAIL marker:", terminal)
        self.assertEqual(raw, output)
        self.assertIn("invalid output:", report)

    def test_clean_early_exit_still_fails(self):
        result, terminal, _, report, _ = self.run_smoke(b"exited", returncode=0)
        self.assertEqual(result, 1)
        self.assertIn("process exited too early (rc=0)", terminal)
        self.assertIn("exited early", report)

    def test_healthy_timeout_still_passes(self):
        result, terminal, _, report, process = self.run_smoke(
            "启动完成\n".encode("utf-8"), returncode=-15, timeout=True,
        )
        self.assertEqual(result, 0)
        self.assertIn("OK: survived", terminal)
        self.assertIn("stopped after smoke timeout", report)
        process.terminate.assert_called_once()

    def test_traceback_in_application_log_still_fails(self):
        result, terminal, _, report, _ = self.run_smoke(
            b"", timeout=True,
            app_log="Traceback (most recent call last):\nRuntimeError: QML 加载失败\n",
        )
        self.assertEqual(result, 1)
        self.assertIn("FAIL marker: Traceback", terminal)
        self.assertIn("native-ui.log", report)
        self.assertIn("QML 加载失败", report)


if __name__ == "__main__":
    unittest.main()
