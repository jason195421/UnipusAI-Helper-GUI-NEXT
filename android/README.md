# Android 独立运行版

安卓端采用原生 Android 界面、系统 WebView 与 Chaquopy 内嵌 Python。浏览器、任务处理和配置均在手机上运行，不需要电脑上的 Selenium 服务。Windows / macOS 继续使用原来的 Qt Quick 桌面界面。

当前处于开发验证阶段。本文记录已接入的代码与构建步骤，不代表已完成真机登录、真实课程或全题型兼容验证。APK 是否已产出，以本次 Gradle 构建结果和 Actions 产物为准。

## 设备要求

- Android 8.0 / API 26 或以上。
- APK 包含 `arm64-v8a` 与 `x86_64` 两种 ABI，不包含 32 位 ARM；`x86_64` 也用于模拟器测试。
- 系统 WebView 需要支持文档开始脚本和双向消息接口。应用会检查这些能力；出现「浏览器内核不可用」时，先更新设备的 Android System WebView 或当前 WebView 提供程序，再重新打开应用。
- 登录、课程页面、官方答案接口或配置的 AI 接口需要网络连接。独立运行不等于离线作答。

## 当前功能与限制

| 功能 | 安卓实现与边界 |
| --- | --- |
| 界面 | 「课程浏览器」「任务」「日志」「设置」四个原生触屏页面，保留深色模式、跟随系统和动画开关。 |
| 浏览器 | 应用内 WebView；通过逐 frame 的消息代理执行脚本，提供元素查询、输入、点击、iframe 和窗口切换等兼容接口，不启动桌面 WebDriver 可执行文件。 |
| 登录 | 可填写账号密码自动登录，也可直接在内置网页手动登录；验证码和人机验证仍由用户完成。 |
| 任务 | 复用现有目录扫描、选择、筛选、开始 / 停止和文本题目处理逻辑。真实网站页面变化仍可能需要调整选择器。 |
| AI 接口 | 使用轻量 HTTP 兼容客户端；无需在 APK 中安装桌面的 OpenAI SDK 或 Pydantic 原生扩展。API 地址、Key 和模型需与所选服务匹配。 |
| 官方答案接口 | 保留实验性接口路径；安卓 AES 解密通过 Android 标准密码库桥接，接口可用性与返回内容仍取决于服务端。 |
| 语音转写 | 通过 Android NDK 的 whisper.cpp 与 FFmpeg 共享库在手机本地解码、转写。英语 `tiny.en` 模型单独下载并校验，不在手机运行桌面 FFmpeg 或 PyTorch。模型和本地库都就绪后才提供转写。 |
| 自动录音与朗读 | 复用现有 WebAudio 虚拟麦克风注入流程。原声或系统 TextToSpeech 合成的 WAV 直接交给网页录音器，不通过扬声器回录。默认使用 `tts_engine=android`、`skip_record_mode=off`；缺引擎、离线英语语音或网页音频能力时明确提示，仍需按真实课程验证兼容性。 |
| 语音设置 | 展示系统 TTS / 离线语音与本地模型状态，支持英语试听、模型下载进度与取消。录音/朗读开关保存后需重新加载浏览器；不会在启动时自动下载模型或语音数据。 |
| 手动网页录音 | 可由用户申请应用麦克风权限，再逐次允许网页使用麦克风；是否可录音仍受页面与系统 WebView 支持情况影响。 |
| 后台运行 | 没有持续后台任务服务。处理任务时保持应用在前台；切到后台或系统回收后，不保证自动化持续执行。 |

## 安装与使用

1. 本地构建成功后取 `android/app/build/outputs/apk/debug/app-debug.apk`；或在成功的 **Build standalone Android app** 工作流中下载 `unipus-helper-android-debug` artifact 并解压。
2. 将 APK 传到支持的手机，按 Android 安装器提示允许当前来源安装应用。当前产物是开发测试包，不是应用商店正式发布包。
3. 打开应用，默认进入「课程浏览器」。可以在网页直接登录，也可以先到「设置」填写 U 校园账号、密码和登录地址。
4. 如需 AI 作答，在「设置」填写 API Key、API 地址和模型。点击「保存设置」；账号、接口或任务参数有变化后，点击「重新加载课程浏览器」应用新配置。
5. 在内置网页完成登录及验证码。点击「登录完成」只会检查真实登录会话，不会跳过认证或强制把未登录状态标记为就绪。
6. 在课程网页选择课程，打开目录。进入「任务」点击「扫描课程」；「课程浏览器 → 更多 → 扫描当前单元」可只扫描当前单元。
7. 按需筛选、勾选任务，再点击「开始处理」。运行记录在「日志」查看；停止操作会请求核心停止当前处理。
8. 直接处理网页当前题目时，在「任务」点击「处理当前页」并确认。

录音、朗读及音视频转写前，请按下方步骤检查「设置 → 录音与离线语音」。从旧版本升级时，原来明确保存的 `off/all` 选项会保留，需要自行开启并保存。

「课程浏览器 → 更多」还提供刷新、打开登录页和切换课程窗口。遇到网页新开窗口后，可以从「切换浏览器窗口」选取相应窗口。

### 深色模式与动画

「设置 → 外观与动效」可切换深色模式、恢复跟随系统，或关闭界面动画。外观在当前界面即时预览，点击「保存设置」后保留。动画还会尊重设备的系统动画设置。

账号、API Key、登录地址等修改不会在运行中的题目处理线程里强行替换。先停止当前任务、保存，再点击「重新加载课程浏览器」。未重新加载时，运行时会提示应用已保存的新设置。

### 自动录音、英语朗读与本地转写

1. 打开「设置 → 录音与离线语音」，检查系统语音引擎和英语离线语音状态。缺少数据时，点「打开系统语音设置」，安装或启用引擎并下载英语离线语音，再返回应用刷新。不同厂商的设置页面可能不同。
2. 保持语音名称为空可自动选择已安装的英语离线语音，也可从「选择已安装的英语离线语音」指定语音。联网语音和标记为未安装的语音不会当作可用离线语音。
3. 点「试听英语」会真实调用系统 TextToSpeech，并通过扬声器播放短句；试听与用于自动录音的文件合成是两条独立路径。
4. 开启「自动录音与朗读」，保存设置并重新加载课程浏览器。新安装默认开启；旧配置的关闭状态会保留。启用开关本身不会安装缺失依赖，也不会代替网页登录或完成题目。
5. 需要音视频转写时，点「下载英语识别模型」，确认下载约 77.7 MB 的 `tiny.en` 模型。模型只在点击确认后下载，显示进度；官方源连接失败会尝试备用源，两者必须满足相同长度和 SHA-256 校验。网络连接失败会明确报告，不会当作模型已就绪。
6. 模型就绪后，音频由 APK 内的 FFmpeg 解码，再由 whisper.cpp 在本地识别。英语识别效果、速度与设备性能及音频质量有关，`tiny.en` 不等同于桌面版使用的其他模型。

网络受限时，可先在其他设备完整下载同一 `ggml-tiny.en.bin` 模型并传到手机，再点「导入离线模型」，由系统文件选择器选择文件。应用仅使用该次选择授予的读取权限，不申请广泛外部存储权限；在后台把输入流复制到私有缓存，严格限制为 77704715 字节，再由本地接口校验内容并原子保存。扩展名、文件名或大小相同本身都不能代替 SHA-256。导入失败不会用错误文件替换已有正确模型，完成、取消或活动关闭后清理缓存源文件；导入超过两分钟会取消，建议先把云盘文件完整保存到设备。

关闭「自动录音与朗读」并保存、重新加载浏览器后，不再安装虚拟麦克风脚本，网页手动录音恢复使用系统麦克风授权。仅在高级配置关闭 TTS、仍允许处理录音题时，参考音频注入仍可使用。

「停止试听 / 下载 / 识别」和顶部停止按钮可取消语音操作。下载、识别、状态查询与取消使用独立执行路径，不会因长时间下载堵住停止操作；状态标签和进度单独更新，不会每秒重建设置表单。活动关闭时会停止语音并释放对应资源。保持应用前台；当前没有保证长期后台运行的服务。

系统 TTS 的文件合成结果会检查 RIFF/WAV、完整音频帧和非静音数据，再下混、重采样为 **16 kHz、单声道、PCM16**。最长 110 秒约 3.52 MB PCM，避免高采样率立体声超过网页消息大小限制；临时合成文件读回后删除。自动录音使用这个文件或题目原声构造虚拟媒体流，不申请物理麦克风。

### 手动使用麦克风

应用不会在启动时自动申请麦克风权限。需要在课程网页手动录音时：

1. 进入「课程浏览器 → 更多 → 允许网页使用麦克风」。
2. 在 Android 权限对话框中允许麦克风；若之前拒绝，可到系统的应用权限页调整。
3. 返回网页重新开始录音，应用会显示网页来源，再由用户决定是否允许该网页使用麦克风。

拒绝授权时，真实麦克风采集请求会被拒绝。此授权只用于手动网页操作，不会替代自动录音所需的语音引擎或网页兼容能力；自动录音注入不通过物理麦克风。应用没有申请摄像头或广泛外置存储权限。

## 配置与数据位置

应用 ID 为 `io.github.unipus.helper`。配置、日志、临时文件和缓存使用应用自己的私有数据目录；常见路径为：

```text
/data/user/0/io.github.unipus.helper/files/
├── config.json
├── logs/
├── speech-models/
└── tmp/
```

实际路径由 Android 的 `getFilesDir()` 提供，再通过 `UNIPUS_DATA_DIR` 交给 Python；不要把上面的示例路径写死在设备脚本里。WebView 的 Cookie 与站点存储也由应用沙箱管理。

安卓配置与电脑上的 `config.json` 相互独立。保存会合并已有字段，再用同目录临时文件替换原文件，避免只改外观时清空凭据，或写入中断时截断旧配置。配置处于应用私有目录不等于额外加密；当前未实现专门的密码保险库或密钥导出功能。清除应用数据或卸载会移除本地配置与登录状态，应用也未启用 Android 自动备份。

## 本地构建

在仓库根目录执行命令，而不是在手机上执行。构建机需要 Python 3.12；已有工具链时还需要 JDK 17、Gradle 8.13，以及 Android SDK 的 `platform-tools`、`platforms;android-35`、`build-tools;35.0.0`、`ndk;28.2.13676358` 和 `cmake;3.22.1`。运行 JavaScript 单测时另需 Node.js。

Android 工程使用 Chaquopy 嵌入 CPython 3.12；具体 Android Gradle Plugin、Chaquopy 和 WebKit 版本固定在 `android/build.gradle.kts`、`android/app/build.gradle.kts`。无需安装整个桌面版 `requirements.txt` 来打包安卓应用。

Gradle 的本地音频准备任务会调用 `scripts/prepare_android_audio.py`，下载并校验固定版本的 whisper.cpp 源码及 LGPL FFmpeg 共享库与对应源码。下载缓存放在 `.tools/android-audio/`，生成输入放在 `android/app/build/`；NDK 为两种 ABI 构建 JNI 接口。APK 内使用共享库，不需要执行手机上无法运行的桌面命令。模型数据不随构建脚本从用户目录复制，仍由应用内的明确下载操作获取。

### Windows 首次构建

```powershell
py -3.12 scripts/build_android.py --bootstrap
```

`--bootstrap` 将 JDK、Android SDK 和 Gradle 下载到仓库的 `.tools/android-toolchain/`，校验下载归档，并在构建子进程中设置工具链路径，不修改系统级 Java / SDK 环境变量。首次运行会下载依赖，并自动确认 Android SDK 的许可提示。

仅准备工具链：

```powershell
py -3.12 scripts/build_android.py --bootstrap --setup-only
```

同时编译 Android 仪器测试 APK：

```powershell
py -3.12 scripts/build_android.py --bootstrap --test
```

`--test` **只编译**仪器测试，不会自动启动模拟器或证明设备测试已通过。

### 已有工具链

保证 `JAVA_HOME` 指向 JDK 17，设置好 `ANDROID_HOME` / `ANDROID_SDK_ROOT`，并让 Gradle 8.13 在 PATH 中：

```bash
python3.12 scripts/build_android.py --gradle gradle --test
```

在 Windows 也可使用 `py -3.12` 运行同一命令。构建脚本会将当前 Python 路径传给 Chaquopy，避免依赖系统里另一个 Python 版本。

成功产物：

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

脚本会先把主入口及 `unipus_helper/*.py` 复制到 `android/app/build/python-src/`，不复制本机 `config.json`、用户密钥文件、Whisper 模型或桌面二进制。该目录每次构建都会重新生成，不要在其中修改源码。只想检查暂存内容时可运行：

```bash
python scripts/build_android.py --prepare-only
```

`--prepare-only` 不构建 APK。当前工程没有配置应用商店 release / AAB 发布流程。

## GitHub Actions

工作流文件为 `../.github/workflows/build-android.yml`，在仓库 Actions 中显示为 **Build standalone Android app**。

- 可手动运行，也会在推送 `v*` 标签时触发。
- 先运行 Python 驱动、运行时、密码库适配测试与 JavaScript 内核测试，再编译应用和仪器测试 APK。
- 音频适配与录音注入也有独立的 Python / JavaScript 检查；设备测试包含系统语音后端、合成 WAV 归一化、取消与本地语音接口。
- 手动运行时，勾选 `run_device_tests` 才会启动 API 35 的 `x86_64` 模拟器并执行离线 WebView 仪器测试；默认不运行设备测试。
- 成功构建后上传 `unipus-helper-android-debug` artifact。这里是 Actions 产物上传，不会自动创建 GitHub Release。
- 仪器测试报告上传为 `android-test-reports`；没有执行设备测试时，该报告目录可能不存在。

## 验证命令与范围

2026-11-28 本地验证：Debug APK 已构建并安装到 Android 15（API 35、x86_64、WebView 124.0.6367.219）模拟器，10 项离线 WebView 仪器测试全部通过，包括跨域 iframe、HttpOnly Cookie、连续三种画面的截图同步和原生 AES 解密。主界面另已检查登录页加载、键盘避让、快速切页、未保存输入保留，以及深色模式和动画设置保存后重启。尚未验证真实手机、真实账号的课程流程或全部题型。

同日新增音频验证：`BrowserKernelTest`、`AndroidRecordingTest` 与 `AndroidAudioEngineTest` 共 **26 项全部通过，无跳过项**，日志为本机 `.tools/android-toolchain/audio-webview-tts-tests.log`。其中包含真实 Google 系统 TTS 的英文 WAV 合成与 `speak` 完成、48 kHz 立体声到 16 kHz 单声道 PCM16 的转换，以及虚拟麦克风 → MediaRecorder → 解码后非静音检查。TTS 的协议/超时/清理测试使用可控后端，真实引擎测试另行执行；缺离线英语语音的其他设备可能跳过真实引擎项并记录原因，不能按模拟器结果推定所有手机均安装了该语音。

同日完整设备检查包含 40 项：38 项实际执行通过，2 项要求显式参数的模型下载/真实文件导入默认跳过。实际通过项包含原生 FFmpeg 的 WAV、MP3、M4A 解码、经独立大小及 SHA-256 校验的 Whisper 英语识别、越界路径拒绝、错误模型导入及已有模型完整性保护。真实模型下载另以显式参数单独运行并通过：官方源在当前网络连接超时后，备用源成功传输，77,704,715 字节及固定 SHA-256 均匹配。系统文件选择器还实际选择了 Downloads 中的模型，验证从缺失状态复制、校验、保存和清理缓存的完整导入流程。真实账号课程及全部题型仍未验证。

以下 Python / JavaScript 检查不需要真实账号、课程或网络服务：

```bash
python scripts/verify_android_driver.py -v
python scripts/verify_android_runtime.py -v
python scripts/verify_android_crypto.py -v
python scripts/verify_android_audio.py -v
python scripts/verify_recording_js.py -v
node scripts/verify_android_kernel.js
```

它们验证驱动协议、任务命令、配置原子写入、认证状态检查、密钥桥接边界及 JavaScript 逻辑。假桥或 JavaScript 沙箱测试不等同于在 Android WebView 上运行。

已连接合适的设备 / 模拟器，并完成 Python 源码暂存后，可在有 Gradle 的构建环境执行。以下设备测试示例使用 Bash：

```bash
python scripts/build_android.py --prepare-only
gradle -p android -PpythonExecutable="$(command -v python3.12)" connectedDebugAndroidTest
```

`android/app/src/androidTest/` 使用本地 HTML 内容检查真实 WebView、跨 frame 消息、窗口与截图等行为，不登录 U 校园，也不证明真实课程兼容性。只有该任务实际成功结束，才能说明对应设备测试通过；当前开发说明不代替测试报告。

## 常见情况

- **未生成 APK**：查看 Gradle 或 Actions 失败步骤。仅执行 `--prepare-only`、下载完工具链或 Python 单测通过，都不代表已生成可安装 APK。
- **浏览器内核不可用**：更新 Android System WebView / 当前提供程序后重试；最低 Android API 版本不能代替 WebView 功能检查。
- **登录后仍未就绪**：确认在应用内置浏览器完成登录，并处理验证码，再点击「登录完成」；外部浏览器的登录状态不会自动导入此应用。
- **修改账号或接口后未应用**：停止任务、保存设置，再点「重新加载课程浏览器」。
- **英语离线朗读未就绪**：安装系统 TTS 引擎和英语离线语音；确认所选语音属于当前引擎，联网语音不能代替离线数据。返回应用刷新后再试听。
- **本地模型未就绪**：在语音设置中明确下载模型，等待校验完成；网络失败可取消后重试，或通过「导入离线模型」选择已完整下载的同一模型。模型未完成、损坏或本地库不可用时不会伪造识别文本。
- **音频、视频或录音题未完成**：检查语音依赖、录音/朗读开关及保存后重新加载提示，再看网页和题型是否支持现有注入流程。当前仍不承诺所有课程与桌面版等价，可以在网页手动完成。
- **切到后台后任务停滞**：保持应用前台。当前没有用于长期后台自动化的前台服务，也未保证进程被系统回收后的任务恢复。
