"""Exercise the ASGI adapter without a GPU, model, or external dependencies."""

import unittest
from urllib.parse import parse_qs, urlencode
from start_ayaka_api import DEFAULT_PROMPT, DEFAULT_REFERENCE, FixedReferenceMiddleware


class FixedReferenceTests(unittest.IsolatedAsyncioTestCase):
    async def test_phone_text_gets_fixed_native_reference_parameters(self):
        captured = []

        async def native_api(scope, receive, send):
            captured.append(scope)

        middleware = FixedReferenceMiddleware(native_api, DEFAULT_REFERENCE, DEFAULT_PROMPT)
        scope = {"type": "http", "method": "GET", "path": "/tts",
                 "query_string": urlencode({"text": "你好世界", "text_lang": "zh",
                                             "speed_factor": "1.25"}).encode()}
        await middleware(scope, None, None)
        query = parse_qs(captured[0]["query_string"].decode())
        self.assertEqual(["你好世界"], query["text"])
        self.assertEqual(["1.25"], query["speed_factor"])
        self.assertEqual([DEFAULT_REFERENCE], query["ref_audio_path"])
        self.assertEqual([DEFAULT_PROMPT], query["prompt_text"])
        self.assertEqual(["zh"], query["prompt_lang"])
        self.assertEqual(["wav"], query["media_type"])
        self.assertEqual(["false"], query["streaming_mode"])
        self.assertNotIn("ref_audio_path", parse_qs(scope["query_string"].decode()))

    async def test_phone_cannot_override_or_mix_reference_audio(self):
        captured = []

        async def native_api(scope, receive, send):
            captured.append(scope)

        middleware = FixedReferenceMiddleware(native_api, DEFAULT_REFERENCE, DEFAULT_PROMPT)
        await middleware({"type": "http", "method": "GET", "path": "/tts",
                          "query_string": b"text=hello&ref_audio_path=wrong.wav&aux_ref_audio_paths=other.wav&prompt_text=wrong"},
                         None, None)
        query = parse_qs(captured[0]["query_string"].decode())
        self.assertEqual([DEFAULT_REFERENCE], query["ref_audio_path"])
        self.assertEqual([DEFAULT_PROMPT], query["prompt_text"])
        self.assertNotIn("aux_ref_audio_paths", query)

    async def test_other_endpoints_are_unchanged(self):
        captured = []

        async def native_api(scope, receive, send):
            captured.append(scope)

        middleware = FixedReferenceMiddleware(native_api, DEFAULT_REFERENCE, DEFAULT_PROMPT)
        scope = {"type": "http", "method": "GET", "path": "/docs", "query_string": b""}
        await middleware(scope, None, None)
        self.assertIs(scope, captured[0])


if __name__ == "__main__":
    unittest.main()
