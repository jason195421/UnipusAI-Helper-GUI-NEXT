# -*- coding: utf-8 -*-
"""Configuration storage for the workbench's built-in Settings page.

The standalone PyQt5 / Fluent Widgets editor (config_editor.py) has been
superseded: config.json is now read and written through this PySide6 QObject,
exposed to QML as the "settings" context property, so the whole product
ships as one program, one window and one process.
"""
from __future__ import annotations

import json

from PySide6.QtCore import Property, QObject, Signal, Slot

from .accuracy_control import parse_target_accuracy
from .paths import PROJECT_ROOT
from .record_skip import normalize_skip_record_mode

CONFIG_PATH = PROJECT_ROOT / "config.json"

DEFAULT_CONFIG = {
    "username": "",
    "password": "",
    "url": "https://uai.unipus.cn/sso/index.html?service=https%3A%2F%2Fucloud.unipus.cn%2Fhome",
    "token_full": "",
    "api_key": "",
    "base_url": "",
    "model": "",
    "max_tokens": 32768,
    "temperature": 0.3,
    "record_score_threshold": 70,
    "score_threshold": 80,
    "target_accuracy": "100",
    "skip_record_mode": "off",
    "debug_mode": False,
    # 录音题（Exercise 2 这类主观题）没有原声，需要 TTS 生成语音
    "tts_engine": "auto",   # auto / edge / sapi / off
    "tts_voice": "",
    "tts_proxy": "",
    "screenshot": False,
    "answer_api_enabled": True,
    "theme_mode": "system",
    "animations_enabled": True,
    # 浏览器后端：edge（默认）/ chrome，见 unipus_helper/browser.py
    "browser": "edge",
}

STRING_FIELDS = (
    "username", "password", "url", "token_full", "api_key", "base_url",
    "model", "tts_engine", "tts_voice", "tts_proxy", "browser",
    "target_accuracy", "skip_record_mode",
)


def load_config():
    """Defaults merged with the on-disk config.json; never raises."""
    cfg = dict(DEFAULT_CONFIG)
    try:
        with CONFIG_PATH.open("r", encoding="utf-8") as handle:
            data = json.load(handle)
        if isinstance(data, dict):
            cfg.update(data)
    except (OSError, ValueError, TypeError):
        pass
    cfg.update(appearance_preferences(cfg))
    return cfg


def appearance_preferences(data):
    """Normalize just appearance fields, including configs written by older UI."""
    mode = str(data.get("theme_mode", "system")).strip().lower()
    enabled = data.get("animations_enabled", True)
    if isinstance(enabled, str):
        enabled = enabled.strip().lower() not in ("false", "0", "off", "no")
    return {
        "theme_mode": mode if mode in ("system", "light", "dark") else "system",
        "animations_enabled": True if enabled is None else bool(enabled),
    }


def _as_text(value, default=""):
    return default if value is None else str(value)


def _clean_token(value):
    token = _as_text(value).strip()
    if len(token) >= 2 and (
        (token[0] == "'" and token[-1] == "'")
        or (token[0] == '"' and token[-1] == '"')
    ):
        token = token[1:-1].strip()
    return token


def normalize_config(data):
    """Coerce values coming from QML (QVariantMap) into the on-disk schema."""
    if not isinstance(data, dict):
        data = {}
    out = dict(DEFAULT_CONFIG)
    for key in STRING_FIELDS:
        out[key] = _as_text(data.get(key, DEFAULT_CONFIG[key]))
    out["token_full"] = _clean_token(data.get("token_full", ""))
    try:
        out["max_tokens"] = int(float(data.get("max_tokens", DEFAULT_CONFIG["max_tokens"])))
    except (TypeError, ValueError):
        out["max_tokens"] = DEFAULT_CONFIG["max_tokens"]
    try:
        out["temperature"] = float(data.get("temperature", DEFAULT_CONFIG["temperature"]))
    except (TypeError, ValueError):
        out["temperature"] = DEFAULT_CONFIG["temperature"]
    for key in ("record_score_threshold", "score_threshold"):
        try:
            value = float(data.get(key, DEFAULT_CONFIG[key]))
        except (TypeError, ValueError):
            value = DEFAULT_CONFIG[key]
        if not 0 <= value <= 100:
            raise ValueError(f"{key} must be between 0 and 100")
        out[key] = value
    low, high, canonical = parse_target_accuracy(data.get("target_accuracy", "100"))
    out["target_accuracy"] = canonical
    out["skip_record_mode"] = normalize_skip_record_mode(data.get("skip_record_mode", "off"))
    for key in ("debug_mode", "screenshot", "answer_api_enabled"):
        value = data.get(key, DEFAULT_CONFIG[key])
        out[key] = value is True or (
            isinstance(value, str) and value.casefold() == "true"
        )
    out.update(appearance_preferences(data))
    return out


def save_config(data):
    """Write config.json next to the executable. Raises on IO errors."""
    out = normalize_config(data)
    CONFIG_PATH.parent.mkdir(parents=True, exist_ok=True)
    with CONFIG_PATH.open("w", encoding="utf-8") as handle:
        json.dump(out, handle, indent=2, ensure_ascii=False)
    return True


def is_config_ready():
    """True when the U Campus credentials needed to boot the bot are present."""
    config = load_config()
    return bool(_as_text(config.get("username")).strip() and _as_text(config.get("password")).strip())


class SettingsBridge(QObject):
    """Exposes config.json to QML as the "settings" context property."""

    saved = Signal()
    failed = Signal(str)

    def __init__(self, parent=None, setup_mode=False):
        super().__init__(parent)
        self._setup_mode = bool(setup_mode)

    @Property(bool, constant=True)
    def setupMode(self):
        return self._setup_mode

    @Slot(result="QVariantMap")
    def load(self):
        return load_config()

    @Slot("QVariantMap", result=bool)
    def save(self, data):
        if not isinstance(data, dict):
            self.failed.emit("配置数据格式无效")
            return False
        try:
            save_config(data)
        except Exception as exc:
            self.failed.emit(str(exc))
            return False
        self.previewAppearance(**appearance_preferences(data))
        self.saved.emit()
        return True

    @Slot(str, bool)
    def previewAppearance(self, theme_mode, animations_enabled):
        """Apply appearance without saving other fields or ending first-run setup."""
        from .platform_theme import set_appearance

        set_appearance(theme_mode, animations_enabled)

    @Slot(result=bool)
    def isReady(self):
        return is_config_ready()
