# 电脑启动：神里绫华 v2

这是可选辅助启动器，不是 App 3.2 接入神里绫华 v2 的必要条件。App 已固定发送参考路径和原文，模型包中的原生 `python api_v2.py -a 0.0.0.0 -p 9880` 可直接使用。本目录不含权重或参考音频；如需要在电脑固定/覆盖参考参数，将 `start_ayaka_api.py` 和 `start_ayaka_api.bat` 与 `api_v2.py` 放在一起。

1. 模型包应包含神里绫华 v2 的 GPT/SoVITS 权重、对应的推理配置 YAML，以及 `D:/AI-TTS/ayaka_v2/ref_ayaka.wav`。请先由模型打包方确认配置确实指向新训练的两套权重。
2. 使用模型包的 Python 环境运行：

   ```bat
   python start_ayaka_api.py -c "你的神里绫华v2推理配置.yaml"
   ```

   如果根目录已有 `runtime/python.exe`，也可运行：

   ```bat
   start_ayaka_api.bat -c "你的神里绫华v2推理配置.yaml"
   ```

   若模型包已经配置好 `GPT_SoVITS/configs/tts_infer.yaml`，直接双击 BAT 即可。首次加载模型需要等待；看到 Uvicorn 正在监听 9880 后再操作手机。

3. Windows 打开命令提示符执行 `ipconfig`，找 WiFi 网卡的 IPv4 地址，例如 `192.168.1.2`。手机和电脑连接同一 WiFi；允许 Python 在 Windows 防火墙的“专用网络”上通信。
4. 在 App 底部“音质”选择“自建 GPT-SoVITS（在线）”，填 `http://192.168.1.2:9880`。手机不能填 `127.0.0.1` 或 `0.0.0.0`，那不是电脑的局域网地址。
5. 输入“你好世界。”播放。也可以先命名保存，再下载 MP3 和原始 WAV，之后关闭电脑也能听。

电脑浏览器可先测试：

```text
http://127.0.0.1:9880/tts?text=你好世界&text_lang=zh&prompt_lang=zh&text_split_method=cut5&batch_size=1&media_type=wav&streaming_mode=false&speed_factor=1.0
```

启动脚本使用官方 `api_v2.py` 的模型加载与 `/tts`，仅在服务端自动补充/覆盖参考音频、参考原文和中文提示语言。App 3.2 发送的默认参考路径也会被该脚本在电脑端覆盖；App 不上传参考文件。成功响应仍是 `audio/wav` 字节，失败仍是 GPT-SoVITS 的 JSON。

原生 `api_v2.py` 的每次请求都必须带 `ref_audio_path`，App 3.2 已按接口说明传入。若测试时省略这个参数，会报 `ref_audio_path is required`；使用本可选脚本可由电脑自动补上它。

参考原文默认 `感觉又多了解了你一些`，必须与 WAV 的真实文字一致。其他音色可只在电脑更改：

```bat
python start_ayaka_api.py -c "优菈推理配置.yaml" --reference-audio "D:/AI-TTS/eula/ref_eula.wav" --prompt-text "参考音频的准确原文"
```

App 使用电脑当时加载的模型。更换模型后请重新下载文稿音频；已下载文件保留原来的音色和语速。

无 GPU 环境下只验证默认参数补全逻辑：`python -m unittest discover -s server -p "test_*.py"`（在 Android 项目根目录执行）。完整模型加载与实际音质仍需在模型电脑上试听验收。
