from pathlib import Path
import os
import sys

PACKAGE_ROOT = Path(__file__).resolve().parent


def _project_root() -> Path:
    """Return the directory that holds user-writable files (config.json, logs).

    A frozen Windows build keeps them next to the exe.  A macOS .app bundle is
    read-only (code signing / Gatekeeper translocation), so its user data goes
    to the standard Application Support location instead.  QML_DIR and the
    bundled FFmpeg live inside the bundle and are deliberately NOT affected.
    """
    override = (os.environ.get("UNIPUS_DATA_DIR") or "").strip()
    if override:
        root = Path(override).expanduser()
        if not root.is_absolute():
            raise ValueError("UNIPUS_DATA_DIR must be an absolute app-private directory")
        return root.resolve()
    if not getattr(sys, "frozen", False):
        return PACKAGE_ROOT.parent
    exe = Path(sys.executable).resolve()
    if sys.platform == "darwin":
        for parent in exe.parents:
            if parent.name == "Contents":
                return Path.home() / "Library" / "Application Support" / "UnipusAI-Helper"
    return exe.parent


PROJECT_ROOT = _project_root()
QML_DIR = PACKAGE_ROOT / "qml"
