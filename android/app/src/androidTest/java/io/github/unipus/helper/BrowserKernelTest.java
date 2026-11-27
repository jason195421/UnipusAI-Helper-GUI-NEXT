package io.github.unipus.helper;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.util.Base64;
import android.webkit.WebView;
import android.webkit.WebSettings;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

/** Real WebView instrumentation: local srcdoc content, no login or network. */
@RunWith(AndroidJUnit4.class)
public final class BrowserKernelTest {
    private static final String FIXTURE = "<!doctype html><html><head>"
            + "<title>Unipus local fixture</title>"
            + "<style>html,body{margin:0;min-height:100vh;background:#123456;color:white}"
            + "button{height:48px}iframe{display:block;width:280px;height:100px}</style>"
            + "</head><body><button id='target'>Fixture button</button>"
            + "<iframe id='remote' sandbox='allow-scripts' "
            + "srcdoc=\"<!doctype html><html><body><p id='inside'>opaque child</p></body></html>\"></iframe>"
            + "</body></html>";
    private ActivityScenario<BrowserKernelHarnessActivity> scenario;
    private BrowserKernel kernel;

    @Before public void openFixture() throws Exception {
        scenario = ActivityScenario.launch(BrowserKernelHarnessActivity.class);
        AtomicReference<BrowserKernel> reference = new AtomicReference<>();
        scenario.onActivity(activity -> {
            assertTrue("Update the device System WebView", WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER));
            assertTrue("Update the device System WebView", WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT));
            reference.set(activity.getKernel());
            activity.loadFixture(FIXTURE);
        });
        kernel = reference.get();
        awaitTrue("return document.title === 'Unipus local fixture' && !!document.getElementById('remote');");
    }

    private void awaitTrue(String script) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            JSONObject ready = request("evaluate", "script", script, "timeoutMs", 1200);
            if (ready.optBoolean("ok") && ready.optBoolean("value")) return;
            Thread.sleep(60);
        }
        fail("The real WebView fixture / per-frame bridge did not become ready");
    }

    @After public void closeFixture() {
        if (scenario != null) scenario.close();
    }

    private JSONObject request(String op, Object... pairs) throws JSONException {
        JSONObject body = new JSONObject().put("op", op).put("timeoutMs", 6000);
        for (int i = 0; i < pairs.length; i += 2) body.put(String.valueOf(pairs[i]), pairs[i + 1]);
        return new JSONObject(kernel.callJson(body.toString()));
    }

    private Object value(String op, Object... pairs) throws JSONException {
        JSONObject result = request(op, pairs);
        assertTrue(result.toString(), result.optBoolean("ok"));
        return result.opt("value");
    }

    @Test(timeout = 30000) public void usesDesktopUaWithTheInstalledChromeVersionAndPinchZoom() {
        scenario.onActivity(activity -> {
            WebSettings settings = kernel.getCurrentView().getSettings();
            String userAgent = settings.getUserAgentString();
            assertTrue(userAgent, userAgent.contains("(X11; Linux x86_64)"));
            assertFalse(userAgent, userAgent.contains("Android"));
            assertFalse(userAgent, userAgent.contains("Mobile"));
            assertFalse(userAgent, userAgent.contains("Version/4.0"));
            Pattern chrome = Pattern.compile("Chrome/([^ ]+)");
            Matcher actual = chrome.matcher(userAgent);
            Matcher original = chrome.matcher(WebSettings.getDefaultUserAgent(activity));
            assertTrue("The installed WebView must identify its real Chrome version", original.find());
            assertTrue(actual.find());
            assertEquals(original.group(1), actual.group(1));
            assertTrue(settings.supportZoom());
            assertTrue(settings.getBuiltInZoomControls());
            assertFalse(settings.getDisplayZoomControls());
        });
    }

    @Test(timeout = 30000) public void evaluatesPromisesAndRejectsUiThreadWaits() throws Exception {
        JSONObject result = (JSONObject) value("evaluate", "script",
                "return new Promise(resolve => setTimeout(() => resolve({answer: arguments[0] + 1}), 30));",
                "args", new JSONArray().put(41));
        assertEquals(42, result.getInt("answer"));
        AtomicReference<String> blocked = new AtomicReference<>();
        scenario.onActivity(activity -> blocked.set(kernel.callJson("{\"op\":\"url\"}")));
        JSONObject misuse = new JSONObject(blocked.get());
        assertFalse(misuse.getBoolean("ok"));
        assertTrue(misuse.getJSONObject("error").getString("message").contains("worker thread"));
    }

    @Test(timeout = 30000) public void hydratesElementsAndRejectsDetachedHandles() throws Exception {
        JSONObject element = (JSONObject) value("evaluate", "script", "return document.getElementById('target');");
        assertTrue(element.has("__unipus_element__"));
        assertEquals(value("root_frame"), element.getString("frame"));
        assertEquals("Fixture button", value("evaluate", "script", "return arguments[0].textContent;", "args", new JSONArray().put(element)));
        value("evaluate", "script", "arguments[0].remove(); return true;", "args", new JSONArray().put(element));
        JSONObject stale = request("evaluate", "script", "return arguments[0].textContent;", "args", new JSONArray().put(element));
        assertFalse(stale.getBoolean("ok"));
        assertEquals("StaleElementReferenceException", stale.getJSONObject("error").getString("name"));
    }

    @Test(timeout = 30000) public void executesInsideOpaqueSandboxFrameViaProbe() throws Exception {
        assertEquals(Boolean.TRUE, value("evaluate", "script",
                "try { void document.getElementById('remote').contentWindow.document.body; return false; } catch (_) { return true; }"));
        String probe = "return new Promise((resolve,reject) => {"
                + "const child=document.getElementById('remote').contentWindow, nonce='probe-'+Math.random();"
                + "let interval,timeout; const listener=event=>{const d=event.data;"
                + "if(event.source===child&&d&&d.__unipus_frame_reply__===nonce){clearInterval(interval);clearTimeout(timeout);"
                + "window.removeEventListener('message',listener);resolve(d.frame);}};"
                + "window.addEventListener('message',listener);"
                + "const send=()=>child.postMessage({__unipus_frame_probe__:nonce},'*');"
                + "interval=setInterval(send,50);timeout=setTimeout(()=>{clearInterval(interval);window.removeEventListener('message',listener);reject(new Error('frame probe timeout'));},4000);send();});";
        String frame = (String) value("evaluate", "script", probe);
        assertNotEquals(value("root_frame"), frame);
        assertEquals("opaque child", value("evaluate", "frame", frame, "script", "return document.getElementById('inside').textContent;"));
        JSONObject element = (JSONObject) value("evaluate", "frame", frame, "script", "return document.getElementById('inside');");
        assertEquals(frame, element.getString("frame"));
        JSONObject wrongContext = request("evaluate", "script", "return arguments[0];", "args", new JSONArray().put(element));
        assertFalse(wrongContext.getBoolean("ok"));
        assertEquals("StaleElementReferenceException", wrongContext.getJSONObject("error").getString("name"));
    }

    @Test(timeout = 30000) public void capturesRealViewportPng() throws Exception {
        CountDownLatch visual = new CountDownLatch(1);
        int[] viewport = new int[2];
        scenario.onActivity(activity -> {
            WebView view = kernel.getCurrentView();
            viewport[0] = view.getWidth(); viewport[1] = view.getHeight();
            view.postVisualStateCallback(1, new WebView.VisualStateCallback() {
                @Override public void onComplete(long id) { visual.countDown(); }
            });
        });
        assertTrue("WebView did not commit its visual state", visual.await(5, TimeUnit.SECONDS));
        byte[] bytes = Base64.decode((String) value("screenshot"), Base64.DEFAULT);
        assertArrayEquals(new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10}, Arrays.copyOf(bytes, 8));
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        assertNotNull(bitmap);
        try {
            assertEquals(viewport[0], bitmap.getWidth()); assertEquals(viewport[1], bitmap.getHeight());
            int pixel = bitmap.getPixel(bitmap.getWidth() - 8, bitmap.getHeight() - 8);
            assertTrue("Screenshot is not the actual fixture page", Math.abs(Color.red(pixel) - 18)
                    + Math.abs(Color.green(pixel) - 52) + Math.abs(Color.blue(pixel) - 86) < 40);
        } finally { bitmap.recycle(); }
    }

    @Test(timeout = 30000) public void readsRealHttpOnlyCookiesWithoutInventingMetadata() throws Exception {
        String name = "unipus_fixture_" + System.nanoTime();
        JSONObject cookie = new JSONObject().put("name", name).put("value", "native-cookie-value")
                .put("httpOnly", true).put("secure", true).put("path", "/");
        value("add_cookie", "cookie", cookie);
        try {
            assertEquals(Boolean.FALSE, value("evaluate", "script", "return document.cookie.includes(arguments[0]+'=');", "args", new JSONArray().put(name)));
            JSONArray cookies = (JSONArray) value("cookies");
            JSONObject found = null;
            for (int i = 0; i < cookies.length(); i++) if (name.equals(cookies.getJSONObject(i).optString("name"))) found = cookies.getJSONObject(i);
            assertNotNull("Native CookieManager did not return the HttpOnly cookie", found);
            assertEquals("native-cookie-value", found.getString("value"));
            assertFalse(found.has("expiry")); assertFalse(found.has("domain")); assertFalse(found.has("httpOnly"));
        } finally {
            value("add_cookie", "cookie", new JSONObject().put("name", name).put("value", "").put("secure", true).put("httpOnly", true).put("path", "/").put("expiry", 0));
        }
    }

    @Test(timeout = 30000) public void managesWindowsAndCanRestartAfterQuit() throws Exception {
        String first = (String) value("current_window");
        String second = (String) value("new_window");
        assertNotEquals(first, second);
        assertEquals(2, ((JSONArray) value("windows")).length());
        value("switch_window", "window", first);
        assertEquals(first, value("current_window"));
        value("close_window", "window", second);
        assertEquals(first, value("current_window"));
        assertEquals(1, ((JSONArray) value("windows")).length());
        value("quit");
        assertEquals(0, ((JSONArray) value("windows")).length());
        String restarted = (String) value("current_window");
        assertNotEquals(first, restarted);
        assertTrue(((String) value("root_frame")).startsWith("frame-"));
        value("quit");
        assertNotNull(value("new_window"));
    }

    @Test(timeout = 45000) public void documentScriptsApplyAtStartAndDoNotLeakIntoRestartedSessions() throws Exception {
        assertTrue(value("add_document_script", "source", "window.__fixture_session_script='present';") instanceof String);
        scenario.onActivity(activity -> activity.loadFixture(FIXTURE));
        awaitTrue("return document.title === 'Unipus local fixture' && window.__fixture_session_script === 'present';");
        value("quit");
        value("current_window");
        scenario.onActivity(activity -> activity.loadFixture(FIXTURE));
        awaitTrue("return document.title === 'Unipus local fixture' && !!document.getElementById('target');");
        assertEquals(Boolean.TRUE, value("evaluate", "script", "return typeof window.__fixture_session_script === 'undefined';"));
    }

    @Test(timeout = 30000) public void rejectsUnsafeNativeNavigationAndPageCannotIssueCommands() throws Exception {
        String original = (String) value("url");
        for (String url : new String[]{"file:///data/local/forbidden", "content://fixture/forbidden", "intent://fixture", "javascript:alert(1)"}) {
            JSONObject rejected = request("navigate", "url", url);
            assertFalse(rejected.toString(), rejected.getBoolean("ok"));
            assertEquals("InvalidArgumentException", rejected.getJSONObject("error").getString("name"));
        }
        value("evaluate", "script", "window.__unipusNative.postMessage(JSON.stringify({op:'navigate',url:'file:///forbidden'})); return true;");
        assertEquals(original, value("url"));
        scenario.onActivity(activity -> {
            assertFalse(kernel.getCurrentView().getSettings().getAllowFileAccess());
            assertFalse(kernel.getCurrentView().getSettings().getAllowContentAccess());
        });
    }

    private static byte[] hex(String value) {
        byte[] bytes = new byte[value.length() / 2];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        return bytes;
    }

    @Test(timeout = 30000) public void decryptsNistAesEcbVectorsThroughNativeOnlyRpc() throws Exception {
        String[][] vectors = {
                {"000102030405060708090a0b0c0d0e0f", "69c4e0d86a7b0430d8cdb78070b4c55a"},
                {"000102030405060708090a0b0c0d0e0f1011121314151617", "dda97ca4864cdfe06eaf70a0ec0d7191"},
                {"000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f", "8ea2b7ca516745bfeafc49904b496089"}
        };
        byte[] plaintext = hex("00112233445566778899aabbccddeeff");
        for (String[] vector : vectors) {
            String key = Base64.encodeToString(hex(vector[0]), Base64.NO_WRAP);
            String encrypted = Base64.encodeToString(hex(vector[1]), Base64.NO_WRAP);
            assertArrayEquals(plaintext, Base64.decode((String) value("aes_decrypt", "key", key, "data", encrypted), Base64.DEFAULT));
        }
        JSONObject invalid = request("aes_decrypt", "key", "AQID", "data", "AA==");
        assertFalse(invalid.getBoolean("ok"));
        assertEquals("InvalidArgumentException", invalid.getJSONObject("error").getString("name"));
    }
}
