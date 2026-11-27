#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Build one UnipusAI-Helper variant (debug | release) and package an installer.

The build mode is forwarded to UnipusAI-Helper.spec through UNIPUS_BUILD_MODE:

  debug    console=True  -> terminal visible (Windows) / Terminal logs (macOS)
           name suffix "-Debug", no macOS .app bundle
  release  console=False -> pure GUI / windowed, no terminal; macOS .app bundle

Installer tool-chain:
  Windows  Inno Setup 6 ISCC.exe. Discovery order:
           --iscc / UNIPUS_ISCC / ISCC on PATH / common Program Files paths /
           .tools/inno6/ISCC.exe (portable).
  macOS    hdiutil (available on all GitHub-hosted macOS runners).

Usage:
  python scripts/build_variant.py --mode release
  python scripts/build_variant.py --mode debug --skip-installer
  python scripts/build_variant.py --mode release --iscc C:/path/ISCC.exe
"""
from __future__ import annotations

import argparse
import importlib.machinery
import os
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
APP_VERSION = "3.9.19"
DIST = ROOT / "dist"
INSTALLER_DIR = ROOT / "installer"
ISS = INSTALLER_DIR / "UnipusAI-Helper.iss"

APP_ID_RELEASE = "B7E4A1C2-3D5F-4A68-9B01-2C3D4E5F6A70"
APP_ID_DEBUG = "C8F5B2D3-4E60-4B79-AC12-3D4E5F6A7B81"


def log(msg):
    print("[build] " + str(msg), flush=True)


def run(cmd, *, env=None, check=True):
    log("$ " + " ".join(str(c) for c in cmd))
    return subprocess.run([str(c) for c in cmd], cwd=str(ROOT), env=env, check=check)


def suffix(mode):
    return "-Debug" if mode == "debug" else ""


def folder_name(mode):
    return "UnipusAI-Helper" + suffix(mode)


def exe_filename(mode):
    return folder_name(mode) + (".exe" if sys.platform == "win32" else "")


def payload_path(mode):
    if sys.platform == "darwin" and mode == "release":
        return DIST / "UnipusAI-Helper.app"
    return DIST / folder_name(mode)


def is_ondir(payload):
    return payload.is_dir() and not payload.name.endswith(".app")


def build_pyinstaller(mode):
    env = os.environ.copy()
    env["UNIPUS_BUILD_MODE"] = mode
    env.setdefault("PYTHONUTF8", "1")
    run([sys.executable, "-m", "PyInstaller", "--noconfirm", "--clean",
         "UnipusAI-Helper.spec"], env=env)


def build_native_extension():
    extension_suffixes = importlib.machinery.EXTENSION_SUFFIXES
    existing_extensions = [
        artifact for artifact in (ROOT / "unipus_helper").glob("_textmatch*")
        if any(artifact.name.endswith(suffix) for suffix in extension_suffixes)
    ]
    try:
        run([sys.executable, "setup_native.py", "build_ext", "--inplace"])
    except (OSError, subprocess.CalledProcessError) as exc:
        if existing_extensions:
            raise SystemExit(
                "[build] ERROR: C++ matcher compilation failed while an existing native "
                "extension is present; remove or rebuild it before packaging."
            ) from exc
        log("WARNING: C++ matcher unavailable; using Python fallback (%s)" % exc)
        return False
    run([sys.executable, "scripts/verify_textmatch.py", "--require-native"])
    return True


def assemble_payload(mode):
    payload = payload_path(mode)
    if not payload.exists():
        raise SystemExit("[build] ERROR: PyInstaller output not found: " + str(payload))
    # Only Windows keeps config.json / logs next to the exe. On macOS paths.py
    # stores them under ~/Library/Application Support/UnipusAI-Helper.
    if sys.platform == "win32" and is_ondir(payload):
        cfg = payload / "config.json"
        if not cfg.exists():
            shutil.copy2(ROOT / "config.example.json", cfg)
            log("seeded " + str(cfg))
        notes = ROOT / "docs" / "发布说明_Windows.txt"
        if notes.exists():
            shutil.copy2(notes, payload / "_发布说明_READ_ME_FIRST.txt")
    return payload


def verify_payload(mode, payload):
    """Cheap structural checks that catch a broken COLLECT/BUNDLE early."""
    problems = []
    if sys.platform == "darwin" and payload.name.endswith(".app"):
        if not (payload / "Contents" / "MacOS").is_dir():
            problems.append("malformed .app bundle: " + str(payload))
    else:
        exe = payload / exe_filename(mode)
        if not exe.is_file() and not list(payload.rglob(exe_filename(mode))):
            problems.append("launcher not found: " + exe_filename(mode))
        if exe.is_file() and sys.platform == "win32":
            with open(exe, "rb") as fh:
                magic = fh.read(2)
            if magic != b"MZ":
                problems.append("not a PE/MZ binary: " + str(exe))
        if not list(payload.rglob("SettingsPage.qml")):
            problems.append("missing bundled QML SettingsPage.qml")
        ff = "ffmpeg.exe" if sys.platform == "win32" else "ffmpeg"
        if not list(payload.rglob(ff)):
            problems.append("missing bundled " + ff)
        if sys.platform == "win32" and not (payload / "config.json").is_file():
            problems.append("missing config.json template")
    # 内置 Whisper 权重必须随 payload 发布（首次语音识别零联网）。
    if not list(payload.rglob("whisper_models/base.pt")):
        problems.append("missing bundled whisper_models/base.pt")
    if problems:
        for p in problems:
            log("VERIFY FAIL: " + p)
        raise SystemExit("[build] payload verification failed")
    log("payload verification OK")


def find_iscc(explicit=None):
    candidates = []
    if explicit:
        candidates.append(Path(explicit))
    if os.environ.get("UNIPUS_ISCC"):
        candidates.append(Path(os.environ["UNIPUS_ISCC"]))
    found = shutil.which("ISCC") or shutil.which("ISCC.exe")
    if found:
        candidates.append(Path(found))
    for key in ("ProgramFiles(x86)", "ProgramFiles"):
        base = os.environ.get(key)
        if base:
            for ver in ("Inno Setup 6", "Inno Setup 7", "Inno Setup 5"):
                candidates.append(Path(base) / ver / "ISCC.exe")
    candidates.append(Path(r"C:\Program Files (x86)") / "Inno Setup 6" / "ISCC.exe")
    candidates.append(ROOT / ".tools" / "inno6" / "ISCC.exe")
    for c in candidates:
        if c.is_file():
            log("ISCC: " + str(c))
            return c
    return None


def windows_installer(mode, payload, iscc):
    INSTALLER_DIR.mkdir(parents=True, exist_ok=True)
    base = "UnipusAI-Helper-Setup-%s-win-x64-%s" % (APP_VERSION, mode)
    defines = [
        "/DAppVersion=" + APP_VERSION,
        "/DBuildMode=" + mode,
        "/DMyAppExe=" + exe_filename(mode),
        "/DAppDirName=" + folder_name(mode),
        "/DAppId=" + (APP_ID_DEBUG if mode == "debug" else APP_ID_RELEASE),
        "/DSourceDir=" + str(payload),
        "/DSetupBaseName=" + base,
        "/DOutputDir=" + str(INSTALLER_DIR),
    ]
    run([str(iscc), str(ISS)] + defines)
    setup = INSTALLER_DIR / (base + ".exe")
    if not setup.is_file():
        raise SystemExit("[build] ERROR: installer not produced: " + str(setup))
    log("installer: " + str(setup) + " (" + str(setup.stat().st_size) + " bytes)")
    return setup


def portable_zip(mode, payload):
    INSTALLER_DIR.mkdir(parents=True, exist_ok=True)
    out = INSTALLER_DIR / ("UnipusAI-Helper-%s-win-x64-%s.zip" % (APP_VERSION, mode))
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as zf:
        for p in sorted(payload.rglob("*")):
            if p.is_file():
                zf.write(p, p.relative_to(payload.parent))
    log("portable zip: " + str(out))
    return out


def mac_arch():
    import platform
    return "x64" if platform.machine() == "x86_64" else "arm64"


def macos_dmg(mode, payload):
    if mode == "release":
        # Ad-hoc sign before the DMG is created. Non-fatal: unsigned bundles
        # still run after the user clears the quarantine flag.
        run(["codesign", "--force", "--deep", "--sign", "-", str(payload)],
            check=False)
    stage = DIST / ("dmg-stage-" + mode)
    if stage.exists():
        shutil.rmtree(stage)
    stage.mkdir(parents=True)
    shutil.copytree(payload, stage / payload.name, symlinks=True)
    if mode == "release":
        try:
            (stage / "Applications").symlink_to(Path("/Applications"))
        except OSError:
            pass
        readme = "README-macOS.txt"
    else:
        launcher = stage / "Run-Debug.command"
        launcher.write_text(
            "#!/bin/bash\n"
            'cd "$(dirname "$0")/' + payload.name + '"\n'
            "exec ./" + exe_filename(mode) + ' "$@"\n',
            encoding="utf-8")
        launcher.chmod(0o755)
        readme = "README-macOS-Debug.txt"
    shutil.copy2(ROOT / "README.md", stage / readme)
    INSTALLER_DIR.mkdir(parents=True, exist_ok=True)
    out = INSTALLER_DIR / ("UnipusAI-Helper-macOS-%s-%s.dmg" % (mac_arch(), mode))
    if out.exists():
        out.unlink()
    run(["hdiutil", "create", "-volname", "UnipusAI-Helper " + mode,
         "-srcfolder", str(stage), "-ov", "-format", "UDZO", str(out)])
    log("DMG: " + str(out))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mode", choices=["debug", "release"], required=True)
    ap.add_argument("--iscc", default=None)
    ap.add_argument("--skip-installer", action="store_true")
    ap.add_argument("--package-target", choices=["all", "portable", "installer", "none"],
                    default="all", help="select which Windows package to produce")
    ap.add_argument("--installer-only", action="store_true",
                    help="reuse an existing dist/ payload; skip PyInstaller, only package")
    args = ap.parse_args()
    package_target = "none" if args.skip_installer else args.package_target

    if args.mode == "debug":
        log("DEBUG build: console/terminal kept, name suffix -Debug")
    else:
        log("RELEASE build: windowed GUI, no terminal")

    if not args.installer_only:
        build_native_extension()
        build_pyinstaller(args.mode)
    payload = assemble_payload(args.mode)
    verify_payload(args.mode, payload)

    outputs = []
    if package_target != "none":
        if sys.platform == "win32":
            if package_target in ("all", "installer"):
                iscc = find_iscc(args.iscc)
                if iscc is None:
                    log("WARNING: ISCC.exe not found - skipping the setup installer. "
                        "Install Inno Setup 6 or pass --iscc / UNIPUS_ISCC.")
                else:
                    outputs.append(windows_installer(args.mode, payload, iscc))
            if package_target in ("all", "portable"):
                outputs.append(portable_zip(args.mode, payload))
        elif sys.platform == "darwin":
            if package_target in ("all", "installer"):
                outputs.append(macos_dmg(args.mode, payload))
            elif package_target == "portable":
                raise SystemExit("[build] ERROR: portable packages are Windows-only")
        else:
            log("WARNING: no installer toolchain here; payload left in dist/.")

    log("payload: " + str(payload))
    for o in outputs:
        log("output: " + str(o))
    log("DONE (" + args.mode + ")")
    return 0


if __name__ == "__main__":
    sys.exit(main())
