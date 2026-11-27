"""Verify UTF-8 console setup used by the application before logging starts."""
from __future__ import annotations

import ast
import io
import logging
import os
from pathlib import Path
import sys
import types
import unittest


ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "UnipusAI_Helper.py"
MESSAGE = "首次启动：请在打开的设置页填写配置，保存后重新启动程序。"


def load_stdio_bootstrap(fake_sys):
    source = APP.read_text(encoding="utf-8")
    tree = ast.parse(source, filename=str(APP))
    wanted = {"_SafeWriter", "_NullInput", "_bootstrap_gui_stdio"}
    definitions = [
        node for node in tree.body
        if isinstance(node, (ast.ClassDef, ast.FunctionDef)) and node.name in wanted
    ]
    module = types.ModuleType("_app_stdio_bootstrap")
    module.__dict__.update(
        GUI_MODE=False,
        _GUI_STDIO_STREAM=None,
        BASE_DIR=str(ROOT),
        os=os,
        sys=fake_sys,
    )
    code = ast.fix_missing_locations(ast.Module(body=definitions, type_ignores=[]))
    exec(compile(code, str(APP), "exec"), module.__dict__)
    return module


class ConsoleEncodingTests(unittest.TestCase):
    def check_debug_console(self, frozen):
        stdout_bytes, stderr_bytes = io.BytesIO(), io.BytesIO()
        stdout = io.TextIOWrapper(stdout_bytes, encoding="cp1252")
        stderr = io.TextIOWrapper(stderr_bytes, encoding="cp1252")
        fake_sys = types.SimpleNamespace(
            frozen=frozen,
            stdout=stdout,
            stderr=stderr,
            stdin=io.StringIO(),
        )
        bootstrap = load_stdio_bootstrap(fake_sys)

        self.assertFalse(bootstrap._bootstrap_gui_stdio())
        self.assertEqual(stdout.encoding.lower().replace("-", ""), "utf8")
        self.assertEqual(stderr.encoding.lower().replace("-", ""), "utf8")
        self.assertEqual(stdout.errors, "backslashreplace")
        self.assertEqual(stderr.errors, "backslashreplace")

        logger = logging.Logger("console-encoding-test")
        logger.setLevel(logging.INFO)
        handler = logging.StreamHandler(stdout)
        logger.addHandler(handler)
        try:
            logger.info(MESSAGE)
            handler.flush()
        finally:
            logger.removeHandler(handler)

        self.assertIn(MESSAGE.encode("utf-8"), stdout_bytes.getvalue())
        stdout.close()
        stderr.close()

    def test_source_debug_console_logs_chinese(self):
        self.check_debug_console(frozen=False)

    def test_frozen_debug_console_logs_chinese(self):
        self.check_debug_console(frozen=True)

    def test_host_stream_without_reconfigure_is_preserved(self):
        stdout, stderr = io.StringIO(), io.StringIO()
        fake_sys = types.SimpleNamespace(
            frozen=True,
            stdout=stdout,
            stderr=stderr,
            stdin=io.StringIO(),
        )
        bootstrap = load_stdio_bootstrap(fake_sys)

        self.assertFalse(bootstrap._bootstrap_gui_stdio())
        self.assertIs(fake_sys.stdout, stdout)
        self.assertIs(fake_sys.stderr, stderr)

    def test_frozen_windowed_build_keeps_file_backed_streams(self):
        fake_sys = types.SimpleNamespace(
            frozen=True,
            stdout=None,
            stderr=None,
            stdin=None,
        )
        bootstrap = load_stdio_bootstrap(fake_sys)

        self.assertTrue(bootstrap._bootstrap_gui_stdio())
        self.assertIsInstance(fake_sys.stdout, bootstrap._SafeWriter)
        self.assertIsInstance(fake_sys.stderr, bootstrap._SafeWriter)
        self.assertIsInstance(fake_sys.stdin, bootstrap._NullInput)


if __name__ == "__main__":
    unittest.main()
