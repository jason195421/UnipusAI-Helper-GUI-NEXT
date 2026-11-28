package io.github.unipus.helper;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.text.InputType;
import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Touch UI and in-process Python automation. No PC or localhost server required. */
public final class MainActivity extends Activity implements BrowserKernel.Listener {
    private static final int MODEL_PICK_REQUEST = 72;
    private static final long MODEL_IMPORT_BYTES = 77704715L;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService snapshots = Executors.newSingleThreadExecutor();
    private final ExecutorService audioActions = Executors.newSingleThreadExecutor();
    private final ExecutorService audioChecks = Executors.newFixedThreadPool(2);
    private final ExecutorService audioCancellation = Executors.newSingleThreadExecutor();
    private volatile PyObject runtime;
    private volatile boolean closed;
    private boolean polling;
    private boolean started;
    private boolean settingsDirty;
    private boolean audioBusy, audioChecking, ttsChecking, runtimeAudioChecking;
    private JSONObject speechState = new JSONObject(), mediaState = new JSONObject();
    private String audioMessage = "语音依赖尚未检查";
    private TextView speechLabel, mediaLabel, modelLabel, audioLabel;
    private ProgressBar modelProgress;
    private Button modelDownloadButton, modelImportButton, voicePreviewButton, audioCancelButton;
    private volatile ModelImport modelImport;
    private JSONObject state = new JSONObject();
    private JSONObject config = new JSONObject();
    private String page = "browser";
    private String filter = "all";
    private String rendered = "";
    private LinearLayout root, header, navigation, content;
    private FrameLayout host, browserHost;
    private ScrollView scroll;
    private TextView title, subtitle;
    private Button stopButton;
    private BrowserKernel browser;
    private final Map<String, EditText> fields = new LinkedHashMap<>();
    private int canvas, surface, ink, secondary, line, accent;
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
        buildChrome();
        try {
            browser = new BrowserKernel(this, browserHost, this);
        } catch (RuntimeException error) {
            subtitle.setText("请更新 Android System WebView 后重试");
            new AlertDialog.Builder(this).setTitle("浏览器内核不可用")
                .setMessage(error.getMessage()).setPositiveButton("关闭", (dialog, which) -> finish()).show();
            return;
        }
        showPage(page, false);
        worker.execute(() -> {
            try {
                if (!Python.isStarted()) Python.start(new AndroidPlatform(this));
                runtime = Python.getInstance().getModule("unipus_helper.android_runtime");
                JSONObject initial = parse(runtime.callAttr("initialize", browser,
                    getFilesDir().getAbsolutePath(), config.toString()).toString());
                main.post(() -> { state = initial; rendered = ""; showPage(page, false); poll(); });
            } catch (Exception error) { showError("启动失败", error); }
        });
    }

    private JSONObject readConfig() {
        try {
            File file = new File(getFilesDir(), "config.json");
            if (file.isFile()) return new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
        JSONObject defaults = new JSONObject();
        put(defaults, "theme_mode", "system"); put(defaults, "animations_enabled", true);
        put(defaults, "tts_engine", "android"); put(defaults, "skip_record_mode", "off");
        put(defaults, "url", "https://uai.unipus.cn/sso/index.html?service=https%3A%2F%2Fucloud.unipus.cn%2Fhome");
        return defaults;
    }

    private void applyPalette() {
        String mode = config.optString("theme_mode", "system");
        dark = mode.equals("dark") || (mode.equals("system") &&
            (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES);
        setTheme(dark ? R.style.Theme_UnipusHelper_Dark : R.style.Theme_UnipusHelper);
        canvas = Color.parseColor(dark ? "#181E28" : "#F4F7FB");
        surface = Color.parseColor(dark ? "#222B38" : "#FFFFFF");
        ink = Color.parseColor(dark ? "#F0F5FC" : "#1C2B41");
        secondary = Color.parseColor(dark ? "#BFCCDD" : "#52637B");
        line = Color.parseColor(dark ? "#3A475B" : "#DCE4EF");
        accent = Color.parseColor(dark ? "#60CDFF" : "#0078D4");
        getWindow().setStatusBarColor(canvas);
        getWindow().setNavigationBarColor(canvas);
        getWindow().getDecorView().setSystemUiVisibility(dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
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

    private void buildChrome() {
        root = column(); root.setBackgroundColor(canvas);
        root.setFitsSystemWindows(true);
        setContentView(root);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        header = column(); header.setPadding(dp(16), dp(10), dp(16), dp(8));
        title = text("UnipusAI Helper", 21, ink); title.setTypeface(null, Typeface.BOLD);
        subtitle = text("正在启动手机工作台…", 12, secondary); subtitle.setMaxLines(2);
        header.addView(title); header.addView(subtitle);
        stopButton = button("停止当前任务", false, this::requestStop);
        stopButton.setVisibility(View.GONE); header.addView(stopButton); root.addView(header);
        host = new FrameLayout(this);
        root.addView(host, new LinearLayout.LayoutParams(-1, 0, 1));
        browserHost = new FrameLayout(this); host.addView(browserHost, new FrameLayout.LayoutParams(-1, -1));
        navigation = row(); navigation.setBackgroundColor(surface);
        root.addView(navigation, new LinearLayout.LayoutParams(-1, dp(60)));
    }

    private void renderNavigation() {
        navigation.removeAllViews();
        String[][] tabs = {{"browser", "课程浏览器"}, {"tasks", "任务"}, {"logs", "日志"}, {"settings", "设置"}};
        for (String[] tab : tabs) {
            Button button = button(tab[1], tab[0].equals(page), () -> showPage(tab[0], true));
            button.setTextSize(12); button.setPadding(dp(4), 0, dp(4), 0);
            navigation.addView(button, new LinearLayout.LayoutParams(0, -1, 1));
        }
    }

    private void showPage(String next, boolean animate) {
        if (closed) return;
        if (page.equals("settings")) captureFields();
        speechLabel = null; mediaLabel = null; modelLabel = null; audioLabel = null;
        modelProgress = null; modelDownloadButton = null; modelImportButton = null; voicePreviewButton = null; audioCancelButton = null;
        page = next; rendered = "";
        if (scroll != null) host.removeView(scroll);
        scroll = null; content = null;
        boolean web = next.equals("browser");
        browserHost.setVisibility(web ? View.VISIBLE : View.GONE);
        if (web) {
            title.setText("课程浏览器");
            if (browser != null) browser.showCurrent();
            renderBrowserBar();
        } else {
            removeBrowserBar();
            scroll = new ScrollView(this); scroll.setFillViewport(true);
            content = column(); content.setPadding(dp(16), dp(8), dp(16), dp(16));
            scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
            host.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
            if (next.equals("tasks")) { title.setText("课程任务"); renderTasks(); }
            else if (next.equals("logs")) { title.setText("运行日志"); renderLogs(); }
            else { title.setText("设置"); renderSettings(); }
            if (animate && animationsEnabled()) {
                scroll.setAlpha(0.3f); scroll.setTranslationY(dp(6));
                scroll.animate().alpha(1).translationY(0).setDuration(180).setInterpolator(new DecelerateInterpolator()).start();
            }
        }
        renderNavigation(); updateStatus();
    }

    private LinearLayout browserBar;
    private void removeBrowserBar() { if (browserBar != null) { root.removeView(browserBar); browserBar = null; } }
    private void renderBrowserBar() {
        removeBrowserBar(); browserBar = row(); browserBar.setPadding(dp(8), 0, dp(8), dp(4));
        browserBar.addView(button("后退", false, () -> browser.goBack()), weight());
        browserBar.addView(button("更多", false, this::browserOptions), weight());
        browserBar.addView(button("登录完成", true, () -> invoke("mark_ready", new JSONObject())), weight());
        root.addView(browserBar, 1, new LinearLayout.LayoutParams(-1, dp(48)));
    }

    private void browserOptions() {
        String[] options = {"刷新页面", "打开登录页", "扫描当前单元", "切换浏览器窗口", "允许网页使用麦克风"};
        new AlertDialog.Builder(this).setTitle("课程浏览器").setItems(options, (dialog, choice) -> {
            if (choice == 0) browser.reload();
            else if (choice == 1) browser.loadUrl(config.optString("url", "https://ucloud.unipus.cn/home"));
            else if (choice == 2) invoke("scan_unit", new JSONObject());
            else if (choice == 3) chooseBrowserWindow();
            else if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED)
                toast("麦克风权限已允许，请在网页中重新开始录音");
            else requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 31);
        }).show();
    }

    private void chooseBrowserWindow() {
        worker.execute(() -> {
            JSONObject response = parse(browser.callJson(object("op", "windows").toString()));
            JSONArray handles = response.optJSONArray("value");
            if (!response.optBoolean("ok") || handles == null) return;
            String[] labels = new String[handles.length()];
            for (int i = 0; i < labels.length; i++) labels[i] = "课程窗口 " + (i + 1);
            main.post(() -> new AlertDialog.Builder(this).setTitle("切换窗口").setItems(labels, (dialog, index) ->
                worker.execute(() -> {
                    JSONObject command = object("op", "switch_window"); put(command, "window", handles.optString(index));
                    browser.callJson(command.toString());
                })).show());
        });
    }

    private void renderTasks() {
        if (content == null || scroll == null) return;
        final ScrollView taskScroll = scroll;
        int position = taskScroll.getScrollY(); content.removeAllViews();
        JSONArray tasks = state.optJSONArray("tasks"); if (tasks == null) tasks = new JSONArray();
        int done = 0, selected = 0;
        for (int i = 0; i < tasks.length(); i++) {
            JSONObject task = tasks.optJSONObject(i); if (task == null) continue;
            if (task.optString("status").equals("done")) done++;
            if (task.optBoolean("selected")) selected++;
        }
        LinearLayout summary = card();
        summary.addView(text("全部 " + tasks.length() + "   ·   已完成 " + done + "   ·   已选 " + selected, 15, ink));
        ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(Math.max(1, tasks.length())); progress.setProgress(done);
        summary.addView(progress, new LinearLayout.LayoutParams(-1, dp(20))); content.addView(summary);
        LinearLayout actions = row();
        actions.addView(button("扫描课程", true, () -> invoke("scan", new JSONObject())), weight());
        actions.addView(button("处理当前页", false, () -> new AlertDialog.Builder(this).setTitle("处理当前页")
            .setMessage("开始处理课程浏览器当前打开的题目？").setNegativeButton("取消", null)
            .setPositiveButton("开始", (d, w) -> invoke("quick", new JSONObject())).show()), weight());
        actions.addView(button(state.optBoolean("busy") ? "停止" : "开始处理", false,
            () -> invoke(state.optBoolean("busy") ? "stop" : "run", new JSONObject())), weight());
        content.addView(actions);
        LinearLayout filters = row();
        for (String value : new String[]{"all", "pending", "required", "done"}) {
            String label = value.equals("all") ? "全部" : value.equals("pending") ? "未完成" : value.equals("required") ? "必修" : "已完成";
            filters.addView(button(label, filter.equals(value), () -> { filter = value; renderTasks(); }), weight());
        }
        content.addView(filters);
        LinearLayout selection = row();
        selection.addView(button("选择未完成必修", false, () -> invoke("select_mode", object("mode", "pending"))), weight());
        selection.addView(button("清空选择", false, () -> invoke("select_mode", object("mode", "none"))), weight());
        content.addView(selection);
        if (!state.optBoolean("ready")) content.addView(text("请先在课程浏览器完成登录，再点击「登录完成」。", 13, secondary));
        int visible = 0;
        for (int i = 0; i < tasks.length(); i++) {
            JSONObject task = tasks.optJSONObject(i); if (task == null) continue;
            boolean finished = task.optString("status").equals("done");
            if (filter.equals("done") && !finished || filter.equals("pending") && finished || filter.equals("required") && !task.optBoolean("required")) continue;
            visible++;
            LinearLayout tile = card();
            CheckBox check = new CheckBox(this); check.setText(task.optString("title")); check.setTextColor(ink); check.setTextSize(15);
            check.setMinHeight(dp(48)); check.setChecked(task.optBoolean("selected")); check.setEnabled(!state.optBoolean("busy"));
            check.setOnCheckedChangeListener((button, chosen) -> {
                JSONObject payload = object("selected", chosen); put(payload, "ids", new JSONArray().put(task.optString("id")));
                invoke("select", payload);
            });
            tile.addView(check);
            String status = finished ? "已完成" : task.optString("status").equals("doing") ? "进行中" : "未完成";
            String score = task.isNull("score") || !task.has("score") ? "" : " · " + task.optString("score") + " 分";
            TextView meta = text(task.optString("unit") + " · " + (task.optBoolean("required") ? "必修" : "选修") + " · " + status + score, 12, secondary);
            meta.setPadding(dp(8), 0, 0, 0); tile.addView(meta); content.addView(tile);
        }
        if (visible == 0) content.addView(text(tasks.length() == 0 ? "尚未扫描课程任务" : "没有匹配的任务", 16, secondary));
        taskScroll.post(() -> taskScroll.scrollTo(0, position));
    }

    private void renderLogs() {
        if (content == null || scroll == null) return;
        final ScrollView logScroll = scroll;
        int position = logScroll.getScrollY(); content.removeAllViews();
        JSONArray logs = state.optJSONArray("logs");
        if (logs == null || logs.length() == 0) { content.addView(text("暂无日志", 15, secondary)); return; }
        for (int i = Math.max(0, logs.length() - 150); i < logs.length(); i++) {
            JSONObject entry = logs.optJSONObject(i); if (entry == null) continue;
            LinearLayout tile = card();
            tile.addView(text(entry.optString("time") + " · " + entry.optString("level"), 12, secondary));
            TextView message = text(entry.optString("message"), 14, ink); message.setTextIsSelectable(true);
            tile.addView(message); content.addView(tile);
        }
        logScroll.post(() -> logScroll.scrollTo(0, position));
    }

    private void renderSettings() {
        fields.clear();
        LinearLayout appearance = card(); appearance.addView(section("外观与动效"));
        Switch darkSwitch = toggle("深色模式", dark);
        darkSwitch.setOnCheckedChangeListener((button, checked) -> {
            captureFields(); put(config, "theme_mode", checked ? "dark" : "light"); settingsDirty = true; refreshPalette();
        }); appearance.addView(darkSwitch);
        appearance.addView(button("跟随系统", false, () -> { captureFields(); put(config, "theme_mode", "system"); settingsDirty = true; refreshPalette(); }));
        Switch animationSwitch = toggle("界面动画", config.optBoolean("animations_enabled", true));
        animationSwitch.setOnCheckedChangeListener((button, checked) -> {
            put(config, "animations_enabled", checked); settingsDirty = true;
            if (scroll != null) { scroll.animate().cancel(); scroll.setAlpha(1); scroll.setTranslationY(0); }
        }); appearance.addView(animationSwitch); content.addView(appearance);
        content.addView(text("外观立即生效，点击保存后保留。处理任务时请保持应用在前台。", 12, secondary));
        content.addView(section("账号与登录"));
        field("username", "U 校园账号（也可在浏览器手动登录）", false);
        field("password", "密码", true); field("url", "登录地址", false);
        content.addView(section("AI 接口（可选）"));
        field("api_key", "API Key", true); field("base_url", "API 地址", false); field("model", "模型名称", false);
        renderAudioSettings();
        Button save = button("保存设置", true, this::saveSettings);
        content.addView(save, new LinearLayout.LayoutParams(-1, dp(52)));
        content.addView(button("重新加载课程浏览器", false, () -> invoke("restart", new JSONObject())));
    }

    private void field(String key, String label, boolean secret) {
        TextView caption = text(label, 13, secondary); caption.setPadding(0, dp(14), 0, dp(6)); content.addView(caption);
        EditText input = new EditText(this); input.setText(config.optString(key)); input.setSingleLine(true); input.setTextSize(15);
        input.setTextColor(ink); input.setHintTextColor(secondary); input.setMinHeight(dp(48)); input.setPadding(dp(12), dp(8), dp(12), dp(8));
        input.setInputType(InputType.TYPE_CLASS_TEXT | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));
        input.setBackground(shape(surface, line)); input.setContentDescription(label);
        fields.put(key, input); content.addView(input, new LinearLayout.LayoutParams(-1, -2));
    }

    private void captureFields() {
        for (Map.Entry<String, EditText> entry : fields.entrySet()) put(config, entry.getKey(), entry.getValue().getText().toString());
    }

    private void refreshPalette() {
        applyPalette(); root.setBackgroundColor(canvas); navigation.setBackgroundColor(surface);
        title.setTextColor(ink); subtitle.setTextColor(secondary); showPage(page, false);
    }

    private void saveSettings() {
        if (runtime == null) { toast("工作台还在启动，请稍候"); return; }
        captureFields(); final String payload = config.toString();
        worker.execute(() -> {
            try {
                JSONObject result = parse(runtime.callAttr("save_config", payload).toString());
                main.post(() -> {
                    if (result.optBoolean("ok")) { settingsDirty = false; toast("已保存；账号、接口或语音选项修改后请重新加载浏览器"); }
                    else toast(result.optString("error", "保存失败"));
                });
            } catch (Exception error) { showError("保存失败", error); }
        });
    }

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
                if (result.optBoolean("ok") && (action.equals("quick") || action.equals("run"))) main.post(() -> showPage("logs", true));
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
                if (closed) return; state = next; updateStatus();
                String signature = page.equals("tasks") ? next.optString("tasks") + next.optBoolean("busy") + next.optBoolean("ready")
                    : page.equals("logs") ? next.optString("logs") : "";
                if (!signature.equals(rendered)) {
                    rendered = signature;
                    if (page.equals("tasks")) renderTasks(); else if (page.equals("logs")) renderLogs();
                }
            });
        } catch (Exception error) { showError("状态读取失败", error); }
    }

    private void updateStatus() {
        boolean busy = state.optBoolean("busy") || audioBusy || mediaState.optBoolean("busy");
        stopButton.setVisibility(busy ? View.VISIBLE : View.GONE);
        stopButton.setText(state.optBoolean("busy") ? "停止当前任务" : "停止语音操作");
        if (busy) getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (state.length() > 0) subtitle.setText(state.optString("status", "就绪") +
            (state.optString("error").isEmpty() ? "" : " · " + state.optString("error")));
    }

    @Override public void onPageState(String url, boolean loading) {
        if (page.equals("browser")) {
            try {
                String host = android.net.Uri.parse(url).getHost();
                if (host != null && !host.isEmpty()) subtitle.setText((loading ? "正在加载 · " : "") + host);
            }
            catch (Exception ignored) { }
        }
    }
    @Override public void onError(String message) { main.post(() -> toast(message)); }
    @Override public void onTabsChanged() { }

    private boolean animationsEnabled() {
        float scale = android.provider.Settings.Global.getFloat(getContentResolver(), android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
        return config.optBoolean("animations_enabled", true) && scale > 0;
    }
    private LinearLayout column() { LinearLayout value = new LinearLayout(this); value.setOrientation(LinearLayout.VERTICAL); return value; }
    private LinearLayout row() { LinearLayout value = new LinearLayout(this); value.setOrientation(LinearLayout.HORIZONTAL); value.setGravity(Gravity.CENTER_VERTICAL); return value; }
    private LinearLayout.LayoutParams weight() { return new LinearLayout.LayoutParams(0, dp(48), 1); }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView text(String value, float size, int color) { TextView label = new TextView(this); label.setText(value); label.setTextSize(size); label.setTextColor(color); return label; }
    private TextView section(String value) { TextView label = text(value, 17, ink); label.setTypeface(null, Typeface.BOLD); label.setPadding(0, dp(14), 0, dp(8)); return label; }
    private LinearLayout card() {
        LinearLayout tile = column(); tile.setBackground(shape(surface, line)); tile.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.bottomMargin = dp(10); tile.setLayoutParams(params); return tile;
    }
    private GradientDrawable shape(int fill, int stroke) { GradientDrawable drawable = new GradientDrawable(); drawable.setColor(fill); drawable.setCornerRadius(dp(12)); drawable.setStroke(dp(1), stroke); return drawable; }
    private Button button(String label, boolean primary, Runnable click) {
        Button value = new Button(this); value.setText(label); value.setTextSize(13); value.setAllCaps(false); value.setMinHeight(dp(48));
        value.setTextColor(primary ? (dark ? Color.BLACK : Color.WHITE) : ink); value.setBackgroundTintList(android.content.res.ColorStateList.valueOf(primary ? accent : surface));
        value.setOnClickListener(view -> click.run()); return value;
    }
    private Switch toggle(String label, boolean checked) { Switch value = new Switch(this); value.setText(label); value.setTextColor(ink); value.setTextSize(15); value.setMinHeight(dp(48)); value.setChecked(checked); return value; }
    private static JSONObject object(String key, Object value) { JSONObject data = new JSONObject(); put(data, key, value); return data; }
    private static void put(JSONObject data, String key, Object value) { try { data.put(key, value); } catch (JSONException error) { throw new IllegalArgumentException(error); } }
    private static JSONObject parse(String value) { try { return new JSONObject(value); } catch (JSONException error) { throw new IllegalStateException("Python 返回了无效数据", error); } }
    private void toast(String message) { if (!closed) android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show(); }
    private void showError(String title, Exception error) { main.post(() -> { if (!closed) { subtitle.setText(title); toast(title + "：" + error.getClass().getSimpleName()); android.util.Log.e("UnipusHelper", title, error); } }); }

    @Override protected void onStart() {
        super.onStart(); started = true; poll();
        main.removeCallbacks(audioPulse); main.post(audioPulse);
        if (page.equals("settings")) refreshSpeechStatus();
    }
    @Override protected void onStop() { started = false; main.removeCallbacks(audioPulse); super.onStop(); }
    @Override public void onConfigurationChanged(Configuration change) { super.onConfigurationChanged(change); if (page.equals("settings")) captureFields(); refreshPalette(); }
    @Override protected void onSaveInstanceState(Bundle out) { captureFields(); out.putString("page", page); out.putString("filter", filter); out.putString("config", config.toString()); super.onSaveInstanceState(out); }
    @Override public void onBackPressed() { if (!page.equals("browser")) showPage("browser", true); else if (browser != null && browser.getCurrentView() != null && browser.getCurrentView().canGoBack()) browser.goBack(); else super.onBackPressed(); }
    @Override protected void onDestroy() {
        closed = true; main.removeCallbacksAndMessages(null);
        ModelImport importing = modelImport;
        if (importing != null) { importing.cancelled = true; audioCancellation.execute(importing::cancel); }
        if (runtime != null) worker.execute(() -> { try { runtime.callAttr("shutdown"); } catch (Exception ignored) { } });
        if (browser != null) browser.destroy();
        audioActions.shutdownNow(); audioChecks.shutdownNow(); audioCancellation.shutdown();
        worker.shutdown(); snapshots.shutdownNow(); super.onDestroy();
    }

    private void renderAudioSettings() {
        content.addView(section("录音与离线语音"));
        Switch recording = toggle("自动录音与朗读", !config.optString("skip_record_mode", "off").equals("all")
                && !config.optString("tts_engine", "android").equals("off"));
        recording.setOnCheckedChangeListener((button, checked) -> {
            put(config, "tts_engine", checked ? "android" : "off");
            put(config, "skip_record_mode", checked ? "off" : "all");
            settingsDirty = true;
        });
        content.addView(recording);
        content.addView(text("保存并重新加载课程浏览器后应用。自动录音直接注入音频，不需要用扬声器回录；网页和题型仍需支持录音。", 12, secondary));
        field("tts_voice", "英语离线语音名称（留空自动选择）", false);
        content.addView(button("选择已安装的英语离线语音", false, this::chooseOfflineVoice));
        LinearLayout speech = card();
        speechLabel = text("正在检查系统语音引擎…", 13, ink); speech.addView(speechLabel);
        speech.addView(button("打开系统语音设置", false, this::openSpeechSettings));
        voicePreviewButton = button("试听英语", false, this::previewVoice); speech.addView(voicePreviewButton);
        speech.addView(text("请在系统文字转语音设置中安装英语离线语音。试听通过扬声器播放；自动录音使用独立合成的 WAV 文件。", 12, secondary));
        content.addView(speech);
        LinearLayout local = card(); local.addView(section("本地 Whisper / FFmpeg"));
        mediaLabel = text("正在检查本地解码与识别引擎…", 13, ink); local.addView(mediaLabel);
        modelLabel = text("模型尚未检查", 13, secondary); local.addView(modelLabel);
        modelProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        modelProgress.setMax(1000); local.addView(modelProgress, new LinearLayout.LayoutParams(-1, dp(20)));
        modelDownloadButton = button("下载英语识别模型", true, this::confirmModelDownload); local.addView(modelDownloadButton);
        modelImportButton = button("导入离线模型", false, this::chooseModelFile); local.addView(modelImportButton);
        local.addView(text("模型可单独下载或从文件导入。官方源失败后会尝试备用源，所有来源都校验相同 SHA-256；不会仅凭文件名接受模型。", 12, secondary));
        content.addView(local);
        audioLabel = text(audioMessage, 13, secondary); content.addView(audioLabel);
        audioCancelButton = button("停止试听 / 下载 / 识别", false, this::cancelAudio); content.addView(audioCancelButton);
        content.addView(button("刷新语音依赖状态", false, () -> {
            refreshSpeechStatus(); refreshMediaStatus(); refreshRuntimeAudio();
        }));
        updateAudioLabels(); refreshSpeechStatus(); refreshMediaStatus();
    }

    private String draftVoice() {
        EditText input = fields.get("tts_voice");
        return input == null ? config.optString("tts_voice", "").trim() : input.getText().toString().trim();
    }

    private JSONObject audioRequest(String operation, long timeout) {
        JSONObject request = object("op", operation); put(request, "timeoutMs", timeout); return request;
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
                updateAudioLabels();
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
                updateAudioLabels(); updateStatus();
            });
        });
    }

    private void updateAudioLabels() {
        if (speechLabel == null) return;
        String speechError = speechState.optString("error");
        String engineName = speechState.optString("engine");
        JSONArray engines = speechState.optJSONArray("engines");
        if (engines != null) for (int i = 0; i < engines.length(); i++) {
            JSONObject entry = engines.optJSONObject(i);
            if (entry != null && entry.optString("name").equals(engineName)) engineName = entry.optString("label", engineName);
        }
        speechLabel.setText(speechState.length() == 0 ? "正在检查系统语音引擎…"
                : speechState.optBoolean("available") ? "英语离线朗读已就绪" + (engineName.isEmpty() ? "" : " · " + engineName)
                : speechError.isEmpty() ? "英语离线语音未就绪，请安装系统语音引擎和英语语音数据。" : speechError);
        boolean nativeReady = mediaState.optBoolean("whisperAvailable") && mediaState.optBoolean("ffmpegAvailable");
        mediaLabel.setText(mediaState.length() == 0 ? "正在检查本地解码与识别引擎…"
                : nativeReady ? "FFmpeg 解码和 Whisper 本地识别引擎已就绪"
                : mediaState.optString("error", "本地引擎不可用，请安装包含本设备架构的完整 APK。"));
        String modelState = mediaState.optString("modelState");
        boolean downloading = modelState.equals("downloading"), verifying = modelState.equals("verifying");
        double progress = Math.max(0, Math.min(1, mediaState.optDouble("modelProgress", 0)));
        String model = mediaState.optString("modelName", "tiny.en");
        String size = String.format(Locale.ROOT, "%.1f MB", mediaState.optLong("modelBytes", 77704715) / 1000000.0);
        String modelText = mediaState.optBoolean("modelReady") ? model + " 模型已就绪 · " + size
                : downloading ? "正在下载 " + model + " · " + Math.round(progress * 100) + "% / " + size
                : verifying ? "正在校验本地模型…" : model + " 模型未就绪 · 下载大小 " + size;
        if (!mediaState.optString("error").isEmpty()) modelText += "\n" + mediaState.optString("error");
        ModelImport importing = modelImport;
        if (importing != null && importing.copying) {
            progress = Math.min(1, (double) importing.received / MODEL_IMPORT_BYTES);
            modelText = "正在复制所选离线模型 · " + Math.round(progress * 100) + "%";
            verifying = false;
        }
        modelLabel.setText(modelText); modelProgress.setProgress((int) Math.round(progress * 1000));
        modelProgress.setIndeterminate(verifying);
        modelDownloadButton.setEnabled(nativeReady && !audioBusy && !mediaState.optBoolean("busy") && !mediaState.optBoolean("modelReady") && !state.optBoolean("busy"));
        modelDownloadButton.setText(mediaState.optBoolean("modelReady") ? "英语识别模型已安装" : "下载英语识别模型（" + size + "）");
        modelImportButton.setEnabled(!audioBusy && !mediaState.optBoolean("busy") && !state.optBoolean("busy"));
        voicePreviewButton.setEnabled(speechState.optBoolean("available") && !audioBusy && !state.optBoolean("busy"));
        audioCancelButton.setEnabled(audioBusy || mediaState.optBoolean("busy") || speechState.optBoolean("busy"));
        audioLabel.setText(audioMessage.equals("语音依赖尚未检查")
                && (speechState.length() > 0 || mediaState.length() > 0) ? "语音依赖状态已更新" : audioMessage);
    }

    private void confirmModelDownload() {
        String size = String.format(Locale.ROOT, "%.1f MB", mediaState.optLong("modelBytes", 77704715) / 1000000.0);
        new AlertDialog.Builder(this).setTitle("下载英语识别模型")
                .setMessage("将下载 " + size + " 的 tiny.en 模型并校验完整性。官方源连接失败会尝试备用源，SHA-256 校验值不变。建议连接 Wi-Fi，保持应用在前台；也可取消后导入已下载的离线模型。")
                .setNegativeButton("取消", null).setPositiveButton("下载", (dialog, which) ->
                        startAudioAction(audioRequest("audio_prepare_model", 1200000), "正在下载英语识别模型…", "英语识别模型已下载并校验完成")).show();
    }

    private void previewVoice() {
        JSONObject request = audioRequest("tts_speak", 25000);
        put(request, "text", "Hello. This is the Android offline voice preview."); put(request, "language", "en-US");
        put(request, "voice", draftVoice()); put(request, "offlineOnly", true); put(request, "rate", 1.0); put(request, "maxSeconds", 15);
        startAudioAction(request, "正在试听英语离线语音…", "英语试听已完成");
    }

    private void startAudioAction(JSONObject request, String pending, String complete) {
        if (closed || browser == null) return;
        if (audioBusy || state.optBoolean("busy")) { toast("请先停止当前任务或语音操作"); return; }
        audioBusy = true; audioMessage = pending; updateAudioLabels(); updateStatus();
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
                updateAudioLabels(); updateStatus(); refreshSpeechStatus(); refreshMediaStatus(); refreshRuntimeAudio();
            });
        });
    }

    private void requestStop() { cancelAudio(); if (runtime != null) invoke("stop", new JSONObject()); }

    private void cancelAudio() {
        if (closed || browser == null) return;
        ModelImport importing = modelImport;
        if (importing != null) importing.cancelled = true;
        audioMessage = "正在停止语音操作…"; updateAudioLabels();
        audioCancellation.execute(() -> {
            if (importing != null) importing.cancel();
            for (String operation : new String[]{"tts_stop", "audio_cancel"}) {
                try { browser.callJson(audioRequest(operation, 5000).toString()); } catch (Exception ignored) { }
            }
            main.post(() -> {
                if (closed) return;
                audioMessage = audioBusy ? "已请求停止，正在清理语音操作…" : "语音操作已停止";
                updateAudioLabels(); refreshMediaStatus(); refreshSpeechStatus();
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

    private void openSpeechSettings() {
        captureFields();
        for (String action : new String[]{"com.android.settings.TTS_SETTINGS", android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS, android.provider.Settings.ACTION_SETTINGS}) {
            try { startActivity(new Intent(action)); return; }
            catch (ActivityNotFoundException | SecurityException unavailable) { }
        }
        toast("此设备无法直接打开语音设置，请在系统设置中搜索「文字转语音」并安装英语离线语音。");
    }

    private void chooseOfflineVoice() {
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
        new AlertDialog.Builder(this).setTitle("英语离线语音").setItems(labels.toArray(new String[0]), (dialog, index) -> {
            put(config, "tts_voice", names.get(index)); settingsDirty = true;
            EditText input = fields.get("tts_voice"); if (input != null) input.setText(names.get(index));
            refreshSpeechStatus();
        }).show();
    }

    private void chooseModelFile() {
        if (audioBusy || state.optBoolean("busy")) { toast("请先停止当前任务或语音操作"); return; }
        captureFields();
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
        updateAudioLabels(); updateStatus();
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
                updateAudioLabels(); updateStatus(); refreshMediaStatus(); refreshRuntimeAudio();
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
}
