package io.github.unipus.helper;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Touch UI and in-process Python automation. No PC or localhost server required. */
public final class MainActivity extends Activity implements BrowserKernel.Listener {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService snapshots = Executors.newSingleThreadExecutor();
    private volatile PyObject runtime;
    private volatile boolean closed;
    private boolean polling;
    private boolean started;
    private boolean settingsDirty;
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
        stopButton = button("停止当前任务", false, () -> invoke("stop", new JSONObject()));
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
        content.addView(text("安卓独立版使用内置 WebView。当前不包含桌面 Whisper/FFmpeg，录音与朗读题会跳过；文本题目复用现有处理逻辑。", 13, secondary));
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
                    if (result.optBoolean("ok")) { settingsDirty = false; toast("已保存；账号或接口修改后请重新加载浏览器"); }
                    else toast(result.optString("error", "保存失败"));
                });
            } catch (Exception error) { showError("保存失败", error); }
        });
    }

    private void invoke(String action, JSONObject payload) {
        if (runtime == null) { toast("工作台还在启动，请稍候"); return; }
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
        boolean busy = state.optBoolean("busy");
        stopButton.setVisibility(busy ? View.VISIBLE : View.GONE);
        if (busy) getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (state.length() > 0) subtitle.setText(state.optString("status", "就绪") +
            (state.optString("error").isEmpty() ? "" : " · " + state.optString("error")));
    }

    @Override public void onPageState(String url, boolean loading) {
        if (page.equals("browser")) {
            try { subtitle.setText((loading ? "正在加载 · " : "") + android.net.Uri.parse(url).getHost()); }
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

    @Override protected void onStart() { super.onStart(); started = true; poll(); }
    @Override protected void onStop() { started = false; super.onStop(); }
    @Override public void onConfigurationChanged(Configuration change) { super.onConfigurationChanged(change); if (page.equals("settings")) captureFields(); refreshPalette(); }
    @Override protected void onSaveInstanceState(Bundle out) { captureFields(); out.putString("page", page); out.putString("filter", filter); out.putString("config", config.toString()); super.onSaveInstanceState(out); }
    @Override public void onBackPressed() { if (!page.equals("browser")) showPage("browser", true); else if (browser != null && browser.getCurrentView() != null && browser.getCurrentView().canGoBack()) browser.goBack(); else super.onBackPressed(); }
    @Override protected void onDestroy() {
        closed = true; main.removeCallbacksAndMessages(null);
        if (runtime != null) worker.execute(() -> { try { runtime.callAttr("shutdown"); } catch (Exception ignored) { } });
        if (browser != null) browser.destroy();
        worker.shutdown(); snapshots.shutdownNow(); super.onDestroy();
    }
}
