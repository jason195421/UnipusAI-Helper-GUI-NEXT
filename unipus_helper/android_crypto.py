"""Small AES compatibility boundary backed by Android's standard crypto API."""
from __future__ import annotations

import base64
import json
import threading

_bridge = None
_lock = threading.Lock()


def configure(bridge):
    global _bridge
    with _lock:
        _bridge = bridge


class _Cipher:
    def __init__(self, bridge, key):
        self.bridge, self.key = bridge, key

    def decrypt(self, data):
        raw = bytes(data)
        if len(raw) % 16:
            raise ValueError("AES ciphertext length must be a multiple of 16 bytes")
        response = json.loads(str(self.bridge.callJson(json.dumps({
            "op": "aes_decrypt", "key": base64.b64encode(self.key).decode("ascii"),
            "data": base64.b64encode(raw).decode("ascii"),
        }))))
        if not response.get("ok"):
            error = response.get("error") or {}
            raise RuntimeError(error.get("message", "Android AES decryption failed"))
        return base64.b64decode(response["value"], validate=True)


class AES:
    MODE_ECB = 1

    @staticmethod
    def new(key, mode):
        key = bytes(key)
        if len(key) not in (16, 24, 32):
            raise ValueError("Invalid AES key length")
        if mode != AES.MODE_ECB:
            raise ValueError("Only the answer API's AES ECB mode is supported")
        with _lock:
            bridge = _bridge
        if bridge is None:
            raise RuntimeError("Android crypto bridge is not initialized")
        return _Cipher(bridge, key)
