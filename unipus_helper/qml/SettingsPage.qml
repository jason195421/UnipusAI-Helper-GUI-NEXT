import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

// 工作台内置「设置」页：替代原来的独立 PyQt5 / Fluent 配置编辑器。
// 与主界面共用 PySide6 / Qt Quick 及 Theme 单例，只发布一个程序。
Rectangle {
    id: page
    objectName: "settingsPageView"
    property bool firstRun: false
    color: Theme.surface
    radius: Theme.radiusMd
    border.color: Theme.line
    clip: true

    function loadValues() {
        var c = settings.load()
        usernameField.text = String(c.username || "")
        passwordField.text = String(c.password || "")
        urlField.text = String(c.url || "")
        apiKeyField.text = String(c.api_key || "")
        baseUrlField.text = String(c.base_url || "")
        modelField.text = String(c.model || "")
        maxTokensField.text = String(c.max_tokens === undefined ? 32768 : c.max_tokens)
        temperatureField.text = String(c.temperature === undefined ? 0.3 : c.temperature)
        recordScoreThresholdField.text = String(c.record_score_threshold === undefined ? 70 : c.record_score_threshold)
        scoreThresholdField.text = String(c.score_threshold === undefined ? 80 : c.score_threshold)
        targetAccuracyField.text = String(c.target_accuracy === undefined ? "100" : c.target_accuracy)
        skipRecordModeField.text = String(c.skip_record_mode || "off")
        ttsEngineField.text = String(c.tts_engine || "auto")
        ttsVoiceField.text = String(c.tts_voice || "")
        ttsProxyField.text = String(c.tts_proxy || "")
        browserField.text = String(c.browser || "edge")
        tokenField.text = String(c.token_full || "")
        debugCheck.checked = !!c.debug_mode
        screenshotCheck.checked = !!c.screenshot
        answerApiCheck.checked = c.answer_api_enabled !== false
    }

    function saveValues() {
        var ok = settings.save({
            "username": usernameField.text,
            "password": passwordField.text,
            "url": urlField.text,
            "token_full": tokenField.text,
            "api_key": apiKeyField.text,
            "base_url": baseUrlField.text,
            "model": modelField.text,
            "max_tokens": maxTokensField.text,
            "temperature": temperatureField.text,
            "record_score_threshold": recordScoreThresholdField.text,
            "score_threshold": scoreThresholdField.text,
            "target_accuracy": targetAccuracyField.text,
            "skip_record_mode": skipRecordModeField.text,
            "debug_mode": debugCheck.checked,
            "tts_engine": ttsEngineField.text,
            "tts_voice": ttsVoiceField.text,
            "tts_proxy": ttsProxyField.text,
            "browser": browserField.text,
            "screenshot": screenshotCheck.checked,
            "answer_api_enabled": answerApiCheck.checked
        })
        statusText.text = ok
            ? (page.firstRun ? "配置已保存，窗口即将关闭，请重新启动程序。"
                             : "已保存到 config.json，重启程序后生效。")
            : "保存失败，请检查程序目录是否可写。"
        statusText.color = ok ? Theme.success : Theme.error
        if (ok) Qt.callLater(page.loadValues)
    }

    Component.onCompleted: loadValues()

    ColumnLayout {
        anchors.fill: parent
        anchors.margins: 24
        spacing: 16

        ColumnLayout {
            Layout.fillWidth: true
            spacing: 4
            UiText { text: page.firstRun ? "开始配置" : "偏好与连接"; font.pixelSize: 19; font.weight: Font.DemiBold; Layout.fillWidth: true }
            UiText {
                Layout.fillWidth: true
                wrapMode: Text.Wrap
                color: Theme.muted
                font.pixelSize: 13
                text: page.firstRun
                    ? "首次使用：请填写 U 校园账号与 AI 接口信息，点击「保存设置」后重新启动程序。"
                    : "配置保存在程序目录的 config.json；修改后点击保存，重启程序后生效。"
            }
        }

        ScrollView {
            id: formScroll
            Layout.fillWidth: true
            Layout.fillHeight: true
            Layout.minimumHeight: 0
            clip: true
            contentWidth: availableWidth
            ScrollBar.horizontal.policy: ScrollBar.AlwaysOff

            ColumnLayout {
                width: formScroll.availableWidth
                spacing: 14

                UiText { text: "账号与登录"; font.pixelSize: 15; font.weight: Font.DemiBold; color: Theme.ink; Layout.fillWidth: true; Layout.topMargin: 4 }
                Rectangle { Layout.fillWidth: true; height: 1; color: Theme.subtleLine }

                RowLayout {
                    Layout.fillWidth: true; spacing: 16
                    UiText { text: "账号"; Layout.preferredWidth: 128; color: Theme.secondary; font.pixelSize: 13 }
                    ConfigField { id: usernameField; objectName: "cfg_username"; placeholderText: "U 校园账号 / 学号" }
                }
                RowLayout {
                    Layout.fillWidth: true; spacing: 16
                    UiText { text: "密码"; Layout.preferredWidth: 128; color: Theme.secondary; font.pixelSize: 13 }
                    ConfigField { id: passwordField; objectName: "cfg_password"; echoMode: TextInput.Password; passwordCharacter: "•"; placeholderText: "U 校园密码" }
                }
                RowLayout {
                    Layout.fillWidth: true; spacing: 16
                    UiText { text: "登录地址"; Layout.preferredWidth: 128; color: Theme.secondary; font.pixelSize: 13 }
                    ConfigField { id: urlField; objectName: "cfg_url"; placeholderText: "SSO 登录地址" }
                }

                UiText { text: "AI 接口"; font.pixelSize: 15; font.weight: Font.DemiBold; color: Theme.ink; Layout.fillWidth: true; Layout.topMargin: 10 }
                Rectangle { Layout.fillWidth: true; height: 1; color: Theme.subtleLine }

                RowLayout {
                    Layout.fillWidth: true; spacing: 16
                    UiText { text: "API Key"; Layout.preferredWidth: 128; color: Theme.secondary; font.pixelSize: 13 }
                    ConfigField { id: apiKeyField; objectName: "cfg_api_key"; placeholderText: "必填，例如 sk-..." }
                }
                RowLayout {
                    Layout.fillWidth: true; spacing: 16
                    UiText { text: "API 地址"; Layout.preferredWidth: 128; color: Theme.secondary; font.pixelSize: 13 }
                    ConfigField { id: baseUrlField; objectName: "cfg_base_url"; placeholderText: "例如 https://api.moonshot.cn/v1" }
                }
                RowLayout {
                    Layout.fillWidth: true; spacing: 16
                    UiText { text: "模型名称"; Layout.preferredWidth: 128; color: Theme.secondary; font.pixelSize: 13 }
                    ConfigField { id: modelField; objectName: "cfg_model"; placeholderText: "例如 kimi-k2-turbo-preview" }
                }
                RowLayout {
                    Layout.fillWidth: true; spacing: 16
                    UiText { text: "最大 Token 数"; Layout.preferredWidth: 128; color: Theme.secondary; font.pixelSize: 13 }
                    ConfigField { id: maxTokensField; objectName: "cfg_max_tokens"; placeholderText: "32768" }
                }
                RowLayout {
                    Layout.fillWidth: true; spacing: 16
                    UiText { text: "温度 (0-2)"; Layout.preferredWidth: 128; color: Theme.secondary; font.pixelSize: 13 }
                    ConfigField { id: temperatureField; objectName: "cfg_temperature"; placeholderText: "0.3" }
                }

                UiText { text: "录音题、浏览器与高级"; font.pixelSize: 15; font.weight: Font.DemiBold; color: Theme.ink; Layout.fillWidth: true; Layout.topMargin: 10 }
                Rectangle { Layout.fillWidth: true; height: 1; color: Theme.subtleLine }

                RowLayout {
                    Layout.fillWidth: true; spacing: 16
                    UiText { text: "浏览器"; Layout.preferredWidth: 128; color: Theme.secondary; font.pixelSize: 13 }
                    ConfigField { id: browserField; objectName: "cfg_browser"; placeholderText: "edge（默认）/ chrome" }
                }
                RowLayout {
                    Layout.fillWidth: true; spacing: 16
                    UiText { text: "录音、朗读达标分"; Layout.preferredWidth: 128; color: Theme.secondary; font.pixelSize: 13 }
                    ConfigField { id: recordScoreThresholdField; objectName: "cfg_record_score_threshold"; placeholderText: "70（0-100）" }
                }
                RowLayout {
                    Layout.fillWidth: true; spacing: 16
                    UiText { text: "其余题型达标分"; Layout.preferredWidth: 128; color: Theme.secondary; font.pixelSize: 13 }
                    ConfigField { id: scoreThresholdField; objectName: "cfg_score_threshold"; placeholderText: "80（0-100）" }
                }
                RowLayout {
                    Layout.fillWidth: true; spacing: 16
                    UiText { text: "目标正确率"; Layout.preferredWidth: 128; color: Theme.secondary; font.pixelSize: 13 }
                    ConfigField { id: targetAccuracyField; objectName: "cfg_target_accuracy"; placeholderText: "100，或 85 / 80-90（实验性）" }
                }
                RowLayout {
                    Layout.fillWidth: true; spacing: 16
                    UiText { text: "跟读跳过"; Layout.preferredWidth: 128; color: Theme.secondary; font.pixelSize: 13 }
                    ConfigField { id: skipRecordModeField; objectName: "cfg_skip_record_mode"; placeholderText: "off / follow_aloud / all（实验性）" }
                }

                RowLayout {
                    Layout.fillWidth: true; spacing: 16
                    UiText { text: "TTS 引擎"; Layout.preferredWidth: 128; color: Theme.secondary; font.pixelSize: 13 }
                    ConfigField { id: ttsEngineField; objectName: "cfg_tts_engine"; placeholderText: "auto / edge / sapi / off" }
                }
                RowLayout {
                    Layout.fillWidth: true; spacing: 16
                    UiText { text: "TTS 音色（可选）"; Layout.preferredWidth: 128; color: Theme.secondary; font.pixelSize: 13 }
                    ConfigField { id: ttsVoiceField; objectName: "cfg_tts_voice"; placeholderText: "留空使用引擎默认英语音色" }
                }
                RowLayout {
                    Layout.fillWidth: true; spacing: 16
                    UiText { text: "TTS 代理（可选）"; Layout.preferredWidth: 128; color: Theme.secondary; font.pixelSize: 13 }
                    ConfigField { id: ttsProxyField; objectName: "cfg_tts_proxy"; placeholderText: "例如 http://127.0.0.1:7890" }
                }
                RowLayout {
                    Layout.fillWidth: true; spacing: 16
                    UiText { text: "Token（可选）"; Layout.preferredWidth: 128; color: Theme.secondary; font.pixelSize: 13; Layout.alignment: Qt.AlignTop }
                    TextArea {
                        id: tokenField
                        objectName: "cfg_token_full"
                        Layout.fillWidth: true; Layout.minimumWidth: 0
                        Layout.preferredHeight: 88
                        wrapMode: TextEdit.Wrap
                        selectByMouse: true
                        color: Theme.ink
                        placeholderText: "可选：完整的 U 校园 Token / Cookie 字符串"
                        placeholderTextColor: Theme.muted
                        font.family: Theme.fontFamily
                        font.pixelSize: 13
                        leftPadding: 12; rightPadding: 12; topPadding: 8; bottomPadding: 8
                        background: Rectangle {
                            radius: Theme.radiusSm; color: tokenField.activeFocus ? Theme.surface : Theme.field; border.width: tokenField.activeFocus ? 2 : 1
                            border.color: tokenField.activeFocus ? Theme.accent : Theme.line
                        }
                    }
                }
                RowLayout {
                    Layout.fillWidth: true; spacing: 16
                    UiText { text: "调试模式"; Layout.preferredWidth: 128; color: Theme.secondary; font.pixelSize: 13 }
                    UiCheck { id: debugCheck; objectName: "cfg_debug_mode"; text: "启用详细日志"; checked: false; Layout.fillWidth: true }
                }
                RowLayout {
                    Layout.fillWidth: true; spacing: 16
                    UiText { text: "识图模式"; Layout.preferredWidth: 128; color: Theme.secondary; font.pixelSize: 13 }
                    UiCheck { id: screenshotCheck; objectName: "cfg_screenshot"; text: "题目截图发送给模型（需支持识图）"; checked: false; Layout.fillWidth: true }
                }
                RowLayout {
                    Layout.fillWidth: true; spacing: 16
                    UiText { text: "官方答案接口"; Layout.preferredWidth: 128; color: Theme.secondary; font.pixelSize: 13 }
                    UiCheck { id: answerApiCheck; objectName: "cfg_answer_api_enabled"; text: "启用实验性官方答案接口"; checked: true; Layout.fillWidth: true }
                }
            }
        }

        Rectangle { Layout.fillWidth: true; height: 1; color: Theme.subtleLine }

        RowLayout {
            Layout.fillWidth: true; spacing: 12
            UiText { id: statusText; text: ""; color: Theme.muted; font.pixelSize: 13; Layout.fillWidth: true; wrapMode: Text.Wrap }
            ActionButton { objectName: "saveSettings"; text: "保存设置"; iconName: "check"; primary: true; implicitWidth: 128; implicitHeight: 40; onClicked: page.saveValues() }
        }
    }
}
