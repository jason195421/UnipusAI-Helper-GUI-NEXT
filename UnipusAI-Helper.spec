# -*- mode: python ; coding: utf-8 -*-
"""PyInstaller spec for UnipusAI-Helper.

One spec produces two build variants, selected by the UNIPUS_BUILD_MODE
environment variable (see scripts/build_variant.py):

  UNIPUS_BUILD_MODE=debug    console=True  -> terminal visible on Windows,
                               Terminal logs on macOS; name suffix "-Debug";
                               no macOS .app bundle.
  UNIPUS_BUILD_MODE=release  console=False -> pure GUI / windowed, no terminal;
                               macOS gets a real .app bundle.

The runtime (UnipusAI_Helper.py) detects a windowed build automatically
(sys.stdout/stderr/stdin are None) and switches to GUI_MODE: stdio is
redirected to log files, fatal errors surface as a system dialog, and the
interactive environment-check menu is skipped.  A release build additionally
forces UNIPUS_GUI=1 through a runtime hook, so the console flag remains the
single source of truth for debug vs release behaviour.
"""
from PyInstaller.utils.hooks import collect_data_files, collect_submodules
import os
import platform as _platform
import sys as _sys

APP_VERSION = "3.9.19"

BUILD_MODE = os.environ.get("UNIPUS_BUILD_MODE", "release").strip().lower()
if BUILD_MODE not in ("debug", "release"):
    BUILD_MODE = "release"
IS_DEBUG = BUILD_MODE == "debug"
IS_MAC = _sys.platform == "darwin"
SUFFIX = "-Debug" if IS_DEBUG else ""

# A console=True macOS .app bundle writes LSBackgroundOnly into Info.plist,
# which hides the Dock icon / menu bar and stops the QML window from appearing.
# Therefore macOS console (debug) builds are shipped as a plain onedir folder
# launched from Terminal, while only macOS release builds become a .app.
CONSOLE = IS_DEBUG
MAKE_APP_BUNDLE = IS_MAC and not IS_DEBUG

EXE_NAME = "UnipusAI-Helper" + SUFFIX
COLLECT_NAME = "UnipusAI-Helper" + SUFFIX
APP_NAME = "UnipusAI-Helper" + SUFFIX + ".app"

RUNTIME_HOOKS = [] if IS_DEBUG else ["scripts/gui_runtime_hook.py"]

print("[spec] BUILD_MODE=%s IS_DEBUG=%s IS_MAC=%s CONSOLE=%s "
      "MAKE_APP_BUNDLE=%s EXE_NAME=%s" % (
          BUILD_MODE, IS_DEBUG, IS_MAC, CONSOLE, MAKE_APP_BUNDLE, EXE_NAME))


def _ffmpeg_platform_id():
    """返回本次构建要内置的 ffmpeg/<id> 目录（与 ffmpeg_runtime.platform_id 一致）。"""
    if _sys.platform == "win32":
        return "win32-x64"
    if _sys.platform == "darwin":
        return "darwin-arm64" if _platform.machine() == "arm64" else "darwin-x64"
    machine = _platform.machine().lower()
    if machine in ("aarch64", "arm64"):
        return "linux-arm64"
    return "linux-x64"


datas = [('unipus_helper/qml', 'unipus_helper/qml')]
datas += collect_data_files('whisper')
datas += collect_data_files('selenium')

# 内置 FFmpeg：把对应平台的 ffmpeg/ffprobe 二进制原样收进 _MEIPASS，
# 运行时由 unipus_helper.ffmpeg_runtime 注入 PATH，首次启动无需联网下载。
# 该目录由 scripts/fetch_ffmpeg.py 或 GitHub Actions 在构建前生成（不进 git）。
_ffmpeg_id = _ffmpeg_platform_id()
_ffmpeg_dir = os.path.join('ffmpeg', _ffmpeg_id)
if os.path.isdir(_ffmpeg_dir):
    datas.append((_ffmpeg_dir, 'ffmpeg/' + _ffmpeg_id))
else:
    print('[spec] WARNING: 未找到 ' + _ffmpeg_dir + '，请先运行 scripts/fetch_ffmpeg.py --platform ' + _ffmpeg_id)

# 内置 Whisper 模型权重：whisper_models/base.pt 由 scripts/fetch_whisper.py
# 在构建前准备（复用本机 whisper 缓存或从官方镜像下载），随 _MEIPASS 发布。
# 运行时 unipus_helper.whisper_runtime 定位该目录并作为 download_root 传给
# whisper.load_model，文件 SHA256 与官方 URL 内嵌校验值一致，首次识别零联网。
_whisper_models_dir = 'whisper_models'
if os.path.isfile(os.path.join(_whisper_models_dir, 'base.pt')):
    datas.append((_whisper_models_dir, _whisper_models_dir))
else:
    print('[spec] WARNING: 未找到 whisper_models/base.pt，请先运行 scripts/fetch_whisper.py')

hiddenimports = collect_submodules('unipus_helper')
# selenium 4.x 在 selenium/webdriver/__init__.py 用 __getattr__ + importlib.import_module
# 懒加载 webdriver.EdgeOptions / Edge / ActionChains / Keys 等（第 92-103 行），
# PyInstaller 静态分析发现不了这些动态导入，必须显式收集全部子模块，
# 否则运行时报 ModuleNotFoundError: No module named 'selenium.webdriver.edge.options'。
hiddenimports += collect_submodules('selenium')
hiddenimports += [
    'PySide6.QtCore', 'PySide6.QtGui', 'PySide6.QtQml',
    'PySide6.QtQuick', 'PySide6.QtQuickControls2', 'shiboken6',
    'edge_tts', 'tiktoken_ext.openai_public', 'whisper',
]
# winreg / winsound only exist on Windows; keep them platform-gated.
if _sys.platform == 'win32':
    hiddenimports += ['winreg', 'winsound']

a = Analysis(
    ['UnipusAI_Helper.py'],
    pathex=[],
    binaries=[],
    datas=datas,
    hiddenimports=hiddenimports,
    hookspath=[],
    hooksconfig={},
    runtime_hooks=RUNTIME_HOOKS,
    # 配置编辑已合并进主程序的 PySide6 / QML 设置页；旧版 PyQt5 编辑器不再打包。
    excludes=['PyQt5', 'qfluentwidgets'],
    noarchive=False,
    optimize=0,
)
pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name=EXE_NAME,
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    console=CONSOLE,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
)
coll = COLLECT(
    exe,
    a.binaries,
    a.datas,
    strip=False,
    upx=False,
    upx_exclude=[],
    name=COLLECT_NAME,
)

# A real .app bundle is only meaningful on macOS release builds.  Debug builds
# (console=True) stay a plain onedir folder launched from Terminal, and Windows
# never produces a .app.
if MAKE_APP_BUNDLE:
    from PyInstaller.building.osx import BUNDLE

    app = BUNDLE(
        coll,
        name=APP_NAME,
        icon=None,
        bundle_identifier='com.jason195421.unipusai-helper',
        version=APP_VERSION,
        info_plist={
            'NSHighResolutionCapable': True,
            'LSMinimumSystemVersion': '11.0',
            'CFBundleDisplayName': 'UnipusAI-Helper',
            'CFBundleName': 'UnipusAI-Helper',
        },
    )
