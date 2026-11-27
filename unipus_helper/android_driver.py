"""Selenium-shaped adapter for the app's native Android WebView kernel.

BrowserKernel.callJson is the sole native boundary. Java's document bootstrap
hydrates element references and serializes results in their owning frame; Python
never exposes a Java object or an unrestricted native API to the course page.
"""
from __future__ import annotations

import base64
import json
import math
import mimetypes
from pathlib import Path
import threading
import time
from types import SimpleNamespace
import uuid

from .android_selenium import (
    By, ElementClickInterceptedException, ElementNotInteractableException,
    InvalidArgumentException, InvalidSelectorException, InvalidSessionIdException,
    JavascriptException, NoAlertPresentException, NoSuchDriverException,
    NoSuchElementException, NoSuchFrameException, NoSuchWindowException,
    SessionNotCreatedException, StaleElementReferenceException, TimeoutException,
    UnsupportedCommandException, WebDriverException,
)


_ERRORS = {item.__name__: item for item in (
    WebDriverException, ElementClickInterceptedException,
    ElementNotInteractableException, InvalidArgumentException,
    InvalidSelectorException, InvalidSessionIdException, JavascriptException,
    NoAlertPresentException, NoSuchDriverException, NoSuchElementException,
    NoSuchFrameException, NoSuchWindowException, SessionNotCreatedException,
    StaleElementReferenceException, TimeoutException, UnsupportedCommandException,
)}
_ERRORS.update({name: JavascriptException for name in (
    "Error", "TypeError", "ReferenceError", "RangeError", "EvalError",
    "URIError", "AggregateError", "SyntaxError",
)})
_ERRORS.update({"TimeoutError": TimeoutException,
                "UnsupportedOperationException": UnsupportedCommandException})
_DEFAULT = object()


_FIND_JS = r"""
/* unipus:find */
const root = arguments[0] || document, by = arguments[1], value = String(arguments[2]);
let nodes;
try {
    if (by === 'css selector') nodes = Array.from(root.querySelectorAll(value));
    else if (by === 'xpath') {
        const doc = root.ownerDocument || root;
        const result = doc.evaluate(value, root, null, XPathResult.ORDERED_NODE_SNAPSHOT_TYPE, null);
        nodes = Array.from({length:result.snapshotLength}, (_, i) => result.snapshotItem(i));
        if (nodes.some(node => node.nodeType !== 1)) throw new Error('XPath must select elements');
    } else if (by === 'id') nodes = Array.from(root.querySelectorAll('[id]')).filter(el => el.id === value);
    else if (by === 'name') nodes = Array.from(root.querySelectorAll('[name]')).filter(el => el.getAttribute('name') === value);
    else if (by === 'tag name') nodes = Array.from(root.querySelectorAll(value));
    else if (by === 'class name') {
        if (!value || /\s/.test(value)) throw new Error('Compound class names are not supported');
        nodes = Array.from(root.querySelectorAll('[class]')).filter(el => el.classList.contains(value));
    } else if (by === 'link text' || by === 'partial link text') {
        nodes = Array.from(root.querySelectorAll('a')).filter(el => {
            const text = (el.innerText || '').trim();
            return by === 'link text' ? text === value : text.includes(value);
        });
    } else throw new Error('Unsupported locator strategy: ' + by);
} catch (cause) {
    const error = new Error(cause.message); error.name = 'InvalidSelectorException'; throw error;
}
return nodes;
"""

_SEND_KEYS_JS = r"""
/* unipus:send-keys */
const el = arguments[0], values = arguments[1], clearOnly = arguments[2];
const fail = message => { const error = new Error(message); error.name='ElementNotInteractableException'; throw error; };
if (!el || !el.isConnected) { const e = new Error('Element is detached'); e.name='StaleElementReferenceException'; throw e; }
if (el.disabled || el.readOnly) fail('The input is disabled or readonly');
el.focus();
const editable = el.isContentEditable;
const textControl = el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement;
const read = () => editable ? (el.textContent || '') : textControl ? String(el.value || '') : '';
const write = (value, data, inputType) => {
    if (editable) el.textContent = value;
    else if (textControl) {
        const proto = el instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
        const setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
        setter.call(el, value);
    } else fail('This element does not accept text');
    let event;
    try { event = new InputEvent('input', {bubbles:true, composed:true, data:data, inputType:inputType}); }
    catch (_) { event = new Event('input', {bubbles:true, composed:true}); }
    el.dispatchEvent(event);
};
let start = textControl && typeof el.selectionStart === 'number' ? el.selectionStart : read().length;
let end = textControl && typeof el.selectionEnd === 'number' ? el.selectionEnd : start;
const select = () => { if (textControl && typeof el.setSelectionRange === 'function') { try { el.setSelectionRange(start, end); } catch (_) {} } };
const insert = (text, inputType='insertText') => {
    const before = read();
    write(before.slice(0, start) + text + before.slice(end), text, inputType);
    start += text.length; end = start; select();
};
if (clearOnly) {
    write('', null, 'deleteContentBackward'); start = end = 0; select();
    el.dispatchEvent(new Event('change', {bubbles:true, composed:true}));
    return true;
}
let ctrl=false, shift=false, alt=false, meta=false;
const special = {'\ue003':'Backspace','\ue004':'Tab','\ue005':'Clear','\ue006':'Enter','\ue007':'Enter','\ue00b':'Pause','\ue00c':'Escape','\ue00d':' ','\ue010':'End','\ue011':'Home','\ue012':'ArrowLeft','\ue013':'ArrowUp','\ue014':'ArrowRight','\ue015':'ArrowDown','\ue017':'Delete'};
for (const value of values) for (const char of String(value)) {
    if (char === '\ue009') { ctrl=true; continue; }
    if (char === '\ue008') { shift=true; continue; }
    if (char === '\ue00a') { alt=true; continue; }
    if (char === '\ue03d') { meta=true; continue; }
    if (char === '\ue000') { ctrl=shift=alt=meta=false; continue; }
    const key = special[char] || char;
    const options = {key:key, bubbles:true, cancelable:true, composed:true, ctrlKey:ctrl, shiftKey:shift, altKey:alt, metaKey:meta};
    const allowed = el.dispatchEvent(new KeyboardEvent('keydown', options));
    if (allowed) {
        if ((ctrl || meta) && key.toLowerCase() === 'a') { start=0; end=read().length; select(); }
        else if (ctrl || meta || alt) { /* shortcut handlers received the event */ }
        else if (key === 'Backspace' || key === 'Delete') {
            if (start === end) {
                const value=read();
                if (key === 'Backspace') start=Math.max(0,start-(start>1 && value.codePointAt(start-2)>0xffff?2:1));
                else end=Math.min(value.length,end+(value.codePointAt(end)>0xffff?2:1));
            }
            insert('', key === 'Backspace' ? 'deleteContentBackward' : 'deleteContentForward');
        } else if (key === 'Clear') { start=0; end=read().length; insert('','deleteContentBackward');
        } else if (key === 'Home') { start=end=0; select(); }
        else if (key === 'End') { start=end=read().length; select(); }
        else if (key === 'ArrowLeft' || key === 'ArrowRight') {
            start=end=Math.max(0,Math.min(read().length,(key==='ArrowLeft'?start-1:end+1))); select();
        } else if (key === 'Tab') {
            const focusable=Array.from(document.querySelectorAll('input,textarea,select,button,a[href],[tabindex]'))
                .filter(node => !node.disabled && node.tabIndex >= 0 && node.getClientRects().length);
            const index=focusable.indexOf(el), step=shift?-1:1;
            if (focusable.length) focusable[(index+step+focusable.length)%focusable.length].focus();
        } else if (key === 'Enter') {
            el.dispatchEvent(new KeyboardEvent('keypress', options));
            if (editable || el instanceof HTMLTextAreaElement) insert('\n','insertLineBreak');
            else if (el.form && typeof el.form.requestSubmit === 'function') el.form.requestSubmit();
            else if (el.matches('button,input[type=button],input[type=submit]')) el.click();
        } else if (key === ' ' && !editable && !textControl && typeof el.click === 'function') el.click();
        else if (key === ' ' || (!special[char] && !(char.codePointAt(0) >= 0xe000 && char.codePointAt(0) <= 0xf8ff))) {
            el.dispatchEvent(new KeyboardEvent('keypress', options)); insert(key);
        } else if (!special[char]) {
            const error=new Error('Unsupported private-use keyboard code'); error.name='UnsupportedCommandException'; throw error;
        }
    }
    el.dispatchEvent(new KeyboardEvent('keyup', options));
}
if (textControl || editable) el.dispatchEvent(new Event('change', {bubbles:true, composed:true}));
return true;
"""

_FRAME_PROBE_JS = r"""
/* unipus:frame-probe */
const element=arguments[0], nonce=arguments[1], timeout=arguments[2];
if (!element || !element.contentWindow || !/^(IFRAME|FRAME)$/.test(element.tagName)) {
    const error=new Error('The selected element is not a frame'); error.name='NoSuchFrameException'; throw error;
}
return new Promise((resolve,reject) => {
    let timer, retry, finished=false;
    const finish = (error, frame) => {
        if (finished) return;
        finished=true;
        clearTimeout(timer); clearInterval(retry);
        window.removeEventListener('message',listener);
        if (error) reject(error); else resolve(frame);
    };
    const fail = message => {
        const error=new Error(message); error.name='NoSuchFrameException'; finish(error);
    };
    const listener = event => {
        const data=event.data;
        if (event.source !== element.contentWindow || !data || data.__unipus_frame_reply__ !== nonce) return;
        if (!data.frame || typeof data.frame !== 'string') {
            fail('The frame did not provide an automation identity');
        } else finish(null,data.frame);
    };
    const probe = () => {
        if (finished) return;
        try {
            if (!element.contentWindow) { fail('The frame browsing context is unavailable'); return; }
            element.contentWindow.postMessage({__unipus_frame_probe__:nonce}, '*');
        } catch (error) {
            fail('The frame automation probe could not be delivered');
        }
    };
    window.addEventListener('message',listener);
    timer=setTimeout(() => fail('The frame automation handshake timed out'),timeout);
    retry=setInterval(probe,50);
    probe();
});
"""


def _seconds(value):
    if hasattr(value, "total_seconds"):
        value = value.total_seconds()
    value = float(value)
    if not math.isfinite(value) or value < 0:
        raise ValueError("Timeout must be finite and non-negative")
    return value


class AndroidWebElement:
    def __init__(self, driver, element_id, frame=None, window=None):
        self._driver = driver
        self._id = str(element_id)
        self._frame = frame
        self._window = window

    @property
    def parent(self):
        return self._driver

    @property
    def id(self):
        return self._id

    def _execute(self, script, *args):
        return self._driver._evaluate(script, (self, *args), frame=self._frame, window=self._window)

    @property
    def text(self):
        return self._execute("return arguments[0].innerText || arguments[0].textContent || '';")

    @property
    def tag_name(self):
        return self._execute("return arguments[0].tagName.toLowerCase();")

    def get_attribute(self, name):
        return self._execute(r"""
const el=arguments[0], name=String(arguments[1]);
const prop=el[({'class':'className','readonly':'readOnly','for':'htmlFor'})[name] || name];
if (typeof prop === 'boolean') return prop ? 'true' : null;
if (prop !== undefined && prop !== null && (typeof prop === 'string' || typeof prop === 'number')) return String(prop);
return el.getAttribute(name);
""", str(name))

    def get_dom_attribute(self, name):
        return self._execute("return arguments[0].getAttribute(arguments[1]);", str(name))

    def get_property(self, name):
        return self._execute("return arguments[0][arguments[1]];", str(name))

    def value_of_css_property(self, name):
        return self._execute("return getComputedStyle(arguments[0]).getPropertyValue(arguments[1]);", str(name))

    def click(self):
        self._execute(r"""
/* unipus:click */
const el=arguments[0];
if (el.disabled || el.getAttribute('aria-disabled') === 'true') { const e=new Error('Element is disabled'); e.name='ElementNotInteractableException'; throw e; }
el.scrollIntoView({block:'center',inline:'nearest'});
const r=el.getBoundingClientRect();
if (!r.width || !r.height) { const e=new Error('Element has no visible bounds'); e.name='ElementNotInteractableException'; throw e; }
const opts={bubbles:true,cancelable:true,composed:true,view:window,clientX:r.left+r.width/2,clientY:r.top+r.height/2,button:0};
el.dispatchEvent(new MouseEvent('mouseover',opts));
if (typeof PointerEvent !== 'undefined') el.dispatchEvent(new PointerEvent('pointerdown',{...opts,pointerId:1,pointerType:'mouse',isPrimary:true}));
el.dispatchEvent(new MouseEvent('mousedown',opts));
if (typeof el.focus === 'function') el.focus();
if (typeof PointerEvent !== 'undefined') el.dispatchEvent(new PointerEvent('pointerup',{...opts,pointerId:1,pointerType:'mouse',isPrimary:true}));
el.dispatchEvent(new MouseEvent('mouseup',opts));
if (typeof el.click === 'function') el.click(); else el.dispatchEvent(new MouseEvent('click',opts));
return true;
""")

    def clear(self):
        self._execute(_SEND_KEYS_JS, [], True)

    def send_keys(self, *values):
        flattened = []
        for value in values:
            if isinstance(value, (list, tuple)):
                flattened.extend(str(item) for item in value)
            else:
                flattened.append(str(value))
        if self.tag_name == "input" and (self.get_attribute("type") or "").lower() == "file":
            files = []
            remaining = 10 * 1024 * 1024  # Base64 plus the JS body must fit the 16 MiB bridge envelope.
            for name in "".join(flattened).splitlines():
                path = Path(name)
                if not path.is_file():
                    raise InvalidArgumentException("Upload source must be a readable app-local file")
                if path.stat().st_size > remaining:
                    raise InvalidArgumentException("Upload sources exceed the 10 MiB combined limit")
                with path.open("rb") as handle:
                    data = handle.read(remaining + 1)
                if len(data) > remaining:
                    raise InvalidArgumentException("Upload sources exceed the 10 MiB combined limit")
                remaining -= len(data)
                files.append({"name": path.name, "type": mimetypes.guess_type(path.name)[0] or "application/octet-stream",
                              "data": base64.b64encode(data).decode("ascii")})
            if not files:
                raise InvalidArgumentException("At least one upload file is required")
            self._execute(r"""
/* unipus:upload */
const el=arguments[0];
if (typeof DataTransfer === 'undefined' || typeof File === 'undefined') { const e=new Error('This WebView cannot attach generated files'); e.name='UnsupportedCommandException'; throw e; }
if (!el.multiple && arguments[1].length > 1) { const e=new Error('The input does not accept multiple files'); e.name='InvalidArgumentException'; throw e; }
const transfer=new DataTransfer();
for (const entry of arguments[1]) {
    const data=Uint8Array.from(atob(entry.data),char=>char.charCodeAt(0));
    transfer.items.add(new File([data],entry.name,{type:entry.type}));
}
el.files=transfer.files;
el.dispatchEvent(new Event('input',{bubbles:true,composed:true}));
el.dispatchEvent(new Event('change',{bubbles:true,composed:true}));
return true;
""", files)
            return
        self._execute(_SEND_KEYS_JS, flattened, False)

    def find_elements(self, by=By.ID, value=None):
        return self._driver._find(by, value, self)

    def find_element(self, by=By.ID, value=None):
        elements = self.find_elements(by, value)
        if not elements:
            raise NoSuchElementException(f"No element matched {by}: {value}")
        return elements[0]

    def is_displayed(self):
        return bool(self._execute(r"""
const el=arguments[0];
for (let node=el; node && node.nodeType===1; node=node.parentElement) {
    const style=getComputedStyle(node);
    if (style.display==='none' || style.visibility==='hidden' || style.visibility==='collapse' || style.opacity==='0') return false;
}
const target=el.tagName==='OPTION' ? el.closest('select') : el;
return !!target && !!target.getClientRects().length;
"""))

    def is_enabled(self):
        return bool(self._execute("return !arguments[0].disabled && !arguments[0].matches(':disabled') && arguments[0].getAttribute('aria-disabled') !== 'true';"))

    def is_selected(self):
        return bool(self._execute("return !!(arguments[0].selected || arguments[0].checked);"))

    @property
    def rect(self):
        return self._execute("const r=arguments[0].getBoundingClientRect(); return {x:r.left+scrollX,y:r.top+scrollY,width:r.width,height:r.height};")

    @property
    def location(self):
        rect = self.rect
        return {"x": rect["x"], "y": rect["y"]}

    @property
    def size(self):
        rect = self.rect
        return {"width": rect["width"], "height": rect["height"]}

    @property
    def screenshot_as_png(self):
        raise UnsupportedCommandException("Element-cropped screenshots are not supported by the Android viewport capture API")

    def __eq__(self, other):
        return isinstance(other, AndroidWebElement) and (self._driver, self._window, self._frame, self._id) == (other._driver, other._window, other._frame, other._id)

    def __hash__(self):
        return hash((self._driver, self._window, self._frame, self._id))

    def __repr__(self):
        return f"<AndroidWebElement {self._id!r} frame={self._frame!r}>"


class _SwitchTo:
    def __init__(self, driver):
        self._driver = driver

    def default_content(self):
        self._driver._frame = None
        self._driver._frames.clear()

    def parent_frame(self):
        driver = self._driver
        driver._frame = driver._frames.pop() if driver._frames else None

    def frame(self, frame_reference):
        driver = self._driver
        if isinstance(frame_reference, bool):
            raise NoSuchFrameException("A boolean is not a frame reference")
        if isinstance(frame_reference, int):
            frames = driver.find_elements(By.CSS_SELECTOR, "iframe,frame")
            if frame_reference < 0 or frame_reference >= len(frames):
                raise NoSuchFrameException("Frame index is out of range")
            element = frames[frame_reference]
        elif isinstance(frame_reference, str):
            element = driver.execute_script("return Array.from(document.querySelectorAll('iframe,frame')).find(el=>el.id===arguments[0] || el.name===arguments[0]) || null;", frame_reference)
            if element is None:
                raise NoSuchFrameException("No frame matched the given name or id")
        elif isinstance(frame_reference, AndroidWebElement) and frame_reference.parent is driver:
            element = frame_reference
        else:
            raise NoSuchFrameException("Invalid frame reference")
        timeout_ms = max(1, min(10000, int(driver._script_timeout * 1000)))
        frame = driver._evaluate(_FRAME_PROBE_JS, (element, uuid.uuid4().hex, timeout_ms),
                                 frame=element._frame, window=element._window)
        if not isinstance(frame, str) or not frame:
            raise NoSuchFrameException("The native frame handshake did not return a frame identity")
        driver._frames.append(driver._frame)
        driver._frame = frame

    def window(self, window_name):
        driver = self._driver
        if not isinstance(window_name, str) or window_name not in driver.window_handles:
            raise NoSuchWindowException("The requested WebView window does not exist")
        driver._command("switch_window", window=window_name)
        driver._window = window_name
        self.default_content()

    def new_window(self, type_hint="tab"):
        if type_hint not in ("tab", "window"):
            raise InvalidArgumentException("Window type must be 'tab' or 'window'")
        driver = self._driver
        result = driver._command("new_window", type=type_hint)
        handle = result.get("handle") if isinstance(result, dict) else result
        if not isinstance(handle, str) or not handle:
            raise WebDriverException("The native kernel did not return a new window handle")
        driver._command("switch_window", window=handle)
        driver._window = handle
        self.default_content()

    @property
    def active_element(self):
        element = self._driver.execute_script("return document.activeElement || document.body || document.documentElement;")
        if not isinstance(element, AndroidWebElement):
            raise NoSuchElementException("The current document has no active element")
        return element

    @property
    def alert(self):
        raise UnsupportedCommandException("Handle browser JavaScript dialogs in the Android interface; alert automation is not exposed")


class AndroidWebDriver:
    """Create on a Python worker: native window creation must not block Android UI."""
    def __init__(self, bridge):
        if not callable(getattr(bridge, "callJson", None)) and not callable(bridge):
            raise TypeError("bridge must expose callJson(request_json)")
        self._bridge = bridge
        self._lock = threading.RLock()
        self._closed = False
        self._window = None
        self._frame = None
        self._frames = []
        self._implicit_timeout = 0.0
        self._script_timeout = 30.0
        self._page_load_timeout = 60.0
        self.session_id = uuid.uuid4().hex
        self.switch_to = _SwitchTo(self)
        self.capabilities = {"browserName": "android-webview", "platformName": "Android",
                             "javascriptEnabled": True, "takesScreenshot": True}
        # A new driver after quit must obtain a newly created native WebView,
        # never inherit the previous adapter's destroyed window/frame handles.
        self._window = self.current_window_handle

    def _command(self, op, **params):
        if self._closed:
            raise InvalidSessionIdException("The Android browser session has closed")
        request = {"op": op, **params}
        encoded = json.dumps(request, ensure_ascii=False, allow_nan=False)
        try:
            with self._lock:
                response = (self._bridge.callJson(encoded) if callable(getattr(self._bridge, "callJson", None))
                            else self._bridge(encoded))
        except WebDriverException:
            raise
        except Exception as exc:
            raise WebDriverException("Android browser bridge call failed: " + type(exc).__name__) from exc
        try:
            response = response if isinstance(response, dict) else json.loads(str(response))
        except (ValueError, TypeError) as exc:
            raise WebDriverException("Android browser bridge returned invalid JSON") from exc
        if not isinstance(response, dict) or not isinstance(response.get("ok"), bool):
            raise WebDriverException("Android browser bridge returned an invalid result envelope")
        if not response["ok"]:
            error = response.get("error") or {}
            if isinstance(error, dict):
                name, message = str(error.get("name", "")), str(error.get("message") or "Browser operation failed")
            else:
                name, message = "", str(error)
            raise _ERRORS.get(name.rsplit(".", 1)[-1], WebDriverException)(message)
        return response.get("value")

    def _serialize(self, value):
        if isinstance(value, AndroidWebElement):
            if value.parent is not self:
                raise InvalidArgumentException("An element belongs to a different Android browser session")
            return {"__unipus_element__": value.id, "frame": value._frame}
        if value is None or isinstance(value, (str, bool, int)):
            return value
        if isinstance(value, float):
            if not math.isfinite(value):
                raise InvalidArgumentException("Script numbers must be finite")
            return value
        if isinstance(value, (list, tuple)):
            return [self._serialize(item) for item in value]
        if isinstance(value, dict):
            if not all(isinstance(key, str) for key in value):
                raise InvalidArgumentException("Script object keys must be strings")
            return {key: self._serialize(item) for key, item in value.items()}
        raise InvalidArgumentException("Unsupported script argument type: " + type(value).__name__)

    def _decode(self, value, *, window, frame):
        if isinstance(value, dict):
            if "__unipus_element__" in value:
                if not isinstance(value["__unipus_element__"], (str, int)):
                    raise WebDriverException("Invalid native element reference")
                return AndroidWebElement(self, value["__unipus_element__"], value.get("frame", frame), window)
            return {key: self._decode(item, window=window, frame=frame) for key, item in value.items()}
        if isinstance(value, list):
            return [self._decode(item, window=window, frame=frame) for item in value]
        return value

    def _evaluate(self, script, args=(), *, frame=_DEFAULT, window=None):
        if not isinstance(script, str):
            raise InvalidArgumentException("The JavaScript body must be text")
        window = window or self.current_window_handle
        frame = self._frame if frame is _DEFAULT else frame
        result = self._command("evaluate", script=script, args=self._serialize(args),
                               window=window, frame=frame,
                               timeoutMs=max(1, int(self._script_timeout * 1000)))
        return self._decode(result, window=window, frame=frame)

    def execute_script(self, script, *args):
        return self._evaluate(script, args)

    def execute_async_script(self, script, *args):
        if not isinstance(script, str):
            raise InvalidArgumentException("The JavaScript body must be text")
        body = """
/* unipus:async-script */
const values=Array.from(arguments);
return new Promise((resolve,reject) => {
    let finished=false;
    const timer=setTimeout(() => {
        if (finished) return; finished=true;
        const error=new Error('Async JavaScript callback timed out'); error.name='TimeoutException'; reject(error);
    }, TIMEOUT);
    const done=value => { if (!finished) { finished=true; clearTimeout(timer); resolve(value); } };
    try {
        const result=(new Function(SOURCE)).apply(window,values.concat([done]));
        if (result && typeof result.catch === 'function') result.catch(error => { if (!finished) { finished=true; clearTimeout(timer); reject(error); } });
    } catch(error) { if (!finished) { finished=true; clearTimeout(timer); reject(error); } }
});
""".replace("TIMEOUT", str(max(1, int(self._script_timeout * 1000)))).replace("SOURCE", json.dumps(script))
        return self._evaluate(body, args)

    def _find(self, by, value, root=None):
        if value is None:
            raise InvalidArgumentException("A locator value is required")
        deadline = time.monotonic() + self._implicit_timeout
        while True:
            result = (root._execute(_FIND_JS, str(by), str(value)) if root is not None
                      else self.execute_script(_FIND_JS, None, str(by), str(value)))
            if not isinstance(result, list) or any(not isinstance(item, AndroidWebElement) for item in result):
                raise WebDriverException("Native element search did not return element references")
            if result or time.monotonic() >= deadline:
                return result
            time.sleep(min(0.1, max(0, deadline-time.monotonic())))

    def find_elements(self, by=By.ID, value=None):
        return self._find(by, value)

    def find_element(self, by=By.ID, value=None):
        result = self.find_elements(by, value)
        if not result:
            raise NoSuchElementException(f"No element matched {by}: {value}")
        return result[0]

    @property
    def current_window_handle(self):
        if self._window is None:
            handle = self._command("current_window")
            if not isinstance(handle, str) or not handle:
                raise NoSuchWindowException("The Android browser has no current window")
            self._window = handle
        return self._window

    @property
    def window_handles(self):
        result = self._command("windows")
        if not isinstance(result, list) or any(not isinstance(handle, str) for handle in result):
            raise WebDriverException("The native kernel returned invalid window handles")
        return result

    @property
    def current_url(self):
        return self._command("url", window=self.current_window_handle)

    @property
    def title(self):
        return self._command("title", window=self.current_window_handle)

    @property
    def page_source(self):
        return self.execute_script("return document.documentElement.outerHTML;")

    def _wait_page_loaded(self):
        deadline = time.monotonic() + self._page_load_timeout
        while True:
            if not self._command("loading", window=self.current_window_handle):
                try:
                    if self.execute_script("return document.readyState;") == "complete":
                        return
                except JavascriptException:
                    pass
            if time.monotonic() >= deadline:
                raise TimeoutException("The Android page did not finish loading before its timeout")
            time.sleep(min(0.1, max(0, deadline-time.monotonic())))

    def get(self, url):
        self.switch_to.default_content()
        self._command("navigate", url=str(url), window=self.current_window_handle,
                      timeoutMs=max(1, int(self._page_load_timeout * 1000)))
        self._wait_page_loaded()

    def refresh(self):
        self.get(self.current_url)

    def back(self):
        self.switch_to.default_content()
        self.execute_script("setTimeout(() => history.back(),0); return true;")
        time.sleep(0.05)
        self._wait_page_loaded()

    def close(self):
        self._command("close_window", window=self.current_window_handle)
        self._window = None
        self.switch_to.default_content()

    def quit(self):
        if not self._closed:
            try:
                self._command("quit")
            finally:
                self._closed = True
                self._window = None
                self._frame = None
                self._frames.clear()

    def implicitly_wait(self, seconds):
        self._implicit_timeout = _seconds(seconds)

    def set_script_timeout(self, seconds):
        self._script_timeout = _seconds(seconds)

    def set_page_load_timeout(self, seconds):
        self._page_load_timeout = _seconds(seconds)

    @property
    def timeouts(self):
        return SimpleNamespace(implicit_wait=self._implicit_timeout,
                               page_load=self._page_load_timeout, script=self._script_timeout)

    def get_cookies(self):
        result = self._command("cookies", window=self.current_window_handle)
        if result is None:
            return []
        if isinstance(result, str):
            return [{"name": part.partition("=")[0].strip(), "value": part.partition("=")[2]}
                    for part in result.split(";") if "=" in part and part.partition("=")[0].strip()]
        if isinstance(result, list) and all(isinstance(item, dict) and "name" in item and "value" in item for item in result):
            return result
        raise WebDriverException("The native kernel returned invalid cookies")

    def get_cookie(self, name):
        return next((cookie for cookie in self.get_cookies() if cookie["name"] == name), None)

    def get_screenshot_as_base64(self):
        result = self._command("screenshot", window=self.current_window_handle)
        result = result.get("data") if isinstance(result, dict) else result
        if not isinstance(result, str):
            raise WebDriverException("The native kernel did not return a PNG screenshot")
        try:
            image = base64.b64decode(result, validate=True)
        except (ValueError, TypeError) as exc:
            raise WebDriverException("The native kernel returned an invalid screenshot") from exc
        if not image.startswith(b"\x89PNG\r\n\x1a\n"):
            raise WebDriverException("The native screenshot is not a PNG")
        return result

    def get_screenshot_as_png(self):
        return base64.b64decode(self.get_screenshot_as_base64())

    def save_screenshot(self, filename):
        image = self.get_screenshot_as_png()
        try:
            Path(filename).write_bytes(image)
        except OSError:
            return False
        return True

    get_screenshot_as_file = save_screenshot

    def execute_cdp_cmd(self, cmd, cmd_args):
        if not isinstance(cmd_args, dict):
            raise InvalidArgumentException("CDP arguments must be a mapping")
        if cmd == "Page.addScriptToEvaluateOnNewDocument":
            source = cmd_args.get("source")
            if not isinstance(source, str):
                raise InvalidArgumentException("A document script source is required")
            result = self._command("add_document_script", source=source)
            return {"identifier": result} if isinstance(result, str) else {}
        if cmd == "Page.captureScreenshot":
            if cmd_args.get("clip") or cmd_args.get("captureBeyondViewport") or cmd_args.get("format", "png") != "png":
                raise UnsupportedCommandException("Android supports PNG viewport screenshots, not CDP clipping or full-page capture")
            return {"data": self.get_screenshot_as_base64()}
        if cmd == "Runtime.evaluate":
            value = self.execute_script("return (0,eval)(arguments[0]);", str(cmd_args.get("expression", "")))
            kind = "boolean" if isinstance(value, bool) else "number" if isinstance(value, (int, float)) else "string" if isinstance(value, str) else "object"
            return {"result": {"type": kind, "value": value}}
        raise UnsupportedCommandException("Desktop CDP command is unavailable in Android WebView: " + str(cmd))


WebDriver = AndroidWebDriver
WebElement = AndroidWebElement
