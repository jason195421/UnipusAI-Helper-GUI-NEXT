"""Check Android's answer-decryption transport without private config or keys."""
import base64
import json
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from unipus_helper.android_crypto import AES, configure


class CryptoTransportTests(unittest.TestCase):
    def tearDown(self):
        configure(None)

    def test_binary_data_round_trip(self):
        calls = []
        class Bridge:
            def callJson(self, value):
                calls.append(json.loads(value))
                return json.dumps({"ok": True, "value": base64.b64encode(bytes(range(16))).decode("ascii")})
        configure(Bridge())
        result = AES.new(b"1234567890123456", AES.MODE_ECB).decrypt(bytes(16))
        self.assertEqual(result, bytes(range(16)))
        self.assertEqual(calls[0]["op"], "aes_decrypt")
        self.assertEqual(base64.b64decode(calls[0]["key"]), b"1234567890123456")
        self.assertEqual(base64.b64decode(calls[0]["data"]), bytes(16))

    def test_invalid_key_is_rejected(self):
        with self.assertRaises(ValueError):
            AES.new(b"short", AES.MODE_ECB)

    def test_uninitialized_bridge_is_rejected(self):
        configure(None)
        with self.assertRaises(RuntimeError):
            AES.new(bytes(16), AES.MODE_ECB)

    def test_invalid_block_is_rejected_before_native_call(self):
        class Bridge:
            def callJson(self, _):
                raise AssertionError("Invalid input reached Android")
        configure(Bridge())
        with self.assertRaises(ValueError):
            AES.new(bytes(16), AES.MODE_ECB).decrypt(b"short")

    def test_native_failure_is_reported(self):
        class Bridge:
            def callJson(self, _):
                return json.dumps({"ok": False, "error": {"message": "fixture failure"}})
        configure(Bridge())
        with self.assertRaisesRegex(RuntimeError, "fixture failure"):
            AES.new(bytes(16), AES.MODE_ECB).decrypt(bytes(16))


if __name__ == "__main__":
    unittest.main()
