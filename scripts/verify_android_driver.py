"""Exercise Android browser/API adapters without Android, a network or credentials."""
from __future__ import annotations

import base64
import io
import json
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from unipus_helper import android_driver, android_openai, android_selenium
from unipus_helper.android_driver import AndroidWebDriver, AndroidWebElement
from unipus_helper.android_selenium import (
    ActionChains, By, EC, InvalidArgumentException, InvalidSelectorException,
    InvalidSessionIdException, JavascriptException, Keys, NoSuchElementException, NoSuchFrameException,
    NoSuchWindowException, StaleElementReferenceException, TimeoutException,
    UnsupportedCommandException, WebDriverException, WebDriverWait,
)


def element(name="element-one", frame="root"):
    return {"__unipus_element__": name, "frame": frame}


class FakeBridge:
    def __init__(self):
        self.calls = []
        self.handles = ["window-one"]
        self.current = "window-one"
        self.url = "https://example.invalid/course"
        self.loading = []
        self.cookies = [{"name": "jwt", "value": "fixture-not-a-token", "httpOnly": True}]
        self.evaluate = lambda request: None
        self.failure = None

    def callJson(self, raw):
        request = json.loads(raw)
        self.calls.append(request)
        if self.failure:
            return json.dumps({"ok": False, "error": self.failure})
        op = request["op"]
        if op == "current_window":
            if self.current is None:
                self.current = "window-restarted"
                self.handles.append(self.current)
            value = self.current
        elif op == "windows":
            value = list(self.handles)
        elif op == "evaluate":
            value = self.evaluate(request)
        elif op == "new_window":
            value = "window-" + str(len(self.handles) + 1)
            self.handles.append(value)
        elif op == "switch_window":
            self.current = request["window"]
            value = None
        elif op == "close_window":
            self.handles.remove(request["window"])
            self.current = self.handles[0] if self.handles else None
            value = None
        elif op == "navigate":
            self.url = request["url"]
            value = None
        elif op == "loading":
            value = self.loading.pop(0) if self.loading else False
        elif op == "url":
            value = self.url
        elif op == "title":
            value = "Fixture course"
        elif op == "cookies":
            value = self.cookies
        elif op == "screenshot":
            value = base64.b64encode(b"\x89PNG\r\n\x1a\nfixture").decode("ascii")
        elif op == "add_document_script":
            value = "native-script-id"
        elif op == "quit":
            self.handles = []
            self.current = None
            value = None
        else:
            raise AssertionError("Unexpected fake native operation: " + op)
        return json.dumps({"ok": True, "value": value})


class DriverTests(unittest.TestCase):
    def setUp(self):
        self.bridge = FakeBridge()
        self.driver = AndroidWebDriver(self.bridge)
        self.bridge.calls.clear()

    def evaluations(self):
        return [request for request in self.bridge.calls if request["op"] == "evaluate"]

    def test_nested_element_results_and_arguments_preserve_frame_identity(self):
        self.bridge.evaluate = lambda request: {"items": [element(frame="nested-frame"), 4]}
        result = self.driver.execute_script("return arguments[0];", {"hello": "中文"})
        found = result["items"][0]
        self.assertIsInstance(found, AndroidWebElement)
        self.assertIs(found.parent, self.driver)
        self.assertEqual(found.id, "element-one")
        self.driver.execute_script("return arguments[0];", {"node": [found]})
        request = self.evaluations()[-1]
        self.assertEqual(request["args"], [{"node": [element(frame="nested-frame")]}])
        self.assertEqual(request["window"], "window-one")

    def test_cross_session_elements_and_invalid_json_values_are_rejected(self):
        other = AndroidWebElement(AndroidWebDriver(FakeBridge()), "different")
        for value in (other, float("nan"), object(), {3: "invalid-key"}):
            with self.subTest(value=type(value).__name__), self.assertRaises(InvalidArgumentException):
                self.driver.execute_script("return arguments[0]", value)
        self.assertEqual(self.evaluations(), [])

    def test_native_error_classes_are_preserved(self):
        for name, cls in (("StaleElementReferenceException", StaleElementReferenceException),
                          ("TimeoutException", TimeoutException),
                          ("InvalidSelectorException", InvalidSelectorException),
                          ("NoSuchFrameException", NoSuchFrameException),
                          ("UnsupportedOperationException", UnsupportedCommandException),
                          *((name, JavascriptException) for name in (
                              "Error", "TypeError", "ReferenceError", "RangeError",
                              "EvalError", "URIError", "AggregateError", "SyntaxError",
                          ))):
            with self.subTest(name=name):
                self.bridge.failure = {"name": name, "message": "fixture error"}
                with self.assertRaisesRegex(cls, "fixture error"):
                    self.driver.execute_script("return 1")

    def test_invalid_native_envelope_does_not_look_like_success(self):
        for response in ("not-json", "null", "{}", '{"ok":1}'):
            with self.subTest(response=response):
                with self.assertRaises(WebDriverException):
                    driver = AndroidWebDriver(lambda raw: response)
                    driver.execute_script("return 1")

    def test_find_decodes_elements_and_reports_missing_elements(self):
        self.bridge.evaluate = lambda request: [element()] if request["args"][-1] == ".available" else []
        found = self.driver.find_element(By.CSS_SELECTOR, ".available")
        self.assertIsInstance(found, AndroidWebElement)
        self.assertEqual(found._frame, "root")
        self.assertEqual(self.driver.find_elements(By.ID, "missing"), [])
        with self.assertRaises(NoSuchElementException):
            self.driver.find_element(By.ID, "missing")
        with self.assertRaises(InvalidArgumentException):
            self.driver.find_element(By.ID)

    def test_element_lookup_uses_its_own_frame_and_root(self):
        self.bridge.evaluate = lambda request: [element("child", "child-frame")]
        parent = AndroidWebElement(self.driver, "container", "child-frame", "window-one")
        result = parent.find_element(By.XPATH, ".//input")
        request = self.evaluations()[-1]
        self.assertEqual(request["frame"], "child-frame")
        self.assertEqual(request["args"], [element("container", "child-frame"), "xpath", ".//input"])
        self.assertEqual(result.id, "child")

    def test_frame_probe_tracks_nested_frames_and_restores_parent(self):
        answers = iter(("frame-a", "frame-b"))
        self.bridge.evaluate = lambda request: next(answers) if "unipus:frame-probe" in request["script"] else 12
        root_frame = AndroidWebElement(self.driver, "iframe-a", "root", "window-one")
        self.driver.switch_to.frame(root_frame)
        self.driver.execute_script("return 12;")
        self.assertEqual(self.evaluations()[-1]["frame"], "frame-a")
        child_frame = AndroidWebElement(self.driver, "iframe-b", "frame-a", "window-one")
        self.driver.switch_to.frame(child_frame)
        self.assertEqual(self.driver._frame, "frame-b")
        self.driver.switch_to.parent_frame()
        self.assertEqual(self.driver._frame, "frame-a")
        self.driver.switch_to.default_content()
        self.assertIsNone(self.driver._frame)
        self.assertEqual(self.driver._frames, [])
        first = self.evaluations()[0]
        self.assertIn("event.source !== element.contentWindow", first["script"])
        self.assertIn("__unipus_frame_probe__", first["script"])

    def test_invalid_frame_reply_is_not_accepted(self):
        self.bridge.evaluate = lambda request: None
        with self.assertRaises(NoSuchFrameException):
            self.driver.switch_to.frame(AndroidWebElement(self.driver, "iframe", "root", "window-one"))
        self.assertEqual(self.driver._frames, [])
        with self.assertRaises(NoSuchFrameException):
            self.driver.switch_to.frame(True)

    def test_real_window_handles_are_used_and_close_resets_context(self):
        self.assertEqual(self.driver.current_window_handle, "window-one")
        self.driver._frame = "frame-a"
        self.driver.switch_to.new_window("tab")
        self.assertEqual(self.driver.current_window_handle, "window-2")
        self.assertIsNone(self.driver._frame)
        self.driver.switch_to.window("window-one")
        with self.assertRaises(NoSuchWindowException):
            self.driver.switch_to.window("not-real")
        self.driver.close()
        self.assertEqual(self.driver.window_handles, ["window-2"])
        self.assertEqual(self.driver.current_window_handle, "window-2")

    def test_navigation_waits_for_loading_and_document_completion(self):
        self.bridge.loading = [True, False]
        self.bridge.evaluate = lambda request: "complete"
        self.driver.set_page_load_timeout(0.5)
        self.driver.get("https://example.invalid/next")
        self.assertEqual(self.driver.current_url, "https://example.invalid/next")
        navigate = next(request for request in self.bridge.calls if request["op"] == "navigate")
        self.assertEqual(navigate["timeoutMs"], 500)
        self.assertGreaterEqual(len([r for r in self.bridge.calls if r["op"] == "loading"]), 2)

    def test_loading_timeout_is_bounded(self):
        self.bridge.loading = [True] * 10
        self.driver.set_page_load_timeout(0.01)
        with self.assertRaises(TimeoutException):
            self.driver.get("https://example.invalid/never")
        self.assertLessEqual(len([r for r in self.bridge.calls if r["op"] == "loading"]), 3)

    def test_async_script_gets_callback_wrapper_and_native_timeout(self):
        self.bridge.evaluate = lambda request: {"result": "callback-value"}
        self.driver.set_script_timeout(0.012)
        result = self.driver.execute_async_script("arguments[arguments.length-1](arguments[0]);", "payload")
        self.assertEqual(result, {"result": "callback-value"})
        request = self.evaluations()[-1]
        self.assertEqual(request["timeoutMs"], 12)
        self.assertEqual(request["args"], ["payload"])
        self.assertIn("values.concat([done])", request["script"])
        self.assertIn("setTimeout", request["script"])

    def test_cookie_lookup_uses_native_cookie_store_including_http_only(self):
        self.assertEqual(self.driver.get_cookie("jwt"), self.bridge.cookies[0])
        self.assertIsNone(self.driver.get_cookie("missing"))
        self.bridge.cookies = "jwt=a=b=c; language=zh-CN"
        self.assertEqual(self.driver.get_cookie("jwt"), {"name": "jwt", "value": "a=b=c"})
        self.assertTrue(any(request["op"] == "cookies" for request in self.bridge.calls))

    def test_desktop_cdp_and_cropped_screenshots_fail_explicitly(self):
        with self.assertRaises(UnsupportedCommandException):
            self.driver.execute_cdp_cmd("Emulation.setDeviceMetricsOverride", {"width": 1400})
        with self.assertRaises(UnsupportedCommandException):
            self.driver.execute_cdp_cmd("Page.captureScreenshot", {"clip": {"x": 0}})
        with self.assertRaises(UnsupportedCommandException):
            _ = AndroidWebElement(self.driver, "node").screenshot_as_png
        self.assertEqual(self.bridge.calls, [])

    def test_document_script_and_viewport_screenshot_use_real_native_operations(self):
        result = self.driver.execute_cdp_cmd("Page.addScriptToEvaluateOnNewDocument", {"source": "window.fixture=true;"})
        self.assertEqual(result, {"identifier": "native-script-id"})
        self.assertEqual(self.bridge.calls[-1]["op"], "add_document_script")
        self.assertEqual(self.driver.get_screenshot_as_png(), b"\x89PNG\r\n\x1a\nfixture")

    def test_text_input_uses_native_setter_and_react_input_events(self):
        def evaluate(request):
            if "tagName.toLowerCase" in request["script"]:
                return "textarea"
            return True
        self.bridge.evaluate = evaluate
        target = AndroidWebElement(self.driver, "text-input", "root", "window-one")
        target.send_keys(Keys.CONTROL, "a", Keys.NULL, "中文 😀", Keys.SPACE)
        request = self.evaluations()[-1]
        self.assertEqual(request["args"][1], [Keys.CONTROL, "a", Keys.NULL, "中文 😀", Keys.SPACE])
        self.assertIn("Object.getOwnPropertyDescriptor(proto, 'value').set", request["script"])
        self.assertIn("InputEvent", request["script"])
        self.assertIn("insert(key)", request["script"])
        target.clear()
        self.assertIs(self.evaluations()[-1]["args"][-1], True)

    def test_file_upload_passes_bytes_not_a_desktop_picker_path(self):
        def evaluate(request):
            if "tagName.toLowerCase" in request["script"]:
                return "input"
            if request["args"][-1] == "type":
                return "file"
            return True
        self.bridge.evaluate = evaluate
        target = AndroidWebElement(self.driver, "upload", "root", "window-one")
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "generated.wav"
            path.write_bytes(b"RIFFfixture-audio")
            target.send_keys(str(path))
        request = self.evaluations()[-1]
        self.assertIn("unipus:upload", request["script"])
        upload = request["args"][1][0]
        self.assertEqual(upload["name"], "generated.wav")
        self.assertEqual(base64.b64decode(upload["data"]), b"RIFFfixture-audio")
        self.assertNotIn("path", upload)

    def test_quit_invalidates_session_and_does_not_repeat_native_close(self):
        self.driver.quit()
        self.driver.quit()
        self.assertEqual([r["op"] for r in self.bridge.calls], ["quit"])
        with self.assertRaises(InvalidSessionIdException):
            _ = self.driver.title

    def test_new_driver_after_quit_gets_a_fresh_window_without_old_frame_state(self):
        self.driver._frame = "old-frame"
        self.driver._frames = [None, "previous-frame"]
        self.driver.quit()
        restarted = AndroidWebDriver(self.bridge)
        self.assertEqual(restarted.current_window_handle, "window-restarted")
        self.assertIsNone(restarted._frame)
        self.assertEqual(restarted._frames, [])
        self.assertNotEqual(restarted.session_id, self.driver.session_id)

    @unittest.skipUnless(shutil.which("node"), "Optional Node.js syntax compiler is unavailable")
    def test_javascript_bodies_are_syntactically_valid(self):
        self.bridge.evaluate = lambda request: None
        self.driver.execute_async_script("arguments[arguments.length-1](1);")
        sources = [android_driver._FIND_JS, android_driver._SEND_KEYS_JS,
                   android_driver._FRAME_PROBE_JS, android_selenium._POINTER_JS,
                   self.evaluations()[-1]["script"]]
        result = subprocess.run([shutil.which("node"), "-e",
                                 "for (const source of JSON.parse(process.argv[1])) new Function(source);",
                                 json.dumps(sources)], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)

    @unittest.skipUnless(shutil.which("node"), "Optional Node.js runtime is unavailable")
    def test_text_input_js_preserves_unicode_selection_and_native_setter(self):
        program = r"""
class Input {
    constructor(value) { this._value=value; this.isConnected=true; this.isContentEditable=false; this.selectionStart=this.selectionEnd=value.length; this.events=[]; }
    get value() { return this._value; }
    set value(value) { this._value=value; }
    focus() {}
    setSelectionRange(start,end) { this.selectionStart=start; this.selectionEnd=end; }
    dispatchEvent(event) { this.events.push(event.type); return true; }
    matches() { return false; }
}
global.HTMLInputElement=Input;
global.HTMLTextAreaElement=class Textarea extends Input {};
global.Event=class Event { constructor(type,options) { this.type=type; Object.assign(this,options); } };
global.InputEvent=global.KeyboardEvent=global.Event;
global.document={querySelectorAll:()=>[]};
const fn=new Function(JSON.parse(process.argv[1]));
const el=new Input('replace me');
Object.defineProperty(el,'value',{get(){return this._value;},set(){throw new Error('Do not use the React instance tracker setter');}});
fn.apply(global,[el,['\ue009','a','\ue000','中文 😀','\ue00d'],false]);
if(el.value!=='中文 😀 ') throw new Error('Unicode or SPACE key was corrupted: '+el.value);
fn.apply(global,[el,['\ue003','\ue003'],false]);
if(el.value!=='中文 ') throw new Error('Backspace split a Unicode code point: '+JSON.stringify(el.value));
fn.apply(global,[el,['\ue011','\ue017'],false]);
if(el.value!=='文 ') throw new Error('HOME/DELETE did not update the selection');
fn.apply(global,[el,[],true]);
if(el.value!=='') throw new Error('clear did not empty the value');
if(!el.events.includes('input') || !el.events.includes('change')) throw new Error('Input/change events missing');
console.log('native-setter/unicode/selection/input-events verified');
"""
        result = subprocess.run([shutil.which("node"), "-e", program, json.dumps(android_driver._SEND_KEYS_JS)],
                                capture_output=True, encoding="utf-8")
        self.assertEqual(result.returncode, 0, result.stderr)

    @unittest.skipUnless(shutil.which("node"), "Optional Node.js runtime is unavailable")
    def test_frame_probe_js_checks_source_nonce_and_timeout(self):
        program = r"""
(async()=>{
    let listener;
    const host={addEventListener(name,value){listener=value;},removeEventListener(){listener=null;}};
    global.window=host;
    const child={postMessage(message,target){
        listener({source:{},data:{__unipus_frame_reply__:message.__unipus_frame_probe__,frame:'spoofed'}});
        listener({source:child,data:{__unipus_frame_reply__:'wrong',frame:'spoofed'}});
        queueMicrotask(()=>listener({source:child,data:{__unipus_frame_reply__:message.__unipus_frame_probe__,frame:'actual-child'}}));
    }};
    const fn=new Function(JSON.parse(process.argv[1]));
    const actual=await fn.apply(host,[{tagName:'IFRAME',contentWindow:child},'nonce-one',100]);
    if(actual!=='actual-child') throw new Error('Frame probe accepted an invalid reply');
    const silent={postMessage(){}};
    try { await fn.apply(host,[{tagName:'IFRAME',contentWindow:silent},'nonce-two',2]); throw new Error('Missing frame did not time out'); }
    catch(error) { if(error.name!=='NoSuchFrameException') throw error; }
    console.log('frame source/nonce/timeout verified');
})().catch(error=>{console.error(error);process.exit(1);});
"""
        result = subprocess.run([shutil.which("node"), "-e", program, json.dumps(android_driver._FRAME_PROBE_JS)],
                                capture_output=True, encoding="utf-8")
        self.assertEqual(result.returncode, 0, result.stderr)


class SeleniumPrimitiveTests(unittest.TestCase):
    def test_wait_ignores_missing_elements_and_returns_truthy_value(self):
        values = iter((NoSuchElementException("not yet"), False, "ready"))
        def condition(driver):
            result = next(values)
            if isinstance(result, Exception):
                raise result
            return result
        self.assertEqual(WebDriverWait(object(), .1, .001).until(condition), "ready")

    def test_wait_times_out_and_until_not_handles_disappearance(self):
        with self.assertRaises(TimeoutException):
            WebDriverWait(object(), .003, .001).until(lambda driver: False)
        def missing(driver):
            raise NoSuchElementException("gone")
        self.assertTrue(WebDriverWait(object(), .01).until_not(missing))

    def test_expected_clickability_checks_visibility_and_enabled_state(self):
        target = Mock()
        target.is_displayed.return_value = True
        target.is_enabled.return_value = False
        self.assertFalse(EC.element_to_be_clickable(target)(None))
        target.is_enabled.return_value = True
        self.assertIs(EC.element_to_be_clickable(target)(None), target)

    def test_action_chain_runs_only_on_perform_and_preserves_order(self):
        driver, source, target = Mock(), Mock(), Mock()
        chain = ActionChains(driver).click_and_hold(source).move_to_element(target).release(target)
        driver.execute_script.assert_not_called()
        chain.perform()
        self.assertEqual([call.args[-1] for call in driver.execute_script.call_args_list], ["hold", "move", "release"])
        self.assertIs(driver.execute_script.call_args_list[0].args[1], source)
        self.assertIs(driver.execute_script.call_args_list[-1].args[1], target)


class ApiClientTests(unittest.TestCase):
    def client(self):
        client = android_openai.OpenAI(api_key="fixture-only", base_url="https://example.invalid/v1")
        client._opener = Mock()
        return client

    def response(self, payload):
        return io.BytesIO(json.dumps(payload).encode("utf-8"))

    def test_chat_completion_request_and_response_match_core_usage(self):
        client = self.client()
        client._opener.open.return_value = self.response({"choices": [{"message": {"content": "fixture answer"}, "finish_reason": "stop"}], "usage": {"total_tokens": 8}})
        result = client.chat.completions.create(model="fixture-model", messages=[{"role": "user", "content": "test"}], max_tokens=32, extra_body={"temperature": .3})
        self.assertEqual(result.choices[0].message.content, "fixture answer")
        self.assertEqual(result.choices[0].finish_reason, "stop")
        self.assertEqual(result.model, "fixture-model")
        self.assertEqual(result.usage.total_tokens, 8)
        request = client._opener.open.call_args.args[0]
        self.assertEqual(request.full_url, "https://example.invalid/v1/chat/completions")
        self.assertEqual(request.get_header("Authorization"), "Bearer fixture-only")
        self.assertEqual(json.loads(request.data)["temperature"], .3)

    def test_http_errors_keep_status_and_provider_message_for_core_retry_logic(self):
        client = self.client()
        body = b'{"error":{"message":"max_tokens is too large"}}'
        client._opener.open.side_effect = urllib.error.HTTPError("https://example.invalid", 400, "Bad Request", {}, io.BytesIO(body))
        with self.assertRaises(android_openai.APIStatusError) as raised:
            client.chat.completions.create(model="fixture", messages=[])
        self.assertEqual(raised.exception.status_code, 400)
        self.assertIn("max_tokens", str(raised.exception))
        self.assertEqual(raised.exception.response.status_code, 400)

    def test_invalid_api_response_is_not_treated_as_an_answer(self):
        client = self.client()
        for payload in ({"choices": []}, {"choices": [{"message": {"content": ["not-text"]}}]}, ["invalid"]):
            with self.subTest(payload=payload):
                client._opener.open.return_value = self.response(payload)
                with self.assertRaises(android_openai.APIResponseValidationError):
                    client.chat.completions.create(model="fixture", messages=[])

    def test_timeout_and_streaming_are_reported_explicitly(self):
        client = self.client()
        client._opener.open.side_effect = urllib.error.URLError(socket.timeout("fixture"))
        with self.assertRaises(android_openai.APITimeoutError):
            client.chat.completions.create(model="fixture", messages=[])
        with self.assertRaises(NotImplementedError):
            client.chat.completions.create(model="fixture", messages=[], stream=True)

    def test_redirects_cannot_forward_authorization_to_another_origin(self):
        handler = android_openai._SameOriginRedirect()
        request = urllib.request.Request("https://example.invalid/v1", headers={"Authorization": "Bearer fixture"})
        with self.assertRaises(android_openai.APIConnectionError):
            handler.redirect_request(request, None, 302, "Found", {}, "https://other.invalid/steal")
        redirected = handler.redirect_request(request, None, 302, "Found", {}, "https://example.invalid/new")
        self.assertEqual(redirected.full_url, "https://example.invalid/new")


if __name__ == "__main__":
    unittest.main()
