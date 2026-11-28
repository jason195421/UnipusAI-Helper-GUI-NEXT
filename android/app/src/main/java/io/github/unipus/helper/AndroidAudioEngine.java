package io.github.unipus.helper;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** App-private Android speech. Only the native worker RPC can invoke this class. */
public final class AndroidAudioEngine {
    private static final int MAX_AUDIO_BYTES = 8 * 1024 * 1024;
    private static final int MAX_SOURCE_WAV_BYTES = 64 * 1024 * 1024;
    private static final int OUTPUT_SAMPLE_RATE = 16000;
    private static final int MAX_QUEUE = 8;
    private static final long INIT_TIMEOUT_MS = 15000;
    private static final String SETTINGS_ACTION = "com.android.settings.TTS_SETTINGS";
    // Even TTS's synchronous Binder getters stay off the Activity main thread.
    private final HandlerThread speechThread;
    private final Handler ui;
    private final BackendFactory factory;
    private final File cache;
    private final ArrayDeque<AudioCall> queue = new ArrayDeque<>();
    private SpeechBackend backend;
    private AudioCall active;
    private String engine = "";
    private boolean ready;
    private boolean initializing;
    private volatile boolean destroyed;
    private boolean closed;
    private int generation;
    private Runnable initTimeout;
    private List<EngineInfo> installedEngines = Collections.emptyList();

    public AndroidAudioEngine(Context context) {
        this(new AndroidBackendFactory(context.getApplicationContext()),
                new File(context.getApplicationContext().getCacheDir(), "native-tts"));
    }

    // Package-private seam keeps cancellation/timeout tests independent of any
    // installed voice or network service. Production always uses TextToSpeech.
    AndroidAudioEngine(BackendFactory factory, File cache) {
        this.factory = factory;
        this.cache = cache;
        speechThread = new HandlerThread("unipus-system-speech");
        speechThread.start();
        ui = new Handler(speechThread.getLooper());
    }

    /** Worker-only. Speaker playback and synthesis both have finite deadlines. */
    public String callJson(JSONObject request, long timeoutMs) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return error("WebDriverException", "Speech RPC must run on a worker thread").toString();
        }
        if (destroyed) return error("InvalidSessionIdException", "Speech engine was closed").toString();
        AudioCall call = null;
        try {
            call = new AudioCall(request, Math.max(250, Math.min(120000, timeoutMs)));
            final AudioCall submitted = call;
            if ("tts_synthesize".equals(call.op)) {
                if (!cache.isDirectory() && !cache.mkdirs()) throw new IOException("Cannot create speech cache");
                call.file = File.createTempFile("speech-", ".wav", cache);
            }
            if (!ui.post(() -> submit(submitted))) {
                return error("InvalidSessionIdException", "Speech UI is unavailable").toString();
            }
            try {
                if (!call.latch.await(call.remainingMs(), TimeUnit.MILLISECONDS)) {
                    call.complete(error("TimeoutException", "Android speech operation timed out"));
                    ui.post(() -> cancelCall(submitted, "TimeoutException", "Android speech operation timed out"));
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                call.complete(error("CancelledException", "Android speech operation was interrupted"));
                ui.post(() -> cancelCall(submitted, "CancelledException", "Android speech operation was interrupted"));
            }
            JSONObject result = call.result;
            if (result == null) return error("AudioSynthesisException", "Speech engine returned no result").toString();
            if (result.optBoolean("ok") && "tts_synthesize".equals(call.op)) {
                long length = call.file.length();
                if (length <= 44 || length > MAX_SOURCE_WAV_BYTES) {
                    throw new AudioException("AudioSynthesisException", "The speech engine returned empty or oversized WAV audio");
                }
                Wave wave = parseWave(Files.readAllBytes(call.file.toPath()), call.maxSeconds);
                JSONObject value = result.getJSONObject("value");
                put(value, "format", "wav", "data", Base64.encodeToString(wave.data, Base64.NO_WRAP),
                        "bytes", wave.data.length, "sampleRate", wave.sampleRate, "channels", wave.channels,
                        "bitsPerSample", wave.bits, "duration", wave.duration, "truncated", wave.truncated);
            }
            return result.toString();
        } catch (AudioException exception) {
            return error(exception.name, exception.getMessage()).toString();
        } catch (IOException | JSONException | RuntimeException exception) {
            return error("AudioSynthesisException", "Unable to prepare or read Android speech audio").toString();
        } catch (OutOfMemoryError exhausted) {
            return error("AudioSynthesisException", "内存不足，无法读取或转换合成音频，请缩短朗读文本后重试。").toString();
        } finally {
            if (call != null && call.file != null) {
                try { Files.deleteIfExists(call.file.toPath()); } catch (IOException ignored) { }
            }
        }
    }

    /** Nonblocking lifecycle methods, safe to invoke from the Activity UI. */
    public void stop() {
        if (Looper.myLooper() == ui.getLooper()) stopAll("CancelledException", "Speech was stopped");
        else ui.post(() -> stopAll("CancelledException", "Speech was stopped"));
    }

    public void destroy() {
        destroyed = true;
        if (Looper.myLooper() != ui.getLooper()) { ui.post(this::close); return; }
        close();
    }

    private void close() {
        if (closed) return;
        closed = true;
        stopAll("InvalidSessionIdException", "Speech engine was closed");
        releaseBackend();
        speechThread.quitSafely();
    }

    private void submit(AudioCall call) {
        if (call.finished) return;
        if (destroyed) { finish(call, error("InvalidSessionIdException", "Speech engine was closed")); return; }
        if ("tts_stop".equals(call.op)) {
            stopAll("CancelledException", "Speech was stopped");
            finish(call, success(object("stopped", true)));
            return;
        }
        try {
            List<EngineInfo> installed = factory.engines();
            installedEngines = installed;
            String selected = selectEngine(call.requestedEngine, installed);
            if (selected.isEmpty()) {
                String message = "未安装可用的文字转语音引擎，请打开系统文字转语音设置安装或启用引擎。";
                if ("tts_status".equals(call.op)) finish(call, success(status(call, installed, message)));
                else finish(call, error("DependencyUnavailableException", message));
                return;
            }
            call.selectedEngine = selected;
            if ("tts_status".equals(call.op) && ready && selected.equals(engine)) {
                finish(call, success(status(call, installed, "")));
                return;
            }
            if (queue.size() >= MAX_QUEUE) throw new AudioException("AudioBusyException", "Too many queued speech requests");
            call.timeout = () -> cancelCall(call, "TimeoutException", "Android speech operation timed out");
            queue.add(call);
            ui.postDelayed(call.timeout, call.remainingMs());
            pump();
        } catch (AudioException exception) { finish(call, error(exception.name, exception.getMessage())); }
        catch (RuntimeException exception) { finish(call, error("AudioSynthesisException", "Unable to query the Android speech service")); }
    }

    private void pump() {
        if (destroyed || active != null || initializing) return;
        while (!queue.isEmpty()) {
            AudioCall call = queue.peek();
            if (call.finished) { queue.remove(); continue; }
            if (!ready || !call.selectedEngine.equals(engine)) { initialize(call.selectedEngine); return; }
            queue.remove();
            if ("tts_status".equals(call.op)) {
                try { finish(call, success(status(call, installedEngines, ""))); }
                catch (RuntimeException unavailable) { finish(call, error("AudioSynthesisException", "Unable to query the Android speech service")); }
                continue;
            }
            active = call;
            try {
                Voice voice = chooseVoice(call);
                if (backend.setVoice(voice) != TextToSpeech.SUCCESS
                        || backend.setRate(call.rate) != TextToSpeech.SUCCESS
                        || backend.setPitch(call.pitch) != TextToSpeech.SUCCESS) {
                    throw new AudioException("DependencyUnavailableException", "语音引擎无法加载所选语音，请在系统文字转语音设置下载语音数据。 ");
                }
                call.voice = voice.getName();
                call.actualLanguage = voice.getLocale().toLanguageTag();
                Bundle params = new Bundle();
                int result = "tts_synthesize".equals(call.op)
                        ? backend.synthesize(call.text, params, call.file, call.id)
                        : backend.speak(call.text, params, call.id);
                if (result != TextToSpeech.SUCCESS) throw new AudioException("AudioSynthesisException", "The Android speech engine rejected the request");
                if ("tts_speak".equals(call.op)) {
                    call.playbackLimit = () -> cancelCall(call, "TimeoutException", "朗读达到时长上限，已停止播放。");
                    ui.postDelayed(call.playbackLimit, (long) Math.ceil(call.maxSeconds * 1000));
                }
            } catch (AudioException exception) { finish(call, error(exception.name, exception.getMessage())); }
            catch (RuntimeException exception) { finish(call, error("AudioSynthesisException", "The Android speech service is unavailable")); }
            return;
        }
    }

    private void initialize(String selected) {
        releaseBackend();
        engine = selected;
        initializing = true;
        final int token = generation;
        initTimeout = () -> {
            if (initializing && generation == token) failInitialization("文字转语音引擎初始化超时，请检查系统语音设置后重试。");
        };
        ui.postDelayed(initTimeout, INIT_TIMEOUT_MS);
        try {
            backend = factory.create(selected, status -> ui.post(() -> {
                if (destroyed || token != generation) return;
                initializing = false;
                if (initTimeout != null) ui.removeCallbacks(initTimeout);
                if (status != TextToSpeech.SUCCESS) { failInitialization("文字转语音引擎初始化失败，请检查系统语音设置。"); return; }
                ready = true;
                pump();
            }), new UtteranceProgressListener() {
                @Override public void onStart(String id) { }
                @Override public void onDone(String id) { ui.post(() -> utteranceDone(id, token)); }
                @Override public void onError(String id) { onError(id, TextToSpeech.ERROR_SYNTHESIS); }
                @Override public void onError(String id, int code) { ui.post(() -> utteranceFailed(id, token, code)); }
                @Override public void onStop(String id, boolean interrupted) {
                    ui.post(() -> {
                        if (generation == token && active != null && active.id.equals(id)) {
                            finish(active, error("CancelledException", "Speech was stopped by the engine"));
                        }
                    });
                }
                @Override public void onAudioAvailable(String id, byte[] audio) {
                    final int count = audio == null ? 0 : audio.length;
                    ui.post(() -> {
                        if (generation != token || active == null || !active.id.equals(id)
                                || !"tts_synthesize".equals(active.op)) return;
                        active.generatedBytes += count;
                        if (active.generatedBytes > MAX_SOURCE_WAV_BYTES - 44) {
                            cancelCall(active, "AudioSynthesisException", "Generated speech exceeded the audio size limit");
                        }
                    });
                }
            });
        } catch (RuntimeException exception) { failInitialization("无法连接系统文字转语音引擎，请检查系统语音设置。"); }
    }

    private void failInitialization(String message) {
        releaseBackend();
        for (AudioCall call : new ArrayList<>(queue)) {
            if ("tts_status".equals(call.op)) finish(call, success(status(call, installedEngines, message)));
            else finish(call, error("DependencyUnavailableException", message));
        }
    }

    private void releaseBackend() {
        generation++;
        ready = false;
        initializing = false;
        if (initTimeout != null) ui.removeCallbacks(initTimeout);
        initTimeout = null;
        if (backend != null) {
            try { backend.shutdown(); } catch (RuntimeException ignored) { }
            backend = null;
        }
    }

    private String selectEngine(String requested, List<EngineInfo> installed) {
        if (!requested.isEmpty()) {
            for (EngineInfo entry : installed) if (entry.name.equals(requested)) return entry.name;
            throw new AudioException("DependencyUnavailableException", "所选文字转语音引擎未安装，请打开系统语音设置安装或更换引擎。 ");
        }
        String preferred = defaultEngine();
        for (EngineInfo entry : installed) if (entry.name.equals(preferred)) return entry.name;
        return installed.isEmpty() ? "" : installed.get(0).name;
    }

    private Voice chooseVoice(AudioCall call) {
        if (backend.languageAvailability(call.language) < TextToSpeech.LANG_AVAILABLE) {
            throw new AudioException("DependencyUnavailableException", "所选语言的语音数据不可用，请在系统文字转语音设置下载 " + call.language.toLanguageTag() + " 语音。 ");
        }
        List<Voice> candidates = new ArrayList<>();
        for (Voice voice : safeVoices()) {
            if (!call.voice.isEmpty() && !call.voice.equals(voice.getName())) continue;
            if (!voice.getLocale().getLanguage().equals(call.language.getLanguage())) continue;
            if (!voiceInstalled(voice) || (call.offlineOnly && voice.isNetworkConnectionRequired())) continue;
            candidates.add(voice);
        }
        if (candidates.isEmpty()) {
            String specific = call.voice.isEmpty() ? call.language.toLanguageTag() : call.voice;
            throw new AudioException("DependencyUnavailableException", "没有已安装的" + (call.offlineOnly ? "离线" : "")
                    + "语音可用于 " + specific + "，请在系统文字转语音设置下载语音数据或选择其他语音。 ");
        }
        candidates.sort(Comparator.comparing((Voice voice) -> !voice.getLocale().equals(call.language))
                .thenComparing(Voice::isNetworkConnectionRequired)
                .thenComparing(Comparator.comparingInt(Voice::getQuality).reversed())
                .thenComparingInt(Voice::getLatency).thenComparing(Voice::getName));
        return candidates.get(0);
    }

    private Set<Voice> safeVoices() {
        Set<Voice> voices;
        try { voices = backend == null ? null : backend.voices(); }
        catch (RuntimeException unavailable) { voices = null; }
        return voices == null ? Collections.emptySet() : voices;
    }

    private String defaultEngine() {
        try { return factory.defaultEngine(); } catch (RuntimeException unavailable) { return ""; }
    }

    private static boolean voiceInstalled(Voice voice) {
        Set<String> features = voice.getFeatures();
        return features == null || !features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED);
    }

    private JSONObject status(AudioCall call, List<EngineInfo> installed, String problem) {
        JSONArray engines = new JSONArray(), voices = new JSONArray();
        for (EngineInfo entry : installed) engines.put(object("name", entry.name, "label", entry.label, "system", entry.system));
        int languageStatus = backend != null && ready ? backend.languageAvailability(call.language) : TextToSpeech.LANG_MISSING_DATA;
        boolean offlineAvailable = false, anyAvailable = false, selectedOffline = false, selectedAvailable = false;
        for (Voice voice : safeVoices()) {
            boolean loaded = voiceInstalled(voice);
            if (voice.getLocale().getLanguage().equals(call.language.getLanguage()) && loaded) {
                anyAvailable = true;
                if (!voice.isNetworkConnectionRequired()) offlineAvailable = true;
                if (call.voice.isEmpty() || call.voice.equals(voice.getName())) {
                    selectedAvailable = true;
                    if (!voice.isNetworkConnectionRequired()) selectedOffline = true;
                }
            }
            if (voices.length() < 512) voices.put(object("name", voice.getName(), "language", voice.getLocale().toLanguageTag(),
                    "networkRequired", voice.isNetworkConnectionRequired(), "installed", loaded,
                    "offlineAvailable", loaded && !voice.isNetworkConnectionRequired(), "quality", voice.getQuality(), "latency", voice.getLatency()));
        }
        boolean available = ready && languageStatus >= TextToSpeech.LANG_AVAILABLE && (call.offlineOnly ? selectedOffline : selectedAvailable);
        if (problem.isEmpty() && !available) {
            problem = ready ? "所选语言没有可用的" + (call.offlineOnly ? "离线" : "")
                    + "语音数据，请打开系统文字转语音设置安装语音。" : "文字转语音引擎尚未就绪。";
        }
        return object("installed", !installed.isEmpty(), "available", available, "ready", ready,
                "initializing", initializing, "engine", engine, "defaultEngine", defaultEngine(), "engines", engines,
                "language", call.language.toLanguageTag(), "languageAvailable", languageStatus >= TextToSpeech.LANG_AVAILABLE,
                "languageStatus", languageStatus, "offlineAvailable", offlineAvailable, "offlineOnly", call.offlineOnly,
                "requestedVoice", call.voice, "voiceAvailable", call.offlineOnly ? selectedOffline : selectedAvailable,
                "voices", voices, "voicesTruncated", safeVoices().size() > 512,
                "maxInputLength", TextToSpeech.getMaxSpeechInputLength(), "busy", active != null,
                "queued", queue.size(), "settingsAction", SETTINGS_ACTION, "error", problem);
    }

    private void utteranceDone(String id, int token) {
        if (token != generation || active == null || !active.id.equals(id)) return;
        AudioCall call = active;
        finish(call, success(object("utteranceId", call.id, "engine", engine, "voice", call.voice,
                "language", call.actualLanguage, "offlineOnly", call.offlineOnly)));
    }

    private void utteranceFailed(String id, int token, int code) {
        if (token != generation || active == null || !active.id.equals(id)) return;
        finish(active, error(code == TextToSpeech.ERROR_NOT_INSTALLED_YET ? "DependencyUnavailableException" : "AudioSynthesisException",
                "Android speech failed (" + code + "); 请检查系统语音引擎和已安装语音数据。"));
    }

    private void cancelCall(AudioCall call, String name, String message) {
        if (active == call && backend != null) {
            try { backend.stop(); } catch (RuntimeException ignored) { }
        }
        finish(call, error(name, message));
        if (initializing && queue.isEmpty()) releaseBackend();
    }

    private void stopAll(String name, String message) {
        if (backend != null) { try { backend.stop(); } catch (RuntimeException ignored) { } }
        if (active != null) finish(active, error(name, message));
        for (AudioCall call : new ArrayList<>(queue)) finish(call, error(name, message));
        if (initializing) releaseBackend();
    }

    private void finish(AudioCall call, JSONObject result) {
        queue.remove(call);
        if (active == call) active = null;
        if (call.timeout != null) ui.removeCallbacks(call.timeout);
        if (call.playbackLimit != null) ui.removeCallbacks(call.playbackLimit);
        call.complete(result);
        ui.post(this::pump);
    }

    static Wave parseWave(byte[] source, double maxSeconds) {
        if (source.length < 44 || source.length > MAX_SOURCE_WAV_BYTES || !tag(source, 0, "RIFF") || !tag(source, 8, "WAVE")
                || u32(source, 4) + 8 != source.length) throw new AudioException("AudioSynthesisException", "Speech engine output is not a complete RIFF/WAV file");
        int fmt = -1, data = -1, dataLength = 0;
        for (int at = 12; at <= source.length - 8;) {
            long length = u32(source, at + 4);
            if (length > source.length - at - 8) throw new AudioException("AudioSynthesisException", "Speech WAV has a truncated chunk");
            if (tag(source, at, "fmt ") && length >= 16) fmt = at + 8;
            if (tag(source, at, "data") && data < 0) { data = at + 8; dataLength = (int) length; }
            long next = at + 8L + length + (length & 1);
            if (next > Integer.MAX_VALUE) break;
            at = (int) next;
        }
        if (fmt < 0 || data < 0 || dataLength <= 0) throw new AudioException("AudioSynthesisException", "Speech WAV contains no PCM audio");
        int format = u16(source, fmt), channels = u16(source, fmt + 2), sampleRate = (int) u32(source, fmt + 4);
        int align = u16(source, fmt + 12), bits = u16(source, fmt + 14);
        if (!(format == 1 || format == 3) || channels < 1 || channels > 2 || sampleRate < 8000 || sampleRate > 192000
                || !(bits == 8 || bits == 16 || bits == 32) || (format == 3 && bits != 32)
                || align != channels * (bits / 8) || dataLength % align != 0
                || u32(source, fmt + 8) != (long) sampleRate * align) {
            throw new AudioException("AudioSynthesisException", "Speech engine returned an unsupported WAV format");
        }
        int sourceFrames = dataLength / align;
        int frames = (int) Math.min(Math.floor((double) sourceFrames * OUTPUT_SAMPLE_RATE / sampleRate),
                Math.min(Math.floor(maxSeconds * OUTPUT_SAMPLE_RATE), (MAX_AUDIO_BYTES - 44) / 2));
        if (frames <= 0) throw new AudioException("AudioSynthesisException", "Speech WAV contains no complete audio frames");
        int length = frames * 2;
        // Android engines may supply unsigned 8-bit or IEEE float PCM. Return
        // canonical 16 kHz mono PCM16 WAV for Python and WebAudio. Downmix and
        // interpolate directly from the input, without a second full PCM buffer.
        byte[] result = new byte[44 + length];
        ascii(result, 0, "RIFF"); le32(result, 4, result.length - 8); ascii(result, 8, "WAVE");
        ascii(result, 12, "fmt "); le32(result, 16, 16); le16(result, 20, 1); le16(result, 22, 1);
        le32(result, 24, OUTPUT_SAMPLE_RATE); le32(result, 28, OUTPUT_SAMPLE_RATE * 2); le16(result, 32, 2); le16(result, 34, 16);
        ascii(result, 36, "data"); le32(result, 40, length);
        boolean audible = false;
        for (int sample = 0; sample < frames; sample++) {
            double position = (double) sample * sampleRate / OUTPUT_SAMPLE_RATE;
            int first = Math.min((int) position, sourceFrames - 1), second = Math.min(first + 1, sourceFrames - 1);
            double fraction = position - first;
            double a = monoSample(source, data, first, channels, bits, format);
            double b = monoSample(source, data, second, channels, bits, format);
            int value = (int) Math.max(-32768, Math.min(32767, Math.round((a + (b - a) * fraction) * 32768)));
            audible |= value != 0;
            le16(result, 44 + sample * 2, value);
        }
        if (!audible) throw new AudioException("AudioSynthesisException", "The speech engine returned only silence");
        return new Wave(result, OUTPUT_SAMPLE_RATE, 1, 16, (double) frames / OUTPUT_SAMPLE_RATE,
                (double) sourceFrames / sampleRate > maxSeconds + 1.0 / sampleRate);
    }

    private static double monoSample(byte[] data, int offset, int frame, int channels, int bits, int format) {
        double total = 0;
        for (int channel = 0; channel < channels; channel++) {
            int at = offset + (frame * channels + channel) * (bits / 8);
            if (format == 3) {
                float input = Float.intBitsToFloat((int) u32(data, at));
                if (!Float.isFinite(input)) throw new AudioException("AudioSynthesisException", "Speech WAV contains invalid floating point samples");
                total += Math.max(-1f, Math.min(1f, input));
            } else if (bits == 8) total += ((data[at] & 255) - 128) / 128.0;
            else if (bits == 16) total += (short) u16(data, at) / 32768.0;
            else total += (int) u32(data, at) / 2147483648.0;
        }
        return total / channels;
    }

    private static boolean tag(byte[] bytes, int offset, String value) {
        if (offset < 0 || offset + 4 > bytes.length) return false;
        for (int i = 0; i < 4; i++) if (bytes[offset + i] != value.charAt(i)) return false;
        return true;
    }
    private static int u16(byte[] b, int at) { return (b[at] & 255) | ((b[at + 1] & 255) << 8); }
    private static long u32(byte[] b, int at) { return u16(b, at) | ((long) u16(b, at + 2) << 16); }
    private static void le16(byte[] b, int at, int value) { b[at] = (byte) value; b[at + 1] = (byte) (value >>> 8); }
    private static void le32(byte[] b, int at, int value) { le16(b, at, value); le16(b, at + 2, value >>> 16); }
    private static void ascii(byte[] b, int at, String text) { System.arraycopy(text.getBytes(StandardCharsets.US_ASCII), 0, b, at, text.length()); }
    private static JSONObject success(Object value) { return object("ok", true, "value", value); }
    private static JSONObject error(String name, String message) { return object("ok", false, "error", object("name", name, "message", message)); }
    private static JSONObject object(Object... values) { JSONObject out = new JSONObject(); put(out, values); return out; }
    private static void put(JSONObject out, Object... values) {
        try { for (int at = 0; at < values.length; at += 2) out.put(String.valueOf(values[at]), values[at + 1]); }
        catch (JSONException exception) { throw new IllegalArgumentException("Invalid speech JSON", exception); }
    }

    static final class Wave {
        final byte[] data;
        final int sampleRate, channels, bits;
        final double duration;
        final boolean truncated;
        Wave(byte[] data, int rate, int channels, int bits, double duration, boolean truncated) {
            this.data = data; sampleRate = rate; this.channels = channels; this.bits = bits; this.duration = duration; this.truncated = truncated;
        }
    }

    static final class AudioException extends RuntimeException {
        final String name;
        AudioException(String name, String message) { super(message); this.name = name; }
    }

    private static final class AudioCall {
        final JSONObject request;
        final String id = "speech-" + UUID.randomUUID();
        final String op, text, requestedEngine;
        final Locale language;
        final float rate, pitch;
        final double maxSeconds;
        final boolean offlineOnly;
        final long deadline;
        final CountDownLatch latch = new CountDownLatch(1);
        volatile boolean finished;
        volatile JSONObject result;
        File file;
        Runnable timeout, playbackLimit;
        String selectedEngine = "", voice, actualLanguage = "";
        long generatedBytes;

        AudioCall(JSONObject request, long timeout) {
            this.request = request;
            op = request.optString("op", "");
            if (!(op.equals("tts_status") || op.equals("tts_synthesize") || op.equals("tts_speak") || op.equals("tts_stop"))) {
                throw new AudioException("UnknownCommandException", "Unknown speech operation");
            }
            deadline = SystemClock.elapsedRealtime() + timeout;
            text = request.optString("text", "").trim();
            voice = request.optString("voice", "").trim();
            String selected = request.optString("engine", "").trim();
            requestedEngine = Arrays.asList("auto", "android", "system").contains(selected.toLowerCase(Locale.ROOT)) ? "" : selected;
            String tag = request.optString("language", "en-US").replace('_', '-');
            language = Locale.forLanguageTag(tag);
            rate = (float) request.optDouble("rate", 1);
            pitch = (float) request.optDouble("pitch", 1);
            maxSeconds = request.optDouble("maxSeconds", 110);
            offlineOnly = request.optBoolean("offlineOnly", true);
            if (language.getLanguage().isEmpty() || !Double.isFinite(maxSeconds) || maxSeconds < .1 || maxSeconds > 120
                    || !Float.isFinite(rate) || rate < .25 || rate > 4 || !Float.isFinite(pitch) || pitch < .25 || pitch > 4) {
                throw new AudioException("InvalidArgumentException", "Invalid speech language, rate, pitch or duration");
            }
            if ((op.equals("tts_synthesize") || op.equals("tts_speak"))
                    && (text.isEmpty() || text.length() > TextToSpeech.getMaxSpeechInputLength())) {
                throw new AudioException("InvalidArgumentException", "Speech text is empty or exceeds the Android engine input limit");
            }
        }
        long remainingMs() { return Math.max(1, deadline - SystemClock.elapsedRealtime()); }
        synchronized void complete(JSONObject value) {
            if (finished) return;
            result = value; finished = true; latch.countDown();
        }
    }

    static final class EngineInfo {
        final String name, label;
        final boolean system;
        EngineInfo(String name, String label, boolean system) { this.name = name; this.label = label; this.system = system; }
    }
    interface BackendFactory {
        List<EngineInfo> engines();
        String defaultEngine();
        SpeechBackend create(String engine, TextToSpeech.OnInitListener listener, UtteranceProgressListener progress);
    }
    interface SpeechBackend {
        int languageAvailability(Locale locale);
        Set<Voice> voices();
        int setVoice(Voice voice);
        int setRate(float rate);
        int setPitch(float pitch);
        int synthesize(String text, Bundle parameters, File file, String id);
        int speak(String text, Bundle parameters, String id);
        int stop();
        void shutdown();
    }

    private static final class AndroidBackendFactory implements BackendFactory {
        private final Context context;
        AndroidBackendFactory(Context context) { this.context = context; }
        @Override public List<EngineInfo> engines() {
            Map<String, EngineInfo> result = new LinkedHashMap<>();
            for (ResolveInfo info : context.getPackageManager().queryIntentServices(new Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)) {
                if (info.serviceInfo == null) continue;
                String name = info.serviceInfo.packageName;
                CharSequence label = info.loadLabel(context.getPackageManager());
                int flags = info.serviceInfo.applicationInfo.flags;
                result.put(name, new EngineInfo(name, label == null ? name : label.toString(),
                        (flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0));
            }
            List<EngineInfo> installed = new ArrayList<>(result.values());
            installed.sort(Comparator.comparing((EngineInfo info) -> !info.system).thenComparing(info -> info.label));
            return installed;
        }
        @Override public String defaultEngine() {
            String value = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.TTS_DEFAULT_SYNTH);
            return value == null ? "" : value;
        }
        @Override public SpeechBackend create(String engine, TextToSpeech.OnInitListener listener, UtteranceProgressListener progress) {
            TextToSpeech service = new TextToSpeech(context, listener, engine);
            service.setOnUtteranceProgressListener(progress);
            return new SpeechBackend() {
                @Override public int languageAvailability(Locale locale) { return service.isLanguageAvailable(locale); }
                @Override public Set<Voice> voices() { return service.getVoices(); }
                @Override public int setVoice(Voice voice) { return service.setVoice(voice); }
                @Override public int setRate(float rate) { return service.setSpeechRate(rate); }
                @Override public int setPitch(float pitch) { return service.setPitch(pitch); }
                @Override public int synthesize(String text, Bundle parameters, File file, String id) { return service.synthesizeToFile(text, parameters, file, id); }
                @Override public int speak(String text, Bundle parameters, String id) { return service.speak(text, TextToSpeech.QUEUE_ADD, parameters, id); }
                @Override public int stop() { return service.stop(); }
                @Override public void shutdown() { service.shutdown(); }
            };
        }
    }
}
