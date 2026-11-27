"""Regression checks for appearance preferences, using only temporary config files.

Install the project's GUI dependencies, then run:
    python scripts/verify_appearance_settings.py -v
"""
from __future__ import annotations

import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
if sys.platform == "win32" and os.environ.get("WINDIR"):
    fonts = Path(os.environ["WINDIR"]) / "Fonts"
    if fonts.is_dir():
        os.environ.setdefault("QT_QPA_FONTDIR", str(fonts))

try:
    from PySide6.QtGui import QGuiApplication
except ImportError as exc:
    raise SystemExit(
        "Install the project GUI dependencies (PySide6-Essentials) before "
        "running verify_appearance_settings.py."
    ) from exc

from unipus_helper import platform_theme, settings


APP = QGuiApplication.instance() or QGuiApplication([])
FIXTURE = {
    "username": "appearance-test-account",
    "password": "fixture-password-only",
    "api_key": "fixture-api-key-only",
    "base_url": "https://example.invalid/v1",
    "model": "fixture-model",
}


class AppearanceSettingsTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory(prefix="unipus-appearance-test-")
        self.addCleanup(directory.cleanup)
        self.path = Path(directory.name) / "config.json"
        config_path = patch.object(settings, "CONFIG_PATH", self.path)
        config_path.start()
        self.addCleanup(config_path.stop)

    def write_fixture(self, **preferences):
        payload = dict(FIXTURE, **preferences)
        self.path.write_text(json.dumps(payload), encoding="utf-8")
        return self.path.read_bytes()

    def observed_bridge(self, **kwargs):
        bridge = settings.SettingsBridge(**kwargs)
        saved, failed = [], []
        bridge.saved.connect(lambda: saved.append(True))
        bridge.failed.connect(failed.append)
        return bridge, saved, failed

    def assert_credentials(self, config):
        for key, value in FIXTURE.items():
            self.assertEqual(config[key], value, key)

    def test_missing_config_uses_defaults_without_creating_a_file(self):
        config = settings.load_config()
        self.assertEqual(config["theme_mode"], "system")
        self.assertIs(config["animations_enabled"], True)
        self.assertFalse(self.path.exists())

    def test_old_config_gets_appearance_defaults_and_keeps_credentials(self):
        self.write_fixture()
        config = settings.load_config()
        self.assertEqual(config["theme_mode"], "system")
        self.assertIs(config["animations_enabled"], True)
        self.assert_credentials(config)

    def test_invalid_theme_modes_fall_back_to_system(self):
        for value in (None, "", "sepia", 123, [], {}):
            with self.subTest(value=value):
                result = settings.appearance_preferences({"theme_mode": value})
                self.assertEqual(result["theme_mode"], "system")
        for value, expected in ((" DARK ", "dark"), ("Light", "light"), ("SYSTEM", "system")):
            with self.subTest(value=value):
                result = settings.appearance_preferences({"theme_mode": value})
                self.assertEqual(result["theme_mode"], expected)

    def test_false_animation_forms_are_normalized(self):
        for value in (False, 0, "false", "FALSE", "off", "0", "no", " off "):
            with self.subTest(value=value):
                result = settings.appearance_preferences({"animations_enabled": value})
                self.assertIs(result["animations_enabled"], False)
                self.write_fixture(animations_enabled=value)
                self.assertIs(settings.load_config()["animations_enabled"], False)

    def test_true_and_missing_animation_values_keep_defaults(self):
        for value in (True, 1, "true", "on", "yes", None):
            with self.subTest(value=value):
                result = settings.appearance_preferences({"animations_enabled": value})
                self.assertIs(result["animations_enabled"], True)
        self.assertIs(settings.appearance_preferences({})["animations_enabled"], True)

    def test_full_config_round_trip_preserves_preferences_and_credentials(self):
        payload = dict(FIXTURE, theme_mode=" DARK ", animations_enabled="off")
        self.assertTrue(settings.save_config(payload))
        config = settings.load_config()
        self.assertEqual(config["theme_mode"], "dark")
        self.assertIs(config["animations_enabled"], False)
        self.assert_credentials(config)
        on_disk = json.loads(self.path.read_text(encoding="utf-8"))
        self.assertEqual(on_disk["theme_mode"], "dark")
        self.assertIs(on_disk["animations_enabled"], False)

    def test_first_run_preview_does_not_save_or_emit_saved(self):
        original = self.write_fixture(theme_mode="light", animations_enabled=True)
        bridge, saved, failed = self.observed_bridge(setup_mode=True)
        with patch.object(platform_theme, "set_appearance") as apply_preferences:
            bridge.previewAppearance("dark", False)
        apply_preferences.assert_called_once_with("dark", False)
        self.assertTrue(bridge.setupMode)
        self.assertEqual(saved, [])
        self.assertEqual(failed, [])
        self.assertEqual(self.path.read_bytes(), original)

    def test_successful_save_applies_normalized_preferences_and_emits_saved(self):
        self.write_fixture(theme_mode="light", animations_enabled=True)
        bridge, saved, failed = self.observed_bridge()
        with patch.object(platform_theme, "set_appearance") as apply_preferences:
            result = bridge.save(dict(FIXTURE, theme_mode="DARK", animations_enabled="false"))
        self.assertTrue(result)
        apply_preferences.assert_called_once_with("dark", False)
        self.assertEqual(saved, [True])
        self.assertEqual(failed, [])
        self.assert_credentials(settings.load_config())
        self.assertIs(settings.load_config()["animations_enabled"], False)

    def test_invalid_save_leaves_existing_config_and_runtime_unchanged(self):
        original = self.write_fixture(theme_mode="light", animations_enabled=True)
        bridge, saved, failed = self.observed_bridge()
        payload = dict(FIXTURE, theme_mode="dark", animations_enabled=False, score_threshold=101)
        with patch.object(platform_theme, "set_appearance") as apply_preferences:
            self.assertFalse(bridge.save(payload))
        apply_preferences.assert_not_called()
        self.assertEqual(saved, [])
        self.assertEqual(len(failed), 1)
        self.assertEqual(self.path.read_bytes(), original)

    def test_write_failure_does_not_emit_saved_or_apply_preview(self):
        original = self.write_fixture(theme_mode="light", animations_enabled=True)
        bridge, saved, failed = self.observed_bridge()
        with (
            patch.object(settings, "save_config", side_effect=PermissionError("fixture write denied")),
            patch.object(platform_theme, "set_appearance") as apply_preferences,
        ):
            self.assertFalse(bridge.save(dict(FIXTURE, theme_mode="dark", animations_enabled=False)))
        apply_preferences.assert_not_called()
        self.assertEqual(saved, [])
        self.assertEqual(failed, ["fixture write denied"])
        self.assertEqual(self.path.read_bytes(), original)

    def test_interrupted_write_preserves_existing_config_and_removes_temp_file(self):
        original = self.write_fixture(theme_mode="light", animations_enabled=True)
        bridge, saved, failed = self.observed_bridge()

        def interrupted_dump(_data, handle, **_kwargs):
            handle.write('{"username":')
            handle.flush()
            raise OSError("fixture interrupted write")

        with (
            patch.object(settings.json, "dump", side_effect=interrupted_dump),
            patch.object(platform_theme, "set_appearance") as apply_preferences,
        ):
            self.assertFalse(bridge.save(dict(FIXTURE, theme_mode="dark", animations_enabled=False)))
        apply_preferences.assert_not_called()
        self.assertEqual(saved, [])
        self.assertEqual(failed, ["fixture interrupted write"])
        self.assertEqual(self.path.read_bytes(), original)
        self.assertEqual(list(self.path.parent.glob(".config-*.tmp")), [])

    def test_replace_failure_preserves_existing_config_and_removes_temp_file(self):
        original = self.write_fixture(theme_mode="light", animations_enabled=True)
        bridge, saved, failed = self.observed_bridge()
        with (
            patch.object(settings.os, "replace", side_effect=PermissionError("fixture replace denied")),
            patch.object(platform_theme, "set_appearance") as apply_preferences,
        ):
            self.assertFalse(bridge.save(dict(FIXTURE, theme_mode="dark", animations_enabled=False)))
        apply_preferences.assert_not_called()
        self.assertEqual(saved, [])
        self.assertEqual(failed, ["fixture replace denied"])
        self.assertEqual(self.path.read_bytes(), original)
        self.assertEqual(list(self.path.parent.glob(".config-*.tmp")), [])


class PlatformAppearanceTests(unittest.TestCase):
    def setUp(self):
        environment = patch.dict(os.environ, {
            "UNIPUS_THEME": "", "UNIPUS_ACCENT": "", "UNIPUS_REDUCED_MOTION": "",
        })
        environment.start()
        self.addCleanup(environment.stop)
        system_theme = patch.object(platform_theme, "_system_dark", return_value=False)
        system_theme.start()
        self.addCleanup(system_theme.stop)
        previous_scheme = APP.styleHints().colorScheme()
        self.addCleanup(APP.styleHints().setColorScheme, previous_scheme)

    def test_switching_theme_and_animations_emits_notifications(self):
        info = platform_theme.PlatformInfo(theme_mode="light", animations_enabled=True)
        theme_events, animation_events = [], []
        info.themeChanged.connect(lambda: theme_events.append(info.dark))
        info.animationsChanged.connect(lambda: animation_events.append(info.animationsEnabled))
        old_fill = info.accentFill.name()
        info.setAppearance("dark", False)
        self.assertTrue(info.dark)
        self.assertEqual(info.themeMode, "dark")
        self.assertFalse(info.animationsEnabled)
        self.assertTrue(info.themeId.endswith("dark"))
        self.assertEqual(info.accentHex, platform_theme.default_accent(True))
        self.assertNotEqual(info.accentFill.name(), old_fill)
        info.setAppearance("dark", False)
        self.assertEqual(theme_events, [True])
        self.assertEqual(animation_events, [False])
        info.setAppearance("light", True)
        self.assertEqual(theme_events, [True, False])
        self.assertEqual(animation_events, [False, True])

    def test_dynamic_qml_properties_expose_notify_signals(self):
        info = platform_theme.PlatformInfo(theme_mode="light")
        for name in ("dark", "themeMode", "accent", "accentFill", "accentPressed", "accentHex", "themeId", "animationsEnabled"):
            with self.subTest(name=name):
                prop = info.metaObject().property(info.metaObject().indexOfProperty(name))
                self.assertTrue(prop.hasNotifySignal())
                self.assertFalse(prop.isConstant())

    def test_invalid_theme_mode_restores_system_default(self):
        info = platform_theme.PlatformInfo(theme_mode="dark")
        info.setAppearance("invalid", True)
        self.assertEqual(info.themeMode, "system")
        self.assertFalse(info.dark)

    def test_theme_environment_override_wins_over_saved_and_preview_modes(self):
        for override, expected, opposite in (("dark", True, "light"), ("light", False, "dark")):
            with self.subTest(override=override), patch.dict(os.environ, {"UNIPUS_THEME": override}):
                info = platform_theme.PlatformInfo(theme_mode=opposite)
                self.assertTrue(info.themeOverridden)
                self.assertIs(info.dark, expected)
                info.setAppearance("system", False)
                self.assertIs(info.dark, expected)
                info.setAppearance(opposite, True)
                self.assertIs(info.dark, expected)

    def test_custom_accent_is_preserved_when_switching_themes(self):
        with patch.dict(os.environ, {"UNIPUS_ACCENT": "#8855aa"}):
            info = platform_theme.PlatformInfo(theme_mode="light")
        original_accent = info.accentHex
        original_fill = info.accentFill.name()
        info.setAppearance("dark", True)
        self.assertEqual(info.accentHex, original_accent)
        self.assertNotEqual(info.accentFill.name(), original_fill)

    def test_reduced_motion_environment_override_survives_user_switch(self):
        with patch.dict(os.environ, {"UNIPUS_REDUCED_MOTION": "1"}):
            info = platform_theme.PlatformInfo(theme_mode="light", animations_enabled=False)
        self.assertTrue(info.reducedMotion)
        info.setAppearance("dark", True)
        self.assertTrue(info.animationsEnabled)
        self.assertTrue(info.reducedMotion)


if __name__ == "__main__":
    unittest.main()
