package io.github.unipus.helper;

import android.util.Base64;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Real, offline WebView recording: generated PCM -> virtual microphone -> MediaRecorder. */
@RunWith(AndroidJUnit4.class)
public final class AndroidRecordingTest {
    private static final String FIXTURE = "<!doctype html><html><head><title>Local recording fixture</title>"
            + "<script>window.__fixtureInstalledAtStart=!!window.__ucRec;</script>"
            + "</head><body>Offline WebAudio recording fixture</body></html>";
    private static final String CALLBACK_SCRIPT = "const source=arguments[0],values=Array.from(arguments).slice(1);"
            + "return new Promise((resolve,reject)=>{try{"
            + "const result=(new Function(source)).apply(window,values.concat([resolve]));"
            + "if(result&&typeof result.catch==='function')result.catch(reject);"
            + "}catch(error){reject(error);}});";

    private ActivityScenario<BrowserKernelHarnessActivity> scenario;
    private BrowserKernel kernel;
    private String injection;

    @Before public void openFixture() throws Exception {
        String bootstrap = readAsset("record_mic_bootstrap.js");
        injection = readAsset("record_mic.js");
        scenario = ActivityScenario.launch(BrowserKernelHarnessActivity.class);
        AtomicReference<BrowserKernel> reference = new AtomicReference<>();
        scenario.onActivity(activity -> {
            assertTrue("Update the device System WebView", WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER));
            assertTrue("Update the device System WebView", WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT));
            reference.set(activity.getKernel());
        });
        kernel = reference.get();
        value("add_document_script", "source", bootstrap);
        scenario.onActivity(activity -> activity.loadFixture(FIXTURE));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            JSONObject result = request("evaluate", "script",
                    "return document.title==='Local recording fixture'&&window.__fixtureInstalledAtStart===true;",
                    "timeoutMs", 1200);
            if (result.optBoolean("ok") && result.optBoolean("value")) return;
            Thread.sleep(60);
        }
        fail("The local fixture or document-start recording bootstrap did not become ready");
    }

    @After public void closeFixture() {
        if (scenario != null) scenario.close();
    }

    private static String readAsset(String name) throws Exception {
        try (InputStream input = InstrumentationRegistry.getInstrumentation().getTargetContext().getAssets().open(name);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int length;
            while ((length = input.read(buffer)) != -1) output.write(buffer, 0, length);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private JSONObject request(String op, Object... pairs) throws JSONException {
        JSONObject body = new JSONObject().put("op", op).put("timeoutMs", 10000);
        for (int i = 0; i < pairs.length; i += 2) body.put(String.valueOf(pairs[i]), pairs[i + 1]);
        return new JSONObject(kernel.callJson(body.toString()));
    }

    private Object value(String op, Object... pairs) throws JSONException {
        JSONObject result = request(op, pairs);
        assertTrue(result.toString(), result.optBoolean("ok"));
        return result.opt("value");
    }

    private JSONObject inject(String audio, double pad, double maxDuration) throws Exception {
        return (JSONObject) value("evaluate", "script", CALLBACK_SCRIPT, "args",
                new JSONArray().put(injection).put(audio).put(pad).put(maxDuration));
    }

    /** 1.5 seconds of 440 Hz, 16 kHz, signed 16-bit mono PCM in a real WAV container. */
    private static String sineWave() {
        int sampleRate = 16000;
        int frames = sampleRate * 3 / 2;
        ByteBuffer wav = ByteBuffer.allocate(44 + frames * 2).order(ByteOrder.LITTLE_ENDIAN);
        wav.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + frames * 2);
        wav.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16);
        wav.putShort((short) 1).putShort((short) 1).putInt(sampleRate).putInt(sampleRate * 2);
        wav.putShort((short) 2).putShort((short) 16);
        wav.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(frames * 2);
        for (int i = 0; i < frames; i++) wav.putShort((short) Math.round(16000 * Math.sin(2 * Math.PI * 440 * i / sampleRate)));
        return Base64.encodeToString(wav.array(), Base64.NO_WRAP);
    }

    @Test(timeout = 45000) public void recordsNonSilentAudioThroughMediaRecorderWithoutHardwareMicrophone() throws Exception {
        JSONObject injected = inject(sineWave(), 0.1, 1.0);
        assertTrue(injected.toString(), injected.getBoolean("ok"));
        assertEquals("running", injected.getString("state"));
        assertEquals(1.5, injected.getDouble("srcDur"), 0.03);
        assertEquals(1.1, injected.getDouble("dur"), 0.03);

        JSONObject recorded = (JSONObject) value("evaluate", "timeoutMs", 15000, "script",
                "return (async()=>{"
                        + "if(typeof MediaRecorder!=='function')throw new Error('MediaRecorder is unavailable');"
                        + "const api=window.__ucRec;const pending=navigator.mediaDevices.getUserMedia({audio:true});"
                        + "const promised=!!pending&&typeof pending.then==='function';const stream=await pending;"
                        + "const tracks=stream.getAudioTracks();let recorder;let timer;"
                        + "try{const mime=['audio/webm;codecs=opus','audio/webm','audio/mp4'].find(t=>MediaRecorder.isTypeSupported(t));"
                        + "if(!mime)throw new Error('No supported audio recording codec');"
                        + "recorder=new MediaRecorder(stream,{mimeType:mime});const chunks=[];"
                        + "const stopped=new Promise((resolve,reject)=>{"
                        + "recorder.ondataavailable=e=>{if(e.data&&e.data.size)chunks.push(e.data);};"
                        + "recorder.onstop=resolve;recorder.onerror=e=>reject(e.error||new Error('MediaRecorder failed'));"
                        + "timer=setTimeout(()=>reject(new Error('MediaRecorder stop timed out')),6000);});"
                        + "recorder.start(100);await new Promise(resolve=>setTimeout(resolve,1450));recorder.stop();await stopped;"
                        + "clearTimeout(timer);const blob=new Blob(chunks,{type:recorder.mimeType});"
                        + "const decoded=await api.ctx.decodeAudioData(await blob.arrayBuffer());"
                        + "const samples=decoded.getChannelData(0);let peak=0,power=0;"
                        + "for(let i=0;i<samples.length;i++){peak=Math.max(peak,Math.abs(samples[i]));power+=samples[i]*samples[i];}"
                        + "return {promised,tracks:tracks.length,bytes:blob.size,mime:blob.type,"
                        + "frames:samples.length,duration:decoded.duration,peak,rms:Math.sqrt(power/samples.length),"
                        + "state:api.ctx.state,calls:api.calls,secure:window.isSecureContext};"
                        + "}finally{clearTimeout(timer);if(recorder&&recorder.state!=='inactive')recorder.stop();tracks.forEach(track=>track.stop());}"
                        + "})();");
        assertTrue(recorded.toString(), recorded.getBoolean("promised"));
        assertTrue(recorded.toString(), recorded.getBoolean("secure"));
        assertEquals(1, recorded.getInt("tracks"));
        assertEquals(1, recorded.getInt("calls"));
        assertEquals("running", recorded.getString("state"));
        assertTrue(recorded.toString(), recorded.getInt("bytes") > 100);
        assertTrue(recorded.toString(), recorded.getInt("frames") > 1000);
        assertTrue("The encoded recording contains no actual waveform: " + recorded, recorded.getDouble("peak") > 0.15);
        assertTrue("The encoded recording is silent: " + recorded, recorded.getDouble("rms") > 0.02);
        assertTrue(recorded.toString(), recorded.getDouble("duration") >= 0.75 && recorded.getDouble("duration") < 4);
    }

    @Test(timeout = 30000) public void waitsForSuspendedContextToResumeAndReportsClosedContext() throws Exception {
        assertTrue(inject(sineWave(), 0, 0).getBoolean("ok"));
        assertEquals("suspended", value("evaluate", "script", "return window.__ucRec.ctx.suspend().then(()=>window.__ucRec.ctx.state);"));
        JSONObject resumed = inject("", 0, 0);
        assertTrue(resumed.toString(), resumed.getBoolean("ok"));
        assertEquals("running", resumed.getString("state"));
        assertEquals("closed", value("evaluate", "script", "return window.__ucRec.ctx.close().then(()=>window.__ucRec.ctx.state);"));
        JSONObject closed = inject("", 0, 0);
        assertFalse(closed.toString(), closed.getBoolean("ok"));
        assertTrue(closed.toString(), closed.getString("err").contains("AudioContext is closed"));
        JSONObject rejected = (JSONObject) value("evaluate", "script",
                "return navigator.mediaDevices.getUserMedia({audio:true}).then(()=>({rejected:false}),error=>({rejected:true,message:String(error)}));");
        assertTrue(rejected.toString(), rejected.getBoolean("rejected"));
    }

    @Test(timeout = 30000) public void reportsRejectedResumeInsteadOfClaimingSilentSuccess() throws Exception {
        assertTrue(inject("", 0, 0).getBoolean("ok"));
        value("evaluate", "script", "return window.__ucRec.ctx.suspend().then(()=>{"
                + "window.__ucRec.ctx.resume=()=>Promise.reject(new DOMException('fixture resume blocked','NotAllowedError'));return true;});");
        JSONObject rejected = inject(sineWave(), 0, 0);
        assertFalse(rejected.toString(), rejected.getBoolean("ok"));
        assertTrue(rejected.toString(), rejected.getString("err").contains("fixture resume blocked"));
        assertEquals("suspended", value("evaluate", "script", "return window.__ucRec.ctx.state;"));
    }

    @Test(timeout = 30000) public void rejectsBadAudioAndDoesNotReuseThePreviousBuffer() throws Exception {
        assertTrue(inject(sineWave(), 0, 0).getBoolean("ok"));
        JSONObject invalid = inject(Base64.encodeToString("not an audio file".getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP), 0, 0);
        assertFalse(invalid.toString(), invalid.getBoolean("ok"));
        assertFalse(invalid.toString(), invalid.getString("err").isEmpty());
        assertEquals(Boolean.TRUE, value("evaluate", "script", "return window.__ucRec.buf===null&&window.__ucRec.dur===0&&window.__ucRec.srcDur===0;"));
        JSONObject badPadding = inject(sineWave(), -1, 0);
        assertFalse(badPadding.toString(), badPadding.getBoolean("ok"));
        assertTrue(badPadding.toString(), badPadding.getString("err").contains("finite non-negative"));
    }

    @Test(timeout = 30000) public void reportsMissingBootstrapAndUnsupportedWebAudio() throws Exception {
        value("evaluate", "script", "window.AudioContext=undefined;window.webkitAudioContext=undefined;return true;");
        JSONObject unsupported = inject("", 0, 0);
        assertFalse(unsupported.toString(), unsupported.getBoolean("ok"));
        assertTrue(unsupported.toString(), unsupported.getString("err").contains("Web Audio API is unavailable"));
        value("evaluate", "script", "window.__ucRec=undefined;return true;");
        JSONObject missing = inject("", 0, 0);
        assertFalse(missing.toString(), missing.getBoolean("ok"));
        assertEquals("bootstrap missing", missing.getString("err"));
    }
}
