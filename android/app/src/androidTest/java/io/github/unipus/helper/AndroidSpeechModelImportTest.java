package io.github.unipus.helper;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.*;

/** Offline import validation; importing a real model requires an explicit private-cache path. */
@RunWith(AndroidJUnit4.class)
public final class AndroidSpeechModelImportTest {
    private static final String IMPORT_ARGUMENT = "importAudioModelFrom";
    private static final long MODEL_BYTES = 77704715L;
    private static final String MODEL_SHA256 = "921e4cf8686fdd993dcd081a5da5b6c365bfde1162e72b08d75ac75289920b1f";
    private static Context context;
    private static AndroidSpeechNative speech;
    private static File model;
    private File directory;
    private final List<File> outsideFixtures = new ArrayList<>();

    @BeforeClass public static void openNativeEngine() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        speech = new AndroidSpeechNative(context);
        model = new File(context.getFilesDir(), "speech-models/ggml-tiny.en.bin").getCanonicalFile();
    }

    @AfterClass public static void closeNativeEngine() throws Exception {
        if (speech == null) return;
        try { value("audio_release", "timeoutMs", 10000); }
        finally { speech.destroy(); }
    }

    @Before public void createFixtureDirectory() throws Exception {
        directory = new File(context.getCacheDir(), "model-import-test-" + System.nanoTime()).getCanonicalFile();
        assertTrue(directory.mkdirs());
    }

    @After public void removeOnlyGeneratedFixtures() throws Exception {
        for (File fixture : outsideFixtures) if (fixture.exists()) assertTrue(fixture.delete());
        if (directory != null && directory.exists()) removeFixtureTree(directory);
    }

    private void removeFixtureTree(File file) throws Exception {
        String root = directory.getCanonicalPath(), actual = file.getCanonicalPath();
        assertTrue("Fixture cleanup escaped its own directory", actual.equals(root) || actual.startsWith(root + File.separator));
        File[] children = file.listFiles();
        if (children != null) for (File child : children) removeFixtureTree(child);
        assertTrue("Cannot remove generated fixture: " + file, file.delete());
    }

    private static JSONObject request(String operation, Object... pairs) throws Exception {
        JSONObject request = new JSONObject().put("op", operation).put("timeoutMs", 120000);
        for (int i = 0; i < pairs.length; i += 2) request.put(String.valueOf(pairs[i]), pairs[i + 1]);
        return new JSONObject(speech.callJson(request.toString()));
    }

    private static JSONObject value(String operation, Object... pairs) throws Exception {
        JSONObject response = request(operation, pairs);
        assertTrue(operation + " failed: " + response, response.optBoolean("ok"));
        return response.getJSONObject("value");
    }

    private static JSONObject failure(JSONObject response, String name) throws Exception {
        assertFalse(response.toString(), response.getBoolean("ok"));
        JSONObject error = response.getJSONObject("error");
        assertEquals(response.toString(), name, error.getString("name"));
        assertFalse(response.toString(), error.getString("message").isEmpty());
        return error;
    }

    private static JSONObject capabilities() throws Exception {
        JSONObject state = value("audio_capabilities");
        assertTrue("The packaged native speech library is unavailable: " + state, state.getBoolean("whisperAvailable"));
        return state;
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

    private static final class ModelSnapshot {
        final boolean existed, ready;
        final long bytes;
        final String digest;

        ModelSnapshot() throws Exception {
            ready = capabilities().getBoolean("modelReady");
            existed = model.exists();
            bytes = model.length();
            digest = existed ? sha256(model) : "";
        }

        void assertUnchanged() throws Exception {
            assertEquals("A rejected import changed model existence", existed, model.exists());
            if (existed) {
                assertEquals("A rejected import changed model size", bytes, model.length());
                assertEquals("A rejected import corrupted the existing model", digest, sha256(model));
            }
            JSONObject state = capabilities();
            assertEquals("A rejected import changed model readiness", ready, state.getBoolean("modelReady"));
            if (!existed) {
                assertFalse("A bad import invented a ready model", state.getBoolean("modelReady"));
                assertNotEquals("ready", state.getString("modelState"));
            }
        }
    }

    private void rejectsCandidateWithoutChangingModel(File source, String name) throws Exception {
        ModelSnapshot before = new ModelSnapshot();
        long length = source.length();
        String digest = sha256(source);
        failure(request("audio_import_model", "inputPath", source.getPath()), name);
        assertTrue("Failed import deleted its caller-owned source", source.isFile());
        assertEquals(length, source.length());
        assertEquals("Failed import modified its source", digest, sha256(source));
        before.assertUnchanged();
    }

    @Test(timeout = 120000) public void rejectsWrongModelLengthWithoutChangingExistingModel() throws Exception {
        File invalid = new File(directory, "wrong-length.bin");
        try (FileOutputStream output = new FileOutputStream(invalid)) {
            output.write("invalid model".getBytes(StandardCharsets.US_ASCII));
        }
        rejectsCandidateWithoutChangingModel(invalid, "ModelValidationException");
    }

    @Test(timeout = 180000) public void rejectsExactLengthWithWrongChecksumWithoutChangingExistingModel() throws Exception {
        File invalid = new File(directory, "wrong-checksum.bin");
        try (RandomAccessFile output = new RandomAccessFile(invalid, "rw")) { output.setLength(MODEL_BYTES); }
        assertEquals(MODEL_BYTES, invalid.length());
        assertNotEquals("The invalid fixture unexpectedly matches the real model", MODEL_SHA256, sha256(invalid));
        rejectsCandidateWithoutChangingModel(invalid, "ModelValidationException");
    }

    @Test(timeout = 120000) public void rejectsInputOutsideFilesOrCacheIncludingTraversal() throws Exception {
        ModelSnapshot before = new ModelSnapshot();
        File outside = new File(context.getNoBackupFilesDir(), directory.getName() + ".bin");
        outsideFixtures.add(outside);
        try (FileOutputStream output = new FileOutputStream(outside)) { output.write("private-boundary-fixture".getBytes(StandardCharsets.US_ASCII)); }
        String original = sha256(outside);
        String traversal = new File(context.getFilesDir(), "../" + context.getNoBackupFilesDir().getName() + "/" + outside.getName()).getPath();
        assertEquals(outside.getCanonicalPath(), new File(traversal).getCanonicalPath());
        for (String path : new String[]{outside.getPath(), traversal, "", "relative.bin"}) {
            failure(request("audio_import_model", "inputPath", path), "InvalidArgumentException");
        }
        assertEquals(original, sha256(outside));
        before.assertUnchanged();
    }

    @Test(timeout = 240000) public void explicitlyRequestedRealModelImportVerifiesReadyAndPreservesSource() throws Exception {
        String requested = InstrumentationRegistry.getArguments().getString(IMPORT_ARGUMENT, "").trim();
        Assume.assumeTrue("No real model is imported unless -e " + IMPORT_ARGUMENT + " <app-private-cache-path> is supplied.", !requested.isEmpty());
        assertTrue("The import argument must be an absolute path", new File(requested).isAbsolute());
        File source = new File(requested).getCanonicalFile();
        assertTrue("The explicit import fixture must be inside app-private cache",
                source.getPath().startsWith(context.getCacheDir().getCanonicalPath() + File.separator));
        assertTrue("The explicit model source does not exist", source.isFile());
        assertEquals(MODEL_BYTES, source.length());
        assertEquals("The supplied model source does not match the pinned tiny.en model", MODEL_SHA256, sha256(source));
        boolean alreadyReady = capabilities().getBoolean("modelReady");
        JSONObject imported = value("audio_import_model", "inputPath", source.getPath(), "timeoutMs", 180000);
        assertTrue(imported.toString(), imported.getBoolean("modelReady"));
        assertEquals(model.getCanonicalPath(), new File(imported.getString("modelPath")).getCanonicalPath());
        if (alreadyReady) assertTrue("An already verified model should be retained", imported.getBoolean("unchanged"));
        long modified = model.lastModified();
        JSONObject repeated = value("audio_import_model", "inputPath", source.getPath(), "timeoutMs", 180000);
        assertTrue(repeated.toString(), repeated.getBoolean("modelReady"));
        assertTrue("Reimport must retain the already verified model", repeated.getBoolean("unchanged"));
        assertEquals("Reimport unnecessarily replaced the verified model", modified, model.lastModified());
        assertTrue(model.isFile());
        assertEquals(MODEL_BYTES, model.length());
        assertEquals(MODEL_SHA256, sha256(model));
        assertTrue("Import must retain its caller-owned source", source.isFile());
        assertEquals(MODEL_BYTES, source.length());
        assertEquals(MODEL_SHA256, sha256(source));
        JSONObject state = capabilities();
        assertTrue(state.toString(), state.getBoolean("modelReady"));
        assertEquals("ready", state.getString("modelState"));
        assertEquals(MODEL_BYTES, state.getLong("modelBytes"));
    }
}
