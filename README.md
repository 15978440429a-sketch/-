# 文字朗读

单模块 Android 应用（Kotlin / Jetpack Compose Material 3 / MVVM / StateFlow）。基础朗读调用设备安装的系统 `TextToSpeech` 引擎；可选神里绫华、优菈和可莉在线音色通过第三方 VITS 接口合成音频。无账号、广告、API Key 或收费接口，也不附带角色音频和模型。

## 环境与项目结构

- Android Studio 稳定版（建议 Rabbit 1 或兼容 AGP 9.4 的版本）、JDK 17、Android SDK Platform 37。
- Gradle 9.6.0、Android Gradle Plugin 9.4.0、Compose BOM 2026.09.00、Kotlin Compose Compiler 插件 2.4.10。
- `minSdk 26`，`compileSdk 37`，`targetSdk 37`，包名 `com.example.texttospeech`。
- `app/src/main/java/com/example/texttospeech/`: `MainActivity.kt` 是 Compose 界面；`ReaderViewModel.kt` 负责状态、旋转恢复和任务取消；`tts/SystemTtsManager.kt` 使用系统 TTS；`network/OnlineSpeechClient.kt` 通过 OkHttp 下载音频；`playback/AudioFilePlayer.kt` 播放缓存文件；`util/TextSegmenter.kt` 分段；`model/VoiceOption.kt` 定义状态。
- `app/src/main/res/values/strings.xml` 是中文文案；`USER_AGREEMENT.md` 是完整用户协议。

## 打开、构建与安装

1. 解压完整项目，在 Android Studio 选择 **Open**，打开包含 `settings.gradle.kts` 的 `TextToSpeechApp` 文件夹。
2. 在 SDK Manager 安装 Android 17 / API 37，并等待 Gradle 同步。请使用 JDK 17 或 Android Studio 自带的兼容 JDK。
3. 在项目根目录执行 `./gradlew assembleDebug`（Windows 命令提示符执行 `gradlew.bat assembleDebug`），或点击 **Build > Build App Bundle(s) / APK(s) > Build APK(s)**。
4. 调试 APK 输出为 `app/build/outputs/apk/debug/app-debug.apk`。连接并授权手机后执行 `adb install -r app/build/outputs/apk/debug/app-debug.apk`，也可将 APK 发送到手机并在系统提示下安装。安卓 8.0 及以上可运行。

项目附带 Gradle 9.6.0 官方标准 wrapper JAR 和启动脚本；wrapper JAR 已核对官方 SHA-256。它会按 `gradle-wrapper.properties` 下载 Gradle 9.6.0，并校验发行包 SHA-256。首次构建还需联网下载 SDK/Gradle/Maven 依赖。

### 不在本机安装 Android Studio：使用 GitHub Actions

项目提供 `.github/workflows/build-apk.yml`，可以在 GitHub 的构建机上生成调试 APK：

1. 将 **`TextToSpeechApp` 文件夹里的全部内容**提交到你自己的 GitHub 仓库根目录；根目录应直接包含 `settings.gradle.kts`、`app` 和 `.github`。解压后请保留以点开头的 `.github` 文件夹。
2. 打开该仓库的 **Actions**，按页面提示启用工作流，选择 **Build APK**，点击 **Run workflow**。
3. 等待该次运行完成。成功后，在该次运行详情页的 **Artifacts** 中下载 `text-to-speech-debug`，解压可得到 `app-debug.apk`。文件保留 7 天，过期后重新运行即可。
4. 安装方式同上。此文件为调试签名 APK，可用于个人测试；发布前应另行配置自己的签名。

工作流安装 JDK 17、API 37 和 Build Tools 36.0.0，执行 `./gradlew --no-daemon assembleDebug`，并由 Gradle Action 验证项目中的官方 wrapper。提交到 `main` 分支时也会自动构建。

**构建验证状态：**2026-10-13 已在 GitHub Actions 成功执行 `./gradlew --no-daemon assembleDebug`，构建耗时 2 分 16 秒。已下载并校验调试 APK 的产物 SHA-256、ZIP 完整性和 APK 文件结构。[成功构建记录](https://github.com/15978440429a-sketch/-/actions/runs/38408202871)，对应提交 `c8e70d25b71cc4ae85d077cef7a3396a317f40a3`。APK 大小 12,446,139 字节，SHA-256：`9abe4de2cf7e32f7bca2c31b7f19786a50876f1a8384bf50af783b082a321b8a`。尚未完成真机朗读和公开在线接口播放验证，实际音色可用性取决于设备语音包与第三方服务。

## 使用方法

1. 输入文本。默认选中“系统默认（离线）”，点击“播放”。系统 TTS 会根据设备上**实际安装**的离线语音包朗读，自动分段；“停止”立即中断。无可用中文离线语音时，点击“安装语音数据”并在系统设置中下载中文语音包；返回应用后会重新初始化引擎并刷新可用音色。某些设备还需在系统设置里启用/更换 TTS 引擎。
2. 点击“已选：系统默认（离线）”打开音色管理，查看语言、类型和来源。选择“神里绫华（在线）”、“优菈（在线）”或“可莉（在线）”，输入文本并点击“播放”；此时文本经网络发送到下方公开 API 并播放返回的 MP3/WAV。选择后对**下次**播放生效。
3. 系统 TTS 语言在下拉框中选择。语速、音调范围均为 0.5x–2.0x，调整后下次系统朗读生效。在线音色另有 `noise`、`length` 和 MP3/WAV 设置；`length` 值越大朗读越慢。
4. 若有自建服务，选择“自定义音色（在线）”，填写服务器根地址和服务端识别的 `speaker` 名称。它必须支持下述同一 GET 查询协议；GPT-SoVITS 的不同 WebUI 接口通常需要自行编写一个兼容适配层。

公开接口示例（参数由客户端进行 URL 编码）：

```text
http://233366.proxy.nscc-gz.cn:8888/?text=你好&speaker=神里绫华&format=mp3&noise=0.667&length=1.20
```

预设音色使用同一个公开接口，客户端按选择发送不同的 `speaker`：

| 界面名称 | speaker 参数 | 类型 |
| --- | --- | --- |
| 神里绫华（在线） | 神里绫华 | 在线音色（需联网） |
| 优菈（在线） | 优菈 | 在线音色（需联网） |
| 可莉（在线） | 可莉 | 在线音色（需联网） |

“尤拉”对应角色优菈；请求必须使用接口文档中的准确名称 `优菈`。新增音色共用已有的分段、停止、超时和音频播放处理。接口文档列有以上三个角色，但当前交付环境尚未完成在线合成及真机播放验证。

自建接口也接收 `text`、`speaker`、`format`（`mp3` 或 `wav`）、`noise`、`length`，以 2xx 状态返回真正的 MP3/WAV 文件。客户端检查 HTTP 状态、音频文件头和大小（每段上限 20 MiB）。在线请求有连接/读取/总时限；失败、超时、非音频响应会显示错误。长文本在线每段约 180 个 UTF-16 字符，最多 80 段，逐段下载播放；系统朗读分段不受此在线上限影响。

## 权限、隐私与已知限制

- 只声明 `INTERNET` 权限。系统 TTS 不需要网络权限；在线音色必须联网。输入的在线文本会发送给所选第三方或自建服务器。
- 公开接口仅提供 **HTTP**，没有传输加密。为了兼容它和用户的局域网 HTTP 服务，应用允许明文 HTTP；请勿将敏感文本提交在线合成。自建服务建议部署 HTTPS。应用不将生成的音频永久保存；临时音频播放后删除。
- 公共 API 由第三方维护，无法保证持续可用、响应质量或特定音色始终存在；网络环境也可能阻断该地址。在线失败时可在状态区点“切换到系统离线音色”；设备须先安装相应语音数据。
- 未集成 Sherpa-ONNX：单个 `.onnx` 文件通常不足以运行端侧 TTS，还需要兼容的模型结构、配置、词表/分词与前后处理，以及设备性能和授权验证。目前没有已确认可直接用于 Sherpa-ONNX 的神里绫华模型，因此不提供虚假的模型导入或离线角色音色。
- 系统 `TextToSpeech` 不支持可靠的原生暂停，本项目仅提供播放和停止。关闭或旋转 Activity 时会停止播放并 `shutdown()`；文本和设置由 ViewModel/SavedStateHandle 保持，极长文本在进程被系统杀死时仍受 Android 保存状态大小限制。

## 版权与使用条件

神里绫华、优菈、可莉等角色的音频版权属于米哈游科技（上海）有限公司。公开 API 与相关模型仅允许用于二次创作，**禁止商业用途、禁止二次配布**；请遵守接口提供者的要求。用户对使用他人音色产生的版权问题自行负责。本应用本身不内置任何未经授权的角色音频文件或模型，也并非米哈游或接口提供者的官方产品。详见 [用户协议](USER_AGREEMENT.md)。

公开 API 的协议、参数和非商业说明见项目维护者的 [genshinTTS README](https://github.com/gbxh/genshinTTS)。Android 配置可参照 [Android Gradle Plugin 发布说明](https://developer.android.com/build/releases/agp-9-4-0-release-notes) 与 [Compose BOM 文档](https://developer.android.com/develop/ui/compose/bom)。

## 完整文件清单

所有路径均相对于 `TextToSpeechApp`：

```text
.github/workflows/build-apk.yml
.gitignore
README.md
USER_AGREEMENT.md
settings.gradle.kts
build.gradle.kts
gradle.properties
gradlew
gradlew.bat
gradle/wrapper/gradle-wrapper.jar
gradle/wrapper/gradle-wrapper.properties
app/build.gradle.kts
app/src/main/AndroidManifest.xml
app/src/main/java/com/example/texttospeech/MainActivity.kt
app/src/main/java/com/example/texttospeech/ReaderViewModel.kt
app/src/main/java/com/example/texttospeech/model/VoiceOption.kt
app/src/main/java/com/example/texttospeech/network/OnlineSpeechClient.kt
app/src/main/java/com/example/texttospeech/playback/AudioFilePlayer.kt
app/src/main/java/com/example/texttospeech/tts/SystemTtsManager.kt
app/src/main/java/com/example/texttospeech/util/TextSegmenter.kt
app/src/main/res/values/strings.xml
app/src/main/res/values/styles.xml
app/src/main/res/values-night/styles.xml
```
