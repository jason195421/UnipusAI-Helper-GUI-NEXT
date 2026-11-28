import gc
import tempfile
import os
import traceback
import urllib.error
import urllib.request
from typing import Dict, Optional


class AudioTranscriber:

    def __init__(self):
        self.local_model = None
        self._transcript_cache: Dict[str, str] = {}

    @staticmethod
    def _print_exception(prefix: str, exc: Exception) -> None:
        print(f"{prefix}: {exc}")
        tb = traceback.format_exc().rstrip()
        if tb and tb != "NoneType: None":
            print(tb)

    def _init_local_model(self):
        """初始化本地 Whisper 模型（进到需要转写的页面才导入，省启动开销）。

        优先加载随安装包内置的 whisper_models/base.pt（零联网）；仅在内置
        文件缺失时才回退到 whisper 默认的联网下载 / 用户缓存行为。
        """
        if os.environ.get("UNIPUS_ANDROID") == "1":
            try:
                from .android_audio import AndroidWhisperModel
                self.local_model = AndroidWhisperModel()
                print("       安卓本地 Whisper 模型已就绪")
                return
            except Exception as exc:
                self._print_exception("       安卓本地语音识别不可用", exc)
                raise
        try:
            import whisper
            from . import whisper_runtime
        except ImportError:
            print("       未安装 whisper，请运行: pip install openai-whisper")
            raise
        try:
            bundled = whisper_runtime.bundled_whisper_dir()
            if bundled is not None:
                print("       加载内置 Whisper 模型 (base)...")
                self.local_model = whisper.load_model(
                    "base", download_root=str(bundled))
            else:
                print("       加载 Whisper 本地模型 (base)...")
                # 可选: tiny, base, small, medium, large
                # base 是速度与准确率的平衡选择
                self.local_model = whisper.load_model("base")
            print("       本地模型加载完成")
        except Exception as e:
            self._print_exception("       加载本地模型失败", e)
            raise

    def _ensure_local_model(self) -> bool:
        if self.local_model is not None:
            return True
        try:
            self._init_local_model()
        except Exception:
            return False
        return self.local_model is not None

    def release_local_model(self) -> None:
        if self.local_model is None:
            return
        try:
            if os.environ.get("UNIPUS_ANDROID") == "1":
                self.local_model.close()
        except Exception as exc:
            self._print_exception("       释放安卓 Whisper 模型失败", exc)
        finally:
            self.local_model = None
        gc.collect()
        print("       已释放 Whisper 模型")

    def transcribe(self, audio_url: str, language: str = "en") -> str:
        if audio_url in self._transcript_cache:
            print("       使用缓存的识别结果")
            return self._transcript_cache[audio_url]

        audio_path = None

        try:
            if not self._ensure_local_model():
                return ""
            print("      【下载】获取音频...")
            with urllib.request.urlopen(audio_url, timeout=30) as response, \
                    tempfile.NamedTemporaryFile(suffix=".mp3", delete=False) as f:
                f.write(response.read())
                audio_path = f.name

            print("        开始识别...")
            text = self._transcribe_local(audio_path, language)

            if text:
                self._transcript_cache[audio_url] = text
                print(f"       识别成功 ({len(text)} 字符)")
                print(f"       音频识别结果{text}")

            return text or ""

        except urllib.error.URLError as e:
            self._print_exception("       下载音频失败", e)
            return ""
        except Exception as e:
            self._print_exception("       识别失败", e)
            return ""
        finally:
            if audio_path:
                try:
                    os.unlink(audio_path)
                except OSError:
                    pass

    def _transcribe_local(self, audio_path: str, language: str) -> Optional[str]:
        if self.local_model is None:
            print("       本地模型未加载")
            return None

        result = self.local_model.transcribe(
            audio_path,
            language=language,
            fp16=False
        )

        return result["text"].strip() if result else None
