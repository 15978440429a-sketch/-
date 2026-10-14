"""Start native GPT-SoVITS while keeping the reference audio on the computer.

Copy this file beside api_v2.py and run it in the model's Python environment.
No model weights, reference audio, or third-party source code are bundled here.
"""

import argparse
import importlib
import os
from pathlib import Path
import sys
from urllib.parse import parse_qsl, urlencode


DEFAULT_REFERENCE = "D:/AI-TTS/ayaka_v2/ref_ayaka.wav"
DEFAULT_PROMPT = "感觉又多了解了你一些"


class FixedReferenceMiddleware:
    """Fill the native GET /tts parameters before FastAPI validates them."""

    def __init__(self, app, reference_audio, prompt_text):
        self.app = app
        self.fixed = {
            "ref_audio_path": reference_audio,
            "prompt_text": prompt_text,
            "prompt_lang": "zh",
            "media_type": "wav",
            "streaming_mode": "false",
        }

    async def __call__(self, scope, receive, send):
        if (scope.get("type") == "http" and scope.get("method") == "GET"
                and scope.get("path") == "/tts"):
            query = parse_qsl(scope.get("query_string", b"").decode("utf-8"),
                              keep_blank_values=True)
            # A phone cannot change the reference clip or mix auxiliary voices.
            query = [(key, value) for key, value in query
                     if key not in self.fixed and key != "aux_ref_audio_paths"]
            query.extend(self.fixed.items())
            scope = dict(scope, query_string=urlencode(query).encode("utf-8"))
        await self.app(scope, receive, send)


def main():
    parser = argparse.ArgumentParser(description="原神好声音：固定参考音频的 GPT-SoVITS API")
    parser.add_argument("-a", "--bind-addr", default="0.0.0.0")
    parser.add_argument("-p", "--port", type=int, default=9880)
    parser.add_argument("-c", "--tts-config", default="GPT_SoVITS/configs/tts_infer.yaml")
    parser.add_argument("--reference-audio", default=DEFAULT_REFERENCE)
    parser.add_argument("--prompt-text", default=DEFAULT_PROMPT)
    args = parser.parse_args()

    root = Path(__file__).resolve().parent
    if not (root / "api_v2.py").is_file():
        parser.error("请把 start_ayaka_api.py 复制到 api_v2.py 所在的 GPT-SoVITS 根目录。")
    os.chdir(root)
    reference = Path(args.reference_audio).expanduser().resolve()
    config = Path(args.tts_config).expanduser().resolve()
    if not reference.is_file():
        parser.error(f"找不到参考音频：{reference}")
    if not config.is_file():
        parser.error(f"找不到推理配置：{config}；请使用 -c 指定模型包提供的 tts_infer.yaml。")

    # api_v2 parses its own CLI and loads the model when imported.
    original_argv = list(sys.argv)
    sys.path.insert(0, str(root))
    sys.argv = [str(root / "api_v2.py"), "-a", args.bind_addr,
                "-p", str(args.port), "-c", str(config)]
    try:
        api = importlib.import_module("api_v2")
    finally:
        sys.argv = original_argv
    api.APP.add_middleware(FixedReferenceMiddleware,
                          reference_audio=reference.as_posix(), prompt_text=args.prompt_text)

    import uvicorn
    print(f"\n参考音频已由电脑固定：{reference}")
    print("手机与电脑同一 WiFi，在 App 填：http://电脑局域网IP:" + str(args.port))
    print("请确认上方模型加载日志对应你的神里绫华 v2 权重。\n")
    uvicorn.run(api.APP, host=args.bind_addr, port=args.port, workers=1)


if __name__ == "__main__":
    main()
