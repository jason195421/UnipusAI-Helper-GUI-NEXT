package io.github.unipus.helper;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Real JNI/FFmpeg/whisper.cpp tests. No RPC here downloads a model or calls remote ASR. */
@RunWith(AndroidJUnit4.class)
public final class AndroidSpeechNativeTest {
    private static final long MODEL_BYTES = 77704715L;
    private static final String MODEL_SHA256 = "921e4cf8686fdd993dcd081a5da5b6c365bfde1162e72b08d75ac75289920b1f";
    private static Context context;
    private static AndroidSpeechNative speech;
    private File directory;
    private final List<File> outsideFixtures = new ArrayList<>();

    @BeforeClass public static void openNativeEngine() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        speech = new AndroidSpeechNative(context);
    }

    @AfterClass public static void closeNativeEngine() throws Exception {
        if (speech == null) return;
        try {
            JSONObject result = new JSONObject(speech.callJson("{\"op\":\"audio_release\",\"timeoutMs\":10000}"));
            assertTrue(result.toString(), result.optBoolean("ok"));
        } finally { speech.destroy(); }
    }

    @Before public void openFixtureDirectory() throws Exception {
        directory = new File(context.getCacheDir(), "speech-instrumentation-" + System.nanoTime()).getCanonicalFile();
        assertTrue(directory.mkdirs());
    }

    @After public void closeNative() throws Exception {
        try {
            if (speech != null) {
                JSONObject result = request("audio_release", "timeoutMs", 10000);
                assertTrue(result.toString(), result.optBoolean("ok"));
            }
        } finally {
            for (File fixture : outsideFixtures) if (fixture.exists()) assertTrue(fixture.delete());
            if (directory != null && directory.exists()) deleteFixtureTree(directory);
        }
    }

    private void deleteFixtureTree(File file) throws Exception {
        String root = directory.getCanonicalPath();
        String actual = file.getCanonicalPath();
        assertTrue("Cleanup escaped its own fixture directory", actual.equals(root) || actual.startsWith(root + File.separator));
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteFixtureTree(child);
        assertTrue("Cannot remove fixture: " + file, file.delete());
    }

    private JSONObject request(String op, Object... pairs) throws JSONException {
        JSONObject body = new JSONObject().put("op", op).put("timeoutMs", 120000);
        for (int i = 0; i < pairs.length; i += 2) body.put(String.valueOf(pairs[i]), pairs[i + 1]);
        return new JSONObject(speech.callJson(body.toString()));
    }

    private JSONObject value(String op, Object... pairs) throws JSONException {
        JSONObject result = request(op, pairs);
        assertTrue(result.toString(), result.optBoolean("ok"));
        return result.getJSONObject("value");
    }

    private static JSONObject failure(JSONObject response, String name) throws JSONException {
        assertFalse(response.toString(), response.getBoolean("ok"));
        JSONObject error = response.getJSONObject("error");
        assertEquals(response.toString(), name, error.getString("name"));
        assertFalse(response.toString(), error.getString("message").isEmpty());
        return error;
    }

    private File copyAsset(String name) throws Exception {
        return copyAsset(name, new File(directory, name));
    }

    private File copyAsset(String name, File destination) throws Exception {
        // Speech fixtures belong to the test APK; shared injection scripts belong to the main APK.
        try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("speech-test/" + name);
             FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[8192];
            int length;
            while ((length = input.read(buffer)) != -1) output.write(buffer, 0, length);
        }
        assertTrue(destination.isFile() && destination.length() > 44);
        return destination;
    }

    private static byte[] read(File file) throws Exception {
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int length;
            while ((length = input.read(buffer)) != -1) output.write(buffer, 0, length);
            return output.toByteArray();
        }
    }

    private static String hash(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[65536];
            int length;
            while ((length = input.read(buffer)) != -1) digest.update(buffer, 0, length);
        }
        StringBuilder value = new StringBuilder();
        for (byte item : digest.digest()) value.append(String.format(Locale.ROOT, "%02x", item & 255));
        return value.toString();
    }

    private JSONObject capabilities() throws Exception {
        JSONObject result = value("audio_capabilities");
        assertTrue("The packaged JNI Whisper library is unavailable: " + result, result.getBoolean("whisperAvailable"));
        assertTrue("The packaged FFmpeg libraries are unavailable: " + result, result.getBoolean("ffmpegAvailable"));
        assertEquals("tiny.en", result.getString("modelName"));
        assertEquals(MODEL_BYTES, result.getLong("modelBytes"));
        return result;
    }

    @Test(timeout = 30000) public void reportsRealNativeCapabilitiesWithoutDownloadingModel() throws Exception {
        File model = new File(context.getFilesDir(), "speech-models/ggml-tiny.en.bin");
        boolean existed = model.exists();
        long size = model.length(), modified = model.lastModified();
        JSONObject result = capabilities();
        assertTrue(result.has("modelReady"));
        assertTrue(result.has("modelState"));
        assertTrue(result.getDouble("modelProgress") >= 0 && result.getDouble("modelProgress") <= 1);
        JSONObject versions = result.getJSONObject("versions");
        assertFalse(versions.getString("whisperVersion").isEmpty());
        assertFalse(versions.getString("ffmpegVersion").isEmpty());
        assertEquals("Capability lookup must not create a model", existed, model.exists());
        assertEquals(size, model.length());
        assertEquals(modified, model.lastModified());
    }

    private void decodesRealAudio(String name) throws Exception {
        capabilities();
        File input = copyAsset(name);
        File output = new File(directory, name + ".normalized.wav");
        JSONObject result = value("audio_decode", "inputPath", input.getPath(), "outputPath", output.getPath());
        assertEquals(output.getCanonicalPath(), result.getString("path"));
        assertEquals(16000, result.getInt("sampleRate"));
        assertEquals(1, result.getInt("channels"));
        assertEquals(16, result.getInt("bitsPerSample"));
        assertEquals("wav", result.getString("format"));
        long frames = result.getLong("samples");
        assertTrue(result.toString(), frames > 16000L * 5 && frames < 16000L * 30);
        assertEquals(frames / 16000.0, result.getDouble("seconds"), 0.0001);
        byte[] wav = read(output);
        assertEquals(44 + frames * 2, wav.length);
        ByteBuffer header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals("RIFF", new String(wav, 0, 4, StandardCharsets.US_ASCII));
        assertEquals("WAVEfmt ", new String(wav, 8, 8, StandardCharsets.US_ASCII));
        assertEquals("data", new String(wav, 36, 4, StandardCharsets.US_ASCII));
        assertEquals(1, header.getShort(20));
        assertEquals(1, header.getShort(22));
        assertEquals(16000, header.getInt(24));
        assertEquals(16, header.getShort(34));
        assertEquals(frames * 2, header.getInt(40));
        double power = 0, peak = 0;
        for (int offset = 44; offset < wav.length; offset += 2) {
            double sample = header.getShort(offset) / 32768.0;
            power += sample * sample;
            peak = Math.max(peak, Math.abs(sample));
        }
        assertTrue("FFmpeg output is silent for " + name, peak > 0.01);
        assertTrue("FFmpeg output contains no speech waveform for " + name, Math.sqrt(power / frames) > 0.001);
    }

    @Test(timeout = 120000) public void decodesWavWithRealFfmpeg() throws Exception { decodesRealAudio("jfk.wav"); }
    @Test(timeout = 120000) public void decodesMp3WithRealFfmpeg() throws Exception { decodesRealAudio("jfk.mp3"); }
    @Test(timeout = 120000) public void decodesM4aWithRealFfmpeg() throws Exception { decodesRealAudio("jfk.m4a"); }

    @Test(timeout = 30000) public void rejectsExistingFilesAndOutputPathsOutsideAllowedPrivateRoots() throws Exception {
        capabilities();
        File input = copyAsset("jfk.wav");
        File outsideInput = new File(context.getNoBackupFilesDir(), directory.getName() + "-input.wav");
        File outsideOutput = new File(context.getNoBackupFilesDir(), directory.getName() + "-output.wav");
        outsideFixtures.add(outsideInput);
        outsideFixtures.add(outsideOutput);
        copyAsset("jfk.wav", outsideInput);
        String traversalInput = new File(context.getFilesDir(), "../" + context.getNoBackupFilesDir().getName() + "/" + outsideInput.getName()).getPath();
        String traversalOutput = new File(context.getFilesDir(), "../" + context.getNoBackupFilesDir().getName() + "/" + outsideOutput.getName()).getPath();
        assertEquals(outsideInput.getCanonicalPath(), new File(traversalInput).getCanonicalPath());
        File output = new File(directory, "should-not-exist.wav");
        for (String path : new String[]{outsideInput.getPath(), traversalInput}) {
            failure(request("audio_decode", "inputPath", path, "outputPath", output.getPath()), "InvalidArgumentException");
            assertFalse(output.exists());
        }
        failure(request("audio_decode", "inputPath", input.getPath(), "outputPath", traversalOutput), "InvalidArgumentException");
        assertFalse("The bridge wrote outside files/cache", outsideOutput.exists());
    }

    @Test(timeout = 30000) public void rejectsBadAudioAndDoesNotReplaceExistingOutput() throws Exception {
        capabilities();
        File invalid = new File(directory, "damaged.wav");
        byte[] sentinel = "keep-existing-output".getBytes(StandardCharsets.US_ASCII);
        try (FileOutputStream output = new FileOutputStream(invalid)) { output.write("not an audio file".getBytes(StandardCharsets.US_ASCII)); }
        File destination = new File(directory, "existing.wav");
        try (FileOutputStream output = new FileOutputStream(destination)) { output.write(sentinel); }
        failure(request("audio_decode", "inputPath", invalid.getPath(), "outputPath", destination.getPath()), "AudioProcessingException");
        assertArrayEquals(sentinel, read(destination));
        File[] temporary = directory.listFiles((parent, name) -> name.startsWith("normalized-"));
        assertNotNull(temporary);
        assertEquals("Failed decoding leaked its temporary file", 0, temporary.length);
    }

    @Test(timeout = 30000) public void rejectsEmptyRelativeDirectoryAndSameFilePaths() throws Exception {
        capabilities();
        File input = copyAsset("jfk.wav"), output = new File(directory, "normalized.wav");
        String original = hash(input);
        for (String path : new String[]{"", "relative.wav"}) {
            failure(request("audio_decode", "inputPath", path, "outputPath", output.getPath()), "InvalidArgumentException");
            failure(request("audio_decode", "inputPath", input.getPath(), "outputPath", path), "InvalidArgumentException");
        }
        failure(request("audio_decode", "inputPath", input.getPath(), "outputPath", input.getPath()), "InvalidArgumentException");
        failure(request("audio_decode", "inputPath", input.getPath(), "outputPath", directory.getPath()), "InvalidArgumentException");
        File empty = new File(directory, "empty.wav");
        assertTrue(empty.createNewFile());
        failure(request("audio_decode", "inputPath", empty.getPath(), "outputPath", output.getPath()), "InvalidArgumentException");
        assertEquals("Invalid requests modified their input file", original, hash(input));
        assertFalse(output.exists());
    }

    @Test(timeout = 30000) public void rejectsUiThreadCallsAndMalformedRequests() throws Exception {
        AtomicReference<String> result = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> result.set(speech.callJson("{\"op\":\"audio_capabilities\"}")));
        JSONObject error = failure(new JSONObject(result.get()), "WebDriverException");
        assertTrue(error.getString("message").contains("worker"));
        failure(new JSONObject(speech.callJson("{")), "InvalidArgumentException");
        failure(request("unknown_fixture_operation"), "UnsupportedCommandException");
    }

    @Test(timeout = 300000) public void transcribesActualEnglishWithVerifiedPrivateWhisperModel() throws Exception {
        JSONObject capabilities = capabilities();
        if (!capabilities.getBoolean("modelReady")) {
            assertEquals("Only an absent model may skip real inference; an invalid model must fail", "missing", capabilities.getString("modelState"));
        }
        Assume.assumeTrue("Install the checked tiny.en model in the app-private speech-models directory: " + capabilities,
                capabilities.getBoolean("modelReady"));
        File model = new File(context.getFilesDir(), "speech-models/ggml-tiny.en.bin");
        assertTrue(model.isFile());
        assertEquals(MODEL_BYTES, model.length());
        assertEquals("The ASR fixture requires the real, verified tiny.en model", MODEL_SHA256, hash(model));
        File input = copyAsset("jfk.wav");
        JSONObject result = value("audio_transcribe", "inputPath", input.getPath(), "language", "en", "timeoutMs", 240000);
        String text = result.getString("text").trim();
        assertFalse("Native Whisper returned an empty transcript", text.isEmpty());
        assertTrue("Whisper did not recognize the known JFK recording: " + text, text.toLowerCase(Locale.ROOT).contains("country"));
        assertEquals(16000, result.getInt("sampleRate"));
        assertTrue(result.toString(), result.getDouble("seconds") > 5);
    }
}
