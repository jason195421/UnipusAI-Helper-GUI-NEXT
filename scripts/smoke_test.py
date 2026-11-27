#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Headless smoke test for a built UnipusAI-Helper variant.

Launches the exe with QT_QPA_PLATFORM=offscreen for a short while. A healthy
build keeps running (the QML setup page enters app.exec()); a broken one exits
immediately with a traceback. Works for both console (debug) and windowed/GUI
(release) builds: in GUI mode stdio goes to logs/, which we scan for markers.
"""
from __future__ import annotations

import argparse
import os
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
FATAL = ("Traceback (most recent call last)", "ModuleNotFoundError",
         "ReferenceError", "QML_LOAD_FAIL", "Fatal Python error")


def configure_console():
    # PYTHONUTF8 in the child's environment does not change this process's
    # Windows console encoding. Diagnostics may contain Chinese or U+FFFD.
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8", errors="backslashreplace")


def main():
    configure_console()
    ap = argparse.ArgumentParser()
    ap.add_argument("--mode", choices=["debug", "release"], required=True)
    ap.add_argument("--seconds", type=float, default=12.0)
    args = ap.parse_args()

    name = "UnipusAI-Helper" + ("-Debug" if args.mode == "debug" else "")
    if sys.platform == "darwin" and args.mode == "release":
        exe = ROOT / "dist" / "UnipusAI-Helper.app" / "Contents" / "MacOS" / "UnipusAI-Helper"
        logdir = Path.home() / "Library" / "Application Support" / "UnipusAI-Helper" / "logs"
    else:
        exe = ROOT / "dist" / name / (name + (".exe" if sys.platform == "win32" else ""))
        logdir = ROOT / "dist" / name / "logs"
    if not exe.is_file():
        raise SystemExit("[smoke] ERROR: exe not found: " + str(exe))

    env = os.environ.copy()
    env["QT_QPA_PLATFORM"] = "offscreen"
    env["SE_OFFLINE"] = "true"
    env["UNIPUS_GUI_NO_DIALOG"] = "1"
    env["PYTHONUTF8"] = "1"
    env["PYTHONIOENCODING"] = "utf-8:backslashreplace"

    if logdir.is_dir():
        for p in logdir.glob("*.log"):
            try:
                p.unlink()
            except OSError:
                pass
    logdir.mkdir(parents=True, exist_ok=True)

    print("[smoke] launching " + str(exe), flush=True)
    proc = subprocess.Popen([str(exe), "--skip-check"], cwd=str(exe.parent),
                            env=env, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT)
    try:
        out = proc.communicate(timeout=args.seconds)[0]
        early = True
    except subprocess.TimeoutExpired:
        early = False
        proc.terminate()
        try:
            out = proc.communicate(timeout=10)[0]
        except subprocess.TimeoutExpired:
            proc.kill()
            out = proc.communicate()[0]

    raw_output = out or b""
    # A crash before the application's logger starts must still be available
    # in the runtime-log artifact, even if CI truncates the console excerpt.
    console_log = logdir / "smoke-console.log"
    report_log = logdir / "smoke-report.log"
    console_log.write_bytes(raw_output)
    text = raw_output.decode("utf-8", "replace")
    if logdir.is_dir():
        for p in sorted(logdir.glob("*.log")):
            if p in (console_log, report_log):
                continue
            try:
                text += "\n--- " + p.name + " ---\n"
                text += p.read_text(encoding="utf-8", errors="replace")
            except OSError:
                pass

    process_state = "exited early" if early else "stopped after smoke timeout"
    report_log.write_text(
        "[smoke] " + process_state + "; returncode=" + str(proc.returncode) + "\n" + text,
        encoding="utf-8", errors="backslashreplace",
    )
    print("[smoke] full diagnostics: " + str(report_log), flush=True)

    for marker in FATAL:
        if marker in text:
            print("[smoke] FAIL marker: " + marker, flush=True)
            print("[smoke] returncode=" + str(proc.returncode), flush=True)
            print(text[-4000:], flush=True)
            return 1
    if early:
        print("[smoke] FAIL: process exited too early (rc=%s)" % proc.returncode, flush=True)
        print(text[-4000:], flush=True)
        return 1
    print("[smoke] OK: survived %.0fs headless, no fatal markers" % args.seconds, flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
