package io.github.unipus.helper;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Toast;
import androidx.activity.ComponentActivity;
import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;
import io.github.unipus.helper.ui.UiBridge;
import io.github.unipus.helper.ui.UnipusAppKt;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Controller for the in-process Python automation and the miuix/Compose interface.
 *
 * The activity owns every piece of behaviour (WebView kernel, Python runtime, offline speech,
 * model import) and publishes a read-only snapshot to {@link UiBridge}; the Compose UI in
 * {@code io.github.unipus.helper.ui} renders it and calls back into these public methods.
 */
public final class MainActivity extends ComponentActivity implements BrowserKernel.Listener {
    private static final String TAG = "UnipusHelper";
    private static final String DEFAULT_LOGIN_URL = "https://ucloud.unipus.cn/home";
    private static final String LEGACY_DEFAULT_LOGIN_URL = "https://uai.unipus.cn/sso/index.html?service=https%3A%2F%2Fucloud.unipus.cn%2Fhome";
    private static final int MODEL_PICK_REQUEST = 72;
    private static final long MODEL_IMPORT_BYTES = 77704715L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService snapshots = Executors.newSingleThreadExecutor();
    private final ExecutorService audioActions = Executors.newSingleThreadExecutor();
    private final ExecutorService audioChecks = Executors.newFixedThreadPool(2);
    private final ExecutorService audioCancellation = Executors.newSingleThreadExecutor();
    private final UiBridge bridge = new UiBridge();

    private volatile PyObject runtime;
    private volatile boolean closed;
    private boolean polling;
    private boolean started;
    private boolean audioBusy, audioChecking, ttsChecking, runtimeAudioChecking;
    private JSONObject speechState = new JSONObject(), mediaState = new JSONObject();
    private String audioMessage = "语音依赖尚未检查";
    private volatile ModelImport modelImport;
    private JSONObject state = new JSONObject();
    private JSONObject config = new JSONObject();
    private String page = "browser";
    private String filter = "all";
    private FrameLayout browserHost;
    private BrowserKernel browser;
    private int canvas;
    private boolean dark;

    private final Runnable audioPulse = new Runnable() {
        @Override public void run() {
            if (closed || !started) return;
            if (page.equals("settings") || audioBusy) refreshMediaStatus();
            main.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        config = readConfig();
        if (savedInstanceState != null) {
            page = savedInstanceState.getString("page", "browser");
            filter = savedInstanceState.getString("filter", "all");
            try { config = new JSONObject(savedInstanceState.getString("config", config.toString())); }
            catch (JSONException ignored) { }
        }
        applyPalette();
        buildUi();
        try {
            browser = new BrowserKernel(this, browserHost, this);
        } catch (RuntimeException error) {
            bridge.postSubtitle("请更新 Android System WebView 后重试");
            new android.app.AlertDialog.Builder(this).setTitle("浏览器内核不可用")
                .setMessage(error.getMessage()).setPositiveButton("关闭", (dialog, which) -> finish()).show();
            return;
        }
        showPage(page);
        worker.execute(() -> {
            try {
                if (!Python.isStarted()) Python.start(new AndroidPlatform(this));
                runtime = Python.getInstance().getModule("unipus_helper.android_runtime");
                JSONObject initial = parse(runtime.callAttr("initialize", browser,
                    getFilesDir().getAbsolutePath(), config.toString()).toString());
                main.post(() -> { state = initial; showPage(page); poll(); });
            } catch (Exception error) { showError("启动失败", error); }
        });
    }

    // ---------------------------------------------------------------- snapshot publication

    private void buildUi() {
        browserHost = new FrameLayout(this);
        UnipusAppKt.attachUnipusUi(this, this, bridge, browserHost);
    }

    /** Publishes the controller state to Compose and keeps the screen awake while busy. */
    private void publish() {
        boolean busy = state.optBoolean("busy") || audioBusy || mediaState.optBoolean("busy");
        if (busy) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        bridge.sync(this);
    }

    private void updateStatus() {
        boolean busy = state.optBoolean("busy") || audioBusy || mediaState.optBoolean("busy");
        if (busy) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (state.length() > 0) {
            bridge.postSubtitle(state.optString("status", "就绪")
                + (state.optString("error").isEmpty() ? "" : " · " + state.optString("error")));
        }
        bridge.sync(this);
    }

    // ---------------------------------------------------------------- getters for the bridge

    public String getPageName() { return page; }
    public String getFilterName() { return filter; }
    public boolean getDarkMode() { return dark; }
    public boolean getPageBusy() {
        return state.optBoolean("busy") || audioBusy || mediaState.optBoolean("busy");
    }
    public boolean getAudioBusy() { return audioBusy; }
    public String getAudioStatusText() { return audioMessage; }
    public JSONObject getSnapshotJson() { return state; }
    public JSONObject getDraftConfigJson() { return config; }
    public JSONObject getSpeechStateJson() { return speechState; }
    public JSONObject getMediaStateJson() { return mediaState; }
    public float getModelImportProgress() {
        ModelImport importing = modelImport;
        if (importing == null || !importing.copying) return -1f;
        return Math.min(1f, (float) importing.received / MODEL_IMPORT_BYTES);
    }

    // ---------------------------------------------------------------- configuration

    private JSONObject readConfig() {
        try {
            File file = new File(getFilesDir(), "config.json");
            if (file.isFile()) {
                JSONObject saved = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
                if (LEGACY_DEFAULT_LOGIN_URL.equals(saved.optString("url"))) put(saved, "url", DEFAULT_LOGIN_URL);
                return saved;
            }
        } catch (Exception ignored) { }
        JSONObject defaults = new JSONObject();
        put(defaults, "theme_mode", "system"); put(defaults, "animations_enabled", true);
        put(defaults, "tts_engine", "android"); put(defaults, "skip_record_mode", "off");
        put(defaults, "url", DEFAULT_LOGIN_URL);
        return defaults;
    }

    private void applyPalette() {
        String mode = config.optString("theme_mode", "system");
        dark = mode.equals("dark") || (mode.equals("system") &&
            (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES);
        setTheme(dark ? R.style.Theme_UnipusHelper_Dark : R.style.Theme_UnipusHelper);
        canvas = Color.parseColor(dark ? "#181E28" : "#F4F7FB");
        getWindow().setStatusBarColor(canvas);
        getWindow().setNavigationBarColor(canvas);
        getWindow().getDecorView().setSystemUiVisibility(dark ? 0
            : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().getDecorView().post(() -> {
                WindowInsetsController controller = getWindow().getInsetsController();
                if (controller != null) {
                    int lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                    controller.setSystemBarsAppearance(dark ? 0 : lightBars, lightBars);
                }
            });
        }
    }

    /** Called from the settings page whenever a text field changes. */
    public void setConfigValue(String key, String value) {
        put(config, key, value == null ? "" : value);
        publish();
    }

    public void setThemeMode(String mode) {
        put(config, "theme_mode", mode);
        applyPalette();
        publish();
    }

    public void setAnimationsEnabled(boolean enabled) {
        put(config, "animations_enabled", enabled);
        publish();
    }

    public void setSpeechEnabled(boolean enabled) {
        put(config, "tts_engine", enabled ? "android" : "off");
        put(config, "skip_record_mode", enabled ? "off" : "all");
        publish();
    }

    public void pickVoice(String name) {
        put(config, "tts_voice", name == null ? "" : name);
        publish();
        refreshSpeechStatus();
    }

    public void saveSettings() {
        if (runtime == null) { toast("工作台还在启动，请稍候"); return; }
        final String payload = config.toString();
        worker.execute(() -> {
            try {
                JSONObject result = parse(runtime.callAttr("save_config", payload).toString());
                main.post(() -> {
                    if (result.optBoolean("ok")) toast("已保存；账号、接口或语音选项修改后请重新加载浏览器");
                    else toast(result.optString("error", "保存失败"));
                });
            } catch (Exception error) { showError("保存失败", error); }
        });
    }

    // ---------------------------------------------------------------- navigation and browser

    public void showPage(String next) {
        if (closed) return;
        page = next;
        if (next.equals("browser") && browser != null) browser.showCurrent();
        updateStatus();
    }

    public void setFilter(String value) {
        filter = value;
        publish();
    }

    public void goBack() { if (browser != null) browser.goBack(); }
    public void reloadPage() { if (browser != null) browser.reload(); }
    public void loadLoginPage() {
        if (browser != null) browser.loadUrl(config.optString("url", DEFAULT_LOGIN_URL));
    }

    public void markReady() { invoke("mark_ready", new JSONObject()); }
    public void scanCourses() { invoke("scan", new JSONObject()); }
    public void scanUnit() { invoke("scan_unit", new JSONObject()); }
    public void quickProcess() { invoke("quick", new JSONObject()); }
    public void startRun() { invoke("run", new JSONObject()); }
    public void restartBrowser() { invoke("restart", new JSONObject()); }

    public void selectMode(String mode) {
        JSONObject payload = new JSONObject();
        put(payload, "mode", mode);
        invoke("select_mode", payload);
    }

    public void toggleTask(String id, boolean selected) {
        JSONObject payload = new JSONObject();
        put(payload, "selected", selected);
        JSONArray ids = new JSONArray();
        ids.put(id);
        put(payload, "ids", ids);
        invoke("select", payload);
    }

    public void requestMicrophone() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            toast("麦克风权限已允许，请在网页中重新开始录音");
        } else {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 31);
        }
    }

    public void openWindows() {
        if (browser == null) return;
        worker.execute(() -> {
            JSONObject response = parse(browser.callJson(object("op", "windows").toString()));
            JSONArray handles = response.optJSONArray("value");
            if (!response.optBoolean("ok") || handles == null) return;
            List<String> values = new ArrayList<>();
            for (int i = 0; i < handles.length(); i++) values.add(handles.optString(i));
            main.post(() -> bridge.showWindows(values));
        });
    }

    public void switchWindow(String handle) {
        if (browser == null) return;
        worker.execute(() -> {
            JSONObject command = object("op", "switch_window");
            put(command, "window", handle);
            browser.callJson(command.toString());
        });
    }

    // ---------------------------------------------------------------- task commands

    private void invoke(String action, JSONObject payload) {
        if (runtime == null) { toast("工作台还在启动，请稍候"); return; }
        if ((audioBusy || mediaState.optBoolean("busy")) && (action.equals("scan") || action.equals("scan_unit")
                || action.equals("run") || action.equals("quick") || action.equals("restart"))) {
            toast("请先完成或停止当前语音操作"); return;
        }
        worker.execute(() -> {
            try {
                final JSONObject result;
                if (action.equals("restart")) {
                    JSONObject current = parse(runtime.callAttr("snapshot").toString());
                    if (current.optBoolean("busy")) result = object("ok", false);
                    else {
                        JSONObject fresh = parse(runtime.callAttr("initialize", browser,
                            getFilesDir().getAbsolutePath(), "{}").toString());
                        result = object("ok", fresh.optString("error").isEmpty());
                        put(result, "error", fresh.optString("error"));
                    }
                } else result = parse(runtime.callAttr("command", action, payload.toString()).toString());
                if (!result.optBoolean("ok")) main.post(() -> toast(result.optString("error", "操作未完成")));
                if (result.optBoolean("ok") && (action.equals("quick") || action.equals("run"))) main.post(() -> showPage("logs"));
                refreshSnapshot();
            } catch (Exception error) { showError("操作失败", error); }
        });
    }

    private void poll() {
        if (!started || closed || polling || runtime == null) return;
        polling = true;
        snapshots.execute(() -> {
            try { refreshSnapshot(); }
            finally { main.post(() -> { polling = false; if (started && !closed) main.postDelayed(this::poll, 1000); }); }
        });
    }

    private void refreshSnapshot() {
        if (runtime == null || closed) return;
        try {
            JSONObject next = parse(runtime.callAttr("snapshot").toString());
            main.post(() -> {
                if (closed) return;
                state = next;
                updateStatus();
            });
        } catch (Exception error) { showError("状态读取失败", error); }
    }

    @Override public void onPageState(String url, boolean loading) {
        if (page.equals("browser")) {
            try {
                String host = android.net.Uri.parse(url).getHost();
                if (host != null && !host.isEmpty()) bridge.postSubtitle((loading ? "正在加载 · " : "") + host);
            } catch (Exception ignored) { }
        }
    }
    @Override public void onError(String message) { main.post(() -> toast(message)); }
    @Override public void onTabsChanged() { }

    // ---------------------------------------------------------------- offline speech

    private String draftVoice() { return config.optString("tts_voice", "").trim(); }

    private JSONObject audioRequest(String operation, long timeout) {
        JSONObject request = object("op", operation); put(request, "timeoutMs", timeout); return request;
    }

    public void refreshSpeech() {
        refreshSpeechStatus();
        refreshMediaStatus();
        refreshRuntimeAudio();
    }

    private void refreshSpeechStatus() {
        if (closed || browser == null || ttsChecking) return;
        ttsChecking = true;
        JSONObject request = audioRequest("tts_status", 30000);
        put(request, "language", "en-US"); put(request, "offlineOnly", true); put(request, "voice", draftVoice());
        audioChecks.execute(() -> {
            JSONObject result;
            try { result = parse(browser.callJson(request.toString())); }
            catch (Exception failure) { result = object("ok", false); put(result, "error", object("message", "系统语音状态读取失败")); }
            JSONObject response = result;
            main.post(() -> {
                if (closed) return; ttsChecking = false;
                speechState = response.optBoolean("ok") && response.optJSONObject("value") != null
                    ? response.optJSONObject("value") : object("error", rpcError(response));
                publish();
            });
        });
    }

    private void refreshMediaStatus() {
        if (closed || browser == null || audioChecking) return;
        audioChecking = true;
        audioChecks.execute(() -> {
            JSONObject result;
            try { result = parse(browser.callJson(audioRequest("audio_capabilities", 10000).toString())); }
            catch (Exception failure) { result = object("ok", false); put(result, "error", object("message", "本地语音状态读取失败")); }
            JSONObject response = result;
            main.post(() -> {
                if (closed) return; audioChecking = false;
                mediaState = response.optBoolean("ok") && response.optJSONObject("value") != null
                    ? response.optJSONObject("value") : object("error", rpcError(response));
                updateStatus();
            });
        });
    }

    public void downloadModel() {
        startAudioAction(audioRequest("audio_prepare_model", 1200000),
            "正在下载英语识别模型…", "英语识别模型已下载并校验完成");
    }

    public void previewVoice() {
        JSONObject request = audioRequest("tts_speak", 25000);
        put(request, "text", "Hello. This is the Android offline voice preview."); put(request, "language", "en-US");
        put(request, "voice", draftVoice()); put(request, "offlineOnly", true); put(request, "rate", 1.0); put(request, "maxSeconds", 15);
        startAudioAction(request, "正在试听英语离线语音…", "英语试听已完成");
    }

    private void startAudioAction(JSONObject request, String pending, String complete) {
        if (closed || browser == null) return;
        if (audioBusy || state.optBoolean("busy")) { toast("请先停止当前任务或语音操作"); return; }
        audioBusy = true; audioMessage = pending; updateStatus();
        audioActions.execute(() -> {
            JSONObject response;
            try { response = parse(browser.callJson(request.toString())); }
            catch (Exception failure) { response = object("ok", false); put(response, "error", object("message", "语音操作失败，请检查依赖状态")); }
            JSONObject result = response;
            main.post(() -> {
                if (closed) return;
                audioBusy = false;
                JSONObject cause = result.optJSONObject("error");
                boolean cancelled = cause != null && (cause.optString("name").equals("CancelledException") || cause.optString("name").equals("CancellationException"));
                audioMessage = result.optBoolean("ok") ? complete : cancelled ? "语音操作已停止" : rpcError(result);
                updateStatus(); refreshSpeechStatus(); refreshMediaStatus(); refreshRuntimeAudio();
            });
        });
    }

    public void requestStop() {
        cancelAudio();
        invoke("stop", new JSONObject());
    }

    public void cancelAudio() {
        if (closed || browser == null) return;
        ModelImport importing = modelImport;
        if (importing != null) importing.cancelled = true;
        audioMessage = "正在停止语音操作…"; publish();
        audioCancellation.execute(() -> {
            if (importing != null) importing.cancel();
            for (String operation : new String[]{"tts_stop", "audio_cancel"}) {
                try { browser.callJson(audioRequest(operation, 5000).toString()); } catch (Exception ignored) { }
            }
            main.post(() -> {
                if (closed) return;
                audioMessage = audioBusy ? "已请求停止，正在清理语音操作…" : "语音操作已停止";
                publish(); refreshMediaStatus(); refreshSpeechStatus();
            });
        });
    }

    private void refreshRuntimeAudio() {
        PyObject current = runtime;
        if (closed || current == null || runtimeAudioChecking) return;
        runtimeAudioChecking = true;
        audioChecks.execute(() -> {
            try { current.callAttr("command", "audio_status", "{}"); } catch (Exception ignored) { }
            finally { main.post(() -> { if (!closed) runtimeAudioChecking = false; }); }
        });
    }

    private String rpcError(JSONObject result) {
        JSONObject cause = result.optJSONObject("error");
        return cause == null ? result.optString("error", "语音操作未完成") : cause.optString("message", "语音操作未完成");
    }

    public void openSpeechSettings() {
        for (String action : new String[]{"com.android.settings.TTS_SETTINGS", android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS, android.provider.Settings.ACTION_SETTINGS}) {
            try { startActivity(new Intent(action)); return; }
            catch (ActivityNotFoundException | SecurityException unavailable) { }
        }
        toast("此设备无法直接打开语音设置，请在系统设置中搜索「文字转语音」并安装英语离线语音。");
    }

    public void chooseOfflineVoice() {
        JSONArray voices = speechState.optJSONArray("voices");
        List<String> labels = new ArrayList<>(), names = new ArrayList<>();
        labels.add("自动选择英语离线语音"); names.add("");
        if (voices != null) for (int i = 0; i < voices.length(); i++) {
            JSONObject voice = voices.optJSONObject(i);
            if (voice != null && voice.optBoolean("installed") && !voice.optBoolean("networkRequired") && voice.optString("language").startsWith("en")) {
                labels.add(voice.optString("name") + " · " + voice.optString("language")); names.add(voice.optString("name"));
            }
        }
        if (names.size() == 1) { toast("尚未找到已安装的英语离线语音，请先打开系统语音设置安装。"); return; }
        bridge.showVoices(labels, names);
    }

    // ---------------------------------------------------------------- offline model import

    public void chooseModelFile() {
        if (audioBusy || state.optBoolean("busy")) { toast("请先停止当前任务或语音操作"); return; }
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivityForResult(picker, MODEL_PICK_REQUEST); }
        catch (ActivityNotFoundException | SecurityException unavailable) { toast("此设备无法打开系统文件选择器"); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != MODEL_PICK_REQUEST || result != RESULT_OK || data == null || data.getData() == null || closed) return;
        if (audioBusy || state.optBoolean("busy")) { toast("请先停止当前任务或语音操作，再重新选择模型文件"); return; }
        importModel(data.getData());
    }

    private void importModel(Uri uri) {
        if (browser == null || closed) return;
        ModelImport importing = new ModelImport(); modelImport = importing;
        audioBusy = true; audioMessage = "正在复制离线模型，完成后将校验内容…";
        updateStatus();
        Runnable deadline = () -> {
            if (!closed && modelImport == importing) { importing.timedOut = true; cancelAudio(); }
        };
        main.postDelayed(deadline, 120000);
        // ContentResolver may use a remote document provider. Never open/read
        // its stream on the Activity thread and never infer trust from a suffix.
        audioActions.execute(() -> {
            JSONObject response;
            try {
                if (importing.cancelled) throw new IOException("模型导入已取消");
                importing.source = File.createTempFile("model-import-", ".bin", getCacheDir());
                if (importing.cancelled) throw new IOException("模型导入已取消");
                try (AssetFileDescriptor descriptor = getContentResolver().openAssetFileDescriptor(uri, "r", importing.signal)) {
                    if (descriptor == null) throw new IOException("无法读取所选模型文件");
                    if (importing.cancelled) throw new IOException("模型导入已取消");
                    try (InputStream input = descriptor.createInputStream(); FileOutputStream output = new FileOutputStream(importing.source)) {
                        importing.input = input;
                        byte[] buffer = new byte[64 * 1024];
                        int count;
                        while (true) {
                            if (importing.cancelled || Thread.currentThread().isInterrupted()) throw new IOException("模型导入已取消");
                            count = input.read(buffer);
                            if (count == -1) break;
                            if (importing.cancelled || Thread.currentThread().isInterrupted()) throw new IOException("模型导入已取消");
                            if (count == 0) continue;
                            if (importing.received + count > MODEL_IMPORT_BYTES) throw new IOException("所选文件超过 tiny.en 模型的 77.7 MB 上限");
                            output.write(buffer, 0, count); importing.received += count;
                        }
                        if (importing.received != MODEL_IMPORT_BYTES) throw new IOException("所选文件大小与 tiny.en 模型不符；需要完整的 77704715 字节模型");
                        output.getFD().sync();
                    } finally { importing.input = null; }
                }
                if (importing.cancelled || Thread.currentThread().isInterrupted()) throw new IOException("模型导入已取消");
                importing.copying = false;
                JSONObject command = audioRequest("audio_import_model", 120000);
                put(command, "inputPath", importing.source.getCanonicalPath());
                response = parse(browser.callJson(command.toString()));
            } catch (Exception failure) {
                response = object("ok", false);
                JSONObject reason = object("name", importing.timedOut ? "TimeoutException" : importing.cancelled ? "CancelledException" : "ModelImportException");
                put(reason, "message", importing.timedOut ? "离线模型导入超时，已取消；请先将模型完整保存到设备，再重试。"
                    : importing.cancelled ? "离线模型导入已取消" : failure.getMessage() == null ? "无法导入所选模型文件" : failure.getMessage());
                put(response, "error", reason);
            } finally { importing.cleanup(); main.removeCallbacks(deadline); }
            JSONObject result = response;
            main.post(() -> {
                if (closed || modelImport != importing) return;
                modelImport = null; audioBusy = false;
                audioMessage = result.optBoolean("ok") ? "离线模型已通过 SHA-256 校验并保存" : rpcError(result);
                updateStatus(); refreshMediaStatus(); refreshRuntimeAudio();
            });
        });
    }

    private static final class ModelImport {
        final CancellationSignal signal = new CancellationSignal();
        volatile boolean cancelled, timedOut, copying = true;
        volatile long received;
        volatile InputStream input;
        volatile File source;
        void cancel() {
            cancelled = true;
            try { signal.cancel(); } catch (RuntimeException ignored) { }
            cleanup();
        }
        void cleanup() {
            InputStream current = input;
            if (current != null) { try { current.close(); } catch (IOException ignored) { } }
            File temporary = source;
            if (temporary != null) { try { Files.deleteIfExists(temporary.toPath()); } catch (IOException ignored) { } }
        }
    }

    // ---------------------------------------------------------------- lifecycle

    @Override protected void onStart() {
        super.onStart();
        started = true; poll();
        main.removeCallbacks(audioPulse);
        main.post(audioPulse);
        if (page.equals("settings")) refreshSpeechStatus();
    }

    @Override protected void onStop() {
        super.onStop();
        started = false;
        main.removeCallbacks(audioPulse);
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        applyPalette();
        publish();
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("page", page);
        outState.putString("filter", filter);
        outState.putString("config", config.toString());
    }

    @Override public void onBackPressed() {
        if (!page.equals("browser")) { showPage("browser"); return; }
        if (browser != null && browser.getCurrentView() != null && browser.getCurrentView().canGoBack()) {
            browser.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override protected void onDestroy() {
        closed = true;
        main.removeCallbacksAndMessages(null);
        ModelImport importing = modelImport;
        if (importing != null) { modelImport = null; importing.cancelled = true; audioCancellation.execute(importing::cancel); }
        final PyObject current = runtime;
        if (current != null) worker.execute(() -> { try { current.callAttr("shutdown"); } catch (Exception ignored) { } });
        if (browser != null) browser.destroy();
        audioActions.shutdownNow();
        audioChecks.shutdownNow();
        audioCancellation.shutdown();
        worker.shutdown();
        snapshots.shutdownNow();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- helpers

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private JSONObject object(Object... values) {
        JSONObject result = new JSONObject();
        for (int i = 0; i + 1 < values.length; i += 2) put(result, String.valueOf(values[i]), values[i + 1]);
        return result;
    }

    private JSONObject put(JSONObject target, String key, Object value) {
        try { target.put(key, value); } catch (JSONException ignored) { }
        return target;
    }

    private JSONObject parse(String text) {
        try { return new JSONObject(text == null ? "{}" : text); }
        catch (JSONException error) { throw new IllegalStateException("Python 返回了无效数据", error); }
    }

    private void showError(String title, Exception error) {
        Log.e(TAG, title, error);
        main.post(() -> {
            bridge.postSubtitle(title);
            toast(title + "：" + error.getClass().getSimpleName());
        });
    }
}
