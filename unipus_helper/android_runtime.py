"""Standalone Android host for the shared automation core.

Chaquopy calls these functions from an Android worker thread. This module is
standard-library-only until initialize() has selected the app-private data
directory; it never imports the desktop Qt interface or starts a WebDriver
service. Browser requests are served by the activity's native WebView bridge.
"""
from __future__ import annotations

import importlib
import json
import math
import os
from pathlib import Path
import tempfile
import threading
from types import SimpleNamespace
from urllib.parse import urlsplit


DEFAULT_LOGIN_URL = "https://ucloud.unipus.cn/home"
LEGACY_DEFAULT_LOGIN_URL = "https://uai.unipus.cn/sso/index.html?service=https%3A%2F%2Fucloud.unipus.cn%2Fhome"
DEFAULT_CONFIG = {
    "username": "", "password": "", "url": DEFAULT_LOGIN_URL,
    "api_key": "", "base_url": "", "model": "", "token_full": "",
    "temperature": 0.3, "max_tokens": 32768, "debug_mode": False,
    "record_score_threshold": 70, "score_threshold": 80,
    "target_accuracy": "100", "browser": "chrome",
    "theme_mode": "system", "animations_enabled": True,
    # Android uses its native speech service; model downloads remain explicit.
    "tts_engine": "android", "skip_record_mode": "off", "screenshot": False,
    "answer_api_enabled": True,
}
CAPABILITIES = {
    "standalone": True, "requiresComputer": False,
    "browser": "android-webview", "textAutomation": True,
    "localWhisper": False, "desktopFFmpeg": False, "recordingAutomation": False,
    "nativeFFmpeg": False, "speechSynthesis": False,
}
_STATE_LOCK = threading.RLock()
_current = None
_config_path = None
_boot_error = ""


def _json(value):
    return json.dumps(value, ensure_ascii=False, allow_nan=False)


def _object(raw, *, label="配置"):
    if raw is None or raw == "":
        return {}
    value = json.loads(str(raw)) if not isinstance(raw, dict) else dict(raw)
    if not isinstance(value, dict):
        raise ValueError(f"{label}必须是 JSON 对象")
    return value


def _bool(value, default=False):
    if value is None:
        return default
    if isinstance(value, str):
        return value.strip().lower() not in ("", "false", "0", "off", "no")
    return bool(value)


def _normalize_config(data):
    out = dict(DEFAULT_CONFIG)
    out.update(data)
    for name in ("username", "password", "url", "api_key", "base_url", "model", "token_full"):
        value = out.get(name)
        if value is not None and not isinstance(value, str):
            raise ValueError(f"{name} 必须是文本")
        out[name] = value or ""
    for name in ("username", "url", "api_key", "base_url", "model"):
        out[name] = out[name].strip()
    out["url"] = out["url"] or DEFAULT_LOGIN_URL
    if out["url"] == LEGACY_DEFAULT_LOGIN_URL:
        out["url"] = DEFAULT_LOGIN_URL
    for name in ("url", "base_url"):
        if out[name] and urlsplit(out[name]).scheme not in ("http", "https"):
            raise ValueError(f"{name} 必须是 HTTP 或 HTTPS 地址")
    out["max_tokens"] = int(out.get("max_tokens", 32768))
    if not 1 <= out["max_tokens"] <= 131072:
        raise ValueError("max_tokens 必须在 1–131072 之间")
    out["temperature"] = float(out.get("temperature", .3))
    if not math.isfinite(out["temperature"]) or not 0 <= out["temperature"] <= 2:
        raise ValueError("temperature 必须在 0–2 之间")
    for name in ("record_score_threshold", "score_threshold"):
        out[name] = float(out[name])
        if not math.isfinite(out[name]) or not 0 <= out[name] <= 100:
            raise ValueError(f"{name} 必须在 0–100 之间")
    from .accuracy_control import parse_target_accuracy

    out["target_accuracy"] = parse_target_accuracy(out["target_accuracy"])[2]
    mode = str(out.get("theme_mode", "system")).strip().lower()
    out["theme_mode"] = mode if mode in ("system", "light", "dark") else "system"
    out["animations_enabled"] = _bool(out.get("animations_enabled"), True)
    out["debug_mode"] = _bool(out.get("debug_mode"))
    out["screenshot"] = _bool(out.get("screenshot"))
    out["answer_api_enabled"] = _bool(out.get("answer_api_enabled"), True)
    from .record_skip import normalize_skip_record_mode

    engine = str(out.get("tts_engine") or "android").strip().lower()
    out["tts_engine"] = "off" if engine in ("off", "none", "disable", "disabled") else "android"
    out["tts_voice"] = str(out.get("tts_voice") or "").strip()
    out["skip_record_mode"] = normalize_skip_record_mode(out.get("skip_record_mode"))
    out["browser"] = "chrome"
    return out


def _read_config(path):
    if not path.exists():
        return {}
    return _object(path.read_text(encoding="utf-8"))


def _write_config(path, config):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=path.parent,
                                         prefix=".config-", suffix=".tmp", delete=False) as handle:
            temporary = Path(handle.name)
            json.dump(config, handle, ensure_ascii=False, indent=2, allow_nan=False)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def _redact(message, config):
    text = str(message)
    for key in ("password", "api_key", "token_full", "username"):
        value = str(config.get(key) or "")
        if value:
            text = text.replace(value, "••••")
    return text[:500]


def _preferences(config):
    return {"theme_mode": config.get("theme_mode", "system"),
            "animations_enabled": config.get("animations_enabled", True)}


def _empty_snapshot(error=""):
    return {
        "version": "3.9.19", "ready": False, "busy": False, "operation": None,
        "status": "需要检查" if error else "等待初始化", "error": error,
        "excludeUngraded": False, "debug": False, "tasks": [], "logs": [],
        "account": "未连接账号", "model": "未配置", "apiConfigured": False,
        "apiTesting": False, "apiStatus": "未检测", "apiOk": False, "apiFail": False,
        "progress": {"completed": 0, "total": 0},
        "platform": "android", "capabilities": dict(CAPABILITIES),
        "audio": {},
        "preferences": _preferences(DEFAULT_CONFIG), "configRestartRequired": False,
    }


def _controller_status(runtime, status, error=""):
    with runtime.controller._lock:
        runtime.controller._status = status
        runtime.controller._error = _redact(error, runtime.config)


def _start_popup_watcher(runtime):
    with runtime.controller._lock:
        if runtime.watcher_started or runtime.stopped.is_set():
            return
        runtime.watcher_started = True
        watcher = threading.Thread(target=runtime.bot.popup_watcher.run, daemon=True,
                                   name="android-popups")
        runtime.bot._background_threads.append(watcher)
        watcher.start()


def _authenticated(runtime):
    """A button press alone must never manufacture a logged-in state."""
    driver = runtime.bot.driver
    driver.switch_to.default_content()
    url = urlsplit(driver.current_url)
    return (url.scheme == "https" and url.hostname == "ucloud.unipus.cn"
            and bool(runtime.bot._home_ready(driver)))


def _login_worker(runtime):
    try:
        configured = bool(runtime.config["username"] and runtime.config["password"])
        if configured:
            _controller_status(runtime, "正在登录")
            success = runtime.bot._login()
            if success and not runtime.stopped.is_set():
                _start_popup_watcher(runtime)
                runtime.controller.enable_scan_button()
                return
        else:
            runtime.bot.driver.get(runtime.config["url"])
        if runtime.stopped.is_set():
            return
        _controller_status(runtime, "等待手动登录")
        runtime.controller._add_log("请在课程浏览器中完成登录或验证码，登录成功后可扫描课程。", "info")
        while not runtime.stopped.wait(.75):
            try:
                if _authenticated(runtime):
                    _start_popup_watcher(runtime)
                    runtime.controller.enable_scan_button()
                    return
            except Exception:
                # Navigation/frame replacement during manual SSO is transient.
                continue
    except Exception as exc:
        if not runtime.stopped.is_set():
            message = _redact(exc, runtime.config)
            _controller_status(runtime, "需要检查", message)
            runtime.controller._add_log("浏览器登录失败：" + message, "error")


def _refresh_audio(runtime):
    from . import android_audio

    audio = android_audio.status(voice=runtime.config.get("tts_voice", ""))
    if not runtime.stopped.is_set():
        with _STATE_LOCK:
            runtime.audio_status = audio
    return audio


def _audio_capabilities(runtime):
    result = dict(CAPABILITIES)
    audio = runtime.audio_status
    tts, local = audio.get("tts", {}), audio.get("transcription", {})
    result.update(recordingAutomation=runtime.recording_ready,
                  speechSynthesis=tts.get("available") is True,
                  localWhisper=(local.get("whisperAvailable") is True
                                and local.get("ffmpegAvailable") is True
                                and local.get("modelReady") is True),
                  nativeFFmpeg=local.get("ffmpegAvailable") is True)
    return result


def initialize(bridge, data_dir: str, config_json: str = "{}") -> str:
    """Initialize without waiting for SSO, browser navigation or task work."""
    global _current, _config_path, _boot_error
    shutdown()
    config = {}
    bot = controller = None
    try:
        private = Path(str(data_dir)).expanduser()
        if not private.is_absolute():
            raise ValueError("安卓数据目录必须是应用私有绝对路径")
        private = private.resolve()
        private.mkdir(parents=True, exist_ok=True)
        temporary = private / "tmp"
        temporary.mkdir(exist_ok=True)
        os.environ["UNIPUS_ANDROID"] = "1"
        os.environ["UNIPUS_DATA_DIR"] = str(private)
        for key in ("TMPDIR", "TEMP", "TMP"):
            os.environ[key] = str(temporary)
        tempfile.tempdir = str(temporary)
        _config_path = private / "config.json"
        config = _read_config(_config_path)
        config.update(_object(config_json))
        config = _normalize_config(config)
        _write_config(_config_path, config)
        if bridge is None:
            raise ValueError("安卓 WebView 桥尚未准备好")
        core = importlib.import_module("UnipusAI_Helper")
        # Activity recreation stays in the same app-private directory.
        core.BASE_DIR = str(private)
        core.logger = core.setup_logging()
        from .android_driver import AndroidWebDriver
        from .android_crypto import configure as configure_crypto
        from . import android_audio
        from .workbench_controller import WorkbenchController

        configure_crypto(bridge)
        android_audio.configure(bridge)
        recording = {"ready": False, "error": ""}

        def create_driver():
            driver = AndroidWebDriver(bridge)
            if config["skip_record_mode"] == "all":
                return driver
            try:
                driver.execute_cdp_cmd("Page.addScriptToEvaluateOnNewDocument", {
                    "source": core.RECORD_MIC_BOOTSTRAP_JS,
                })
                recording["ready"] = True
            except Exception as exc:
                recording["error"] = _redact(exc, config)
            if recording["ready"]:
                try:
                    # Registration covers future frames; this also covers the
                    # WebView's initial document before the first navigation.
                    driver.execute_script(core.RECORD_MIC_BOOTSTRAP_JS)
                except Exception:
                    pass
            return driver

        bot = core.UCampusBot(str(_config_path), skip_check=True,
                             driver_factory=create_driver)
        solver = core.AISolver(bot.driver, bot.config)
        controller = WorkbenchController(bot.driver, solver, bot, vars(core))
        bot.gui = controller
        runtime = SimpleNamespace(
            bridge=bridge, directory=private, core=core, bot=bot,
            controller=controller, config=config, stopped=threading.Event(),
            pending_restart=False, watcher_started=False,
            audio_status={}, recording_ready=recording["ready"],
        )
        with _STATE_LOCK:
            _current, _boot_error = runtime, ""
        controller._add_log("安卓独立运行：浏览器和文本任务在手机上执行，不需要电脑。", "success")
        controller._add_log("录音使用安卓本地语音引擎；本地转写需先在语音设置中下载 Whisper 模型。", "info")
        if recording["error"]:
            controller._add_log("录音注入脚本安装失败：" + recording["error"], "warning")
        login = threading.Thread(target=_login_worker, args=(runtime,), daemon=True, name="android-login")
        audio = threading.Thread(target=_refresh_audio, args=(runtime,), daemon=True,
                                 name="android-audio-status")
        bot._background_threads = [login, audio]
        login.start()
        audio.start()
    except Exception as exc:
        if _current is not None:
            shutdown()
        elif controller is not None:
            controller.close()
        elif bot is not None:
            bot.request_shutdown()
            if bot.driver is not None:
                try:
                    bot.driver.quit()
                except Exception:
                    pass
        _boot_error = _redact(exc, config)
    return snapshot()


def snapshot() -> str:
    with _STATE_LOCK:
        runtime, error = _current, _boot_error
    if runtime is None:
        return _json(_empty_snapshot(error))
    state = runtime.controller.snapshot()
    state.update(platform="android", capabilities=_audio_capabilities(runtime),
                 audio=runtime.audio_status,
                 preferences=_preferences(runtime.config),
                 configRestartRequired=runtime.pending_restart)
    return _json(state)


def command(action: str, payload_json: str = "{}") -> str:
    with _STATE_LOCK:
        runtime = _current
    if runtime is None:
        return _json({"ok": False, "error": _boot_error or "请先初始化安卓工作台。"})
    try:
        payload = _object(payload_json, label="操作参数")
        controller = runtime.controller
        if action in ("scan", "scan_unit", "run", "quick"):
            if runtime.pending_restart:
                return _json({"ok": False, "error": "设置已保存，请重新加载浏览器后执行任务。"})
            result = controller.start_action(action)
        elif action == "stop":
            result = controller.stop()
            from .android_audio import cancel
            cancel()
        elif action == "audio_status":
            result = {"ok": True, "audio": _refresh_audio(runtime)}
        elif action == "prepare_audio_model":
            if controller.snapshot()["busy"]:
                return _json({"ok": False, "error": "请先停止当前任务，再下载语音模型。"})
            from .android_audio import prepare_model
            try:
                prepared = prepare_model()
                result = {"ok": True, "model": prepared}
            finally:
                _refresh_audio(runtime)
        elif action == "restart":
            if controller.snapshot()["busy"]:
                return _json({"ok": False, "error": "请先停止当前任务，再重新加载浏览器。"})
            state = json.loads(initialize(runtime.bridge, str(runtime.directory), "{}"))
            return _json({"ok": not bool(state["error"]), "state": state})
        elif action == "select":
            ids = payload.get("ids", [payload.get("id")] if payload.get("id") else [])
            result = controller.set_selection(ids, _bool(payload.get("selected"), True))
        elif action in ("select_mode", "all", "none"):
            ids = payload.get("ids")
            if ids is None:
                ids = [task["id"] for task in controller.snapshot()["tasks"]]
            mode = payload.get("mode", "all") if action == "select_mode" else action
            result = controller.select_tasks(mode, ids)
        elif action == "exclude_ungraded":
            result = controller.set_exclude_ungraded(_bool(payload.get("enabled")))
        elif action == "test_api":
            result = controller.test_api()
        elif action == "mark_ready":
            if controller.snapshot()["busy"]:
                result = {"ok": False, "error": "当前任务运行中。"}
            elif not _authenticated(runtime):
                result = {"ok": False, "error": "尚未检测到有效的 U 校园登录会话。"}
            else:
                if not controller.snapshot()["ready"]:
                    _start_popup_watcher(runtime)
                    controller.enable_scan_button()
                result = {"ok": True}
        else:
            result = {"ok": False, "error": "无法识别此操作。"}
        return _json(result)
    except Exception as exc:
        return _json({"ok": False, "error": _redact(exc, runtime.config)})


def save_config(config_json: str) -> str:
    """Merge a form update without losing unknown settings or stored secrets."""
    global _boot_error
    with _STATE_LOCK:
        runtime, path = _current, _config_path
    if path is None:
        return _json({"ok": False, "error": "请先初始化安卓数据目录。"})
    old = {}
    try:
        old = _read_config(path)
        changed = dict(old)
        changed.update(_object(config_json))
        changed = _normalize_config(changed)
        _write_config(path, changed)
        operational = (set(old) | set(changed)) - {"theme_mode", "animations_enabled"}
        restart = any(old.get(key) != changed.get(key) for key in operational)
        if runtime is not None:
            runtime.config = changed
            runtime.pending_restart = runtime.pending_restart or restart
        _boot_error = ""
        return _json({"ok": True, "restart_required": restart,
                      "preferences": _preferences(changed),
                      "message": "设置已保存。" + ("请重新加载浏览器以应用连接或任务设置。" if restart else "")})
    except Exception as exc:
        return _json({"ok": False, "error": _redact(exc, old)})


def shutdown() -> None:
    global _current
    with _STATE_LOCK:
        runtime, _current = _current, None
    if runtime is None:
        return
    runtime.stopped.set()
    from . import android_audio

    try:
        android_audio.cancel()
        try:
            android_audio.release()
        except Exception:
            pass
        runtime.bot.request_shutdown()
    finally:
        try:
            runtime.controller.close()
        finally:
            android_audio.configure(None)
