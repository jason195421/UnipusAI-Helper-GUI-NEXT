"""Prepare pinned Android speech dependencies; never copy user configuration.

FFmpeg shared libraries are the LGPL JavaCPP Presets distribution. Whisper is
compiled from upstream source by the Android NDK. No desktop executables run on
the phone. Downloaded archives stay in .tools; generated inputs stay in build/.
"""
from __future__ import annotations

import ast
import hashlib
from pathlib import Path
import shutil
import tarfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / '.tools/android-audio'
DEST = ROOT / 'android/app/build/native-deps'
ASSETS = ROOT / 'android/app/build/generated/audio-assets'
MAVEN = 'https://repo.maven.apache.org/maven2/org/bytedeco/ffmpeg/8.1.2-1.5.14/'
DEPENDENCIES = (
    ('ffmpeg-android-arm64.jar', MAVEN + 'ffmpeg-8.1.2-1.5.14-android-arm64.jar',
     '763bb56cd8a72140053312e73c1b30edc952d1d884a580fcc8f6eca18e838667'),
    ('ffmpeg-android-x86_64.jar', MAVEN + 'ffmpeg-8.1.2-1.5.14-android-x86_64.jar',
     '6194a88faaa4c00d7c86cdfc93e45d81745ffb7072dc1d7879b7d49119611d10'),
    ('whisper-v1.9.6.tar.gz', 'https://codeload.github.com/ggml-org/whisper.cpp/tar.gz/refs/tags/v1.9.6',
     '7ab15dc88022d2425ad94dcffd8abbf1b6d7018089b4e48d6b40780738e0b445'),
    ('ffmpeg-8.1.2.tar.xz', 'https://ffmpeg.org/releases/ffmpeg-8.1.2.tar.xz',
     '464beb5e7bf0c311e68b45ae2f04e9cc2af88851abb4082231742a74d97b524c'),
)


def checksum(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def fetch(name, url, digest):
    path = CACHE / name
    CACHE.mkdir(parents=True, exist_ok=True)
    if path.is_file() and checksum(path) == digest:
        return path
    partial = path.with_suffix(path.suffix + '.part')
    print(f'[audio] Downloading {name}', flush=True)
    try:
        request = urllib.request.Request(url, headers={'User-Agent': 'UnipusHelper-AndroidBuild'})
        with urllib.request.urlopen(request, timeout=90) as source, partial.open('wb') as target:
            shutil.copyfileobj(source, target, 1024 * 1024)
        if checksum(partial) != digest:
            raise RuntimeError(f'SHA256 mismatch: {name}')
        partial.replace(path)
    finally:
        partial.unlink(missing_ok=True)
    return path


def write(path, data):
    raw = data.encode('utf-8') if isinstance(data, str) else data
    if not path.is_file() or path.read_bytes() != raw:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(raw)


def unpack(archive, destination, digest):
    marker = destination / '.source-sha256'
    if marker.is_file() and marker.read_text() == digest:
        return
    destination.mkdir(parents=True, exist_ok=True)
    with tarfile.open(archive) as source:
        for member in source.getmembers():
            parts = Path(member.name).parts[1:]
            if not parts or member.isdir():
                continue
            if not member.isfile() or '..' in parts:
                if member.issym() or member.islnk():
                    continue
                raise ValueError(f'Unsafe source archive member: {member.name}')
            target = destination.joinpath(*parts)
            if not target.resolve().is_relative_to(destination.resolve()):
                raise ValueError('Source archive escaped build directory')
            target.parent.mkdir(parents=True, exist_ok=True)
            with source.extractfile(member) as reader, target.open('wb') as writer:
                shutil.copyfileobj(reader, writer)
    write(marker, digest)


def main():
    archives = {name: fetch(name, url, sha) for name, url, sha in DEPENDENCIES}
    for name, abi in (('ffmpeg-android-arm64.jar', 'arm64-v8a'), ('ffmpeg-android-x86_64.jar', 'x86_64')):
        with zipfile.ZipFile(archives[name]) as source:
            for library in ('avutil', 'swresample', 'avcodec', 'avformat'):
                file = f'lib{library}.so'
                write(DEST / 'lib' / abi / file, source.read(f'lib/{abi}/{file}'))
    unpack(archives['whisper-v1.9.6.tar.gz'], DEST / 'whisper', DEPENDENCIES[2][2])
    unpack(archives['ffmpeg-8.1.2.tar.xz'], DEST / 'ffmpeg', DEPENDENCIES[3][2])
    # Both supported ABIs are little-endian and support unaligned loads. These
    # are FFmpeg's two generated public-header build properties, not codec code.
    write(DEST / 'ffmpeg/libavutil/avconfig.h', '#ifndef AVUTIL_AVCONFIG_H\n#define AVUTIL_AVCONFIG_H\n#define AV_HAVE_BIGENDIAN 0\n#define AV_HAVE_FAST_UNALIGNED 1\n#endif\n')
    write(DEST / 'ffmpeg/libavutil/ffversion.h', '#define FFMPEG_VERSION "8.1.2"\n')
    # Do not put FFmpeg's source root on the C++ include path: its plain-text
    # "version" file shadows the standard library's <version> header.
    for directory in ('libavutil', 'libavcodec', 'libavformat', 'libswresample'):
        for header in (DEST / 'ffmpeg' / directory).rglob('*.h'):
            write(DEST / 'ffmpeg-include' / header.relative_to(DEST / 'ffmpeg'), header.read_bytes())
    for file in ('COPYING.LGPLv2.1', 'COPYING.LGPLv3'):
        write(ASSETS / 'native-licenses' / file, (DEST / 'ffmpeg' / file).read_bytes())
    write(ASSETS / 'native-licenses/whisper-LICENSE', (DEST / 'whisper/LICENSE').read_bytes())
    write(ROOT / 'android/app/build/generated/audio-test-assets/speech-test/jfk.wav',
          (DEST / 'whisper/samples/jfk.wav').read_bytes())
    write(ASSETS / 'native-licenses/SOURCES.txt',
          'Whisper.cpp v1.9.6: https://github.com/ggml-org/whisper.cpp/tree/v1.9.6\n'
          'FFmpeg 8.1.2 LGPL libraries: org.bytedeco:ffmpeg:8.1.2-1.5.14 (non-GPL classifiers)\n'
          'Build recipe: https://github.com/bytedeco/javacpp-presets/tree/1.5.14/ffmpeg\n'
          'FFmpeg source: https://ffmpeg.org/releases/ffmpeg-8.1.2.tar.xz\n'
          'Exact SHA256 pins and integration source: scripts/prepare_android_audio.py, android/app/src/main/cpp\n')
    tree = ast.parse((ROOT / 'UnipusAI_Helper.py').read_text(encoding='utf-8-sig'))
    constants = {}
    for node in tree.body:
        if isinstance(node, ast.Assign):
            for target in node.targets:
                if isinstance(target, ast.Name) and target.id in ('RECORD_MIC_BOOTSTRAP_JS', 'RECORD_MIC_JS'):
                    constants[target.id] = ast.literal_eval(node.value)
    for name, file in (('RECORD_MIC_BOOTSTRAP_JS', 'record_mic_bootstrap.js'), ('RECORD_MIC_JS', 'record_mic.js')):
        write(ASSETS / file, constants[name])
    print('[audio] FFmpeg libraries, Whisper source and recording fixtures are ready.', flush=True)


if __name__ == '__main__':
    main()
