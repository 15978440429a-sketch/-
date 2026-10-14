# 原神好声音

“原神好声音”是一个单模块 Android 文本朗读应用，使用 Kotlin、Jetpack Compose Material 3、MVVM、Coroutines 和 StateFlow 开发。基础方案调用手机系统 `TextToSpeech`，可离线朗读；需要 AI 音色时，直接调用用户电脑上的 GPT-SoVITS 原生 `/tts` 服务。应用不需要账号、API Key 或付费接口，没有广告，也不内置角色音频或模型。

> 应用已移除音质不理想的神里绫华、优菈、可莉公共在线接口。角色模型与生成音频只能在获得许可的范围内使用；详见本文末尾与 [用户协议](USER_AGREEMENT.md)。

## 已实现功能

- 多行文本输入、字数统计、清空、长文本自动分段；空文本不能播放。
- 3.0 版采用沉浸式播放器主页：中央按段显示正文，当前段自动滚动并突出显示；底部是类似即时通信应用的固定控制栏。
- 底部控制栏依次提供音质/音色设置、文稿页、播放或暂停、三态播放模式和播放队列；播放模式按钮每点一次按“顺序 → 单曲 → 随机”循环。
- 播放队列使用底部抽屉展示当前歌单内全部文稿名称，可直接点选任意文稿开始播放。
- 系统 TTS 离线朗读，优先 `Locale.CHINA`；显示初始化、朗读、停止和错误状态，并可打开系统语音数据安装页。
- 系统可用语言与音色列表，语速和音调均可在 0.5x–2.0x 调节。
- 在线音色只保留“自建 GPT-SoVITS（在线）”：手机只填写电脑地址，客户端按模型包约定发送固定参考音频路径与原文；不上传音频文件。服务器地址、在线语速与随机度会自动保存。
- 原生中文 `/tts`、`cut5`、单句非流式 WAV；按接口适配说明换算 `speed_factor = 1 / length`、`temperature = 0.8 + noise`，格式固定为 WAV。
- 文稿可命名保存；点“保存文稿”时名称框保持空白，不会把正文自动当作名称。当前草稿自动保存，退出应用、旋转屏幕或界面进程重建后仍可恢复。
- 文本由独立 `:text_storage` 进程中的 `ContentProvider + SQLite` 保存，旧版数据库会自动迁移，不会为了升级主动清空文稿。
- 可创建和删除歌单，把文稿加入或移出歌单；删除歌单不会删除文稿。
- 顺序播放、随机播放、单曲循环。顺序播放到末尾后从头继续。
- “每篇播放次数”可设为 1–99；顺序和随机模式会在当前文稿完成指定次数后才切换下一篇。
- 每句话单独播放，句尾加入约 350 毫秒自然停顿；只有真正正在发声的那一句会加深显示，合成等待和句间停顿时不会错误高亮。
- 系统 TTS 可按句暂停/继续，在线或已下载的逐句音频也可暂停/继续。
- 倒计时定时播放，范围 1–1440 分钟，可随时取消。
- 自建服务器生成的在线语音可以下载到应用私有目录，**同时保留带句间停顿的合并原始 WAV、128 kbps MP3 与逐句 WAV 缓存**；以后断网仍可逐句播放和准确高亮。
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

1. 点击底部“文稿”进入编辑页，输入或打开文本，再返回播放页并保持“系统默认（离线）”。
2. 若提示中文语音不可用，点击“安装语音数据”，在系统设置中安装中文语音包后返回应用。
3. 点击底部“音质”，可选择系统语言、系统音色，并调整语速、音调。修改项从下次播放开始生效。
4. 中央按钮在“播放/暂停/继续”之间切换。Android 系统 TTS 没有原生精确暂停，所以应用会真正停止队列，并在继续时从当前分段开头恢复；在线或本地音频由播放器在原位置暂停和继续。

### 新版页面结构

1. 播放页左上角选择歌单；中央是按段排列的正文，朗读进度会自动跟随；右下角打开播放队列。
2. 右侧“顺序/单曲/随机”合并为一个按钮，每点一次切换到下一种模式，切换不会打断当前朗读。
3. 左下角“音质”打开底部设置面板，集中管理音色、系统 TTS 语言、语速、音调、在线参数和自定义服务器。
4. 左下偏中的“文稿”切换到文稿与歌单页面。文稿编辑、命名保存、新建、删除、歌单增删、每篇播放次数、定时播放及角色音频下载/导出都集中在该页面。

### 神里绫华 v2 / 自建 GPT-SoVITS

1. 电脑先按模型包的启动说明运行 `python api_v2.py -a 0.0.0.0 -p 9880`；确保其推理配置使用已训练完成的神里绫华 v2 GPT/SoVITS 权重，参考音频位于 `D:/AI-TTS/ayaka_v2/ref_ayaka.wav`。可用 `-c` 指定包中的推理配置 YAML。
2. 手机与电脑连接同一 WiFi，点击 App 底部“音质”，选择“自建 GPT-SoVITS（在线）”，填写 `http://电脑局域网IP:9880`（也接受 `/tts` 地址）。手机不填写参考 WAV 路径和说话者名称。
3. 输入文本后直接播放，或者先保存并命名文稿再下载离线音频。服务器不可用、超时或返回非音频内容时会显示明确错误，可随时切回系统离线音色。
4. 音色切换会先安全停止当前播放。原生 v2 使用新的缓存音色 ID，避免把旧的低质量 VITS 缓存误当作新模型；旧版选择项会回退到系统默认音色。

### 下载 MP3 和原始 WAV

1. 先选择自建 GPT-SoVITS 音色、填写电脑地址，再保存并命名当前文稿；修改文稿后需要再次保存。
2. 点击“下载 MP3 + 原始 WAV”。应用逐句请求 WAV，在手机本地插入句间静音、合并原始 PCM WAV，并编码一份 MP3，同时保留私有逐句缓存。
3. 完成后再次播放相同文本和相同音色时，会逐句读取本地缓存，不再联网，并与在线播放一样准确高亮当前发声句。
4. “导出 MP3”和“导出原始 WAV”会打开 Android 文件选择器，由用户决定保存位置。旧版合并缓存仍可播放和导出，但无法得知精确句子时间；重新下载一次即可启用逐句高亮。
5. 修改文本或删除文稿会使不再匹配的离线文件失效并删除；“删除离线音频”不会删除文稿。

### 文稿、歌单和播放次数

1. “保存文稿”会弹出空白名称框，由用户自行输入名称，不会预填正文。重新保存已打开的文稿会更新它；“新建”会保留旧文稿并建立空白草稿。
2. 新建歌单后，可在文稿列表中逐篇“加入歌单”或“移出歌单”。选择“全部文稿”可播放所有已保存文稿。
3. 顺序模式按歌单顺序播放，末尾自动回到第一篇；随机模式随机选下一篇；单曲循环一直重复当前文稿。
4. 顺序或随机模式下，每篇会先完成设定的 1–99 次，再进入下一篇。点击“停止”会取消当前播放与后续自动切换。

### 定时播放

设定分钟数后点击“开始计时”。应用进程仍存活时，即使界面进入后台，倒计时通常也会继续；如果系统强制结束整个进程，应用无法在后台自行唤醒，重新打开时会恢复目标时间并立即执行已经到期的任务。本版本没有申请精确闹钟权限，也没有常驻前台服务。

## GPT-SoVITS 原生接口

应用自动将电脑根地址规范化为 `/tts`，只连接用户填写的服务器。单句请求示例：

```text
http://192.168.1.2:9880/tts?text=你好世界&text_lang=zh&ref_audio_path=D:/AI-TTS/ayaka_v2/ref_ayaka.wav&prompt_lang=zh&prompt_text=感觉又多了解了你一些&text_split_method=cut5&batch_size=1&media_type=wav&streaming_mode=false&speed_factor=0.83&temperature=1.467
```

官方 [api_v2.py](https://github.com/RVC-Boss/GPT-SoVITS/blob/main/api_v2.py) 要求 `ref_audio_path`。按照用户交付的《给APP的接口适配说明.md》，客户端固定发送以下值，不在手机界面要求输入：

```text
ref_audio_path=D:/AI-TTS/ayaka_v2/ref_ayaka.wav
prompt_text=感觉又多了解了你一些
prompt_lang=zh
```

不要把这个 Windows 路径当成手机上的文件路径。App 不上传参考音频或模型，可以直接连接未经修改的原生 API。另附 [可选服务端启动脚本](server/README.md)，供模型打包方在电脑固定或覆盖参考音频（例如后续接优菈）；使用它不是本次神里绫华 v2 接入的前提。

成功响应是 `HTTP 200`、`Content-Type: audio/wav`，body 直接存为 WAV 播放。客户端会显示 JSON 中的 `message`、`Exception` 或 `detail`，而不会把模型错误都归成“网络不可用”。即使错误 JSON 使用 200 状态也会被识别。连接超时 10 秒，读取超时 120 秒，总调用上限 180 秒，可随时停止取消。

下载时同样固定请求 WAV；合并与 MP3 编码要求 16-bit PCM WAV。手机播放与导出保留 350 毫秒句间停顿。在线语速改动作用于下次合成，已下载音频保留下载时的音色和语速；更换模型或语速后需要重新下载。

客户端校验地址、HTTP 状态、音频头和大小，设置连接、读取与总超时。文本按句切分，单句过长时再按约 180 个 UTF-16 字符切开，最多 80 段；单段返回文件上限 60 MiB。

## 存储、权限和隐私

- 清单只声明 `INTERNET`。系统 TTS、SQLite、应用私有音频和 Android 文件选择器不需要额外存储权限。
- 草稿、文稿、歌单和音频元数据由单独的 `:text_storage` 进程管理，界面进程通过 `ContentResolver` 读写。保存带递增版本号，可拒绝较旧的并发草稿写入。
- 文本没有云同步。只有用户主动选择自建在线音色并播放或下载时，相应句子才会发往其填写的服务器，请勿提交密码、个人敏感信息或保密内容。
- 用户自建的局域网服务可能使用 HTTP，因此清单允许明文网络流量；公网服务建议使用 HTTPS。

## 已知限制

- 应用不提供公共角色语音服务器。自建 GPT-SoVITS 服务的部署、可用性、音质、鉴权与合规由用户和服务提供者负责；系统 TTS 仍是无网络时的基础方案。
- 下载依赖自建服务成功返回兼容 WAV。超长文稿会消耗较多时间、网络、存储和电量。
- 定时播放不是系统级精确闹钟；应用进程被系统彻底结束后，无法在指定时刻主动唤醒。
- 未集成 Sherpa-ONNX。GPT-SoVITS 的权重在电脑运行，不会直接打包进 APK；下载后的逐句 WAV/MP3 可以在手机断网播放。
- 当前验收使用本地模拟的原生 `/tts` 服务与真实可解码 PCM WAV；尚未访问用户的电脑局域网 IP，也没有收到模型权重和参考音频，不能声称已试听神里绫华 v2 实际音质。模型包就绪后仍需在同一 WiFi 下实测。
- App 图标是原创“冰蓝星形 + 声波”风格，不是《原神》官方图标，也不表示与米哈游存在官方关系。

## 自动构建与测试

GitHub Actions 会使用 JDK 17、API 37 和 Android 35 x86_64 模拟器执行：

- `assembleDebug` 与 `assembleDebugAndroidTest`；
- 系统离线音色与自建 SoVITS 音色反复切换；
- 新版底部导航、音色抽屉和三态播放模式循环切换；
- Activity 重建后的草稿恢复；
- 输入完整正文后，文稿名称输入框仍为空白；
- 本地原生 `/tts` 请求参数、中文编码、地址规范化、WAV 下载和 Android MediaPlayer 实际解码播放；
- HTTP 400 JSON 和 HTTP 200 错误 JSON 均显示具体模型错误；
- 电脑地址输入与 Activity 重建恢复；
- 不依赖 GPU 的服务端参考音频参数补全测试；
- 每个中文句号、问号和感叹号都形成独立播放段及准确文本范围；
- 两段 16-bit PCM WAV 的真实合并、350 毫秒句间静音、原始 WAV 文件头检查、Jump3r MP3 编码和 MP3 文件头检查。

**3.2 构建状态：待本次构建验证。** 上一版 3.1 的 [构建与 Android 35 模拟器测试](https://github.com/15978440429a-sketch/-/actions/runs/38523347982) 已成功；新版构建完成后更新本节。

## 版权与使用条件

神里绫华、优菈、可莉等角色及相关音频版权属于米哈游科技（上海）有限公司。用户自行配置的角色模型与生成音频只能在获得许可的范围内用于二次创作，**禁止商业用途、禁止未经许可的二次配布**。导出 MP3/WAV 不代表取得传播授权。用户对训练、保存、使用或分享他人音色产生的版权问题自行负责。

本应用不内置任何角色音频文件或模型，也不再连接任何公共角色音色服务；本应用不代表米哈游、模型作者或用户配置的服务器提供者。

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
server/start_ayaka_api.py
server/start_ayaka_api.bat
server/test_fixed_reference.py
server/README.md
```
