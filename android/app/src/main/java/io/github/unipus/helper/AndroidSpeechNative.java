package io.github.unipus.helper;

import android.content.Context;
import android.os.Looper;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/** Native-only, local FFmpeg/whisper.cpp operations; never exposed to page JS. */
public final class AndroidSpeechNative {
    private static final String MODEL_NAME = "tiny.en";
    private static final String MODEL_FILE = "ggml-tiny.en.bin";
    private static final long MODEL_BYTES = 77704715L;
    private static final String MODEL_SHA256 = "921e4cf8686fdd993dcd081a5da5b6c365bfde1162e72b08d75ac75289920b1f";
    private static final String[] MODEL_URLS = {
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en.bin",
            "https://hf-mirror.com/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en.bin",
    };
    private static final String[] MODEL_SOURCES = {"official", "mirror"};
    private static final long MAX_INPUT_BYTES = 256L * 1024 * 1024;
    private static final long TRANSCRIBE_TIMEOUT = 30L * 60 * 1000;
    private static final long DOWNLOAD_TIMEOUT = 20L * 60 * 1000;
    private static final Object LOAD_LOCK = new Object();
    private static final Object CANCEL_LOCK = new Object();
    // JNI owns a process-wide model and cancellation flag, so all instances
    // share the same operation lock during Activity recreation as well.
    private static final ReentrantLock OPERATION = new ReentrantLock();
    private static volatile Work active;
    private static volatile boolean loaded;
    private static volatile String loadError = "";

    private final File files;
    private final File cache;
    private final File modelDirectory;
    private final Object modelLock = new Object();
    private final ScheduledExecutorService watchdog;
    private volatile boolean destroyed;
    private volatile boolean modelReady;
    private volatile String modelState = "unchecked";
    private volatile String modelError = "";
    private volatile String modelSource = "";
    private volatile String modelSourceUrl = "";
    // Publish immutable JSON snapshots so capability reads never share a mutable array.
    private volatile String modelSourceErrors = "[]";
    private volatile double modelProgress;
    private long checkedSize = -1;
    private long checkedModified = -1;

    public AndroidSpeechNative(Context context) {
        Context application = context.getApplicationContext();
        files = application.getFilesDir();
        cache = application.getCacheDir();
        modelDirectory = new File(files, "speech-models");
        watchdog = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "unipus-speech-deadline");
            thread.setDaemon(true);
            return thread;
        });
        watchdog.scheduleAtFixedRate(this::checkDeadline, 100, 100, TimeUnit.MILLISECONDS);
    }

    /** Call only from Python/native workers. No RPC ever starts cloud ASR. */
    public String callJson(String raw) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return error("WebDriverException", "Local speech RPC must run on a worker thread").toString();
        }
        Work work = null;
        try {
            if (raw == null || raw.length() > 16384) throw new SpeechException("InvalidArgumentException", "Invalid local speech request");
            JSONObject request = new JSONObject(raw);
            String op = request.optString("op", "");
            if ("audio_cancel".equals(op)) {
                cancel();
                return success(object("stopped", true)).toString();
            }
            if ("audio_release".equals(op)) {
                release(Math.max(250, Math.min(10000, request.optLong("timeoutMs", 10000))));
                return success(object("released", true, "modelRetained", true)).toString();
            }
            if (destroyed) throw new SpeechException("InvalidSessionIdException", "Local speech engine was closed");
            if ("audio_capabilities".equals(op)) return success(capabilities()).toString();
            if (!("audio_prepare_model".equals(op) || "audio_import_model".equals(op)
                    || "audio_transcribe".equals(op) || "audio_decode".equals(op))) {
                throw new SpeechException("UnsupportedCommandException", "Unknown local speech operation");
            }
            long ceiling = ("audio_prepare_model".equals(op) || "audio_import_model".equals(op))
                    ? DOWNLOAD_TIMEOUT : TRANSCRIBE_TIMEOUT;
            long timeout = Math.max(250, Math.min(ceiling, request.optLong("timeoutMs", ceiling)));
            if (!OPERATION.tryLock()) throw new SpeechException("AudioBusyException", "本地音频引擎正在处理其他操作，请稍后重试。 ");
            work = new Work(this, op, SystemClock.elapsedRealtime() + timeout);
            synchronized (CANCEL_LOCK) { active = work; }
            JSONObject result;
            if ("audio_prepare_model".equals(op)) result = prepareModel(work);
            else if ("audio_import_model".equals(op)) {
                result = importModel(privateFile(request.optString("inputPath", ""), true), work);
            }
            else {
                requireNative();
                File input = privateFile(request.optString("inputPath", ""), true);
                if ("audio_decode".equals(op)) result = decode(input, request, work);
                else result = transcribe(input, request, work);
            }
            checkCancelled(work);
            return success(result).toString();
        } catch (SpeechException failure) {
            return error(failure.name, failure.getMessage()).toString();
        } catch (JSONException | IllegalArgumentException failure) {
            return error("InvalidArgumentException", "Malformed local speech request or result").toString();
        } catch (IOException failure) {
            return error(errorName(work, "AudioIOException"), errorMessage(work, failure)).toString();
        } catch (LinkageError failure) {
            return error("DependencyUnavailableException", "本地音频库加载失败，请安装包含当前设备架构的完整 APK。").toString();
        } catch (RuntimeException failure) {
            return error(errorName(work, "AudioProcessingException"), errorMessage(work, failure)).toString();
        } finally {
            if (work != null) {
                synchronized (CANCEL_LOCK) { if (active == work) active = null; }
                OPERATION.unlock();
            }
        }
    }

    /** Cancellation never waits for the inference/model-operation lock. */
    public void cancel() {
        Work work = active;
        if (work != null && work.owner == this) cancelWork(work, false);
    }

    /** Activity lifecycle hook, safe on the UI thread. */
    public void destroy() {
        if (destroyed) return;
        destroyed = true;
        cancel();
        Thread cleanup = new Thread(() -> {
            try { release(10000); } catch (RuntimeException ignored) { }
            finally { watchdog.shutdownNow(); }
        }, "unipus-speech-release");
        cleanup.setDaemon(true);
        cleanup.start();
    }

    private static boolean loadNative() {
        if (loaded) return true;
        synchronized (LOAD_LOCK) {
            if (loaded) return true;
            try {
                System.loadLibrary("unipusspeech");
                new JSONObject(nativeVersions());
                loaded = true;
                loadError = "";
            } catch (LinkageError | JSONException | RuntimeException failure) {
                loadError = "本地 Whisper/FFmpeg 库不可用，请安装包含当前设备架构的完整 APK。";
            }
            return loaded;
        }
    }

    private static void requireNative() {
        if (!loadNative()) throw new SpeechException("DependencyUnavailableException", loadError);
    }

    private JSONObject capabilities() throws IOException, JSONException {
        boolean available = loadNative();
        verifyModel(null);
        JSONObject result = object("whisperAvailable", available, "ffmpegAvailable", available,
                "modelReady", modelReady, "modelName", MODEL_NAME, "modelBytes", MODEL_BYTES,
                "modelState", modelState, "modelProgress", modelProgress,
                "modelDownloadedBytes", Math.round(modelProgress * MODEL_BYTES),
                "modelSource", modelSource, "modelSourceUrl", modelSourceUrl,
                "modelSourceErrors", new JSONArray(modelSourceErrors),
                "busy", active != null, "error", available ? modelError : loadError);
        if (available) {
            JSONObject versions = new JSONObject(nativeVersions());
            result.put("versions", versions);
        }
        return result;
    }

    private File modelFile() throws IOException {
        return privateFile(new File(modelDirectory, MODEL_FILE).getPath(), false);
    }

    private boolean verifyModel(Work work) throws IOException {
        synchronized (modelLock) {
            File target = modelFile();
            long size = target.isFile() ? target.length() : -1;
            long modified = target.isFile() ? target.lastModified() : -1;
            if (size == checkedSize && modified == checkedModified && !"unchecked".equals(modelState)) return modelReady;
            if (size == -1) {
                checkedSize = checkedModified = -1;
                modelReady = false;
                Work current = active;
                if (current == null || current.owner != this
                        || !("audio_prepare_model".equals(current.op) || "audio_import_model".equals(current.op))) {
                    modelState = "missing";
                    modelProgress = 0;
                    modelError = "尚未下载本地 Whisper 模型，请在语音设置中下载。";
                }
                return false;
            }
            modelReady = false;
            modelState = "verifying";
            modelProgress = 0;
            boolean matches = size == MODEL_BYTES && MODEL_SHA256.equals(hash(target, work));
            checkedSize = size;
            checkedModified = modified;
            modelReady = matches;
            modelState = matches ? "ready" : "invalid";
            modelProgress = matches ? 1 : 0;
            modelError = matches ? "" : "本地 Whisper 模型校验失败，请重新下载模型。";
            if (matches && modelSource.isEmpty()) modelSource = "existing";
            return matches;
        }
    }

    private JSONObject prepareModel(Work work) throws IOException, JSONException {
        requireNative();
        if (verifyModel(work)) return modelResult(modelFile(), true);
        if (!modelDirectory.isDirectory() && !modelDirectory.mkdirs()) throw new IOException("Cannot create the private model directory");
        File target = modelFile();
        File partial = privateFile(new File(modelDirectory, MODEL_FILE + ".partial").getPath(), false);
        JSONArray failures = new JSONArray();
        modelSourceErrors = failures.toString();
        try {
            for (int index = 0; index < MODEL_URLS.length; index++) {
                checkCancelled(work);
                modelSource = MODEL_SOURCES[index];
                modelSourceUrl = MODEL_URLS[index];
                modelState = "downloading";
                modelProgress = 0;
                modelError = "";
                try {
                    downloadModel(MODEL_URLS[index], partial, work);
                    checkCancelled(work);
                    atomicMove(partial, target);
                    markModelReady(target);
                    return modelResult(target, false);
                } catch (IOException | RuntimeException failure) {
                    // A deliberate stop or the shared 20-minute deadline must
                    // never trigger another source or another network request.
                    checkCancelled(work);
                    failures.put(object("source", MODEL_SOURCES[index], "url", MODEL_URLS[index],
                            "error", errorMessage(work, failure)));
                    modelSourceErrors = failures.toString();
                } finally {
                    disconnect(work);
                    if (partial.exists() && !partial.delete()) partial.deleteOnExit();
                }
            }
            throw new IOException("官方源和备用源均下载失败，请检查网络，或在语音设置中导入已下载的 ggml-tiny.en.bin。详情：" + failures);
        } catch (IOException | RuntimeException failure) {
            modelState = work.timedOut ? "timeout" : work.cancelled ? "cancelled" : "error";
            modelError = errorMessage(work, failure);
            modelReady = false;
            throw failure;
        }
    }

    private void downloadModel(String source, File partial, Work work) throws IOException {
        HttpURLConnection connection = openDownload(source, work);
        long declaredLength = connection.getContentLengthLong();
        if (declaredLength > 0 && declaredLength != MODEL_BYTES) {
            throw new SpeechException("ModelValidationException", "模型下载长度不正确，已拒绝该文件。");
        }
        try (InputStream input = connection.getInputStream()) {
            copyVerifiedModel(input, partial, work, false);
        }
    }

    private JSONObject importModel(File input, Work work) throws IOException, JSONException {
        boolean existingReady = verifyModel(work);
        String previousSource = modelSource, previousUrl = modelSourceUrl;
        modelSourceErrors = "[]";
        modelError = "";
        if (!existingReady) {
            modelSource = "local-import";
            modelSourceUrl = "";
        }
        File partial = null;
        try {
            if (input.length() != MODEL_BYTES) {
                throw new SpeechException("ModelValidationException", "模型大小不正确，需要原始 ggml-tiny.en.bin（77704715 字节）。");
            }
            if (!modelDirectory.isDirectory() && !modelDirectory.mkdirs()) throw new IOException("Cannot create the private model directory");
            File target = modelFile();
            // Never reuse or remove a caller-selected source as our staging
            // file, even if it lives in speech-models and ends with .partial.
            partial = File.createTempFile("import-", ".partial", target.getParentFile());
            if (!existingReady) {
                modelState = "importing";
                modelProgress = 0;
                modelSource = "local-import";
                modelSourceUrl = "";
            }
            try (InputStream source = new FileInputStream(input)) {
                copyVerifiedModel(source, partial, work, existingReady);
            }
            checkCancelled(work);
            // Even with an already-installed model, validate the selected
            // input first so a damaged import is never reported as successful.
            if (verifyModel(work)) {
                modelSource = previousSource.isEmpty() ? "existing" : previousSource;
                modelSourceUrl = previousUrl;
                modelError = "";
                return modelResult(target, true);
            }
            atomicMove(partial, target);
            modelSource = "local-import";
            modelSourceUrl = "";
            markModelReady(target);
            return modelResult(target, false);
        } catch (IOException | RuntimeException failure) {
            modelError = errorMessage(work, failure);
            modelSourceErrors = new JSONArray().put(object("source", "local-import", "error", modelError)).toString();
            if (existingReady) {
                modelReady = true;
                modelState = "ready";
                modelProgress = 1;
                modelSource = previousSource;
                modelSourceUrl = previousUrl;
            } else {
                modelReady = false;
                modelState = work.timedOut ? "timeout" : work.cancelled ? "cancelled" : "error";
            }
            throw failure;
        } finally {
            if (partial != null && partial.exists() && !partial.delete()) partial.deleteOnExit();
        }
    }

    private void copyVerifiedModel(InputStream input, File partial, Work work, boolean keepReady) throws IOException {
        MessageDigest digest = digest();
        long received = 0;
        try (FileOutputStream output = new FileOutputStream(partial, false)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                checkCancelled(work);
                received += count;
                if (received > MODEL_BYTES) throw new SpeechException("ModelValidationException", "模型文件超过预期大小，已拒绝该文件。");
                output.write(buffer, 0, count);
                digest.update(buffer, 0, count);
                if (!keepReady) modelProgress = (double) received / MODEL_BYTES;
            }
            output.getFD().sync();
        }
        checkCancelled(work);
        if (!keepReady) modelState = "verifying";
        if (received != MODEL_BYTES || !MODEL_SHA256.equals(hex(digest.digest()))) {
            throw new SpeechException("ModelValidationException", "模型长度或 SHA256 校验失败，请重新下载原始 ggml-tiny.en.bin。");
        }
    }

    private void markModelReady(File target) {
        synchronized (modelLock) {
            checkedSize = target.length();
            checkedModified = target.lastModified();
            modelReady = true;
            modelState = "ready";
            modelProgress = 1;
            modelError = "";
        }
    }

    private JSONObject modelResult(File target, boolean unchanged) throws JSONException {
        return object("modelReady", true, "modelPath", target.getPath(), "unchanged", unchanged,
                "modelSource", modelSource, "modelSourceUrl", modelSourceUrl,
                "modelSourceErrors", new JSONArray(modelSourceErrors));
    }

    private static void disconnect(Work work) {
        HttpURLConnection connection = work.connection;
        if (connection != null) connection.disconnect();
        work.connection = null;
    }

    private HttpURLConnection openDownload(String source, Work work) throws IOException {
        URL url = new URL(source);
        for (int redirect = 0; redirect < 6; redirect++) {
            checkCancelled(work);
            if (!"https".equalsIgnoreCase(url.getProtocol()) || url.getUserInfo() != null) {
                throw new IOException("Whisper model download requires a secure HTTPS source");
            }
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            work.connection = connection;
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("User-Agent", "UnipusAI-Helper-Android/1");
            int code = connection.getResponseCode();
            if (code == 200) return connection;
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null) throw new IOException("Whisper model redirect is missing a destination");
                url = new URL(url, location);
            } else {
                connection.disconnect();
                throw new IOException("Whisper model download failed (HTTP " + code + ")");
            }
        }
        throw new IOException("Whisper model download had too many redirects");
    }

    private JSONObject transcribe(File input, JSONObject request, Work work) throws IOException, JSONException {
        if (!verifyModel(work)) throw new SpeechException("DependencyUnavailableException", modelError);
        String language = request.optString("language", "en").trim().toLowerCase(Locale.ROOT);
        if (!(language.isEmpty() || "auto".equals(language) || "en".equals(language))) {
            throw new SpeechException("InvalidArgumentException", "当前 tiny.en 本地模型只支持英语音频。 ");
        }
        int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 1));
        checkCancelled(work);
        JSONObject result = new JSONObject(nativeTranscribe(input.getPath(), modelFile().getPath(), language, threads));
        if (!(result.opt("text") instanceof String)) throw new SpeechException("AudioProcessingException", "Whisper returned no valid transcript");
        return result;
    }

    private JSONObject decode(File input, JSONObject request, Work work) throws IOException, JSONException {
        File output = privateFile(request.optString("outputPath", ""), false);
        if (input.equals(output)) throw new SpeechException("InvalidArgumentException", "Input and output audio paths must differ");
        File parent = output.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) throw new IOException("Cannot create the private audio output directory");
        output = privateFile(output.getPath(), false);
        File temporary = File.createTempFile("normalized-", ".wav", output.getParentFile());
        try {
            checkCancelled(work);
            long samples = nativeDecode(input.getPath(), temporary.getPath());
            checkCancelled(work);
            if (samples <= 0 || samples > 16000L * 1200 || temporary.length() != 44 + samples * 2) {
                throw new IOException("FFmpeg returned incomplete normalized WAV audio");
            }
            try (FileOutputStream sync = new FileOutputStream(temporary, true)) { sync.getFD().sync(); }
            atomicMove(temporary, output);
            return object("path", output.getPath(), "samples", samples, "seconds", (double) samples / 16000,
                    "sampleRate", 16000, "channels", 1, "bitsPerSample", 16, "format", "wav");
        } finally {
            if (temporary.exists() && !temporary.delete()) temporary.deleteOnExit();
        }
    }

    private File privateFile(String path, boolean input) throws IOException {
        if (path == null || path.isEmpty() || !new File(path).isAbsolute()) {
            throw new SpeechException("InvalidArgumentException", "Audio paths must be absolute app-private paths");
        }
        File candidate = new File(path).getCanonicalFile();
        String value = candidate.getPath();
        String filesRoot = files.getCanonicalPath() + File.separator;
        String cacheRoot = cache.getCanonicalPath() + File.separator;
        if (!(value.startsWith(filesRoot) || value.startsWith(cacheRoot))) {
            throw new SpeechException("InvalidArgumentException", "音频路径必须位于应用私有 files 或 cache 目录。 ");
        }
        if (input && (!candidate.isFile() || candidate.length() <= 0 || candidate.length() > MAX_INPUT_BYTES)) {
            throw new SpeechException("InvalidArgumentException", "Audio input must be a nonempty file of at most 256 MiB");
        }
        if (!input && candidate.exists() && !candidate.isFile()) {
            throw new SpeechException("InvalidArgumentException", "Audio output must be a file path");
        }
        return candidate;
    }

    private void release(long timeout) {
        cancel();
        boolean acquired = false;
        try {
            acquired = OPERATION.tryLock(timeout, TimeUnit.MILLISECONDS);
            if (!acquired) throw new SpeechException("AudioBusyException", "Local audio is still stopping; retry release shortly");
            if (loaded) nativeRelease();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new SpeechException("CancelledException", "Local audio release was interrupted");
        } finally {
            if (acquired) OPERATION.unlock();
        }
    }

    private void checkDeadline() {
        Work work = active;
        if (work == null || work.owner != this) return;
        if (SystemClock.elapsedRealtime() >= work.deadline) cancelWork(work, true);
        // JNI resets its abort flag on entry. Repeat a requested cancellation
        // until the worker exits, covering a cancel immediately before JNI entry.
        else if (work.cancelled) cancelWork(work, false);
    }

    private static void cancelWork(Work work, boolean timeout) {
        HttpURLConnection connection;
        synchronized (CANCEL_LOCK) {
            // A delayed deadline callback must not cancel the next operation.
            if (active != work) return;
            work.cancelled = true;
            work.timedOut |= timeout;
            if (loaded) { try { nativeCancel(); } catch (LinkageError ignored) { } }
            connection = work.connection;
        }
        if (connection != null) connection.disconnect();
    }

    private static void checkCancelled(Work work) {
        if (work == null) return;
        if (SystemClock.elapsedRealtime() >= work.deadline) cancelWork(work, true);
        if (work.cancelled || Thread.currentThread().isInterrupted()) {
            throw new SpeechException(work.timedOut ? "TimeoutException" : "CancelledException",
                    work.timedOut ? "本地音频操作超时，请缩短音频后重试。" : "本地音频操作已取消。");
        }
    }

    private static String hash(File file, Work work) throws IOException {
        MessageDigest digest = digest();
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                checkCancelled(work);
                digest.update(buffer, 0, count);
            }
        }
        return hex(digest.digest());
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is unavailable", impossible); }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }

    private static void atomicMove(File from, File to) throws IOException {
        try { Os.rename(from.getPath(), to.getPath()); }
        catch (ErrnoException failure) { throw new IOException("Cannot atomically save the private audio file", failure); }
    }

    private static String errorName(Work work, String fallback) {
        return work != null && work.timedOut ? "TimeoutException"
                : work != null && work.cancelled ? "CancelledException" : fallback;
    }

    private static String errorMessage(Work work, Throwable failure) {
        if (work != null && work.timedOut) return "本地音频操作超时，请检查网络或缩短音频后重试。";
        if (work != null && work.cancelled) return "本地音频操作已取消。";
        String message = failure.getMessage();
        return message == null || message.isEmpty() ? "本地音频处理失败，请检查音频格式或重新下载模型。" : message;
    }

    private static JSONObject object(Object... pairs) {
        JSONObject result = new JSONObject();
        try {
            for (int i = 0; i < pairs.length; i += 2) result.put(String.valueOf(pairs[i]), pairs[i + 1]);
        } catch (JSONException impossible) { throw new IllegalArgumentException("Invalid local speech response", impossible); }
        return result;
    }

    private static JSONObject success(JSONObject value) { return object("ok", true, "value", value); }
    private static JSONObject error(String name, String message) { return object("ok", false, "error", object("name", name, "message", message)); }

    private static final class Work {
        final AndroidSpeechNative owner;
        final String op;
        final long deadline;
        volatile boolean cancelled;
        volatile boolean timedOut;
        volatile HttpURLConnection connection;
        Work(AndroidSpeechNative owner, String op, long deadline) { this.owner = owner; this.op = op; this.deadline = deadline; }
    }

    private static final class SpeechException extends RuntimeException {
        final String name;
        SpeechException(String name, String message) { super(message); this.name = name; }
    }

    private static native String nativeVersions();
    private static native long nativeDecode(String input, String output);
    private static native String nativeTranscribe(String input, String model, String language, int threads);
    private static native void nativeCancel();
    private static native void nativeRelease();
}
