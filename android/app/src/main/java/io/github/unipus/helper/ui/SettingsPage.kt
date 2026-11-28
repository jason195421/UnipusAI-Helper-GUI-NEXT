package io.github.unipus.helper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.unipus.helper.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun SettingsPage(bridge: UiBridge, controller: MainActivity) {
    val config = bridge.config
    val speech = bridge.speech
    val media = bridge.media
    val busy = bridge.busy
    val audioBusy = bridge.audioBusy
    val mediaBusy = media.optBoolean("busy")
    val speechBusy = speech.optBoolean("busy")
    val nativeReady = media.optBoolean("whisperAvailable") && media.optBoolean("ffmpegAvailable")
    val modelReady = media.optBoolean("modelReady")
    val speechReady = speech.optBoolean("available")
    val modelText = modelLabelText(media, bridge.importProgress)
    var confirmDownload by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState())
            .padding(PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 16.dp)),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SmallTitle("外观与动效")
        Card(Modifier.fillMaxWidth()) {
            BasicComponent(
                title = "深色模式",
                summary = "深色界面与浅色界面一键切换",
                endActions = {
                    Switch(
                        checked = bridge.dark,
                        onCheckedChange = { controller.setThemeMode(if (it) "dark" else "light") },
                    )
                },
            )
            BasicComponent(
                title = "跟随系统",
                summary = "由系统的深色模式设置决定界面外观",
                onClick = { controller.setThemeMode("system") },
            )
            BasicComponent(
                title = "界面动画",
                summary = "页面切换时使用淡入与位移动画",
                endActions = {
                    Switch(
                        checked = config.optBoolean("animations_enabled", true),
                        onCheckedChange = { controller.setAnimationsEnabled(it) },
                    )
                },
            )
            PaddedBlock {
                NoteText("外观立即生效，点击保存后保留。处理任务时请保持应用在前台。")
            }
        }

        SmallTitle("账号与登录")
        Card(Modifier.fillMaxWidth()) {
            PaddedBlock {
                ConfigField(bridge, controller, "username", "U 校园账号（也可在浏览器手动登录）")
                ConfigField(bridge, controller, "password", "密码", secret = true)
                ConfigField(bridge, controller, "url", "登录地址")
            }
        }

        SmallTitle("AI 接口（可选）")
        Card(Modifier.fillMaxWidth()) {
            PaddedBlock {
                ConfigField(bridge, controller, "api_key", "API Key", secret = true)
                ConfigField(bridge, controller, "base_url", "API 地址")
                ConfigField(bridge, controller, "model", "模型名称")
            }
        }

        SmallTitle("录音与离线语音")
        Card(Modifier.fillMaxWidth()) {
            BasicComponent(
                title = "自动录音与朗读",
                summary = "保存并重新加载课程浏览器后应用",
                endActions = {
                    Switch(
                        checked = speechEnabled(config),
                        onCheckedChange = { controller.setSpeechEnabled(it) },
                    )
                },
            )
            PaddedBlock {
                NoteText("自动录音直接注入音频，不需要用扬声器回录；网页和题型仍需支持录音。")
                Spacer(Modifier.height(8.dp))
                ConfigField(bridge, controller, "tts_voice", "英语离线语音名称（留空自动选择）")
                Button(
                    onClick = { controller.chooseOfflineVoice() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(color = MiuixTheme.colorScheme.secondaryContainer),
                ) { Text("选择已安装的英语离线语音") }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            PaddedBlock {
                Text(text = speechLabelText(speech), style = MiuixTheme.textStyles.body2)
                Spacer(Modifier.height(8.dp))
                NoteText("请在系统文字转语音设置中安装英语离线语音。试听通过扬声器播放；自动录音使用独立合成的 WAV 文件。")
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { controller.openSpeechSettings() },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(color = MiuixTheme.colorScheme.secondaryContainer),
                    ) { Text("打开系统语音设置") }
                    Button(
                        onClick = { controller.previewVoice() },
                        modifier = Modifier.weight(1f),
                        enabled = speechReady && !audioBusy && !busy,
                        colors = ButtonDefaults.buttonColors(color = MiuixTheme.colorScheme.secondaryContainer),
                    ) { Text("试听英语") }
                }
            }
        }

        SmallTitle("本地 Whisper / FFmpeg")
        Card(Modifier.fillMaxWidth()) {
            PaddedBlock {
                Text(text = mediaLabelText(media), style = MiuixTheme.textStyles.body2)
                Spacer(Modifier.height(6.dp))
                NoteText(modelText, emphasis = true)
                Spacer(Modifier.height(10.dp))
                val progress = modelProgressValue(media, bridge.importProgress)
                LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { confirmDownload = true },
                        modifier = Modifier.weight(1f),
                        enabled = nativeReady && !audioBusy && !mediaBusy && !modelReady && !busy,
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) { Text(if (modelReady) "英语识别模型已安装" else "下载英语识别模型") }
                    Button(
                        onClick = { controller.chooseModelFile() },
                        modifier = Modifier.weight(1f),
                        enabled = !audioBusy && !mediaBusy && !busy,
                        colors = ButtonDefaults.buttonColors(color = MiuixTheme.colorScheme.secondaryContainer),
                    ) { Text("导入离线模型") }
                }
                Spacer(Modifier.height(8.dp))
                NoteText("模型可单独下载或从文件导入。官方源失败后会尝试备用源，所有来源都校验相同 SHA-256；不会仅凭文件名接受模型。")
            }
        }

        Card(Modifier.fillMaxWidth()) {
            PaddedBlock {
                NoteText(bridge.audioMessage, emphasis = true)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { controller.cancelAudio() },
                        modifier = Modifier.weight(1f),
                        enabled = audioBusy || mediaBusy || speechBusy,
                        colors = ButtonDefaults.buttonColors(color = MiuixTheme.colorScheme.secondaryContainer),
                    ) { Text("停止试听 / 下载 / 识别") }
                    Button(
                        onClick = { controller.refreshSpeech() },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(color = MiuixTheme.colorScheme.secondaryContainer),
                    ) { Text("刷新语音依赖状态") }
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        Button(
            onClick = { controller.saveSettings() },
            modifier = Modifier.fillMaxWidth(),
            minHeight = 52.dp,
            colors = ButtonDefaults.buttonColorsPrimary(),
        ) { Text("保存设置") }
        Button(
            onClick = { controller.restartBrowser() },
            modifier = Modifier.fillMaxWidth(),
            minHeight = 52.dp,
            colors = ButtonDefaults.buttonColors(color = MiuixTheme.colorScheme.surfaceVariant),
        ) { Text("重新加载课程浏览器") }
    }

    OverlayDialog(
        show = confirmDownload,
        title = "下载英语识别模型",
        summary = "将下载 " + modelSizeText(media) + " 的 tiny.en 模型并校验完整性。官方源连接失败会尝试备用源，SHA-256 校验值不变。建议连接 Wi-Fi，保持应用在前台；也可取消后导入已下载的离线模型。",
        onDismissRequest = { confirmDownload = false },
    ) {
        Column(Modifier.fillMaxWidth()) {
            Button(
                onClick = { confirmDownload = false; controller.downloadModel() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) { Text("下载") }
            Spacer(Modifier.height(8.dp))
            TextButton("取消", { confirmDownload = false }, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun PaddedBlock(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

@Composable
private fun ColumnScope.NoteText(text: String, emphasis: Boolean = false) {
    Text(
        text = text,
        style = if (emphasis) MiuixTheme.textStyles.footnote1 else MiuixTheme.textStyles.footnote2,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
}

@Composable
private fun ConfigField(
    bridge: UiBridge,
    controller: MainActivity,
    key: String,
    label: String,
    secret: Boolean = false,
) {
    TextField(
        value = bridge.config.optString(key, ""),
        onValueChange = { controller.setConfigValue(key, it) },
        modifier = Modifier.fillMaxWidth(),
        label = label,
        useLabelAsPlaceholder = true,
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
    )
}

private fun speechEnabled(config: JSONObject): Boolean =
    config.optString("skip_record_mode", "off") != "all" && config.optString("tts_engine", "android") != "off"

private fun modelSizeText(media: JSONObject): String {
    val bytes = if (media.has("modelBytes")) media.optLong("modelBytes", 77704715L) else 77704715L
    return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1000000.0)
}

private fun speechLabelText(speech: JSONObject): String {
    if (speech.length() == 0) return "正在检查系统语音引擎…"
    var engine = speech.optString("engine")
    val engines: JSONArray? = speech.optJSONArray("engines")
    if (engines != null) {
        for (index in 0 until engines.length()) {
            val entry = engines.optJSONObject(index) ?: continue
            if (entry.optString("name") == engine) engine = entry.optString("label", engine)
        }
    }
    if (speech.optBoolean("available")) {
        return "英语离线朗读已就绪" + (if (engine.isEmpty()) "" else " · " + engine)
    }
    val error = speech.optString("error")
    return if (error.isEmpty()) "英语离线语音未就绪，请安装系统语音引擎和英语语音数据。" else error
}

private fun mediaLabelText(media: JSONObject): String {
    if (media.length() == 0) return "正在检查本地解码与识别引擎…"
    val ready = media.optBoolean("whisperAvailable") && media.optBoolean("ffmpegAvailable")
    if (ready) return "FFmpeg 解码和 Whisper 本地识别引擎已就绪"
    return media.optString("error", "本地引擎不可用，请安装包含本设备架构的完整 APK。")
}

private fun modelLabelText(media: JSONObject, importProgress: Float): String {
    val model = if (media.optString("modelName").isEmpty()) "tiny.en" else media.optString("modelName")
    val size = modelSizeText(media)
    val state = media.optString("modelState")
    val progress = media.optDouble("modelProgress", 0.0).coerceIn(0.0, 1.0)
    val text = when {
        media.optBoolean("modelReady") -> model + " 模型已就绪 · " + size
        state == "downloading" -> "正在下载 " + model + " · " + Math.round(progress * 100) + "% / " + size
        state == "verifying" -> "正在校验本地模型…"
        importProgress >= 0f -> "正在复制所选离线模型 · " + (importProgress * 100).toInt() + "%"
        else -> model + " 模型未就绪 · 下载大小 " + size
    }
    val error = media.optString("error")
    return if (error.isEmpty()) text else text + "\n" + error
}

private fun modelProgressValue(media: JSONObject, importProgress: Float): Float? {
    if (media.optString("modelState") == "verifying") return null
    if (importProgress >= 0f) return importProgress.coerceIn(0f, 1f)
    return media.optDouble("modelProgress", 0.0).toFloat().coerceIn(0f, 1f)
}
