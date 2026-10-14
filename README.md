# 原神好声音

“原神好声音”是一个单模块 Android 文本朗读应用，使用 Kotlin、Jetpack Compose Material 3、MVVM、Coroutines 和 StateFlow 开发。基础方案调用手机系统 `TextToSpeech`，可离线朗读；神里绫华、优菈、可莉等角色音色通过公开 VITS 服务在线合成。应用不需要账号、API Key 或付费接口，没有广告，也不内置角色音频或模型。

> 角色语音、公开模型与接口仅供二次创作，禁止商业用途、禁止二次配布。详见本文末尾与 [用户协议](USER_AGREEMENT.md)。

## 已实现功能

- 多行文本输入、字数统计、清空、长文本自动分段；空文本不能播放。
- 系统 TTS 离线朗读，优先 `Locale.CHINA`；显示初始化、朗读、停止和错误状态，并可打开系统语音数据安装页。
- 系统可用语言与音色列表，语速和音调均可在 0.5x–2.0x 调节。
- 在线音色：神里绫华、优菈（“尤拉”对应优菈）、可莉，以及用户自定义推理服务器。
- 在线角色主服务使用 Hugging Face VITS Space；失败后再尝试旧公开 VITS 地址，并明确区分“第三方服务不可用”和“手机无网络”。
- 文稿可命名保存；当前草稿自动保存，退出应用、旋转屏幕或界面进程重建后仍可恢复。
- 文本由独立 `:text_storage` 进程中的 `ContentProvider + SQLite` 保存，旧版数据库会自动迁移，不会为了升级主动清空文稿。
- 可创建和删除歌单，把文稿加入或移出歌单；删除歌单不会删除文稿。
- 顺序播放、随机播放、单曲循环。顺序播放到末尾后从头继续。
- “每篇播放次数”可设为 1–99；顺序和随机模式会在当前文稿完成指定次数后才切换下一篇。
- 正在朗读时显示当前文稿、当前第几次，并突出显示当前句子。
- 倒计时定时播放，范围 1–1440 分钟，可随时取消。
- 在线角色语音可以下载到应用私有目录，**同时保留合并后的原始 WAV 与 128 kbps MP3**；以后断网可直接播放。
- MP3 和原始 WAV 都能分别导出到用户选择的手机文件夹；不需要存储权限。
- 支持深色模式。应用名称为“原神好声音”；图标是原创冰蓝星形与声波设计，不复制米哈游或《原神》官方商标图形。

## 开发环境

- Android Studio 当前稳定版，JDK 17。
- `minSdk 26`，`compileSdk 37`，`targetSdk 37`。
- Gradle 9.6.0，Android Gradle Plugin 9.4.0，Kotlin 2.4.10。
- Compose BOM 2026.09.00，OkHttp 5.3.2。
- Jump3r 1.0.5 用于在手机本地把 16-bit PCM WAV 编码为 MP3。

## 用 Android Studio 打开并构建

1. 下载或克隆本仓库，在 Android Studio 选择 **Open**，打开仓库根目录；该目录应直接包含 `settings.gradle.kts`、`app` 和 `.github`。
2. 在 SDK Manager 安装 Android API 37，等待 Gradle 同步完成。
3. 选择 **Build > Build App Bundle(s) / APK(s) > Build APK(s)**，或者在项目根目录执行：

   ```bash
   ./gradlew assembleDebug
   ```

   Windows 命令提示符使用：

   ```bat
   gradlew.bat assembleDebug
   ```

4. APK 输出路径：`app/build/outputs/apk/debug/app-debug.apk`。

也可以在仓库的 **Actions > Build APK** 中手动运行工作流。成功后从该次运行的 **Artifacts** 下载 `text-to-speech-debug` 并解压。

## 安装到手机

- 可把 `app-debug.apk` 发送到 Android 8.0 或更高版本手机，允许所用文件管理器“安装未知应用”后打开安装。
- 也可以连接已授权 USB 调试的手机并执行：

  ```bash
  adb install -r app/build/outputs/apk/debug/app-debug.apk
  ```

- GitHub 临时构建机生成的调试证书可能与旧 APK 不同。如果出现“签名不一致”或“应用未安装”，请先把重要文稿复制出来，再卸载旧版并安装新版。卸载或在系统设置中清除存储会删除应用私有的草稿、文稿、歌单和已下载音频。

## 使用方法

### 系统离线朗读

1. 输入文本，保持“系统默认（离线）”，点击“播放”。
2. 若提示中文语音不可用，点击“安装语音数据”，在系统设置中安装中文语音包后返回应用。
3. 可选择系统语言、系统音色，并调整语速、音调。修改项从下次播放开始生效。
4. 系统 TTS 没有可靠的原生暂停，因此应用只提供真实有效的“播放”和“停止”，没有伪暂停按钮。

### 神里绫华、优菈和可莉

1. 点击音色选择器，选择“神里绫华（在线）”“优菈（在线）”或“可莉（在线）”。
2. 输入文本后直接播放，或者先点“保存文稿”并填写名称。
3. 在线播放时会显示连接、排队、合成和播放进度。服务休眠、排队或停机时会给出明确错误，可切回系统离线音色。
4. 音色切换会先安全停止当前播放；系统引擎的重复音色 ID 和厂商异常均有保护，避免过去选择音色时直接闪退的问题。

### 下载 MP3 和原始 WAV

1. 先选择在线角色音色，保存并命名当前文稿；修改文稿后需要再次保存。
2. 点击“下载 MP3 + 原始 WAV”。应用按段在线合成 WAV，在手机本地合并原始 PCM WAV，并编码一份 MP3。
3. 完成后该文稿会显示离线音频标记；再次播放相同文本和相同角色时，优先使用本地 MP3，MP3 缺失时回退本地 WAV，不再联网。
4. “导出 MP3”和“导出原始 WAV”会打开 Android 文件选择器，由用户决定保存位置。旧版只有 MP3 的缓存仍可继续播放和导出，重新下载后会补齐 WAV。
5. 修改文本或删除文稿会使不再匹配的离线文件失效并删除；“删除离线音频”不会删除文稿。

### 文稿、歌单和播放次数

1. “保存文稿”会要求输入名称。重新保存已打开的文稿会更新它；“新建”会保留旧文稿并建立空白草稿。
2. 新建歌单后，可在文稿列表中逐篇“加入歌单”或“移出歌单”。选择“全部文稿”可播放所有已保存文稿。
3. 顺序模式按歌单顺序播放，末尾自动回到第一篇；随机模式随机选下一篇；单曲循环一直重复当前文稿。
4. 顺序或随机模式下，每篇会先完成设定的 1–99 次，再进入下一篇。点击“停止”会取消当前播放与后续自动切换。

### 定时播放

设定分钟数后点击“开始计时”。应用进程仍存活时，即使界面进入后台，倒计时通常也会继续；如果系统强制结束整个进程，应用无法在后台自行唤醒，重新打开时会恢复目标时间并立即执行已经到期的任务。本版本没有申请精确闹钟权限，也没有常驻前台服务。

## 在线接口

预设角色首先连接：

```text
页面：https://huggingface.co/spaces/zomehwh/vits-uma-genshin-honkai
队列：wss://zomehwh-vits-uma-genshin-honkai.hf.space/queue/join
```

该 Gradio 服务返回 WAV，不需要 API Key。应用动态读取角色选项，并保留以下已知映射作为页面配置暂时不可读时的降级值：

| 界面名称 | Hugging Face 选项 | 旧接口 speaker | 类型 |
| --- | --- | --- | --- |
| 神里绫华（在线） | 神里绫华（龟龟） | 神里绫华 | 在线、需联网 |
| 优菈（在线） | 优菈 | 优菈 | 在线、需联网 |
| 可莉（在线） | 可莉 | 可莉 | 在线、需联网 |

旧公开接口仅作为备用：

```text
http://245671.proxy.nscc-gz.cn:8888/?text=你好&speaker=神里绫华&format=wav&noise=0.667&length=1.20
http://233366.proxy.nscc-gz.cn:8888/?text=你好&speaker=神里绫华&format=wav&noise=0.667&length=1.20
```

自定义服务器接受 GET 参数 `text`、`speaker`、`format`、`noise`、`length`，并应以 2xx 返回有效 MP3 或 WAV。下载双格式离线文件时，客户端会请求 WAV，以便保留原始文件；仅支持返回兼容的 16-bit PCM WAV 进行合并和 MP3 编码。

客户端校验 HTTP 状态、音频头与大小，设置连接、排队、读取和总时限。预设角色按最多 96 个 UTF-16 字符分段，最多 125 段；自定义服务约 180 个字符一段，最多 80 段。单段返回文件上限 20 MiB。

## 存储、权限和隐私

- 清单只声明 `INTERNET`。系统 TTS、SQLite、应用私有音频和 Android 文件选择器不需要额外存储权限。
- 草稿、文稿、歌单和音频元数据由单独的 `:text_storage` 进程管理，界面进程通过 `ContentResolver` 读写。保存带递增版本号，可拒绝较旧的并发草稿写入。
- 文本没有云同步。只有用户选择在线音色并播放或下载时，相应文本才会发往第三方或自定义服务器，请勿提交密码、个人敏感信息或保密内容。
- Hugging Face 主服务使用 HTTPS/WSS；旧备用服务及用户自建局域网服务可能使用 HTTP，因此清单允许明文网络流量。

## 已知限制

- 公共服务由第三方维护，可能休眠、排队、限流、改变协议或永久停止，应用无法保证角色音色始终可用；系统 TTS 仍是无网络时的基础方案。
- 下载依赖在线服务成功返回兼容 WAV。超长文稿会消耗较多时间、网络、存储和电量。
- 定时播放不是系统级精确闹钟；应用进程被系统彻底结束后，无法在指定时刻主动唤醒。
- 未集成 Sherpa-ONNX。单个 `.onnx` 文件通常不足以运行 TTS，还需要匹配的配置、词表、分词和前后处理；目前没有已确认可直接用于 Sherpa-ONNX 的神里绫华模型，因此不虚构模型导入功能。
- App 图标是原创“冰蓝星形 + 声波”风格，不是《原神》官方图标，也不表示与米哈游存在官方关系。

## 自动构建与测试

GitHub Actions 会使用 JDK 17、API 37 和 Android 35 x86_64 模拟器执行：

- `assembleDebug` 与 `assembleDebugAndroidTest`；
- 神里绫华、优菈、可莉音色连续切换；
- 三种播放模式切换；
- Activity 重建后的草稿恢复；
- 本地模拟 HTTP 主服务失败后的备用服务器重试；
- 两段 16-bit PCM WAV 的真实合并、原始 WAV 文件头检查、Jump3r MP3 编码和 MP3 文件头检查。

**2.1 构建状态：已通过。** 2026-10-14 的 [GitHub Actions 构建与 Android 35 模拟器测试](https://github.com/15978440429a-sketch/-/actions/runs/38514913310) 全部成功，对应功能提交 `e7d5b9bea43251c8b045541c390f32a3cb89b108`。生成的 APK 为 12,897,994 字节，SHA-256：`31c0136e40d7457ac5a923acd199e8156256cd327ce548f16e0c3701411fbafc`。

## 版权与使用条件

神里绫华、优菈、可莉等角色的音频版权属于米哈游科技（上海）有限公司。公开 API、相关模型与生成音频仅允许用于二次创作，**禁止商业用途、禁止二次配布**。导出 MP3/WAV 不代表取得传播授权。用户对保存、使用或分享他人音色产生的版权问题自行负责，并应遵守权利人及接口提供者的要求。

本应用不内置任何未经授权的角色音频文件或模型，不代表米哈游、Hugging Face、模型作者或接口提供者。公开 API 的项目说明可参考 [GenshinVoice](https://github.com/TimeRainStarSky/GenshinVoice)。

## 主要文件

```text
.github/workflows/build-apk.yml
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
app/src/main/java/com/example/texttospeech/network/HuggingFaceVitsClient.kt
app/src/main/java/com/example/texttospeech/network/OnlineSpeechClient.kt
app/src/main/java/com/example/texttospeech/playback/AudioFilePlayer.kt
app/src/main/java/com/example/texttospeech/playback/OfflineAudioExporter.kt
app/src/main/java/com/example/texttospeech/storage/TextRepository.kt
app/src/main/java/com/example/texttospeech/storage/TextStoreContract.kt
app/src/main/java/com/example/texttospeech/storage/TextStoreProvider.kt
app/src/main/java/com/example/texttospeech/tts/SystemTtsManager.kt
app/src/main/java/com/example/texttospeech/util/TextSegmenter.kt
app/src/main/res/drawable/ic_launcher_foreground.xml
app/src/main/res/mipmap-anydpi/ic_launcher.xml
app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml
app/src/main/res/values/strings.xml
app/src/androidTest/java/com/example/texttospeech/OnlineSpeechClientInstrumentedTest.kt
app/src/androidTest/java/com/example/texttospeech/VoiceAndStorageSmokeTest.kt
```
