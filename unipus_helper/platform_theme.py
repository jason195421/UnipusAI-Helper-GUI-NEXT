"""Platform detection for the adaptive Qt Quick theme.

One QML codebase serves three looks:

* Windows  -> WinUI 3 / Fluent (Mica-like tint, rounded cards, Fluent blue)
* macOS    -> Aqua (vibrancy-like sidebar, system blue, PingFang SC)
* Linux    -> neutral dark fallback

The chosen palette is exposed to QML as a registered singleton
(import UnipusAI.Platform 1.0).  Values are resolved once at startup;
set UNIPUS_THEME=dark|light (or UNIPUS_ACCENT=#rrggbb) to override.
Motion follows Qt's accessibility hints when available; set
UNIPUS_REDUCED_MOTION=1 to disable nonessential animation on any Qt version.
"""
from __future__ import annotations

import os
import subprocess
import sys

from PySide6.QtCore import Property, QObject, Qt, Signal
from PySide6.QtGui import QColor, QFontDatabase, QGuiApplication
from PySide6.QtQml import qmlRegisterSingletonInstance

URI = "UnipusAI.Platform"
TYPE_NAME = "PlatformInfo"


def is_windows() -> bool:
    return sys.platform == "win32"


def is_macos() -> bool:
    return sys.platform == "darwin"


def _registry_dword(path: str, name: str):
    if not is_windows():
        return None
    try:
        import winreg
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, path) as key:
            value, _ = winreg.QueryValueEx(key, name)
            return int(value)
    except (OSError, ValueError, TypeError):
        return None


def _system_dark() -> bool:
    override = (os.environ.get("UNIPUS_THEME") or "").strip().lower()
    if override in ("dark", "1", "true"):
        return True
    if override in ("light", "0", "false"):
        return False

    if is_windows():
        # AppsUseLightTheme: 0 = dark apps, 1 = light apps.  Fall back to the
        # system-wide value, then to light (the Windows default).
        personalize = r"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize"
        apps = _registry_dword(personalize, "AppsUseLightTheme")
        if apps is not None:
            return apps == 0
        system = _registry_dword(personalize, "SystemUsesLightTheme")
        if system is not None:
            return system == 0
        return False

    if is_macos():
        try:
            result = subprocess.run(
                ["defaults", "read", "-g", "AppleInterfaceStyle"],
                capture_output=True, text=True, timeout=2,
            )
            return result.returncode == 0 and "dark" in result.stdout.lower()
        except (OSError, subprocess.SubprocessError):
            return False

    # Linux / other: prefer a dark GTK scheme, otherwise keep the neutral
    # dark palette the app has always shipped with.
    theme = (os.environ.get("GTK_THEME") or "").lower()
    if "light" in theme:
        return False
    try:
        result = subprocess.run(
            ["gsettings", "get", "org.gnome.desktop.interface", "color-scheme"],
            capture_output=True, text=True, timeout=2,
        )
        if result.returncode == 0 and "prefer-light" in result.stdout:
            return False
    except (OSError, subprocess.SubprocessError):
        pass
    return True


def default_accent(dark: bool) -> str:
    if is_windows():
        # WinUI 3 exposes a lighter accent on dark backgrounds for contrast.
        return "#60CDFF" if dark else "#0078D4"
    if is_macos():
        return "#0A84FF" if dark else "#007AFF"
    return "#77B4FF" if dark else "#0067C0"


def ui_font_family() -> str:
    try:
        families = set(QFontDatabase.families())
    except Exception:
        families = set()
    if is_windows():
        preferred = ("Microsoft YaHei UI", "Microsoft YaHei", "Segoe UI", "Noto Sans SC")
    elif is_macos():
        preferred = ("PingFang SC", "SF Pro Text", "Helvetica Neue", "Noto Sans SC")
    else:
        preferred = ("Noto Sans SC", "Source Han Sans SC", "DejaVu Sans")
    return next((name for name in preferred if name in families), "")


class PlatformInfo(QObject):
    """QML-facing platform description, with live accessibility preferences.

    PySide6 6.12 requires the @Property decorator form on a QObject subclass;
    assigning Property(...) as a class attribute silently yields false/empty
    values inside QML singletons.
    """

    reducedMotionChanged = Signal()

    def __init__(self, dark: bool | None = None, accent: str | None = None,
                 parent=None):
        super().__init__(parent)
        self._dark = _system_dark() if dark is None else bool(dark)
        self._accent = (accent or os.environ.get("UNIPUS_ACCENT") or
                        default_accent(self._dark))
        self._family = ui_font_family()
        self._force_reduced_motion = os.environ.get(
            "UNIPUS_REDUCED_MOTION", ""
        ).strip().lower() in ("1", "true", "yes", "on")
        self._motion_source = None
        # Accessibility hints are newer than our minimum supported Qt version.
        app = QGuiApplication.instance()
        accessibility = getattr(app.styleHints(), "accessibility", None) if app else None
        if callable(accessibility):
            try:
                self._motion_source = accessibility()
                changed = getattr(self._motion_source, "motionPreferenceChanged", None)
                if changed is not None:
                    changed.connect(self._motion_preference_changed)
            except (AttributeError, RuntimeError, TypeError):
                self._motion_source = None

    def _motion_preference_changed(self, *_):
        self.reducedMotionChanged.emit()

    @Property(bool, notify=reducedMotionChanged)
    def reducedMotion(self):
        if self._force_reduced_motion:
            return True
        reduced = getattr(getattr(Qt, "MotionPreference", None), "ReducedMotion", None)
        preference = getattr(self._motion_source, "motionPreference", None)
        try:
            return reduced is not None and callable(preference) and preference() == reduced
        except RuntimeError:
            return False

    @Property(bool, constant=True)
    def isWindows(self):
        return is_windows()

    @Property(bool, constant=True)
    def isMacOS(self):
        return is_macos()

    @Property(bool, constant=True)
    def isLinux(self):
        return not (is_windows() or is_macos())

    @Property(bool, constant=True)
    def dark(self):
        return self._dark

    @Property(QColor, constant=True)
    def accent(self):
        return QColor(self._accent)

    @Property(QColor, constant=True)
    def accentFill(self):
        c = QColor(self._accent)
        return c.darker(112) if self._dark else c

    @Property(QColor, constant=True)
    def accentPressed(self):
        c = QColor(self._accent)
        return c.lighter(112) if self._dark else c.darker(118)

    @Property(str, constant=True)
    def accentHex(self):
        return self._accent

    @Property(str, constant=True)
    def family(self):
        return self._family

    @Property(str, constant=True)
    def themeId(self):
        if is_windows():
            return "winui3-dark" if self._dark else "winui3-light"
        if is_macos():
            return "mac-dark" if self._dark else "mac-light"
        return "fluent-dark" if self._dark else "fluent-light"

    def color_scheme(self):
        return Qt.ColorScheme.Dark if self._dark else Qt.ColorScheme.Light


_PLATFORM_INFO = None
_REGISTERED = False


def register_platform_theme(dark: bool | None = None, accent: str | None = None):
    """Register (once) the singleton and return it; keeps Python ownership."""
    global _PLATFORM_INFO, _REGISTERED
    if _PLATFORM_INFO is None:
        _PLATFORM_INFO = PlatformInfo(dark=dark, accent=accent)
    if not _REGISTERED:
        qmlRegisterSingletonInstance(
            PlatformInfo, URI, 1, 0, TYPE_NAME, _PLATFORM_INFO
        )
        _REGISTERED = True
    return _PLATFORM_INFO
