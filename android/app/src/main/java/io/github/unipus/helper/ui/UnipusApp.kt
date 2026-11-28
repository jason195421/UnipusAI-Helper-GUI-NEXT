package io.github.unipus.helper.ui

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.unipus.helper.MainActivity
import io.github.unipus.helper.R
import org.json.JSONArray
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val TabKeys = listOf("browser", "tasks", "logs", "settings")
private val TabLabels = listOf("课程浏览器", "任务", "日志", "设置")
private val TabIcons = intArrayOf(
    R.drawable.ic_tab_browser,
    R.drawable.ic_tab_tasks,
    R.drawable.ic_tab_logs,
    R.drawable.ic_tab_settings,
)
private val FilterKeys = listOf("all", "pending", "required", "done")
private val FilterLabels = listOf("全部", "未完成", "必修", "已完成")

/** Installs the Compose UI on a [ComponentActivity]. Called from the Java controller. */
fun attachUnipusUi(
    activity: ComponentActivity,
    controller: MainActivity,
    bridge: UiBridge,
    browserHost: FrameLayout,
) {
    bridge.bind(controller)
    activity.setContent {
        UnipusApp(bridge, controller, browserHost)
    }
}

@Composable
fun UnipusApp(bridge: UiBridge, controller: MainActivity, browserHost: FrameLayout) {
    val palette = remember(bridge.dark) { miuixPalette(bridge.dark) }
    MiuixTheme(colors = palette) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = pageTitle(bridge.page),
                    subtitle = bridge.subtitle,
                    actions = {
                        if (bridge.busy) {
                            TextButton(
                                text = if (bridge.snapshot.optBoolean("busy")) "停止当前任务" else "停止语音操作",
                                onClick = { controller.requestStop() },
                                colors = ButtonDefaults.textButtonColorsPrimary(),
                                insideMargin = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                    },
                )
            },
            bottomBar = {
                NavigationBar {
                    TabKeys.forEachIndexed { index, key ->
                        NavigationBarItem(
                            selected = bridge.page == key,
                            onClick = { controller.showPage(key) },
                            icon = ImageVector.vectorResource(TabIcons[index]),
                            label = TabLabels[index],
                        )
                    }
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    AndroidView(
                        factory = {
                            (browserHost.parent as? ViewGroup)?.removeView(browserHost)
                            browserHost
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    when (bridge.page) {
                        "tasks" -> TasksPage(bridge, controller)
                        "logs" -> LogsPage(bridge)
                        "settings" -> SettingsPage(bridge, controller)
                        else -> Unit
                    }
                }
                if (bridge.page == "browser") BrowserBar(bridge, controller)
            }
            PageDialogs(bridge, controller)
        }
    }
}

private fun pageTitle(page: String): String = when (page) {
    "tasks" -> "课程任务"
    "logs" -> "运行日志"
    "settings" -> "设置"
    else -> "课程浏览器"
}

@Composable
private fun BrowserBar(bridge: UiBridge, controller: MainActivity) {
    var showOptions by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(MiuixTheme.colorScheme.surface)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(text = "后退", onClick = { controller.goBack() }, modifier = Modifier.weight(1f))
            TextButton(text = "更多", onClick = { showOptions = true }, modifier = Modifier.weight(1f))
            TextButton(
                text = "登录完成",
                onClick = { controller.markReady() },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
    OverlayDialog(
        show = showOptions,
        title = "课程浏览器",
        onDismissRequest = { showOptions = false },
    ) {
        Column(Modifier.fillMaxWidth()) {
            DialogRow("刷新页面") { showOptions = false; controller.reloadPage() }
            DialogRow("打开登录页") { showOptions = false; controller.loadLoginPage() }
            DialogRow("扫描当前单元") { showOptions = false; controller.scanUnit() }
            DialogRow("切换浏览器窗口") { showOptions = false; controller.openWindows() }
            DialogRow("允许网页使用麦克风") { showOptions = false; controller.requestMicrophone() }
        }
        Spacer(Modifier.height(8.dp))
        TextButton(
            text = "关闭",
            onClick = { showOptions = false },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun PageDialogs(bridge: UiBridge, controller: MainActivity) {
    val windows = bridge.windowHandles
    OverlayDialog(
        show = windows != null,
        title = "切换窗口",
        summary = "选择要操作的课程窗口",
        onDismissRequest = { bridge.windowHandles = null },
    ) {
        Column(Modifier.fillMaxWidth()) {
            windows?.forEachIndexed { index, handle ->
                DialogRow("课程窗口 " + (index + 1)) {
                    bridge.windowHandles = null
                    controller.switchWindow(handle)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        TextButton("取消", { bridge.windowHandles = null }, Modifier.fillMaxWidth())
    }

    val voices = bridge.voiceChoices
    OverlayDialog(
        show = voices != null,
        title = "英语离线语音",
        onDismissRequest = { bridge.voiceChoices = null },
    ) {
        Column(Modifier.fillMaxWidth()) {
            voices?.forEach { (label, name) ->
                DialogRow(label) {
                    bridge.voiceChoices = null
                    controller.pickVoice(name)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        TextButton("取消", { bridge.voiceChoices = null }, Modifier.fillMaxWidth())
    }
}

@Composable
private fun DialogRow(title: String, onClick: () -> Unit) {
    BasicComponent(title = title, onClick = onClick)
}

@Composable
private fun PageBackground(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) { content() }
}

@Composable
private fun TasksPage(bridge: UiBridge, controller: MainActivity) {
    val state = bridge.snapshot
    val tasks: JSONArray = state.optJSONArray("tasks") ?: JSONArray()
    val busy = bridge.busy
    val ready = state.optBoolean("ready")
    val filter = bridge.filter
    var done = 0
    var selectedCount = 0
    val visible = ArrayList<JSONObject>()
    for (index in 0 until tasks.length()) {
        val task = tasks.optJSONObject(index) ?: continue
        val finished = task.optString("status") == "done"
        if (finished) done++
        if (task.optBoolean("selected")) selectedCount++
        if (filter == "pending" && finished) continue
        if (filter == "done" && !finished) continue
        if (filter == "required" && !task.optBoolean("required")) continue
        visible.add(task)
    }
    PageBackground {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 14.dp)) {
                    Text(
                        text = "全部 " + tasks.length() + "   ·   已完成 " + done + "   ·   已选 " + selectedCount,
                        style = MiuixTheme.textStyles.body1,
                    )
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = if (tasks.length() == 0) 0f else done.toFloat() / tasks.length(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { controller.scanCourses() },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) { Text("扫描课程") }
                    Button(onClick = { controller.quickProcess() }, modifier = Modifier.weight(1f)) { Text("处理当前页") }
                    Button(
                        onClick = { if (busy) controller.requestStop() else controller.startRun() },
                        modifier = Modifier.weight(1f),
                    ) { Text(if (busy) "停止" else "开始处理") }
                }
            }
            item {
                TabRow(
                    tabs = FilterLabels,
                    selectedTabIndex = FilterKeys.indexOf(filter).coerceAtLeast(0),
                    onTabSelected = { controller.setFilter(FilterKeys[it]) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { controller.selectMode("pending") },
                        modifier = Modifier.weight(1f),
                        enabled = !busy,
                    ) { Text("选择未完成必修") }
                    Button(
                        onClick = { controller.selectMode("none") },
                        modifier = Modifier.weight(1f),
                        enabled = !busy,
                    ) { Text("清空选择") }
                }
            }
            if (!ready) {
                item {
                    Text(
                        text = "请先在课程浏览器完成登录，再点击「登录完成」。",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            items(items = visible, key = { it.optString("id") }) { task ->
                TaskCard(task, busy, controller)
            }
            if (visible.isEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                        Text(
                            text = if (tasks.length() == 0) "尚未扫描课程任务" else "没有匹配的任务",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskCard(task: JSONObject, busy: Boolean, controller: MainActivity) {
    val selected = task.optBoolean("selected")
    val id = task.optString("id")
    Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
        BasicComponent(
            title = task.optString("title"),
            summary = taskMeta(task),
            startAction = {
                Checkbox(
                    state = if (selected) ToggleableState.On else ToggleableState.Off,
                    onClick = if (busy) null else ({ controller.toggleTask(id, !selected) }),
                )
            },
        )
    }
}

private fun taskMeta(task: JSONObject): String {
    val parts = ArrayList<String>()
    parts.add(task.optString("unit", "当前单元"))
    parts.add(if (task.optBoolean("required")) "必修" else "选修")
    parts.add(
        when (task.optString("status")) {
            "done" -> "已完成"
            "doing" -> "进行中"
            else -> "未完成"
        },
    )
    val score = task.opt("score")
    if (score != null && score != JSONObject.NULL) parts.add(score.toString() + " 分")
    return parts.joinToString(" · ")
}

@Composable
private fun LogsPage(bridge: UiBridge) {
    val logs: JSONArray = bridge.snapshot.optJSONArray("logs") ?: JSONArray()
    val start = maxOf(0, logs.length() - 150)
    PageBackground {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (logs.length() == 0) {
                item {
                    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                        Text(
                            text = "暂无日志",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
            for (index in start until logs.length()) {
                val entry = logs.optJSONObject(index) ?: continue
                item(key = "log-" + entry.optString("id", index.toString())) {
                    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(
                            text = entry.optString("time") + " · " + entry.optString("level", "info"),
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        val message = entry.optString("message")
                        if (message.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(text = message, style = MiuixTheme.textStyles.paragraph)
                        }
                    }
                }
            }
        }
    }
}
