"""Exercise Android runtime contracts without Qt, a browser or network access."""
from __future__ import annotations

from contextlib import ExitStack
import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile
import threading
from types import ModuleType, SimpleNamespace
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from unipus_helper import android_runtime as runtime


class DeferredThread:
    def __init__(self, *, target, args=(), daemon=True, name=""):
        self.target, self.args, self.name, self.started = target, args, name, False

    def start(self):
        self.started = True


class FakeDriver:
    def __init__(self, bridge):
        self.bridge, self.authenticated, self.closed = bridge, False, False
        self.current_url = "https://ucloud.unipus.cn/home"
        self.switch_to = SimpleNamespace(default_content=lambda: None)

    def quit(self):
        self.closed = True


class FakeBot:
    def __init__(self, path, *, skip_check, driver_factory):
        self.config = SimpleNamespace(**json.loads(Path(path).read_text(encoding="utf-8")))
        self.driver = driver_factory()
        self.popup_watcher = SimpleNamespace(run=lambda: None)
        self.login_calls, self.closed = 0, False
        self._background_threads = []

    def _login(self):
        self.login_calls += 1
        return False

    @staticmethod
    def _has_live_auth(driver):
        return driver.authenticated

    def request_shutdown(self):
        self.closed = True


class FakeController:
    def __init__(self, driver, solver, bot, app_globals):
        self.driver, self.solver, self.bot = driver, solver, bot
        self._lock = threading.RLock()
        self._status, self._error = "等待登录", ""
        self.ready, self.busy, self.closed = False, False, False
        self.actions, self.logs, self.selections = [], [], []

    def snapshot(self):
        data = runtime._empty_snapshot()
        data.update(ready=self.ready, busy=self.busy, status=self._status,
                    error=self._error, tasks=[{"id": "task-1"}])
        return data

    def _add_log(self, message, level="info"):
        self.logs.append((message, level))

    def enable_scan_button(self):
        self.ready = True

    def start_action(self, action):
        self.actions.append(action)
        return {"ok": self.ready}

    def stop(self):
        self.busy = False
        return {"ok": True}

    def set_selection(self, ids, selected):
        self.selections.append((ids, selected))
        return {"ok": True}

    def select_tasks(self, mode, ids):
        self.selections.append((mode, ids))
        return {"ok": True}

    def set_exclude_ungraded(self, enabled):
        return {"ok": True, "enabled": enabled}

    def test_api(self):
        return {"ok": True}

    def close(self):
        self.closed = True
        self.driver.quit()


class AndroidRuntimeTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="android-runtime-test-")
        self.private = Path(self.directory.name)
        self.old_tempdir = tempfile.tempdir
        self.old_config_path, self.old_error = runtime._config_path, runtime._boot_error
        self.stack = ExitStack()
        self.stack.enter_context(patch.dict(os.environ, {}, clear=False))
        driver_module, controller_module = ModuleType("driver"), ModuleType("controller")
        driver_module.AndroidWebDriver = FakeDriver
        controller_module.WorkbenchController = FakeController
        self.stack.enter_context(patch.dict(sys.modules, {
            "unipus_helper.android_driver": driver_module,
            "unipus_helper.workbench_controller": controller_module,
        }))
        self.core = SimpleNamespace(
            UCampusBot=FakeBot, AISolver=lambda driver, config: SimpleNamespace(config=config),
            setup_logging=lambda: None,
        )
        self.stack.enter_context(patch.object(runtime.importlib, "import_module", return_value=self.core))
        self.stack.enter_context(patch.object(runtime.threading, "Thread", DeferredThread))
        self.addCleanup(self.cleanup_runtime)

    def cleanup_runtime(self):
        runtime.shutdown()
        runtime._config_path, runtime._boot_error = self.old_config_path, self.old_error
        self.stack.close()
        tempfile.tempdir = self.old_tempdir
        self.directory.cleanup()

    def initialize(self, **changes):
        config = {"username": "fixture-account", "password": "fixture-password",
                  "api_key": "fixture-secret", "model": "fixture-model",
                  "base_url": "https://example.invalid/v1"}
        config.update(changes)
        return json.loads(runtime.initialize(object(), str(self.private), json.dumps(config)))

    def test_initialize_returns_before_login_and_uses_private_data(self):
        state = self.initialize()
        self.assertFalse(state["ready"])
        self.assertEqual(state["error"], "")
        self.assertEqual(runtime._current.bot.login_calls, 0)
        self.assertTrue(runtime._current.bot._background_threads[0].started)
        self.assertEqual(os.environ["UNIPUS_DATA_DIR"], str(self.private))
        self.assertEqual(os.environ["UNIPUS_ANDROID"], "1")
        self.assertEqual(runtime._current.config["skip_record_mode"], "all")
        self.assertEqual(runtime._current.config["tts_engine"], "off")
        self.assertTrue(state["capabilities"]["standalone"])
        self.assertFalse(state["capabilities"]["localWhisper"])
        self.assertNotIn("fixture-secret", runtime.snapshot())

    def test_mark_ready_requires_real_site_and_authentication(self):
        self.initialize()
        self.assertFalse(json.loads(runtime.command("mark_ready"))["ok"])
        driver = runtime._current.bot.driver
        driver.authenticated = True
        driver.current_url = "https://example.invalid/"
        self.assertFalse(json.loads(runtime.command("mark_ready"))["ok"])
        driver.current_url = "https://ucloud.unipus.cn/home"
        self.assertTrue(json.loads(runtime.command("mark_ready"))["ok"])
        self.assertTrue(json.loads(runtime.snapshot())["ready"])
        self.assertTrue(runtime._current.watcher_started)

    def test_commands_forward_selection_and_do_not_accept_unknown_actions(self):
        self.initialize()
        controller = runtime._current.controller
        self.assertTrue(json.loads(runtime.command("select", '{"ids":["task-1"],"selected":false}'))["ok"])
        self.assertEqual(controller.selections[-1], (["task-1"], False))
        self.assertTrue(json.loads(runtime.command("select_mode", '{"mode":"pending"}'))["ok"])
        self.assertEqual(controller.selections[-1], ("pending", ["task-1"]))
        self.assertFalse(json.loads(runtime.command("not-a-command"))["ok"])
        self.assertFalse(json.loads(runtime.command("select", "[]"))["ok"])

    def test_preference_save_preserves_credentials_and_unknown_fields(self):
        self.initialize(extra_feature={"keep": True})
        result = json.loads(runtime.save_config('{"theme_mode":"dark","animations_enabled":false}'))
        self.assertTrue(result["ok"])
        self.assertFalse(result["restart_required"])
        saved = json.loads((self.private / "config.json").read_text(encoding="utf-8"))
        self.assertEqual(saved["password"], "fixture-password")
        self.assertEqual(saved["api_key"], "fixture-secret")
        self.assertEqual(saved["extra_feature"], {"keep": True})
        self.assertEqual(saved["theme_mode"], "dark")
        self.assertFalse(saved["animations_enabled"])

    def test_connection_change_requires_restart_and_reloads_config(self):
        self.initialize()
        old = runtime._current
        result = json.loads(runtime.save_config('{"api_key":"new-fixture-secret"}'))
        self.assertTrue(result["restart_required"])
        self.assertFalse(json.loads(runtime.command("run"))["ok"])
        self.assertEqual(old.controller.actions, [])
        result = json.loads(runtime.command("restart"))
        self.assertTrue(result["ok"])
        self.assertTrue(old.closed if hasattr(old, "closed") else old.controller.closed)
        self.assertTrue(old.bot.driver.closed)
        self.assertEqual(runtime._current.bot.config.api_key, "new-fixture-secret")
        self.assertFalse(json.loads(runtime.snapshot())["configRestartRequired"])

    def test_interrupted_write_preserves_old_config(self):
        self.initialize()
        path = self.private / "config.json"
        original = path.read_bytes()

        def fail_after_partial_write(_data, handle, **_kwargs):
            handle.write('{"username":')
            raise OSError("fixture interrupted write")

        with patch.object(runtime.json, "dump", side_effect=fail_after_partial_write):
            result = json.loads(runtime.save_config('{"theme_mode":"dark"}'))
        self.assertFalse(result["ok"])
        self.assertEqual(path.read_bytes(), original)
        self.assertEqual(list(self.private.glob(".config-*.tmp")), [])

    def test_invalid_config_is_rejected_without_overwriting_existing_file(self):
        self.initialize()
        path = self.private / "config.json"
        original = path.read_bytes()
        self.assertFalse(json.loads(runtime.save_config('{"url":"javascript:alert(1)"}'))["ok"])
        self.assertFalse(json.loads(runtime.save_config('{"score_threshold":101}'))["ok"])
        self.assertEqual(path.read_bytes(), original)

    def test_shutdown_closes_controller_and_invalidates_commands(self):
        self.initialize()
        old = runtime._current
        runtime.shutdown()
        self.assertTrue(old.stopped.is_set())
        self.assertTrue(old.bot.closed)
        self.assertTrue(old.controller.closed)
        self.assertFalse(json.loads(runtime.command("scan"))["ok"])

    def test_data_directory_override_requires_an_absolute_path(self):
        path = ROOT / "unipus_helper/paths.py"
        spec = importlib.util.spec_from_file_location("_android_paths_probe", path)
        with patch.dict(os.environ, {"UNIPUS_DATA_DIR": str(self.private)}):
            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)
            self.assertEqual(module.PROJECT_ROOT, self.private)
        with patch.dict(os.environ, {"UNIPUS_DATA_DIR": "relative-path"}):
            module = importlib.util.module_from_spec(spec)
            with self.assertRaises(ValueError):
                spec.loader.exec_module(module)


if __name__ == "__main__":
    unittest.main()
