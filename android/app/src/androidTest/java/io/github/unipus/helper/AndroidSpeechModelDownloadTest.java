package io.github.unipus.helper;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;

import static org.junit.Assert.*;

/** Opt-in integration test for a fresh, verified model download on the device. */
@RunWith(AndroidJUnit4.class)
public final class AndroidSpeechModelDownloadTest {
    private static final String ARGUMENT = "prepareAudioModel";
    private static final String MODEL_FILE = "ggml-tiny.en.bin";
    private static final long MODEL_BYTES = 77704715L;
    private static final String MODEL_SHA256 = "921e4cf8686fdd993dcd081a5da5b6c365bfde1162e72b08d75ac75289920b1f";
    private static final long DOWNLOAD_TIMEOUT_MS = 20L * 60 * 1000;

    @Test(timeout = 22L * 60 * 1000)
    public void explicitlyRequestedDownloadProducesTheVerifiedPrivateModel() throws Exception {
        String requested = InstrumentationRegistry.getArguments().getString(ARGUMENT, "false");
        Assume.assumeTrue("Offline by default. Pass -e " + ARGUMENT + " true to download and verify the model.",
                "true".equalsIgnoreCase(requested));

        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File model = new File(new File(context.getFilesDir(), "speech-models"), MODEL_FILE).getCanonicalFile();
        assertFalse("A preinstalled model cannot prove that the device download flow works: " + model,
                model.exists());

        AndroidSpeechNative speech = new AndroidSpeechNative(context);
        try {
            JSONObject before = value(speech, "audio_capabilities");
            assertTrue("Native Whisper library is missing: " + before, before.getBoolean("whisperAvailable"));
            assertFalse("The model unexpectedly appeared before the download request", before.getBoolean("modelReady"));
            assertEquals("missing", before.getString("modelState"));

            JSONObject prepared = value(speech, "audio_prepare_model", "timeoutMs", DOWNLOAD_TIMEOUT_MS);
            assertTrue("The helper did not confirm a successful model download: " + prepared,
                    prepared.getBoolean("modelReady"));
            assertEquals("The helper returned a path outside the expected app-private model location",
                    model.getCanonicalPath(), new File(prepared.getString("modelPath")).getCanonicalPath());
            assertTrue("The downloaded model was not saved to app-private storage", model.isFile());
            assertEquals("The downloaded model size does not match tiny.en", MODEL_BYTES, model.length());
            assertEquals("The downloaded model SHA-256 does not match the pinned tiny.en model",
                    MODEL_SHA256, sha256(model));
            assertFalse("Successful download left an incomplete model file",
                    new File(model.getParentFile(), MODEL_FILE + ".partial").exists());

            JSONObject after = value(speech, "audio_capabilities");
            assertTrue("Capabilities did not report the downloaded model as ready: " + after,
                    after.getBoolean("modelReady"));
            assertEquals("ready", after.getString("modelState"));
            assertEquals(1.0, after.getDouble("modelProgress"), 0.000001);
            assertEquals(MODEL_BYTES, after.getLong("modelBytes"));
        } finally {
            speech.destroy();
        }
    }

    private static JSONObject value(AndroidSpeechNative speech, String operation, Object... pairs) throws Exception {
        JSONObject request = new JSONObject().put("op", operation).put("timeoutMs", 10000);
        for (int i = 0; i < pairs.length; i += 2) request.put(String.valueOf(pairs[i]), pairs[i + 1]);
        JSONObject response = new JSONObject(speech.callJson(request.toString()));
        assertTrue(operation + " failed: " + response, response.optBoolean("ok"));
        return response.getJSONObject("value");
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int length;
            while ((length = input.read(buffer)) != -1) digest.update(buffer, 0, length);
        }
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest()) result.append(String.format(Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }
}
