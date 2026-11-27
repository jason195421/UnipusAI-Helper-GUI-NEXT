"""Pure-stdlib, non-streaming chat-completions client for the Android runtime.

The public surface matches the OpenAI calls used by this project; it does not
load pydantic, httpx or their native wheels. TLS verification is never disabled.
"""
from __future__ import annotations

import json
import math
import socket
import ssl
from types import SimpleNamespace
import urllib.error
import urllib.parse
import urllib.request


class APIError(Exception):
    def __init__(self, message, *, status_code=None, body=None, headers=None):
        super().__init__(message)
        self.status_code = status_code
        self.body = body
        self.response = SimpleNamespace(
            status_code=status_code, headers=dict(headers or {}),
            text=body if isinstance(body, str) else json.dumps(body, ensure_ascii=False),
        )


class APIStatusError(APIError):
    pass


class APIConnectionError(APIError):
    pass


class APITimeoutError(APIConnectionError):
    pass


class APIResponseValidationError(APIError):
    pass


def _origin(url):
    parsed = urllib.parse.urlsplit(url)
    return parsed.scheme.lower(), (parsed.hostname or "").lower(), parsed.port or (443 if parsed.scheme == "https" else 80)


class _SameOriginRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if _origin(req.full_url) != _origin(newurl):
            raise APIConnectionError("Refused a cross-origin API redirect to protect the authorization header")
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def _namespace(value):
    if isinstance(value, dict):
        return SimpleNamespace(**{str(k): _namespace(v) for k, v in value.items()})
    if isinstance(value, list):
        return [_namespace(item) for item in value]
    return value


def _error_message(body):
    error = body.get("error", body) if isinstance(body, dict) else body
    if isinstance(error, dict):
        return str(error.get("message") or error.get("code") or "API request failed")
    return str(error or "API request failed")


class _Completions:
    def __init__(self, client):
        self._client = client

    def create(self, **kwargs):
        client = self._client
        if client._closed:
            raise APIConnectionError("The API client has been closed")
        if kwargs.get("stream"):
            raise NotImplementedError("Android chat-completions currently supports stream=False only")
        payload = dict(kwargs)
        extra = payload.pop("extra_body", None)
        if extra is not None:
            if not isinstance(extra, dict):
                raise TypeError("extra_body must be a mapping")
            payload.update(extra)
        timeout = float(payload.pop("timeout", client.timeout))
        if not math.isfinite(timeout) or timeout <= 0:
            raise ValueError("timeout must be a positive finite number")
        encoded = json.dumps(payload, ensure_ascii=False, allow_nan=False).encode("utf-8")
        request = urllib.request.Request(
            client.base_url + "/chat/completions", data=encoded, method="POST",
            headers={"Authorization": "Bearer " + client.api_key,
                     "Content-Type": "application/json", "Accept": "application/json",
                     "User-Agent": "UnipusAI-Helper-Android"},
        )
        try:
            with client._opener.open(request, timeout=timeout) as response:
                raw = response.read(16 * 1024 * 1024 + 1)
                if len(raw) > 16 * 1024 * 1024:
                    raise APIResponseValidationError("API response exceeded the 16 MiB limit")
                status = getattr(response, "status", 200)
                headers = getattr(response, "headers", {})
        except urllib.error.HTTPError as exc:
            try:
                text = exc.read(65536).decode("utf-8", errors="replace")
                try:
                    body = json.loads(text)
                except ValueError:
                    body = text
                raise APIStatusError(_error_message(body), status_code=exc.code,
                                     body=body, headers=exc.headers) from exc
            finally:
                exc.close()
        except (TimeoutError, socket.timeout) as exc:
            raise APITimeoutError("API request timed out") from exc
        except urllib.error.URLError as exc:
            if isinstance(exc.reason, (TimeoutError, socket.timeout)):
                raise APITimeoutError("API request timed out") from exc
            raise APIConnectionError("API connection failed: " + str(exc.reason)) from exc
        except OSError as exc:
            raise APIConnectionError("API connection failed: " + str(exc)) from exc
        try:
            body = json.loads(raw.decode("utf-8"))
        except (UnicodeError, ValueError) as exc:
            raise APIResponseValidationError("API response was not valid UTF-8 JSON", status_code=status) from exc
        if isinstance(body, dict) and "error" in body:
            raise APIStatusError(_error_message(body), status_code=status, body=body, headers=headers)
        if not isinstance(body, dict) or not isinstance(body.get("choices"), list) or not body["choices"]:
            raise APIResponseValidationError("API response did not include completion choices", status_code=status, body=body)
        for choice in body["choices"]:
            if not isinstance(choice, dict) or not isinstance(choice.get("message"), dict):
                raise APIResponseValidationError("API choice did not include a message", status_code=status, body=body)
            content = choice["message"].get("content")
            if content is not None and not isinstance(content, str):
                raise APIResponseValidationError("API message content must be text or null", status_code=status)
            choice["message"].setdefault("content", None)
            choice.setdefault("finish_reason", None)
        body.setdefault("model", payload.get("model"))
        body.setdefault("usage", None)
        return _namespace(body)


class OpenAI:
    def __init__(self, *, api_key, base_url="https://api.openai.com/v1", timeout=60.0, max_retries=0):
        if not isinstance(api_key, str) or not api_key.strip():
            raise ValueError("api_key must not be empty")
        parsed = urllib.parse.urlsplit(str(base_url))
        if parsed.scheme not in ("https", "http") or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
            raise ValueError("base_url must be an HTTP(S) API root without credentials, query or fragment")
        if max_retries:
            raise NotImplementedError("Retries are managed by the application; use max_retries=0")
        self.api_key = api_key
        self.base_url = str(base_url).rstrip("/")
        self.timeout = float(timeout)
        if not math.isfinite(self.timeout) or self.timeout <= 0:
            raise ValueError("timeout must be a positive finite number")
        try:
            import certifi
        except ImportError:
            context = ssl.create_default_context()
        else:
            context = ssl.create_default_context(cafile=certifi.where())
        self._opener = urllib.request.build_opener(
            _SameOriginRedirect(), urllib.request.HTTPSHandler(context=context),
        )
        self._closed = False
        self.chat = SimpleNamespace(completions=_Completions(self))

    def close(self):
        self._closed = True

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()
