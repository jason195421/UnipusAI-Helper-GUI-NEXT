"""Stage the standalone Android app and build an APK with isolated tooling.

Normal CI: python scripts/build_android.py --gradle gradle
First Windows build: python scripts/build_android.py --bootstrap
The bootstrap keeps all downloaded SDK/JDK/Gradle files under .tools/.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android"
TOOLS = ROOT / ".tools" / "android-toolchain"
GRADLE_VERSION = "8.13"


def request(url):
    return urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "UnipusHelper-AndroidBuild"}), timeout=60)


def download(url, destination, digest, algorithm="sha256"):
    destination.parent.mkdir(parents=True, exist_ok=True)
    def checksum(path):
        hasher = hashlib.new(algorithm)
        with path.open("rb") as handle:
            for chunk in iter(lambda: handle.read(1024 * 1024), b""):
                hasher.update(chunk)
        return hasher.hexdigest()
    if destination.is_file() and checksum(destination) == digest.lower():
        return destination
    partial = destination.with_suffix(destination.suffix + ".part")
    print(f"[android] Downloading {destination.name}", flush=True)
    try:
        with request(url) as response, partial.open("wb") as handle:
            shutil.copyfileobj(response, handle, length=1024 * 1024)
        if checksum(partial) != digest.lower():
            raise RuntimeError(f"Checksum mismatch: {destination.name}")
        partial.replace(destination)
    finally:
        partial.unlink(missing_ok=True)
    return destination


def extract(archive, destination):
    destination.mkdir(parents=True, exist_ok=True)
    base = destination.resolve()
    with zipfile.ZipFile(archive) as source:
        for item in source.infolist():
            target = (destination / item.filename).resolve()
            if not target.is_relative_to(base) or (item.external_attr >> 16) & 0o170000 == 0o120000:
                raise ValueError("Unsafe archive member")
        source.extractall(destination)


def bootstrap_java():
    if platform.system() != "Windows":
        java_home = os.environ.get("JAVA_HOME")
        if not java_home:
            raise RuntimeError("Install JDK 17 and set JAVA_HOME before building on this host.")
        return Path(java_home)
    folder = TOOLS / "jdk17"
    installed = list(folder.glob("*/bin/java.exe")) if folder.exists() else []
    if installed:
        return installed[0].parent.parent
    url = "https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse"
    with request(url) as response:
        package = json.load(response)[0]["binary"]["package"]
    archive = download(package["link"], TOOLS / "downloads/jdk17.zip", package["checksum"])
    extract(archive, folder)
    return next(folder.glob("*/bin/java.exe")).parent.parent


def bootstrap_sdk():
    sdk = TOOLS / "sdk"
    executable = "sdkmanager.bat" if os.name == "nt" else "sdkmanager"
    manager = sdk / "cmdline-tools/latest/bin" / executable
    if not manager.is_file():
        with request("https://dl.google.com/android/repository/repository2-1.xml") as response:
            repository = ET.fromstring(response.read())
        package = next(item for item in repository.iter() if item.tag.endswith("remotePackage") and item.get("path") == "cmdline-tools;latest")
        host = {"Windows": "windows", "Linux": "linux", "Darwin": "macosx"}[platform.system()]
        selected = None
        for archive in package.iter():
            if not archive.tag.endswith("archive"):
                continue
            values = {item.tag.split("}")[-1]: item.text for item in archive.iter()}
            if values.get("host-os") == host:
                selected = values
                break
        if selected is None:
            raise RuntimeError("Android command-line tools are unavailable for this host.")
        archive = download("https://dl.google.com/android/repository/" + selected["url"], TOOLS / "downloads/sdk-tools.zip", selected["checksum"], "sha1")
        staging = TOOLS / "sdk-extract"
        extract(archive, staging)
        manager.parent.parent.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(str(staging / "cmdline-tools"), str(sdk / "cmdline-tools/latest"))
    if os.name != "nt":
        manager.chmod(manager.stat().st_mode | 0o111)
    return sdk, manager


def bootstrap_gradle():
    executable = "gradle.bat" if os.name == "nt" else "gradle"
    path = TOOLS / f"gradle-{GRADLE_VERSION}/bin" / executable
    if not path.is_file():
        url = f"https://services.gradle.org/distributions/gradle-{GRADLE_VERSION}-bin.zip"
        with request(url + ".sha256") as response:
            checksum = response.read().decode("ascii").strip()
        archive = download(url, TOOLS / f"downloads/gradle-{GRADLE_VERSION}.zip", checksum)
        extract(archive, TOOLS)
    if os.name != "nt":
        path.chmod(path.stat().st_mode | 0o111)
    return path


def install_sdk_packages(sdk, manager, environment):
    """Support both the legacy sdkmanager and the current Android CLI."""
    cli = manager.with_name("android.exe" if os.name == "nt" else "android")
    if cli.is_file():
        for package in ("platform-tools", "platforms/android-35", "build-tools/35.0.0", "ndk/28.2.13676358", "cmake/3.22.1"):
            subprocess.run(
                [str(cli), f"--sdk={sdk}", "sdk", "install", package],
                input="y\n" * 100, text=True, env=environment, check=True,
            )
    else:
        subprocess.run([str(manager), f"--sdk_root={sdk}", "--licenses"], input="y\n" * 100, text=True, env=environment, check=True)
        subprocess.run([str(manager), f"--sdk_root={sdk}", "platform-tools", "platforms;android-35", "build-tools;35.0.0", "ndk;28.2.13676358", "cmake;3.22.1"], env=environment, check=True)
    aapt = "aapt2.exe" if os.name == "nt" else "aapt2"
    for required in (sdk / "platforms/android-35/android.jar", sdk / "build-tools/35.0.0" / aapt):
        if not required.is_file():
            raise RuntimeError(f"Android SDK install did not produce {required}")


def stage_python():
    destination = ANDROID / "app/build/python-src"
    if not destination.resolve().is_relative_to((ANDROID / "app/build").resolve()):
        raise ValueError("Android staging directory escaped the build directory.")
    if destination.exists():
        shutil.rmtree(destination)
    destination.mkdir(parents=True)
    source_files = [ROOT / "UnipusAI_Helper.py", *(ROOT / "unipus_helper").glob("*.py")]
    for source in source_files:
        target = destination / source.relative_to(ROOT)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
    print(f"[android] Staged {len(source_files)} Python source files (no config, keys, models or desktop binaries).", flush=True)
    return source_files


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prepare-only", action="store_true")
    parser.add_argument("--bootstrap", action="store_true")
    parser.add_argument("--setup-only", action="store_true", help="Only provision the isolated build toolchain")
    parser.add_argument("--gradle", default="gradle")
    parser.add_argument("--test", action="store_true", help="Compile Android instrumentation tests too")
    args = parser.parse_args()
    if args.prepare_only:
        stage_python()
        return 0
    environment = dict(os.environ)
    gradle = args.gradle
    if args.bootstrap:
        java = bootstrap_java()
        environment["JAVA_HOME"] = str(java)
        environment["PATH"] = str(java / "bin") + os.pathsep + environment.get("PATH", "")
        sdk, manager = bootstrap_sdk()
        environment["ANDROID_HOME"] = environment["ANDROID_SDK_ROOT"] = str(sdk)
        environment["ANDROID_USER_HOME"] = str(TOOLS / "android-home")
        environment["GRADLE_USER_HOME"] = str(TOOLS / "gradle-home")
        install_sdk_packages(sdk, manager, environment)
        gradle = str(bootstrap_gradle())
    if args.setup_only:
        print("[android] Isolated toolchain is ready.", flush=True)
        return 0
    stage_python()
    command = [gradle, "-p", str(ANDROID), "--no-daemon", f"-PpythonExecutable={sys.executable}", "assembleDebug"]
    if args.test:
        command.append("assembleDebugAndroidTest")
    print("[android] Building standalone Android APK", flush=True)
    subprocess.run(command, env=environment, check=True)
    outputs = list((ANDROID / "app/build/outputs/apk/debug").glob("*.apk"))
    if not outputs:
        raise RuntimeError("Gradle completed without producing an APK.")
    for output in outputs:
        print(f"[android] APK: {output}", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
