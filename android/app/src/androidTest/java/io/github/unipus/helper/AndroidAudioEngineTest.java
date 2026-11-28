package io.github.unipus.helper;

import android.content.Context;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.util.Base64;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** No network, microphone, real voice installation, or user configuration. */
@RunWith(AndroidJUnit4.class)
public final class AndroidAudioEngineTest {
    private AndroidAudioEngine engine;
    private FakeFactory factory;
    private File cache;
    private ExecutorService workers;

    @Before public void createEngine() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        cache = new File(context.getCacheDir(), "tts-test-" + UUID.randomUUID());
        factory = new FakeFactory();
        engine = new AndroidAudioEngine(factory, cache);
        workers = Executors.newFixedThreadPool(2);
    }

    @After public void closeEngine() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(engine::destroy);
        workers.shutdownNow();
        File[] files = cache.listFiles();
        if (files != null) for (File file : files) file.delete();
        cache.delete();
    }

    private JSONObject call(String op, Object... pairs) throws Exception { return callWithTimeout(op, 6000, pairs); }
    private JSONObject callWithTimeout(String op, long timeout, Object... pairs) throws Exception {
        JSONObject request = new JSONObject().put("op", op);
        for (int i = 0; i < pairs.length; i += 2) request.put(String.valueOf(pairs[i]), pairs[i + 1]);
        return new JSONObject(engine.callJson(request, timeout));
    }
    private JSONObject value(String op, Object... pairs) throws Exception {
        JSONObject response = call(op, pairs);
        assertTrue(response.toString(), response.getBoolean("ok"));
        return response.getJSONObject("value");
    }
    private void assertError(String name, JSONObject response) throws Exception {
        assertFalse(response.toString(), response.getBoolean("ok"));
        assertEquals(response.toString(), name, response.getJSONObject("error").getString("name"));
    }
    private void assertNoFiles() {
        File[] files = cache.listFiles();
        assertTrue("Temporary speech files were retained", files == null || files.length == 0);
    }

    @Test(timeout = 15000) public void synthesizesRealPcmWaveClipsAndDeletesTheTemporaryFile() throws Exception {
        JSONObject result = value("tts_synthesize", "text", "A local audio fixture.", "maxSeconds", .25);
        assertEquals("wav", result.getString("format"));
        assertEquals(16000, result.getInt("sampleRate"));
        assertEquals(1, result.getInt("channels")); assertEquals(16, result.getInt("bitsPerSample"));
        assertEquals(.25, result.getDouble("duration"), .00001); assertTrue(result.getBoolean("truncated"));
        byte[] bytes = Base64.decode(result.getString("data"), Base64.DEFAULT);
        assertEquals(44 + 4000 * 2, bytes.length);
        assertEquals("RIFF", new String(bytes, 0, 4, StandardCharsets.US_ASCII));
        assertEquals("WAVE", new String(bytes, 8, 4, StandardCharsets.US_ASCII));
        assertTrue("The fixture audio must contain non-silent samples", bytes[46] != 0 || bytes[47] != 0);
        assertEquals(1, factory.syntheses); assertEquals(0, factory.speaks);
        assertNoFiles();
    }

    @Test(timeout = 15000) public void reportsInstalledOfflineNetworkAndMissingVoiceDataTruthfully() throws Exception {
        JSONObject status = value("tts_status");
        assertTrue(status.getBoolean("installed")); assertTrue(status.getBoolean("ready"));
        assertTrue(status.getBoolean("available")); assertTrue(status.getBoolean("offlineAvailable"));
        JSONArray voices = status.getJSONArray("voices");
        boolean network = false, missing = false;
        for (int i = 0; i < voices.length(); i++) {
            JSONObject voice = voices.getJSONObject(i);
            if (voice.getString("name").equals("network")) { network = true; assertFalse(voice.getBoolean("offlineAvailable")); }
            if (voice.getString("name").equals("missing")) { missing = true; assertFalse(voice.getBoolean("installed")); }
        }
        assertTrue(network && missing);
        for (String voice : new String[]{"network", "missing", "does-not-exist"}) {
            assertError("DependencyUnavailableException", call("tts_synthesize", "text", "Fixture", "voice", voice));
        }
        assertFalse(value("tts_status", "voice", "network").getBoolean("available"));
        assertEquals(0, factory.syntheses);
        assertNoFiles();
    }

    @Test(timeout = 15000) public void exposesActionableMissingEngineAndLanguageState() throws Exception {
        factory.installed = false;
        JSONObject absent = value("tts_status");
        assertFalse(absent.getBoolean("installed")); assertFalse(absent.getBoolean("available"));
        assertFalse(absent.getString("error").isEmpty()); assertFalse(absent.getString("settingsAction").isEmpty());
        assertError("DependencyUnavailableException", call("tts_synthesize", "text", "Fixture"));
        factory.installed = true;
        JSONObject language = value("tts_status", "language", "zh-CN");
        assertFalse(language.getBoolean("languageAvailable")); assertFalse(language.getBoolean("available"));
        assertError("DependencyUnavailableException", call("tts_synthesize", "text", "Fixture", "language", "zh-CN"));
        assertError("DependencyUnavailableException", call("tts_synthesize", "text", "Fixture", "engine", "missing.engine"));
        assertNoFiles();
    }

    @Test(timeout = 15000) public void aSecondWorkerCanStopSynthesisAndReuseTheEngine() throws Exception {
        factory.hold = true;
        Future<JSONObject> pending = workers.submit(() -> call("tts_synthesize", "text", "Hold this request"));
        assertTrue(factory.started.await(3, TimeUnit.SECONDS));
        assertTrue(value("tts_status").getBoolean("busy"));
        assertTrue(value("tts_stop").getBoolean("stopped"));
        assertError("CancelledException", pending.get(3, TimeUnit.SECONDS));
        assertNoFiles();
        factory.hold = false;
        assertTrue(value("tts_synthesize", "text", "The next request succeeds").getInt("bytes") > 44);
        assertTrue(factory.stops > 0);
    }

    @Test(timeout = 15000) public void deadlinesStopPendingSynthesisAndInitialization() throws Exception {
        factory.hold = true;
        assertError("TimeoutException", callWithTimeout("tts_synthesize", 300, "text", "Fixture"));
        value("tts_stop");
        assertTrue(factory.stops > 0); assertNoFiles();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(engine::destroy);
        factory = new FakeFactory(); factory.hangInitialization = true;
        engine = new AndroidAudioEngine(factory, cache);
        assertError("TimeoutException", callWithTimeout("tts_status", 300));
        value("tts_stop");
        assertTrue(factory.shutdowns > 0);
        factory.hangInitialization = false;
        assertTrue(value("tts_status").getBoolean("ready"));
    }

    @Test(timeout = 15000) public void destructionCancelsAndRejectsLaterCalls() throws Exception {
        factory.hold = true;
        Future<JSONObject> pending = workers.submit(() -> call("tts_synthesize", "text", "Hold this request"));
        assertTrue(factory.started.await(3, TimeUnit.SECONDS));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(engine::destroy);
        assertError("InvalidSessionIdException", pending.get(3, TimeUnit.SECONDS));
        assertError("InvalidSessionIdException", call("tts_status"));
        assertTrue(factory.shutdowns > 0); assertNoFiles();
    }

    @Test(timeout = 15000) public void previewUsesSpeakAndUiThreadWaitsAreRejected() throws Exception {
        assertFalse(value("tts_speak", "text", "Preview fixture").getString("utteranceId").isEmpty());
        assertEquals(1, factory.speaks); assertEquals(0, factory.syntheses);
        AtomicReference<String> result = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> result.set(engine.callJson(new JSONObject(), 1000)));
        assertError("WebDriverException", new JSONObject(result.get()));
        assertError("InvalidArgumentException", call("tts_synthesize", "text", " "));
        assertError("InvalidArgumentException", call("tts_synthesize", "text", "Fixture", "rate", -1));
        assertNoFiles();
    }

    @Test public void waveReaderNormalizesSupportedPcmAndRejectsCorruptOutput() {
        for (int[] format : new int[][]{{1, 8}, {1, 16}, {1, 32}, {3, 32}}) {
            AndroidAudioEngine.Wave wave = AndroidAudioEngine.parseWave(wav(format[0], format[1]), .4);
            assertEquals(16, wave.bits); assertEquals(.4, wave.duration, .00001);
            assertEquals(16000, wave.sampleRate); assertEquals(1, wave.channels);
            assertEquals(44 + 6400 * 2, wave.data.length); assertEquals(1, wave.data[20]);
        }
        byte[] corrupt = wav(1, 16); corrupt[0] = 'X';
        assertThrows(AndroidAudioEngine.AudioException.class, () -> AndroidAudioEngine.parseWave(corrupt, 1));
        byte[] truncated = Arrays.copyOf(wav(1, 16), 100);
        assertThrows(AndroidAudioEngine.AudioException.class, () -> AndroidAudioEngine.parseWave(truncated, 1));
        byte[] badRate = wav(1, 16); badRate[28] = 1;
        assertThrows(AndroidAudioEngine.AudioException.class, () -> AndroidAudioEngine.parseWave(badRate, 1));
    }

    @Test public void stereo48KhzOutputIsDownmixedAndResampledBeforeTheBrowserBridge() {
        AndroidAudioEngine.Wave wave = AndroidAudioEngine.parseWave(wav(1, 16, 48000, 2), .75);
        assertEquals(16000, wave.sampleRate); assertEquals(1, wave.channels); assertEquals(16, wave.bits);
        assertEquals(.75, wave.duration, .00001); assertEquals(44 + 12000 * 2, wave.data.length);
        assertTrue(wave.data[46] != 0 || wave.data[47] != 0);
    }

    @Test(timeout = 60000) public void realSystemEngineSynthesizesEnglishWaveWhenOfflineVoiceExists() throws Exception {
        AndroidAudioEngine system = new AndroidAudioEngine(InstrumentationRegistry.getInstrumentation().getTargetContext());
        try {
            requireRealOfflineVoice(system);
            JSONObject request = new JSONObject().put("op", "tts_synthesize").put("language", "en-US")
                    .put("text", "This is a real Android offline speech synthesis test.").put("offlineOnly", true).put("maxSeconds", 4);
            JSONObject response = new JSONObject(system.callJson(request, 25000));
            skipOnlyMissingVoice(response);
            assertTrue(response.toString(), response.getBoolean("ok"));
            JSONObject result = response.getJSONObject("value");
            assertEquals(16000, result.getInt("sampleRate")); assertEquals(1, result.getInt("channels")); assertEquals(16, result.getInt("bitsPerSample"));
            byte[] data = Base64.decode(result.getString("data"), Base64.DEFAULT);
            AndroidAudioEngine.Wave wave = AndroidAudioEngine.parseWave(data, 4);
            assertTrue(wave.duration > 0 && wave.duration <= 4); assertTrue(data.length > 44);
            Log.i("UnipusRealTtsTest", "Real system TTS synthesized " + data.length + " bytes of 16kHz mono PCM16 using " + result.optString("engine"));
        } finally { system.destroy(); }
    }

    @Test(timeout = 60000) public void realSystemEngineSpeaksEnglishWhenOfflineVoiceExists() throws Exception {
        AndroidAudioEngine system = new AndroidAudioEngine(InstrumentationRegistry.getInstrumentation().getTargetContext());
        try {
            requireRealOfflineVoice(system);
            JSONObject request = new JSONObject().put("op", "tts_speak").put("language", "en-US")
                    .put("text", "Offline speech test.").put("offlineOnly", true).put("maxSeconds", 8);
            JSONObject response = new JSONObject(system.callJson(request, 20000));
            skipOnlyMissingVoice(response);
            assertTrue(response.toString(), response.getBoolean("ok"));
            assertFalse(response.getJSONObject("value").getString("utteranceId").isEmpty());
            Log.i("UnipusRealTtsTest", "Real Android TextToSpeech speak completed without a fake backend");
        } finally { system.destroy(); }
    }

    private static void requireRealOfflineVoice(AndroidAudioEngine system) throws Exception {
        JSONObject status = new JSONObject(system.callJson(new JSONObject().put("op", "tts_status")
                .put("language", "en-US").put("offlineOnly", true), 25000));
        skipOnlyMissingVoice(status);
        assertTrue(status.toString(), status.getBoolean("ok"));
        JSONObject value = status.getJSONObject("value");
        if (!value.optBoolean("available")) {
            String reason = "Real system English offline voice unavailable: " + value.optString("error")
                    + "; installed=" + value.optBoolean("installed") + "; engine=" + value.optString("engine");
            Log.w("UnipusRealTtsTest", reason);
            Assume.assumeTrue(reason, false);
        }
    }

    private static void skipOnlyMissingVoice(JSONObject response) throws Exception {
        JSONObject cause = response.optJSONObject("error");
        if (!response.optBoolean("ok") && cause != null && cause.optString("name").equals("DependencyUnavailableException")) {
            String reason = "Real system offline voice/data missing: " + cause.optString("message");
            Log.w("UnipusRealTtsTest", reason); Assume.assumeTrue(reason, false);
        }
    }

    static byte[] wav(int format, int bits) {
        return wav(format, bits, 16000, 1);
    }
    static byte[] wav(int format, int bits, int rate, int channels) {
        int frames = rate, width = bits / 8, align = width * channels;
        ByteBuffer out = ByteBuffer.allocate(44 + frames * align).order(ByteOrder.LITTLE_ENDIAN);
        out.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(out.capacity() - 8);
        out.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) format).putShort((short) channels);
        out.putInt(rate).putInt(rate * align).putShort((short) align).putShort((short) bits);
        out.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(frames * align);
        for (int i = 0; i < frames; i++) {
            for (int channel = 0; channel < channels; channel++) {
                double sample = Math.sin(i * 2 * Math.PI * (440 + channel * 220) / rate) * .5;
                if (format == 3) out.putFloat((float) sample);
                else if (bits == 8) out.put((byte) (128 + Math.round(sample * 127)));
                else if (bits == 16) out.putShort((short) Math.round(sample * 32767));
                else out.putInt((int) Math.round(sample * Integer.MAX_VALUE));
            }
        }
        return out.array();
    }

    private static final class FakeFactory implements AndroidAudioEngine.BackendFactory {
        volatile boolean installed = true, hold, hangInitialization;
        volatile int syntheses, speaks, stops, shutdowns;
        final CountDownLatch started = new CountDownLatch(1);
        @Override public List<AndroidAudioEngine.EngineInfo> engines() {
            return installed ? Collections.singletonList(new AndroidAudioEngine.EngineInfo("fixture.engine", "Test fixture", false)) : Collections.emptyList();
        }
        @Override public String defaultEngine() { return "fixture.engine"; }
        @Override public AndroidAudioEngine.SpeechBackend create(String engine, TextToSpeech.OnInitListener listener, UtteranceProgressListener progress) {
            if (!hangInitialization) listener.onInit(TextToSpeech.SUCCESS);
            return new AndroidAudioEngine.SpeechBackend() {
                @Override public int languageAvailability(Locale locale) { return locale.getLanguage().equals("en") ? TextToSpeech.LANG_COUNTRY_AVAILABLE : TextToSpeech.LANG_MISSING_DATA; }
                @Override public Set<Voice> voices() {
                    return new LinkedHashSet<>(Arrays.asList(
                            new Voice("offline", Locale.US, 400, 200, false, Collections.emptySet()),
                            new Voice("network", Locale.US, 400, 200, true, Collections.emptySet()),
                            new Voice("missing", Locale.US, 400, 200, false, Collections.singleton(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED))));
                }
                @Override public int setVoice(Voice voice) { return TextToSpeech.SUCCESS; }
                @Override public int setRate(float rate) { return TextToSpeech.SUCCESS; }
                @Override public int setPitch(float pitch) { return TextToSpeech.SUCCESS; }
                @Override public int synthesize(String text, Bundle parameters, File file, String id) {
                    syntheses++; started.countDown();
                    if (!hold) {
                        try { byte[] wave = wav(1, 16, 48000, 2); Files.write(file.toPath(), wave); progress.onAudioAvailable(id, Arrays.copyOfRange(wave, 44, wave.length)); progress.onStart(id); progress.onDone(id); }
                        catch (Exception error) { progress.onError(id, TextToSpeech.ERROR_OUTPUT); }
                    }
                    return TextToSpeech.SUCCESS;
                }
                @Override public int speak(String text, Bundle parameters, String id) {
                    speaks++; started.countDown(); if (!hold) { progress.onStart(id); progress.onDone(id); } return TextToSpeech.SUCCESS;
                }
                @Override public int stop() { stops++; return TextToSpeech.SUCCESS; }
                @Override public void shutdown() { shutdowns++; }
            };
        }
    }
}
