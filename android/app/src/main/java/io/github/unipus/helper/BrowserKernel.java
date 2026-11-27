package io.github.unipus.helper;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Base64;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/** WebView-owned browser operations. callJson is deliberately worker-thread only. */
public final class BrowserKernel {
    public interface Listener {
        void onPageState(String url, boolean loading);
        void onError(String message);
        void onTabsChanged();
    }

    private static final String BRIDGE = "__unipusNative";
    private static final Set<String> ALL_ORIGINS = Collections.singleton("*");
    private static final long DEFAULT_TIMEOUT_MS = 30000;
    private static final int MAX_TABS = 12;
    private static final int MAX_MESSAGE_BYTES = 16 * 1024 * 1024;
    private final Activity activity;
    private final FrameLayout container;
    private final Listener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Map<String, Tab> tabs = new LinkedHashMap<>();
    private final Map<String, Pending> pending = new LinkedHashMap<>();
    private final Map<String, Evaluation> evaluations = new LinkedHashMap<>();
    private final List<String> documentScripts = new ArrayList<>();
    private final String bootstrap;
    private final boolean bridgeSupported;
    private String currentWindow;
    private volatile boolean destroyed;

    public BrowserKernel(Activity activity, FrameLayout container, Listener listener) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("Construct BrowserKernel on the UI thread");
        }
        this.activity = activity;
        this.container = container;
        this.listener = listener;
        this.bootstrap = readAsset("browser_kernel.js");
        this.bridgeSupported = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
                && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT);
        if (!bridgeSupported) {
            notifyError("请更新 Android System WebView 或 Chrome：当前浏览器内核不支持自动化消息桥。");
        }
        Tab first = createTab(true);
        navigate(first, "about:blank", null);
    }

    /** All requests/results are JSON. This method never waits on the Android UI thread. */
    public String callJson(String request) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return error("WebDriverException", "callJson must run on a Python worker thread").toString();
        }
        final Pending call;
        try {
            if (request == null || request.length() > MAX_MESSAGE_BYTES) {
                return error("InvalidArgumentException", "Browser request is empty or too large").toString();
            }
            JSONObject data = new JSONObject(request);
            long timeout = Math.max(250, Math.min(120000, data.optLong("timeoutMs", DEFAULT_TIMEOUT_MS)));
            call = new Pending(data, timeout);
        } catch (JSONException exception) {
            return error("InvalidArgumentException", "Malformed browser request JSON").toString();
        }
        // Crypto is native-only and stays on this worker; web-message callbacks
        // never dispatch operations and cannot reach this entry point.
        if ("aes_decrypt".equals(call.op)) return decryptAes(call.data);
        if (!ui.post(() -> dispatch(call))) {
            return error("InvalidSessionIdException", "Browser UI is unavailable").toString();
        }
        try {
            if (!call.latch.await(call.timeoutMs, TimeUnit.MILLISECONDS)) {
                call.complete(error("TimeoutException", "Browser operation timed out: " + call.op));
                ui.post(() -> forget(call));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            call.complete(error("WebDriverException", "Browser operation was interrupted"));
            ui.post(() -> forget(call));
        }
        return call.result;
    }

    public void loadUrl(String url) {
        requireUi();
        try {
            Tab tab = currentWindow == null ? createTab(true) : getTab(currentWindow);
            navigate(tab, url, null);
        } catch (KernelException exception) {
            notifyError(exception.getMessage());
        }
    }

    public void goBack() {
        requireUi();
        if (destroyed || currentWindow == null) return;
        Tab tab = tabs.get(currentWindow);
        if (tab != null && tab.view.canGoBack()) {
            invalidateFrames(tab, "Document changed during back navigation");
            tab.view.goBack();
        }
    }

    public void reload() {
        requireUi();
        if (destroyed || currentWindow == null) return;
        Tab tab = tabs.get(currentWindow);
        if (tab != null) {
            invalidateFrames(tab, "Document was reloaded");
            tab.view.reload();
        }
    }

    public WebView getCurrentView() {
        requireUi();
        Tab tab = tabs.get(currentWindow);
        return tab == null ? null : tab.view;
    }

    public void showCurrent() {
        requireUi();
        for (Tab tab : tabs.values()) {
            tab.view.setVisibility(tab.id.equals(currentWindow) ? View.VISIBLE : View.GONE);
        }
        Tab tab = tabs.get(currentWindow);
        if (tab != null) {
            tab.view.bringToFront();
            listener.onPageState(safeUrl(tab), tab.loading);
        }
    }

    public void destroy() {
        requireUi();
        if (destroyed) return;
        destroyed = true;
        documentScripts.clear();
        for (Tab tab : new ArrayList<>(tabs.values())) closeTab(tab);
        for (Pending call : new ArrayList<>(pending.values())) {
            finishError(call, "InvalidSessionIdException", "Browser session was closed");
        }
    }

    private void dispatch(Pending call) {
        if (call.finished) return;
        pending.put(call.id, call);
        try {
            if ("quit".equals(call.op)) {
                // Python can restart its browser session using this same Activity.
                for (Tab tab : new ArrayList<>(tabs.values())) closeTab(tab);
                documentScripts.clear();
                finish(call, JSONObject.NULL);
                return;
            }
            if (destroyed) throw new KernelException("InvalidSessionIdException", "Browser session was closed");
            switch (call.op) {
                case "windows": finish(call, windowHandles()); return;
                case "current_window": {
                    if (currentWindow == null) {
                        Tab first = createTab(true);
                        navigate(first, "about:blank", null);
                    }
                    finish(call, currentTab().id);
                    return;
                }
                case "new_window": {
                    Tab tab = createTab(true);
                    navigate(tab, "about:blank", null);
                    finish(call, tab.id);
                    return;
                }
                case "switch_window": {
                    Tab tab = getTab(stringArgument(call.data, "window", 0));
                    currentWindow = tab.id;
                    showCurrent();
                    notifyTabs();
                    finish(call, JSONObject.NULL);
                    return;
                }
            }
            Tab tab = resolveTab(call.data);
            call.owner = tab;
            switch (call.op) {
                case "navigate": navigate(tab, stringArgument(call.data, "url", 0), call); break;
                case "evaluate": requireBridge(); evaluate(call, tab); break;
                case "root_frame":
                    requireBridge();
                    if (tab.rootFrame == null) tab.waitingForRoot.add(call);
                    else finish(call, tab.rootFrame);
                    break;
                case "close_window": closeTab(tab); finish(call, windowHandles()); break;
                case "url": finish(call, safeUrl(tab)); break;
                case "title": finish(call, tab.view.getTitle() == null ? "" : tab.view.getTitle()); break;
                case "loading": finish(call, tab.loading); break;
                case "add_document_script": {
                    requireBridge();
                    String source = call.data.has("source") ? call.data.optString("source", "")
                            : call.data.optString("script", "");
                    if (source.length() > MAX_MESSAGE_BYTES) throw new KernelException("InvalidArgumentException", "Script is too large");
                    documentScripts.add(source);
                    for (Tab target : tabs.values()) WebViewCompat.addDocumentStartJavaScript(target.view, source, ALL_ORIGINS);
                    finish(call, "document-script-" + documentScripts.size());
                    break;
                }
                case "screenshot": screenshot(call, tab); break;
                case "cookies": finish(call, cookies(tab)); break;
                case "add_cookie": addCookie(call, tab); break;
                case "clear_cookies":
                    CookieManager.getInstance().removeAllCookies(value -> finish(call, JSONObject.NULL));
                    break;
                default: throw new KernelException("UnknownCommandException", "Unknown browser operation: " + call.op);
            }
        } catch (KernelException exception) {
            finishError(call, exception.name, exception.getMessage());
        } catch (Exception exception) {
            finishError(call, "WebDriverException", cleanMessage(exception));
        }
    }

    private Tab createTab(boolean activate) {
        if (destroyed) throw new KernelException("InvalidSessionIdException", "Browser session was closed");
        if (tabs.size() >= MAX_TABS) throw new KernelException("WebDriverException", "Too many browser windows");
        WebView view = new WebView(activity);
        Tab tab = new Tab("window-" + UUID.randomUUID(), view);
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        String userAgent = settings.getUserAgentString();
        if (userAgent != null && !userAgent.isEmpty()) {
            // Keep this installed WebView's real Chrome version while asking
            // the course site for the desktop DOM used by the shared parser.
            userAgent = userAgent.replaceFirst("\\([^)]*\\)", "(X11; Linux x86_64)")
                    .replaceAll("\\s+Version/4\\.0\\b", "")
                    .replaceAll("\\bMobile\\b", "")
                    .replaceAll("(?:;\\s*)?\\bwv\\b", "")
                    .replaceAll("\\s{2,}", " ").trim();
            settings.setUserAgentString(userAgent);
        }
        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSafeBrowsingEnabled(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true);
        view.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView webView, WebResourceRequest request) {
                String scheme = request.getUrl().getScheme();
                boolean supported = scheme != null && (scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http")
                        || scheme.equalsIgnoreCase("about") || scheme.equalsIgnoreCase("data") || scheme.equalsIgnoreCase("blob"));
                if (!supported) notifyError("此链接使用了不支持的地址协议。");
                return !supported;
            }
            @Override public void onPageStarted(WebView webView, String url, Bitmap favicon) {
                if (tab.closed) return;
                invalidateFrames(tab, "Document was navigated");
                tab.loading = true;
                notifyPage(tab);
            }
            @Override public void onPageFinished(WebView webView, String url) {
                if (tab.closed) return;
                tab.loading = false;
                if (bridgeSupported) webView.evaluateJavascript(bootstrap, null);
                maybeFinishNavigation(tab);
                notifyPage(tab);
            }
            @Override public void onReceivedError(WebView webView, WebResourceRequest request, WebResourceError failure) {
                if (!request.isForMainFrame() || tab.closed) return;
                tab.loading = false;
                if (tab.navigation != null) finishError(tab.navigation, "WebDriverException", String.valueOf(failure.getDescription()));
                notifyPage(tab);
                notifyError("网页加载失败：" + failure.getDescription());
            }
            @Override public void onReceivedSslError(WebView webView, SslErrorHandler handler, SslError sslError) {
                handler.cancel();
                tab.loading = false;
                if (tab.navigation != null) finishError(tab.navigation, "WebDriverException", "TLS certificate validation failed");
                notifyPage(tab);
                notifyError("网页证书验证失败，已取消连接。");
            }
            @Override public boolean onRenderProcessGone(WebView webView, RenderProcessGoneDetail detail) {
                closeTab(tab);
                notifyError("浏览器渲染进程已退出，请重新打开浏览器窗口。");
                return true;
            }
        });
        view.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onCreateWindow(WebView source, boolean isDialog, boolean isUserGesture, Message result) {
                try {
                    Tab popup = createTab(true);
                    WebView.WebViewTransport transport = (WebView.WebViewTransport) result.obj;
                    transport.setWebView(popup.view);
                    result.sendToTarget();
                    return true;
                } catch (RuntimeException exception) {
                    notifyError(cleanMessage(exception));
                    return false;
                }
            }
            @Override public void onCloseWindow(WebView webView) { closeTab(tab); }
            @Override public void onReceivedTitle(WebView webView, String title) { notifyPage(tab); }
            @Override public void onPermissionRequest(PermissionRequest permission) {
                boolean audio = false;
                for (String resource : permission.getResources()) {
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) audio = true;
                }
                if (!audio || activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    permission.deny();
                    notifyError("此网页需要麦克风权限，请先在安卓应用权限中允许麦克风。");
                    return;
                }
                new AlertDialog.Builder(activity).setTitle("允许网页使用麦克风？")
                        .setMessage(String.valueOf(permission.getOrigin().getHost()))
                        .setPositiveButton("允许", (dialog, which) -> permission.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE}))
                        .setNegativeButton("拒绝", (dialog, which) -> permission.deny())
                        .setOnCancelListener(dialog -> permission.deny()).show();
            }
        });
        if (bridgeSupported) {
            WebViewCompat.addWebMessageListener(view, BRIDGE, ALL_ORIGINS,
                    (owner, message, origin, isMainFrame, proxy) -> receive(tab, message, isMainFrame, proxy));
            WebViewCompat.addDocumentStartJavaScript(view, bootstrap, ALL_ORIGINS);
            for (String source : documentScripts) WebViewCompat.addDocumentStartJavaScript(view, source, ALL_ORIGINS);
        }
        tabs.put(tab.id, tab);
        container.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (activate || currentWindow == null) currentWindow = tab.id;
        showCurrent();
        notifyTabs();
        return tab;
    }

    private void receive(Tab tab, WebMessageCompat message, boolean isMainFrame, JavaScriptReplyProxy proxy) {
        if (destroyed || tab.closed) return;
        try {
            String raw = message.getData();
            if (raw == null || raw.length() > MAX_MESSAGE_BYTES) {
                failEvaluations(tab, "JavascriptException", "Browser response exceeded the size limit");
                return;
            }
            JSONObject data = new JSONObject(raw);
            String frameId = data.optString("frame", "");
            if (!frameId.startsWith("frame-") || frameId.length() > 128) return;
            if ("hello".equals(data.optString("kind"))) {
                if (tab.retiredFrames.contains(frameId)) return;
                Frame existing = tab.frames.get(frameId);
                if (existing != null && (existing.proxy != proxy || existing.main != isMainFrame)) return;
                if (isMainFrame && tab.rootFrame != null && !tab.rootFrame.equals(frameId)) {
                    invalidateFrames(tab, "Main frame execution context changed");
                }
                Frame frame = new Frame(frameId, proxy, isMainFrame);
                tab.frames.put(frameId, frame);
                if (isMainFrame) {
                    tab.rootFrame = frameId;
                    for (Pending waiting : new ArrayList<>(tab.waitingForRoot)) {
                        tab.waitingForRoot.remove(waiting);
                        if (waiting.finished) continue;
                        if ("root_frame".equals(waiting.op)) finish(waiting, frameId);
                        else evaluate(waiting, tab);
                    }
                    maybeFinishNavigation(tab);
                }
            } else if ("result".equals(data.optString("kind"))) {
                Evaluation evaluation = evaluations.get(data.optString("request", ""));
                if (evaluation == null || evaluation.tab != tab || !evaluation.frame.id.equals(frameId)
                        || evaluation.frame.proxy != proxy || evaluation.frame.main != isMainFrame) return;
                if (data.optBoolean("ok")) finish(evaluation.call, data.opt("value"));
                else {
                    JSONObject cause = data.optJSONObject("error");
                    finishError(evaluation.call, cause == null ? "JavascriptException" : cause.optString("name", "JavascriptException"),
                            cause == null ? "JavaScript execution failed" : cause.optString("message", "JavaScript execution failed"));
                }
            }
        } catch (JSONException | RuntimeException ignored) {
            // Web content can only report replies. It never dispatches native operations.
        }
    }

    private void evaluate(Pending call, Tab tab) {
        String frameId = call.data.isNull("frame") ? tab.rootFrame : call.data.optString("frame", tab.rootFrame);
        if (frameId == null || frameId.isEmpty()) {
            if (!tab.waitingForRoot.contains(call)) tab.waitingForRoot.add(call);
            return;
        }
        Frame frame = tab.frames.get(frameId);
        if (frame == null) {
            finishError(call, "NoSuchFrameException", "Frame was destroyed or navigated away");
            return;
        }
        JSONArray args = call.data.optJSONArray("args");
        if (call.data.has("args") && !call.data.isNull("args") && args == null) {
            finishError(call, "InvalidArgumentException", "Script args must be an array");
            return;
        }
        evaluations.put(call.id, new Evaluation(call, tab, frame));
        JSONObject command = object("kind", "command", "request", call.id, "frame", frame.id,
                "script", call.data.optString("script", ""), "args", args == null ? new JSONArray() : args,
                "timeoutMs", call.timeoutMs);
        try {
            frame.proxy.postMessage(command.toString());
        } catch (RuntimeException exception) {
            finishError(call, "NoSuchFrameException", "Frame execution context is unavailable");
        }
    }

    private void navigate(Tab tab, String url, Pending call) {
        validateNavigationUrl(url);
        if (tab.navigation != null) finishError(tab.navigation, "WebDriverException", "Navigation was superseded");
        invalidateFrames(tab, "Document was navigated");
        tab.navigation = call;
        tab.loading = true;
        tab.view.loadUrl(url);
        notifyPage(tab);
    }

    private void maybeFinishNavigation(Tab tab) {
        if (!tab.loading && tab.navigation != null && (tab.rootFrame != null || !bridgeSupported)) {
            finish(tab.navigation, JSONObject.NULL);
        }
    }

    private void invalidateFrames(Tab tab, String message) {
        tab.retiredFrames.addAll(tab.frames.keySet());
        while (tab.retiredFrames.size() > 4096) {
            tab.retiredFrames.remove(tab.retiredFrames.iterator().next());
        }
        tab.frames.clear();
        tab.rootFrame = null;
        failEvaluations(tab, "NoSuchFrameException", message);
        for (Pending call : new ArrayList<>(pending.values())) {
            if (call.owner == tab && "screenshot".equals(call.op)) finishError(call, "WebDriverException", message);
        }
    }

    private void failEvaluations(Tab tab, String name, String message) {
        for (Evaluation evaluation : new ArrayList<>(evaluations.values())) {
            if (evaluation.tab == tab) finishError(evaluation.call, name, message);
        }
    }

    private void closeTab(Tab tab) {
        if (tab.closed) return;
        tab.closed = true;
        invalidateFrames(tab, "Browser window was closed");
        for (Pending call : new ArrayList<>(pending.values())) {
            if (call.owner == tab && !"close_window".equals(call.op)) {
                finishError(call, "NoSuchWindowException", "Browser window was closed");
            }
        }
        tab.waitingForRoot.clear();
        tabs.remove(tab.id);
        container.removeView(tab.view);
        tab.view.stopLoading();
        tab.view.setWebChromeClient(null);
        tab.view.setWebViewClient(new WebViewClient());
        tab.view.destroy();
        if (tab.id.equals(currentWindow)) {
            currentWindow = null;
            for (String handle : tabs.keySet()) currentWindow = handle;
        }
        showCurrent();
        notifyTabs();
    }

    private JSONArray cookies(Tab tab) {
        JSONArray result = new JSONArray();
        String url = safeUrl(tab);
        if (!(url.startsWith("https://") || url.startsWith("http://"))) return result;
        String header = CookieManager.getInstance().getCookie(url);
        if (header == null) return result;
        for (String pair : header.split(";")) {
            int equals = pair.indexOf('=');
            if (equals > 0) result.put(object("name", pair.substring(0, equals).trim(), "value", pair.substring(equals + 1).trim()));
        }
        return result;
    }

    private void addCookie(Pending call, Tab tab) throws JSONException {
        JSONObject cookie = call.data.optJSONObject("cookie");
        if (cookie == null) throw new KernelException("InvalidArgumentException", "Missing cookie object");
        String name = cookie.optString("name", ""), value = cookie.optString("value", "");
        if (!name.matches("[^=;\\s\\p{Cntrl}]+") || value.indexOf(';') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new KernelException("InvalidArgumentException", "Invalid cookie name or value");
        }
        Uri url = Uri.parse(safeUrl(tab));
        if (url.getHost() == null || !("https".equals(url.getScheme()) || "http".equals(url.getScheme()))) {
            throw new KernelException("InvalidCookieDomainException", "Open an HTTP(S) page before adding cookies");
        }
        StringBuilder header = new StringBuilder(name).append('=').append(value);
        if (cookie.has("domain")) {
            String domain = cookie.getString("domain").toLowerCase(Locale.ROOT);
            String plain = domain.startsWith(".") ? domain.substring(1) : domain;
            String host = url.getHost().toLowerCase(Locale.ROOT);
            if (plain.isEmpty() || !(host.equals(plain) || host.endsWith("." + plain)) || !domain.matches("\\.?[a-z0-9.-]+")) {
                throw new KernelException("InvalidCookieDomainException", "Cookie domain does not match the current page");
            }
            header.append("; Domain=").append(domain);
        }
        String path = cookie.optString("path", "/");
        if (path.indexOf(';') >= 0 || path.indexOf('\r') >= 0 || path.indexOf('\n') >= 0 || !path.startsWith("/")) {
            throw new KernelException("InvalidArgumentException", "Invalid cookie path");
        }
        header.append("; Path=").append(path);
        if (cookie.optBoolean("secure")) header.append("; Secure");
        if (cookie.optBoolean("httpOnly")) header.append("; HttpOnly");
        if (cookie.has("expiry")) header.append("; Max-Age=").append(Math.max(0, cookie.getLong("expiry") - System.currentTimeMillis() / 1000));
        String sameSite = cookie.optString("sameSite", "");
        if (!sameSite.isEmpty()) {
            if (!("Lax".equalsIgnoreCase(sameSite) || "Strict".equalsIgnoreCase(sameSite) || "None".equalsIgnoreCase(sameSite))) {
                throw new KernelException("InvalidArgumentException", "Invalid SameSite cookie value");
            }
            header.append("; SameSite=").append(sameSite);
        }
        CookieManager.getInstance().setCookie(url.toString(), header.toString(), accepted -> {
            if (accepted) finish(call, JSONObject.NULL);
            else finishError(call, "UnableToSetCookieException", "WebView rejected the cookie");
        });
    }

    private void screenshot(Pending call, Tab tab) {
        if (!tab.id.equals(currentWindow)) {
            finishError(call, "WebDriverException", "Switch to the window before taking a screenshot");
            return;
        }
        int width = tab.view.getWidth(), height = tab.view.getHeight();
        if (width <= 0 || height <= 0 || (long) width * height > 8000000L) {
            finishError(call, "WebDriverException", "Browser viewport has no valid screenshot size");
            return;
        }
        final Bitmap bitmap;
        try {
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        } catch (RuntimeException | OutOfMemoryError failure) {
            finishError(call, "WebDriverException", "Not enough memory for a viewport screenshot");
            return;
        }
        // PixelCopy reads the Activity surface: when the browser tab is hidden,
        // it would otherwise capture the task/settings UI instead of the page.
        if (!tab.view.isShown()) {
            drawScreenshot(call, tab, bitmap);
            return;
        }
        int[] location = new int[2];
        tab.view.getLocationInWindow(location);
        Rect source = new Rect(location[0], location[1], location[0] + width, location[1] + height);
        try {
            PixelCopy.request(activity.getWindow(), source, bitmap, status -> {
                if (call.finished) { bitmap.recycle(); return; }
                if (status == PixelCopy.SUCCESS) finishScreenshot(call, bitmap);
                else drawScreenshot(call, tab, bitmap);
            }, ui);
        } catch (IllegalArgumentException exception) {
            drawScreenshot(call, tab, bitmap);
        }
    }

    private void drawScreenshot(Pending call, Tab tab, Bitmap bitmap) {
        try {
            if (call.finished || tab.closed) return;
            tab.view.draw(new Canvas(bitmap));
            finishScreenshot(call, bitmap);
        } catch (RuntimeException exception) {
            finishError(call, "WebDriverException", "Unable to capture the WebView viewport");
        } finally {
            if (!bitmap.isRecycled()) bitmap.recycle();
        }
    }

    private void finishScreenshot(Pending call, Bitmap bitmap) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                finishError(call, "WebDriverException", "PNG encoding failed");
                return;
            }
            finish(call, Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP));
        } catch (RuntimeException | OutOfMemoryError failure) {
            finishError(call, "WebDriverException", "Unable to encode the WebView screenshot");
        } finally {
            if (!bitmap.isRecycled()) bitmap.recycle();
        }
    }

    private void finish(Pending call, Object value) {
        call.complete(object("ok", true, "value", value));
        forget(call);
    }

    private void finishError(Pending call, String name, String message) {
        call.complete(error(name, message));
        forget(call);
    }

    private void forget(Pending call) {
        pending.remove(call.id);
        evaluations.remove(call.id);
        if (call.owner != null) {
            call.owner.waitingForRoot.remove(call);
            if (call.owner.navigation == call) call.owner.navigation = null;
        }
    }

    private Tab currentTab() {
        return getTab(currentWindow);
    }

    private Tab getTab(String handle) {
        Tab tab = handle == null ? null : tabs.get(handle);
        if (tab == null || tab.closed) throw new KernelException("NoSuchWindowException", "Browser window does not exist");
        return tab;
    }

    private Tab resolveTab(JSONObject data) {
        return data.has("window") && !data.isNull("window") ? getTab(data.optString("window")) : currentTab();
    }

    private JSONArray windowHandles() {
        JSONArray handles = new JSONArray();
        for (String handle : tabs.keySet()) handles.put(handle);
        return handles;
    }

    private void notifyTabs() { ui.post(listener::onTabsChanged); }
    private void notifyError(String message) { ui.post(() -> listener.onError(message)); }
    private void notifyPage(Tab tab) {
        if (!tab.closed && tab.id.equals(currentWindow)) listener.onPageState(safeUrl(tab), tab.loading);
    }
    private String safeUrl(Tab tab) { return tab.view.getUrl() == null ? "about:blank" : tab.view.getUrl(); }
    private void requireBridge() {
        if (!bridgeSupported) throw new KernelException("UnsupportedOperationException", "Update Android System WebView to enable the browser bridge");
    }
    private void requireUi() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("WebView operation must run on the UI thread");
    }
    private void validateNavigationUrl(String url) {
        Uri parsed = Uri.parse(url == null ? "" : url);
        String scheme = parsed.getScheme();
        if ("about:blank".equals(url) || "about:srcdoc".equals(url)) return;
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https")) || parsed.getHost() == null) {
            throw new KernelException("InvalidArgumentException", "Only HTTP(S) and blank-page navigation are supported");
        }
    }

    private String readAsset(String name) {
        try (InputStream input = activity.getAssets().open(name); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Missing browser bootstrap asset", exception);
        }
    }

    private static String stringArgument(JSONObject data, String key, int index) {
        if (data.has(key) && !data.isNull(key)) return data.optString(key, "");
        JSONArray args = data.optJSONArray("args");
        return args != null ? args.optString(index, "") : "";
    }
    private static JSONObject object(Object... pairs) {
        JSONObject value = new JSONObject();
        try {
            for (int index = 0; index < pairs.length; index += 2) {
                value.put(String.valueOf(pairs[index]), pairs[index + 1] == null ? JSONObject.NULL : pairs[index + 1]);
            }
        } catch (JSONException exception) {
            throw new IllegalArgumentException("Invalid JSON result", exception);
        }
        return value;
    }
    private static JSONObject error(String name, String message) {
        return object("ok", false, "error", object("name", name, "message", message == null ? "Browser operation failed" : message));
    }
    private static String cleanMessage(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    private String decryptAes(JSONObject data) {
        if (destroyed) return error("InvalidSessionIdException", "Browser session was closed").toString();
        byte[] key = null;
        try {
            key = Base64.decode(data.optString("key", ""), Base64.DEFAULT);
            byte[] ciphertext = Base64.decode(data.optString("data", ""), Base64.DEFAULT);
            if (!(key.length == 16 || key.length == 24 || key.length == 32)) {
                return error("InvalidArgumentException", "AES key must contain 16, 24 or 32 bytes").toString();
            }
            if (ciphertext.length % 16 != 0 || ciphertext.length > 8 * 1024 * 1024) {
                return error("InvalidArgumentException", "AES data must contain complete 16-byte blocks within the size limit").toString();
            }
            Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
            return object("ok", true, "value", Base64.encodeToString(cipher.doFinal(ciphertext), Base64.NO_WRAP)).toString();
        } catch (IllegalArgumentException exception) {
            return error("InvalidArgumentException", "Invalid Base64 AES input").toString();
        } catch (java.security.GeneralSecurityException exception) {
            return error("WebDriverException", "Native AES decryption failed").toString();
        } finally {
            if (key != null) java.util.Arrays.fill(key, (byte) 0);
        }
    }

    private static final class Pending {
        final String id = UUID.randomUUID().toString();
        final JSONObject data;
        final String op;
        final long timeoutMs;
        final CountDownLatch latch = new CountDownLatch(1);
        volatile boolean finished;
        volatile String result;
        Tab owner;
        Pending(JSONObject data, long timeoutMs) {
            this.data = data;
            this.op = data.optString("op", "");
            this.timeoutMs = timeoutMs;
        }
        synchronized void complete(JSONObject value) {
            if (finished) return;
            result = value.toString();
            finished = true;
            latch.countDown();
        }
    }
    private static final class Frame {
        final String id;
        final JavaScriptReplyProxy proxy;
        final boolean main;
        Frame(String id, JavaScriptReplyProxy proxy, boolean main) { this.id = id; this.proxy = proxy; this.main = main; }
    }
    private static final class Tab {
        final String id;
        final WebView view;
        final Map<String, Frame> frames = new LinkedHashMap<>();
        final LinkedHashSet<String> retiredFrames = new LinkedHashSet<>();
        final List<Pending> waitingForRoot = new ArrayList<>();
        boolean closed;
        boolean loading = true;
        String rootFrame;
        Pending navigation;
        Tab(String id, WebView view) { this.id = id; this.view = view; }
    }
    private static final class Evaluation {
        final Pending call;
        final Tab tab;
        final Frame frame;
        Evaluation(Pending call, Tab tab, Frame frame) { this.call = call; this.tab = tab; this.frame = frame; }
    }
    private static final class KernelException extends RuntimeException {
        final String name;
        KernelException(String name, String message) { super(message); this.name = name; }
    }
}
